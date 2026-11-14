package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.web.DirectorySource
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WordGame
import de.amongusmanager.crewbot.web.WordGameWords
import de.amongusmanager.crewbot.web.WordRoom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordGameTest {
    private val closers = ArrayList<AutoCloseable>()

    @AfterTest
    fun close() = closers.reversed().forEach { runCatching { it.close() } }

    private fun room(): Pair<WordRoom, List<WordRoom.Player>> {
        val room = WordRoom("ABCD", Random(7))
        val players = listOf("A1" to "a", "A2" to "a", "B1" to "b", "B2" to "b").mapIndexed { index, (name, team) ->
            WordRoom.Player("p$index", "t$index", name, 0).also {
                room.players[it.id] = it
                assertNull(room.seat(it, team, if (name.endsWith("1")) "chief" else "agent"))
            }
        }
        room.hostId = players[0].id
        return room to players
    }

    @Test
    fun wordPacksAreBigEnoughAndClean() {
        for (pack in WordGameWords.PACKS) {
            assertTrue(pack.words.size >= 100, pack.id)
            assertEquals(pack.words.size, pack.words.toSet().size, pack.id)
            assertTrue(pack.words.all { it == it.uppercase() && it.length in 2..18 }, pack.id)
        }
        assertEquals(listOf("KILIAN", "MONTAGS RUNDE", "X-WING"), WordGameWords.custom("Kilian, montags   runde;x-wing\n<b>,a, kilian"))
    }

    @Test
    fun roundFollowsTheRules() {
        val (room, p) = room()
        val (chiefA, agentA, chiefB, agentB) = p
        assertEquals("Nur der Host startet die Runde.", room.start(agentB))
        assertNull(room.start(chiefA))
        assertEquals(25, room.cards.size)
        val first = room.turn
        val second = if (first == "a") "b" else "a"
        assertEquals(9, room.left(first))
        assertEquals(8, room.left(second))
        assertEquals(1, room.cards.count { it.color == "x" })
        assertEquals(7, room.cards.count { it.color == "n" })

        // Sichten: Funker sehen die Farben, Spürnasen nicht
        fun colors(player: WordRoom.Player) = room.view(player)["cards"]!!.jsonArray.map { it.jsonObject["c"] }
        assertTrue(colors(chiefA).none { it is JsonNull })
        assertTrue(colors(agentA).all { it is JsonNull })
        assertTrue(colors(agentB).all { it is JsonNull })

        val chief = if (first == "a") chiefA else chiefB
        val agent = if (first == "a") agentA else agentB
        val enemyAgent = if (first == "a") agentB else agentA
        val open = room.cards.first { it.color == first }.word
        assertContains(room.giveClue(chief, open, 2)!!, "offen")
        assertContains(room.giveClue(chief, "zwei wörter", 2)!!, "ein Wort")
        assertNotNull(room.giveClue(enemyAgent, "TIER", 2))
        assertNull(room.giveClue(chief, "tier", 2))
        assertEquals("TIER", room.clue?.word)
        assertEquals(3, room.guessesLeft)

        // Antippen: nur das Team, das dran ist
        val own = room.cards.indices.filter { room.cards[it].color == first }
        assertNotNull(room.tip(enemyAgent, own[0]))
        assertNull(room.tip(agent, own[0]))
        assertEquals(setOf(agent.id), room.cards[own[0]].tips)

        // zwei richtige + Bonus-Versuch, dann ist der Zug vorbei
        assertNull(room.reveal(agent, own[0]))
        assertNull(room.reveal(agent, own[1]))
        assertNull(room.reveal(agent, own[2]))
        assertEquals(second, room.turn)
        assertEquals(WordRoom.Phase.CLUE, room.phase)
        assertEquals(6, room.left(first))

        // Passant beendet den Zug
        val otherChief = if (second == "a") chiefA else chiefB
        val otherAgent = if (second == "a") agentA else agentB
        assertNull(room.giveClue(otherChief, "WASSER", 0))
        assertEquals(-1, room.guessesLeft)
        assertNull(room.reveal(otherAgent, room.cards.indexOfFirst { it.color == "n" }))
        assertEquals(first, room.turn)

        // Bombe: sofort verloren
        assertNull(room.giveClue(chief, "BUMM", 1))
        assertNull(room.reveal(agent, room.cards.indexOfFirst { it.color == "x" }))
        assertEquals(WordRoom.Phase.OVER, room.phase)
        assertEquals(second, room.winner)
        assertEquals("bomb", room.reason)
        assertEquals(1, room.wins[second])
        assertTrue(colors(agentA).none { it is JsonNull }) // nach dem Ende sehen alle die Farben

        // nächste Runde: neue Wörter
        val before = room.cards.map { it.word }.toSet()
        assertNull(room.start(chiefA))
        assertTrue(room.cards.none { it.word in before })
    }

    @Test
    fun lastWordWinsAndGiftsCount() {
        val (room, p) = room()
        assertNull(room.start(p[0]))
        val first = room.turn
        val second = if (first == "a") "b" else "a"
        val chief = p.first { it.team == first && it.role == "chief" }
        val agent = p.first { it.team == first && it.role == "agent" }
        assertNull(room.giveClue(chief, "ALLES", -1))
        // alle fremden bis auf eins … geht nicht, ein fremdes beendet den Zug – also: das gegnerische Team bis auf 1 leeren
        room.cards.filter { it.color == second }.drop(1).forEach { it.revealed = true }
        assertNull(room.reveal(agent, room.cards.indexOfFirst { it.color == second && !it.revealed }))
        assertEquals(WordRoom.Phase.OVER, room.phase)
        assertEquals(second, room.winner)
        assertEquals("gift", room.reason)
    }

    @Test
    fun whoeverSawTheKeyCannotGuess() {
        val (room, p) = room()
        assertNull(room.start(p[0]))
        val chiefA = p[0]
        assertContains(room.seat(chiefA, "a", "agent")!!, "Funker")
        assertContains(room.seat(chiefA, "b", "chief")!!, "Funker")
        // Spürnase wird Funker (zweiter Platz) → sieht ab jetzt die Farben, kann nicht zurück
        val agentB = p[3]
        assertNull(room.seat(agentB, "b", "chief"))
        assertTrue(room.view(agentB)["key"]!!.jsonPrimitive.boolean)
        assertNotNull(room.seat(agentB, "b", "agent"))
        // in der Lobby geht alles wieder
        assertNull(room.toLobby(p[0]))
        assertNull(room.seat(agentB, "b", "agent"))
        assertNull(room.seat(chiefA, "", "agent"))
        assertEquals("Jedes Team braucht einen Funker.", room.startProblem())
    }

    @Test
    fun playsOverHttpWithLiveUpdates() {
        val game = WordGame()
        game.start()
        closers += AutoCloseable { game.stop() }
        val store = StatsStore(DirectorySource(Files.createTempDirectory("runden")))
        store.refresh()
        val server = StatsServer(store, 0, "127.0.0.1", wordGame = game)
        server.start()
        closers += AutoCloseable { server.stop() }
        val base = "http://127.0.0.1:${server.port}"
        val client = HttpClient.newHttpClient()

        fun post(path: String, body: String, type: String = "application/json"): Pair<Int, JsonObject> {
            val request = HttpRequest.newBuilder(URI("$base/api/geheimfunk$path")).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.statusCode() to (parseJson(response.body()) as JsonObject)
        }

        // Seite + Dateien kommen aus dem JAR
        val page = client.send(HttpRequest.newBuilder(URI("$base/spiele/geheimfunk")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, page.statusCode())
        assertContains(page.body(), "geheimfunk.js")
        assertContains(page.headers().firstValue("Content-Security-Policy").orElse(""), "script-src 'self';")
        for (file in listOf("geheimfunk.js", "geheimfunk.css", "fredoka.woff2")) {
            assertEquals(200, client.send(HttpRequest.newBuilder(URI("$base/spiele/$file")).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), file)
        }
        assertEquals(404, client.send(HttpRequest.newBuilder(URI("$base/spiele/../web/index.html")).build(), HttpResponse.BodyHandlers.discarding()).statusCode())

        assertEquals(415, post("/create", """{"name":"Kylo"}""", type = "text/plain").first)
        assertEquals(400, post("/create", """{"name":"  "}""").first)
        val (status, created) = post("/create", """{"name":"Kylo","avatar":3}""")
        assertEquals(200, status)
        val code = created["room"]!!.jsonPrimitive.content
        assertEquals(4, code.length)
        val tokens = mutableListOf(created["token"]!!.jsonPrimitive.content)
        for (name in listOf("Saru", "Pingu", "Geltus")) {
            val (s, joined) = post("/join", """{"room":"${code.lowercase()}","name":"$name"}""")
            assertEquals(200, s)
            tokens += joined["token"]!!.jsonPrimitive.content
        }
        assertEquals(404, post("/join", """{"room":"ZZZZ","name":"X"}""").first)
        // mit token: gleicher Platz
        assertEquals(tokens[1], post("/join", """{"room":"$code","token":"${tokens[1]}"}""").second["token"]!!.jsonPrimitive.content)

        // Stream eines Spielers (Spürnase von Team b)
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val stream = client.send(
            HttpRequest.newBuilder(URI("$base/api/geheimfunk/stream?room=$code&token=${tokens[3]}")).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertEquals(200, stream.statusCode())
        assertEquals("text/event-stream; charset=utf-8", stream.headers().firstValue("Content-Type").orElse(""))
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(stream.body(), Charsets.UTF_8)).use { input ->
                    var name = ""
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith("event: ")) name = line.removePrefix("event: ")
                        if (line.startsWith("data: ")) events += name to (parseJson(line.removePrefix("data: ")) as JsonObject)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        closers += AutoCloseable { stream.body().close(); reader.interrupt() }
        fun next(kind: String, until: (JsonObject) -> Boolean = { true }): JsonObject {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val (name, data) = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (name == kind && until(data)) return data
            }
            error("kein $kind")
        }
        val first = next("state")
        assertEquals(code, first["room"]!!.jsonPrimitive.content)
        assertEquals(4, first["players"]!!.jsonArray.size)
        // keine tokens in der Ausgabe
        tokens.forEach { assertFalse(first.toString().contains(it)) }

        fun act(index: Int, body: String) = post("/act", """{"room":"$code","token":"${tokens[index]}",$body}""")
        assertEquals(409, act(0, """"do":"start"""").first) // noch keine Teams
        assertEquals(409, act(1, """"do":"shuffle"""").first) // nur der Host
        assertEquals(200, act(0, """"do":"seat","team":"a","role":"chief"""").first)
        assertEquals(200, act(1, """"do":"seat","team":"a","role":"agent"""").first)
        assertEquals(200, act(2, """"do":"seat","team":"b","role":"chief"""").first)
        assertEquals(200, act(3, """"do":"seat","team":"b","role":"agent"""").first)
        assertEquals(200, act(0, """"do":"start"""").first)
        val running = next("state") { it["phase"]!!.jsonPrimitive.content == "clue" }
        assertEquals(25, running["cards"]!!.jsonArray.size)
        assertTrue(running["cards"]!!.jsonArray.all { it.jsonObject["c"] is JsonNull }) // Spürnase sieht keine Farben
        assertFalse(running["key"]!!.jsonPrimitive.boolean)

        val room = game.room(code)!!
        val turn = room.turn
        val chief = if (turn == "a") 0 else 2
        val agent = if (turn == "a") 1 else 3
        assertEquals(200, act(chief, """"do":"clue","word":"Tier","n":1""").first)
        val own = synchronized(room) { room.cards.indexOfFirst { it.color == turn } }
        assertEquals(200, act(agent, """"do":"reveal","i":$own""").first)
        val after = next("state") { s -> s["cards"]!!.jsonArray[own].jsonObject["r"]!!.jsonPrimitive.boolean }
        assertEquals(turn, after["cards"]!!.jsonArray[own].jsonObject["c"]!!.jsonPrimitive.content)
        assertContains(after["log"].toString(), "TIER")

        // Reaktion kommt als fx an
        assertEquals(200, act(1, """"do":"react","emoji":"💀"""").first)
        assertEquals("💀", next("fx")["emoji"]!!.jsonPrimitive.content)
        assertEquals(409, act(1, """"do":"react","emoji":"<script>"""").first)

        // Rauswerfen: der Stream bekommt bye
        assertEquals(200, act(0, """"do":"kick","id":"${first["you"]!!.jsonPrimitive.content}"""").first)
        assertEquals("removed", next("bye")["reason"]!!.jsonPrimitive.content)
        assertEquals(410, act(3, """"do":"pass"""").first)

        val info = client.send(HttpRequest.newBuilder(URI("$base/api/geheimfunk")).build(), HttpResponse.BodyHandlers.ofString())
        val packs = (parseJson(info.body()) as JsonObject)["packs"] as JsonArray
        assertEquals(WordGameWords.PACKS.size, packs.size)
        assertEquals(1, ((parseJson(info.body()) as JsonObject)["rooms"] as JsonPrimitive).int)
    }
}
