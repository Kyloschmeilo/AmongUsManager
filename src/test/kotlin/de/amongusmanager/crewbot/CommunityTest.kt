package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.HistoryRound
import de.amongusmanager.crewbot.web.ArchiveEntry
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.BotLevel
import de.amongusmanager.crewbot.web.Community
import de.amongusmanager.crewbot.web.CommunityRules
import de.amongusmanager.crewbot.web.CommunityStore
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.DrawGame
import de.amongusmanager.crewbot.web.DrawRules
import de.amongusmanager.crewbot.web.DrawingRender
import de.amongusmanager.crewbot.web.MemeGame
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsSource
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Community-Bereich: Zeichnungen malen, Memes annehmen, Galerie, Herzen, Admin-Moderation, Texte, Seite + Routen. */
class CommunityTest {
    private val admin = 111L
    private val player = 222L
    private var now: Instant = Instant.parse("2026-11-21T20:00:00Z")
    private val clock: () -> Instant = { now }
    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private object NoRounds : StatsSource {
        override val description = "Test"
        override fun archived() = emptyList<ArchiveEntry>()
        override fun archivedJson(gameIds: Collection<String>) = emptyMap<String, String>()
        override fun historySignature() = ""
        override fun history() = emptyList<HistoryRound>()
        override fun friendLinks() = emptyMap<String, Long>()
        override fun levels() = emptyMap<Long, BotLevel>()
    }

    private object NoDiscord : DiscordApi {
        override fun lookup(userId: Long): DiscordUser? = null
        override fun download(url: String): Pair<ByteArray, String>? = null
        override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String) = error("nicht im Test")
        override fun me(accessToken: String): DiscordUser = error("nicht im Test")
    }

    private inner class Setup(memes: MemeGame? = null) {
        val db = Database.inMemory(botSchema = false).also { opened += it }
        val accounts = WebAccounts(db, clock)
        val store = CommunityStore(db, clock)
        val stats = StatsStore(NoRounds, accounts, clock).also { it.refresh() }
        val auth = WebAuth(accounts, stats, NoDiscord, AuthConfig("1", "geheim", "http://stats.test"), null, clock)
        val community = Community(store, auth, accounts, memes, isAdmin = { it == admin }, clock = clock)

        fun call(method: String, path: String, user: Long? = null, body: String? = null, query: Map<String, String> = emptyMap()): WebResponse {
            val headers = HashMap<String, String>()
            user?.let { headers["cookie"] = "cs_session=" + accounts.createSession(it) }
            if (body != null) headers["content-type"] = "application/json"
            headers["origin"] = "http://stats.test"
            headers["host"] = "stats.test"
            return community.handle(WebRequest(method, path, query, headers, (body ?: "").toByteArray()), "1.2.3.4")
        }

        fun drawing(source: String, word: String, likes: Int = 1): Long =
            store.add("drawing", source, word, "Anna", "$likes× gefeiert", DrawingRender.png(listOf(line())), "image/png", 800, 600)!!
    }

    private fun WebResponse.json(): JsonObject = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject

    private fun line(c: Int = 2, w: Int = 3, vararg p: Int = intArrayOf(100, 100, 300, 100)): JsonObject = buildJsonObject {
        put("k", "l"); put("i", 1); put("c", c); put("w", w); put("p", buildJsonArray { p.forEach { add(JsonPrimitive(it)) } })
    }

    private fun jpegDataUrl(width: Int = 400, height: Int = 300): String {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.ORANGE; fillRect(0, 0, width, height); dispose() }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun drawingsArePaintedLikeThePage() {
        // gleiche Farben und Stiftgrößen wie seiten/kritzeln.html
        val page = Files.readString(Path.of("seiten/kritzeln.html"))
        val palette = Regex("const PALETTE = \\[([^\\]]+)]").find(page)!!.groupValues[1].split(',').map { it.trim().trim('"') }
        assertEquals(DrawingRender.PALETTE, palette)
        val sizes = Regex("const SIZES = \\[([^\\]]+)]").find(page)!!.groupValues[1].split(',').map { it.trim().toInt() }
        assertEquals(DrawingRender.SIZES, sizes)
        assertEquals(DrawRules.COLORS, DrawingRender.PALETTE.size)

        // Linie in Rot, Rahmen in Schwarz, Eimer füllt nur das Innere, „u“ nimmt den letzten Strich zurück
        val box = buildJsonObject {
            put("k", "l"); put("i", 7); put("c", 12); put("w", 1)
            put("p", buildJsonArray { listOf(500, 300, 700, 300, 700, 500, 500, 500, 500, 300).forEach { add(JsonPrimitive(it)) } })
        }
        val fill = buildJsonObject { put("k", "f"); put("c", 5); put("x", 600); put("y", 400) }
        val undone = buildJsonObject { put("k", "l"); put("i", 9); put("c", 6); put("w", 4); put("p", buildJsonArray { listOf(50, 500, 60, 500).forEach { add(JsonPrimitive(it)) } }) }
        val ops = listOf(line(), box, fill, undone, buildJsonObject { put("k", "u") })
        val image = ImageIO.read(ByteArrayInputStream(DrawingRender.png(ops)))
        assertEquals(800, image.width)
        assertEquals(Color(0xEF130B).rgb, image.getRGB(200, 100), "rote Linie")
        assertEquals(Color(0x00CC00).rgb, image.getRGB(600, 400), "innen grün gefüllt")
        assertEquals(Color.WHITE.rgb, image.getRGB(400, 400), "außen bleibt weiß")
        assertEquals(Color.WHITE.rgb, image.getRGB(55, 500), "zurückgenommener Strich fehlt")
        assertFalse(DrawingRender.hasContent(listOf(line(), buildJsonObject { put("k", "u") })))
    }

    @Test
    fun kritzelkisteSendsTheMostCelebratedDrawings() {
        var t = 1_000_000L
        val received = ArrayList<DrawGame.Picture>()
        val game = DrawGame(clock = { t }, random = Random(5), gallery = { received += it })
        fun call(body: JsonObject): JsonObject =
            Json.parseToJsonElement(game.handle(WebRequest("POST", DrawGame.PATH, emptyMap(), emptyMap(), body.toString().toByteArray()), "1.2.3.4").body.toString(Charsets.UTF_8)).jsonObject
        val host = call(buildJsonObject { put("a", "create"); put("name", "Kilian") })
        val code = host["code"]!!.jsonPrimitive.content
        val anna = call(buildJsonObject { put("a", "join"); put("room", code); put("name", "Anna") })
        val seats = listOf(host, anna).map { it["token"]!!.jsonPrimitive.content to it["you"]!!.jsonPrimitive.int }
        fun act(seat: Pair<String, Int>, a: String, vararg extra: Pair<String, Any>) = call(buildJsonObject {
            put("a", a); put("room", code); put("token", seat.first)
            for ((k, v) in extra) when (v) { is Int -> put(k, v); is JsonArray -> put(k, v); else -> put(k, v.toString()) }
        })
        act(seats[0], "settings", "rounds" to 1, "seconds" to 30)
        var state = act(seats[0], "start")
        repeat(2) { turn ->
            val drawer = seats.first { it.second == state["drawer"]!!.jsonPrimitive.int }
            val other = seats.first { it != drawer }
            act(drawer, "pick", "i" to 0)
            act(drawer, "sync", "ops" to JsonArray(listOf(line())), "s" to -1)
            if (turn == 0) act(other, "like", "v" to 1)
            while (act(seats[0], "sync")["phase"]!!.jsonPrimitive.content == "draw") { t += 2_000; seats.forEach { act(it, "sync") } }
            t += DrawRules.REVEAL_MS + 1
            seats.forEach { state = act(it, "sync") }
        }
        assertEquals("final", state["phase"]!!.jsonPrimitive.content)
        assertEquals(1, received.size, "nur die gefeierte Zeichnung")
        assertEquals(1, received[0].likes)
        assertTrue(received[0].source.startsWith("kritzel:"))
        assertTrue(state["chat"].toString().contains("Startseite (Community)"))

        // Regeln: ohne 🔥 nichts, mehr 💀 als 🔥 nichts, höchstens drei
        fun pic(likes: Int, dislikes: Int) = DrawGame.Picture("s$likes$dislikes", "Wort", "A", listOf(line()), likes, dislikes, 0, 1)
        assertEquals(listOf(5, 3, 2), DrawRules.bestPictures(listOf(pic(0, 0), pic(1, 1), pic(2, 0), pic(5, 0), pic(3, 0), pic(1, 0))).map { it.likes })
    }

    @Test
    fun onlyTheBestMemesOfAFinishedGameGetIn() {
        var t = 1_000_000L
        val game = MemeGame(clock = { t }, random = Random(3))
        val s = Setup(game)
        fun call(body: JsonObject): JsonObject =
            Json.parseToJsonElement(game.handle(WebRequest("POST", MemeGame.PATH, emptyMap(), emptyMap(), body.toString().toByteArray()), "1.2.3.4").body.toString(Charsets.UTF_8)).jsonObject
        val host = call(buildJsonObject { put("a", "create"); put("name", "Kilian") })
        val code = host["code"]!!.jsonPrimitive.content
        val timo = call(buildJsonObject { put("a", "join"); put("room", code); put("name", "Timo") })
        val seats = listOf(host, timo).map { it["token"]!!.jsonPrimitive.content to it["you"]!!.jsonPrimitive.int }
        fun act(seat: Pair<String, Int>, a: String) = call(buildJsonObject { put("a", a); put("room", code); put("token", seat.first) })
        fun send(seat: Pair<String, Int>, body: JsonObject) = call(JsonObject(body + mapOf("room" to JsonPrimitive(code), "token" to JsonPrimitive(seat.first))))
        send(seats[0], buildJsonObject { put("a", "settings"); put("rounds", 1) })
        send(seats[0], buildJsonObject { put("a", "start") })

        // Meme hochladen, bevor die Partie vorbei ist → abgelehnt
        val early = s.call("POST", "${Community.PATH}/meme", body = buildJsonObject { put("room", code); put("token", seats[0].first); put("n", 0); put("image", jpegDataUrl()) }.toString())
        assertEquals(403, early.status)

        for ((i, seat) in seats.withIndex()) {
            val slots = game.slotsOf(game.templateOf(code, seat.second))
            send(seat, buildJsonObject { put("a", "write"); put("done", true); put("captions", buildJsonArray { repeat(slots) { add(JsonPrimitive("Text $i $it")) } }) })
        }
        repeat(2) {
            seats.forEach { send(it, buildJsonObject { put("a", "vote"); put("v", 4) }) }
            t += MemeGame.ALL_VOTED_MS + 1
            act(seats[0], "sync")
            t += MemeGame.REVEAL_MS + 1
            seats.forEach { act(it, "sync") }
        }
        t += MemeGame.SCORES_MS + 1
        val final = act(seats[0], "sync")
        assertEquals("final", final["phase"]!!.jsonPrimitive.content)
        val share = final["share"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertTrue(share.isNotEmpty())
        val n = share.first()
        val authorName = final["gallery"]!!.jsonArray.map { it.jsonObject }.first { it["n"]!!.jsonPrimitive.int == n }["name"]!!.jsonPrimitive.content

        fun upload(token: String, n: Int, image: String = jpegDataUrl()) =
            s.call("POST", "${Community.PATH}/meme", body = buildJsonObject { put("room", code); put("token", token); put("n", n); put("image", image) }.toString())
        assertEquals(403, upload("falsch", n).status, "fremdes Token")
        assertEquals(403, upload(seats[0].first, 99).status, "kein bestes Meme")
        assertEquals(400, upload(seats[0].first, n, "data:image/png;base64,AAAA").status, "kaputtes Bild")
        assertEquals(400, upload(seats[0].first, n, "data:image/svg+xml;base64,PHN2Zz4=").status, "nur PNG/JPEG")
        val ok = upload(seats[0].first, n).json()
        assertTrue(ok["ok"]!!.jsonPrimitive.boolean)
        assertEquals(true, upload(seats[1].first, n).json()["already"]!!.jsonPrimitive.boolean, "zweimal = einmal")

        val list = s.call("GET", "${Community.PATH}/galerie", query = mapOf("art" to "memes")).json()
        val item = list["items"]!!.jsonArray.single().jsonObject
        assertEquals(authorName, item["author"]!!.jsonPrimitive.content, "Autor kommt vom Server")
        assertTrue(item["info"]!!.jsonPrimitive.content.contains("Punkte"))
        val image = s.call("GET", item["src"]!!.jsonPrimitive.content.removePrefix("/api/community").let { Community.PATH + it })
        assertEquals("image/jpeg", image.type)
        assertEquals(0, s.call("GET", "${Community.PATH}/galerie", query = mapOf("art" to "zeichnungen")).json()["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun heartsNeedLoginAndStaySecret() {
        val s = Setup()
        val id = s.drawing("kritzel:a:1", "Banane")
        val body = """{"id":$id,"on":true}"""
        assertEquals(401, s.call("POST", "${Community.PATH}/herz", body = body).status)
        assertEquals(1, s.call("POST", "${Community.PATH}/herz", user = player, body = body).json()["likes"]!!.jsonPrimitive.int)
        assertEquals(1, s.call("POST", "${Community.PATH}/herz", user = player, body = body).json()["likes"]!!.jsonPrimitive.int, "ein Herz je Konto")
        assertEquals(2, s.call("POST", "${Community.PATH}/herz", user = admin, body = body).json()["likes"]!!.jsonPrimitive.int)
        val mine = s.call("GET", "${Community.PATH}/galerie", user = player, query = mapOf("sort" to "beste")).json()["items"]!!.jsonArray.single().jsonObject
        assertTrue(mine["liked"]!!.jsonPrimitive.boolean)
        val guest = s.call("GET", "${Community.PATH}/galerie").json()
        assertFalse(guest["items"]!!.jsonArray.single().jsonObject["liked"]!!.jsonPrimitive.boolean)
        assertFalse(guest.toString().contains(player.toString()), "keine Discord-IDs")
        assertEquals(1, s.call("POST", "${Community.PATH}/herz", user = player, body = """{"id":$id,"on":false}""").json()["likes"]!!.jsonPrimitive.int)
        // fremde Seiten dürfen nicht
        val foreign = s.community.handle(WebRequest("POST", "${Community.PATH}/herz", emptyMap(),
            mapOf("origin" to "https://boese.example", "host" to "stats.test", "content-type" to "application/json", "cookie" to "cs_session=" + s.accounts.createSession(player)),
            body.toByteArray()), "1.2.3.4")
        assertEquals(403, foreign.status)
    }

    @Test
    fun adminsModerateImages() {
        val s = Setup()
        val id = s.drawing("kritzel:a:1", "Banane")
        val other = s.drawing("kritzel:a:2", "Apfel")
        val hide = """{"a":"hide","id":$id}"""
        assertEquals(401, s.call("POST", "${Community.PATH}/admin", body = hide).status)
        assertEquals(403, s.call("POST", "${Community.PATH}/admin", user = player, body = hide).status)
        assertEquals(200, s.call("POST", "${Community.PATH}/admin", user = admin, body = hide).status)

        // ausgeblendet: weg für alle, Admins sehen es markiert; Bild nur noch für Admins
        assertEquals(1, s.call("GET", "${Community.PATH}/galerie").json()["items"]!!.jsonArray.size)
        val adminView = s.call("GET", "${Community.PATH}/galerie", user = admin).json()
        assertTrue(adminView["admin"]!!.jsonPrimitive.boolean)
        assertTrue(adminView["items"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.int.toLong() == id }["hidden"]!!.jsonPrimitive.boolean)
        assertEquals(404, s.call("GET", "${Community.PATH}/bild/$id").status)
        val adminImage = s.call("GET", "${Community.PATH}/bild/$id", user = admin)
        assertEquals(200, adminImage.status)
        assertTrue(adminImage.headers.any { it.first == "Cache-Control" && it.second.contains("no-store") })
        assertEquals(404, s.call("POST", "${Community.PATH}/herz", user = player, body = """{"id":$id,"on":true}""").status, "kein Herz für Ausgeblendete")
        s.call("POST", "${Community.PATH}/admin", user = admin, body = """{"a":"show","id":$id}""")
        assertEquals(2, s.call("GET", "${Community.PATH}/galerie").json()["total"]!!.jsonPrimitive.int)
        assertEquals(200, s.call("POST", "${Community.PATH}/admin", user = admin, body = """{"a":"delete","id":$other}""").status)
        assertEquals(404, s.call("GET", "${Community.PATH}/bild/$other").status)

        // Changelog des Bots steht in der Übersicht, Regeln/FAQ/Neuigkeiten gibt es nicht mehr
        val overview = s.call("GET", Community.PATH).json()
        assertNotNull(overview["changelog"]!!.jsonObject["version"])
        assertNull(overview["rules"])
        assertEquals(400, s.call("POST", "${Community.PATH}/admin", user = admin, body = """{"a":"news.add","title":"x","text":"x"}""").status)
        assertFalse(s.call("GET", Community.PATH, user = player).json()["me"]!!.jsonObject["admin"]!!.jsonPrimitive.boolean)
        assertTrue(s.call("GET", Community.PATH, user = admin).json()["me"]!!.jsonObject["admin"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun imageChecks() {
        assertNull(CommunityRules.decodeImage("data:image/gif;base64,R0lGODlh"))
        assertNull(CommunityRules.decodeImage("data:image/jpeg;base64,nichtbase64!!"))
        assertNull(CommunityRules.decodeImage(jpegDataUrl(20, 20)), "zu klein")
        assertNull(CommunityRules.decodeImage(jpegDataUrl(2000, 300)), "zu groß")
        // PNG-Inhalt mit JPEG-Kennung → abgelehnt
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        assertNull(CommunityRules.decodeImage("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(png)))
        assertEquals(100, CommunityRules.decodeImage("data:image/png;base64," + Base64.getEncoder().encodeToString(png))!!.width)
        assertEquals("a b", CommunityRules.clean(" a\u0000 ‮ b ", 10, lines = false))
        assertEquals(setOf(1L, 22L), Community.adminIds("1, 22;x"))
    }

    @Test
    fun serverServesCommunityApi() {
        val s = Setup()
        s.drawing("kritzel:a:1", "Banane")
        val server = StatsServer(s.stats, 0, "127.0.0.1", community = s.community)
        server.start()
        try {
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            fun get(path: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path")).build(), HttpResponse.BodyHandlers.ofString())
            for (path in listOf("/community", "/community/memes")) {
                val page = get(path)
                assertEquals(302, page.statusCode(), path)
                assertEquals("/#community", page.headers().firstValue("Location").orElse(""), path)
            }
            val api = get("/api/community/galerie")
            assertEquals(200, api.statusCode())
            assertTrue(api.body().contains("Banane"))
            assertEquals(200, get("/amongus/api/community").statusCode())
            assertEquals(404, get("/community.html").statusCode())
        } finally {
            server.stop()
        }
    }
}
