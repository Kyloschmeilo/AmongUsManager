package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Online-Schach zu zweit (Seite seiten/schach.html, `POST /api/schach`, `GET /api/schach` = Live-Status).
 * Ohne Anmeldung: Raum-Code aus 4 Buchstaben, Platz hängt an einem geheimen Token. Die ersten zwei im Raum spielen,
 * alle weiteren schauen zu. Der Server prüft jeden Zug ([ChessPosition]) und rechnet die Uhren ([ChessMatch]).
 * Alles nur im Speicher. Die Analyse nach der Partie macht die Seite selbst (Stockfish im Browser).
 */
class ChessGame(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    enum class Phase { LOBBY, PLAY, END }

    internal class Player(val id: Int, val token: String, val name: String) {
        var lastSeen = 0L
    }

    internal class Reaction(val id: Int, val name: String, val emoji: String)

    internal class Room(val code: String) {
        val players = LinkedHashMap<Int, Player>()
        var nextId = 1
        var host = 0
        var phase = Phase.LOBBY
        /** Bedenkzeit in Minuten (0 = ohne Uhr) und Aufschlag je Zug in Sekunden. */
        var minutes = 5
        var increment = 3
        /** Farbe des Gastgebers in der ersten Partie: random, white, black. */
        var color = "random"
        /** Spieler-ids [Weiß, Schwarz] der laufenden/letzten Partie. */
        var seats = listOf<Int>()
        var match: ChessMatch? = null
        var games = 0
        val rematch = BooleanArray(2)
        val reactions = ArrayDeque<Reaction>()
        var nextReaction = 1
        var note = ""
        /** Steigt bei jeder Änderung; die Seite fragt mit `v` nach und bekommt sonst nur {same:true}. */
        var version = 1
        var online = ""
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
                var error: String? = null
                when (action) {
                    "sync" -> if (body.int("v") == room.version) return json(200, obj("ok" to true, "same" to true))
                    "settings" -> error = settings(room, player, body)
                    "start" -> error = start(room, player, now)
                    "move" -> error = move(room, player, body.str("m") ?: "", now)
                    "resign" -> seatOf(room, player)?.let { room.match?.resign(it, now); end(room) } ?: run { error = "Du spielst nicht mit." }
                    "draw" -> seatOf(room, player)?.let { side ->
                        error = room.match?.offerDraw(side, now)
                        if (room.match?.over == true) end(room)
                    } ?: run { error = "Du spielst nicht mit." }
                    "decline" -> seatOf(room, player)?.let { room.match?.declineDraw(it) }
                    "again" -> error = again(room, player, now)
                    "lobby" -> if (room.phase == Phase.END && player.id == room.host) toLobby(room) else error = "Das darf nur der Gastgeber."
                    "react" -> react(room, player, body.str("e") ?: "")
                    "leave" -> {
                        remove(room, player, now)
                        if (room.players.isEmpty()) rooms.remove(room.code)
                        return json(200, obj("ok" to true, "left" to true))
                    }
                    else -> error = "Unbekannte Aktion"
                }
                if (action != "sync") room.version++
                json(200, state(room, player, now, error))
            }
        }
    }

    /** Für die Live-Leiste der Startseite: Räume, in denen gerade jemand ist (ohne Codes/Namen). */
    private fun status(): WebResponse {
        val now = clock()
        sweep(now)
        val online = rooms.values.map { room -> room.players.values.count { now - it.lastSeen < OFFLINE_MS } }.filter { it > 0 }
        return json(200, obj("rooms" to online.size, "players" to online.sum()))
    }

    // ---------- Raum betreten ----------

    private fun create(body: JsonObject, client: String, now: Long): WebResponse {
        val name = cleanName(body.str("name") ?: "")
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
        settings(room, null, body)
        val player = add(room, name, now)
        room.host = player.id
        return json(200, state(room, player, now, null, token = player.token))
    }

    private fun join(body: JsonObject, now: Long): WebResponse {
        val room = rooms[body.str("room")?.trim()?.uppercase() ?: ""]
            ?: return json(200, obj("ok" to false, "error" to "Diesen Raum gibt es nicht. Stimmt der Code?"))
        tick(room, now)
        val token = body.str("token") ?: ""
        val known = room.players.values.firstOrNull { token.isNotEmpty() && it.token == token }
        if (known != null) {
            known.lastSeen = now
            room.version++
            return json(200, state(room, known, now, null, token = known.token))
        }
        val name = cleanName(body.str("name") ?: "")
        if (name.isEmpty()) return json(200, obj("ok" to false, "error" to "Bitte gib einen Namen ein."))
        if (room.players.size >= MAX_PEOPLE) return json(200, obj("ok" to false, "error" to "Der Raum ist voll."))
        val player = add(room, name, now)
        room.active = now
        room.version++
        return json(200, state(room, player, now, null, token = player.token))
    }

    private fun add(room: Room, wanted: String, now: Long): Player {
        var name = wanted
        var n = 2
        while (room.players.values.any { it.name.equals(name, ignoreCase = true) }) name = "${wanted.take(13)} ${n++}"
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val player = Player(room.nextId++, token, name)
        player.lastSeen = now
        room.players[player.id] = player
        return player
    }

    // ---------- Aktionen ----------

    /** Die zwei, die in der Lobby spielen würden: die ersten beiden, die im Raum sind. */
    private fun lobbyPlayers(room: Room): List<Player> = room.players.values.take(2)

    private fun seatOf(room: Room, player: Player): Int? = room.seats.indexOf(player.id).takeIf { it >= 0 && room.match != null }

    private fun settings(room: Room, player: Player?, body: JsonObject): String? {
        if (player != null && player.id != room.host) return "Das darf nur der Gastgeber."
        if (room.phase != Phase.LOBBY) return "Die Partie läuft schon."
        body.int("min")?.let { if (it in TIME_MINUTES) room.minutes = it }
        body.int("inc")?.let { if (it in 0..MAX_INCREMENT) room.increment = it }
        body.str("color")?.let { if (it in COLORS) room.color = it }
        if (room.minutes == 0) room.increment = 0
        return null
    }

    private fun start(room: Room, player: Player, now: Long): String? {
        if (player.id != room.host) return "Starten darf nur der Gastgeber."
        if (room.phase != Phase.LOBBY) return "Die Partie läuft schon."
        val two = lobbyPlayers(room)
        if (two.size < 2) return "Es fehlt noch ein Gegner."
        val host = two.firstOrNull { it.id == room.host } ?: two[0]
        val guest = two.first { it.id != host.id }
        val hostWhite = when (room.color) {
            "white" -> true
            "black" -> false
            else -> random.nextBoolean()
        }
        begin(room, if (hostWhite) listOf(host.id, guest.id) else listOf(guest.id, host.id), now)
        return null
    }

    private fun begin(room: Room, seats: List<Int>, now: Long) {
        room.seats = seats
        room.match = ChessMatch(room.minutes * 60_000L, room.increment * 1000L, now)
        room.phase = Phase.PLAY
        room.games++
        room.rematch.fill(false)
        room.note = ""
    }

    private fun move(room: Room, player: Player, uci: String, now: Long): String? {
        val match = room.match
        if (room.phase != Phase.PLAY || match == null) return "Gerade läuft keine Partie."
        val side = seatOf(room, player) ?: return "Du schaust nur zu."
        val error = match.move(side, uci, now)
        if (match.over) end(room)
        return error
    }

    private fun end(room: Room) {
        if (room.match?.over == true) room.phase = Phase.END
    }

    /** Revanche: wenn beide wollen, neue Partie mit getauschten Farben. */
    private fun again(room: Room, player: Player, now: Long): String? {
        if (room.phase != Phase.END) return "Die Partie läuft noch."
        val side = room.seats.indexOf(player.id)
        if (side < 0) return "Du schaust nur zu."
        if (room.seats.any { it !in room.players }) return "Dein Gegner ist nicht mehr da."
        room.rematch[side] = true
        if (room.rematch.all { it }) begin(room, room.seats.reversed(), now)
        return null
    }

    private fun react(room: Room, player: Player, emoji: String) {
        if (emoji !in REACTIONS) return
        room.reactions.addLast(Reaction(room.nextReaction++, player.name, emoji))
        while (room.reactions.size > 12) room.reactions.removeFirst()
    }

    private fun toLobby(room: Room) {
        room.phase = Phase.LOBBY
        room.match = null
        room.seats = emptyList()
        room.rematch.fill(false)
        room.note = ""
    }

    private fun remove(room: Room, player: Player, now: Long) {
        room.players.remove(player.id)
        val side = room.seats.indexOf(player.id)
        if (side >= 0 && room.phase == Phase.PLAY) {
            room.match?.abandon(side, now)
            end(room)
            room.note = "${player.name} hat die Partie verlassen."
        } else {
            room.note = "${player.name} ist gegangen."
        }
        if (room.host == player.id) room.host = room.players.values.firstOrNull()?.id ?: 0
        room.version++
    }

    /** Uhren prüfen, und wer zu lange nichts von sich hören lässt, fliegt raus. */
    private fun tick(room: Room, now: Long) {
        val match = room.match
        if (room.phase == Phase.PLAY && match != null && match.checkTime(now)) {
            end(room)
            room.version++
        }
        for (p in room.players.values.toList()) {
            val seated = room.phase != Phase.LOBBY && p.id in room.seats
            val limit = if (seated) GONE_PLAYING_MS else GONE_LOBBY_MS
            if (now - p.lastSeen > limit) remove(room, p, now)
        }
        // Wer online ist, steht in der Antwort – ändert sich das, bekommen alle den neuen Stand
        val online = room.players.values.filter { now - it.lastSeen < OFFLINE_MS }.joinToString(",") { it.id.toString() }
        if (online != room.online) {
            room.online = online
            room.version++
        }
    }

    private fun sweep(now: Long) {
        val iterator = rooms.values.iterator()
        while (iterator.hasNext()) {
            val room = iterator.next()
            if (now - room.active > ROOM_IDLE_MS || room.players.isEmpty()) iterator.remove()
        }
        if (created.size > 500) created.values.removeIf { it.isEmpty() || now - it.last() > 60_000 }
    }

    // ---------- Antwort ----------

    private fun state(room: Room, me: Player, now: Long, error: String?, token: String? = null): JsonObject {
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = error == null
        if (error != null) out["error"] = error
        if (token != null) out["token"] = token
        out["code"] = room.code
        out["v"] = room.version
        out["phase"] = room.phase.name.lowercase()
        out["note"] = room.note
        val seat = room.seats.indexOf(me.id)
        val lobbyIds = lobbyPlayers(room).map { it.id }
        out["me"] = obj(
            "id" to me.id, "name" to me.name, "host" to (room.host == me.id),
            "role" to when {
                room.phase != Phase.LOBBY && seat == 0 -> "w"
                room.phase != Phase.LOBBY && seat == 1 -> "b"
                room.phase == Phase.LOBBY && me.id in lobbyIds -> "player"
                else -> "spec"
            },
        )
        out["people"] = room.players.values.map {
            obj(
                "id" to it.id, "name" to it.name, "online" to (now - it.lastSeen < OFFLINE_MS), "host" to (it.id == room.host),
                "playing" to if (room.phase == Phase.LOBBY) it.id in lobbyIds else it.id in room.seats,
            )
        }
        out["settings"] = obj("min" to room.minutes, "inc" to room.increment, "color" to room.color)
        out["games"] = room.games
        val match = room.match
        if (match != null) {
            val left = match.remaining(now)
            out["game"] = obj(
                "white" to seatJson(room, 0, now),
                "black" to seatJson(room, 1, now),
                "moves" to match.moves.toList(),
                "timed" to match.timed,
                "base" to match.baseMs,
                "inc" to match.incrementMs,
                "clock" to left.toList(),
                "running" to match.clockRunning,
                "firstLeft" to (if (match.timed && !match.over && match.moves.size < 2) (ChessMatch.FIRST_MOVE_MS - (now - match.turnStart)).coerceAtLeast(0) else null),
                "result" to match.result,
                "reason" to match.reason,
                "draw" to when (match.drawOffer) {
                    0 -> "w"
                    1 -> "b"
                    else -> null
                },
                "rematch" to room.rematch.toList(),
            )
        }
        out["reactions"] = room.reactions.map { obj("id" to it.id, "n" to it.name, "e" to it.emoji) }
        return obj(*out.map { it.key to it.value }.toTypedArray())
    }

    private fun seatJson(room: Room, side: Int, now: Long): JsonObject? {
        val id = room.seats.getOrNull(side) ?: return null
        val player = room.players[id]
        return obj("name" to (player?.name ?: "weg"), "online" to (player != null && now - player.lastSeen < OFFLINE_MS), "here" to (player != null))
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun bytes(element: JsonElement): ByteArray = element.toString().toByteArray(Charsets.UTF_8)

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    // ---------- für Tests ----------

    @Synchronized
    internal fun roomCount(): Int = rooms.size

    @Synchronized
    internal fun matchOf(code: String): ChessMatch? = rooms[code]?.match

    companion object {
        const val PATH = "/api/schach"
        private val NO_STORE = "Cache-Control" to "no-store"
        private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        /** Bedenkzeiten zur Auswahl (Minuten, 0 = ohne Uhr) – gleiche Liste in der Seite (TIMES). */
        val TIME_MINUTES = setOf(0, 1, 3, 5, 10, 15, 30)
        const val MAX_INCREMENT = 30
        val COLORS = setOf("random", "white", "black")
        /** Erlaubte Reaktionen – gleiche Liste in der Seite (REACTIONS). */
        val REACTIONS = listOf("👏", "😂", "😱", "🤔", "🔥", "😭", "🤝", "👀")
        const val MAX_ROOMS = 200
        const val MAX_PEOPLE = 22
        const val MAX_CREATE_PER_MINUTE = 20
        const val OFFLINE_MS = 8_000L
        const val GONE_LOBBY_MS = 30_000L
        const val GONE_PLAYING_MS = 90_000L
        const val ROOM_IDLE_MS = 30 * 60_000L

        fun cleanName(raw: String): String =
            raw.filter { !it.isISOControl() && it != '​' && it != '‮' }.trim().replace(Regex("\\s+"), " ").take(16).trim()
    }
}
