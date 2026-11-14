/*
 * Stats-Website: „Geheimfunk“ – eigenes Wort-Ratespiel für zwei Teams (Seite /spiele/geheimfunk.html, Dateien in
 * src/main/resources/web-spiele). 25 Wörter, je Team ein Funker (kennt die Farben, gibt ein Hinweiswort + Zahl) und
 * Spürnasen (raten). Wer zuerst alle eigenen Wörter findet, gewinnt – wer die Bombe aufdeckt, verliert sofort.
 *
 *   GET  /api/geheimfunk          Wortpakete + Anzahl offener Räume
 *   POST /api/geheimfunk/create   {name, avatar}                → {room, token}
 *   POST /api/geheimfunk/join     {room, name, avatar, token?}  → {room, token}   (mit token: alter Platz)
 *   GET  /api/geheimfunk/stream   ?room=&token=  Server-Sent Events: state (ganzer Stand, je Spieler eigene Sicht), fx, bye
 *   POST /api/geheimfunk/act      {room, token, do, …}          Spielzüge, siehe [WordGame.act]
 *
 * Ohne Anmeldung: wer den Raum-Code kennt, spielt mit; der Platz hängt am geheimen token (liegt im Browser).
 * Die Farben der verdeckten Karten gehen nur an Funker (und an alle, wenn die Runde vorbei ist).
 * Alles nur im Speicher: Neustart der Website = Räume weg. Kein WebSocket (JDK-HttpServer) → SSE + POST; die Streams
 * belegen keinen Server-Thread (der Handler gibt die Verbindung ab, geschrieben wird aus einem kleinen eigenen Pool).
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** Ein Raum mit seinen Regeln – ohne HTTP, getestet in WordGameTest. Aufrufer synchronisieren auf den Raum. */
class WordRoom(val code: String, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, CLUE, GUESS, OVER }

    class Player(val id: String, val token: String, var name: String, var avatar: Int) {
        /** "a" | "b" | "" (schaut zu) */
        var team = ""
        /** "chief" (Funker) | "agent" (Spürnase) */
        var role = "agent"
        /** Hat in dieser Runde die Farben gesehen – darf bis zum Rundenende nicht mehr raten. */
        var sawKey = false
        var offlineSince: Instant? = null
        internal val clients = ArrayList<WordGame.Client>()
        internal val actions = ArrayDeque<Instant>()
        val online: Boolean get() = clients.isNotEmpty()
    }

    class Card(val word: String, val color: String) {
        var revealed = false
        val tips = LinkedHashSet<String>()
    }

    class Clue(val word: String, val count: Int, val by: String)

    val players = LinkedHashMap<String, Player>()
    var hostId = ""
    var phase = Phase.LOBBY
    var cards: List<Card> = emptyList()
    var turn = "a"
    var clue: Clue? = null
    /** Verbleibende Versuche, -1 = beliebig viele (Hinweis mit 0 oder ∞). */
    var guessesLeft = 0
    var winner = ""
    var reason = ""
    var round = 0
    val wins = mutableMapOf("a" to 0, "b" to 0)
    val log = ArrayDeque<JsonObject>()
    var packs: Set<String> = setOf(WordGameWords.CLASSIC.id)
    var custom: List<String> = emptyList()
    var lastActive: Instant = Instant.now()
    private val used = HashSet<String>()

    fun left(team: String): Int = cards.count { it.color == team && !it.revealed }

    fun pool(): List<String> = (packs.mapNotNull { WordGameWords.pack(it) }.flatMap { it.words } + custom).distinct()

    private fun other(team: String) = if (team == "a") "b" else "a"

    private fun members(team: String, role: String) = players.values.filter { it.team == team && it.role == role }

    private fun say(kind: String, vararg more: Pair<String, Any?>) {
        log.addLast(obj("k" to kind, *more))
        while (log.size > LOG_LIMIT) log.removeFirst()
    }

    /** Warum die Runde nicht starten kann, sonst null. */
    fun startProblem(): String? = when {
        pool().size < CARDS -> "Zu wenige Wörter – wähle ein Wortpaket oder trage mindestens $CARDS eigene Wörter ein."
        TEAMS.any { members(it, "chief").isEmpty() } -> "Jedes Team braucht einen Funker."
        TEAMS.any { members(it, "agent").isEmpty() } -> "Jedes Team braucht mindestens eine Spürnase."
        else -> null
    }

    fun seat(player: Player, team: String, role: String): String? {
        if (team != "" && team !in TEAMS) return "Unbekanntes Team"
        if (role != "chief" && role != "agent") return "Unbekannte Rolle"
        val playing = phase == Phase.CLUE || phase == Phase.GUESS
        val wantsChief = team != "" && role == "chief"
        if (playing && player.sawKey && !(wantsChief && team == player.team)) {
            return "Du kennst die Farben schon – bis zum Rundenende bleibst du Funker deines Teams."
        }
        if (wantsChief && members(team, "chief").any { it !== player } && members(team, "chief").size >= MAX_CHIEFS) {
            return "Dieses Team hat schon $MAX_CHIEFS Funker."
        }
        cards.forEach { it.tips.remove(player.id) }
        player.team = team
        player.role = if (team == "") "agent" else role
        if (playing && wantsChief && !player.sawKey) {
            player.sawKey = true
            say("chief", "team" to team, "name" to player.name)
        }
        return null
    }

    fun start(player: Player): String? {
        if (player.id != hostId) return "Nur der Host startet die Runde."
        if (phase != Phase.LOBBY && phase != Phase.OVER) return "Die Runde läuft schon."
        startProblem()?.let { return it }
        val pool = pool()
        var fresh = pool.filter { it !in used }
        if (fresh.size < CARDS) {
            used.clear()
            fresh = pool
        }
        val words = fresh.shuffled(random).take(CARDS)
        used += words
        turn = if (random.nextBoolean()) "a" else "b"
        val colors = (List(FIRST_TEAM_CARDS) { turn } + List(FIRST_TEAM_CARDS - 1) { other(turn) } +
            List(CARDS - 2 * FIRST_TEAM_CARDS) { "n" } + "x").shuffled(random)
        cards = words.mapIndexed { index, word -> Card(word, colors[index]) }
        phase = Phase.CLUE
        clue = null
        guessesLeft = 0
        winner = ""
        reason = ""
        round++
        log.clear()
        players.values.forEach { it.sawKey = it.team != "" && it.role == "chief" }
        say("start", "team" to turn, "round" to round)
        return null
    }

    fun giveClue(player: Player, rawWord: String, count: Int): String? {
        if (phase != Phase.CLUE) return "Gerade wird kein Hinweis erwartet."
        if (player.team != turn || player.role != "chief") return "Den Hinweis gibt der Funker des Teams, das dran ist."
        val word = rawWord.trim().uppercase()
        if (word.length !in 1..CLUE_LENGTH) return "Der Hinweis ist ein Wort mit höchstens $CLUE_LENGTH Zeichen."
        if (!word.all { it.isLetterOrDigit() || it == '-' }) return "Der Hinweis ist genau ein Wort (Buchstaben, Ziffern, Bindestrich)."
        if (cards.any { !it.revealed && it.word == word }) return "Der Hinweis darf kein Wort sein, das noch offen auf dem Tisch liegt."
        if (count !in -1..MAX_COUNT) return "Die Zahl liegt zwischen 0 und $MAX_COUNT (oder ∞)."
        clue = Clue(word, count, player.name)
        guessesLeft = if (count <= 0) -1 else count + 1
        phase = Phase.GUESS
        say("clue", "team" to turn, "name" to player.name, "word" to word, "n" to count)
        return null
    }

    private fun guesser(player: Player, index: Int): String? {
        if (phase != Phase.GUESS) return "Gerade wird nicht geraten."
        if (player.team != turn || player.role != "agent") return "Raten dürfen nur die Spürnasen des Teams, das dran ist."
        if (index !in cards.indices || cards[index].revealed) return "Diese Karte geht nicht."
        return null
    }

    /** Karte antippen („ich glaube, die ist es“) – alle sehen es, aufgedeckt wird noch nichts. */
    fun tip(player: Player, index: Int): String? {
        guesser(player, index)?.let { return it }
        val tips = cards[index].tips
        if (!tips.remove(player.id)) tips += player.id
        return null
    }

    fun reveal(player: Player, index: Int): String? {
        guesser(player, index)?.let { return it }
        val card = cards[index]
        card.revealed = true
        card.tips.clear()
        say("guess", "team" to turn, "name" to player.name, "word" to card.word, "color" to card.color)
        when (card.color) {
            "x" -> finish(other(turn), "bomb")
            turn -> when {
                left(turn) == 0 -> finish(turn, "all")
                guessesLeft > 0 && --guessesLeft == 0 -> endTurn("limit")
            }
            "n" -> endTurn("neutral")
            else -> if (left(card.color) == 0) finish(card.color, "gift") else endTurn("enemy")
        }
        return null
    }

    fun pass(player: Player): String? {
        if (phase != Phase.GUESS) return "Gerade wird nicht geraten."
        if (player.team != turn || player.role != "agent") return "Den Zug beenden die Spürnasen des Teams, das dran ist."
        say("pass", "team" to turn, "name" to player.name)
        endTurn("pass")
        return null
    }

    private fun endTurn(why: String) {
        cards.forEach { it.tips.clear() }
        turn = other(turn)
        phase = Phase.CLUE
        clue = null
        guessesLeft = 0
        if (why != "pass") say("turn", "team" to turn, "why" to why)
    }

    private fun finish(team: String, why: String) {
        cards.forEach { it.tips.clear() }
        phase = Phase.OVER
        winner = team
        reason = why
        wins[team] = (wins[team] ?: 0) + 1
        say("end", "team" to team, "why" to why)
    }

    /** Zurück in die Lobby (Runde abbrechen bzw. nach dem Ende neu aufstellen). */
    fun toLobby(player: Player): String? {
        if (player.id != hostId) return "Das macht der Host."
        phase = Phase.LOBBY
        cards = emptyList()
        clue = null
        winner = ""
        reason = ""
        log.clear()
        players.values.forEach { it.sawKey = false }
        return null
    }

    /** Alle Mitspieler (nicht die Zuschauer, wenn es sonst genug gibt) zufällig auf zwei Teams, je ein Funker. */
    fun shuffle(player: Player): String? {
        if (player.id != hostId) return "Das macht der Host."
        if (phase != Phase.LOBBY) return "Teams mischen geht nur in der Lobby."
        val all = players.values.shuffled(random)
        all.forEachIndexed { index, p ->
            p.team = TEAMS[index % 2]
            p.role = if (index < 2) "chief" else "agent"
        }
        return null
    }

    fun settings(player: Player, packIds: List<String>, customText: String?): String? {
        if (player.id != hostId) return "Das macht der Host."
        if (phase != Phase.LOBBY) return "Wörter ändern geht nur in der Lobby."
        packs = packIds.filter { WordGameWords.pack(it) != null }.toSet()
        if (customText != null) custom = WordGameWords.custom(customText)
        return null
    }

    fun remove(player: Player) {
        players.remove(player.id)
        cards.forEach { it.tips.remove(player.id) }
        if (hostId == player.id) pickHost()
    }

    fun pickHost() {
        hostId = (players.values.firstOrNull { it.online } ?: players.values.firstOrNull())?.id ?: ""
    }

    /** Der ganze Stand, so wie [viewer] ihn sehen darf (null = Zuschauer ohne Platz). */
    fun view(viewer: Player?): JsonObject {
        val seesKey = phase == Phase.OVER || (viewer != null && viewer.sawKey && phase != Phase.LOBBY)
        return obj(
            "room" to code,
            "phase" to phase.name.lowercase(),
            "round" to round,
            "turn" to turn,
            "you" to viewer?.id,
            "host" to hostId,
            "key" to seesKey,
            "players" to players.values.map { p ->
                obj("id" to p.id, "name" to p.name, "avatar" to p.avatar, "team" to p.team, "role" to p.role, "online" to p.online)
            },
            "cards" to cards.map { card ->
                obj(
                    "w" to card.word,
                    "r" to card.revealed,
                    "c" to card.color.takeIf { card.revealed || seesKey },
                    "tips" to card.tips.toList(),
                )
            },
            "left" to obj("a" to left("a"), "b" to left("b")),
            "wins" to obj("a" to (wins["a"] ?: 0), "b" to (wins["b"] ?: 0)),
            "clue" to clue?.let { obj("word" to it.word, "n" to it.count, "by" to it.by) },
            "guesses" to guessesLeft,
            "winner" to winner,
            "reason" to reason,
            "log" to log.toList(),
            "packs" to packs.toList(),
            "custom" to (if (viewer?.id == hostId) custom.joinToString(", ") else null),
            "customCount" to custom.size,
            "pool" to pool().size,
            "problem" to (if (phase == Phase.LOBBY || phase == Phase.OVER) startProblem() else null),
        )
    }

    companion object {
        val TEAMS = listOf("a", "b")
        const val CARDS = 25
        const val FIRST_TEAM_CARDS = 9
        const val MAX_COUNT = 9
        const val CLUE_LENGTH = 24
        const val MAX_CHIEFS = 2
        const val LOG_LIMIT = 150
    }
}

class WordGame(
    private val clock: () -> Instant = Instant::now,
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(WordGame::class.java)
    private val secure = SecureRandom()
    private val rooms = HashMap<String, WordRoom>() // unter synchronized(rooms); Spielstand unter synchronized(room)
    private val creates = HashMap<String, ArrayDeque<Instant>>()
    private var streams = 0
    private var writers: ExecutorService? = null
    private var ticker: ScheduledExecutorService? = null

    /** Ein offener Stream. Geschrieben wird nie im Thread, der den Spielzug macht: [push] legt nur ab. */
    internal inner class Client(val room: WordRoom, val player: WordRoom.Player, private val out: OutputStream, private val onClose: () -> Unit) {
        private val state = AtomicReference<String?>(null)
        private val extra = ConcurrentLinkedQueue<String>()
        private val busy = AtomicBoolean(false)
        private val closing = AtomicBoolean(false)
        val closed = AtomicBoolean(false)

        fun pushState(text: String) {
            state.set(text)
            wake()
        }

        fun push(text: String) {
            if (extra.size < 40) extra += text
            wake()
        }

        /** Letzte Nachricht schicken, danach schließen. */
        fun closeAfter(text: String) {
            push(text)
            closing.set(true)
            wake()
        }

        private fun wake() {
            if (closed.get() || !busy.compareAndSet(false, true)) return
            val pool = writers
            if (pool == null) {
                busy.set(false)
                drain()
            } else {
                pool.execute { drain() }
            }
        }

        private fun drain() {
            try {
                while (true) {
                    val next = state.getAndSet(null) ?: extra.poll() ?: break
                    out.write(next.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                if (closing.get()) close()
            } catch (e: Exception) {
                close()
            } finally {
                busy.set(false)
            }
            if (!closed.get() && (closing.get() || state.get() != null || extra.isNotEmpty())) wake()
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { out.close() }
            runCatching { onClose() }
            dropped(this)
        }
    }

    // ---------- HTTP ----------

    fun handle(request: WebRequest, client: String): WebResponse {
        if (request.method == "POST" && request.header("content-type")?.startsWith("application/json") != true) {
            return json(415, obj("error" to "Nur JSON")) // kein Formular von fremden Seiten
        }
        val body = if (request.method == "POST") parseBody(request) ?: return json(400, obj("error" to "Ungültige Eingabe")) else null
        return when (request.path) {
            PATH -> if (request.method == "GET" || request.method == "HEAD") info() else notAllowed("GET")
            "$PATH/create" -> if (body != null) create(body, client) else notAllowed("POST")
            "$PATH/join" -> if (body != null) join(body, client) else notAllowed("POST")
            "$PATH/act" -> if (body != null) act(body) else notAllowed("POST")
            else -> json(404, obj("error" to "Nicht gefunden"))
        }
    }

    private fun info(): WebResponse = json(200, obj(
        "packs" to WordGameWords.PACKS.map { obj("id" to it.id, "name" to it.name, "about" to it.about, "count" to it.words.size) },
        "rooms" to synchronized(rooms) { rooms.size },
    ))

    private fun create(body: JsonObject, client: String): WebResponse {
        val name = cleanName(body.str("name")) ?: return json(400, obj("error" to "Gib dir einen Namen (1–$NAME_LENGTH Zeichen)."))
        val now = clock()
        val room = synchronized(rooms) {
            if (!allowCreate(client, now)) return json(429, obj("error" to "Zu viele neue Räume – kurz warten."))
            if (rooms.size >= MAX_ROOMS) return json(503, obj("error" to "Gerade sind alle Räume belegt – versuch es gleich nochmal."))
            var code: String
            do {
                code = String(CharArray(CODE_LENGTH) { CODE_CHARS[secure.nextInt(CODE_CHARS.length)] })
            } while (code in rooms)
            WordRoom(code, random).also { rooms[code] = it }
        }
        val player = synchronized(room) {
            room.lastActive = now
            newPlayer(room, name, body.int("avatar") ?: 0).also { room.hostId = it.id }
        }
        log.info("Geheimfunk: Raum {} eröffnet ({} offen)", room.code, synchronized(rooms) { rooms.size })
        return json(200, obj("room" to room.code, "token" to player.token))
    }

    private fun join(body: JsonObject, client: String): WebResponse {
        val room = roomOf(body) ?: return json(404, obj("error" to "Diesen Raum gibt es nicht (mehr).", "gone" to true))
        val now = clock()
        val token = body.str("token")
        val player = synchronized(room) {
            room.lastActive = now
            room.players.values.firstOrNull { token != null && it.token == token }?.let { return@synchronized it }
            val name = cleanName(body.str("name")) ?: return json(400, obj("error" to "Gib dir einen Namen (1–$NAME_LENGTH Zeichen)."))
            if (room.players.size >= MAX_PLAYERS) return json(409, obj("error" to "Der Raum ist voll."))
            if (!synchronized(rooms) { allowCreate(client, now) }) return json(429, obj("error" to "Zu viele Versuche – kurz warten."))
            val player = newPlayer(room, name, body.int("avatar") ?: 0)
            if (room.hostId.isEmpty()) room.hostId = player.id
            player
        }
        broadcast(room)
        return json(200, obj("room" to room.code, "token" to player.token))
    }

    private fun newPlayer(room: WordRoom, name: String, avatar: Int): WordRoom.Player {
        var unique = name
        var n = 2
        while (room.players.values.any { it.name.equals(unique, ignoreCase = true) }) unique = "${name.take(NAME_LENGTH - 2)} ${n++}"
        var id: String
        do {
            id = token(4)
        } while (id in room.players)
        val player = WordRoom.Player(id, token(18), unique, avatar.coerceIn(0, AVATARS - 1))
        player.offlineSince = clock()
        room.players[id] = player
        return player
    }

    /**
     * Spielzüge: `do` = seat {team, role} | start | clue {word, n} | tip {i} | reveal {i} | pass | lobby | shuffle |
     * settings {packs, custom} | kick {id} | host {id} | react {emoji} | profile {name, avatar} | leave.
     */
    private fun act(body: JsonObject): WebResponse {
        val room = roomOf(body) ?: return json(404, obj("error" to "Diesen Raum gibt es nicht (mehr).", "gone" to true))
        val token = body.str("token")
        val now = clock()
        var effect: String? = null
        val kicked = ArrayList<Client>()
        val problem: String? = synchronized(room) {
            val player = room.players.values.firstOrNull { it.token == token }
                ?: return json(410, obj("error" to "Du bist nicht mehr in diesem Raum.", "gone" to true))
            if (!allowAction(player, now)) return json(429, obj("error" to "Langsam, langsam."))
            room.lastActive = now
            when (body.str("do")) {
                "seat" -> room.seat(player, body.str("team") ?: "", body.str("role") ?: "agent")
                "start" -> room.start(player)
                "clue" -> room.giveClue(player, body.str("word") ?: "", body.int("n") ?: -2)
                "tip" -> room.tip(player, body.int("i") ?: -1)
                "reveal" -> room.reveal(player, body.int("i") ?: -1)
                "pass" -> room.pass(player)
                "lobby" -> room.toLobby(player)
                "shuffle" -> room.shuffle(player)
                "settings" -> room.settings(
                    player,
                    (body["packs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: room.packs.toList(),
                    body.str("custom"),
                )
                "kick", "host" -> {
                    val target = room.players[body.str("id")]
                    when {
                        player.id != room.hostId -> "Das macht der Host."
                        target == null || target === player -> "Diesen Spieler gibt es nicht."
                        body.str("do") == "host" -> null.also { room.hostId = target.id }
                        else -> null.also {
                            kicked += target.clients
                            room.remove(target)
                        }
                    }
                }
                "react" -> {
                    val emoji = body.str("emoji")
                    if (emoji !in REACTIONS) "Unbekannt" else null.also {
                        effect = event("fx", obj("emoji" to emoji, "id" to player.id, "name" to player.name, "team" to player.team))
                    }
                }
                "profile" -> {
                    val name = cleanName(body.str("name"))
                    if (name == null) "Gib dir einen Namen (1–$NAME_LENGTH Zeichen)." else null.also {
                        if (room.players.values.none { it !== player && it.name.equals(name, ignoreCase = true) }) player.name = name
                        player.avatar = (body.int("avatar") ?: player.avatar).coerceIn(0, AVATARS - 1)
                    }
                }
                "leave" -> null.also {
                    kicked += player.clients
                    room.remove(player)
                }
                else -> "Unbekannter Zug"
            }
        }
        if (problem != null) return json(409, obj("error" to problem))
        for (client in kicked) client.closeAfter(event("bye", obj("reason" to "removed")))
        val fx = effect
        if (fx != null) clientsOf(room).forEach { it.push(fx) } else broadcast(room)
        return json(200, obj("ok" to true))
    }

    // ---------- Stream ----------

    /** Für /api/geheimfunk/stream: Raum + Spieler prüfen. Danach [attach] mit dem offenen Ausgang aufrufen. */
    fun openStream(request: WebRequest): Pair<Pair<WordRoom, WordRoom.Player>?, WebResponse?> {
        val room = synchronized(rooms) { rooms[request.query["room"]?.uppercase()] }
            ?: return null to json(404, obj("error" to "Diesen Raum gibt es nicht (mehr).", "gone" to true))
        val token = request.query["token"]
        val player = synchronized(room) { room.players.values.firstOrNull { it.token == token } }
            ?: return null to json(410, obj("error" to "Du bist nicht mehr in diesem Raum.", "gone" to true))
        if (synchronized(rooms) { streams >= MAX_STREAMS }) return null to json(503, obj("error" to "Zu viele Verbindungen"))
        return (room to player) to null
    }

    /** Stream anhängen: schickt sofort den Stand. [onClose] räumt die Verbindung weg (HttpExchange.close). */
    fun attach(room: WordRoom, player: WordRoom.Player, out: OutputStream, onClose: () -> Unit) {
        val client = Client(room, player, out, onClose)
        val old = synchronized(room) {
            if (room.players[player.id] !== player) null else {
                val tooMany = if (player.clients.size >= STREAMS_PER_PLAYER) player.clients.removeAt(0) else null
                player.clients += client
                player.offlineSince = null
                // Host-Wechsel nur über tick() (Host länger weg) – ein kurzes Neuladen kostet den Host nicht
                if (room.players[room.hostId] == null) room.pickHost()
                listOfNotNull(tooMany)
            }
        }
        if (old == null) return client.closeAfter(event("bye", obj("reason" to "removed")))
        synchronized(rooms) { streams++ }
        old.forEach { it.close() }
        client.push(": " + " ".repeat(2048) + "\n\n") // Polster für Proxys, die erst ab ein paar KB weiterreichen
        broadcast(room)
    }

    private fun dropped(client: Client) {
        val room = client.room
        val was = synchronized(room) {
            val removed = client.player.clients.remove(client)
            if (removed && !client.player.online) client.player.offlineSince = clock()
            removed
        }
        if (was) {
            synchronized(rooms) { streams-- }
            broadcast(room)
        }
    }

    private fun clientsOf(room: WordRoom): List<Client> = synchronized(room) { room.players.values.flatMap { it.clients } }

    /** Jedem seine Sicht schicken (Funker sehen mehr als Spürnasen). */
    private fun broadcast(room: WordRoom) {
        val messages = synchronized(room) {
            room.players.values.filter { it.online }.map { player ->
                val text = event("state", room.view(player))
                player.clients.toList() to text
            }
        }
        for ((clients, text) in messages) clients.forEach { it.pushState(text) }
    }

    // ---------- Takt ----------

    fun start() {
        if (ticker != null) return
        writers = Executors.newFixedThreadPool(WRITERS) { task -> Thread(task, "geheimfunk-out").apply { isDaemon = true } }
        ticker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "geheimfunk").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (e: Exception) {
                    log.warn("Geheimfunk: {}", e.toString())
                }
            }, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        ticker?.shutdownNow()
        ticker = null
        synchronized(rooms) { rooms.values.toList() }.flatMap { clientsOf(it) }.forEach { it.close() }
        writers?.shutdownNow()
        writers = null
    }

    /** Alle 10 s: Lebenszeichen (merkt geschlossene Browser), Host weitergeben, verlassene Spieler und Räume aufräumen. */
    fun tick() {
        val now = clock()
        val all = synchronized(rooms) { rooms.values.toList() }
        for (room in all) {
            clientsOf(room).forEach { it.push(": ping\n\n") }
            val changed = synchronized(room) {
                var changed = false
                // in der Lobby fliegt raus, wer lange weg ist; im Spiel bleibt der Platz (Browser neu laden, Handy gesperrt)
                val limit = if (room.phase == WordRoom.Phase.LOBBY) GONE_LOBBY else GONE_GAME
                for (player in room.players.values.toList()) {
                    val since = player.offlineSince ?: continue
                    if (Duration.between(since, now) > limit) {
                        room.remove(player)
                        changed = true
                    }
                }
                val host = room.players[room.hostId]
                if (host == null || (host.offlineSince?.let { Duration.between(it, now) > HOST_AWAY } == true && room.players.values.any { it.online })) {
                    val before = room.hostId
                    room.pickHost()
                    changed = changed || before != room.hostId
                }
                changed
            }
            if (changed) broadcast(room)
        }
        synchronized(rooms) {
            rooms.values.removeIf { room ->
                synchronized(room) { room.players.values.none { it.online } && Duration.between(room.lastActive, now) > ROOM_IDLE }
            }
            if (creates.size > 2000) creates.clear()
        }
    }

    // ---------- Helfer ----------

    private fun roomOf(body: JsonObject): WordRoom? = synchronized(rooms) { rooms[body.str("room")?.trim()?.uppercase()] }

    /** Für Tests. */
    internal fun room(code: String): WordRoom? = synchronized(rooms) { rooms[code] }

    private fun allowCreate(client: String, now: Instant): Boolean {
        val window = creates.getOrPut(client) { ArrayDeque() }
        while (window.isNotEmpty() && window.first().plusSeconds(60).isBefore(now)) window.removeFirst()
        if (window.size >= JOINS_PER_MINUTE) return false
        window.addLast(now)
        return true
    }

    private fun allowAction(player: WordRoom.Player, now: Instant): Boolean {
        val window = player.actions
        while (window.isNotEmpty() && window.first().plusSeconds(10).isBefore(now)) window.removeFirst()
        if (window.size >= ACTIONS_PER_10S) return false
        window.addLast(now)
        return true
    }

    private fun cleanName(raw: String?): String? {
        val name = (raw ?: "").filter { !it.isISOControl() && it != '<' && it != '>' }.trim().replace(Regex("\\s+"), " ").take(NAME_LENGTH).trim()
        return name.ifEmpty { null }
    }

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(secure::nextBytes))

    private fun parseBody(request: WebRequest): JsonObject? {
        if (request.body.isEmpty() || request.body.size > MAX_BODY) return null
        return runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun event(name: String, data: JsonElement) = "event: $name\ndata: $data\n\n"

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun notAllowed(allowed: String) = WebResponse(405, body = bytes(obj("error" to "Nur $allowed")), headers = listOf("Allow" to allowed, NO_STORE))

    companion object {
        const val PATH = "/api/geheimfunk"
        const val STREAM_PATH = "$PATH/stream"
        const val MAX_BODY = 12 * 1024
        const val MAX_ROOMS = 60
        const val MAX_PLAYERS = 24
        const val MAX_STREAMS = 400
        const val STREAMS_PER_PLAYER = 3
        const val NAME_LENGTH = 16
        const val AVATARS = 24
        const val CODE_LENGTH = 4
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val WRITERS = 4
        private const val JOINS_PER_MINUTE = 30
        private const val ACTIONS_PER_10S = 60
        val REACTIONS = setOf("😂", "💀", "🤡", "🧠", "🔥", "👀", "🫡", "😱")
        val TICK: Duration = Duration.ofSeconds(10)
        val GONE_LOBBY: Duration = Duration.ofMinutes(2)
        val GONE_GAME: Duration = Duration.ofMinutes(20)
        val HOST_AWAY: Duration = Duration.ofSeconds(45)
        val ROOM_IDLE: Duration = Duration.ofMinutes(30)
        private val NO_STORE = "Cache-Control" to "no-store"
    }
}
