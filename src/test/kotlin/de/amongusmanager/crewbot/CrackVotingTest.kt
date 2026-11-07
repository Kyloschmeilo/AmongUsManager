package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.HistoryRound
import de.amongusmanager.crewbot.web.ArchiveEntry
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.BotLevel
import de.amongusmanager.crewbot.web.CrackPoll
import de.amongusmanager.crewbot.web.CrackVotes
import de.amongusmanager.crewbot.web.CrackVoting
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.StatsSource
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Crack-Voting: 15 Minuten nach der Runde, eine Stimme je Konto, Ergebnis erst danach, zählt dann in Bestenliste + Profil. */
class CrackVotingTest {
    private val example: String = javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()
    private val gameId = "4f1c2b8e9d0a4c7f8e1b2a3c4d5e6f70"
    private val received = Instant.parse("2026-11-01T19:07:00Z") // Runde endet 19:06:40
    private var now: Instant = received.plusSeconds(30)
    private val clock: () -> Instant = { now }
    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private inner class OneRound(private val receivedAt: Instant) : StatsSource {
        override val description = "Test"
        override fun archived() = listOf(ArchiveEntry(gameId, receivedAt, false))
        override fun archivedJson(gameIds: Collection<String>) = if (gameId in gameIds) mapOf(gameId to example) else emptyMap()
        override fun historySignature() = ""
        override fun history() = emptyList<HistoryRound>()
        override fun friendLinks() = mapOf("kilian#1234" to KILIAN, "mia#5678" to MIA)
        override fun levels() = emptyMap<Long, BotLevel>()
    }

    private object NoDiscord : DiscordApi {
        override fun lookup(userId: Long): DiscordUser? = null
        override fun download(url: String): Pair<ByteArray, String>? = null
        override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String) = error("nicht im Test")
        override fun me(accessToken: String): DiscordUser = error("nicht im Test")
    }

    private class Setup(val accounts: WebAccounts, val votes: CrackVotes, val store: StatsStore, val voting: CrackVoting)

    private fun setup(receivedAt: Instant = received): Setup {
        val db = Database.inMemory(botSchema = false).also { opened += it }
        val accounts = WebAccounts(db, clock)
        val votes = CrackVotes(db, clock)
        val store = StatsStore(OneRound(receivedAt), accounts, clock, votes)
        store.refresh()
        val auth = WebAuth(accounts, store, NoDiscord, AuthConfig("1", "geheim", "http://stats.test"), null, clock)
        return Setup(accounts, votes, store, CrackVoting(votes, auth, store, clock))
    }

    private fun Setup.call(method: String, path: String, user: Long? = null, body: String? = null, origin: String? = "http://stats.test"): WebResponse {
        val headers = HashMap<String, String>()
        user?.let { headers["cookie"] = "cs_session=" + accounts.createSession(it) }
        if (body != null) headers["content-type"] = "application/json"
        origin?.let { headers["origin"] = it }
        headers["host"] = "stats.test"
        return voting.handle(WebRequest(method, path, emptyMap(), headers, (body ?: "").toByteArray()))
    }

    private fun WebResponse.json(): JsonObject = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject

    private fun JsonObject.player(id: Int): JsonObject = this["players"]!!.jsonArray.map { it.jsonObject }.first { it["playerId"]!!.jsonPrimitive.int == id }

    @Test
    fun votingIsOpenFifteenMinutesAndHidesCountsUntilTheEnd() {
        val s = setup()
        val path = "${CrackVoting.PATH}/$gameId"

        val guest = s.call("GET", path).json()
        assertEquals("open", guest["state"]!!.jsonPrimitive.content)
        assertEquals("false", guest["loggedIn"]!!.jsonPrimitive.content)
        assertEquals(401, s.call("POST", path, body = """{"playerId":2}""").status)

        // Kilian (Spieler 0) stimmt für Tom, Mia für Tom, ein Zuschauer erst für Mia, dann um auf Tom … und wieder zurück auf Mia
        assertEquals(200, s.call("POST", path, KILIAN, """{"playerId":2}""").status)
        assertEquals(200, s.call("POST", path, MIA, """{"playerId":2}""").status)
        assertEquals(200, s.call("POST", path, 99L, """{"playerId":1}""").status)
        val open = s.call("POST", path, 99L, """{"playerId":1}""").json()
        assertEquals(3, open["total"]!!.jsonPrimitive.int)
        assertEquals(1, open["myVote"]!!.jsonPrimitive.int)
        assertTrue(open.player(2)["votes"] is JsonNull, "Stimmen erst nach dem Ende")

        // nicht für sich selbst, nur Spieler der Runde, nur von der eigenen Seite, nur JSON
        assertEquals(409, s.call("POST", path, KILIAN, """{"playerId":0}""").status)
        assertEquals(400, s.call("POST", path, KILIAN, """{"playerId":7}""").status)
        assertEquals(403, s.call("POST", path, KILIAN, """{"playerId":1}""", origin = "http://boese.test").status)
        assertEquals(true, s.call("GET", path, KILIAN).json().player(0)["me"]!!.jsonPrimitive.content.toBoolean())

        // offene Votings für die Startseite
        val list = s.call("GET", CrackVoting.PATH, MIA).json()
        assertEquals(1, list["open"]!!.jsonArray.size)
        assertEquals("true", list["open"]!!.jsonArray[0].jsonObject["voted"]!!.jsonPrimitive.content)

        // Bestenliste zählt noch nichts
        s.store.refresh()
        assertEquals(0, s.store.snapshot.summaryOf(WebAuth.playerKeyOf(KILIAN))!!["crackPolls"]!!.jsonPrimitive.int)

        // nach 15 Minuten: zu, Ergebnis sichtbar, keine Stimmen mehr
        now = received.plus(CrackPoll.WINDOW).plusSeconds(1)
        assertEquals(409, s.call("POST", path, 77L, """{"playerId":2}""").status)
        val closed = s.call("GET", path).json()
        assertEquals("closed", closed["state"]!!.jsonPrimitive.content)
        assertEquals(2, closed.player(2)["votes"]!!.jsonPrimitive.int)
        assertEquals(1, closed.player(1)["votes"]!!.jsonPrimitive.int)
        assertEquals("true", closed.player(2)["winner"]!!.jsonPrimitive.content)
        assertEquals("false", closed.player(1)["winner"]!!.jsonPrimitive.content)
        assertEquals(0, s.call("GET", CrackVoting.PATH).json()["open"]!!.jsonArray.size)
        // wer wen gewählt hat, steht nirgends (nur die eigene Stimme)
        assertFalse(String(s.call("GET", path).body).contains(KILIAN.toString()))

        // jetzt in Bestenliste und Profil
        assertTrue(s.store.refresh())
        val snapshot = s.store.snapshot
        val tom = snapshot.views.single().keys[2]
        val tomSummary = snapshot.summaryOf(tom)!!
        assertEquals(1, tomSummary["crackWins"]!!.jsonPrimitive.int)
        assertEquals(2, tomSummary["crackVotes"]!!.jsonPrimitive.int)
        assertEquals(1, tomSummary["crackPolls"]!!.jsonPrimitive.int)
        val mia = snapshot.summaryOf(WebAuth.playerKeyOf(MIA))!!
        assertEquals(0, mia["crackWins"]!!.jsonPrimitive.int)
        assertEquals(1, mia["crackVotes"]!!.jsonPrimitive.int)
        val profile = Json.parseToJsonElement(String(snapshot.playerJson(tom)!!)).jsonObject["crack"]!!.jsonObject
        assertEquals(1, profile["won"]!!.jsonArray.size)
        assertFalse(s.store.refresh(), "ohne neue Stimmen kein neuer Snapshot")
    }

    @Test
    fun withdrawingAndTiesAndLateRounds() {
        val s = setup()
        val path = "${CrackVoting.PATH}/$gameId"
        s.call("POST", path, KILIAN, """{"playerId":1}""")
        s.call("POST", path, MIA, """{"playerId":2}""")
        s.call("POST", path, 5L, """{"playerId":2}""")
        assertEquals(2, s.call("POST", path, 5L, """{"playerId":null}""").json()["total"]!!.jsonPrimitive.int)
        now = received.plus(Duration.ofMinutes(20))
        val closed = s.call("GET", path).json()
        assertTrue(closed.player(1)["winner"]!!.jsonPrimitive.content.toBoolean() && closed.player(2)["winner"]!!.jsonPrimitive.content.toBoolean(), "Gleichstand: beide vorne")
        assertEquals(setOf(1, 2), CrackPoll.winners(mapOf(1 to 1, 2 to 1, 0 to 0)))
        assertEquals(emptySet(), CrackPoll.winners(mapOf(1 to 0)))

        // erst Stunden später angekommen (Offline-Puffer der Mod) → kein Voting
        val late = setup(receivedAt = Instant.parse("2026-11-01T22:00:00Z"))
        now = Instant.parse("2026-11-01T22:01:00Z")
        assertEquals("none", late.call("GET", path).json()["state"]!!.jsonPrimitive.content)
        assertEquals(409, late.call("POST", path, KILIAN, """{"playerId":1}""").status)
    }

    private companion object {
        const val KILIAN = 111L
        const val MIA = 222L
    }
}
