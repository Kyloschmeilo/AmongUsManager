package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.ShipBoard
import de.amongusmanager.crewbot.web.ShipGame
import de.amongusmanager.crewbot.web.ShipMatch
import de.amongusmanager.crewbot.web.ShipRules
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShipGameTest {
    private var now = 1_000_000L
    private val game = ShipGame(clock = { now }, random = Random(5))

    private fun call(body: JsonObject): JsonObject {
        val response = game.handle(WebRequest("POST", ShipGame.PATH, emptyMap(), emptyMap(), body.toString().toByteArray()), "1.2.3.4")
        assertEquals(200, response.status)
        return Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
    }

    private fun msg(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is List<*> -> put(key, buildJsonArray {
                for (item in value) when (item) {
                    is Int -> add(JsonPrimitive(item))
                    is List<*> -> add(buildJsonArray { item.forEach { add(JsonPrimitive(it as Int)) } })
                    else -> error("Typ")
                }
            })
            null -> Unit
            else -> error("Typ")
        }
    }

    private class Seat(val code: String, val token: String)

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.boolean
    private fun JsonObject.obj(name: String) = this[name]!!.jsonObject
    private fun JsonObject.ints(name: String) = this[name]!!.jsonArray.map { it.jsonPrimitive.int }

    private fun send(seat: Seat, action: String, vararg pairs: Pair<String, Any?>): JsonObject =
        call(msg("a" to action, "room" to seat.code, "token" to seat.token, *pairs))

    private fun layoutOf(commander: String, seed: Int): List<List<Int>> = ShipBoard.random(commander, Random(seed)).ships.map { it.cells }

    /** Zwei Spieler im Gefecht: Anna (kallisto) und Ben (bellini). Liefert die Plätze in Zugreihenfolge (wer dran ist zuerst). */
    private fun battle(a: String = "kallisto", b: String = "bellini"): Pair<Seat, Seat> {
        val created = call(msg("a" to "create", "name" to "Anna"))
        val anna = Seat(created.str("code"), created.str("token"))
        val joined = call(msg("a" to "join", "room" to anna.code, "name" to "Ben"))
        val ben = Seat(anna.code, joined.str("token"))
        assertEquals("lobby", send(anna, "pick", "cmd" to a).str("phase"))
        assertEquals("place", send(ben, "pick", "cmd" to b).str("phase"))
        assertTrue(send(anna, "place", "ships" to layoutOf(a, 1)).ok())
        val state = send(ben, "place", "ships" to layoutOf(b, 2))
        assertEquals("battle", state.str("phase"))
        return if (state["myTurn"]!!.jsonPrimitive.boolean) ben to anna else anna to ben
    }

    @Test
    fun pageAndServerShareTheSameRules() {
        val page = listOf(Path.of("seiten/schiffe.html"), Path.of("../seiten/schiffe.html")).first { Files.exists(it) }
        val text = Files.readString(page)
        val rules = Json.parseToJsonElement(text.substringAfter("/*RULES*/").substringBefore("/*END*/")).jsonObject
        assertEquals(ShipRules.TURN_POINTS, rules["turn"]!!.jsonPrimitive.int)
        assertEquals(ShipRules.HIT_POINTS, rules["hit"]!!.jsonPrimitive.int)
        assertEquals(ShipRules.MAX_MINES, rules["maxMines"]!!.jsonPrimitive.int)
        assertEquals(ShipRules.MINE_SHOTS, rules["mineShots"]!!.jsonPrimitive.int)
        val cost = rules.obj("cost")
        assertEquals(listOf(ShipRules.COST_SONAR, ShipRules.COST_MINE, ShipRules.COST_BOMB), listOf("sonar", "mine", "bomb").map { cost[it]!!.jsonPrimitive.int })
        assertEquals(ShipRules.SHAPES, rules.obj("shapes").mapValues { it.value.jsonPrimitive.content })
        val commanders = rules.obj("commanders")
        assertEquals(ShipRules.COMMANDERS.map { it.id }, commanders.keys.toList())
        for (c in ShipRules.COMMANDERS) {
            assertEquals(c.cd, commanders.obj(c.id)["cd"]!!.jsonPrimitive.int, c.id)
            assertEquals(c.fleet, commanders.obj(c.id)["fleet"]!!.jsonArray.map { it.jsonPrimitive.content }, c.id)
            assertEquals(16, c.fleet.sumOf { ShipRules.cellsOf(it).size }, "${c.id}: jede Flotte hat 16 Felder")
            assertTrue(text.contains("id: '${c.id}'"), "${c.id} fehlt auf der Seite")
        }
    }

    @Test
    fun shapesMayBeRotatedAndMirroredButNotBent() {
        assertEquals(2, ShipRules.orientations("I4").size)
        assertEquals(8, ShipRules.orientations("L4").size)
        assertEquals(1, ShipRules.orientations("X5").size)
        assertTrue(ShipRules.fits("L3", listOf(0, 10, 11)))
        assertTrue(ShipRules.fits("L3", listOf(55, 56, 46)))
        assertFalse(ShipRules.fits("L3", listOf(0, 1, 2)))
        assertFalse(ShipRules.fits("I2", listOf(9, 10)), "nicht über den Rand")
        assertFalse(ShipRules.fits("I2", listOf(98, 108)))
        for (c in ShipRules.COMMANDERS) repeat(20) { assertTrue(ShipBoard.valid(c.id, layoutOf(c.id, it))) }
        val layout = layoutOf("kallisto", 3)
        assertFalse(ShipBoard.valid("kallisto", layout.dropLast(1)))
        assertFalse(ShipBoard.valid("kallisto", layout.dropLast(1) + listOf(layout[0].take(2))), "Überlappung")
        assertFalse(ShipBoard.valid("pembroke", layout), "falsche Flotte")
    }

    private fun match(a: String, b: String, first: Int = 0) =
        ShipMatch(listOf(ShipBoard.random(a, Random(11)), ShipBoard.random(b, Random(12))), first, Random(13))

    @Test
    fun pointsComeFromTurnsAndFromOwnShipsBeingHit() {
        val m = match("kallisto", "bellini")
        assertEquals(listOf(2, 0), m.points.toList())
        val water = (0 until 100).first { m.boards[1].grid[it] == 0 }
        val (events, error) = m.act(0, "shot", water)
        assertNull(error)
        assertEquals(listOf("volley", "turn"), events.map { it.type })
        assertEquals(listOf(2, 2), m.points.toList())
        assertEquals(1, m.turn)
        assertNotNull(m.act(0, "shot", 5).second, "nicht dran")
        val ship = m.boards[0].ships[0].cells[0]
        val (hit, _) = m.act(1, "shot", ship)
        assertEquals(listOf("volley"), hit.map { it.type }, "nach einem Treffer kein Zugwechsel")
        assertEquals(listOf(3, 2), m.points.toList(), "1 für den Treffer am eigenen Schiff, keine 2 ohne Zugwechsel")
        assertEquals(1, m.turn, "wer trifft, ist nochmal dran")
        val miss = (0 until 100).first { m.boards[0].grid[it] == 0 && m.boards[0].shots[it] == 0 }
        m.act(1, "shot", miss)
        assertEquals(listOf(5, 2), m.points.toList(), "Fehlschuss: Zug wechselt, 2 für den neuen Zug")
        assertNotNull(m.act(0, "shot", water).second, "schon beschossen")
        assertEquals(1, m.charge[0])
    }

    @Test
    fun sonarMineAndBombCostPoints() {
        val m = match("kallisto", "bellini")
        assertNotNull(m.act(0, "sonar", 0).second, "zu wenige Punkte")
        m.points[0] = 20
        assertNotNull(m.act(0, "sonar", 0).second, "braucht ein beschossenes Feld")
        // Bombe: 2×2, kostet 5, beendet den Zug nicht
        val (bomb, _) = m.act(0, "bomb", 99)
        assertEquals(listOf(88, 89, 98, 99), bomb.single().hits.map { it.cell }, "am Rand nach innen geschoben")
        assertEquals(0, m.turn)
        val hits = bomb.single().hits.count { it.result != "miss" }
        assertEquals(15, m.points[0])
        assertEquals(hits, m.points[1])
        // Sonar: zählt unentdeckte Schiffsteile in den acht Feldern drumherum
        val (sonar, _) = m.act(0, "sonar", 88)
        assertEquals("sonar", sonar.single().type)
        assertEquals(12, m.points[0])
        val around = listOf(77, 78, 79, 87, 89, 97, 98, 99).count { m.boards[1].grid[it] > 0 && m.boards[1].shots[it] == 0 }
        assertEquals(around, m.sonarCount(m.boards[1], 88))
        assertNotNull(m.act(0, "sonar", 88).second, "dort liegt schon eins")
        // Mine: nur auf eigenes freies Wasser, höchstens drei
        val ownShip = m.boards[0].ships[0].cells[0]
        assertNotNull(m.act(0, "mine", ownShip).second)
        val free = (0 until 100).filter { m.boards[0].grid[it] == 0 }
        for (i in 0 until 3) assertNull(m.act(0, "mine", free[i]).second)
        assertEquals(6, m.points[0])
        assertNotNull(m.act(0, "mine", free[3]).second, "höchstens drei")
        assertEquals(0, m.turn, "Sonar und Mine beenden den Zug nicht")
    }

    @Test
    fun hittingAMineFiresTwoRandomShotsBackAndEndsTheTurn() {
        val m = match("kallisto", "bellini")
        val free = (0 until 100).filter { m.boards[0].grid[it] == 0 }
        m.act(0, "mine", free[0])
        m.act(0, "shot", (0 until 100).first { m.boards[1].grid[it] == 0 })
        // Seite 1 wirft eine Bombe auf die Mine: eigentlich dürfte sie danach noch schießen
        m.points[1] = 10
        val (events, error) = m.act(1, "bomb", free[0])
        assertNull(error)
        assertEquals(listOf("volley", "volley", "turn"), events.map { it.type })
        assertTrue(events[0].hits.any { it.cell == free[0] && it.result == "mine" })
        assertEquals("mine", events[1].kind)
        assertEquals(0, events[1].by, "die Schüsse kommen vom Besitzer der Mine")
        assertEquals(2, events[1].hits.size)
        assertEquals(3, m.boards[1].shots.count { it != 0 }, "zwei Felder beim Schützen sind dazugekommen (eins war schon beschossen)")
        assertEquals(4, m.boards[0].shots[free[0]])
        assertTrue(m.boards[0].mines.isEmpty())
        assertEquals(0, m.turn, "der Zug ist vorbei")
    }

    @Test
    fun everyCommanderAbilityDoesWhatItSays() {
        fun ready(m: ShipMatch, side: Int = 0) { m.charge[side] = m.cooldown[side] }
        // Pfeilhagel: Ziel + 6 in der Nähe
        var m = match("kallisto", "bellini")
        assertNotNull(m.act(0, "abil", 55).second, "lädt noch")
        ready(m)
        var hits = m.act(0, "abil", 55).first.first().hits
        assertEquals(7, hits.size)
        assertEquals(55, hits[0].cell)
        assertTrue(hits.all { kotlin.math.abs(it.cell / 10 - 5) <= 2 && kotlin.math.abs(it.cell % 10 - 5) <= 2 })
        assertEquals(0, m.charge[0])
        // Präzisionsschlag: ein Treffer versenkt das ganze Schiff
        m = match("beaumont", "kallisto")
        ready(m)
        val big = m.boards[1].ships[0]
        hits = m.act(0, "abil", big.cells[2]).first.first().hits
        assertTrue(big.sunk)
        assertEquals(big.cells.toSet(), hits.map { it.cell }.toSet())
        assertEquals(5, m.points[1], "fünf Schiffsteile getroffen")
        assertEquals(0, m.turn, "Treffer mit der Fähigkeit: nochmal dran")
        // Salve: fünf frei gewählte Felder
        m = match("finch", "kallisto")
        ready(m)
        assertNotNull(m.act(0, "abil", 0, chosen = listOf(1, 2, 3, 4, 5, 6)).second, "höchstens fünf")
        hits = m.act(0, "abil", 0, chosen = listOf(1, 22, 43, 64, 85)).first.first().hits
        assertEquals(listOf(1, 22, 43, 64, 85), hits.map { it.cell })
        // Odins Würfel: sechs zufällige
        m = match("sigrun", "kallisto")
        ready(m)
        assertEquals(6, m.act(0, "abil", -1).first.first().hits.size)
        // Bombardamento: 3×3
        m = match("bellini", "kallisto")
        ready(m)
        assertEquals(setOf(0, 1, 10, 11), m.act(0, "abil", 0).first.first().hits.map { it.cell }.toSet(), "in der Ecke nur vier Felder")
        // Torpedo: läuft vom nahen Rand bis zum ersten Schiff
        m = match("petersen", "kallisto")
        ready(m)
        val row = (0 until 10).first { r -> (0 until 10).any { m.boards[1].grid[r * 10 + it] > 0 } }
        val firstShip = (0 until 10).first { m.boards[1].grid[row * 10 + it] > 0 }
        hits = m.act(0, "abil", row * 10).first.first().hits
        assertEquals((0..firstShip).map { row * 10 + it }, hits.map { it.cell })
        assertTrue(hits.last().result != "miss")
        // Luftangriff: ganze Spalte
        m = match("pembroke", "kallisto")
        ready(m)
        assertEquals((0 until 10).map { it * 10 + 3 }, m.act(0, "abil", 53, vertical = true).first.first().hits.map { it.cell })
    }

    @Test
    fun matchEndsWhenAllShipsAreSunk() {
        val m = match("kallisto", "bellini")
        val targets = m.boards[1].ships.flatMap { it.cells }
        val water = (0 until 100).filter { m.boards[0].grid[it] == 0 }.iterator()
        var last: List<ShipMatch.Event> = emptyList()
        for (t in targets) {
            last = m.act(0, "shot", t).first
            if (m.over) break
            m.act(1, "shot", water.next())
        }
        assertTrue(m.over)
        assertEquals(0, m.winner)
        assertEquals("end", last.last().type)
        assertNotNull(m.act(1, "shot", 0).second)
    }

    @Test
    fun twoPlayersPlayThroughARoom() {
        val (first, second) = battle()
        // Status für die Live-Leiste: ohne Namen; der Raum ist voll, also kein Code zum Beitreten
        val status = game.handle(WebRequest("GET", ShipGame.PATH, emptyMap(), emptyMap()), "1.2.3.4").body.toString(Charsets.UTF_8)
        assertEquals("""{"rooms":1,"players":2,"list":[]}""", status)
        assertFalse(send(second, "act", "m" to "shot", "t" to 0).ok(), "nicht dran")
        val match = game.matchOf(first.code)!!
        val water = (0 until 100).first { i -> match.boards.all { it.grid[i] == 0 } }
        val shot = send(first, "act", "m" to "shot", "t" to water)
        assertTrue(shot.ok(), shot.toString())
        assertFalse(shot["myTurn"]!!.jsonPrimitive.boolean)
        val events = shot["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("turn", "volley", "turn"), events.map { it.str("e") })
        assertEquals(0, events[1]["by"]!!.jsonPrimitive.int, "aus eigener Sicht bin ich Seite 0")
        // Der andere sieht dasselbe gespiegelt, und nur neue Ereignisse
        val seen = send(second, "sync", "since" to 1)
        val theirs = seen["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("volley", "turn"), theirs.map { it.str("e") })
        assertEquals(1, theirs[0]["by"]!!.jsonPrimitive.int)
        assertTrue(seen["myTurn"]!!.jsonPrimitive.boolean)
        assertEquals(seen.ints("pts"), shot.ints("pts").reversed())
        // Vom gegnerischen Feld gehen nur Schüsse und versenkte Schiffe raus, nie die Aufstellung
        assertEquals(setOf("shots", "sunk", "sonar", "left"), seen.obj("theirs").keys)
        assertTrue(seen.obj("theirs")["left"]!!.jsonArray.isEmpty())
        assertEquals(5, seen.obj("mine")["ships"]!!.jsonArray.size)
        // Mine legen: der Gegner erfährt nur, dass eine liegt
        val own = seen.obj("mine")["ships"]!!.jsonArray.flatMap { ship -> ship.jsonArray.map { it.jsonPrimitive.int } }.toSet()
        val spot = (1 until 100).first { it !in own }
        val laid = send(second, "act", "m" to "mine", "t" to spot)
        assertTrue(laid.ok(), laid.toString())
        assertEquals(listOf(spot), laid.obj("mine").ints("mines"))
        val hidden = send(first, "sync", "since" to 3)["events"]!!.jsonArray.single().jsonObject
        assertEquals("laid", hidden.str("e"))
        assertNull(hidden["t"], "wo die Mine liegt, bleibt geheim")
    }

    @Test
    fun leavingDuringBattleLetsTheOtherWinAndRematchGoesBackToLobby() {
        val (first, second) = battle()
        assertTrue(send(second, "leave")["left"]!!.jsonPrimitive.boolean)
        val state = send(first, "sync")
        assertEquals("end", state.str("phase"))
        assertTrue(state["won"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, state["foe"])
        assertTrue(state["events"]!!.jsonArray.last().jsonObject["left"]!!.jsonPrimitive.boolean)
        assertEquals("lobby", send(first, "again").str("phase"))
        // Wer lange weg ist, fliegt raus; leere Räume verschwinden
        now += ShipGame.ROOM_IDLE_MS + 1
        assertTrue(call(msg("a" to "sync", "room" to first.code, "token" to first.token))["gone"]!!.jsonPrimitive.boolean)
        assertEquals(0, game.roomCount())
    }

    @Test
    fun roomTakesOnlyTwoAndChecksPlacement() {
        val created = call(msg("a" to "create", "name" to "  Anna‮  "))
        val anna = Seat(created.str("code"), created.str("token"))
        assertEquals("Anna", created.obj("me").str("name"))
        assertFalse(call(msg("a" to "create", "name" to " ")).ok())
        val ben = Seat(anna.code, call(msg("a" to "join", "room" to anna.code.lowercase(), "name" to "Ben")).str("token"))
        assertFalse(call(msg("a" to "join", "room" to anna.code, "name" to "Cleo")).ok(), "nur zu zweit")
        assertFalse(call(msg("a" to "join", "room" to "QQQQ", "name" to "Cleo")).ok())
        // Wiederkommen mit dem eigenen Token geht immer
        assertTrue(call(msg("a" to "join", "room" to anna.code, "token" to ben.token)).ok())
        assertFalse(send(anna, "pick", "cmd" to "nelson").ok())
        send(anna, "pick", "cmd" to "finch")
        // Der Gegner sieht vor dem Start nicht, wen ich gewählt habe
        assertEquals("", send(ben, "sync").obj("foe").str("cmd"))
        assertTrue(send(ben, "sync").obj("foe")["picked"]!!.jsonPrimitive.boolean)
        send(ben, "pick", "cmd" to "finch")
        assertFalse(send(anna, "place", "ships" to layoutOf("kallisto", 1)).ok(), "falsche Flotte")
        assertFalse(send(anna, "place", "ships" to listOf(listOf(0, 1), listOf(0, 1))).ok())
        assertTrue(send(anna, "place", "ships" to layoutOf("finch", 1)).ok())
        assertEquals("place", send(anna, "sync").str("phase"))
        assertNull(game.matchOf(anna.code))
    }
}
