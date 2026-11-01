package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.DatabaseSource
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordException
import de.amongusmanager.crewbot.web.DiscordSync
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.HttpDiscordApi
import de.amongusmanager.crewbot.web.ProfileException
import de.amongusmanager.crewbot.web.ProfileOptions
import de.amongusmanager.crewbot.web.ProfileRules
import de.amongusmanager.crewbot.web.ProfileSettings
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stats-Website: „Mit Discord anmelden“, eigenes Profil (Anzeigename, Über mich, Farbe …) und Discord-Profilbilder.
 * Discord selbst ist ein Fake – geprüft wird, was die Website daraus macht, und dass keine Discord-ID den Browser erreicht.
 */
class WebAccountsTest {
    private val example: String = javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()
    private val opened = ArrayList<AutoCloseable>()
    private var now: Instant = Instant.parse("2026-11-01T20:00:00Z")
    private val clock: () -> Instant = { now }

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    /** Discord zum Ausprobieren: Nutzer, Bilder und OAuth-Codes stehen in Maps. */
    private class FakeDiscord : DiscordApi {
        val users = HashMap<Long, DiscordUser>()
        val images = HashMap<String, Pair<ByteArray, String>>()
        val codes = HashMap<String, Long>()
        var lookups = 0
        var downloads = 0
        var failDownloads = false
        var lastRedirect: String? = null

        override fun lookup(userId: Long): DiscordUser? {
            lookups++
            return users[userId]
        }

        override fun download(url: String): Pair<ByteArray, String>? {
            downloads++
            if (failDownloads) throw DiscordException("CDN nicht erreichbar")
            return images[url]
        }

        override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String): String {
            lastRedirect = redirectUri
            if (clientId != CLIENT_ID || clientSecret != SECRET) throw DiscordException("falsche App")
            return "token-" + (codes[code] ?: throw DiscordException("Code ungültig"))
        }

        override fun me(accessToken: String): DiscordUser = users.getValue(accessToken.removePrefix("token-").toLong())

        fun user(id: Long, username: String, nick: String? = null, globalName: String? = null, avatar: String? = null): DiscordUser {
            val url = avatar?.let { "https://cdn.discordapp.com/avatars/$id/$it.webp?size=128" }
            val user = DiscordUser(id, username, globalName, nick, avatar?.let { "u:$it" }, url)
            users[id] = user
            if (url != null) images[url] = byteArrayOf(1, 2, 3, avatar.length.toByte()) to "image/webp"
            return user
        }
    }

    private class Setup(
        val bot: TestServices,
        val accounts: WebAccounts,
        val store: StatsStore,
        val discord: FakeDiscord,
        val sync: DiscordSync,
        val auth: WebAuth,
    )

    private fun setup(config: AuthConfig = AuthConfig(CLIENT_ID, SECRET, "http://stats.test:8080")): Setup {
        val bot = TestServices(Database.inMemory().also { opened += it })
        bot.links.link("kilian#1234", KILIAN)
        bot.links.link("mia#5678", MIA)
        bot.modRounds.applyWithoutRoles(ModRound.parse(example), example)
        val accounts = WebAccounts(Database.inMemory(botSchema = false).also { opened += it }, clock)
        val store = StatsStore(DatabaseSource(bot.db), accounts, clock)
        store.refresh()
        val discord = FakeDiscord()
        val sync = DiscordSync(discord, accounts, clock)
        return Setup(bot, accounts, store, discord, sync, WebAuth(accounts, store, discord, config, sync, clock))
    }

    private fun request(method: String, path: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), body: String = "") =
        WebRequest(method, path, query, headers.mapKeys { it.key.lowercase() }, body.toByteArray())

    private fun WebResponse.header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun WebResponse.cookie(name: String): String? =
        headers.filter { it.first == "Set-Cookie" }.map { it.second }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.substringBefore(';')

    private fun WebResponse.json(): JsonObject = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

    /** Kompletter Login über Discord, liefert das Sitzungs-Cookie. */
    private fun login(s: Setup, userId: Long, back: String = "/"): String {
        val start = s.auth.handle(request("GET", "/auth/login", mapOf("back" to back)))
        val state = query(start.header("Location")!!).getValue("state")
        s.discord.codes["code-$userId"] = userId
        val done = s.auth.handle(request("GET", "/auth/callback", mapOf("state" to state, "code" to "code-$userId"), mapOf("Cookie" to "cs_state=$state")))
        assertEquals(302, done.status)
        assertEquals(back, done.header("Location"))
        return assertNotNull(done.cookie(WebAuth.SESSION_COOKIE))
    }

    private fun saveProfile(s: Setup, session: String?, body: String, origin: String? = "http://stats.test:8080", type: String = "application/json"): WebResponse =
        s.auth.handle(
            request(
                "POST", "/api/me/profile",
                headers = buildMap {
                    put("Content-Type", type)
                    put("Host", "stats.test:8080")
                    if (origin != null) put("Origin", origin)
                    if (session != null) put("Cookie", "theme=dark; ${WebAuth.SESSION_COOKIE}=$session")
                },
                body = body,
            ),
        )

    private fun players(s: Setup): Map<String?, JsonObject> =
        Json.parseToJsonElement(String(s.store.snapshot.playersJson, Charsets.UTF_8)).jsonObject.getValue("players").jsonArray
            .map { it.jsonObject }.associateBy { it.str("key") }

    // ---------- Sitzungen ----------

    @Test
    fun sessionsExpireAndCanBeDeleted() {
        val accounts = WebAccounts(Database.inMemory(botSchema = false).also { opened += it }, clock)
        val token = accounts.createSession(KILIAN)
        assertEquals(KILIAN, accounts.sessionUser(token))
        assertNull(accounts.sessionUser(token + "x"))
        assertNull(accounts.sessionUser(null))
        assertNull(accounts.sessionUser("x".repeat(500)))

        now = now.plus(WebAccounts.SESSION_LIFETIME).plusSeconds(1)
        assertNull(accounts.sessionUser(token), "abgelaufen")

        val second = accounts.createSession(MIA)
        assertEquals(MIA, accounts.sessionUser(second))
        accounts.deleteSession(second)
        assertNull(accounts.sessionUser(second))
    }

    @Test
    fun standaloneDatabaseHasNoBotTables() {
        val file = Files.createTempDirectory("crewstats-web").resolve("data/website.db")
        val db = Database.openStandalone(file).also { opened += it }
        WebAccounts(db)
        assertTrue(Files.exists(file))
        assertEquals(setOf("web_discord_users", "web_profiles", "web_sessions"), db.tableNames().toSet())
    }

    // ---------- Profil-Regeln ----------

    @Test
    fun profileRulesAcceptOnlyOwnChoices() {
        val options = ProfileOptions(listOf("Kilian", "kilo"), mapOf("tor:Sheriff" to "Sheriff"), mapOf(0 to "The Skeld", 2 to "Polus"))
        fun parse(text: String) = ProfileRules.parse(Json.parseToJsonElement(text).jsonObject, options)

        val ok = parse(
            """{"displayName":"kilo","bio":"  Hallo\u0007 Welt  ","colorId":3,"favoriteRole":"tor:Sheriff","favoriteMap":2,
               "showcase":["kills","wins","kills"],"showAvatar":false}""",
        )
        assertEquals(ProfileSettings("kilo", "Hallo Welt", 3, "tor:Sheriff", 2, listOf("kills", "wins"), showAvatar = false), ok)
        assertEquals(ProfileSettings(), parse("{}"))
        assertNull(parse("""{"displayName":"  "}""").displayName)

        // Über mich: höchstens 3 Zeilen, keine unsichtbaren Richtungszeichen
        assertEquals("a\nb\nc", parse("""{"bio":"a\r\n\nb\nc\nd\ne"}""").bio)
        assertEquals("ab", parse("""{"bio":"a‮b"}""").bio)
        assertEquals("x".repeat(ProfileRules.BIO_MAX), parse("""{"bio":"${"x".repeat(ProfileRules.BIO_MAX)}"}""").bio)

        val invalid = listOf(
            """{"displayName":"Mia"}""", // fremder Name
            """{"bio":"${"x".repeat(ProfileRules.BIO_MAX + 1)}"}""",
            """{"colorId":18}""",
            """{"colorId":"3"}""",
            """{"favoriteRole":"tor:Jester"}""",
            """{"favoriteMap":7}""",
            """{"showcase":["wins","kills","games","tasks"]}""",
            """{"showcase":["geheim"]}""",
            """{"showcase":"wins"}""",
            """{"showAvatar":"nein"}""",
            """{"displayName":{"x":1}}""",
        )
        for (text in invalid) assertFailsWith<ProfileException>(text) { parse(text) }
    }

    @Test
    fun metricIdsMatchTheWebsite() {
        val source = Files.readString(Path.of("web/src/lib/metrics.ts"))
        val ids = Regex("""id: '([a-z]+)', label""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(ProfileRules.METRICS, ids)
    }

    @Test
    fun clientIdComesFromTheBotToken() {
        val id = "412345678901234567"
        val token = Base64.getEncoder().withoutPadding().encodeToString(id.toByteArray()) + ".GaBcDe.geheimerteil"
        assertEquals(id, HttpDiscordApi.clientIdFromToken(token))
        assertNull(HttpDiscordApi.clientIdFromToken(null))
        assertNull(HttpDiscordApi.clientIdFromToken("kein-token"))
    }

    // ---------- Anmeldung ----------

    @Test
    fun loginWithDiscordCreatesSession() {
        val s = setup()
        s.discord.user(KILIAN, "kilian_", nick = "Kilo", avatar = "abc")

        val start = s.auth.handle(request("GET", "/auth/login", mapOf("back" to "/bestenliste?nach=kills")))
        assertEquals(302, start.status)
        val location = start.header("Location")!!
        assertTrue(location.startsWith("https://discord.com/oauth2/authorize?"))
        val params = query(location)
        assertEquals(CLIENT_ID, params["client_id"])
        assertEquals("identify", params["scope"])
        assertEquals("code", params["response_type"])
        assertEquals("http://stats.test:8080/auth/callback", params["redirect_uri"])
        val state = params.getValue("state")
        assertEquals(state, start.cookie(WebAuth.STATE_COOKIE))
        assertContains(start.headers.first { it.second.startsWith("cs_state=") }.second, "HttpOnly")

        // falsches oder fehlendes state-Cookie → Fehler, state ist danach verbraucht
        s.discord.codes["c1"] = KILIAN
        val forged = s.auth.handle(request("GET", "/auth/callback", mapOf("state" to state, "code" to "c1"), mapOf("Cookie" to "cs_state=anders")))
        assertEquals("/?login=fehler", forged.header("Location"))
        assertNull(forged.cookie(WebAuth.SESSION_COOKIE))
        val reused = s.auth.handle(request("GET", "/auth/callback", mapOf("state" to state, "code" to "c1"), mapOf("Cookie" to "cs_state=$state")))
        assertEquals("/?login=fehler", reused.header("Location"))

        val session = login(s, KILIAN, back = "/bestenliste?nach=kills")
        assertEquals("http://stats.test:8080/auth/callback", s.discord.lastRedirect)
        val me = s.auth.handle(request("GET", "/api/me", headers = mapOf("Cookie" to "${WebAuth.SESSION_COOKIE}=$session"))).json()
        assertEquals("true", me.getValue("loggedIn").jsonPrimitive.content)
        assertEquals("Kilo", me.getValue("user").jsonObject.str("name"))
        val player = me.getValue("player").jsonObject
        assertEquals(WebAuth.playerKeyOf(KILIAN), player.str("key"))
        assertEquals("Kilian", player.str("name"))
        val names = me.getValue("options").jsonObject.getValue("names").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Kilian", "Kilo", "kilian_"), names)
        assertFalse(KILIAN.toString() in String(s.auth.handle(request("GET", "/api/me", headers = mapOf("Cookie" to "cs_session=$session"))).body))

        // Abbrechen bei Discord
        val again = s.auth.handle(request("GET", "/auth/login", mapOf("back" to "/runden")))
        val state2 = query(again.header("Location")!!).getValue("state")
        val cancelled = s.auth.handle(request("GET", "/auth/callback", mapOf("state" to state2, "error" to "access_denied"), mapOf("Cookie" to "cs_state=$state2")))
        assertEquals("/runden?login=abgebrochen", cancelled.header("Location"))

        // Abmelden
        val out = s.auth.handle(request("POST", "/auth/logout", headers = mapOf("Cookie" to "cs_session=$session", "Origin" to "http://stats.test:8080")))
        assertEquals(200, out.status)
        assertEquals("", out.cookie(WebAuth.SESSION_COOKIE))
        val after = s.auth.handle(request("GET", "/api/me", headers = mapOf("Cookie" to "cs_session=$session"))).json()
        assertEquals("false", after.getValue("loggedIn").jsonPrimitive.content)
    }

    @Test
    fun loginOnlyReturnsToOwnPages() {
        for (back in listOf("https://boese.example", "//boese.example", "/\\boese.example", "/auth/logout", "spieler", "")) {
            assertEquals("/", WebAuth.safeBack(back), back)
        }
        assertEquals("/spieler/abc?bearbeiten=1", WebAuth.safeBack("/spieler/abc?bearbeiten=1"))
        assertEquals("/", WebAuth.safeBack(null))
    }

    @Test
    fun loginIsOffWithoutSecret() {
        val s = setup(AuthConfig(CLIENT_ID, null, null))
        val start = s.auth.handle(request("GET", "/auth/login", mapOf("back" to "/maps")))
        assertEquals("/maps?login=aus", start.header("Location"))
        val me = s.auth.handle(request("GET", "/api/me")).json()
        assertEquals("false", me.getValue("loginEnabled").jsonPrimitive.content)
    }

    // ---------- Profil speichern ----------

    @Test
    fun profileCanOnlyBeSavedByItsOwner() {
        val s = setup()
        s.discord.user(KILIAN, "kilian_", nick = "Kilo")
        s.discord.user(STRANGER, "fremd")
        val session = login(s, KILIAN)
        val body = """{"displayName":"Kilo","bio":"Sheriff-Main","colorId":5,"favoriteMap":0,"showcase":["wins","kills"],"showAvatar":true}"""

        assertEquals(401, saveProfile(s, null, body).status)
        assertEquals(415, saveProfile(s, session, body, type = "text/plain").status)
        assertEquals(403, saveProfile(s, session, body, origin = "https://boese.example").status)
        assertEquals(400, saveProfile(s, session, """{"displayName":"Mia"}""").status)
        assertEquals(400, saveProfile(s, session, "kein json").status)
        assertEquals(413, saveProfile(s, session, """{"bio":"${"x".repeat(9000)}"}""").status)
        assertEquals(405, s.auth.handle(request("GET", "/api/me/profile")).status)

        val saved = saveProfile(s, session, body)
        assertEquals(200, saved.status, String(saved.body))
        assertEquals("Sheriff-Main", saved.json().getValue("profile").jsonObject.str("bio"))

        // überall sichtbar: Name in der Bestenliste, Profil mit eigenen Angaben
        val key = WebAuth.playerKeyOf(KILIAN)
        assertEquals("Kilo", players(s).getValue(key).str("name"))
        val profile = Json.parseToJsonElement(String(s.store.snapshot.playerJson(key)!!, Charsets.UTF_8)).jsonObject
        assertEquals("Kilian", profile.str("ingameName"))
        val custom = profile.getValue("custom").jsonObject
        assertEquals("Sheriff-Main", custom.str("bio"))
        assertEquals("The Skeld", custom.getValue("favoriteMap").jsonObject.str("map"))
        assertEquals(listOf("wins", "kills"), custom.getValue("showcase").jsonArray.map { it.jsonPrimitive.content })
        // in einer Runde steht weiter der Name, der im Spiel zu sehen war
        val detail = String(s.store.roundDetail(StatsWebsiteTest.EXAMPLE_ID)!!, Charsets.UTF_8)
        assertContains(detail, "\"name\":\"Kilian\"")

        // Ohne Runden (nicht verknüpft) gibt es kein Profil zum Bearbeiten
        val strangerSession = login(s, STRANGER)
        assertEquals(404, saveProfile(s, strangerSession, "{}").status)
        val strangerMe = s.auth.handle(request("GET", "/api/me", headers = mapOf("Cookie" to "cs_session=$strangerSession"))).json()
        assertEquals("true", strangerMe.getValue("loggedIn").jsonPrimitive.content)
        assertEquals("null", strangerMe.getValue("player").toString())
    }

    // ---------- Discord-Profilbilder ----------

    @Test
    fun discordSyncKeepsNamesAndAvatarsFresh() {
        val s = setup()
        s.discord.user(KILIAN, "kilian_", nick = "Kilo", avatar = "abc")
        // Mia gibt es bei Discord nicht (mehr)
        assertEquals(2, s.sync.sync(s.store.snapshot.linkedDiscordIds()))
        assertEquals(1, s.discord.downloads)
        assertEquals("Kilo", s.accounts.discordUser(KILIAN)?.shownName)
        assertNull(s.accounts.discordUser(MIA))
        assertNotNull(s.accounts.avatar(KILIAN))

        // gleich danach: niemand ist fällig
        assertEquals(0, s.sync.sync(s.store.snapshot.linkedDiscordIds()))

        // nach 12 h wieder – gleiches Bild wird nicht neu geladen
        now = now.plus(Duration.ofHours(13))
        assertEquals(2, s.sync.sync(s.store.snapshot.linkedDiscordIds()))
        assertEquals(1, s.discord.downloads)

        // neues Bild, aber das CDN klemmt → altes Bild bleibt
        s.discord.user(KILIAN, "kilian_", nick = "Kilo", avatar = "neu")
        s.discord.failDownloads = true
        now = now.plus(Duration.ofHours(13))
        s.sync.sync(listOf(KILIAN))
        assertEquals("u:abc", s.accounts.avatar(KILIAN)?.version)
        s.discord.failDownloads = false
        now = now.plus(Duration.ofHours(13))
        s.sync.sync(listOf(KILIAN))
        assertEquals("u:neu", s.accounts.avatar(KILIAN)?.version)

        // Bild entfernt → keins mehr
        s.discord.user(KILIAN, "kilian_", nick = "Kilo")
        now = now.plus(Duration.ofHours(13))
        s.sync.sync(listOf(KILIAN))
        assertNull(s.accounts.avatar(KILIAN))
    }

    @Test
    fun avatarsAreServedByTheWebsiteOnly() {
        val s = setup()
        s.discord.user(KILIAN, "kilian_", avatar = "abc")
        s.discord.user(MIA, "mia")
        s.sync.sync(s.store.snapshot.linkedDiscordIds())
        assertTrue(s.store.refresh(), "neue Profilbilder → neuer Snapshot")

        val kilianKey = WebAuth.playerKeyOf(KILIAN)
        val avatar = assertNotNull(players(s).getValue(kilianKey).str("avatar"))
        assertTrue(avatar.startsWith("/api/avatar/$kilianKey?v="))
        assertNull(players(s).getValue(WebAuth.playerKeyOf(MIA)).str("avatar"))

        val server = StatsServer(s.store, 0, "127.0.0.1", s.auth, s.accounts)
        server.start()
        opened += AutoCloseable { server.stop() }
        val client = HttpClient.newHttpClient()
        fun get(path: String, cookie: String? = null): HttpResponse<ByteArray> = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path")).apply { if (cookie != null) header("Cookie", cookie) }.build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

        val image = get(avatar)
        assertEquals(200, image.statusCode())
        assertEquals("image/webp", image.headers().firstValue("Content-Type").orElse(null))
        assertEquals(listOf<Byte>(1, 2, 3, 3), image.body().toList())
        assertEquals(404, get("/api/avatar/${WebAuth.playerKeyOf(MIA)}").statusCode())
        assertEquals(404, get("/api/avatar/gibtsnicht").statusCode())

        // Login und /api/me laufen über den Server (Weiterleitung, Cookies)
        val start = get("/auth/login?back=%2Frunden")
        assertEquals(302, start.statusCode())
        assertTrue(start.headers().firstValue("Location").orElse("").startsWith("https://discord.com/oauth2/authorize?"))
        assertTrue(start.headers().allValues("Set-Cookie").any { it.startsWith("cs_state=") })
        val me = Json.parseToJsonElement(String(get("/api/me").body(), Charsets.UTF_8)).jsonObject
        assertEquals("true", me.getValue("loginEnabled").jsonPrimitive.content)

        // Profilbild ausgeschaltet → farbiger Crewmate, Bild nicht mehr abrufbar
        val session = login(s, KILIAN)
        assertEquals(200, saveProfile(s, session, """{"showAvatar":false}""").status)
        assertNull(players(s).getValue(kilianKey).str("avatar"))
        assertEquals(404, get(avatar).statusCode())

        // Keine Discord-IDs und keine Discord-Adressen in irgendeiner Antwort
        val outputs = listOf(s.store.snapshot.overviewJson, s.store.snapshot.playersJson, s.store.roundDetail(StatsWebsiteTest.EXAMPLE_ID)!!) +
            s.store.snapshot.views.flatMap { it.keys }.distinct().map { s.store.snapshot.playerJson(it)!! } +
            listOf(get("/api/me", "cs_session=$session").body())
        for (bytes in outputs) {
            val text = String(bytes, Charsets.UTF_8)
            for (secret in listOf(KILIAN.toString(), MIA.toString(), "cdn.discordapp.com", "kilian#1234")) assertFalse(secret in text, "$secret in der Ausgabe")
        }
    }

    @Test
    fun serverWithoutLoginSaysSo() {
        val s = setup()
        val server = StatsServer(s.store, 0, "127.0.0.1")
        server.start()
        opened += AutoCloseable { server.stop() }
        val client = HttpClient.newHttpClient()
        val me = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/api/me")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, me.statusCode())
        assertEquals("""{"loginEnabled":false,"loggedIn":false}""", me.body())
        val login = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/auth/login")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(404, login.statusCode())
    }

    companion object {
        const val KILIAN = 412345678901234567L
        const val MIA = 412345678901234568L
        const val STRANGER = 412345678901234599L
        const val CLIENT_ID = "1300000000000000001"
        const val SECRET = "geheim"
    }
}
