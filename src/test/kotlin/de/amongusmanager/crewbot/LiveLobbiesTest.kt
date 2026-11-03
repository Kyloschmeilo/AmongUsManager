package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.LobbyPlayer
import de.amongusmanager.crewbot.services.LobbyStore
import de.amongusmanager.crewbot.services.ModApiSettings
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.WebQueue
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.BotLink
import de.amongusmanager.crewbot.web.DatabaseSource
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.JoinPicks
import de.amongusmanager.crewbot.web.LiveLobbies
import de.amongusmanager.crewbot.web.ModApi
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live-Lobbys der Stats-Website (web/LiveLobbies.kt): Spielerliste aus der Lobby-Meldung des Hosts, keine Friendcodes
 * an den Browser, Code nur für verknüpfte Spieler, „Beitreten“ geht über die nächste Auto-Join-Frage der Mod.
 */
open class LiveLobbiesTest {
    open fun newDatabases(): Pair<Database, () -> Database> {
        val file = Files.createTempDirectory("crewstats-lobbies").resolve("crewbot.db")
        return Database.open(file) to { Database.openShared(file) }
    }

    private val opened = ArrayList<AutoCloseable>()
    private var now = Instant.parse("2026-11-03T20:00:00Z")
    private val example: String = javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private class Setup(val botDb: Database, val lobbies: LiveLobbies, val mod: ModApi, val accounts: WebAccounts, val key: String)

    private fun setup(): Setup {
        val (botDb, open) = newDatabases()
        opened += botDb
        val webDb = open().also { opened += it }
        val services = TestServices(botDb)
        services.links.link("kilian#1234", KILIAN)
        services.links.link("mia#5678", MIA)
        services.modRounds.applyWithoutRoles(ModRound.parse(example), example)
        val accounts = WebAccounts(Database.inMemory(botSchema = false).also { opened += it })
        val store = StatsStore(DatabaseSource(botDb), accounts)
        store.refresh()
        val discord = object : DiscordApi {
            override fun lookup(userId: Long): DiscordUser? = null
            override fun download(url: String): Pair<ByteArray, String>? = null
            override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String): String = error("nicht im Test")
            override fun me(accessToken: String): DiscordUser = error("nicht im Test")
        }
        val auth = WebAuth(accounts, store, discord, AuthConfig("1", "geheim", "http://stats.test:8080")) { now }
        val picks = JoinPicks { now }
        val key = ModApiSettings(botDb).key()
        val mod = ModApi(webDb, BotLink(WebQueue(webDb), wait = Duration.ofSeconds(1), pollMillis = 10) { now }, picks) { now }
        return Setup(botDb, LiveLobbies(webDb, auth, store, picks) { now }, mod, accounts, key)
    }

    private fun lobby(state: String = "open", code: String = "ABCDEF", sender: String = "kilian#1234", host: String = "Kilian", players: List<LobbyPlayer> = PLAYERS) =
        LobbyEvent(state, code, "Polus", players.size, 15, host, sender, "Modded NA", "na.example.org", players)

    private fun get(s: Setup, session: String? = null): JsonObject {
        val response = s.lobbies.handle(request("GET", LiveLobbies.PATH, session))
        assertEquals(200, response.status)
        return parseJson(String(response.body, Charsets.UTF_8)).jsonObject
    }

    private fun join(s: Setup, session: String?, body: String, origin: String? = "http://stats.test:8080"): WebResponse =
        s.lobbies.handle(request("POST", LiveLobbies.PATH + "/join", session, body, origin))

    private fun request(method: String, path: String, session: String?, body: String = "", origin: String? = null) = WebRequest(
        method, path, emptyMap(),
        buildMap {
            put("content-type", "application/json")
            put("host", "stats.test:8080")
            if (origin != null) put("origin", origin)
            if (session != null) put("cookie", "${WebAuth.SESSION_COOKIE}=$session")
        },
        body.toByteArray(),
    )

    private fun modAsks(s: Setup, fc: String): JsonObject {
        val response = s.mod.handle(
            WebRequest("POST", ModApiSettings.PATH + s.key, emptyMap(), emptyMap(), """{"crewstats":"join","v":1,"fc":"$fc"}""".toByteArray()),
            "1.2.3.4",
        )
        return parseJson(String(response.body, Charsets.UTF_8)).jsonObject
    }

    @Test
    fun parsesThePlayerListOfTheMod() {
        val text = """{"crewstats":"lobby","v":1,"state":"open","code":"ABCDEF","map":"Polus","players":2,"max":15,"host":"Kilian",
            "sender":"kilian#1234","list":[{"name":"Kilian","fc":"Kilian#1234","color":0},{"name":"  ","fc":"x#1"},{"name":"Gast","color":"kaputt"},7]}"""
        val event = LobbyEvent.parse(text)!!
        assertEquals(listOf(LobbyPlayer("Kilian", "kilian#1234", 0), LobbyPlayer("Gast", "", -1)), event.list)
        assertEquals(emptyList(), LobbyEvent.parse("""{"crewstats":"lobby","state":"open","code":"ABCDEF"}""")!!.list, "alte Mod ohne Liste")
    }

    @Test
    fun oneLobbyPerHostNewestFirst() {
        val s = setup()
        val store = LobbyStore(s.botDb)
        store.save(lobby(code = "AAAAAA"), now.minusSeconds(30))
        store.save(lobby(code = "BBBBBB", sender = "tom#9012", host = "Tom"), now.minusSeconds(20))
        store.save(lobby(code = "CCCCCC"), now.minusSeconds(10)) // Kilian hostet neu
        assertEquals(listOf("CCCCCC", "BBBBBB"), store.live(now).map { it.first.code })
        store.save(lobby(state = "closed", code = "BBBBBB", sender = "tom#9012"), now)
        assertEquals(listOf("CCCCCC"), store.live(now).map { it.first.code })
        assertEquals("CCCCCC", store.current(now)?.first?.code, "Auto-Join nimmt weiter die neueste")
        assertTrue(store.live(now.plus(Duration.ofHours(1)).plusSeconds(11)).isEmpty(), "nach 1 h ohne Meldung weg")
    }

    @Test
    fun guestsSeePlayersButNoCodeAndNoFriendCodes() {
        val s = setup()
        LobbyStore(s.botDb).save(lobby(), now.minusSeconds(5))
        val reply = get(s)
        val text = reply.toString()
        val first = reply.getValue("lobbies").jsonArray.single().jsonObject
        assertEquals(JsonNull, first["code"], "Code nur für verknüpfte Spieler")
        assertEquals("open", first["state"]?.jsonPrimitive?.content)
        assertEquals("5", first["age"]?.jsonPrimitive?.content)
        val list = first.getValue("list") as JsonArray
        assertEquals(listOf("Kilian", "Mia", "Neu"), list.map { it.jsonObject["name"]?.jsonPrimitive?.content })
        val kilian = list[0].jsonObject
        assertEquals(WebAuth.playerKeyOf(KILIAN), kilian["key"]?.jsonPrimitive?.content, "Profil-Link über die Verknüpfung")
        assertEquals("true", kilian["host"]?.jsonPrimitive?.content)
        assertEquals("1", kilian["games"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, list[2].jsonObject["key"], "noch nie gespielt → kein Profil")
        for (secret in listOf("kilian#1234", "mia#5678", "neu#0001", KILIAN.toString(), "na.example.org")) assertFalse(secret in text, "$secret in der Ausgabe")
        assertEquals("false", reply.getValue("me").jsonObject["loggedIn"]?.jsonPrimitive?.content)
    }

    @Test
    fun joinGoesToTheModOfThatPlayer() {
        val s = setup()
        LobbyStore(s.botDb).save(lobby(players = PLAYERS.take(2)), now.minusSeconds(5))
        LobbyStore(s.botDb).save(lobby(code = "ZZZZZZ", sender = "tom#9012", host = "Tom", players = listOf(LobbyPlayer("Tom", "tom#9012", 2))), now)
        s.accounts.createSession(STRANGER).let { session ->
            assertEquals(409, join(s, session, """{"code":"ABCDEF"}""").status, "nicht verknüpft")
        }
        val session = s.accounts.createSession(KILIAN)
        assertEquals(401, join(s, null, """{"code":"ABCDEF"}""").status)
        assertEquals(403, join(s, session, """{"code":"ABCDEF"}""", origin = "https://boese.example").status)
        assertEquals(409, join(s, session, """{"code":"ABCDEF"}""").status, "schon drin")
        assertEquals("ABCDEF", get(s, session).getValue("me").jsonObject["inLobby"]?.jsonPrimitive?.content)
        assertEquals("ABCDEF", get(s, session).getValue("lobbies").jsonArray.last().jsonObject["code"]?.jsonPrimitive?.content, "verknüpft → Code sichtbar")
        assertEquals(404, join(s, session, """{"code":"QQQQQQ"}""").status)

        // Ohne Wahl antwortet Auto-Join wie bisher mit der neuesten Lobby
        assertEquals("ZZZZZZ", modAsks(s, "kilian#1234")["code"]?.jsonPrimitive?.content)
        assertNull(modAsks(s, "kilian#1234")["pick"])

        val ok = join(s, session, """{"code":"zzzzzz"}""")
        assertEquals(200, ok.status)
        val pending = get(s, session).getValue("me").jsonObject.getValue("join").jsonObject
        assertEquals("ZZZZZZ", pending["code"]?.jsonPrimitive?.content)
        assertEquals("false", pending["sent"]?.jsonPrimitive?.content)

        assertNull(modAsks(s, "mia#5678")["pick"], "andere Spieler bekommen die Wahl nicht")
        val reply = modAsks(s, "KILIAN#1234")
        assertEquals("ZZZZZZ", reply["code"]?.jsonPrimitive?.content)
        assertTrue(reply["pick"]?.jsonPrimitive?.content.orEmpty().isNotEmpty())
        assertNull(modAsks(s, "kilian#1234")["pick"], "jede Wahl nur einmal")
        assertEquals("true", get(s, session).getValue("me").jsonObject.getValue("join").jsonObject["sent"]?.jsonPrimitive?.content)

        // Wahl verfällt
        assertEquals(200, join(s, session, """{"code":"ZZZZZZ"}""").status)
        now = now.plus(JoinPicks.TTL).plusSeconds(1)
        assertNull(modAsks(s, "kilian#1234")["pick"])
    }

    @Test
    fun noJoinIntoRunningOrFullLobbies() {
        val s = setup()
        val session = s.accounts.createSession(KILIAN)
        LobbyStore(s.botDb).save(lobby(state = "started", sender = "tom#9012", players = listOf(LobbyPlayer("Tom", "tom#9012", 2))), now)
        assertEquals(409, join(s, session, """{"code":"ABCDEF"}""").status)
        LobbyStore(s.botDb).save(lobby(sender = "tom#9012", players = listOf(LobbyPlayer("Tom", "tom#9012", 2))).copy(players = 15), now)
        assertEquals(409, join(s, session, """{"code":"ABCDEF"}""").status, "voll")
        assertEquals("true", get(s).getValue("lobbies").jsonArray.single().jsonObject["full"]?.jsonPrimitive?.content)
    }

    companion object {
        const val KILIAN = 412345678901234567L
        const val MIA = 412345678901234568L
        const val STRANGER = 499999999999999999L
        val PLAYERS = listOf(LobbyPlayer("Kilian", "kilian#1234", 0), LobbyPlayer("Mia", "mia#5678", 1), LobbyPlayer("Neu", "neu#0001", 5))
    }
}
