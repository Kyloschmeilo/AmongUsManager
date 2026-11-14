package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.DirectorySource
import de.amongusmanager.crewbot.web.DrawGame
import de.amongusmanager.crewbot.web.DrawRules
import de.amongusmanager.crewbot.web.DrawWords
import de.amongusmanager.crewbot.web.Pages
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawGameTest {
    private var now = 1_000_000L
    private val game = DrawGame(clock = { now }, random = Random(7))

    private fun call(body: JsonObject, client: String = "1.2.3.4"): JsonObject {
        val response = game.handle(WebRequest("POST", DrawGame.PATH, emptyMap(), emptyMap(), body.toString().toByteArray()), client)
        assertEquals(200, response.status)
        return Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
    }

    private fun msg(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is JsonArray -> put(key, value)
            null -> Unit
            else -> error("Typ")
        }
    }

    private class Seat(val code: String, val token: String, val id: Int)

    private fun create(name: String): Seat {
        val res = call(msg("a" to "create", "name" to name))
        assertTrue(res["ok"]!!.jsonPrimitive.content.toBoolean(), res.toString())
        return Seat(res.str("code"), res.str("token"), res["you"]!!.jsonPrimitive.int)
    }

    private fun join(code: String, name: String): Seat {
        val res = call(msg("a" to "join", "room" to code, "name" to name))
        assertTrue(res.ok(), res.toString())
        return Seat(res.str("code"), res.str("token"), res["you"]!!.jsonPrimitive.int)
    }

    private fun act(seat: Seat, action: String, vararg extra: Pair<String, Any?>): JsonObject =
        call(msg("a" to action, "room" to seat.code, "token" to seat.token, *extra))

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
    private fun JsonObject.ok() = this["ok"]?.jsonPrimitive?.content == "true"
    private fun JsonObject.scoreOf(id: Int) = this["players"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.int == id }["score"]!!.jsonPrimitive.int

    /** Lässt Zeit vergehen, während alle wie der Browser regelmäßig nachfragen. */
    private fun advance(seats: List<Seat>, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 2_000L)
            now += step
            left -= step
            for (s in seats) act(s, "sync")
        }
    }

    @Test
    fun rulesCompareGuessesForgivingly() {
        assertTrue(DrawRules.matches("  Kühlschrank ", "Kühlschrank"))
        assertTrue(DrawRules.matches("kuehlschrank", "Kühlschrank"))
        assertTrue(DrawRules.matches("eis-am stiel!", "Eis am Stiel"))
        assertFalse(DrawRules.matches("", "Eis"))
        assertTrue(DrawRules.close("Kühlschrnk", "Kühlschrank"))
        assertTrue(DrawRules.close("Pinguinn", "Pinguin"))
        assertFalse(DrawRules.close("Pinguin", "Pinguin"))
        assertFalse(DrawRules.close("Hund", "Pinguin"))
        assertFalse(DrawRules.close("Ex", "Ei"), "kurze Wörter nie „knapp daneben“")

        assertEquals("___ __ _____", DrawRules.mask("Eis am Stiel", emptySet()))
        assertEquals("_-____", DrawRules.mask("U-Boot", emptySet()))
        assertEquals("E__ __ _____", DrawRules.mask("Eis am Stiel", setOf(0)))

        assertEquals(550, DrawRules.guessPoints(80_000, 80_000, first = true))
        assertEquals(100, DrawRules.guessPoints(0, 80_000, first = false))
        assertEquals(300, DrawRules.guessPoints(40_000, 80_000, first = false))
        assertEquals(0, DrawRules.drawerPoints(emptyList()))
        assertEquals(200, DrawRules.drawerPoints(listOf(400, 0)))

        assertEquals(0, DrawRules.hintsDue("Pinguin", 2, 10_000, 80_000))
        assertEquals(2, DrawRules.hintsDue("Pinguin", 2, 79_000, 80_000))
        assertEquals(0, DrawRules.hintsDue("Ei", 3, 79_000, 80_000), "nie mehr als die Hälfte verraten")

        assertEquals("Kilian", DrawRules.cleanName("  Kilian\u0000 "))
        assertEquals(16, DrawRules.cleanName("x".repeat(40)).length)
        assertEquals(listOf("Kilians Auto", "Mett"), DrawRules.customWords("Kilians Auto, Mett, mett\nx, "))
    }

    @Test
    fun wordListsAreUsable() {
        assertTrue(DrawWords.CATEGORIES.map { it.id }.toSet().containsAll(DrawWords.DEFAULT))
        for (category in DrawWords.CATEGORIES) {
            assertTrue(category.words.size >= 50, "${category.id} hat nur ${category.words.size} Wörter")
            for (word in category.words) assertTrue(word.length in 2..30 && DrawRules.normalize(word).isNotEmpty(), word)
        }
    }

    @Test
    fun playsAFullGameWithPointsAndSecrecy() {
        val host = create("Kilian")
        val anna = join(host.code, "Anna")
        val ben = join(host.code, "Ben")

        // Nur der Host stellt ein, Grenzen werden eingehalten
        act(anna, "settings", "rounds" to 9)
        var state = act(host, "settings", "rounds" to 1, "seconds" to 5000, "hints" to 1)
        assertEquals(1, state["settings"]!!.jsonObject["rounds"]!!.jsonPrimitive.int)
        assertEquals(240, state["settings"]!!.jsonObject["seconds"]!!.jsonPrimitive.int)
        state = act(host, "settings", "seconds" to 60)

        act(anna, "start")
        assertEquals("lobby", act(anna, "sync").str("phase"))
        state = act(host, "start")
        assertEquals("choose", state.str("phase"))
        val drawerId = state["drawer"]!!.jsonPrimitive.int
        val seats = listOf(host, anna, ben)
        val drawer = seats.first { it.id == drawerId }
        val guessers = seats.filter { it.id != drawerId }

        // Wortauswahl sieht nur der Zeichner
        for (g in guessers) assertNull(act(g, "sync")["choices"])
        val choices = act(drawer, "sync")["choices"]!!.jsonArray
        assertEquals(3, choices.size)
        state = act(drawer, "pick", "i" to 1)
        assertEquals("draw", state.str("phase"))
        val word = game.wordOf(host.code)
        assertEquals(choices[1].jsonPrimitive.content, word)
        assertEquals(word, state.str("word"))

        // Ratende bekommen nur die Lücken, nie das Wort
        val guesserView = act(guessers[0], "sync")
        assertNull(guesserView["word"])
        assertFalse(guesserView.toString().contains(word, ignoreCase = true), "Wort darf nicht durchsickern")
        assertEquals(DrawRules.mask(word, emptySet()), guesserView.str("mask"))

        // Striche gehen an die Ratenden, Fremde dürfen nicht malen
        val ops = buildJsonArray {
            add(buildJsonObject { put("k", "l"); put("i", 1); put("c", 2); put("w", 1); put("p", buildJsonArray { listOf(10, 10, 50, 60, 900, -5).forEach { add(JsonPrimitive(it)) } }) })
            add(buildJsonObject { put("k", "f"); put("c", 4); put("x", 400); put("y", 300) })
        }
        act(guessers[0], "sync", "ops" to ops)
        assertEquals(0, act(guessers[1], "sync", "s" to 0)["ops"]!!.jsonArray.size)
        act(drawer, "sync", "ops" to ops, "s" to -1)
        val seen = act(guessers[1], "sync", "s" to 0)
        val got = seen["ops"]!!.jsonArray
        assertEquals(2, got.size)
        assertEquals(listOf(10, 10, 50, 60, 800, 0), got[0].jsonObject["p"]!!.jsonArray.map { it.jsonPrimitive.int }, "Punkte werden auf die Fläche begrenzt")
        val gen = seen["gen"]!!.jsonPrimitive.int
        assertEquals(0, act(guessers[1], "sync", "s" to 2, "g" to gen)["ops"]!!.jsonArray.size, "nur Neues")
        assertNull(act(drawer, "sync", "s" to -1)["ops"], "Zeichner bekommt seine Striche nicht zurück")

        // Raten: knapp daneben nur für einen selbst, richtig gibt Punkte
        now += 400
        act(guessers[0], "say", "text" to word.dropLast(1) + "x")
        val chatOther = act(guessers[1], "sync", "c" to 0)["chat"]!!.jsonArray.map { it.jsonObject }
        assertFalse(chatOther.any { it.str("k") == "close" }, "„knapp daneben“ sehen die anderen nicht")
        advance(seats, 10_000)
        val after = act(guessers[0], "say", "text" to word.uppercase())
        assertTrue(after["players"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.int == guessers[0].id && it.jsonObject["guessed"]!!.jsonPrimitive.content == "true" })
        assertEquals(word, after.str("word"), "wer's erraten hat, sieht das Wort")
        val first = after.scoreOf(guessers[0].id)
        assertTrue(first in 400..550, "Punkte $first")
        val ownChat = after["chat"]!!.jsonArray.map { it.jsonObject }
        assertFalse(ownChat.any { it.str("text").contains(word, ignoreCase = true) && it.str("k") == "msg" }, "richtige Lösung erscheint nicht im Chat")

        // Wer das Wort kennt, schreibt nur für Eingeweihte
        now += 400
        act(guessers[0], "say", "text" to "Das ist $word lol")
        val other = act(guessers[1], "sync", "c" to 0)["chat"]!!.jsonArray
        assertFalse(other.toString().contains(word, ignoreCase = true), "Eingeweihte verraten nichts")
        assertTrue(act(drawer, "sync", "c" to 0)["chat"]!!.jsonArray.any { it.jsonObject.str("k") == "hush" })

        // Letzter rät → Runde vorbei, Zeichner bekommt den Durchschnitt
        now += 400
        val done = act(guessers[1], "say", "text" to word)
        assertEquals("reveal", done.str("phase"))
        assertEquals(word, done.str("word"))
        val drawerPts = done.scoreOf(drawer.id)
        assertEquals(DrawRules.drawerPoints(listOf(first, done.scoreOf(guessers[1].id))), drawerPts)

        // weiter: alle malen einmal (1 Runde), Zeit läuft ab
        repeat(2) {
            advance(seats, DrawRules.REVEAL_MS + 1)
            state = act(host, "sync")
            assertEquals("choose", state.str("phase"))
            advance(seats, DrawRules.CHOOSE_MS + 1)
            state = act(host, "sync")
            assertEquals("draw", state.str("phase"), "Wort wird nach Ablauf automatisch gewählt")
            // Hinweise kommen mit der Zeit
            advance(seats, 55_000)
            val mask = act(seats.first { it.id != state["drawer"]!!.jsonPrimitive.int }, "sync").str("mask")
            if (game.wordOf(host.code).count { it.isLetterOrDigit() } >= 3) assertTrue(mask.any { it.isLetterOrDigit() }, "Tipp nach Zeit: $mask")
            advance(seats, 6_000)
            assertEquals("reveal", act(host, "sync").str("phase"))
        }
        advance(seats, DrawRules.REVEAL_MS + 1)
        state = act(host, "sync")
        assertEquals("final", state.str("phase"))
        assertTrue(state["chat"].toString().isNotEmpty())

        // Nochmal → zurück in die Lobby, Punkte bleiben bis zum Start
        assertEquals("final", act(anna, "again").str("phase"), "nur der Host")
        assertEquals("lobby", act(host, "again").str("phase"))
        assertEquals("choose", act(host, "start").str("phase"))
        assertEquals(0, act(host, "sync").scoreOf(drawer.id))
    }

    @Test
    fun handlesLeavingKickingAndReconnecting() {
        val host = create("Host")
        val anna = join(host.code, "Anna")
        val annaAgain = join(host.code, "Anna")
        assertEquals("Anna 2", act(annaAgain, "sync")["players"]!!.jsonArray.last().jsonObject.str("name"), "doppelte Namen bekommen eine Nummer")

        // Wiederkommen mit Token = gleicher Platz
        val back = call(msg("a" to "join", "room" to host.code, "token" to anna.token))
        assertEquals(anna.id, back["you"]!!.jsonPrimitive.int)
        assertEquals(3, back["players"]!!.jsonArray.size)

        act(anna, "kick", "id" to host.id)
        assertEquals(3, act(host, "sync")["players"]!!.jsonArray.size, "nur der Host wirft raus")
        act(host, "kick", "id" to annaAgain.id)
        val kicked = act(annaAgain, "sync")
        assertFalse(kicked.ok())
        assertEquals("true", kicked["gone"]!!.jsonPrimitive.content)

        // Host geht → Anna wird Host
        act(host, "leave")
        assertEquals(anna.id, act(anna, "sync")["host"]!!.jsonPrimitive.int)

        // Abwesende fliegen raus, leere Räume verschwinden
        val other = join(host.code, "Carl")
        now += DrawGame.GONE_LOBBY_MS + 1
        act(other, "sync")
        assertEquals(1, act(other, "sync")["players"]!!.jsonArray.size)
        now += DrawGame.ROOM_IDLE_MS + 1
        create("Neu")
        assertEquals(1, game.roomCount())

        assertFalse(call(msg("a" to "join", "room" to "ZZZZ", "name" to "x")).ok())
        assertFalse(call(msg("a" to "create", "name" to "   ")).ok())
    }

    @Test
    fun gameEndsWhenPlayersDropOut() {
        val host = create("Host")
        val anna = join(host.code, "Anna")
        act(host, "start")
        // Anna meldet sich nicht mehr
        now += DrawGame.OFFLINE_MS + 1
        val state = act(host, "sync")
        assertEquals("final", state.str("phase"))
        assertContains(state.str("note"), "Zu wenige")
        assertEquals(2, state["players"]!!.jsonArray.size, "Anna bleibt noch kurz in der Liste")
        assertNotNull(anna)
    }

    @Test
    fun limitsRoomCreationPerClient() {
        repeat(DrawGame.MAX_CREATE_PER_MINUTE) { create("R$it") }
        assertFalse(call(msg("a" to "create", "name" to "zu viel")).ok())
        assertTrue(call(msg("a" to "create", "name" to "anderer"), client = "5.6.7.8").ok())
    }

    @Test
    fun serverRoutesTheGameAndShipsThePage() {
        val pagesDir = Path.of("seiten")
        assertTrue(Files.isRegularFile(pagesDir.resolve("kritzeln.html")), "seiten/kritzeln.html fehlt")
        val html = Files.readString(pagesDir.resolve("kritzeln.html"))
        assertContains(html, DrawGame.PATH)
        assertFalse(Regex("<script[^>]+src=|<link[^>]+stylesheet").containsMatchIn(html), "Seite soll ohne fremde Dateien auskommen")

        val store = StatsStore(DirectorySource(Files.createTempDirectory("runden")))
        store.refresh()
        val server = StatsServer(store, 0, "127.0.0.1", pages = Pages(pagesDir), drawGame = DrawGame())
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.port}"
            val page = client.send(HttpRequest.newBuilder(URI("$base/seiten/kritzeln.html")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, page.statusCode())
            assertContains(page.body(), "Kritzelkiste")
            val res = client.send(
                HttpRequest.newBuilder(URI("$base${DrawGame.PATH}")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"a":"create","name":"Test"}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode())
            assertContains(res.body(), "\"code\"")
            assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(""))
            val get = client.send(HttpRequest.newBuilder(URI("$base${DrawGame.PATH}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, get.statusCode())
        } finally {
            server.stop()
        }
    }
}
