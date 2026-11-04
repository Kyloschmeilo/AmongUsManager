package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.BADGES
import de.amongusmanager.crewbot.web.BadgeRound
import de.amongusmanager.crewbot.web.Badges
import de.amongusmanager.crewbot.web.StatsSnapshot
import de.amongusmanager.crewbot.web.WebRound
import de.amongusmanager.crewbot.web.WebRounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Abzeichen der Stats-Website (web/Badges.kt). */
class BadgesTest {
    private var counter = 0
    private val start = Instant.parse("2026-11-01T18:00:00Z")

    /** Kleine Runde: Spieler 0 = „Ich“ (Friendcode me#1), 1 = Partner/Crew, 2–5 Crew. */
    private fun round(
        myTeam: String,
        winningTeam: String,
        myKills: List<Double> = emptyList(),
        iDie: Boolean = false,
        partnerDead: Boolean = false,
        firstVictim: Boolean = false,
        votes: String = "",
        at: Instant = start.plusSeconds(600L * counter),
    ): WebRound {
        val id = "g${counter++}"
        val partnerTeam = if (myTeam == "IMPOSTOR") "IMPOSTOR" else "CREW"
        val impostor = if (myTeam == "IMPOSTOR") 0 else 5
        val kills = buildList {
            if (firstVictim) add("""{"atSeconds":5,"killer":$impostor,"victim":0}""")
            myKills.forEachIndexed { i, t -> add("""{"atSeconds":${t + 10},"killer":0,"victim":${2 + i}}""") }
        }
        fun player(pid: Int, name: String, fc: String, team: String, dead: Boolean, k: Int) =
            """{"playerId":$pid,"name":"$name","friendCode":"$fc","team":"$team","won":${team == winningTeam},"isDead":$dead,"kills":$k,"role":"${if (team == "IMPOSTOR") "Impostor" else "Crewmate"}"}"""
        val players = listOf(
            player(0, "Ich", "me#1", myTeam, iDie || firstVictim, myKills.size),
            player(1, "Partner", "p#1", partnerTeam, partnerDead, 0),
            player(2, "A", "a#1", "CREW", myKills.size > 0, 0),
            player(3, "B", "b#1", "CREW", myKills.size > 1, 0),
            player(4, "C", "c#1", "CREW", myKills.size > 2, 0),
            player(5, "D", "d#1", if (myTeam == "IMPOSTOR") "CREW" else "IMPOSTOR", false, if (firstVictim) 1 else 0),
        )
        val meetings = if (votes.isEmpty()) "" else """{"atSeconds":100,"caller":2,"votes":[$votes]}"""
        val json = """{"gameId":"$id","lobbyCode":"ABCDEF","map":"The Skeld","endedAt":"$at","durationSeconds":600,
            "winningTeam":"$winningTeam","players":[${players.joinToString(",")}],"kills":[${kills.joinToString(",")}],
            "meetings":[$meetings]}"""
        return WebRounds.fromJson(json, at)!!
    }

    private fun evaluate(rounds: List<WebRound>) =
        Badges.evaluate(rounds.map { BadgeRound(it, it.players.first { p -> p.friendCode == "me#1" }) }).associateBy { it.def.id }

    @Test
    fun impostorStreakSkipsCrewRoundsAndBreaksOnLoss() {
        val rounds = listOf(
            round("IMPOSTOR", "IMPOSTOR"),
            round("CREW", "IMPOSTOR"), // Crew-Runde dazwischen zählt nicht
            round("IMPOSTOR", "IMPOSTOR"),
            round("IMPOSTOR", "IMPOSTOR"),
            round("IMPOSTOR", "CREW"), // reißt
            round("IMPOSTOR", "IMPOSTOR"),
        )
        val r = evaluate(rounds).getValue("impostor_streak")
        assertEquals(3, r.value)
        assertEquals(1, r.current)
        assertEquals(1, r.tier) // Bronze ab 3
        assertEquals(rounds[3].gameId, r.reached.single().gameId)
    }

    @Test
    fun goldAfterTenImpostorWinsInARow() {
        val r = evaluate(List(10) { round("IMPOSTOR", "IMPOSTOR") }).getValue("impostor_streak")
        assertEquals(3, r.tier)
        assertEquals(3, r.reached.size)
    }

    @Test
    fun roundMoments() {
        val res = evaluate(
            listOf(
                round("IMPOSTOR", "IMPOSTOR", myKills = listOf(0.0, 20.0, 200.0)), // Doppelschlag + 3 Kills + Erstschlag
                round("IMPOSTOR", "IMPOSTOR", partnerDead = true, votes = """{"voter":2,"target":1}"""), // Einsamer Wolf + Unsichtbar
                round("CREW", "IMPOSTOR", firstVictim = true),
                round("CREW", "CREW", votes = """{"voter":0,"target":5}"""),
            ),
        )
        assertEquals(1, res.getValue("double_kill").value)
        assertEquals(1, res.getValue("massacre").value)
        assertEquals(1, res.getValue("first_blood").value)
        assertEquals(1, res.getValue("lone_wolf").value)
        assertEquals(1, res.getValue("invisible").value)
        assertEquals(1, res.getValue("first_victim").value)
        assertEquals(0, res.getValue("sharp_eye").value) // nur 1 richtige Stimme
        assertEquals(4, res.getValue("regular").value)
        assertEquals(3, res.getValue("hunter").value)
        assertEquals(2, res.getValue("roles").value)
    }

    @Test
    fun idsAreUniqueAndTiersAscending() {
        assertEquals(BADGES.size, BADGES.map { it.id }.toSet().size)
        for (b in BADGES) {
            assertEquals(3, b.tiers.size, b.id)
            assertTrue(b.tiers.toList().zipWithNext().all { (a, c) -> a < c }, b.id)
        }
        // Das Frontend hat für jede id ein Symbol
        val icons = java.io.File("web/src/components/Badges.tsx").readText()
        for (b in BADGES) assertTrue(Regex("""\b${b.id}:""").containsMatchIn(icons), "Symbol fehlt: ${b.id}")
    }

    @Test
    fun profileContainsBadgesWithRarity() {
        val rounds = List(3) { round("IMPOSTOR", "IMPOSTOR") }
        val snapshot = StatsSnapshot.build(rounds, emptyMap(), emptyMap(), Instant.now())
        val players = Json.parseToJsonElement(snapshot.playersJson.decodeToString()).jsonObject["players"]!!.jsonArray
        val me = players.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "Ich" }
        val profile = Json.parseToJsonElement(snapshot.playerJson(me["key"]!!.jsonPrimitive.content)!!.decodeToString()).jsonObject
        val badges = profile["badges"] as JsonArray
        assertEquals(BADGES.size, badges.size)
        val streak = badges.map { it as JsonObject }.first { it["id"]!!.jsonPrimitive.content == "impostor_streak" }
        assertEquals(1, streak["tier"]!!.jsonPrimitive.int)
        // 2 von 6 Spielern (ich + Partner) haben die Serie
        assertEquals(2.0 / 6, streak["rarity"]!!.jsonPrimitive.content.toDouble(), 0.01)
        // keine Friendcodes im Profil
        assertTrue("me#1" !in profile.toString())
    }
}
