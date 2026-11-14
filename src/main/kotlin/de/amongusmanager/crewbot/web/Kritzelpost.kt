/*
 * Stats-Website: „Kritzelpost“ – Stille Post mit Zeichnungen (Kilian, 14.11.2026). Jeder schreibt einen Satz, der Nächste
 * zeichnet ihn, der Übernächste beschreibt die Zeichnung, … am Ende werden alle Bücher gemeinsam aufgedeckt.
 * Die Seite dazu ist `seiten/kritzelpost.html` (Ordner seiten/, siehe Pages.kt).
 *
 * Räume leben nur im Speicher (Neustart = weg), ohne Anmeldung: wer einen Raum erstellt oder ihm beitritt, bekommt einen
 * geheimen Schlüssel, mit dem der Browser jede Sekunde den Stand abfragt (kein WebSocket/SSE – der JDK-HttpServer kann
 * kein WebSocket, und Abfragen binden keinen Thread).
 *
 *   GET  /api/kritzelpost                              {ok, rooms}
 *   POST /api/kritzelpost/create  {name,color,face}     → {room,id,key}
 *   POST /api/kritzelpost/join    {room,name,color,face}→ {room,id,key}
 *   GET  /api/kritzelpost/state?room=&key=&v=           Stand aus Sicht dieses Spielers ({same:true}, wenn unverändert)
 *   GET  /api/kritzelpost/drawing?room=&key=&id=        eine Zeichnung, die dieser Spieler gerade sehen darf
 *   POST /api/kritzelpost/act     {room,key,type,…}     settings|start|submit|edit|force|next|react|again|kick|leave|look
 *
 * Zeichnungen sind Strich-Listen ({"ops":[{"t":"s","c":"#rrggbb","w":8,"p":[x,y,…]},{"t":"f","c":…,"x":…,"y":…}]}) auf
 * einer festen Fläche von 800×600 – der Server prüft sie und schreibt sie neu, gespeichert wird nie fremdes HTML.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import java.util.Random

class Kritzelpost(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) {
    class Player(val id: Int, val key: String, var name: String, var color: Int, var face: Int, var lastSeen: Long) {
        var online = true
        var gone = false
        var lastReaction = 0L
    }

    class Entry(val kind: String, val author: Int, val text: String?, val drawing: String?, val missing: Boolean)

    class Reaction(val id: Int, val emoji: Int, val player: Int, val at: Long)

    class Room(val code: String, var lastActive: Long) {
        val players = LinkedHashMap<Int, Player>()
        var nextPlayerId = 1
        var hostId = 0
        var writeSeconds = 60
        var drawSeconds = 120
        var maxSteps = 0
        var phase = LOBBY
        var game = 0
        var order: List<Int> = emptyList()
        var steps = 0
        var step = 0
        var chains: List<MutableList<Entry>> = emptyList()
        val submitted = HashMap<Int, Entry>()
        var deadline: Long? = null
        var stepStarted = 0L
        var revealChain = 0
        var revealShown = 0
        val reactions = ArrayDeque<Reaction>()
        var nextReactionId = 1
        var version = 1
        var drawingBytes = 0L
    }

    private val rooms = HashMap<String, Room>()
    private val calls = HashMap<String, ArrayDeque<Long>>()

    fun handles(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    /** Anzahl der Räume (für Tests und die Statuszeile). */
    @Synchronized
    fun roomCount(): Int = rooms.size

    @Synchronized
    fun handle(request: WebRequest, client: String): WebResponse {
        val now = clock()
        cleanup(now)
        val route = request.path.removePrefix(PATH).trim('/')
        val get = request.method == "GET" || request.method == "HEAD"
        return when (route) {
            "" -> if (get) json(200, obj("ok" to true, "rooms" to rooms.size)) else notAllowed("GET")
            "state" -> if (get) state(request, now) else notAllowed("GET")
            "drawing" -> if (get) drawing(request, now) else notAllowed("GET")
            "create", "join", "act" -> {
                if (request.method != "POST") return notAllowed("POST")
                if (request.header("content-type")?.startsWith("application/json") != true) return fail(415, "Nur JSON")
                if (request.body.size > MAX_BODY) return fail(413, "Zu groß")
                val body = parseJsonOrNull(String(request.body, Charsets.UTF_8)) as? JsonObject ?: return fail(400, "Kein JSON")
                when (route) {
                    "create" -> create(body, client, now)
                    "join" -> join(body, client, now)
                    else -> act(body, now)
                }
            }
            else -> fail(404, "Nicht gefunden")
        }
    }

    // ---------- Räume ----------

    private fun create(body: JsonObject, client: String, now: Long): WebResponse {
        if (limited(client, now)) return fail(429, "Zu viele Anfragen – kurz warten.")
        if (rooms.size >= MAX_ROOMS) return fail(503, "Gerade sind alle Räume belegt. Versuch es gleich nochmal.")
        val name = cleanName(body.str("name")) ?: return fail(400, "Gib dir einen Namen.")
        var code: String
        do {
            code = (1..4).map { CODE_LETTERS[random.nextInt(CODE_LETTERS.length)] }.joinToString("")
        } while (code in rooms)
        val room = Room(code, now)
        rooms[code] = room
        val player = addPlayer(room, name, body, now)
        room.hostId = player.id
        return json(200, obj("room" to code, "id" to player.id, "key" to player.key))
    }

    private fun join(body: JsonObject, client: String, now: Long): WebResponse {
        if (limited(client, now)) return fail(429, "Zu viele Anfragen – kurz warten.")
        val room = rooms[cleanCode(body.str("room"))] ?: return fail(404, "Diesen Raum gibt es nicht (mehr).")
        val name = cleanName(body.str("name")) ?: return fail(400, "Gib dir einen Namen.")
        if (room.players.values.count { !it.gone } >= MAX_PLAYERS) return fail(409, "Der Raum ist voll.")
        var unique = name
        var n = 2
        while (room.players.values.any { !it.gone && it.name.equals(unique, ignoreCase = true) }) unique = "${name.take(MAX_NAME - 2)} ${n++}"
        val player = addPlayer(room, unique, body, now)
        touch(room, now)
        return json(200, obj("room" to room.code, "id" to player.id, "key" to player.key))
    }

    private fun addPlayer(room: Room, name: String, body: JsonObject, now: Long): Player {
        val key = (1..24).map { KEY_LETTERS[random.nextInt(KEY_LETTERS.length)] }.joinToString("")
        val player = Player(room.nextPlayerId++, key, name, look(body.int("color"), COLORS), look(body.int("face"), FACES), now)
        room.players[player.id] = player
        return player
    }

    private fun look(value: Int?, count: Int): Int = if (value != null && value in 0 until count) value else random.nextInt(count)

    private fun touch(room: Room, now: Long) {
        room.version++
        room.lastActive = now
    }

    /** Alte Räume weg, Timer und Anwesenheit nachziehen. */
    private fun cleanup(now: Long) {
        rooms.values.removeIf { room ->
            val idle = now - room.lastActive
            idle > ROOM_IDLE_MS || (room.players.values.none { !it.gone && now - it.lastSeen < OFFLINE_AFTER_MS } && idle > ROOM_EMPTY_MS)
        }
        for (room in rooms.values) tick(room, now)
        if (calls.size > 2000) calls.clear()
    }

    private fun tick(room: Room, now: Long) {
        var changed = false
        for (player in room.players.values) {
            val online = !player.gone && now - player.lastSeen < OFFLINE_AFTER_MS
            if (online != player.online) {
                player.online = online
                changed = true
            }
        }
        val host = room.players[room.hostId]
        if (host == null || host.gone || (!host.online && now - host.lastSeen > HOST_AWAY_MS)) {
            val next = room.players.values.firstOrNull { it.online }
            if (next != null && next.id != room.hostId) {
                room.hostId = next.id
                changed = true
            }
        }
        if (changed) room.version++
        if (room.phase == PLAY) {
            val deadline = room.deadline
            val waiting = room.order.filter { it !in room.submitted }
            val everyoneHereIsDone = room.submitted.isNotEmpty() && waiting.none { room.players[it]?.online == true }
            if (everyoneHereIsDone || (deadline != null && now > deadline + GRACE_MS)) finishStep(room, now)
        }
        while (room.reactions.isNotEmpty() && now - room.reactions.first().at > REACTION_MS) room.reactions.removeFirst()
    }

    // ---------- Spielablauf ----------

    private fun kindOf(step: Int): String = if (step % 2 == 1) DRAW else TEXT

    private fun start(room: Room, now: Long) {
        val players = room.players.values.filter { it.online }.map { it.id }.shuffled(random)
        room.game++
        room.order = players
        room.steps = if (room.maxSteps in 2 until players.size) room.maxSteps else players.size
        room.step = 0
        room.chains = players.map { ArrayList<Entry>() }
        room.submitted.clear()
        room.drawingBytes = 0
        room.reactions.clear()
        room.phase = PLAY
        beginStep(room, now)
    }

    private fun beginStep(room: Room, now: Long) {
        val seconds = if (kindOf(room.step) == DRAW) room.drawSeconds else room.writeSeconds
        room.stepStarted = now
        room.deadline = if (seconds > 0) now + seconds * 1000L else null
    }

    /** Buch, an dem der Spieler an Stelle [index] in diesem Schritt arbeitet. */
    private fun chainOf(room: Room, index: Int, step: Int = room.step): Int = Math.floorMod(index - step, room.order.size)

    private fun finishStep(room: Room, now: Long) {
        val kind = kindOf(room.step)
        for ((index, playerId) in room.order.withIndex()) {
            val chain = room.chains[chainOf(room, index)]
            chain += room.submitted[playerId] ?: when {
                kind == DRAW -> Entry(DRAW, playerId, null, EMPTY_DRAWING, missing = true)
                room.step == 0 -> Entry(TEXT, playerId, FALLBACK_PROMPTS[random.nextInt(FALLBACK_PROMPTS.size)], null, missing = true)
                else -> Entry(TEXT, playerId, FALLBACK_GUESSES[random.nextInt(FALLBACK_GUESSES.size)], null, missing = true)
            }
        }
        room.submitted.clear()
        room.step++
        if (room.step >= room.steps) {
            room.phase = REVEAL
            room.deadline = null
            room.revealChain = 0
            room.revealShown = 0
        } else {
            beginStep(room, now)
        }
        touch(room, now)
    }

    private fun act(body: JsonObject, now: Long): WebResponse {
        val room = rooms[cleanCode(body.str("room"))] ?: return fail(404, "Diesen Raum gibt es nicht (mehr).")
        val me = room.players.values.firstOrNull { it.key == body.str("key") && !it.gone } ?: return fail(403, "Du bist nicht (mehr) in diesem Raum.")
        me.lastSeen = now
        val host = me.id == room.hostId
        fun hostOnly(): WebResponse = fail(403, "Das darf nur der Host.")
        when (body.str("type")) {
            "look" -> {
                body.int("color")?.takeIf { it in 0 until COLORS }?.let { me.color = it }
                body.int("face")?.takeIf { it in 0 until FACES }?.let { me.face = it }
            }
            "settings" -> {
                if (!host) return hostOnly()
                if (room.phase != LOBBY) return fail(409, "Das Spiel läuft schon.")
                body.int("write")?.takeIf { it in WRITE_OPTIONS }?.let { room.writeSeconds = it }
                body.int("draw")?.takeIf { it in DRAW_OPTIONS }?.let { room.drawSeconds = it }
                body.int("max")?.takeIf { it == 0 || it in 2..MAX_PLAYERS }?.let { room.maxSteps = it }
            }
            "start" -> {
                if (!host) return hostOnly()
                if (room.phase != LOBBY) return fail(409, "Das Spiel läuft schon.")
                if (room.players.values.count { it.online } < MIN_PLAYERS) return fail(409, "Ihr braucht mindestens $MIN_PLAYERS Spieler.")
                start(room, now)
            }
            "submit" -> {
                if (room.phase != PLAY) return fail(409, "Gerade wird nichts abgegeben.")
                val index = room.order.indexOf(me.id)
                if (index < 0) return fail(409, "Du bist in dieser Runde nicht dabei.")
                if (body.int("step") != room.step || body.int("game") != room.game) return fail(409, "Zu spät – die Runde ist schon weiter.")
                val entry = if (kindOf(room.step) == DRAW) {
                    val drawing = cleanDrawing(body["drawing"]) ?: return fail(400, "Die Zeichnung ist kaputt oder zu groß.")
                    val old = room.submitted[me.id]?.drawing?.length ?: 0
                    if (totalDrawingBytes() - old + drawing.length > MAX_TOTAL_DRAWING_BYTES) return fail(503, "Der Server ist voll mit Kunst. Zeichne etwas kleiner.")
                    room.drawingBytes += drawing.length - old
                    Entry(DRAW, me.id, null, drawing, missing = false)
                } else {
                    val text = cleanText(body.str("text")) ?: return fail(400, "Schreib erst etwas.")
                    Entry(TEXT, me.id, text, null, missing = false)
                }
                room.submitted[me.id] = entry
                touch(room, now)
                tick(room, now)
                return json(200, obj("ok" to true))
            }
            "edit" -> {
                if (room.phase != PLAY) return fail(409, "Gerade wird nichts abgegeben.")
                room.submitted.remove(me.id)?.let { room.drawingBytes -= it.drawing?.length ?: 0 }
            }
            "force" -> {
                if (!host) return hostOnly()
                if (room.phase != PLAY) return fail(409, "Gerade läuft keine Runde.")
                // erst nach ein paar Sekunden, damit ein Doppelklick nicht zwei Schritte überspringt
                if (now - room.stepStarted < 3000) return fail(409, "Moment noch.")
                finishStep(room, now)
                return json(200, obj("ok" to true))
            }
            "next" -> {
                if (!host) return hostOnly()
                if (room.phase != REVEAL) return fail(409, "Gerade wird nichts aufgedeckt.")
                if (body.int("chain") != room.revealChain || body.int("shown") != room.revealShown) return json(200, obj("ok" to true))
                when {
                    room.revealShown < room.chains[room.revealChain].size -> room.revealShown++
                    room.revealChain < room.chains.size - 1 -> {
                        room.revealChain++
                        room.revealShown = 0
                    }
                    else -> room.phase = END
                }
            }
            "react" -> {
                if (room.phase != REVEAL && room.phase != END) return fail(409, "Gerade nicht.")
                val emoji = body.int("emoji")?.takeIf { it in 0 until EMOJIS } ?: return fail(400, "Unbekannt")
                if (now - me.lastReaction < 250) return json(200, obj("ok" to true))
                me.lastReaction = now
                room.reactions += Reaction(room.nextReactionId++, emoji, me.id, now)
                while (room.reactions.size > 40) room.reactions.removeFirst()
            }
            "again" -> {
                if (!host) return hostOnly()
                if (room.phase != END && room.phase != REVEAL) return fail(409, "Das Spiel läuft noch.")
                room.phase = LOBBY
                room.chains = emptyList()
                room.order = emptyList()
                room.submitted.clear()
                room.drawingBytes = 0
                room.deadline = null
                room.players.values.removeIf { it.gone }
            }
            "kick" -> {
                if (!host) return hostOnly()
                val target = room.players[body.int("player")] ?: return fail(404, "Den Spieler gibt es nicht.")
                if (target.id == me.id) return fail(400, "Dich selbst?")
                leave(room, target)
            }
            "leave" -> leave(room, me)
            else -> return fail(400, "Unbekannte Aktion")
        }
        touch(room, now)
        tick(room, now)
        return json(200, obj("ok" to true))
    }

    private fun leave(room: Room, player: Player) {
        player.gone = true
        player.online = false
        // in der Lobby ganz weg; im Spiel bleibt der Name an den Einträgen stehen
        if (room.phase == LOBBY) room.players.remove(player.id)
    }

    private fun totalDrawingBytes(): Long = rooms.values.sumOf { it.drawingBytes }

    // ---------- Stand ----------

    private fun member(request: WebRequest): Pair<Room, Player>? {
        val room = rooms[cleanCode(request.query["room"])] ?: return null
        val player = room.players.values.firstOrNull { it.key == request.query["key"] && !it.gone } ?: return null
        return room to player
    }

    private fun state(request: WebRequest, now: Long): WebResponse {
        val (room, me) = member(request) ?: return fail(404, "Du bist nicht (mehr) in diesem Raum.")
        me.lastSeen = now
        if (!me.online) tick(room, now)
        room.lastActive = now
        val left = room.deadline?.let { (it - now).coerceAtLeast(0) }
        if (request.query["v"]?.toIntOrNull() == room.version) return json(200, obj("same" to true, "v" to room.version, "left" to left))
        return json(200, view(room, me, now, left))
    }

    private fun entryJson(room: Room, chain: Int, step: Int, entry: Entry): JsonObject {
        val author = room.players[entry.author]
        return obj(
            "kind" to entry.kind,
            "author" to (author?.name ?: "?"),
            "color" to (author?.color ?: 0),
            "face" to (author?.face ?: 0),
            "missing" to entry.missing,
            "text" to entry.text,
            "drawing" to if (entry.drawing != null) drawingId(room, chain, step) else null,
        )
    }

    private fun drawingId(room: Room, chain: Int, step: Int) = "${room.game}-$chain-$step"

    private fun view(room: Room, me: Player, now: Long, left: Long?): JsonObject {
        val index = room.order.indexOf(me.id)
        val fields = LinkedHashMap<String, Any?>()
        fields["v"] = room.version
        fields["room"] = room.code
        fields["phase"] = room.phase
        fields["game"] = room.game
        fields["me"] = obj("id" to me.id, "host" to (me.id == room.hostId), "playing" to (index >= 0))
        fields["players"] = room.players.values.filter { !it.gone || it.id in room.order }.map { p ->
            obj(
                "id" to p.id, "name" to p.name, "color" to p.color, "face" to p.face, "host" to (p.id == room.hostId),
                "online" to p.online, "playing" to (p.id in room.order), "done" to (p.id in room.submitted),
            )
        }
        fields["settings"] = obj("write" to room.writeSeconds, "draw" to room.drawSeconds, "max" to room.maxSteps)
        fields["limits"] = obj("min" to MIN_PLAYERS, "max" to MAX_PLAYERS, "text" to MAX_TEXT, "drawing" to MAX_DRAWING_BYTES)
        when (room.phase) {
            PLAY -> {
                val kind = kindOf(room.step)
                fields["step"] = room.step
                fields["steps"] = room.steps
                fields["kind"] = if (kind == DRAW) "draw" else if (room.step == 0) "write" else "describe"
                fields["left"] = left
                fields["total"] = room.deadline?.let { it - room.stepStarted }
                if (index >= 0) {
                    val chain = chainOf(room, index)
                    val previous = room.chains[chain].lastOrNull()
                    fields["task"] = previous?.let { obj("text" to it.text, "drawing" to if (it.drawing != null) drawingId(room, chain, room.step - 1) else null) }
                    val mine = room.submitted[me.id]
                    fields["mine"] = obj("done" to (mine != null), "text" to mine?.text)
                }
            }
            REVEAL -> {
                val chain = room.chains[room.revealChain]
                val owner = room.players[room.order[room.revealChain]]
                fields["reveal"] = obj(
                    "chain" to room.revealChain,
                    "chains" to room.chains.size,
                    "shown" to room.revealShown,
                    "total" to chain.size,
                    "owner" to obj("name" to (owner?.name ?: "?"), "color" to (owner?.color ?: 0), "face" to (owner?.face ?: 0)),
                    "entries" to chain.take(room.revealShown).mapIndexed { step, entry -> entryJson(room, room.revealChain, step, entry) },
                )
            }
            END -> fields["albums"] = room.chains.mapIndexed { c, chain ->
                val owner = room.players[room.order[c]]
                obj(
                    "owner" to obj("name" to (owner?.name ?: "?"), "color" to (owner?.color ?: 0), "face" to (owner?.face ?: 0)),
                    "entries" to chain.mapIndexed { step, entry -> entryJson(room, c, step, entry) },
                )
            }
        }
        if (room.phase == REVEAL || room.phase == END) {
            fields["reactions"] = room.reactions.map { r ->
                obj("id" to r.id, "emoji" to r.emoji, "name" to (room.players[r.player]?.name ?: "?"), "age" to (now - r.at))
            }
        }
        return JsonObject(fields.mapValues { it.value.toJson() })
    }

    private fun drawing(request: WebRequest, now: Long): WebResponse {
        val (room, me) = member(request) ?: return fail(404, "Du bist nicht (mehr) in diesem Raum.")
        me.lastSeen = now
        val parts = (request.query["id"] ?: "").split('-').map { it.toIntOrNull() ?: return fail(404, "Nicht gefunden") }
        if (parts.size != 3 || parts[0] != room.game) return fail(404, "Nicht gefunden")
        val (_, chain, step) = parts
        val allowed = when (room.phase) {
            END -> true
            REVEAL -> chain < room.revealChain || (chain == room.revealChain && step < room.revealShown)
            PLAY -> {
                val index = room.order.indexOf(me.id)
                index >= 0 && chain == chainOf(room, index) && step == room.step - 1
            }
            else -> false
        }
        val drawing = room.chains.getOrNull(chain)?.getOrNull(step)?.drawing
        if (!allowed || drawing == null) return fail(404, "Nicht gefunden")
        // die Adresse enthält Spiel/Buch/Schritt – der Inhalt ändert sich nie
        return WebResponse(200, StatsServer.TYPE_JSON, drawing.toByteArray(Charsets.UTF_8), listOf("Cache-Control" to "private, max-age=3600"))
    }

    // ---------- Helfer ----------

    private fun limited(client: String, now: Long): Boolean {
        val times = calls.getOrPut(client) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
        if (times.size >= JOINS_PER_MINUTE) return true
        times += now
        return false
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, StatsServer.TYPE_JSON, body.toString().toByteArray(Charsets.UTF_8))

    private fun fail(status: Int, message: String) = json(status, obj("error" to message))

    private fun notAllowed(allow: String) = WebResponse(405, StatsServer.TYPE_JSON, obj("error" to "Nur $allow").toString().toByteArray(), listOf("Allow" to allow))

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    companion object {
        const val PATH = "/api/kritzelpost"
        const val LOBBY = "lobby"
        const val PLAY = "play"
        const val REVEAL = "reveal"
        const val END = "end"
        const val TEXT = "text"
        const val DRAW = "draw"

        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 12
        const val MAX_ROOMS = 20
        const val MAX_NAME = 16
        const val MAX_TEXT = 120
        const val COLORS = 12
        const val FACES = 8
        const val EMOJIS = 8
        const val WIDTH = 800
        const val HEIGHT = 600
        const val MAX_DRAWING_BYTES = 150 * 1024
        const val MAX_TOTAL_DRAWING_BYTES = 48L * 1024 * 1024
        const val MAX_OPS = 2500

        /** Größte Anfrage (eine Zeichnung als JSON samt Drumherum). */
        const val MAX_BODY = 256 * 1024
        const val JOINS_PER_MINUTE = 20
        const val OFFLINE_AFTER_MS = 12_000L
        const val HOST_AWAY_MS = 30_000L
        const val GRACE_MS = 3_000L
        const val REACTION_MS = 6_000L
        const val ROOM_IDLE_MS = 2 * 60 * 60 * 1000L
        const val ROOM_EMPTY_MS = 10 * 60 * 1000L
        val WRITE_OPTIONS = setOf(0, 30, 45, 60, 90)
        val DRAW_OPTIONS = setOf(0, 60, 90, 120, 180)

        const val EMPTY_DRAWING = """{"ops":[]}"""
        private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val KEY_LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val COLOR = Regex("#[0-9a-f]{6}")

        val FALLBACK_PROMPTS = listOf(
            "Ein Crewmate, der im Vent stecken bleibt",
            "Ein Dackel auf einem Skateboard, der zu spät zur Arbeit kommt",
            "Oma gewinnt das Fortnite-Turnier",
            "Ein Toaster mit Existenzkrise",
            "Ein Hai beim Zahnarzt",
            "Der Impostor macht ganz unauffällig seine Tasks",
            "Eine Taube klaut einen Döner",
            "Ein Creeper bei seinem ersten Date",
        )
        val FALLBACK_GUESSES = listOf(
            "Keine Ahnung, was das sein soll. Kunst?",
            "Irgendwas mit Beinen. Glaube ich.",
            "Ein sehr verwirrter Fleck",
            "Das, was passiert, wenn man mit geschlossenen Augen malt",
        )

        fun cleanCode(raw: String?): String = (raw ?: "").uppercase().filter { it in 'A'..'Z' }.take(4)

        private fun visible(text: String): String =
            text.map { if (it.isISOControl() || it == ' ' || it == ' ' || it == '​' || it == '‮') ' ' else it }
                .joinToString("").replace(Regex("\\s+"), " ").trim()

        fun cleanName(raw: String?): String? = visible(raw ?: "").take(MAX_NAME).trim().ifEmpty { null }

        fun cleanText(raw: String?): String? = visible(raw ?: "").take(MAX_TEXT).trim().ifEmpty { null }

        /**
         * Prüft eine Zeichnung und schreibt sie neu (nur bekannte Felder, ganze Zahlen auf der Fläche) – null, wenn sie nicht
         * dem Format entspricht oder zu groß ist.
         */
        fun cleanDrawing(raw: Any?): String? {
            val ops = ((raw as? JsonObject)?.get("ops") as? JsonArray) ?: return null
            if (ops.size > MAX_OPS) return null
            val out = StringBuilder("""{"ops":[""")
            for ((i, element) in ops.withIndex()) {
                val op = element as? JsonObject ?: return null
                val color = (op["c"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()?.takeIf { COLOR.matches(it) } ?: return null
                if (i > 0) out.append(',')
                when ((op["t"] as? JsonPrimitive)?.content) {
                    "s" -> {
                        val width = number(op["w"])?.takeIf { it in 1..80 } ?: return null
                        val points = op["p"] as? JsonArray ?: return null
                        if (points.size < 2 || points.size % 2 != 0) return null
                        out.append("""{"t":"s","c":"""").append(color).append("""","w":""").append(width).append(""","p":[""")
                        for ((j, point) in points.withIndex()) {
                            val limit = if (j % 2 == 0) WIDTH else HEIGHT
                            val value = number(point)?.takeIf { it in -100..limit + 100 } ?: return null
                            if (j > 0) out.append(',')
                            out.append(value)
                        }
                        out.append("]}")
                    }
                    "f" -> {
                        val x = number(op["x"])?.takeIf { it in 0 until WIDTH } ?: return null
                        val y = number(op["y"])?.takeIf { it in 0 until HEIGHT } ?: return null
                        out.append("""{"t":"f","c":"""").append(color).append("""","x":""").append(x).append(""","y":""").append(y).append('}')
                    }
                    else -> return null
                }
                if (out.length > MAX_DRAWING_BYTES) return null
            }
            out.append("]}")
            return if (out.length > MAX_DRAWING_BYTES) null else out.toString()
        }

        private fun number(element: Any?): Int? {
            val primitive = (element as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
            if (primitive.booleanOrNull != null) return null
            val value = primitive.doubleOrNull ?: return null
            return if (value.isFinite() && value in -100000.0..100000.0) Math.round(value).toInt() else null
        }
    }
}
