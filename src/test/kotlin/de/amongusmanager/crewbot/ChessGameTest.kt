package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.ChessGame
import de.amongusmanager.crewbot.web.ChessMatch
import de.amongusmanager.crewbot.web.ChessPosition
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

class ChessGameTest {
    // ---------- Regeln ----------

    /** Bekannte Perft-Zahlen (chessprogramming.org) – prüfen Rochade, en passant, Umwandlung und Fesselungen. */
    @Test
    fun moveGeneratorMatchesKnownPerftNumbers() {
        fun perft(fen: String, depth: Int) = ChessPosition.perft(ChessPosition.fromFen(fen)!!, depth)
        assertEquals(20, perft(ChessPosition.START_FEN, 1))
        assertEquals(8902, perft(ChessPosition.START_FEN, 3))
        assertEquals(197281, perft(ChessPosition.START_FEN, 4))
        assertEquals(97862, perft("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 3))
        assertEquals(43238, perft("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 4))
        assertEquals(9467, perft("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 3))
        assertEquals(62379, perft("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 3))
        assertEquals(89890, perft("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 3))
    }

    @Test
    fun fenRoundTripsAndUciIsChecked() {
        val start = ChessPosition.START
        assertEquals(ChessPosition.START_FEN, start.fen())
        assertNull(start.parseUci("e2e5"))
        assertNull(start.parseUci("e7e5"))
        val after = start.play(start.parseUci("e2e4")!!)!!
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", after.fen())
        // Umwandlung braucht den Buchstaben
        val promo = ChessPosition.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1")!!
        assertNull(promo.parseUci("a7a8"))
        assertEquals("Q7/7k/8/8/8/8/8/K7 b - - 0 1", promo.play(promo.parseUci("a7a8q")!!)!!.fen())
        assertNotNull(promo.parseUci("a7a8n"))
    }

    @Test
    fun matchEndsWithMateStalemateRepetitionAndMaterial() {
        val mate = ChessMatch(0, 0, 0)
        for ((i, uci) in listOf("f2f3", "e7e5", "g2g4", "d8h4").withIndex()) assertNull(mate.move(i % 2, uci, 0))
        assertEquals("0-1", mate.result)
        assertEquals("mate", mate.reason)
        assertEquals("Die Partie ist vorbei.", mate.move(0, "e2e4", 0))

        val repeat = ChessMatch(0, 0, 0)
        val dance = listOf("g1f3", "g8f6", "f3g1", "f6g8")
        for (round in 0 until 2) for ((i, uci) in dance.withIndex()) assertNull(repeat.move(i % 2, uci, 0))
        assertEquals("1/2-1/2", repeat.result)
        assertEquals("repetition", repeat.reason)

        assertTrue(ChessPosition.fromFen("8/8/8/4k3/8/8/2B5/4K3 w - - 0 1")!!.insufficientMaterial())
        assertTrue(ChessPosition.fromFen("8/8/2b5/4k3/8/8/2B5/4K3 w - - 0 1")!!.insufficientMaterial())   // Läufer gleicher Farbe
        assertFalse(ChessPosition.fromFen("8/8/3b4/4k3/8/8/2B5/4K3 w - - 0 1")!!.insufficientMaterial())
        assertFalse(ChessPosition.fromFen("8/8/8/4k3/8/8/2N1N3/4K3 w - - 0 1")!!.insufficientMaterial())
        val stale = ChessPosition.fromFen("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1")!!
        assertTrue(stale.play(stale.parseUci("f7f6")!!)!!.legalMoves().isNotEmpty())
        val staleNext = stale.play(stale.parseUci("g6h6")!!)!!
        assertTrue(staleNext.legalMoves().isEmpty() && !staleNext.inCheck())
    }

    @Test
    fun clocksRunAfterTheFirstMovesAndFlagFalls() {
        val match = ChessMatch(60_000, 2_000, 0)
        assertNull(match.move(0, "e2e4", 50_000))   // erster Zug kostet keine Zeit
        assertNull(match.move(1, "e7e5", 55_000))
        assertTrue(match.clockRunning)
        assertNull(match.move(0, "g1f3", 65_000))   // 10 s verbraucht, 2 s zurück
        assertEquals(52_000, match.clock[0])
        assertEquals(60_000 - 5_000, match.remaining(70_000)[1])
        assertFalse(match.checkTime(124_000))
        assertTrue(match.checkTime(126_000))
        assertEquals("1-0", match.result)
        assertEquals("time", match.reason)

        val slow = ChessMatch(60_000, 0, 0)
        assertFalse(slow.checkTime(ChessMatch.FIRST_MOVE_MS))
        assertTrue(slow.checkTime(ChessMatch.FIRST_MOVE_MS + 1))
        assertEquals("*", slow.result)

        val untimed = ChessMatch(0, 0, 0)
        assertFalse(untimed.checkTime(10_000_000))
    }

    // ---------- Räume ----------

    private var now = 1_000_000L
    private val game = ChessGame(clock = { now }, random = Random(3))

    private fun call(body: JsonObject): JsonObject {
        val response = game.handle(WebRequest("POST", ChessGame.PATH, emptyMap(), emptyMap(), body.toString().toByteArray()), "1.2.3.4")
        assertEquals(200, response.status)
        return Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
    }

    private fun msg(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            null -> Unit
            else -> error("Typ")
        }
    }

    private class Seat(val code: String, val token: String)

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.boolean
    private fun JsonObject.obj(name: String) = this[name]!!.jsonObject
    private fun send(seat: Seat, action: String, vararg pairs: Pair<String, Any?>) = call(msg("a" to action, "room" to seat.code, "token" to seat.token, *pairs))

    /** Anna (Gastgeberin) und Ben starten; Ergebnis: (Weiß, Schwarz). */
    private fun started(color: String = "white", min: Int = 5): Pair<Seat, Seat> {
        val created = call(msg("a" to "create", "name" to "Anna", "min" to min, "inc" to 2, "color" to color))
        assertTrue(created.ok())
        val anna = Seat(created.str("code"), created.str("token"))
        val ben = Seat(anna.code, call(msg("a" to "join", "room" to anna.code, "name" to "Ben")).str("token"))
        assertEquals("Starten darf nur der Gastgeber.", send(ben, "start").str("error"))
        val state = send(anna, "start")
        assertEquals("play", state.str("phase"))
        val role = state.obj("me").str("role")
        return if (role == "w") anna to ben else ben to anna
    }

    @Test
    fun twoPlayersPlayAndSpectatorsWatch() {
        val (white, black) = started()
        assertEquals("w", send(white, "sync").obj("me").str("role"))
        val watcher = Seat(white.code, call(msg("a" to "join", "room" to white.code, "name" to "Cleo")).str("token"))
        assertEquals("spec", send(watcher, "sync").obj("me").str("role"))
        assertEquals("Du schaust nur zu.", send(watcher, "move", "m" to "e2e4").str("error"))
        assertEquals("Du bist nicht am Zug.", send(black, "move", "m" to "e7e5").str("error"))
        assertEquals("Dieser Zug ist nicht erlaubt.", send(white, "move", "m" to "e2e5").str("error"))
        for ((i, uci) in listOf("f2f3", "e7e5", "g2g4").withIndex()) assertTrue(send(if (i % 2 == 0) white else black, "move", "m" to uci).ok())
        val v = send(watcher, "sync")["v"]!!.jsonPrimitive.int
        assertTrue(send(watcher, "sync", "v" to v)["same"]!!.jsonPrimitive.boolean)
        val end = send(black, "move", "m" to "d8h4")
        assertEquals("end", end.str("phase"))
        assertEquals("0-1", end.obj("game").str("result"))
        assertEquals(listOf("f2f3", "e7e5", "g2g4", "d8h4"), end.obj("game")["moves"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Revanche: beide wollen, Farben tauschen
        send(white, "again")
        val again = send(black, "again")
        assertEquals("play", again.str("phase"))
        assertEquals("w", again.obj("me").str("role"))
        assertEquals(2, again["games"]!!.jsonPrimitive.int)
    }

    @Test
    fun resignDrawAndLeaving() {
        val (white, black) = started()
        assertTrue(send(white, "move", "m" to "e2e4").ok())
        assertTrue(send(black, "move", "m" to "e7e5").ok())
        send(white, "draw")
        assertEquals("w", send(black, "sync").obj("game").str("draw"))
        val agreed = send(black, "draw")
        assertEquals("1/2-1/2", agreed.obj("game").str("result"))
        assertEquals("agreed", agreed.obj("game").str("reason"))

        val (w2, b2) = started()
        send(w2, "move", "m" to "d2d4")
        send(b2, "move", "m" to "d7d5")
        assertEquals("0-1", send(w2, "resign").obj("game").str("result"))

        val (w3, b3) = started()
        send(w3, "move", "m" to "d2d4")
        send(b3, "move", "m" to "d7d5")
        send(b3, "leave")
        val left = send(w3, "sync")
        assertEquals("1-0", left.obj("game").str("result"))
        assertEquals("abandon", left.obj("game").str("reason"))
    }

    @Test
    fun timeRunsOutOnTheServer() {
        val (white, black) = started(min = 1)
        send(white, "move", "m" to "e2e4")
        send(black, "move", "m" to "e7e5")
        now += 30_000
        val state = send(black, "sync")
        assertTrue(state.obj("game")["running"]!!.jsonPrimitive.boolean)
        assertEquals(30_000, state.obj("game")["clock"]!!.jsonArray[0].jsonPrimitive.long)
        now += 31_000
        send(white, "sync")   // Weiß bleibt online, die Zeit ist trotzdem um
        val over = send(black, "sync")
        assertEquals("0-1", over.obj("game").str("result"))
        assertEquals("time", over.obj("game").str("reason"))
    }

    @Test
    fun liveStatusAndLimits() {
        started()
        val status = game.handle(WebRequest("GET", ChessGame.PATH, emptyMap(), emptyMap()), "1.2.3.4")
        val json = Json.parseToJsonElement(status.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, json["rooms"]!!.jsonPrimitive.int)
        assertEquals(2, json["players"]!!.jsonPrimitive.int)
        assertFalse(call(msg("a" to "create", "name" to "  ")).ok())
        assertFalse(call(msg("a" to "join", "room" to "QQQQ", "name" to "X")).ok())
        now += ChessGame.ROOM_IDLE_MS + 1
        game.handle(WebRequest("GET", ChessGame.PATH, emptyMap(), emptyMap()), "1.2.3.4")
        assertEquals(0, game.roomCount())
    }

    /** Bedenkzeiten und Reaktionen stehen auch in der Seite – beide Listen müssen gleich sein. */
    @Test
    fun pageUsesTheSameListsAsTheServer() {
        val page = Files.readString(Path.of("seiten/schach.html"))
        val times = Regex("""const TIMES = \[([^\]]*)]""").find(page)!!.groupValues[1]
        val minutes = Regex("""min: (\d+)""").findAll(times).map { it.groupValues[1].toInt() }.toSet()
        assertTrue(minutes.isNotEmpty() && ChessGame.TIME_MINUTES.containsAll(minutes))
        val reactions = Regex("""const REACTIONS = \[([^\]]*)]""").find(page)!!.groupValues[1]
        assertEquals(ChessGame.REACTIONS, Regex("'([^']+)'").findAll(reactions).map { it.groupValues[1] }.toList())
        assertTrue("/api/schach" in page)
    }
}
