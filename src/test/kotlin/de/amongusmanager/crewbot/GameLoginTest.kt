package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.ChessGame
import de.amongusmanager.crewbot.web.DatabaseSource
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordSync
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.DrawGame
import de.amongusmanager.crewbot.web.GameLogin
import de.amongusmanager.crewbot.web.Kritzelpost
import de.amongusmanager.crewbot.web.MemeGame
import de.amongusmanager.crewbot.web.ShipGame
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WordGame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Browserspiele: Hosten nur mit Discord-Anmeldung, Angemeldete spielen unter ihrem Discord-Namen. */
class GameLoginTest {
    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    @Test
    fun knowsWhatIsHostingInEveryGame() {
        val host = GameLogin.Kind.HOST
        val name = GameLogin.Kind.NAME
        fun kind(path: String, body: String = "{}") = GameLogin.kindOf(path, Json.parseToJsonElement(body).jsonObject)
        for (path in listOf(DrawGame.PATH, MemeGame.PATH, ShipGame.PATH, ChessGame.PATH)) {
            assertEquals(host, kind(path, """{"a":"create"}"""), path)
            assertEquals(name, kind(path, """{"a":"join"}"""), path)
            assertNull(kind(path, """{"a":"sync"}"""), path)
        }
        assertEquals(host, kind("/api/kritzelpost/create"))
        assertEquals(name, kind("/api/kritzelpost/join"))
        assertNull(kind("/api/kritzelpost/act"))
        assertEquals(host, kind("/api/geheimfunk/create"))
        assertEquals(name, kind("/api/geheimfunk/join"))
        assertEquals(name, kind("/api/geheimfunk/act", """{"do":"profile"}"""))
        assertNull(kind("/api/geheimfunk/act", """{"do":"guess"}"""))
        // Arenen: kein Hosten, nur der Name
        for (path in listOf("/api/arena/join", "/api/nudelsalat/join", "/api/mampf/join", "/api/mampf/spawn")) assertEquals(name, kind(path), path)
        assertNull(kind("/api/mampf/input"))
    }

    @Test
    fun hostingNeedsLoginAndNameComesFromDiscord() {
        val create = """{"a":"create","name":"Fake"}""".toByteArray()
        val blocked = GameLogin.apply(ShipGame.PATH, create, loginEnabled = true) { null }
        assertEquals(401, blocked.error?.status)
        assertEquals("true", json(blocked.error!!.body).getValue("login").jsonPrimitive.content)

        // ohne Login auf der Website (kein Client Secret) darf jeder hosten
        assertNull(GameLogin.apply(ShipGame.PATH, create, loginEnabled = false) { null }.error)

        val renamed = GameLogin.apply(ShipGame.PATH, create, loginEnabled = true) { "  Kilian <3 der Große und Mächtige " }
        assertNull(renamed.error)
        assertEquals("Kilian 3 der Gro", json(renamed.body).getValue("name").jsonPrimitive.content)

        // Beitreten ohne Login bleibt, Name wie eingegeben
        val join = """{"a":"join","room":"ABCD","name":"Gast"}""".toByteArray()
        assertContentEquals(join, GameLogin.apply(ShipGame.PATH, join, loginEnabled = true) { null }.body)
        assertEquals("Mia", json(GameLogin.apply(ShipGame.PATH, join, loginEnabled = true) { "Mia" }.body).getValue("name").jsonPrimitive.content)

        // alles andere bleibt byte-genau gleich und fragt nicht einmal nach dem Namen
        val sync = """{"a":"sync","name":"x"}""".toByteArray()
        assertContentEquals(sync, GameLogin.apply(ShipGame.PATH, sync, loginEnabled = true) { error("nicht fragen") }.body)
        val steer = "abc;1.5;0".toByteArray()
        assertContentEquals(steer, GameLogin.apply("/api/nudelsalat/steer", steer, loginEnabled = true) { error("nicht fragen") }.body)
    }

    private class FakeDiscord : DiscordApi {
        override fun lookup(userId: Long): DiscordUser? = null
        override fun download(url: String): Pair<ByteArray, String>? = null
        override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String): String = error("nicht gebraucht")
        override fun me(accessToken: String): DiscordUser = error("nicht gebraucht")
    }

    @Test
    fun serverChecksLoginForHostingInRealRequests() {
        val bot = TestServices(Database.inMemory().also { opened += it })
        val accounts = WebAccounts(Database.inMemory(botSchema = false).also { opened += it })
        val store = StatsStore(DatabaseSource(bot.db), accounts)
        store.refresh()
        val discord = FakeDiscord()
        val sync = DiscordSync(discord, accounts)
        val auth = WebAuth(accounts, store, discord, AuthConfig("1300000000000000001", "geheim", "http://127.0.0.1"), sync)
        sync.store(DiscordUser(KILIAN, "kilian_", "Kilian G", "KiloBoss", null, null))
        val session = accounts.createSession(KILIAN)

        val server = StatsServer(store, 0, "127.0.0.1", auth, accounts, kritzelpost = Kritzelpost(), shipGame = ShipGame(), wordGame = WordGame())
        server.start()
        opened += AutoCloseable { server.stop() }
        val client = HttpClient.newHttpClient()
        fun post(path: String, body: String, cookie: String? = null): HttpResponse<ByteArray> = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                .header("Content-Type", "application/json")
                .apply { if (cookie != null) header("Cookie", cookie) }
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        fun get(path: String, cookie: String? = null): JsonObject = json(client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path")).apply { if (cookie != null) header("Cookie", cookie) }.build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        ).body())

        // Status für die Seiten
        val guest = get("/api/spielname")
        assertEquals("true", guest.getValue("loginEnabled").jsonPrimitive.content)
        assertEquals("false", guest.getValue("loggedIn").jsonPrimitive.content)
        val me = get("/api/spielname", "cs_session=$session")
        assertEquals("KiloBoss", me.getValue("name").jsonPrimitive.content)
        assertEquals("KiloBoss", get("/amongus/api/spielname", "cs_session=$session").getValue("name").jsonPrimitive.content)

        // Schiffe: Gast darf nicht hosten, Angemeldeter schon – unter seinem Discord-Namen
        val denied = post(ShipGame.PATH, """{"a":"create","name":"Gast"}""")
        assertEquals(401, denied.statusCode())
        assertEquals("true", json(denied.body()).getValue("login").jsonPrimitive.content)
        val created = post(ShipGame.PATH, """{"a":"create","name":"Falscher Name"}""", "cs_session=$session")
        assertEquals(200, created.statusCode())
        val room = json(created.body())
        assertEquals("KiloBoss", room.getValue("me").jsonObject.getValue("name").jsonPrimitive.content)
        // Gast tritt ohne Anmeldung bei (wie aus der Live-Leiste)
        val joined = post(ShipGame.PATH, """{"a":"join","room":"${room.getValue("code").jsonPrimitive.content}","name":"Gast"}""")
        assertEquals(200, joined.statusCode())
        assertEquals("Gast", json(joined.body()).getValue("me").jsonObject.getValue("name").jsonPrimitive.content)
        // fremdes/kaputtes Cookie zählt nicht
        assertEquals(401, post(ShipGame.PATH, """{"a":"create","name":"x"}""", "cs_session=gefaelscht").statusCode())

        // Kritzelpost + Geheimfunk (Pfad-Aktionen) genauso
        assertEquals(401, post("/api/kritzelpost/create", """{"name":"Gast"}""").statusCode())
        val post = post("/api/kritzelpost/create", """{"name":"Gast"}""", "cs_session=$session")
        assertEquals(200, post.statusCode())
        val code = assertNotNull(json(post.body())["room"]).jsonPrimitive.content
        assertEquals(200, post("/api/kritzelpost/join", """{"name":"Gast","room":"$code"}""").statusCode())
        assertEquals(401, post("/api/geheimfunk/create", """{"name":"Gast"}""").statusCode())
        val funk = post("/api/geheimfunk/create", """{"name":"Gast"}""", "cs_session=$session")
        assertEquals(200, funk.statusCode())
        assertTrue(String(funk.body(), Charsets.UTF_8).contains("token"))
    }

    companion object {
        const val KILIAN = 412345678901234567L
    }
}
