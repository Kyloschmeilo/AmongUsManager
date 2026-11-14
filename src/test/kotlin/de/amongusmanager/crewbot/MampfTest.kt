package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.DirectorySource
import de.amongusmanager.crewbot.web.MampfGame
import de.amongusmanager.crewbot.web.MampfRules
import de.amongusmanager.crewbot.web.MampfThing
import de.amongusmanager.crewbot.web.MampfWorld
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.Json
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MampfTest {
    private fun world() = MampfWorld(Random(7), botTarget = 0, virusTarget = 0)

    @Test
    fun bigBlobsEatSmallerOnesButNotEqualOnes() {
        val world = world()
        val big = world.addPlayer("Groß", 0, 0)
        val small = world.addPlayer("Klein", 1, 100)
        val twin = world.addPlayer("Zwilling", 2, 200)
        world.spawn(big, 200.0, 1000.0 to 1000.0)
        world.spawn(small, 100.0, 1010.0 to 1000.0)
        world.spawn(twin, 190.0, 3000.0 to 3000.0)
        world.step(0.04)
        assertFalse(small.alive)
        assertTrue(big.mass > 299)
        assertEquals(1, world.kills.size)
        assertEquals("Klein", world.kills.single().victim.name)

        // ähnlich groß: keiner frisst den anderen
        world.kills.clear()
        val other = world.addPlayer("Gleich", 3, 300)
        world.spawn(other, 180.0, 3005.0 to 3000.0)
        world.step(0.04)
        assertTrue(twin.alive && other.alive)
        assertTrue(world.kills.isEmpty())
    }

    @Test
    fun splittingHalvesAndPiecesMergeLater() {
        val world = world()
        val player = world.addPlayer("Teiler", 0, 0)
        world.spawn(player, 400.0, 2500.0 to 2500.0)
        player.targetX = 3500.0
        player.targetY = 2500.0
        player.splits = 1
        world.step(0.04)
        assertEquals(2, player.cells.size)
        assertEquals(200.0, player.cells[0].mass, 2.0)
        // das abgeschossene Teil fliegt nach vorne
        assertTrue(player.cells[1].x > player.cells[0].x + 20)

        // Spucken kostet Masse und erzeugt Kleckse (die das vordere Teil auch gleich wieder fressen kann)
        val before = player.mass
        player.ejects = 1
        world.step(0.04)
        assertTrue(world.spit.isNotEmpty())
        assertTrue(player.mass < before)

        // nach der Wartezeit wachsen die Teile wieder zusammen
        player.targetX = player.x
        player.targetY = player.y
        repeat((MampfRules.mergeDelay(200.0) / 0.04).toInt() + 200) { world.step(0.04) }
        assertEquals(1, player.cells.size)
    }

    @Test
    fun cactusPopsBigBlobsAndSpitGrowsNewCactus() {
        val world = world()
        val cactus = world.addVirus(1500.0, 1500.0)
        val fat = world.addPlayer("Dick", 0, 0)
        world.spawn(fat, 600.0, 1500.0 to 1500.0)
        world.step(0.04)
        assertTrue(fat.cells.size > 5, "zerplatzt in ${fat.cells.size} Teile")
        assertFalse(cactus in world.viruses)

        // kleine Blobs können sich hinter Kakteen verstecken
        val small = world.addPlayer("Klein", 1, 0)
        val cactus2 = world.addVirus(4000.0, 4000.0)
        world.spawn(small, 50.0, 4000.0 to 4000.0)
        world.step(0.04)
        assertEquals(1, small.cells.size)
        assertTrue(cactus2 in world.viruses)

        // sieben Mal anspucken → der Kaktus schießt einen neuen in Flugrichtung ab
        val before = world.viruses.size
        repeat(MampfRules.VIRUS_FEED) {
            world.spit += MampfThing(world.newId(), 3880.0, 4000.0, 0, MampfRules.EJECT_MASS).apply { vx = 700.0 }
            repeat(10) { world.step(0.02) }
        }
        assertEquals(before + 1, world.viruses.size)
        val shot = world.viruses.last()
        repeat(30) { world.step(0.02) }
        assertTrue(shot.x > cactus2.x + 100, "fliegt nach rechts: ${shot.x}")
    }

    @Test
    fun botsFillTheWorldAndPlay() {
        val world = MampfWorld(Random(3), botTarget = 8)
        repeat(1500) { world.step(0.04) } // eine Minute
        assertEquals(8, world.players.size)
        assertTrue(world.players.values.all { it.bot })
        assertTrue(world.players.values.count { it.alive } >= 6)
        assertTrue(world.players.values.maxOf { it.peak } > 60, "Bots fressen")
        // echte Spieler verdrängen Bots
        repeat(3) { world.addPlayer("Mensch $it", 0, 0) }
        repeat(400) { world.step(0.04) }
        assertTrue(world.players.values.count { it.bot } <= 5)
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("Hallo Welt", MampfGame.cleanName("  Hallo \n\t Welt  "))
        assertEquals("1234567890123456", MampfGame.cleanName("12345678901234567890"))
        assertEquals("", MampfGame.cleanName("\u0000‮"))
    }

    private fun post(game: MampfGame, route: String, body: String, ip: String = "1.2.3.4") =
        game.handle(WebRequest("POST", "${MampfGame.PATH}/$route", emptyMap(), emptyMap(), body.toByteArray()), ip)

    private fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes)).jsonObject

    @Test
    fun protocolJoinPlayDieAndRecord() {
        val now = AtomicLong(1_000_000)
        val file = Files.createTempDirectory("mampf").resolve("rekorde.json")
        val game = MampfGame(file, Random(1), clock = now::get)
        try {
            val join = post(game, "join", """{"name":"<b>Tester</b>","face":99,"hue":-20}""")
            assertEquals(200, join.status)
            val token = json(join.body)["token"]!!.jsonPrimitive.content
            val session = assertNotNull(game.session(token))

            fun step() {
                now.addAndGet(40)
                game.tick()
            }
            fun frame(): JsonObject = generateSequence { session.queue.poll() }.map { json(it.toByteArray()) }.first { "lb" !in it }
            step()
            val first = frame()
            assertEquals(1, first["full"]!!.jsonPrimitive.int)
            val you = first["you"]!!.jsonPrimitive.int
            val me = first["me"]!!.jsonArray
            assertEquals(1, me[3].jsonPrimitive.int)
            // Name wird nicht umgebaut, nur als JSON-Text verschickt; Gesicht/Farbe begrenzt
            val p = first["p"]!!.jsonArray.map { it.jsonArray }.first { it[0].jsonPrimitive.int == you }
            assertEquals("<b>Tester</b>", p[1].jsonPrimitive.content)
            assertEquals(15, p[2].jsonPrimitive.int)
            assertEquals(340, p[3].jsonPrimitive.int)
            assertTrue(first["fa"]!!.jsonArray.size > 20, "Krümel im Bild")

            // Eingabe bewegt den Blob
            val player = game.world.players.getValue(you)
            val startX = player.x
            assertEquals(200, post(game, "input", """{"t":"$token","x":${player.x + 2000},"y":${player.y}}""").status)
            repeat(10) { step() }
            assertTrue(player.x > startX + 50)

            // der nächste Frame schickt nur neue/verschwundene Krümel, nicht alles nochmal
            session.queue.clear()
            step()
            val next = frame()
            assertFalse("full" in next)

            // gefressen werden: Todesmeldung + Rekord
            val killer = game.world.addPlayer("Riese", 0, 0)
            player.cells.single().mass = 300.0
            player.peak = 300.0
            game.world.spawn(killer, 2000.0, player.x to player.y)
            session.queue.clear()
            step()
            assertFalse(player.alive)
            val frames = generateSequence { session.queue.poll() }.map { json(it.toByteArray()) }.toList()
            val dead = frames.firstNotNullOf { it["dead"]?.jsonObject }
            assertEquals("Riese", dead["by"]!!.jsonPrimitive.content)
            assertTrue(dead["peak"]!!.jsonPrimitive.int >= 300)
            val info = json(game.handle(WebRequest("GET", MampfGame.PATH, emptyMap(), emptyMap()), "x").body)
            assertEquals("<b>Tester</b>", info["records"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(Files.readString(file).contains("Tester"))
            // Rekorde überleben einen Neustart
            assertEquals(1, json(MampfGame(file).handle(WebRequest("GET", MampfGame.PATH, emptyMap(), emptyMap()), "x").body)["records"]!!.jsonArray.size)

            // nochmal spielen
            assertEquals(200, post(game, "spawn", """{"t":"$token","name":"Zweiter Versuch"}""").status)
            step()
            assertTrue(player.alive)
            assertEquals("Zweiter Versuch", player.name)

            // falsche Sitzung, kaputtes JSON, Abmelden, Zeitüberschreitung
            assertEquals(410, post(game, "input", """{"t":"gibtsnicht","x":1,"y":1}""").status)
            assertEquals(400, post(game, "input", "kaputt").status)
            assertEquals(200, post(game, "leave", """{"t":"$token"}""").status)
            step()
            assertEquals(null, game.session(token))
            assertFalse(you in game.world.players)

            val t2 = json(post(game, "join", """{"name":"Schläfer"}""").body)["token"]!!.jsonPrimitive.content
            step()
            now.addAndGet(MampfGame.TIMEOUT_MS + 1)
            step()
            assertEquals(null, game.session(t2))
        } finally {
            game.stop()
        }
    }

    @Test
    fun limitsPerAddress() {
        val game = MampfGame(null, Random(1))
        try {
            repeat(MampfGame.MAX_PER_IP) { assertEquals(200, post(game, "join", "{}", ip = "9.9.9.9").status) }
            assertEquals(429, post(game, "join", "{}", ip = "9.9.9.9").status)
            assertEquals(200, post(game, "join", "{}", ip = "8.8.8.8").status)
        } finally {
            game.stop()
        }
    }

    @Test
    fun serverStreamsTheWorld() {
        val store = StatsStore(DirectorySource(Files.createTempDirectory("runden")))
        store.refresh()
        val game = MampfGame(null)
        val server = StatsServer(store, 0, "127.0.0.1", mampf = game)
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.port}"
            val join = client.send(
                HttpRequest.newBuilder(URI("$base/api/mampf/join")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"name":"Netz"}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, join.statusCode())
            val token = Json.parseToJsonElement(join.body()).jsonObject["token"]!!.jsonPrimitive.content
            assertEquals(410, client.send(HttpRequest.newBuilder(URI("$base/api/mampf/stream?t=falsch")).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
            val stream = client.send(HttpRequest.newBuilder(URI("$base/api/mampf/stream?t=$token")).build(), HttpResponse.BodyHandlers.ofInputStream())
            assertEquals("text/event-stream; charset=utf-8", stream.headers().firstValue("Content-Type").orElse(""))
            BufferedReader(InputStreamReader(stream.body())).use { reader ->
                val frames = ArrayList<JsonObject>()
                while (frames.size < 30) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data: ")) frames += Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                }
                assertTrue(frames.any { "full" in it })
                assertTrue(frames.any { "lb" in it }, "Bestenliste kommt mit")
                val board = frames.last { "lb" in it }["lb"]!!.jsonArray
                assertTrue(board.size > 5, "Bots sind da: $board")
            }
            val info = client.send(HttpRequest.newBuilder(URI("$base/api/mampf")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(1, Json.parseToJsonElement(info.body()).jsonObject["online"]!!.jsonPrimitive.int)
        } finally {
            server.stop()
            game.stop()
        }
    }
}
