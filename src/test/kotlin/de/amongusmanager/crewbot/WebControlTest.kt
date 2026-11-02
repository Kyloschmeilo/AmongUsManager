package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.modules.WebControlModule
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.WebQueue
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.BotLink
import de.amongusmanager.crewbot.web.ControlApi
import de.amongusmanager.crewbot.web.DatabaseSource
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Website → Bot über die Auftrags-Tabelle `web_requests`: Warteschlange (Website und Bot mit getrennten Verbindungen auf
 * dieselbe Datenbank), Warten/Abbrechen, die HTTP-Seite (Login, Herkunft, JSON, Gäste) und die Hilfsfunktionen des Bots.
 * Der Bot selbst (Discord) ist hier ein kleiner Thread, der Aufträge beantwortet.
 */
open class WebControlTest {
    /** Bot-Datenbank (mit Schema) und eine beschreibbare Verbindung ohne Migration, wie sie die Website öffnet. */
    open fun newDatabases(): Pair<Database, () -> Database> {
        val file = Files.createTempDirectory("crewstats-control").resolve("crewbot.db")
        return Database.open(file) to { Database.openShared(file) }
    }

    private val example: String = javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()
    private val opened = ArrayList<AutoCloseable>()
    private val stop = AtomicBoolean(false)

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

    /** Antwortet wie der Bot: Echo der Aktion und des Absenders, Fehler bei „kaputt“. */
    private fun fakeBot(queue: WebQueue, seen: MutableList<Pair<Long, String>> = ArrayList()) = thread(isDaemon = true) {
        queue.heartbeat()
        while (!stop.get()) {
            for (request in queue.open()) {
                if (!queue.claim(request.id)) continue
                synchronized(seen) { seen += request.userId to request.action }
                val body = if (request.action == "admin.post") {
                    """{"error":"Kaputt"}"""
                } else {
                    """{"ok":true,"action":"${request.action}","roster":[{"key":"${WebAuth.playerKeyOf(KILIAN)}","name":"Kilian"},{"key":"000000000000","name":"Gast"}]}"""
                }
                queue.complete(request.id, body)
            }
            Thread.sleep(20)
        }
    }

    // ---------- Warteschlange ----------

    @Test
    fun queueHandsRequestsFromWebsiteToBotExactlyOnce() {
        val (botDb, webDb) = databases()
        val bot = WebQueue(botDb)
        val web = WebQueue(webDb)

        val id = web.submit(KILIAN, "events.join", """{"event":"123"}""")
        assertNull(web.result(id), "noch nicht bearbeitet")
        val open = bot.open()
        assertEquals(listOf("events.join"), open.map { it.action })
        assertEquals(KILIAN, open.single().userId)
        assertTrue(bot.claim(id))
        assertFalse(bot.claim(id), "nur einmal")
        assertFalse(web.cancel(id), "läuft schon – nicht mehr abbrechen")
        assertTrue(bot.open().isEmpty())

        bot.complete(id, """{"ok":true}""")
        assertEquals("""{"ok":true}""", web.take(id))
        assertNull(web.take(id), "abgeholt = gelöscht (Webhook-URLs bleiben nicht liegen)")

        // Abgebrochen, bevor der Bot ihn sieht → der Bot führt ihn nie aus
        val late = web.submit(MIA, "admin.voice", "{}")
        assertTrue(web.cancel(late))
        assertFalse(bot.claim(late))
        assertTrue(bot.open().isEmpty())
    }

    @Test
    fun heartbeatAndCleanup() {
        val (botDb, webDb) = databases()
        val bot = WebQueue(botDb)
        val web = WebQueue(webDb)
        val start = Instant.parse("2026-11-02T18:00:00Z")
        assertFalse(web.botAlive(start), "Bot hat sich nie gemeldet")
        bot.heartbeat(start)
        assertTrue(web.botAlive(start.plusSeconds(30)))
        assertFalse(web.botAlive(start.plus(WebQueue.HEARTBEAT_MAX_AGE).plusSeconds(1)), "Bot offline")

        web.submit(KILIAN, "me", "{}", start)
        val fresh = web.submit(KILIAN, "me", "{}", start.plusSeconds(600))
        assertEquals(1, bot.cleanup(start.plusSeconds(601)))
        assertEquals(listOf(fresh), bot.open().map { it.id })
    }

    // ---------- Warten auf den Bot ----------

    @Test
    fun linkWaitsForTheBotsAnswer() {
        val (botDb, webDb) = databases()
        val link = BotLink(WebQueue(webDb), wait = Duration.ofSeconds(5), pollMillis = 10)
        assertEquals(503, link.call(KILIAN, "me", JsonObject(emptyMap())).status, "ohne Bot")

        fakeBot(WebQueue(botDb))
        Thread.sleep(100)
        val ok = link.call(KILIAN, "events.list", JsonObject(emptyMap()))
        assertEquals(200, ok.status)
        assertEquals("events.list", ok.body.str("action"))
        val failed = link.call(KILIAN, "admin.post", JsonObject(emptyMap()))
        assertEquals(400, failed.status)
        assertEquals("Kaputt", failed.body.str("error"))
    }

    @Test
    fun linkGivesUpAndCancelsWhenTheBotDoesNotAnswer() {
        val (botDb, webDb) = databases()
        val bot = WebQueue(botDb)
        bot.heartbeat() // „läuft“, holt aber nichts ab
        val reply = BotLink(WebQueue(webDb), wait = Duration.ofMillis(200), pollMillis = 10).call(KILIAN, "admin.voice", JsonObject(emptyMap()))
        assertEquals(504, reply.status)
        assertTrue(bot.open().isEmpty(), "abgebrochen – der Bot macht es später nicht doch noch")
    }

    // ---------- HTTP-Seite ----------

    private class Setup(val api: ControlApi, val accounts: WebAccounts, val seen: MutableList<Pair<Long, String>>, val store: StatsStore)

    private fun setup(): Setup {
        val (botDb, webDb) = databases()
        val services = TestServices(botDb)
        services.links.link("kilian#1234", KILIAN)
        services.modRounds.applyWithoutRoles(ModRound.parse(example), example)
        val accounts = WebAccounts(Database.inMemory(botSchema = false).also { opened += it })
        accounts.saveDiscordUser(DiscordUser(KILIAN, "kilian", avatarKey = "u:abc", avatarUrl = "https://cdn.discordapp.com/avatars/$KILIAN/abc.webp"), byteArrayOf(1, 2, 3), "image/webp")
        val store = StatsStore(DatabaseSource(botDb), accounts)
        store.refresh()
        val discord = object : DiscordApi {
            override fun lookup(userId: Long): DiscordUser? = null
            override fun download(url: String): Pair<ByteArray, String>? = null
            override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String): String = error("nicht im Test")
            override fun me(accessToken: String): DiscordUser = error("nicht im Test")
        }
        val auth = WebAuth(accounts, store, discord, AuthConfig("1", "geheim", "http://stats.test:8080"))
        val seen = ArrayList<Pair<Long, String>>()
        fakeBot(WebQueue(botDb), seen)
        Thread.sleep(100)
        return Setup(ControlApi(auth, BotLink(WebQueue(webDb), wait = Duration.ofSeconds(5), pollMillis = 10), store), accounts, seen, store)
    }

    private fun post(s: Setup, action: String, session: String?, body: String = "{}", origin: String? = "http://stats.test:8080", type: String = "application/json", method: String = "POST"): WebResponse =
        s.api.handle(
            WebRequest(
                method, "${ControlApi.PREFIX}$action", emptyMap(),
                buildMap {
                    put("content-type", type)
                    put("host", "stats.test:8080")
                    if (origin != null) put("origin", origin)
                    if (session != null) put("cookie", "${WebAuth.SESSION_COOKIE}=$session")
                },
                body.toByteArray(),
            ),
        )

    @Test
    fun onlyLoggedInMembersFromThisSiteReachTheBot() {
        val s = setup()
        val session = s.accounts.createSession(KILIAN)

        assertEquals(404, post(s, "admin.alles-loeschen", session).status, "unbekannte Aktion wird nicht weitergereicht")
        assertEquals(405, post(s, "me", session, method = "GET").status)
        assertEquals(403, post(s, "admin.voice", session, origin = "https://boese.example").status, "fremde Seite")
        assertEquals(415, post(s, "admin.voice", session, type = "text/plain").status, "nur JSON (kein einfaches Formular)")
        assertEquals(401, post(s, "admin.overview", null).status, "nicht angemeldet")
        assertEquals(401, post(s, "events.join", "falsch").status)
        assertEquals(400, post(s, "events.join", session, body = "[1,2]").status)
        assertTrue(s.seen.isEmpty(), "nichts davon kam beim Bot an")

        val ok = post(s, "events.join", session, body = """{"event":"1"}""")
        assertEquals(200, ok.status)
        assertEquals(listOf(KILIAN to "events.join"), s.seen.toList(), "Discord-ID kommt aus der Sitzung, nicht vom Browser")

        // Gäste dürfen nur die Event-Liste sehen (als Discord-ID 0)
        assertEquals(200, post(s, "events.list", null).status)
        assertEquals(GUEST to "events.list", s.seen.last())
        assertEquals(400, post(s, "admin.post", session).status, "Fehler des Bots kommt als 400 mit Text")
    }

    @Test
    fun answersGetAvatarsButNoDiscordIds() {
        val s = setup()
        val session = s.accounts.createSession(KILIAN)
        val response = post(s, "events.list", session)
        val text = String(response.body, Charsets.UTF_8)
        val roster = Json.parseToJsonElement(text).jsonObject.getValue("roster").toString()
        assertContains(roster, "/api/avatar/${WebAuth.playerKeyOf(KILIAN)}?v=")
        assertContains(roster, "\"profile\":true")
        assertContains(roster, "\"profile\":false", message = "Gast ohne Profil")
        for (secret in listOf(KILIAN.toString(), "cdn.discordapp.com", "kilian#1234")) assertFalse(secret in text, "$secret in der Ausgabe")
    }

    @Test
    fun statsServerRoutesBotRequests() {
        val s = setup()
        val withControl = StatsServer(s.store, 0, "127.0.0.1", control = s.api).also { it.start() }
        val without = StatsServer(s.store, 0, "127.0.0.1").also { it.start() }
        try {
            val http = HttpClient.newHttpClient()
            fun call(port: Int): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/bot/events.list"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val answer = call(withControl.port)
            assertEquals(200, answer.statusCode())
            assertEquals("no-store", answer.headers().firstValue("Cache-Control").orElse(""))
            assertEquals(503, call(without.port).statusCode(), "ohne Bot-Datenbank: aus")
        } finally {
            withControl.stop()
            without.stop()
        }
    }

    // ---------- Hilfsfunktionen des Bots ----------

    @Test
    fun eventDetailsFromTheEmbed() {
        assertEquals("Among Us", WebControlModule.gameOf("🎮 AMONG US EVENT"))
        assertEquals("R.E.P.O.", WebControlModule.gameOf("🔒 🎮 R.E.P.O. EVENT (BEENDET)"))
        assertEquals("MINECRAFT", WebControlModule.gameOf("🎮 MINECRAFT EVENT"), "unbekanntes Spiel bleibt, wie es ist")
        assertEquals("Kilian", WebControlModule.creatorOf("Erstellt von Kilian • Anmeldung geschlossen"))
        assertEquals("Mia Sophie", WebControlModule.creatorOf("Erstellt von Mia Sophie"))
        assertNull(WebControlModule.creatorOf(null))
        assertEquals(
            LocalDateTime.of(2026, 11, 5, 19, 0).atZone(TIMEZONE),
            assertNotNull(WebControlModule.parseWebStart("2026-11-05T19:00")),
        )
        assertNull(WebControlModule.parseWebStart("05.11.2026 19:00"))
        assertNull(WebControlModule.parseWebStart(""))
        assertEquals("✅ 5 Spieler gemutet.", WebControlModule.plain("✅ **5** Spieler gemutet."))
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val KILIAN = 412345678901234567L
        const val MIA = 412345678901234568L
        const val GUEST = 0L
    }
}
