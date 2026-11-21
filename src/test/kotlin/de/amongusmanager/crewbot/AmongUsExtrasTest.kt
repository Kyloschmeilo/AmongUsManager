package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.HistoryRound
import de.amongusmanager.crewbot.web.ArchiveEntry
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.BotLevel
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.LobbyPlanner
import de.amongusmanager.crewbot.web.LobbyPlans
import de.amongusmanager.crewbot.web.MonthlyRecap
import de.amongusmanager.crewbot.web.RoleArt
import de.amongusmanager.crewbot.web.StatsSource
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebGuides
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Among-Us-Extras der Stats-Website: Rollen-Wiki (Bilder, Detail), Monatsrückblick, Lobby-Planer. */
class AmongUsExtrasTest {
    private val example: String = javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()
    private val gameId = "4f1c2b8e9d0a4c7f8e1b2a3c4d5e6f70"
    private var now: Instant = Instant.parse("2026-11-21T18:00:00Z")
    private val clock: () -> Instant = { now }
    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private inner class OneRound : StatsSource {
        override val description = "Test"
        override fun archived() = listOf(ArchiveEntry(gameId, Instant.parse("2026-11-01T19:07:00Z"), false))
        override fun archivedJson(gameIds: Collection<String>) = if (gameId in gameIds) mapOf(gameId to example) else emptyMap()
        override fun historySignature() = ""
        override fun history() = emptyList<HistoryRound>()
        override fun friendLinks() = mapOf("kilian#1234" to KILIAN)
        override fun levels() = emptyMap<Long, BotLevel>()
    }

    private object NoDiscord : DiscordApi {
        override fun lookup(userId: Long): DiscordUser? = null
        override fun download(url: String): Pair<ByteArray, String>? = null
        override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String) = error("nicht im Test")
        override fun me(accessToken: String): DiscordUser = error("nicht im Test")
    }

    private fun JsonObject.arr(name: String) = this[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
    private fun ByteArray.json(): JsonObject = Json.parseToJsonElement(String(this, Charsets.UTF_8)).jsonObject

    // ---------- Rollen-Wiki ----------

    @Test
    fun roleImagesExistAndBelongToRealRoles() {
        for (id in RoleArt.IMAGES.keys) assertTrue(id.removePrefix("tor:") in SPECIAL_ROLES, "unbekannte Rolle $id")
        for (file in RoleArt.FILES) {
            assertNotNull(javaClass.classLoader.getResource("web/${RoleArt.DIR}/$file.webp"), "Bild fehlt: $file (npm run build?)")
        }
        val roles = WebGuides(null).lexiconJson.json().arr("roles")
        val slugs = roles.map { it.str("slug") }
        assertEquals(slugs.size, slugs.toSet().size, "Wiki-Adressen doppelt")
        assertTrue(slugs.none { it in setOf("balancing", "zufall") }, "Wiki-Adresse kollidiert mit einer Seite")
        val sheriffImages = roles.first { it.str("id") == "tor:medic" }.arr("images")
        assertEquals("shield", sheriffImages.first().str("file"))
    }

    @Test
    fun roleDetailListsPlayersAndRounds() {
        val store = StatsStore(OneRound(), null, clock).also { it.refresh() }
        val sheriff = store.snapshot.roleDetailJson(listOf("tor:sheriff")).json()
        assertEquals(1, sheriff["games"]!!.jsonPrimitive.int)
        assertEquals("Kilian", sheriff.arr("players").single().str("name"))
        assertEquals(gameId, sheriff.arr("rounds").single().str("gameId"))
        assertTrue(sheriff.toString().contains("\"holders\""))
        assertFalse(sheriff.toString().contains("kilian#1234"), "Friendcode in der Ausgabe")
        // Modifikator (Lovers) zählt über "tor:<modifier>"
        assertEquals(1, store.snapshot.roleDetailJson(listOf("tor:lover", "tor:lovers")).json()["games"]!!.jsonPrimitive.int)
        assertEquals(0, store.snapshot.roleDetailJson(listOf("tor:vulture")).json()["games"]!!.jsonPrimitive.int)
    }

    // ---------- Monatsrückblick ----------

    @Test
    fun recapPicksRoundOfTheMonthWithReasons() {
        val store = StatsStore(OneRound(), null, clock).also { it.refresh() }
        val recap = store.snapshot.recapJson(null)!!.json()
        assertEquals("2026-11", recap.str("month"))
        assertEquals("November 2026", recap.str("label"))
        val round = recap["roundOfMonth"]!!.jsonObject
        assertEquals(gameId, round.str("gameId"))
        assertTrue(round["reasons"]!!.jsonArray.isNotEmpty())
        assertTrue(recap.arr("awards").any { it.str("id") == "mvp" })
        assertEquals(1, recap["totals"]!!.jsonObject["rounds"]!!.jsonPrimitive.int)
        assertNull(store.snapshot.recapJson("kein-monat"))
        assertNull(store.snapshot.recapJson("2026-10")!!.json()["roundOfMonth"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        assertFalse(recap.toString().contains("kilian#1234"), "Friendcode in der Ausgabe")
    }

    @Test
    fun spectacleExplainsItsScore() {
        val store = StatsStore(OneRound(), null, clock).also { it.refresh() }
        val spectacle = MonthlyRecap.spectacle(store.snapshot.views.single().round)
        assertTrue(spectacle.score > 0)
        assertTrue(spectacle.reasons.isNotEmpty())
    }

    // ---------- Lobby-Planer ----------

    private class Planner(val accounts: WebAccounts, val planner: LobbyPlanner)

    private fun planner(): Planner {
        val db = Database.inMemory(botSchema = false).also { opened += it }
        val accounts = WebAccounts(db, clock)
        val store = StatsStore(OneRound(), accounts, clock).also { it.refresh() }
        val auth = WebAuth(accounts, store, NoDiscord, AuthConfig("1", "geheim", "http://stats.test"), null, clock)
        return Planner(accounts, LobbyPlanner(LobbyPlans(db, clock), auth, accounts, store))
    }

    private fun Planner.call(method: String, path: String, user: Long? = null, body: String? = null, origin: String? = "http://stats.test"): WebResponse {
        val headers = HashMap<String, String>()
        user?.let { headers["cookie"] = "cs_session=" + accounts.createSession(it) }
        if (body != null) headers["content-type"] = "application/json"
        origin?.let { headers["origin"] = it }
        headers["host"] = "stats.test"
        return planner.handle(WebRequest(method, path, emptyMap(), headers, (body ?: "").toByteArray()))
    }

    private fun WebResponse.json(): JsonObject = body.json()

    @Test
    fun planningAVoteFromCreateToChosenDate() {
        val p = planner()
        val slots = """["2026-11-27T19:00:00Z","2026-11-28T19:00:00Z","2026-11-28T19:00:00Z"]"""
        val create = """{"action":"create","title":"  Among Us   am Wochenende ","note":"Modded NA","slots":$slots}"""
        assertEquals(401, p.call("POST", "/api/planer", body = create).status)
        assertEquals(403, p.call("POST", "/api/planer", KILIAN, create, origin = "http://boese.test").status)
        val created = p.call("POST", "/api/planer", KILIAN, create)
        assertEquals(200, created.status, String(created.body))
        val plan = created.json()
        assertEquals("Among Us am Wochenende", plan.str("title"))
        assertEquals(2, plan.arr("slots").size, "doppelter Termin nur einmal")
        val id = plan.str("id")
        val first = plan.arr("slots")[0]["id"]!!.jsonPrimitive.int
        val second = plan.arr("slots")[1]["id"]!!.jsonPrimitive.int

        assertEquals(200, p.call("POST", "/api/planer/$id", KILIAN, """{"action":"vote","answers":{"$first":2,"$second":1}}""").status)
        val voted = p.call("POST", "/api/planer/$id", MIA, """{"action":"vote","answers":{"$first":2,"$second":0}}""").json()
        val slotOne = voted.arr("slots").first { it["id"]!!.jsonPrimitive.int == first }
        assertEquals(2, slotOne["yes"]!!.jsonPrimitive.int)
        assertEquals(first, voted["best"]!!.jsonPrimitive.int)
        assertEquals(2, voted.arr("people").size)
        assertFalse(voted.toString().contains(KILIAN.toString()), "Discord-ID in der Ausgabe")
        assertEquals(400, p.call("POST", "/api/planer/$id", MIA, """{"action":"vote","answers":{"99":2}}""").status)

        // Antwort zurücknehmen
        val withdrawn = p.call("POST", "/api/planer/$id", MIA, """{"action":"vote","answers":{"$first":null}}""").json()
        assertEquals(1, withdrawn.arr("slots").first { it["id"]!!.jsonPrimitive.int == first }["yes"]!!.jsonPrimitive.int)

        // Festlegen + Löschen nur der Ersteller
        assertEquals(403, p.call("POST", "/api/planer/$id", MIA, """{"action":"choose","slot":$first}""").status)
        assertEquals(first, p.call("POST", "/api/planer/$id", KILIAN, """{"action":"choose","slot":$first}""").json()["chosen"]!!.jsonPrimitive.int)
        val list = p.call("GET", "/api/planer").json()
        assertEquals(id, list.arr("plans").single().str("id"))
        assertEquals(403, p.call("POST", "/api/planer/$id", MIA, """{"action":"delete"}""").status)
        assertEquals(200, p.call("POST", "/api/planer/$id", KILIAN, """{"action":"delete"}""").status)
        assertEquals(404, p.call("GET", "/api/planer/$id").status)

        // Vorbei: zwei Tage nach dem letzten Termin verschwindet eine Planung aus der Liste
        val again = p.call("POST", "/api/planer", KILIAN, create).json().str("id")
        now = Instant.parse("2026-12-01T19:00:00Z")
        assertTrue(p.call("GET", "/api/planer").json().arr("plans").none { it.str("id") == again })
        assertEquals(200, p.call("GET", "/api/planer/$again").status)
    }

    @Test
    fun planningRejectsBadInput() {
        val p = planner()
        fun create(title: String, slots: String) = p.call("POST", "/api/planer", KILIAN, """{"action":"create","title":"$title","slots":$slots}""")
        assertEquals(400, create("ok?", """["2026-11-01T19:00:00Z"]""").status, "Termin in der Vergangenheit")
        assertEquals(400, create("Abend", """["2027-06-01T19:00:00Z"]""").status, "zu weit voraus")
        assertEquals(400, create("x", """["2026-11-27T19:00:00Z"]""").status, "Titel zu kurz")
        assertEquals(400, create("Abend", "[]").status)
        assertEquals(400, create("Abend", """["morgen"]""").status)
        val many = (0..12).joinToString(",", "[", "]") { "\"2026-11-25T%02d:00:00Z\"".format(it + 5) }
        assertEquals(400, create("Abend", many).status, "zu viele Termine")
        repeat(5) { assertEquals(200, create("Abend $it", """["2026-11-27T19:00:00Z"]""").status) }
        val sixth = create("Abend 6", """["2026-11-27T19:00:00Z"]""")
        assertEquals(400, sixth.status)
        assertTrue(String(sixth.body).contains("laufende Planungen"))
    }

    private companion object {
        const val KILIAN = 111111111111111111L
        const val MIA = 222222222222222222L
    }
}
