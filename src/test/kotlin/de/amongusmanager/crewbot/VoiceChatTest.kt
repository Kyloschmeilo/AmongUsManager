package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.ModApiSettings
import de.amongusmanager.crewbot.services.WebQueue
import de.amongusmanager.crewbot.web.AuthConfig
import de.amongusmanager.crewbot.web.BotLink
import de.amongusmanager.crewbot.web.DatabaseSource
import de.amongusmanager.crewbot.web.DiscordApi
import de.amongusmanager.crewbot.web.DiscordUser
import de.amongusmanager.crewbot.web.ModApi
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.VoiceChat
import de.amongusmanager.crewbot.web.VoiceReport
import de.amongusmanager.crewbot.web.VoiceRules
import de.amongusmanager.crewbot.web.VoiceRules.Phase
import de.amongusmanager.crewbot.web.VoiceRules.Player
import de.amongusmanager.crewbot.web.WebAccounts
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.WebRequest
import de.amongusmanager.crewbot.web.WebResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proximity-Voice (web/VoiceChat.kt): Hör-Regeln, Host-Meldung, Beitreten, Signale, Lautstärken, keine Friendcodes. */
class VoiceChatTest {
    private val opened = ArrayList<AutoCloseable>()
    private var now = Instant.parse("2026-11-11T20:00:00Z")

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    // ---------- Regeln ----------

    private fun p(fc: String, x: Double = 0.0, y: Double = 0.0, dead: Boolean = false, vent: Boolean = false, mute: Boolean = false) =
        Player(fc, fc, 0, x, y, dead, vent, mute)

    @Test
    fun lobbyAndEndEveryoneHearsEveryone() {
        val far = p("b", 100.0, 100.0, dead = true)
        assertEquals(1.0, VoiceRules.gain(Phase.LOBBY, p("a"), far))
        assertEquals(1.0, VoiceRules.gain(Phase.END, p("a"), far))
    }

    @Test
    fun roundUsesDistanceVentsAndGhosts() {
        val me = p("a")
        assertEquals(1.0, VoiceRules.gain(Phase.TASKS, me, p("b", 1.0)))
        val mid = VoiceRules.gain(Phase.TASKS, me, p("b", 3.0))
        assertTrue(mid > 0.0 && mid < 1.0, "mittlere Entfernung leiser: $mid")
        assertTrue(VoiceRules.gain(Phase.TASKS, me, p("b", 4.5)) < mid, "weiter weg = leiser")
        assertEquals(0.0, VoiceRules.gain(Phase.TASKS, me, p("b", 6.0)))
        assertEquals(0.0, VoiceRules.gain(Phase.TASKS, me, p("b", 0.5, vent = true)), "im Vent nicht zu hören")
        assertEquals(1.0, VoiceRules.gain(Phase.TASKS, p("b", 0.5, vent = true), me), "wer im Vent sitzt, hört trotzdem")
        assertEquals(0.0, VoiceRules.gain(Phase.TASKS, me, p("g", 0.0, dead = true)), "Lebende hören keine Geister")
        assertEquals(1.0, VoiceRules.gain(Phase.TASKS, p("g", 50.0, dead = true), p("h", -50.0, dead = true)), "Geister hören Geister überall")
        assertEquals(0.0, VoiceRules.gain(Phase.TASKS, p("g", 50.0, dead = true), me), "Geister hören Lebende nach Entfernung")
        assertEquals(1.0, VoiceRules.gain(Phase.TASKS, p("g", 0.5, dead = true), me))
    }

    @Test
    fun meetingEveryoneAliveTalksExceptSilenced() {
        val me = p("a")
        assertEquals(1.0, VoiceRules.gain(Phase.MEETING, me, p("b", 40.0)))
        assertEquals(0.0, VoiceRules.gain(Phase.MEETING, me, p("b", 0.0, mute = true)), "Blackmailer-Opfer stumm")
        assertEquals(0.0, VoiceRules.gain(Phase.MEETING, me, p("g", 0.0, dead = true)))
        assertEquals(1.0, VoiceRules.gain(Phase.MEETING, p("g", dead = true), p("b", 40.0)), "Geister hören das Meeting")
    }

    @Test
    fun parsesTheHostReport() {
        val report = VoiceReport.parse(
            """{"crewstats":"voice","v":1,"fc":"Kilian#1234","code":"abcdef","phase":"tasks","players":[
               {"fc":"Kilian#1234","name":"Kilian","color":3,"x":1.5,"y":-2,"dead":false},
               {"fc":"mia#5678","name":"Mia","x":"kaputt","dead":true,"vent":1,"mute":true},{"name":"ohne fc"},7]}""",
        )!!
        assertEquals("kilian#1234", report.host)
        assertEquals("ABCDEF", report.code)
        assertEquals(Phase.TASKS, report.phase)
        assertEquals(listOf("kilian#1234", "mia#5678"), report.players.map { it.fc })
        assertEquals(1.5, report.players[0].x)
        assertTrue(report.players[1].dead && report.players[1].silenced)
        assertNull(VoiceReport.parse("""{"crewstats":"voice","fc":"x#1","phase":"party"}"""))
        assertNull(VoiceReport.parse("""{"crewstats":"join"}"""))
        assertTrue(VoiceReport.isReport("""{"crewstats":"voice"}"""))
        assertFalse(VoiceReport.isReport("""{"crewstats":"join","note":"voice"}"""))
    }

    // ---------- Website ----------

    private class Setup(val voice: VoiceChat, val mod: ModApi, val accounts: WebAccounts, val key: String)

    private fun setup(): Setup {
        val file = Files.createTempDirectory("crewstats-voice").resolve("crewbot.db")
        val botDb = Database.open(file).also { opened += it }
        val services = TestServices(botDb)
        services.links.link("kilian#1234", KILIAN)
        services.links.link("mia#5678", MIA)
        services.links.link("tom#9999", TOM)
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
        val voice = VoiceChat(botDb, auth, store) { now }
        val key = ModApiSettings(botDb).key()
        val mod = ModApi(botDb, BotLink(WebQueue(botDb), wait = Duration.ofSeconds(1), pollMillis = 10) { now }, voice = voice) { now }
        return Setup(voice, mod, accounts, key)
    }

    private fun report(s: Setup, phase: String, vararg players: String): JsonObject {
        val body = """{"crewstats":"voice","v":1,"fc":"kilian#1234","code":"ABCDEF","phase":"$phase","players":[${players.joinToString(",")}]}"""
        val response = s.mod.handle(WebRequest("POST", ModApiSettings.PATH + s.key, emptyMap(), emptyMap(), body.toByteArray()), "1.2.3.4")
        assertEquals(200, response.status)
        return parseJson(String(response.body, Charsets.UTF_8)).jsonObject
    }

    private fun pl(fc: String, x: Double, dead: Boolean = false) = """{"fc":"$fc","name":"${fc.substringBefore('#')}","color":1,"x":$x,"y":0,"dead":$dead}"""

    private fun request(method: String, path: String, session: String?, body: String = "", query: Map<String, String> = emptyMap(), origin: String? = "http://stats.test:8080") =
        WebRequest(
            method, path, query,
            buildMap {
                put("content-type", "application/json")
                put("host", "stats.test:8080")
                if (origin != null) put("origin", origin)
                if (session != null) put("cookie", "${WebAuth.SESSION_COOKIE}=$session")
            },
            body.toByteArray(),
        )

    private fun body(response: WebResponse) = parseJson(String(response.body, Charsets.UTF_8)).jsonObject

    private fun join(s: Setup, session: String): String {
        val response = s.voice.handle(request("POST", "${VoiceChat.PATH}/join", session, "{}"))
        assertEquals(200, response.status, String(response.body))
        return body(response)["peer"]!!.jsonPrimitive.content
    }

    /** Liest Ereignisse aus dem SSE-Stream eines Teilnehmers (im Hintergrund), bis [stop]. */
    private class Sink : OutputStream() {
        val buffer = ByteArrayOutputStream()
        @Volatile var stop = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (stop) throw IOException("zu")
            synchronized(buffer) { buffer.write(b, off, len) }
        }
    }

    private class Listener(s: Setup, session: String, peer: String) {
        val out = Sink()
        val thread: Thread

        init {
            val (member, failed) = s.voice.openStream(
                WebRequest("GET", VoiceChat.STREAM_PATH, mapOf("peer" to peer), mapOf("cookie" to "${WebAuth.SESSION_COOKIE}=$session"), ByteArray(0)),
            )
            assertNull(failed)
            thread = thread(isDaemon = true) { s.voice.pump(member!!, out, keepAlive = Duration.ofMillis(20)) }
        }

        fun text(): String = synchronized(out.buffer) { out.buffer.toString(Charsets.UTF_8) }

        fun events(name: String): List<JsonObject> = text().split("\n\n").filter { it.startsWith("event: $name\n") }
            .map { parseJson(it.substringAfter("data: ")).jsonObject }

        fun waitFor(name: String, count: Int = 1): List<JsonObject> {
            val until = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < until) {
                val list = events(name)
                if (list.size >= count) return list
                Thread.sleep(10)
            }
            error("kein $name in: ${text()}")
        }

        fun close() {
            out.stop = true
            thread.join(2000)
        }
    }

    @Test
    fun joinNeedsLoginLinkAndALobbyWithVoice() {
        val s = setup()
        assertEquals(401, s.voice.handle(request("POST", "${VoiceChat.PATH}/join", null, "{}")).status)
        val kilian = s.accounts.createSession(KILIAN)
        assertEquals(409, s.voice.handle(request("POST", "${VoiceChat.PATH}/join", kilian, "{}")).status, "noch keine Meldung des Hosts")
        report(s, "lobby", pl("kilian#1234", 0.0))
        assertEquals(403, s.voice.handle(request("POST", "${VoiceChat.PATH}/join", kilian, "{}", origin = "https://boese.example")).status)
        val stranger = s.accounts.createSession(424242L)
        assertEquals(409, s.voice.handle(request("POST", "${VoiceChat.PATH}/join", stranger, "{}")).status, "nicht verknüpft")
        val mia = s.accounts.createSession(MIA)
        assertEquals(409, s.voice.handle(request("POST", "${VoiceChat.PATH}/join", mia, "{}")).status, "Mia ist nicht in der Lobby")

        val status = body(s.voice.handle(request("GET", VoiceChat.PATH, kilian)))
        assertEquals("ABCDEF", status["room"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val peer = join(s, kilian)
        assertEquals(listOf(peer), s.voice.membersOf("kilian#1234"))
        assertEquals(1, report(s, "lobby", pl("kilian#1234", 0.0))["listeners"]!!.jsonPrimitive.content.toInt(), "Mod erfährt, dass jemand zuhört")
    }

    @Test
    fun mixFollowsTheGameAndSignalsReachThePeer() {
        val s = setup()
        report(s, "lobby", pl("kilian#1234", 0.0), pl("mia#5678", 20.0), pl("tom#9999", 2.0))
        val kilian = s.accounts.createSession(KILIAN)
        val mia = s.accounts.createSession(MIA)
        val tom = s.accounts.createSession(TOM)
        val pk = join(s, kilian)
        val pm = join(s, mia)
        val pt = join(s, tom)
        val lk = Listener(s, kilian, pk)
        val lm = Listener(s, mia, pm)
        try {
            val peers = lk.waitFor("peers").last()["list"]!!.jsonArray.map { it.jsonObject }
            assertEquals(setOf(pm, pt), peers.map { it["id"]!!.jsonPrimitive.content }.toSet())
            assertEquals(setOf("mia", "tom"), peers.map { it["name"]!!.jsonPrimitive.content }.toSet())
            // genau einer von zwei bietet an
            val miaOffers = lm.waitFor("peers").last()["list"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == pk }
                .jsonObject["offer"]!!.jsonPrimitive.content.toBoolean()
            val kilianOffers = peers.first { it["id"]!!.jsonPrimitive.content == pm }["offer"]!!.jsonPrimitive.content.toBoolean()
            assertTrue(miaOffers != kilianOffers)

            s.voice.tick()
            val lobbyMix = lk.waitFor("mix").last()
            assertEquals("lobby", lobbyMix["mode"]!!.jsonPrimitive.content)
            assertEquals(1.0, lobbyMix["hear"]!!.jsonObject[pm]!!.jsonPrimitive.content.toDouble())

            // Runde: Mia weit weg, Tom nah
            report(s, "tasks", pl("kilian#1234", 0.0), pl("mia#5678", 20.0), pl("tom#9999", 2.0))
            s.voice.tick()
            val round = lk.waitFor("mix", 2).last()
            assertEquals("round", round["mode"]!!.jsonPrimitive.content)
            assertEquals(0.0, round["hear"]!!.jsonObject[pm]!!.jsonPrimitive.content.toDouble())
            val tomGain = round["hear"]!!.jsonObject[pt]!!.jsonPrimitive.content.toDouble()
            assertTrue(tomGain > 0.0 && tomGain < 1.0, "Tom halb laut: $tomGain")
            assertEquals(listOf(pt), round["send"]!!.jsonArray.map { it.jsonPrimitive.content }, "Kilian sendet nur an Tom")

            // gleiche Lage → keine neue Nachricht
            s.voice.tick()
            Thread.sleep(50)
            assertEquals(2, lk.events("mix").size)

            report(s, "meeting", pl("kilian#1234", 0.0), pl("mia#5678", 20.0, dead = true), pl("tom#9999", 2.0))
            s.voice.tick()
            val meeting = lk.waitFor("mix", 3).last()
            assertEquals("meeting", meeting["mode"]!!.jsonPrimitive.content)
            assertEquals(0.0, meeting["hear"]!!.jsonObject[pm]!!.jsonPrimitive.content.toDouble(), "Geist im Meeting unhörbar")
            assertEquals(1.0, meeting["hear"]!!.jsonObject[pt]!!.jsonPrimitive.content.toDouble())
            val miaMix = lm.waitFor("mix", 3).last()
            assertEquals(1.0, miaMix["hear"]!!.jsonObject[pk]!!.jsonPrimitive.content.toDouble(), "Geist hört das Meeting")
            assertEquals(emptyList(), miaMix["send"]!!.jsonArray.toList(), "Geist sendet an niemanden Lebenden")

            // Signal von Kilian an Mia
            val sent = s.voice.handle(request("POST", "${VoiceChat.PATH}/signal", kilian, """{"peer":"$pk","to":"$pm","data":{"sdp":"x"}}"""))
            assertEquals(200, sent.status)
            val signal = lm.waitFor("signal").last()
            assertEquals(pk, signal["from"]!!.jsonPrimitive.content)
            // fremde peer-id → abgelehnt
            assertEquals(410, s.voice.handle(request("POST", "${VoiceChat.PATH}/signal", mia, """{"peer":"$pk","to":"$pm","data":{}}""")).status)

            // nichts im Stream verrät Friendcodes
            assertFalse(lk.text().contains("#1234") || lk.text().contains("5678") || lm.text().contains("9999"))

            // Host meldet sich nicht mehr → alle hören alle
            now = now.plusSeconds(10)
            s.voice.tick()
            val free = lk.waitFor("mix", 4).last()
            assertEquals("free", free["mode"]!!.jsonPrimitive.content)
            assertEquals(1.0, free["hear"]!!.jsonObject[pm]!!.jsonPrimitive.content.toDouble())

            // Tom verlässt den Voice → Kilian bekommt neue Liste
            val before = lk.events("peers").size
            assertEquals(200, s.voice.handle(request("POST", "${VoiceChat.PATH}/leave", tom, """{"peer":"$pt"}""")).status)
            val after = lk.waitFor("peers", before + 1).last()["list"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf(pm), after)
        } finally {
            lk.close()
            lm.close()
        }
    }

    @Test
    fun membersWhoLeaveTheLobbyOrDropTheStreamAreRemoved() {
        val s = setup()
        report(s, "lobby", pl("kilian#1234", 0.0), pl("mia#5678", 1.0))
        val kilian = s.accounts.createSession(KILIAN)
        val mia = s.accounts.createSession(MIA)
        val pk = join(s, kilian)
        val pm = join(s, mia)
        val lk = Listener(s, kilian, pk)
        val lm = Listener(s, mia, pm)
        try {
            // Mia verlässt die Lobby im Spiel
            report(s, "lobby", pl("kilian#1234", 0.0))
            s.voice.tick()
            now = now.plusSeconds(5)
            report(s, "lobby", pl("kilian#1234", 0.0))
            s.voice.tick()
            assertEquals(2, s.voice.membersOf("kilian#1234").size, "kurz weg ist ok")
            now = now.plusSeconds(6)
            report(s, "lobby", pl("kilian#1234", 0.0))
            s.voice.tick()
            assertEquals(listOf(pk), s.voice.membersOf("kilian#1234"))
            assertEquals("left-lobby", lm.waitFor("bye").last()["reason"]!!.jsonPrimitive.content)

            // Kilians Browser ist weg → nach 20 s raus
            lk.close()
            now = now.plusSeconds(5)
            report(s, "lobby", pl("kilian#1234", 0.0))
            s.voice.tick()
            assertEquals(listOf(pk), s.voice.membersOf("kilian#1234"))
            now = now.plusSeconds(21)
            report(s, "lobby", pl("kilian#1234", 0.0))
            s.voice.tick()
            assertEquals(emptyList(), s.voice.membersOf("kilian#1234"))
            assertNotNull(s.voice.handle(request("GET", VoiceChat.PATH, kilian)))
        } finally {
            lk.close()
            lm.close()
        }
    }

    @Test
    fun iceServersFromEnv() {
        assertEquals(VoiceChat.DEFAULT_ICE, VoiceChat.iceFromEnv(""))
        assertEquals(VoiceChat.DEFAULT_ICE, VoiceChat.iceFromEnv("kaputt"))
        val turn = VoiceChat.iceFromEnv("""[{"urls":"turn:turn.example:3478","username":"u","credential":"p"}]""")
        assertEquals("turn:turn.example:3478", turn[0].jsonObject["urls"]!!.jsonPrimitive.content)
    }

    companion object {
        const val KILIAN = 111L
        const val MIA = 222L
        const val TOM = 333L
    }
}
