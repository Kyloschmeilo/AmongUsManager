/*
 * Schiffe versenken zu zweit (Seite seiten/schiffe.html, API POST /api/schiffe).
 *
 * Gegen den Computer läuft das Spiel komplett im Browser. Für zwei Menschen entscheidet der Server alles, damit keiner
 * das Feld des anderen sehen kann: Raum-Code aus 4 Buchstaben, Token aus create/join, alles nur im Speicher,
 * der Browser fragt per kleiner POST-Anfrage ("sync") nach (gleiches Muster wie Kritzelkiste und Meme-Schmiede).
 *
 * Regeln (dieselben wie im Skript der Seite, `ShipGameTest` prüft, dass beide dieselben Zahlen und Flotten haben):
 * 10×10, jeder Kommandeur hat eine eigene Flotte aus fünf Schiffen mit 16 Feldern (auch L-, T- und Z-Formen).
 * Punkte: 2 zu Beginn jedes eigenen Zugs, 1 für jedes eigene Schiffsteil, das getroffen wird. Vor dem Schuss darf man
 * Punkte ausgeben: Sonar (3) auf ein schon beschossenes Feld zeigt, wie viele unentdeckte Schiffsteile in den acht
 * Feldern drumherum liegen; Mine (2) auf ein eigenes Wasserfeld – trifft der Gegner sie, schlagen bei ihm zwei
 * zufällige Schüsse ein und sein Zug ist vorbei; Bombe (5) ist ein zusätzlicher 2×2-Schuss. Der normale Schuss oder
 * die Spezialfähigkeit des Kommandeurs (lädt mit jedem normalen Schuss) beendet den Zug – außer er trifft ein Schiff,
 * dann ist man nochmal dran (16.11.2026, Kilian). Die 2 Punkte gibt es nur, wenn der Zug wirklich wechselt.
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

/** Zahlen, Formen und Flotten (müssen zum Block RULES in seiten/schiffe.html passen). */
object ShipRules {
    const val N = 10
    const val TURN_POINTS = 2
    const val HIT_POINTS = 1
    const val COST_SONAR = 3
    const val COST_MINE = 2
    const val COST_BOMB = 5
    const val MAX_MINES = 3
    const val MINE_SHOTS = 2

    class Commander(val id: String, val cd: Int, val fleet: List<String>)

    /** Formen als Felder "Zeile Spalte" der Grundstellung. */
    val SHAPES: Map<String, String> = linkedMapOf(
        "I2" to "00 01", "I3" to "00 01 02", "I4" to "00 01 02 03", "I5" to "00 01 02 03 04",
        "L3" to "00 10 11", "L4" to "00 10 20 21", "T4" to "00 01 02 11", "S4" to "01 02 10 11", "O4" to "00 01 10 11",
        "V5" to "00 10 20 21 22", "T5" to "00 01 02 11 21", "P5" to "00 01 10 11 20", "U5" to "00 02 10 11 12",
        "Z5" to "00 01 11 21 22", "X5" to "01 10 11 12 21",
    )

    val COMMANDERS: List<Commander> = listOf(
        Commander("kallisto", 4, listOf("I5", "I4", "I3", "I2", "I2")),
        Commander("beaumont", 3, listOf("P5", "O4", "L3", "I2", "I2")),
        Commander("finch", 4, listOf("T5", "T4", "I3", "I2", "I2")),
        Commander("sigrun", 4, listOf("V5", "L4", "I3", "I2", "I2")),
        Commander("bellini", 5, listOf("U5", "S4", "L3", "I2", "I2")),
        Commander("petersen", 4, listOf("Z5", "I4", "I3", "I2", "I2")),
        Commander("pembroke", 6, listOf("X5", "T4", "L3", "I2", "I2")),
    )
    val BY_ID: Map<String, Commander> = COMMANDERS.associateBy { it.id }

    fun cellsOf(shape: String): List<Pair<Int, Int>> = SHAPES.getValue(shape).split(' ').map { (it[0] - '0') to (it[1] - '0') }

    private fun normalize(cells: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val r = cells.minOf { it.first }
        val c = cells.minOf { it.second }
        return cells.map { (it.first - r) to (it.second - c) }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** Alle Lagen einer Form (gedreht und gespiegelt), jeweils an die Ecke oben links geschoben. */
    fun orientations(shape: String): Set<List<Pair<Int, Int>>> {
        val out = LinkedHashSet<List<Pair<Int, Int>>>()
        var cells = cellsOf(shape)
        repeat(2) { mirror ->
            repeat(4) {
                out += normalize(cells)
                cells = cells.map { it.second to -it.first }
            }
            if (mirror == 0) cells = cells.map { it.first to -it.second }
        }
        return out
    }

    private val ORIENTATIONS: Map<String, Set<List<Pair<Int, Int>>>> = SHAPES.keys.associateWith { orientations(it) }

    /** Sind die Felder (0–99) genau diese Form in irgendeiner Lage? */
    fun fits(shape: String, cells: List<Int>): Boolean {
        if (cells.any { it !in 0 until N * N } || cells.toSet().size != cells.size) return false
        return normalize(cells.map { it / N to it % N }) in ORIENTATIONS.getValue(shape)
    }

    fun cleanName(raw: String): String =
        raw.filter { !it.isISOControl() && it != '​' && it != '‮' }.trim().replace(Regex("\\s+"), " ").take(16).trim()
}

/** Ein Feld mit Schiffen. shots: 0 offen, 1 Wasser, 2 Treffer, 3 versenkt, 4 Mine hochgegangen. */
class ShipBoard(val commander: String, layout: List<List<Int>>) {
    class Ship(val id: Int, val cells: List<Int>) {
        var hits = 0
        var sunk = false
    }

    val grid = IntArray(ShipRules.N * ShipRules.N)
    val shots = IntArray(ShipRules.N * ShipRules.N)
    val ships: List<Ship> = layout.mapIndexed { id, cells -> Ship(id, cells) }
    val mines = LinkedHashSet<Int>()

    init {
        for (ship in ships) for (cell in ship.cells) grid[cell] = ship.id + 1
    }

    val allSunk: Boolean get() = ships.all { it.sunk }

    fun open(): List<Int> = shots.indices.filter { shots[it] == 0 }

    companion object {
        /** Prüft eine Aufstellung: fünf Schiffe in den Formen der Flotte, im Feld, ohne Überlappung. */
        fun valid(commander: String, layout: List<List<Int>>): Boolean {
            val fleet = ShipRules.BY_ID[commander]?.fleet ?: return false
            if (layout.size != fleet.size) return false
            val used = HashSet<Int>()
            for ((index, cells) in layout.withIndex()) {
                if (!ShipRules.fits(fleet[index], cells)) return false
                for (cell in cells) if (!used.add(cell)) return false
            }
            return true
        }

        /** Zufällige Aufstellung (für Tests). */
        fun random(commander: String, random: Random): ShipBoard {
            val fleet = ShipRules.BY_ID.getValue(commander).fleet
            val used = HashSet<Int>()
            val layout = fleet.map { shape ->
                val options = ShipRules.orientations(shape).toList()
                var cells: List<Int>
                do {
                    val o = options[random.nextInt(options.size)]
                    val r = random.nextInt(ShipRules.N)
                    val c = random.nextInt(ShipRules.N)
                    cells = o.map { (dr, dc) -> if (r + dr < ShipRules.N && c + dc < ShipRules.N) (r + dr) * ShipRules.N + c + dc else -1 }
                } while (cells.any { it < 0 || it in used })
                used += cells
                cells
            }
            return ShipBoard(commander, layout)
        }
    }
}

/** Eine Partie: Regeln ohne Räume und ohne Netz. Seiten 0 und 1. */
class ShipMatch(val boards: List<ShipBoard>, first: Int, private val random: Random) {
    class Hit(val cell: Int, val result: String, val ship: ShipBoard.Ship? = null)

    /** Was passiert ist. by = wer gehandelt hat (bei "turn": wer jetzt dran ist, bei "end": wer gewonnen hat). */
    class Event(val type: String, val by: Int, val kind: String = "", val target: Int = -1, val vertical: Boolean = false,
                val hits: List<Hit> = emptyList(), val points: List<Int> = emptyList(), val left: Boolean = false) {
        var seq = 0
    }

    val points = intArrayOf(0, 0)
    val charge = intArrayOf(0, 0)
    val cooldown = IntArray(2) { ShipRules.BY_ID.getValue(boards[it].commander).cd }
    /** Sonar-Bojen je Seite: Felder auf dem Feld des Gegners. */
    val sonar = listOf(LinkedHashSet<Int>(), LinkedHashSet<Int>())
    var turn = first
        private set
    var winner = -1
        private set
    val over: Boolean get() = winner >= 0

    init {
        points[first] += ShipRules.TURN_POINTS
    }

    private fun at(r: Int, c: Int) = if (r in 0 until ShipRules.N && c in 0 until ShipRules.N) r * ShipRules.N + c else -1

    private fun pts() = points.toList()

    /** Unentdeckte Schiffsteile in den acht Feldern um das Feld. */
    fun sonarCount(board: ShipBoard, cell: Int): Int {
        var n = 0
        for (dr in -1..1) for (dc in -1..1) {
            val i = at(cell / ShipRules.N + dr, cell % ShipRules.N + dc)
            if (i >= 0 && i != cell && board.grid[i] > 0 && board.shots[i] == 0) n++
        }
        return n
    }

    private fun volley(by: Int, cells: List<Int>, kind: String, target: Int, vertical: Boolean): Event {
        val board = boards[1 - by]
        val hits = ArrayList<Hit>()
        for (cell in cells) {
            if (cell !in board.shots.indices || board.shots[cell] != 0) continue
            if (board.mines.remove(cell)) {
                board.shots[cell] = 4
                hits += Hit(cell, "mine")
                continue
            }
            val id = board.grid[cell] - 1
            if (id < 0) {
                board.shots[cell] = 1
                hits += Hit(cell, "miss")
                continue
            }
            val ship = board.ships[id]
            ship.hits++
            points[1 - by] += ShipRules.HIT_POINTS
            if (ship.hits >= ship.cells.size) {
                ship.sunk = true
                for (k in ship.cells) board.shots[k] = 3
                hits += Hit(cell, "sunk", ship)
            } else {
                board.shots[cell] = 2
                hits += Hit(cell, "hit")
            }
        }
        return Event("volley", by, kind, target, vertical, hits, pts())
    }

    /** Felder, die die Fähigkeit des Kommandeurs beschießt (null = so nicht erlaubt). */
    private fun abilityCells(by: Int, target: Int, vertical: Boolean, chosen: List<Int>): List<Int>? {
        val board = boards[1 - by]
        val n = ShipRules.N
        val inside = target in 0 until n * n
        val r = if (inside) target / n else 0
        val c = if (inside) target % n else 0
        fun line(): List<Int> = (0 until n).map { if (vertical) at(it, c) else at(r, it) }
        return when (boards[by].commander) {
            "kallisto" -> {
                if (!inside || board.shots[target] != 0) return null
                val near = ArrayList<Int>()
                for (dr in -2..2) for (dc in -2..2) {
                    val i = at(r + dr, c + dc)
                    if (i >= 0 && i != target && board.shots[i] == 0) near += i
                }
                listOf(target) + near.shuffled(random).take(6)
            }
            "beaumont" -> {
                if (!inside || board.shots[target] != 0) return null
                val id = board.grid[target] - 1
                if (id < 0 || target in board.mines) listOf(target)
                else listOf(target) + board.ships[id].cells.filter { it != target && board.shots[it] == 0 }
            }
            "finch" -> {
                val cells = chosen.distinct()
                if (cells.isEmpty() || cells.size > 5 || cells.any { it !in 0 until n * n || board.shots[it] != 0 }) return null
                cells
            }
            "sigrun" -> board.open().shuffled(random).take(6)
            "bellini" -> {
                if (!inside) return null
                val cells = ArrayList<Int>()
                for (dr in -1..1) for (dc in -1..1) {
                    val i = at(r + dr, c + dc)
                    if (i >= 0 && board.shots[i] == 0) cells += i
                }
                cells
            }
            "petersen" -> {
                if (!inside) return null
                var cells = line()
                if ((if (vertical) r else c) >= n / 2) cells = cells.reversed()
                val out = ArrayList<Int>()
                for (i in cells) {
                    if (board.shots[i] != 0) continue
                    out += i
                    if (board.grid[i] > 0 || i in board.mines) break
                }
                out
            }
            "pembroke" -> if (!inside) null else line().filter { board.shots[it] == 0 }
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    /**
     * Eine Aktion der Seite, die dran ist: shot | abil | bomb | sonar | mine. Liefert die Ereignisse oder einen Fehlertext.
     */
    fun act(side: Int, action: String, target: Int, vertical: Boolean = false, chosen: List<Int> = emptyList()): Pair<List<Event>, String?> {
        if (over) return emptyList<Event>() to "Das Spiel ist vorbei."
        if (side != turn) return emptyList<Event>() to "Du bist nicht dran."
        val enemy = boards[1 - side]
        val own = boards[side]
        val n = ShipRules.N
        val inside = target in 0 until n * n
        val events = ArrayList<Event>()
        var endsTurn = true
        when (action) {
            "shot" -> {
                if (!inside || enemy.shots[target] != 0) return events to "Das Feld ist schon beschossen."
                charge[side] = minOf(cooldown[side], charge[side] + 1)
                events += volley(side, listOf(target), "shot", target, false)
            }
            "abil" -> {
                if (charge[side] < cooldown[side]) return events to "Die Fähigkeit lädt noch."
                val cells = abilityCells(side, target, vertical, chosen) ?: return events to "Das geht hier nicht."
                charge[side] = 0
                events += volley(side, cells, "abil", target, vertical)
            }
            "bomb" -> {
                if (points[side] < ShipRules.COST_BOMB) return events to "Zu wenige Punkte."
                if (!inside) return events to "Das geht hier nicht."
                val r = minOf(target / n, n - 2)
                val c = minOf(target % n, n - 2)
                val cells = listOf(at(r, c), at(r, c + 1), at(r + 1, c), at(r + 1, c + 1)).filter { enemy.shots[it] == 0 }
                if (cells.isEmpty()) return events to "Dort ist schon alles beschossen."
                points[side] -= ShipRules.COST_BOMB
                events += volley(side, cells, "bomb", at(r, c), false)
                endsTurn = false
            }
            "sonar" -> {
                if (points[side] < ShipRules.COST_SONAR) return events to "Zu wenige Punkte."
                if (!inside || enemy.shots[target] == 0) return events to "Das Sonar braucht ein schon beschossenes Feld."
                if (target in sonar[side]) return events to "Dort liegt schon ein Sonar."
                points[side] -= ShipRules.COST_SONAR
                sonar[side] += target
                return listOf(Event("sonar", side, target = target, points = pts())) to null
            }
            "mine" -> {
                if (points[side] < ShipRules.COST_MINE) return events to "Zu wenige Punkte."
                if (!inside || own.grid[target] > 0 || own.shots[target] != 0 || target in own.mines) return events to "Minen gehen nur auf freies Wasser."
                if (own.mines.size >= ShipRules.MAX_MINES) return events to "Mehr als ${ShipRules.MAX_MINES} Minen gleichzeitig gehen nicht."
                points[side] -= ShipRules.COST_MINE
                own.mines += target
                return listOf(Event("laid", side, target = target, points = pts())) to null
            }
            else -> return events to "Unbekannte Aktion"
        }
        // Treffer (Schuss oder Fähigkeit): man ist nochmal dran
        if ((action == "shot" || action == "abil") && events.last().hits.any { it.result == "hit" || it.result == "sunk" }) endsTurn = false
        // Minen: je getroffener Mine zwei zufällige Schüsse auf das Feld des Schützen, sein Zug ist vorbei
        val mines = events.last().hits.count { it.result == "mine" }
        if (mines > 0) {
            val cells = own.open().shuffled(random).take(mines * ShipRules.MINE_SHOTS)
            if (cells.isNotEmpty()) events += volley(1 - side, cells, "mine", -1, false)
            endsTurn = true
        }
        if (enemy.allSunk) return finish(events, side)
        if (own.allSunk) return finish(events, 1 - side)
        if (endsTurn) {
            turn = 1 - side
            points[turn] += ShipRules.TURN_POINTS
            events += Event("turn", turn, points = pts())
        }
        return events to null
    }

    private fun finish(events: MutableList<Event>, won: Int): Pair<List<Event>, String?> {
        winner = won
        events += Event("end", won, points = pts())
        return events to null
    }

    /** Einer ist gegangen: der andere gewinnt. */
    fun forfeit(loser: Int): Event {
        winner = 1 - loser
        return Event("end", winner, points = pts(), left = true)
    }
}

class ShipGame(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    enum class Phase { LOBBY, PLACE, BATTLE, END }

    internal class Player(val id: Int, val token: String, var name: String) {
        var lastSeen = 0L
        var commander = ""
        var layout: List<List<Int>>? = null
    }

    internal class Room(val code: String) {
        val players = LinkedHashMap<Int, Player>()
        var nextId = 1
        var phase = Phase.LOBBY
        /** Spieler-ids der Seiten 0 und 1 der laufenden Partie. */
        var seats: List<Int> = emptyList()
        var match: ShipMatch? = null
        val events = ArrayList<ShipMatch.Event>()
        var seq = 0
        var note = ""
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
                    "sync" -> Unit
                    "pick" -> error = pick(room, player, body.str("cmd") ?: "")
                    "place" -> error = place(room, player, body)
                    "act" -> error = act(room, player, body)
                    "again" -> if (room.phase == Phase.END) toLobby(room)
                    "leave" -> {
                        remove(room, player)
                        if (room.players.isEmpty()) rooms.remove(room.code)
                        return json(200, obj("ok" to true, "left" to true))
                    }
                    else -> error = "Unbekannte Aktion"
                }
                json(200, state(room, player, body.int("since") ?: 0, now, error))
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
        val name = ShipRules.cleanName(body.str("name") ?: "")
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
        val player = add(room, name, now)
        return json(200, state(room, player, 0, now, null, token = player.token))
    }

    private fun join(body: JsonObject, now: Long): WebResponse {
        val room = rooms[body.str("room")?.trim()?.uppercase() ?: ""]
            ?: return json(200, obj("ok" to false, "error" to "Diesen Raum gibt es nicht. Stimmt der Code?"))
        tick(room, now)
        val token = body.str("token") ?: ""
        val known = room.players.values.firstOrNull { token.isNotEmpty() && it.token == token }
        if (known != null) {
            known.lastSeen = now
            return json(200, state(room, known, 0, now, null, token = known.token))
        }
        val name = ShipRules.cleanName(body.str("name") ?: "")
        if (name.isEmpty()) return json(200, obj("ok" to false, "error" to "Bitte gib einen Namen ein."))
        if (room.players.size >= 2) return json(200, obj("ok" to false, "error" to "Der Raum ist voll. Schiffe versenken geht nur zu zweit."))
        val player = add(room, name, now)
        room.active = now
        return json(200, state(room, player, 0, now, null, token = player.token))
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

    private fun pick(room: Room, player: Player, commander: String): String? {
        if (room.phase != Phase.LOBBY) return "Das Spiel läuft schon."
        if (commander.isNotEmpty() && commander !in ShipRules.BY_ID) return "Den Kommandeur gibt es nicht."
        player.commander = commander
        if (room.players.size == 2 && room.players.values.all { it.commander.isNotEmpty() }) {
            room.phase = Phase.PLACE
            room.seats = room.players.keys.toList()
            room.match = null
            room.events.clear()
            room.note = ""
            for (p in room.players.values) p.layout = null
        }
        return null
    }

    private fun place(room: Room, player: Player, body: JsonObject): String? {
        if (room.phase != Phase.PLACE) return "Gerade wird nicht aufgestellt."
        if (player.layout != null) return null
        val layout = (body["ships"] as? JsonArray)?.map { ship ->
            (ship as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: -1 } ?: emptyList()
        } ?: emptyList()
        if (!ShipBoard.valid(player.commander, layout)) return "Die Aufstellung passt nicht."
        player.layout = layout
        val both = room.seats.map { room.players[it] }
        if (both.all { it?.layout != null }) {
            room.match = ShipMatch(both.map { ShipBoard(it!!.commander, it.layout!!) }, random.nextInt(2), random)
            room.phase = Phase.BATTLE
            push(room, listOf(ShipMatch.Event("turn", room.match!!.turn, points = room.match!!.points.toList())))
        }
        return null
    }

    private fun act(room: Room, player: Player, body: JsonObject): String? {
        val match = room.match
        if (room.phase != Phase.BATTLE || match == null) return "Gerade läuft kein Gefecht."
        val side = room.seats.indexOf(player.id)
        if (side < 0) return "Du spielst nicht mit."
        val chosen = (body["cells"] as? JsonArray)?.take(8)?.map { (it as? JsonPrimitive)?.intOrNull ?: -1 } ?: emptyList()
        val (events, error) = match.act(side, body.str("m") ?: "", body.int("t") ?: -1, body.bool("v") ?: false, chosen)
        push(room, events)
        if (match.over) room.phase = Phase.END
        return error
    }

    private fun push(room: Room, events: List<ShipMatch.Event>) {
        for (event in events) {
            event.seq = ++room.seq
            room.events += event
        }
    }

    private fun toLobby(room: Room) {
        room.phase = Phase.LOBBY
        room.match = null
        room.seats = emptyList()
        room.events.clear()
        room.note = ""
        for (p in room.players.values) {
            p.commander = ""
            p.layout = null
        }
    }

    private fun remove(room: Room, player: Player) {
        room.players.remove(player.id)
        val side = room.seats.indexOf(player.id)
        if (side < 0) return
        when (room.phase) {
            Phase.BATTLE -> {
                room.match?.let { push(room, listOf(it.forfeit(side))) }
                room.phase = Phase.END
                room.note = "${player.name} ist gegangen. Du gewinnst kampflos."
            }
            Phase.PLACE -> {
                toLobby(room)
                room.note = "${player.name} ist gegangen."
            }
            else -> room.note = "${player.name} ist gegangen."
        }
    }

    /** Wer zu lange nichts von sich hören lässt, fliegt raus. */
    private fun tick(room: Room, now: Long) {
        val limit = if (room.phase == Phase.LOBBY || room.phase == Phase.END) GONE_LOBBY_MS else GONE_PLAYING_MS
        for (p in room.players.values.filter { now - it.lastSeen > limit }) remove(room, p)
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

    /** Alles aus Sicht eines Spielers: Seite 0 = er selbst, 1 = der Gegner. Vom gegnerischen Feld nur, was er sehen darf. */
    private fun state(room: Room, me: Player, since: Int, now: Long, error: String?, token: String? = null): JsonObject {
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = error == null
        if (error != null) out["error"] = error
        if (token != null) out["token"] = token
        out["code"] = room.code
        out["phase"] = room.phase.name.lowercase()
        out["note"] = room.note
        val foe = room.players.values.firstOrNull { it.id != me.id }
        val playing = room.phase != Phase.LOBBY
        out["me"] = obj("name" to me.name, "cmd" to me.commander, "ready" to (me.layout != null))
        out["foe"] = foe?.let {
            obj("name" to it.name, "online" to (now - it.lastSeen < OFFLINE_MS), "picked" to it.commander.isNotEmpty(),
                "cmd" to (if (playing) it.commander else ""), "ready" to (it.layout != null))
        }
        out["seq"] = room.seq
        val match = room.match
        val side = room.seats.indexOf(me.id)
        if (match != null && side >= 0) {
            fun rel(by: Int) = if (by == side) 0 else 1
            fun both(values: List<Int>) = listOf(values[side], values[1 - side])
            out["myTurn"] = !match.over && match.turn == side
            out["pts"] = both(match.points.toList())
            out["charge"] = both(match.charge.toList())
            out["cd"] = both(match.cooldown.toList())
            if (match.over) out["won"] = match.winner == side
            val own = match.boards[side]
            val enemy = match.boards[1 - side]
            out["mine"] = obj(
                "ships" to own.ships.map { it.cells },
                "shots" to own.shots.joinToString(""),
                "mines" to own.mines.toList(),
            )
            out["theirs"] = obj(
                "shots" to enemy.shots.joinToString(""),
                "sunk" to enemy.ships.filter { it.sunk }.map { obj("id" to it.id, "cells" to it.cells) },
                "sonar" to match.sonar[side].map { obj("t" to it, "n" to match.sonarCount(enemy, it)) },
                "left" to (if (match.over) enemy.ships.filter { !it.sunk }.map { obj("id" to it.id, "cells" to it.cells) } else emptyList<Any>()),
            )
            out["events"] = room.events.filter { it.seq > since }.map { event ->
                val e = LinkedHashMap<String, Any?>()
                e["seq"] = event.seq
                e["e"] = event.type
                e["by"] = rel(event.by)
                if (event.points.isNotEmpty()) e["pts"] = both(event.points)
                when (event.type) {
                    "volley" -> {
                        e["kind"] = event.kind
                        e["t"] = event.target
                        e["v"] = event.vertical
                        e["res"] = event.hits.map { hit ->
                            if (hit.ship != null) obj("i" to hit.cell, "r" to hit.result, "ship" to hit.ship.id, "cells" to hit.ship.cells)
                            else obj("i" to hit.cell, "r" to hit.result)
                        }
                    }
                    // Wo das Sonar liegt, sieht auch der Gegner – die Zahl nur der Besitzer; wo die Mine liegt, nur der Besitzer
                    "sonar" -> {
                        e["t"] = event.target
                        if (event.by == side) e["n"] = match.sonarCount(enemy, event.target)
                    }
                    "laid" -> if (event.by == side) e["t"] = event.target
                    "end" -> if (event.left) e["left"] = true
                }
                e
            }
        } else {
            out["events"] = emptyList<Any>()
        }
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
    internal fun matchOf(code: String): ShipMatch? = rooms[code]?.match

    @Synchronized
    internal fun seatsOf(code: String): List<Int> = rooms[code]?.seats ?: emptyList()

    companion object {
        const val PATH = "/api/schiffe"
        private val NO_STORE = "Cache-Control" to "no-store"
        private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val MAX_ROOMS = 200
        const val MAX_CREATE_PER_MINUTE = 20
        const val OFFLINE_MS = 8_000L
        const val GONE_LOBBY_MS = 30_000L
        const val GONE_PLAYING_MS = 120_000L
        const val ROOM_IDLE_MS = 15 * 60_000L
    }
}
