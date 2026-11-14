package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.web.DirectorySource
import de.amongusmanager.crewbot.web.NoodleGame
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoodleGameTest {
    private fun init(game: NoodleGame, client: NoodleGame.Client): JsonObject {
        val message = client.queue.poll()!!
        return parseJson(message) as JsonObject
    }

    @Test
    fun arenaFillsWithBotsAndFoodOnlyWhileSomeoneWatches() {
        val game = NoodleGame(random = Random(1))
        game.tick()
        assertTrue(game.allSnakes().isEmpty(), "ohne Zuschauer rechnet nichts")
        val client = game.connect("1.2.3.4")!!
        val first = init(game, client)
        assertEquals(NoodleGame.MIN_SNAKES, first["snakes"]!!.jsonArray.size)
        assertTrue(game.allSnakes().all { it.bot })
        assertTrue(game.foodCount() >= NoodleGame.FOOD_TARGET - 1)
        assertEquals(client.token, first["k"]!!.jsonPrimitive.content)
        var last = ""
        repeat(20 * 20) { // 20 Sekunden Bots (der Browser liest mit)
            game.tick()
            while (true) last = client.queue.poll() ?: break
        }
        assertTrue(game.allSnakes().size >= NoodleGame.MIN_SNAKES - 3, "tote Bots rücken nach")
        assertTrue(game.allSnakes().all { hypot(it.x, it.y) <= NoodleGame.RADIUS })
        // jeder Tick schickt einen Stand
        val step = parseJson(last) as JsonObject
        assertEquals("s", step["t"]!!.jsonPrimitive.content)
        // wer nicht mitliest, fliegt raus (statt dass sich alles im Speicher staut)
        repeat(NoodleGame.MAX_QUEUE + 2 * NoodleGame.TICKS) { game.tick() }
        assertContains(generateSequence { client.queue.poll() }.last(), "\"slow\"")
    }

    @Test
    fun playerSteersGrowsAndDiesAtTheRimIntoTheLeaderboard() {
        val file = Files.createTempDirectory("nudel").resolve("top.json")
        val game = NoodleGame(file, Random(7))
        val client = game.connect("1.2.3.4")!!
        assertNull(game.join("falsch", "x", 0))
        val id = game.join(client.token, "  Kilian\u0000  der   Große Nudelkönig ", 3)!!
        assertEquals(id, game.join(client.token, "zweimal", 1), "wer schon spielt, behält seine Nudel")
        val me = game.snakeOf(client)!!
        assertEquals("Kilian der Große", me.name)
        assertFalse(me.bot)
        // Krümel direkt vors Maul: wächst
        me.length = 600.0
        // geradewegs nach außen
        game.steer(client.token, atan2(me.y, me.x), boost = true)
        var ticks = 0
        while (game.snakeOf(client) != null && ticks < 20 * 120) {
            game.tick(); ticks++
            client.queue.clear()
        }
        assertNull(game.snakeOf(client), "Rand oder andere Nudel erwischt")
        assertTrue(ticks < 20 * 120)
        val top = game.topScores()
        assertEquals(1, top.size)
        assertEquals("Kilian der Große", top[0].name)
        // Bestenliste überlebt einen Neustart
        assertEquals(top[0].score, NoodleGame(file).topScores().single().score)
        // tot: Join gibt eine neue Nudel
        assertNotNull(game.join(client.token, "Kilian", 3))
        game.disconnect(client)
        assertTrue(game.allSnakes().none { !it.bot }, "wer geht, dessen Nudel verschwindet")
    }

    @Test
    fun boostingCostsLengthAndDropsCrumbs() {
        val game = NoodleGame(random = Random(3))
        val client = game.connect("1.2.3.4")!!
        game.join(client.token, "Speedy", 0)
        val me = game.snakeOf(client)!!
        me.length = 400.0
        me.x = 0.0; me.y = 0.0; me.angle = 0.0
        game.steer(client.token, 0.0, boost = true)
        val before = me.length
        repeat(10) { game.tick() }
        if (game.snakeOf(client) != null) assertTrue(me.length < before)
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("Hallo Welt", NoodleGame.cleanName("  Hallo \n\t Welt​ "))
        assertEquals(16, NoodleGame.cleanName("x".repeat(40)).length)
        assertEquals("", NoodleGame.cleanName("\u0000\u0001"))
    }

    @Test
    fun serverStreamsTheArenaAndTakesInput() {
        val game = NoodleGame(random = Random(5))
        val store = StatsStore(DirectorySource(Files.createTempDirectory("runden")))
        store.refresh()
        val server = StatsServer(store, 0, "127.0.0.1", noodles = game)
        server.start()
        game.start()
        try {
            val http = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.port}"
            val status = http.send(HttpRequest.newBuilder(URI("$base${NoodleGame.PATH}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, status.statusCode())
            assertContains(status.body(), "\"top\"")

            val stream = http.send(HttpRequest.newBuilder(URI("$base${NoodleGame.STREAM_PATH}")).build(), HttpResponse.BodyHandlers.ofInputStream())
            assertEquals("text/event-stream; charset=utf-8", stream.headers().firstValue("Content-Type").orElse(""))
            val reader = BufferedReader(InputStreamReader(stream.body(), Charsets.UTF_8))
            fun next(): JsonObject {
                while (true) {
                    val line = reader.readLine() ?: error("Stream zu")
                    if (line.startsWith("data: ")) return parseJson(line.removePrefix("data: ")) as JsonObject
                }
            }
            val first = next()
            assertEquals("init", first["t"]!!.jsonPrimitive.content)
            val token = first["k"]!!.jsonPrimitive.content
            val join = http.send(
                HttpRequest.newBuilder(URI("$base${NoodleGame.PATH}/join")).POST(HttpRequest.BodyPublishers.ofString("""{"k":"$token","name":"Testnudel","skin":4}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, join.statusCode())
            val id = (parseJson(join.body()) as JsonObject)["id"]!!.jsonPrimitive.int
            val steer = http.send(
                HttpRequest.newBuilder(URI("$base${NoodleGame.PATH}/steer")).POST(HttpRequest.BodyPublishers.ofString("$token;1.5;0")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, steer.statusCode())
            val bad = http.send(
                HttpRequest.newBuilder(URI("$base${NoodleGame.PATH}/steer")).POST(HttpRequest.BodyPublishers.ofString("$token;NaN;0")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, bad.statusCode())
            // die neue Nudel taucht im Stand auf
            var seen = false
            repeat(40) {
                val step = next()
                val born = step["n"]?.jsonArray ?: return@repeat
                if (born.any { it.jsonArray[0].jsonPrimitive.int == id && it.jsonArray[1].jsonPrimitive.content == "Testnudel" }) seen = true
            }
            assertTrue(seen)
            stream.body().close()
        } finally {
            game.stop()
            server.stop()
        }
    }
}
