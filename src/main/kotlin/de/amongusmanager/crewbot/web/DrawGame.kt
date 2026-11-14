/*
 * Kritzelkiste: Zeichnen und Raten für mehrere Spieler (Seite seiten/kritzeln.html, API POST /api/kritzel).
 *
 * Kein WebSocket (der JDK-HttpServer kann das nicht) und kein Dauer-Stream: der Browser fragt alle ~250 ms mit einer
 * kleinen POST-Anfrage nach (Aktion "sync", schickt dabei eigene Striche mit) und bekommt den Stand des Raums, neue
 * Chat-Zeilen und neue Striche zurück. Alles liegt nur im Speicher; Räume verschwinden, wenn niemand mehr da ist.
 * Der Server ist der Schiedsrichter: Wort, Zeit, Punkte und wer was sehen darf entscheidet nur er – das Wort geht
 * erst an einen Browser, wenn der Spieler zeichnet, es erraten hat oder die Runde vorbei ist.
 * Ohne Anmeldung: wer den Raum-Code kennt, spielt mit (Name frei wählbar), erkannt wird man am Token aus "create"/"join".
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
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Reine Regeln (getestet in `DrawGameTest`). */
object DrawRules {
    const val MAX_PLAYERS = 12
    const val CHOOSE_MS = 15_000L
    const val REVEAL_MS = 7_000L
    const val COLORS = 24
    const val SIZES = 5
    const val WIDTH = 800
    const val HEIGHT = 600

    /** Vergleichsform: klein, Umlaute ausgeschrieben, ohne Leerzeichen/Bindestriche/Satzzeichen. */
    fun normalize(text: String): String {
        val out = StringBuilder()
        for (c in text.lowercase()) {
            when (c) {
                'ä' -> out.append("ae")
                'ö' -> out.append("oe")
                'ü' -> out.append("ue")
                'ß' -> out.append("ss")
                else -> if (c.isLetterOrDigit()) out.append(c)
            }
        }
        return out.toString()
    }

    fun matches(guess: String, word: String): Boolean = normalize(guess).let { it.isNotEmpty() && it == normalize(word) }

    /** Knapp daneben: ein Tippfehler (ab 9 Buchstaben zwei). */
    fun close(guess: String, word: String): Boolean {
        val a = normalize(guess)
        val b = normalize(word)
        if (a.isEmpty() || a == b || b.length < 4) return false
        return distance(a, b) <= if (b.length >= 9) 2 else 1
    }

    fun distance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                row[j] = minOf(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = row
        }
        return prev[b.length]
    }

    /** Punkte fürs Erraten: 100 bis 500 je nach Restzeit, der Erste bekommt 50 dazu. */
    fun guessPoints(leftMs: Long, totalMs: Long, first: Boolean): Int {
        val share = if (totalMs <= 0) 0.0 else (leftMs.toDouble() / totalMs).coerceIn(0.0, 1.0)
        return 100 + ((400 * share) / 5).roundToInt() * 5 + if (first) 50 else 0
    }

    /** Punkte fürs Zeichnen: Durchschnitt aller Ratenden (wer nicht errät, zählt als 0). */
    fun drawerPoints(gains: List<Int>): Int = if (gains.isEmpty()) 0 else ((gains.sum().toDouble() / gains.size) / 5).roundToInt() * 5

    /** Wort als Lücken: Buchstaben werden „_“, außer den aufgedeckten; Leerzeichen und Bindestriche bleiben. */
    fun mask(word: String, revealed: Set<Int>): String = buildString {
        word.forEachIndexed { index, c -> append(if (c.isLetterOrDigit() && index !in revealed) '_' else c) }
    }

    /** Wie viele Tipp-Buchstaben nach `elapsed` fällig sind (gleichmäßig über die Zeit, nie mehr als die Hälfte des Worts). */
    fun hintsDue(word: String, hints: Int, elapsedMs: Long, totalMs: Long): Int {
        val letters = word.count { it.isLetterOrDigit() }
        val max = minOf(hints, (letters - 1) / 2)
        if (max <= 0 || totalMs <= 0) return 0
        return ((elapsedMs.toDouble() / totalMs) * (max + 1)).toInt().coerceIn(0, max)
    }

    fun cleanName(raw: String): String =
        raw.filter { !it.isISOControl() && it != '​' && it != '‮' }.trim().replace(Regex("\\s+"), " ").take(16).trim()

    fun cleanChat(raw: String): String = raw.filter { !it.isISOControl() && it != '‮' }.trim().replace(Regex("\\s+"), " ").take(100)

    /** Eigene Wörter des Hosts: durch Komma oder Zeilenumbruch getrennt, höchstens 200 Stück mit je 2–30 Zeichen. */
    fun customWords(raw: String): List<String> = raw.split(',', '\n', ';')
        .map { part -> part.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..30 && normalize(it).isNotEmpty() }
        .distinctBy { normalize(it) }
        .take(200)
}

class DrawGame(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    enum class Phase { LOBBY, CHOOSE, DRAW, REVEAL, FINAL }

    internal class Player(val id: Int, val token: String, var name: String, var avatar: List<Int>) {
        var score = 0
        var gain = 0
        var guessed = false
        var liked = false
        var lastSeen = 0L
        var lastChat = 0L
    }

    internal class Line(val n: Int, val kind: String, val from: Int, val name: String, val text: String, val to: Int)

    internal class Settings {
        var rounds = 3
        var seconds = 80
        var hints = 2
        var categories: Set<String> = DrawWords.DEFAULT
        var custom: List<String> = emptyList()
        var customOnly = false
    }

    internal class Room(val code: String) {
        val players = LinkedHashMap<Int, Player>()
        var nextId = 1
        var host = 0
        var phase = Phase.LOBBY
        var round = 0
        var turn = 0
        var drawer = 0
        var deadline = 0L
        var turnMs = 0L
        var word = ""
        var choices: List<String> = emptyList()
        val revealed = HashSet<Int>()
        val drawn = HashSet<Int>()
        val used = HashSet<String>()
        var firstGuess = true
        var reason = ""
        var note = ""
        var gen = 0
        val ops = ArrayList<JsonObject>()
        var points = 0
        val chat = ArrayDeque<Line>()
        var chatSeq = 0
        val settings = Settings()
        var active = 0L
    }

    private val rooms = HashMap<String, Room>()
    private val created = HashMap<String, ArrayDeque<Long>>()

    fun handles(path: String): Boolean = path == PATH

    @Synchronized
    fun handle(request: WebRequest, client: String): WebResponse {
        if (request.method != "POST") return WebResponse(405, body = bytes(obj("error" to "Nur POST")), headers = listOf("Allow" to "POST", NO_STORE))
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
                tick(room, now)
                val token = body.str("token") ?: ""
                val player = room.players.values.firstOrNull { it.token == token && token.isNotEmpty() }
                    ?: return json(200, obj("ok" to false, "gone" to true, "error" to "Du bist nicht mehr in diesem Raum."))
                player.lastSeen = now
                room.active = now
                var error: String? = null
                when (action) {
                    "sync" -> {
                        (body["ops"] as? JsonArray)?.let { draw(room, player, it) }
                        body.str("msg")?.let { say(room, player, it, now) }
                    }
                    "say" -> body.str("text")?.let { say(room, player, it, now) }
                    "start" -> error = start(room, player, now)
                    "pick" -> pick(room, player, body.int("i") ?: -1, now)
                    "settings" -> error = settings(room, player, body)
                    "kick" -> kick(room, player, body.int("id") ?: 0, now)
                    "like" -> like(room, player, body.int("v") ?: 1)
                    "again" -> if (player.id == room.host && room.phase == Phase.FINAL) toLobby(room)
                    "leave" -> {
                        remove(room, player, now, "hat den Raum verlassen")
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

    // ---------- Raum betreten ----------

    private fun create(body: JsonObject, client: String, now: Long): WebResponse {
        val name = DrawRules.cleanName(body.str("name") ?: "")
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
        val name = DrawRules.cleanName(body.str("name") ?: "")
        if (name.isEmpty()) return json(200, obj("ok" to false, "error" to "Bitte gib einen Namen ein."))
        if (room.players.size >= DrawRules.MAX_PLAYERS) return json(200, obj("ok" to false, "error" to "Der Raum ist voll (${DrawRules.MAX_PLAYERS} Spieler)."))
        val player = add(room, name, avatarOf(body), now)
        room.active = now
        system(room, "join", player, "ist dabei")
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
        if (room.players.size < 2) return "Zum Spielen braucht ihr mindestens zwei Leute."
        if (pool(room).size < 3) return "Zu wenige Wörter: wähle eine Kategorie oder trag mindestens drei eigene Wörter ein."
        for (p in room.players.values) {
            p.score = 0
            p.gain = 0
        }
        room.round = 1
        room.drawn.clear()
        room.used.clear()
        room.note = ""
        system(room, "sys", null, "Los geht's! ${room.settings.rounds} ${if (room.settings.rounds == 1) "Runde" else "Runden"}, jeder malt einmal pro Runde.")
        nextTurn(room, now)
        return null
    }

    private fun settings(room: Room, player: Player, body: JsonObject): String? {
        if (player.id != room.host) return "Nur der Host kann die Einstellungen ändern."
        if (room.phase != Phase.LOBBY) return null
        val s = room.settings
        body.int("rounds")?.let { s.rounds = it.coerceIn(1, 10) }
        body.int("seconds")?.let { s.seconds = it.coerceIn(20, 240) }
        body.int("hints")?.let { s.hints = it.coerceIn(0, 4) }
        (body["cats"] as? JsonArray)?.let { list ->
            val known = DrawWords.CATEGORIES.map { it.id }.toSet()
            s.categories = list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in known }.toSet()
        }
        body.str("custom")?.let { s.custom = DrawRules.customWords(it) }
        (body["customOnly"] as? JsonPrimitive)?.booleanOrNull?.let { s.customOnly = it }
        return null
    }

    private fun pick(room: Room, player: Player, index: Int, now: Long) {
        if (room.phase != Phase.CHOOSE || player.id != room.drawer) return
        val word = room.choices.getOrNull(index) ?: return
        startDrawing(room, word, now)
    }

    private fun kick(room: Room, player: Player, id: Int, now: Long) {
        if (player.id != room.host || id == player.id) return
        val target = room.players[id] ?: return
        remove(room, target, now, "wurde rausgeworfen")
    }

    private fun like(room: Room, player: Player, value: Int) {
        if (room.phase != Phase.DRAW || player.id == room.drawer || player.liked) return
        player.liked = true
        line(room, if (value < 0) "dislike" else "like", player, if (value < 0) "findet das Bild fragwürdig" else "feiert das Bild", 0)
    }

    private fun say(room: Room, player: Player, raw: String, now: Long) {
        val text = DrawRules.cleanChat(raw)
        if (text.isEmpty() || now - player.lastChat < CHAT_GAP_MS) return
        player.lastChat = now
        if (room.phase != Phase.DRAW) return line(room, "msg", player, text, 0)
        // wer das Wort kennt, schreibt nur noch für die anderen Eingeweihten – sonst wäre Verraten zu leicht
        if (player.id == room.drawer || player.guessed) return line(room, "hush", player, text, KNOWING)
        if (DrawRules.matches(text, room.word)) {
            val points = DrawRules.guessPoints(room.deadline - now, room.turnMs, room.firstGuess)
            room.firstGuess = false
            player.guessed = true
            player.gain = points
            player.score += points
            line(room, "ok", player, GUESSED[random.nextInt(GUESSED.size)], 0)
            return
        }
        line(room, "msg", player, text, 0)
        if (DrawRules.close(text, room.word)) line(room, "close", player, "„$text“ ist ganz knapp daneben!", player.id)
    }

    private fun draw(room: Room, player: Player, list: JsonArray) {
        if (room.phase != Phase.DRAW || player.id != room.drawer) return
        for (element in list.take(MAX_OPS_PER_REQUEST)) {
            val op = element as? JsonObject ?: continue
            when (op.str("k")) {
                "l" -> {
                    val raw = (op["p"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: continue
                    if (raw.size < 2 || raw.size % 2 != 0 || raw.size > MAX_POINTS_PER_OP * 2) continue
                    if (room.ops.size >= MAX_OPS || room.points + raw.size > MAX_POINTS) continue
                    val points = raw.mapIndexed { i, v -> v.coerceIn(0, if (i % 2 == 0) DrawRules.WIDTH else DrawRules.HEIGHT) }
                    room.points += raw.size
                    room.ops += obj(
                        "k" to "l", "i" to (op.int("i") ?: 0).coerceIn(0, 1_000_000),
                        "c" to (op.int("c") ?: 0).coerceIn(0, DrawRules.COLORS - 1), "w" to (op.int("w") ?: 1).coerceIn(0, DrawRules.SIZES - 1),
                        "p" to JsonArray(points.map { JsonPrimitive(it) }),
                    )
                }
                "f" -> {
                    if (room.ops.size >= MAX_OPS) continue
                    room.ops += obj(
                        "k" to "f", "c" to (op.int("c") ?: 0).coerceIn(0, DrawRules.COLORS - 1),
                        "x" to (op.int("x") ?: 0).coerceIn(0, DrawRules.WIDTH - 1), "y" to (op.int("y") ?: 0).coerceIn(0, DrawRules.HEIGHT - 1),
                    )
                }
                "u" -> if (room.ops.size < MAX_OPS) room.ops += obj("k" to "u")
                "c" -> clearCanvas(room)
            }
        }
    }

    private fun clearCanvas(room: Room) {
        room.gen++
        room.ops.clear()
        room.points = 0
    }

    // ---------- Ablauf ----------

    private fun pool(room: Room): List<String> {
        val s = room.settings
        val custom = s.custom
        return if (s.customOnly && custom.size >= 3) custom else (DrawWords.pool(s.categories) + custom).distinct()
    }

    private fun present(room: Room, now: Long): List<Player> = room.players.values.filter { now - it.lastSeen < OFFLINE_MS }

    private fun nextTurn(room: Room, now: Long) {
        var next = room.players.values.firstOrNull { it.id !in room.drawn && now - it.lastSeen < OFFLINE_MS }
        if (next == null) {
            if (room.round >= room.settings.rounds) return finish(room, "")
            room.round++
            room.drawn.clear()
            next = room.players.values.firstOrNull { now - it.lastSeen < OFFLINE_MS } ?: return finish(room, "Niemand mehr da.")
        }
        room.drawn += next.id
        room.drawer = next.id
        room.turn++
        clearCanvas(room)
        for (p in room.players.values) {
            p.guessed = false
            p.gain = 0
            p.liked = false
        }
        var words = pool(room).filter { it !in room.used }
        if (words.size < 3) {
            room.used.clear()
            words = pool(room)
        }
        room.choices = words.shuffled(random).take(3)
        room.word = ""
        room.revealed.clear()
        room.phase = Phase.CHOOSE
        room.deadline = now + DrawRules.CHOOSE_MS
        room.turnMs = DrawRules.CHOOSE_MS
    }

    private fun startDrawing(room: Room, word: String, now: Long) {
        room.word = word
        room.used += word
        room.choices = emptyList()
        room.revealed.clear()
        room.firstGuess = true
        room.phase = Phase.DRAW
        room.turnMs = room.settings.seconds * 1000L
        room.deadline = now + room.turnMs
    }

    private fun endTurn(room: Room, now: Long, reason: String) {
        val drawer = room.players[room.drawer]
        val guessers = room.players.values.filter { it.id != room.drawer && (it.guessed || now - it.lastSeen < OFFLINE_MS) }
        if (drawer != null && room.word.isNotEmpty()) {
            drawer.gain = DrawRules.drawerPoints(guessers.map { it.gain })
            drawer.score += drawer.gain
        }
        room.reason = reason
        room.phase = Phase.REVEAL
        room.deadline = now + DrawRules.REVEAL_MS
        room.turnMs = DrawRules.REVEAL_MS
        if (room.word.isNotEmpty()) system(room, "word", null, "Das Wort war: ${room.word}")
    }

    private fun finish(room: Room, note: String) {
        room.phase = Phase.FINAL
        room.note = note
        room.deadline = 0
        room.word = ""
        room.choices = emptyList()
        val winner = room.players.values.maxByOrNull { it.score }
        if (winner != null && winner.score > 0) system(room, "sys", null, "${winner.name} gewinnt mit ${winner.score} Punkten!")
    }

    private fun toLobby(room: Room) {
        room.phase = Phase.LOBBY
        room.round = 0
        room.drawer = 0
        room.word = ""
        room.note = ""
        clearCanvas(room)
        for (p in room.players.values) {
            p.gain = 0
            p.guessed = false
        }
    }

    private fun remove(room: Room, player: Player, now: Long, text: String) {
        room.players.remove(player.id)
        system(room, "leave", player, text)
        if (room.host == player.id) {
            room.host = room.players.values.firstOrNull()?.id ?: 0
            room.players[room.host]?.let { system(room, "sys", null, "${it.name} ist jetzt Host.") }
        }
        if (player.id == room.drawer && (room.phase == Phase.CHOOSE || room.phase == Phase.DRAW)) endTurn(room, now, "weg")
    }

    /** Bringt den Raum auf den Stand von `now`: Abwesende raus, abgelaufene Phasen weiter. */
    private fun tick(room: Room, now: Long) {
        val playing = room.phase != Phase.LOBBY && room.phase != Phase.FINAL
        val limit = if (playing) GONE_PLAYING_MS else GONE_LOBBY_MS
        for (p in room.players.values.filter { now - it.lastSeen > limit }) remove(room, p, now, "ist weg (Verbindung verloren)")
        repeat(6) {
            when (room.phase) {
                Phase.LOBBY, Phase.FINAL -> return
                else -> Unit
            }
            if (present(room, now).size < 2) return finish(room, "Zu wenige Spieler – das Spiel ist vorbei.")
            val drawerGone = room.players[room.drawer]?.let { now - it.lastSeen > DRAWER_AWAY_MS } ?: true
            when (room.phase) {
                Phase.CHOOSE -> when {
                    drawerGone -> endTurn(room, now, "weg")
                    now >= room.deadline -> startDrawing(room, room.choices.getOrElse(random.nextInt(maxOf(1, room.choices.size))) { "Strichmännchen" }, room.deadline)
                    else -> return
                }
                Phase.DRAW -> {
                    val elapsed = room.turnMs - (room.deadline - now)
                    val due = DrawRules.hintsDue(room.word, room.settings.hints, elapsed, room.turnMs)
                    val hidden = room.word.indices.filter { room.word[it].isLetterOrDigit() && it !in room.revealed }.toMutableList()
                    while (room.revealed.size < due && hidden.isNotEmpty()) room.revealed += hidden.removeAt(random.nextInt(hidden.size))
                    val guessers = present(room, now).filter { it.id != room.drawer }
                    when {
                        drawerGone -> endTurn(room, now, "weg")
                        guessers.isNotEmpty() && guessers.all { it.guessed } -> endTurn(room, now, "alle")
                        now >= room.deadline -> endTurn(room, room.deadline.coerceAtLeast(now - 60_000), "zeit")
                        else -> return
                    }
                }
                Phase.REVEAL -> if (now >= room.deadline) nextTurn(room, now) else return
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

    // ---------- Chat ----------

    private fun line(room: Room, kind: String, from: Player?, text: String, to: Int) {
        room.chat.addLast(Line(++room.chatSeq, kind, from?.id ?: 0, from?.name ?: "", text, to))
        while (room.chat.size > CHAT_KEEP) room.chat.removeFirst()
    }

    private fun system(room: Room, kind: String, about: Player?, text: String) = line(room, kind, about, text, 0)

    // ---------- Antwort ----------

    private fun state(room: Room, me: Player, body: JsonObject, now: Long, error: String?, token: String? = null): JsonObject {
        val knows = me.id == room.drawer || me.guessed
        val s = room.settings
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = true
        if (error != null) out["error"] = error
        if (token != null) out["token"] = token
        out["you"] = me.id
        out["code"] = room.code
        out["host"] = room.host
        out["phase"] = room.phase.name.lowercase()
        out["round"] = room.round
        out["rounds"] = s.rounds
        out["turn"] = room.turn
        out["drawer"] = room.drawer
        out["left"] = if (room.deadline > 0) maxOf(0, room.deadline - now) else 0
        out["total"] = room.turnMs
        when (room.phase) {
            Phase.CHOOSE -> if (me.id == room.drawer) out["choices"] = room.choices
            Phase.DRAW -> {
                if (knows) out["word"] = room.word
                out["mask"] = DrawRules.mask(room.word, room.revealed)
            }
            Phase.REVEAL -> {
                out["word"] = room.word
                out["reason"] = room.reason
            }
            else -> Unit
        }
        if (room.note.isNotEmpty()) out["note"] = room.note
        out["players"] = room.players.values.map { p ->
            obj("id" to p.id, "name" to p.name, "av" to p.avatar, "score" to p.score, "gain" to p.gain, "guessed" to p.guessed, "on" to (now - p.lastSeen < OFFLINE_MS))
        }
        out["settings"] = obj(
            "rounds" to s.rounds, "seconds" to s.seconds, "hints" to s.hints, "cats" to s.categories.toList(), "customCount" to s.custom.size,
            "customOnly" to s.customOnly, "custom" to if (me.id == room.host) s.custom.joinToString(", ") else null,
        )
        if (room.phase == Phase.LOBBY) {
            out["categories"] = DrawWords.CATEGORIES.map { obj("id" to it.id, "label" to it.label, "count" to it.words.size) }
        }
        val since = body.int("c") ?: 0
        out["chat"] = room.chat.filter { it.n > since && (it.to == 0 || it.to == me.id || (it.to == KNOWING && (knows || room.phase != Phase.DRAW))) }
            .map { obj("n" to it.n, "k" to it.kind, "id" to it.from, "name" to it.name, "text" to it.text) }
        out["chatSeq"] = room.chatSeq
        // Striche: der Zeichner malt selbst und bekommt sie nur auf Wunsch (s >= 0, nach dem Neuladen der Seite)
        val gen = body.int("g") ?: -1
        val from = body.int("s") ?: 0
        out["gen"] = room.gen
        out["opSeq"] = room.ops.size
        if (from >= 0) {
            val start = if (gen == room.gen) from.coerceIn(0, room.ops.size) else 0
            out["ops"] = JsonArray(room.ops.subList(start, room.ops.size).toList())
            out["opFrom"] = start
        }
        if (me.id == room.drawer && room.phase == Phase.DRAW && (room.ops.size >= MAX_OPS || room.points >= MAX_POINTS - 800)) out["full"] = true
        return obj(*out.map { it.key to it.value }.toTypedArray())
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun bytes(element: JsonElement): ByteArray = element.toString().toByteArray(Charsets.UTF_8)

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    // ---------- für Tests ----------

    @Synchronized
    internal fun wordOf(code: String): String = rooms[code]?.word ?: ""

    @Synchronized
    internal fun roomCount(): Int = rooms.size

    companion object {
        const val PATH = "/api/kritzel"
        private val NO_STORE = "Cache-Control" to "no-store"
        private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val KNOWING = -1
        const val MAX_ROOMS = 200
        const val MAX_CREATE_PER_MINUTE = 20
        const val OFFLINE_MS = 8_000L
        const val DRAWER_AWAY_MS = 15_000L
        const val GONE_LOBBY_MS = 20_000L
        const val GONE_PLAYING_MS = 60_000L
        const val ROOM_IDLE_MS = 10 * 60_000L
        const val CHAT_GAP_MS = 200L
        const val CHAT_KEEP = 200
        const val MAX_OPS = 6000
        const val MAX_POINTS = 240_000
        const val MAX_OPS_PER_REQUEST = 60
        const val MAX_POINTS_PER_OP = 300
        private val GUESSED = listOf(
            "hat's erraten!", "hat's gecheckt!", "weiß Bescheid!", "hat den Durchblick!", "ist ein Genie (heute)!", "hat's raus!",
        )
    }
}
