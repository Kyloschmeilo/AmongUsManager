package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.LobbyStore
import de.amongusmanager.crewbot.services.ModApiSettings
import de.amongusmanager.crewbot.services.PlayerInfoProtocol
import de.amongusmanager.crewbot.services.WebQueue
import de.amongusmanager.crewbot.web.BotLink
import de.amongusmanager.crewbot.web.ModApi
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CrewStats-Spieleranfragen über die Stats-Website (services/ModApi.kt, web/ModApi.kt): Schlüssel, Auto-Join aus der
 * Lobby in der Datenbank, Stats ohne Discord-IDs, Verknüpfungs-Code über den Bot, /autojoin-URL nur bei laufender Website.
 */
open class ModApiTest {
    /** Bot-Datenbank (mit Schema) und eine beschreibbare Verbindung ohne Migration, wie sie die Website öffnet. */
    open fun newDatabases(): Pair<Database, () -> Database> {
        val file = Files.createTempDirectory("crewstats-modapi").resolve("crewbot.db")
        return Database.open(file) to { Database.openShared(file) }
    }

    private val opened = ArrayList<AutoCloseable>()
    private val stop = AtomicBoolean(false)
    private var now = Instant.parse("2026-11-03T19:00:00Z")

    @AfterTest
    fun closeAll() {
        stop.set(true)
        Thread.sleep(50)
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private fun databases(): Pair<Database, Database> {
        val (bot, website) = newDatabases()
        opened += bot
        return bot to website().also { opened += it }
    }

    private fun api(webDb: Database) = ModApi(webDb, BotLink(WebQueue(webDb), wait = Duration.ofSeconds(2), pollMillis = 10) { now }) { now }

    private fun ModApi.post(key: String, body: String, client: String = "1.2.3.4", method: String = "POST"): WebResponse =
        handle(WebRequest(method, ModApiSettings.PATH + key, emptyMap(), mapOf("content-type" to "application/json"), body.toByteArray()), client)

    private fun WebResponse.json(): JsonObject = parseJson(String(body, Charsets.UTF_8)).jsonObject

    private fun lobby(state: String) = LobbyEvent(state, "ABCDEF", "Polus", 7, 15, "Kilian", "kilian#1234", "Modded NA", "na.example.org")

    @Test
    fun onlyTheRightKeyIsAnswered() {
        val (botDb, webDb) = databases()
        val api = api(webDb)
        assertEquals(404, api.post("irgendwas", """{"crewstats":"join"}""").status, "noch kein Schlüssel")
        val key = ModApiSettings(botDb).key()
        assertEquals(404, api.post("falsch", """{"crewstats":"join"}""").status)
        assertEquals(404, api.post(key, """{"crewstats":"join"}""").status, "Schlüssel wird gecacht (30 s)")
        now = now.plusSeconds(31)
        assertEquals(200, api.post(key, """{"crewstats":"join"}""").status)
        assertEquals(405, api.post(key, "", method = "GET").status)
        assertEquals(400, api.post(key, """{"crewstats":"round"}""").status, "Runden gehen hier nicht")
    }

    @Test
    fun autoJoinAnswersFromTheLobbyTheBotStored() {
        val (botDb, webDb) = databases()
        val key = ModApiSettings(botDb).key()
        val api = api(webDb)
        assertEquals("none", api.post(key, """{"crewstats":"join","v":1}""").json()["state"]?.jsonPrimitive?.content)

        LobbyStore(botDb).save(lobby("open"), now.minusSeconds(20))
        val reply = api.post(key, """{"crewstats":"join","v":1}""").json()
        assertEquals("join-reply", reply["crewstats"]?.jsonPrimitive?.content)
        assertEquals("ABCDEF", reply["code"]?.jsonPrimitive?.content)
        assertEquals("Modded NA", reply["region"]?.jsonPrimitive?.content)
        assertEquals("20", reply["age"]?.jsonPrimitive?.content)
        assertFalse("kilian#1234" in reply.toString(), "kein Friendcode des Hosts")

        LobbyStore(botDb).save(lobby("closed"), now)
        assertEquals("none", api.post(key, """{"crewstats":"join"}""").json()["state"]?.jsonPrimitive?.content)

        LobbyStore(botDb).save(lobby("started"), now)
        assertEquals("started", api.post(key, """{"crewstats":"join"}""").json()["state"]?.jsonPrimitive?.content)
        now = now.plus(Duration.ofHours(1)).plusSeconds(1)
        assertNull(LobbyStore(webDb).current(now), "nach 1 h ohne Meldung verschwunden")
    }

    @Test
    fun statsWithoutDiscordIds() {
        val (botDb, webDb) = databases()
        val key = ModApiSettings(botDb).key()
        LinkService(botDb).link("kilian#1234", KILIAN)
        LevelService(botDb).setLevel(KILIAN, 7)
        val response = api(webDb).post(key, """{"crewstats":"stats","v":1,"me":"kilian#1234","players":["kilian#1234","fremd#9999"]}""")
        assertEquals(200, response.status)
        val reply = response.json()
        assertEquals(PlayerInfoProtocol.STATS_REPLY, reply["crewstats"]?.jsonPrimitive?.content)
        assertEquals("7", reply["me"]?.jsonObject?.get("level")?.jsonPrimitive?.content)
        val players = reply["players"] as JsonArray
        assertEquals(listOf("true", "false"), players.map { it.jsonObject["linked"]?.jsonPrimitive?.content })
        assertFalse(KILIAN.toString() in reply.toString(), "keine Discord-ID")
    }

    @Test
    fun linkCodeComesFromTheBot() {
        val (botDb, webDb) = databases()
        val key = ModApiSettings(botDb).key()
        val api = api(webDb)
        assertEquals(503, api.post(key, """{"crewstats":"link","v":1,"fc":"kilian#1234"}""").status, "Bot offline")

        val queue = WebQueue(botDb)
        val seen = ArrayList<Pair<Long, String>>()
        queue.heartbeat(now)
        thread(isDaemon = true) {
            while (!stop.get()) {
                for (request in queue.open()) {
                    if (!queue.claim(request.id)) continue
                    synchronized(seen) { seen += request.userId to request.payload }
                    queue.complete(request.id, PlayerInfoProtocol.linkReply("K7P2QX", linked = false))
                }
                Thread.sleep(10)
            }
        }
        val reply = api.post(key, """{"crewstats":"link","v":1,"fc":"Kilian#1234"}""")
        assertEquals(200, reply.status)
        assertEquals("K7P2QX", reply.json()["code"]?.jsonPrimitive?.content)
        assertEquals(0L to """{"fc":"kilian#1234"}""", synchronized(seen) { seen.single() }, "ohne angemeldete Person, Friendcode normalisiert")

        val broken = api.post(key, """{"crewstats":"link","v":1,"fc":"kaputt"}""").json()
        assertEquals("friendcode", broken["error"]?.jsonPrimitive?.content)
        assertEquals(1, synchronized(seen) { seen.size }, "kaputter Friendcode geht gar nicht erst an den Bot")
    }

    @Test
    fun tooManyRequestsPerAddress() {
        val (botDb, webDb) = databases()
        val key = ModApiSettings(botDb).key()
        val api = api(webDb)
        repeat(40) { assertEquals(200, api.post(key, """{"crewstats":"join"}""").status) }
        assertEquals(429, api.post(key, """{"crewstats":"join"}""").status)
        assertEquals(200, api.post(key, """{"crewstats":"join"}""", client = "5.6.7.8").status, "andere Adresse")
    }

    @Test
    fun autojoinShowsTheWebsiteOnlyWhileItRuns() {
        val (botDb, webDb) = databases()
        val bot = ModApiSettings(botDb)
        assertNull(bot.playerUrl(now), "Website hat sich nie gemeldet → weiter Webhook")

        api(webDb).heartbeat("http://45.81.233.92:25570/")
        val key = bot.key()
        assertEquals("http://45.81.233.92:25570/api/mod/$key", bot.playerUrl(now))
        assertTrue(key.length >= 20 && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }, key)

        val renewed = bot.renewKey()
        assertNotEquals(key, renewed)
        assertNotNull(bot.playerUrl(now)?.takeIf { it.endsWith(renewed) })
        assertNull(bot.playerUrl(now.plus(ModApiSettings.WEBSITE_MAX_AGE).plusSeconds(1)), "Website weg → wieder Webhook")
        assertFalse(ModApiSettings.sameKey(null, ""))
        assertTrue(ModApiSettings.sameKey(renewed, renewed))
    }

    companion object {
        const val KILIAN = 412345678901234567L
    }
}
