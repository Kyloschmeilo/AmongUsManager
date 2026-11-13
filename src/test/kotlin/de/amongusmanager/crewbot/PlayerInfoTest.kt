package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.services.LinkCodes
import de.amongusmanager.crewbot.services.MAX_INFO_PLAYERS
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.PlayerInfoProtocol
import de.amongusmanager.crewbot.services.PlayerInfoRequest
import de.amongusmanager.crewbot.services.PlayerInfoService
import de.amongusmanager.crewbot.ui.views.playerRoleLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Komfort-Anfragen von CrewStats (Stats, Lobby-Info, Verknüpfen per Code) ↔ CrewStats PlayerInfoRules.cs. */
class PlayerInfoTest {
    private fun service(s: TestServices, now: Instant = Instant.now()) =
        PlayerInfoService(s.db, s.links, s.levels, s.stats, s.history, ::playerRoleLabel) { now }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun anfragenLesen() {
        val stats = PlayerInfoProtocol.parse("""{"crewstats":"stats","v":1,"me":"Kilian#1234","players":["anna#1111","kaputt",""]}""")
        assertIs<PlayerInfoRequest.Stats>(stats)
        assertEquals("kilian#1234", stats.me)
        assertEquals(listOf("anna#1111"), stats.players) // ungültige Friendcodes fallen raus
        val link = PlayerInfoProtocol.parse("""{"crewstats":"link","v":1,"fc":"happyfox#0001"}""")
        assertEquals(PlayerInfoRequest.Link("happyfox#0001"), link)
        assertEquals(PlayerInfoRequest.Link(""), PlayerInfoProtocol.parse("""{"crewstats":"link","fc":"???"}"""))
        assertNull(PlayerInfoProtocol.parse("""{"crewstats":"join","v":1}"""))
        assertNull(PlayerInfoProtocol.parse("kein json"))
        val many = (1..30).joinToString(",") { "\"p$it#${1000 + it}\"" }
        assertEquals(24, MAX_INFO_PLAYERS)
        assertEquals(MAX_INFO_PLAYERS, (PlayerInfoProtocol.parse("""{"crewstats":"stats","players":[$many]}""") as PlayerInfoRequest.Stats).players.size)
    }

    @Test
    fun statsOhneDiscordDaten() {
        val s = TestServices()
        s.links.link("kilian#1234", 111)
        s.levels.addXp(111, 120, participation = true, win = true)
        val reply = json(service(s).statsReply(PlayerInfoRequest.Stats("kilian#1234", listOf("kilian#1234", "fremd#9999"))))
        assertEquals("stats-reply", reply["crewstats"]!!.jsonPrimitive.content)
        val me = reply["me"]!!.jsonObject
        assertTrue(me["linked"]!!.jsonPrimitive.boolean)
        assertEquals(120, me["xp"]!!.jsonPrimitive.int)
        assertEquals(3, me["level"]!!.jsonPrimitive.int)
        val players = reply["players"]!!.jsonArray
        assertTrue(players[0].jsonObject["linked"]!!.jsonPrimitive.boolean)
        assertEquals(mapOf("linked" to "false"), players[1].jsonObject.mapValues { it.value.jsonPrimitive.content })
        // Nie Discord-IDs oder Friendcodes in der Antwort
        val text = reply.toString()
        assertFalse("111" in text.replace("\"xp\":120", ""))
        assertFalse("kilian#1234" in text)
    }

    @Test
    fun letzteRundeMitXp() {
        val s = TestServices()
        s.links.link("kilian#1234", 111)
        val round = ModRound.parse(
            """{"gameId":"g1","winningTeam":"IMPOSTOR","endReason":"ImpostorsByKill","players":[
              {"playerId":0,"name":"Kilian","friendCode":"kilian#1234","role":"Impostor","team":"IMPOSTOR","won":true,"kills":2},
              {"playerId":1,"name":"Bob","friendCode":"bob#2222","role":"Crewmate","team":"CREW","won":false,"isDead":true,
               "tasksTotal":5,"tasksCompleted":3}]}""",
        )
        s.modRounds.applyWithoutRoles(round)
        val now = Instant.now() + Duration.ofSeconds(30)
        val last = json(service(s, now).statsReply(PlayerInfoRequest.Stats("kilian#1234", emptyList())))["me"]!!.jsonObject["last"]!!.jsonObject
        assertTrue(last["won"]!!.jsonPrimitive.boolean)
        assertEquals("IMPOSTOR", last["team"]!!.jsonPrimitive.content)
        assertEquals("Impostor", last["role"]!!.jsonPrimitive.content) // ohne Emoji
        assertEquals(2, last["kills"]!!.jsonPrimitive.int)
        assertTrue(last["xp"]!!.jsonPrimitive.int >= 25) // Impostor-Sieg (+ evtl. Erfolge)
        assertTrue(last["ago"]!!.jsonPrimitive.int in 25..40)
        assertEquals(service(s).xpOf("g1", 111), last["xp"]!!.jsonPrimitive.int)
    }

    @Test
    fun antwortPasstInEineNachricht() {
        val s = TestServices()
        val players = (1..15).map { "spieler$it#${1000 + it}" }
        players.forEachIndexed { i, code -> s.links.link(code, 1000L + i); s.levels.addXp(1000L + i, 99999) }
        val text = service(s).statsReply(PlayerInfoRequest.Stats(players[0], players))
        assertTrue(text.length <= 1900, "${text.length} Zeichen")
        assertEquals(15, json(text)["players"]!!.jsonArray.size)
    }

    @Test
    fun codesZumVerknuepfen() {
        var now = Instant.parse("2026-11-02T20:00:00Z")
        val codes = LinkCodes { now }
        val first = codes.create("kilian#1234")
        assertEquals(6, first.length)
        val second = codes.create("kilian#1234") // neuer Code ersetzt den alten
        assertNotEquals(first, second)
        assertNull(codes.redeem(first))
        assertEquals("kilian#1234", codes.redeem(" ${second.lowercase()} "))
        assertNull(codes.redeem(second)) // nur einmal einlösbar
        val late = codes.create("bob#2222")
        now += Duration.ofMinutes(11)
        assertNull(codes.redeem(late)) // abgelaufen
        val reply = json(PlayerInfoProtocol.linkReply("ABCDEF", linked = false))
        assertEquals("link-reply", reply["crewstats"]!!.jsonPrimitive.content)
        assertEquals(10, reply["minutes"]!!.jsonPrimitive.int)
        assertFalse(json(PlayerInfoProtocol.linkReply(null, false, "friendcode"))["ok"]!!.jsonPrimitive.boolean)
    }
}
