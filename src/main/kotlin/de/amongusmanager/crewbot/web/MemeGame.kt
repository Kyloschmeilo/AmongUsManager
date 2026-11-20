/*
 * Meme-Schmiede: Partyspiel für mehrere Spieler (Seite seiten/memes.html, API POST /api/memes).
 *
 * Ablauf je Runde: jeder bekommt eine eigene Meme-Vorlage (darf ein paar Mal neu würfeln) und schreibt die Texte dazu.
 * Danach werden alle Memes nacheinander gezeigt, ohne Namen; alle anderen bewerten sie (1–4 Punkte-Stufen) und dürfen
 * Emojis fliegen lassen. Erst nach der Abstimmung wird aufgedeckt, von wem das Meme ist. Nach allen Runden: Podest und
 * die besten Memes des Abends.
 *
 * Gleiches Muster wie die Kritzelkiste (DrawGame): kein WebSocket, der Browser fragt per kleiner POST-Anfrage ("sync")
 * nach, alles liegt nur im Speicher, ohne Anmeldung (Raum-Code aus 4 Buchstaben, Token aus create/join).
 * Der Server entscheidet alles; fremde Texte gehen erst beim Zeigen raus, der Autor erst beim Aufdecken.
 * Die Vorlagen selbst zeichnet die Seite (SVG); der Server kennt nur ihre ids und die Anzahl der Textfelder
 * ([MemeTemplates], `MemeGameTest` prüft, dass Seite und Server dieselben haben). Der Host kann eigene Bilder per
 * https-Adresse dazunehmen (zwei Textfelder oben/unten), die Browser laden sie dann direkt.
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Eingebaute Vorlagen: id → Anzahl Textfelder (gezeichnet werden sie in seiten/memes.html). */
object MemeTemplates {
    class Template(val id: String, val slots: Int)

    val ALL: List<Template> = listOf(
        Template("knoepfe", 3), Template("neinja", 2), Template("gehirn", 4), Template("schock", 2),
        Template("feuer", 2), Template("abgelenkt", 3), Template("waage", 3), Template("schild", 2),
        Template("erwartung", 2), Template("rakete", 2), Template("notfall", 2), Template("tafel", 2),
        Template("kreuzung", 3), Template("nachts", 2), Template("pokal", 2), Template("sturm", 2),
        Template("bett", 2), Template("geist", 2), Template("kurve", 2), Template("tuer", 2),
        Template("regen", 2), Template("stapel", 3), Template("handschlag", 3), Template("pizza", 2),
    )

    val BY_ID: Map<String, Template> = ALL.associateBy { it.id }

    /** Eigene Bilder haben immer zwei Textfelder (oben/unten). */
    const val CUSTOM_SLOTS = 2
}

/** Reine Regeln (getestet in `MemeGameTest`). */
object MemeRules {
    const val MAX_PLAYERS = 12
    const val MAX_CAPTION = 90
    const val MAX_CUSTOM = 20
    const val MAX_URL = 300

    /** Bewertungs-Stufen: Punkte je Stufe 1–4. */
    val RATING_POINTS = intArrayOf(0, 25, 50, 100, 150)

    fun cleanName(raw: String): String =
        raw.filter { !it.isISOControl() && it != '​' && it != '‮' }.trim().replace(Regex("\\s+"), " ").take(16).trim()

    /** Text eines Felds: ohne Steuerzeichen, Zeilenumbrüche höchstens zwei, Leerraum zusammengefasst. */
    fun cleanCaption(raw: String): String {
        val lines = raw.replace("\r", "").split('\n')
            .map { line -> line.filter { !it.isISOControl() && it != '\u202E' && it != '\u200B' }.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        val kept = lines.take(2) + listOfNotNull(lines.drop(2).joinToString(" ").ifEmpty { null })
        return kept.joinToString("\n").take(MAX_CAPTION).trim()
    }

    /** Eigene Bilder: nur https-Adressen ohne Leerzeichen/Anführungszeichen, höchstens [MAX_CUSTOM] Stück. */
    fun customImages(raw: String): List<String> = raw.split('\n', ',', ' ', ';')
        .map { it.trim() }
        .filter { url ->
            url.length in 12..MAX_URL && url.startsWith("https://", ignoreCase = true) &&
                url.none { it.isWhitespace() || it.isISOControl() || it in "\"'<>`\\" } &&
                runCatching { java.net.URI(url).host?.isNotEmpty() == true }.getOrDefault(false)
        }
        .distinct()
        .take(MAX_CUSTOM)

    /** Punkte für ein Meme aus den Stimmen (1–4). */
    fun points(ratings: Collection<Int>): Int = ratings.sumOf { RATING_POINTS.getOrElse(it) { 0 } }
}

class MemeGame(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    enum class Phase { LOBBY, WRITE, VOTE, SCORES, FINAL }

    internal class Player(val id: Int, val token: String, var name: String, var avatar: List<Int>) {
        var score = 0
        var gain = 0
        var lastSeen = 0L
        var template = ""
        var captions: List<String> = emptyList()
        var done = false
        var rerolls = 0
        var playing = false
        var lastReact = 0L
    }

    /** Ein fertiges Meme der Partie. */
    internal class Meme(val n: Int, val round: Int, val author: Int, val authorName: String, val authorAv: List<Int>,
                        val template: String, val url: String?, val captions: List<String>) {
        val votes = LinkedHashMap<Int, Int>()
        val points get() = MemeRules.points(votes.values)
    }

    internal class Reaction(val n: Int, val emoji: Int, val from: Int)

    internal class Settings {
        var rounds = 3
        var seconds = 75
        var rerolls = 2
        var builtIn = true
        var custom: List<String> = emptyList()
    }

    internal class Room(val code: String) {
        val players = LinkedHashMap<Int, Player>()
        var nextId = 1
        var host = 0
        var phase = Phase.LOBBY
        var round = 0
        var deadline = 0L
        var phaseMs = 0L
        val used = HashSet<String>()
        val memes = ArrayList<Meme>()
        /** Reihenfolge der Memes dieser Runde in der Abstimmung (Indizes in [memes]). */
        var show: List<Int> = emptyList()
        var showAt = 0
        var revealed = false
        var note = ""
        val reactions = ArrayDeque<Reaction>()
        var reactSeq = 0
        val settings = Settings()
        var active = 0L
    }

    private val rooms = HashMap<String, Room>()
    private val created = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun handle(request: WebRequest, client: String): WebResponse {
        if (request.method == "GET" || request.method == "HEAD") return status()
        if (request.method != "POST") return WebResponse(405, body = bytes(obj("error" to "Nur POST")), headers = listOf("Allow" to "GET, POST", NO_STORE))
        val body = try {
            Json.parseToJsonElement(request.body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return json(400, obj("ok" to false, "error" to "Ungültige Anfrage"))
        val now = clock()
        sweep(now)
        return when (val action = body.str("a")) {
            "create" -> create(body, client, now)
            "join" -> join(body, now)
            else -> {
                val room = rooms[body.str("room")?.uppercase() ?: ""]
                    ?: return json(200, obj("ok" to false, "gone" to true, "error" to "Den Raum gibt es nicht mehr."))
                val token = body.str("token") ?: ""
                val player = room.players.values.firstOrNull { token.isNotEmpty() && it.token == token }
                    ?: return json(200, obj("ok" to false, "gone" to true, "error" to "Du bist nicht mehr in diesem Raum."))
                player.lastSeen = now
                room.active = now
                tick(room, now)
                if (room.players[player.id] == null) return json(200, obj("ok" to false, "gone" to true, "error" to "Du bist nicht mehr in diesem Raum."))
                var error: String? = null
                when (action) {
                    "sync" -> Unit
                    "start" -> error = start(room, player, now)
                    "settings" -> error = settings(room, player, body)
                    "write" -> write(room, player, body)
                    "reroll" -> error = reroll(room, player)
                    "vote" -> vote(room, player, body.int("v") ?: 0)
                    "react" -> react(room, player, body.int("e") ?: -1, now)
                    "kick" -> kick(room, player, body.int("id") ?: 0, now)
                    "skip" -> if (player.id == room.host) skip(room, now)
                    "again" -> if (player.id == room.host && room.phase == Phase.FINAL) toLobby(room)
                    "leave" -> {
                        remove(room, player, now)
                        if (room.players.isEmpty()) rooms.remove(room.code)
                        return json(200, obj("ok" to true, "left" to true))
                    }
                    else -> error = "Unbekannte Aktion"
                }
                tick(room, now)
                json(200, state(room, player, body, now, error))
            }
        }
    }

    /** Für die Live-Leiste der Startseite: belegte Räume mit Code (zum direkten Beitreten), keine Namen. */
    private fun status(): WebResponse {
        val now = clock()
        sweep(now)
        return json(200, LiveRooms.status(rooms.values.map { room ->
            LiveRooms.Room(room.code, present(room, now).size, MemeRules.MAX_PLAYERS, room.phase != Phase.LOBBY && room.phase != Phase.FINAL, room.players.size)
        }))
    }

    // ---------- Raum betreten ----------

    private fun create(body: JsonObject, client: String, now: Long): WebResponse {
        val name = MemeRules.cleanName(body.str("name") ?: "")
        if (name.isEmpty()) return json(200, obj("ok" to false, "error" to "Bitte gib einen Namen ein."))
        val recent = created.getOrPut(client) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
        if (recent.size >= MAX_CREATE_PER_MINUTE || rooms.size >= MAX_ROOMS) {
            return json(200, obj("ok" to false, "error" to "Gerade gibt es zu viele Räume. Versuch es gleich nochmal."))
        }
        recent.addLast(now)
        var code: String
        do {
            code = (1..4).map { CODE_LETTERS[random.nextInt(CODE_LETTERS.length)] }.joinToString("")
        } while (code in rooms)
        val room = Room(code)
        rooms[code] = room
        room.active = now
        val player = add(room, name, avatarOf(body), now)
        room.host = player.id
        return json(200, state(room, player, body, now, null, token = player.token))
    }

    private fun join(body: JsonObject, now: Long): WebResponse {
        val room = rooms[body.str("room")?.trim()?.uppercase() ?: ""]
            ?: return json(200, obj("ok" to false, "error" to "Diesen Raum gibt es nicht. Stimmt der Code?"))
        tick(room, now)
        val token = body.str("token") ?: ""
        val known = room.players.values.firstOrNull { token.isNotEmpty() && it.token == token }
        if (known != null) {
            known.lastSeen = now
            return json(200, state(room, known, body, now, null, token = known.token))
        }
        val name = MemeRules.cleanName(body.str("name") ?: "")
        if (name.isEmpty()) return json(200, obj("ok" to false, "error" to "Bitte gib einen Namen ein."))
        if (room.players.size >= MemeRules.MAX_PLAYERS) return json(200, obj("ok" to false, "error" to "Der Raum ist voll (${MemeRules.MAX_PLAYERS} Leute)."))
        val player = add(room, name, avatarOf(body), now)
        room.active = now
        return json(200, state(room, player, body, now, null, token = player.token))
    }

    private fun add(room: Room, wanted: String, avatar: List<Int>, now: Long): Player {
        var name = wanted
        var n = 2
        while (room.players.values.any { it.name.equals(name, ignoreCase = true) }) name = "${wanted.take(13)} ${n++}"
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val player = Player(room.nextId++, token, name, avatar)
        player.lastSeen = now
        room.players[player.id] = player
        return player
    }

    private fun avatarOf(body: JsonObject): List<Int> {
        val raw = (body["av"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: 0 } ?: emptyList()
        return listOf((raw.getOrNull(0) ?: 0).coerceIn(0, 11), (raw.getOrNull(1) ?: 0).coerceIn(0, 7), (raw.getOrNull(2) ?: 0).coerceIn(0, 7))
    }

    // ---------- Aktionen ----------

    private fun start(room: Room, player: Player, now: Long): String? {
        if (player.id != room.host || room.phase != Phase.LOBBY) return null
        if (present(room, now).size < MIN_PLAYERS) return "Zum Spielen braucht ihr mindestens $MIN_PLAYERS Leute."
        if (pool(room).isEmpty()) return "Keine Vorlagen: lass die eingebauten an oder trag eigene Bilder ein."
        for (p in room.players.values) {
            p.score = 0
            p.gain = 0
            p.rerolls = room.settings.rerolls
        }
        room.round = 0
        room.used.clear()
        room.memes.clear()
        room.note = ""
        nextRound(room, now)
        return null
    }

    private fun settings(room: Room, player: Player, body: JsonObject): String? {
        if (player.id != room.host) return "Nur der Host kann die Einstellungen ändern."
        if (room.phase != Phase.LOBBY) return null
        val s = room.settings
        body.int("rounds")?.let { s.rounds = it.coerceIn(1, 8) }
        body.int("seconds")?.let { s.seconds = it.coerceIn(30, 180) }
        body.int("rerolls")?.let { s.rerolls = it.coerceIn(0, 5) }
        body.bool("builtIn")?.let { s.builtIn = it }
        body.str("custom")?.let { s.custom = MemeRules.customImages(it) }
        if (!s.builtIn && s.custom.isEmpty()) {
            s.builtIn = true
            return "Ohne eigene Bilder bleiben die eingebauten Vorlagen an."
        }
        return null
    }

    private fun write(room: Room, player: Player, body: JsonObject) {
        if (room.phase != Phase.WRITE || !player.playing) return
        val list = (body["captions"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" } ?: return
        player.captions = (0 until slotsOf(player.template)).map { MemeRules.cleanCaption(list.getOrElse(it) { "" }) }
        body.bool("done")?.let { player.done = it && player.captions.any { c -> c.isNotEmpty() } }
    }

    private fun reroll(room: Room, player: Player): String? {
        if (room.phase != Phase.WRITE || !player.playing || player.done) return null
        if (player.rerolls <= 0) return "Keine Würfe mehr übrig."
        val next = drawTemplate(room, exclude = player.template) ?: return "Keine andere Vorlage mehr da."
        player.rerolls--
        player.template = next
        player.captions = List(slotsOf(next)) { "" }
        return null
    }

    private fun vote(room: Room, player: Player, value: Int) {
        if (room.phase != Phase.VOTE || room.revealed) return
        val meme = currentMeme(room) ?: return
        if (meme.author == player.id) return
        if (value == 0) meme.votes.remove(player.id) else meme.votes[player.id] = value.coerceIn(1, 4)
    }

    private fun react(room: Room, player: Player, emoji: Int, now: Long) {
        if (room.phase != Phase.VOTE || emoji !in 0 until EMOJIS || now - player.lastReact < REACT_GAP_MS) return
        player.lastReact = now
        room.reactions.addLast(Reaction(++room.reactSeq, emoji, player.id))
        while (room.reactions.size > REACT_KEEP) room.reactions.removeFirst()
    }

    private fun kick(room: Room, player: Player, id: Int, now: Long) {
        if (player.id != room.host || id == player.id) return
        room.players[id]?.let { remove(room, it, now) }
    }

    /** Host: aktuelle Phase sofort beenden (z.B. wenn jemand ewig schreibt). */
    private fun skip(room: Room, now: Long) {
        when (room.phase) {
            Phase.WRITE -> endWriting(room, now)
            Phase.VOTE -> if (room.revealed) nextMeme(room, now) else reveal(room, now)
            Phase.SCORES -> nextRound(room, now)
            else -> Unit
        }
    }

    // ---------- Ablauf ----------

    private fun pool(room: Room): List<String> =
        (if (room.settings.builtIn) MemeTemplates.ALL.map { it.id } else emptyList()) + room.settings.custom.indices.map { "u$it" }

    internal fun slotsOf(template: String): Int = MemeTemplates.BY_ID[template]?.slots ?: MemeTemplates.CUSTOM_SLOTS

    private fun urlOf(room: Room, template: String): String? =
        if (template.startsWith("u")) room.settings.custom.getOrNull(template.drop(1).toIntOrNull() ?: -1) else null

    /** Vorlage, die in dieser Partie noch niemand hatte (wenn alle durch sind, geht es von vorne los). */
    private fun drawTemplate(room: Room, exclude: String = ""): String? {
        val pool = pool(room)
        val taken = room.players.values.filter { it.playing }.map { it.template }.toSet()
        var free = pool.filter { it !in room.used && it !in taken && it != exclude }
        if (free.isEmpty()) {
            room.used.clear()
            room.used.addAll(taken)
            free = pool.filter { it !in taken && it != exclude }
        }
        if (free.isEmpty()) free = pool.filter { it != exclude }
        if (free.isEmpty()) return null
        return free[random.nextInt(free.size)].also { room.used += it }
    }

    private fun nextRound(room: Room, now: Long) {
        if (room.round >= room.settings.rounds) return finish(room)
        room.round++
        room.phase = Phase.WRITE
        for (p in room.players.values) {
            p.playing = false
            p.template = ""
            p.done = false
            p.gain = 0
        }
        for (p in present(room, now)) {
            p.playing = true
            p.template = drawTemplate(room) ?: ""
            p.captions = List(slotsOf(p.template)) { "" }
        }
        room.phaseMs = room.settings.seconds * 1000L
        room.deadline = now + room.phaseMs
    }

    private fun endWriting(room: Room, now: Long) {
        val start = room.memes.size
        for (p in room.players.values.filter { it.playing && it.captions.any { c -> c.isNotEmpty() } }) {
            room.memes += Meme(room.memes.size, room.round, p.id, p.name, p.avatar, p.template, urlOf(room, p.template), p.captions)
        }
        val fresh = (start until room.memes.size).toMutableList()
        if (fresh.isEmpty()) {
            room.note = "Diese Runde hat niemand etwas geschrieben."
            return scores(room, now)
        }
        fresh.shuffle(random)
        room.show = fresh
        room.showAt = 0
        room.phase = Phase.VOTE
        startShowing(room, now)
    }

    private fun startShowing(room: Room, now: Long) {
        room.revealed = false
        room.phaseMs = VOTE_MS
        room.deadline = now + VOTE_MS
    }

    private fun reveal(room: Room, now: Long) {
        val meme = currentMeme(room) ?: return
        room.revealed = true
        room.players[meme.author]?.let {
            it.score += meme.points
            it.gain += meme.points
        }
        room.phaseMs = REVEAL_MS
        room.deadline = now + REVEAL_MS
    }

    private fun nextMeme(room: Room, now: Long) {
        room.showAt++
        if (room.showAt >= room.show.size) scores(room, now) else startShowing(room, now)
    }

    private fun scores(room: Room, now: Long) {
        room.phase = Phase.SCORES
        room.phaseMs = SCORES_MS
        room.deadline = now + SCORES_MS
    }

    private fun finish(room: Room) {
        room.phase = Phase.FINAL
        room.deadline = 0
        for (p in room.players.values) p.playing = false
    }

    private fun toLobby(room: Room) {
        room.phase = Phase.LOBBY
        room.round = 0
        room.note = ""
        room.memes.clear()
        room.show = emptyList()
        for (p in room.players.values) {
            p.score = 0
            p.gain = 0
            p.playing = false
        }
    }

    private fun currentMeme(room: Room): Meme? = room.show.getOrNull(room.showAt)?.let { room.memes.getOrNull(it) }

    private fun present(room: Room, now: Long): List<Player> = room.players.values.filter { now - it.lastSeen < OFFLINE_MS }

    /** Wer das aktuelle Meme bewerten darf und noch da ist. */
    private fun voters(room: Room, meme: Meme, now: Long): List<Player> = present(room, now).filter { it.id != meme.author }

    private fun remove(room: Room, player: Player, now: Long) {
        room.players.remove(player.id)
        if (room.host == player.id) room.host = room.players.values.firstOrNull()?.id ?: 0
    }

    /** Bringt den Raum auf den Stand von `now`: Abwesende raus, abgelaufene Phasen weiter. */
    private fun tick(room: Room, now: Long) {
        val playing = room.phase != Phase.LOBBY && room.phase != Phase.FINAL
        val limit = if (playing) GONE_PLAYING_MS else GONE_LOBBY_MS
        for (p in room.players.values.filter { now - it.lastSeen > limit }) remove(room, p, now)
        repeat(8) {
            if (room.phase == Phase.LOBBY || room.phase == Phase.FINAL) return
            if (present(room, now).size < 2) {
                room.note = "Zu wenige Spieler – das Spiel ist vorbei."
                return finish(room)
            }
            when (room.phase) {
                Phase.WRITE -> {
                    val writers = room.players.values.filter { it.playing && now - it.lastSeen < OFFLINE_MS }
                    if (now >= room.deadline || (writers.isNotEmpty() && writers.all { it.done })) endWriting(room, now) else return
                }
                Phase.VOTE -> {
                    val meme = currentMeme(room) ?: return scores(room, now)
                    if (!room.revealed) {
                        val voters = voters(room, meme, now)
                        val allVoted = voters.isNotEmpty() && voters.all { it.id in meme.votes }
                        // Haben alle abgestimmt, kurz warten, damit man die eigene Wahl noch sieht/ändern kann
                        if (now >= room.deadline || (allVoted && room.deadline - now > ALL_VOTED_MS)) {
                            if (now >= room.deadline) reveal(room, now) else room.deadline = now + ALL_VOTED_MS
                        } else return
                    } else if (now >= room.deadline) nextMeme(room, now) else return
                }
                Phase.SCORES -> if (now >= room.deadline) nextRound(room, now) else return
                else -> return
            }
        }
    }

    private fun sweep(now: Long) {
        val iterator = rooms.values.iterator()
        while (iterator.hasNext()) {
            val room = iterator.next()
            if (now - room.active > ROOM_IDLE_MS) iterator.remove()
        }
        if (created.size > 500) created.values.removeIf { it.isEmpty() || now - it.last() > 60_000 }
    }

    // ---------- Antwort ----------

    private fun memeJson(meme: Meme, withAuthor: Boolean): JsonObject = obj(
        "n" to meme.n, "round" to meme.round, "tpl" to meme.template, "url" to meme.url, "captions" to meme.captions,
        "author" to if (withAuthor) meme.author else null,
        "name" to if (withAuthor) meme.authorName else null,
        "av" to if (withAuthor) meme.authorAv else null,
        "points" to if (withAuthor) meme.points else null,
        "counts" to if (withAuthor) (1..4).map { v -> meme.votes.values.count { it == v } } else null,
    )

    private fun state(room: Room, me: Player, body: JsonObject, now: Long, error: String?, token: String? = null): JsonObject {
        val s = room.settings
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = true
        if (error != null) out["error"] = error
        if (token != null) out["token"] = token
        out["code"] = room.code
        out["you"] = me.id
        out["host"] = room.host
        out["phase"] = room.phase.name.lowercase()
        out["round"] = room.round
        out["rounds"] = s.rounds
        out["left"] = if (room.deadline > 0) (room.deadline - now).coerceAtLeast(0) else 0
        out["total"] = room.phaseMs
        if (room.note.isNotEmpty()) out["note"] = room.note
        out["players"] = room.players.values.map { p ->
            obj("id" to p.id, "name" to p.name, "av" to p.avatar, "score" to p.score, "gain" to p.gain,
                "playing" to p.playing, "done" to p.done, "on" to (now - p.lastSeen < OFFLINE_MS))
        }
        out["settings"] = obj(
            "rounds" to s.rounds, "seconds" to s.seconds, "rerolls" to s.rerolls, "builtIn" to s.builtIn,
            "customCount" to s.custom.size, "custom" to if (me.id == room.host) s.custom.joinToString("\n") else null,
            "builtInCount" to MemeTemplates.ALL.size,
        )
        when (room.phase) {
            Phase.WRITE -> if (me.playing) {
                out["me"] = obj("tpl" to me.template, "url" to urlOf(room, me.template), "captions" to me.captions,
                    "done" to me.done, "rerolls" to me.rerolls)
            }
            Phase.VOTE -> currentMeme(room)?.let { meme ->
                out["meme"] = memeJson(meme, room.revealed)
                out["at"] = room.showAt + 1
                out["of"] = room.show.size
                out["mine"] = meme.author == me.id
                out["myVote"] = meme.votes[me.id] ?: 0
                out["revealed"] = room.revealed
                out["voted"] = meme.votes.size
                out["voters"] = voters(room, meme, now).size
            }
            Phase.SCORES -> {
                val round = room.memes.filter { it.round == room.round }
                round.maxByOrNull { it.points }?.takeIf { it.points > 0 }?.let { out["best"] = memeJson(it, true) }
            }
            Phase.FINAL -> {
                // Bestes Meme je Runde + die drei besten insgesamt (ohne Doppelte), mit Autor
                val best = (1..room.round).mapNotNull { r -> room.memes.filter { it.round == r }.maxByOrNull { it.points } }
                val top = room.memes.sortedByDescending { it.points }.take(3)
                out["gallery"] = (top + best).distinctBy { it.n }.sortedByDescending { it.points }.take(8).map { memeJson(it, true) }
            }
            else -> Unit
        }
        val since = body.int("r") ?: -1
        out["reactSeq"] = room.reactSeq
        if (since >= 0) out["reactions"] = room.reactions.filter { it.n > since }.map { obj("n" to it.n, "e" to it.emoji, "id" to it.from) }
        return obj(*out.map { it.key to it.value }.toTypedArray())
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun bytes(element: JsonElement): ByteArray = element.toString().toByteArray(Charsets.UTF_8)

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    // ---------- für Tests ----------

    @Synchronized
    internal fun roomCount(): Int = rooms.size

    @Synchronized
    internal fun templateOf(code: String, id: Int): String = rooms[code]?.players?.get(id)?.template ?: ""

    companion object {
        const val PATH = "/api/memes"
        private val NO_STORE = "Cache-Control" to "no-store"
        private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val MIN_PLAYERS = 2
        const val MAX_ROOMS = 200
        const val MAX_CREATE_PER_MINUTE = 20
        const val OFFLINE_MS = 8_000L
        const val GONE_LOBBY_MS = 20_000L
        const val GONE_PLAYING_MS = 60_000L
        const val ROOM_IDLE_MS = 10 * 60_000L
        const val VOTE_MS = 14_000L
        const val ALL_VOTED_MS = 1_200L
        const val REVEAL_MS = 4_500L
        const val SCORES_MS = 9_000L
        const val EMOJIS = 6
        const val REACT_GAP_MS = 350L
        const val REACT_KEEP = 80
    }
}
