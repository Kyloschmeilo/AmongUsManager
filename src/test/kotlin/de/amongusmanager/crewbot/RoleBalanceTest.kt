package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.BALANCE_MIN_GAMES
import de.amongusmanager.crewbot.web.CREW
import de.amongusmanager.crewbot.web.IMPOSTOR
import de.amongusmanager.crewbot.web.NEUTRAL
import de.amongusmanager.crewbot.web.StatsSnapshot
import de.amongusmanager.crewbot.web.WebPlayer
import de.amongusmanager.crewbot.web.WebRound
import de.amongusmanager.crewbot.web.balanceVerdict
import de.amongusmanager.crewbot.web.wilson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleBalanceTest {
    private fun player(id: Int, team: String, won: Boolean, modRole: String? = null, modifiers: List<String> = emptyList(), deathCause: String? = null, kills: Int = 0) =
        WebPlayer(
            playerId = id, name = "P$id", friendCode = "fc#$id", storedDiscordId = null, colorId = id % 18,
            role = if (team == IMPOSTOR) "Impostor" else "Crewmate", modRole = modRole, modStartRole = modRole,
            modifiers = modifiers, team = team, won = won, isDead = deathCause != null, disconnected = false,
            deathCause = deathCause, killedBy = null, diedAt = null, kills = kills, tasksTotal = 5, tasksCompleted = 5,
            isHost = id == 0, summary = null,
        )

    private fun round(index: Int, winner: String, players: List<WebPlayer>, mod: String? = "TheOtherRoles", reason: String = "") = WebRound(
        gameId = "g$index", endedAt = Instant.parse("2026-11-01T20:00:00Z").plusSeconds(index * 600L), lobbyCode = "ABCDEF",
        map = "The Skeld", mapId = 0, durationSeconds = 600.0, endReason = reason, winningTeam = winner, mod = mod,
        modGameMode = null, server = null, version = 2, fromHistory = false, players = players, kills = emptyList(),
        meetings = emptyList(), sabotages = emptyList(), eventCount = 0, hasTracks = false, mapSample = null,
    )

    /** 12 Runden: Sheriff gewinnt immer, Crew-Rest nur in 6, Jester gewinnt 2× per Sondersieg; dazu eine Vanilla-Runde. */
    private fun rounds(): List<WebRound> {
        val list = ArrayList<WebRound>()
        repeat(12) { i ->
            val crewWins = i < 6
            val jester = i in 10..11
            val winner = when { jester -> NEUTRAL; crewWins -> CREW; else -> IMPOSTOR }
            list += round(i, winner, listOf(
                player(0, CREW, true, modRole = "sheriff", modifiers = listOf("bait"), kills = 1),
                player(1, CREW, crewWins && !jester),
                player(2, CREW, crewWins && !jester, deathCause = "kill"),
                player(3, IMPOSTOR, !crewWins && !jester, modRole = "godfather", deathCause = if (i % 2 == 0) "ejected" else null),
                player(4, NEUTRAL, jester, modRole = "jester"),
            ), reason = if (jester) "JesterWin" else "")
        }
        list += round(99, CREW, listOf(player(0, CREW, true), player(3, IMPOSTOR, false)), mod = null)
        return list
    }

    private fun balance(): JsonObject {
        val snapshot = StatsSnapshot.build(rounds(), emptyMap(), emptyMap(), Instant.parse("2026-11-03T00:00:00Z"))
        return Json.parseToJsonElement(String(snapshot.balanceJson)).jsonObject
    }

    private fun JsonObject.role(id: String) = this["roles"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }

    @Test
    fun countsOnlyTorRounds() {
        val all = balance()["all"]!!.jsonObject
        assertEquals(12, all["rounds"]!!.jsonPrimitive.int)
        val winners = all["winners"]!!.jsonObject
        assertEquals(6, winners["CREW"]!!.jsonPrimitive.int)
        assertEquals(4, winners["IMPOSTOR"]!!.jsonPrimitive.int)
        assertEquals(2, winners["NEUTRAL"]!!.jsonPrimitive.int)
        val special = all["specialWins"]!!.jsonArray.single().jsonObject
        assertEquals("JesterWin", special["reason"]!!.jsonPrimitive.content)
        assertEquals(2, special["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun sheriffIsTooStrongComparedToRestOfCrew() {
        val sheriff = balance()["all"]!!.jsonObject.role("tor:sheriff")
        assertEquals("Sheriff", sheriff["name"]!!.jsonPrimitive.content)
        assertEquals(CREW, sheriff["team"]!!.jsonPrimitive.content)
        assertEquals(12, sheriff["games"]!!.jsonPrimitive.int)
        assertEquals(1.0, sheriff["share"]!!.jsonPrimitive.double)
        assertEquals(1.0, sheriff["winRate"]!!.jsonPrimitive.double)
        // restliche Crew: 24 Spiele, 12 Siege
        assertEquals(0.5, sheriff["baseline"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("strong", sheriff["verdict"]!!.jsonPrimitive.content)
        assertEquals(1, sheriff["players"]!!.jsonPrimitive.int)
    }

    @Test
    fun deathsAndModifiers() {
        val all = balance()["all"]!!.jsonObject
        val godfather = all.role("tor:godfather")
        assertEquals(6, godfather["ejected"]!!.jsonPrimitive.int)
        // Godfather ist der einzige Impostor: keine Vergleichsquote
        assertNull(godfather["baseline"]!!.jsonPrimitive.content.toDoubleOrNull())
        assertEquals("few", godfather["verdict"]!!.jsonPrimitive.content)
        val crewmate = all.role("game:Crewmate")
        assertEquals(12, crewmate["killed"]!!.jsonPrimitive.int)
        val bait = all["modifiers"]!!.jsonArray.single().jsonObject
        assertEquals("bait", bait["id"]!!.jsonPrimitive.content)
        assertEquals(12, bait["games"]!!.jsonPrimitive.int)
    }

    @Test
    fun recentPeriodTakesNewestRounds() {
        val recent = balance()["recent"]!!.jsonObject
        assertEquals(12, recent["rounds"]!!.jsonPrimitive.int)
    }

    @Test
    fun verdictRules() {
        assertEquals("few", balanceVerdict(5, BALANCE_MIN_GAMES - 1, 0.5))
        assertEquals("ok", balanceVerdict(10, 20, 0.5))
        assertEquals("trend_strong", balanceVerdict(13, 20, 0.45)) // 65 % gegen 45 %, Intervall berührt 45 %
        assertEquals("weak", balanceVerdict(1, 30, 0.5))
        val (low, high) = wilson(5, 10)
        assertTrue(low < 0.5 && high > 0.5)
        assertFalse(low < 0.0 || high > 1.0)
    }
}
