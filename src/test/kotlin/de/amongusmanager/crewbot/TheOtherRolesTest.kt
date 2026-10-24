package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.content.roleLabel
import de.amongusmanager.crewbot.content.torRoleLabel
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.AutoMuteEvent
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundResult
import de.amongusmanager.crewbot.services.targetState
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.views.buildModRoundEmbed
import de.amongusmanager.crewbot.ui.views.playerRoleLabel
import de.amongusmanager.crewbot.ui.views.specialWinTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mod-Runden mit The Other Roles (CrewStats 1.4.0): Sieg-XP nach Team, Sondersiege, Rollen-Aktionen, Embed, Auto-Mute. */
open class TheOtherRolesTest {
    /** Datenbank für den Test (Standard SQLite; Unterklasse für MariaDB). */
    open fun newDb(): Database = Database.inMemory()

    /** Eine Runde, wie CrewStats sie mit The Other Roles schickt (Spieler i ist mit Discord-ID 100 + i verknüpft). */
    private fun torRound(
        gameId: String,
        winningTeam: String,
        endReason: String,
        winners: Set<Int>,
        deaths: String,
    ): String = """
        {
          "version": 1, "gameId": "$gameId", "lobbyCode": "ABCDEF", "map": "Polus",
          "durationSeconds": 612, "endReason": "$endReason", "winningTeam": "$winningTeam",
          "mod": "TheOtherRoles GMIA 1.4.1",
          "players": [
            ${player(0, "Rot", "Impostor", "IMPOSTOR", "blackmailer", winners, dead = true, cause = "guess")},
            ${player(1, "Blau", "Crewmate", "CREW", "sheriff", winners, kills = 1)},
            ${player(2, "Gruen", "Crewmate", "NEUTRAL", "jester", winners, dead = true, cause = "ejected")},
            ${player(3, "Gelb", "Crewmate", "CREW", "crewmate", winners, dead = true, cause = "kill", modifiers = "\"lover\"")},
            ${player(4, "Lila", "Impostor", "IMPOSTOR", "godfather", winners, dead = true, cause = "kill")},
            ${player(5, "Pink", "Crewmate", "CREW", "medic", winners, start = "niceShifter")},
            ${player(6, "Weiss", "Crewmate", "CREW", "niceGuesser", winners, kills = 1)}
          ],
          "kills": [{"atSeconds": 120, "killer": 1, "victim": 4}, {"atSeconds": 200, "killer": 0, "victim": 3}],
          "meetings": [
            {"atSeconds": 300, "caller": 6, "body": null, "exiled": 2, "tie": false,
             "votes": [{"voter": 1, "target": 2}, {"voter": 5, "target": 2}, {"voter": 6, "target": 0}]}
          ],
          "modDeaths": [$deaths]
        }
    """.trimIndent()

    private fun player(
        id: Int,
        name: String,
        role: String,
        team: String,
        modRole: String,
        winners: Set<Int>,
        dead: Boolean = false,
        cause: String? = null,
        kills: Int = 0,
        start: String? = null,
        modifiers: String = "",
    ) = """
        {"playerId": $id, "name": "$name", "friendCode": "fc$id#1", "puid": "", "role": "$role", "team": "$team",
         "modRole": "$modRole", "modStartRole": ${start?.let { "\"$it\"" } ?: "null"}, "modifiers": [$modifiers],
         "won": ${id in winners}, "isDead": $dead, "disconnected": false,
         "deathCause": ${cause?.let { "\"$it\"" } ?: "null"}, "killedBy": null, "diedAtSeconds": null,
         "kills": $kills, "tasksTotal": 5, "tasksCompleted": 3}
    """.trimIndent()

    private val jesterDeaths = """
        {"atSeconds": 120, "victim": 4, "killer": 1, "reason": "Kill"},
        {"atSeconds": 200, "victim": 3, "killer": 0, "reason": "Kill"},
        {"atSeconds": 310, "victim": 0, "killer": 6, "reason": "Guess"},
        {"atSeconds": 330, "victim": 2, "killer": null, "reason": "Exile"}
    """.trimIndent()

    /** XP aus der Runde ohne Erfolge (die gibt es zusätzlich, z.B. für die erste Runde). */
    private fun roundXp(s: TestServices, result: ModRoundResult, userId: Long): Int =
        s.levels.get(userId).xp - result.achievements.filter { it.userId == userId }.sumOf { it.achievement.xp }

    private fun services(): TestServices {
        val s = TestServices(newDb())
        for (id in 0..6) s.links.link("fc$id#1", 100L + id)
        return s
    }

    @Test
    fun parsesTorFields() {
        val round = ModRound.parse(torRound("t1", "NEUTRAL", "JesterWin", setOf(2), jesterDeaths))
        assertNull(round.winningTeam)
        assertTrue(round.neutralWin)
        assertTrue(round.decided)
        assertEquals("TheOtherRoles GMIA 1.4.1", round.mod)
        assertEquals(4, round.modDeaths.size)
        assertEquals("Guess", round.modDeaths[2].reason)
        assertNull(round.modDeaths[3].killer)
        val jester = round.player(2)!!
        assertTrue(jester.isNeutral && !jester.isImpostor)
        assertEquals("jester", jester.modRole)
        assertEquals("niceShifter", round.player(5)!!.modStartRole)
        assertEquals(listOf("lover"), round.player(3)!!.modifiers)

        // Runden ohne The Other Roles bleiben wie vorher
        val plain = ModRound.parse("""{"gameId": "x", "winningTeam": "UNKNOWN", "players": []}""")
        assertFalse(plain.decided)
        assertNull(plain.mod)
        assertEquals(emptyList(), plain.modDeaths)
    }

    @Test
    fun jesterWinGivesNeutralXpBonusesAndLosesAllBets() {
        val s = services()
        s.levels.addXp(900, 50)
        s.levels.addXp(901, 50)
        s.bets.place(900, Team.CREW, 10)
        s.bets.place(901, Team.IMPOSTOR, 10)

        val round = ModRound.parse(torRound("t2", "NEUTRAL", "JesterWin", setOf(2), jesterDeaths))
        val result = s.modRounds.applyWithoutRoles(round)
        val outcome = assertNotNull(result.outcome)

        assertNull(outcome.winningTeam)
        assertEquals(listOf(102L), outcome.winners.map { it.first })
        assertEquals(Rules.NEUTRAL_WIN_XP, outcome.xpOf(102))
        assertEquals(30, roundXp(s, result, 102))
        assertEquals(1, s.levels.get(102).wins)
        // Sheriff hat einen Impostor erschossen, der Guesser einen Impostor erraten – auch ohne Sieg
        assertEquals(listOf(101L to "Kill", 106L to "Guess"), outcome.bonuses.map { it.userId to it.death.reason })
        assertEquals(Rules.ROLE_ACTION_XP, roundXp(s, result, 101))
        assertEquals(Rules.ROLE_ACTION_XP, roundXp(s, result, 106))
        assertEquals(0, s.levels.get(101).wins)
        // Der Impostor-Kill an Gelb und der Rauswurf des Jesters bringen nichts
        assertEquals(0, roundXp(s, result, 100))
        assertEquals(0, roundXp(s, result, 103))
        // Sondersieg: alle Wetten verloren und das Wettbüro ist wieder offen
        assertEquals(listOf(null, null), outcome.bets.map { it.second })
        assertEquals(emptyList(), s.bets.active)
        assertEquals(40, s.levels.get(900).xp)

        // Statistik: neutrale Runde zählt nur als Spiel, Stimmen gegen den Jester weder richtig noch falsch
        val jester = s.statsOf(102)
        assertEquals(listOf(1, 0, 0, 0, 0), listOf(jester["games"], jester["crew_games"], jester["crew_wins"], jester["impostor_games"], jester["impostor_wins"]))
        val sheriff = s.statsOf(101)
        assertEquals(listOf(1, 0, 0), listOf(sheriff["crew_games"], sheriff["votes_correct"], sheriff["votes_wrong"]))
        assertEquals(1, s.statsOf(106)["votes_correct"])

        // Zurücksetzen nimmt auch Sondersieg und Rollen-Aktionen zurück
        s.modRounds.undo(round.gameId)
        for (id in 100L..106L) assertEquals(0, s.levels.get(id).xp, "XP von $id")
        assertEquals(0, s.levels.get(102).wins)
        assertEquals(listOf(900L to Team.CREW, 901L to Team.IMPOSTOR), s.bets.active.map { it.userId to it.team })
        assertEquals(0, s.statsOf(102)["games"])
    }

    @Test
    fun normalWinUsesEachWinnersTeam() {
        val s = services()
        s.levels.addXp(900, 10)
        s.bets.place(900, Team.CREW, 5)
        // Crew gewinnt; der Impostor Rot ist Lover von Gelb und gewinnt mit (TOR lässt ihn in den Gewinnern)
        val round = ModRound.parse(torRound("t3", "CREW", "CrewmatesByVote", setOf(0, 1, 3, 5, 6), "{\"atSeconds\": 50, \"victim\": 1, \"killer\": 1, \"reason\": \"Suicide\"}"))
        val result = s.modRounds.applyWithoutRoles(round)
        val outcome = assertNotNull(result.outcome)

        assertEquals(Team.CREW, outcome.winningTeam)
        assertEquals(listOf(100L, 101L, 103L, 105L, 106L), outcome.winners.map { it.first })
        assertEquals(Rules.IMPOSTOR_WIN_XP, outcome.xpOf(100))
        assertEquals(Rules.CREW_WIN_XP, outcome.xpOf(101))
        assertEquals(0, roundXp(s, result, 102), "Jester hat nicht gewonnen")
        assertEquals(emptyList(), outcome.bonuses, "Selbst-Kill ist keine Rollen-Aktion")
        assertNotNull(outcome.bets.single().second, "Crew-Wette gewinnt")
    }

    @Test
    fun embedShowsTorRoles() {
        val s = services()
        val result = s.modRounds.applyWithoutRoles(ModRound.parse(torRound("t4", "NEUTRAL", "JesterWin", setOf(2), jesterDeaths)))
        val embed = buildModRoundEmbed(result)

        assertEquals("🤡 JESTER SIEG", embed.title)
        assertEquals(Palette.PURPLE, embed.colorRaw)
        assertTrue("Der Jester wurde rausgewählt" in embed.description!!)
        val fields = embed.fields.associate { it.name to it.value!! }
        assertTrue("Blackmailer" in fields["🔪 Impostor"]!!)
        assertTrue("Godfather" in fields["🔪 Impostor"]!!)
        assertTrue("🎯" in fields["🔪 Impostor"]!!, "Todesart Guess")
        assertTrue("🔄 Shifter → 🩺 Medic" in fields["🚀 Crew"]!!)
        assertTrue("Crewmate + 💞 Lover" in fields["🚀 Crew"]!!)
        assertTrue("🤡 Jester" in fields["🟣 Neutral"]!!)
        assertTrue("🟣 war neutral" in fields["📊 Highlights"]!!)
        val rewards = fields["🏆 Belohnungen"]!!
        assertTrue("<@102> +30 XP" in rewards)
        assertTrue("<@101> +5 XP 🔫 hat <@104> ausgeschaltet" in rewards)
        assertTrue("<@106> +5 XP 🎯 hat <@100> richtig erraten" in rewards)
        assertTrue("TheOtherRoles GMIA 1.4.1" in embed.footer!!.text!!)
    }

    @Test
    fun roleLabels() {
        assertNull(torRoleLabel("crewmate"))
        assertEquals("🤠 Sheriff", torRoleLabel("sheriff"))
        assertEquals("🕴️ Godfather", torRoleLabel("godfather"))
        // Alle Rollen (nameKey) aus TheOtherRoles GMIA 1.4.1 haben einen Namen mit Emoji (außer Crewmate/Impostor)
        val nameKeys = """
            jester mayor portalmaker engineer sheriff deputy lighter godfather mafioso janitor morphling camouflager vampire
            eraser trickster cleaner warlock bountyHunter detective bait timeMaster medic niceSwapper seer hacker niceShifter
            tracker snitch jackal sidekick spy securityGuard arsonist niceGuesser evilGuesser niceWatcher evilWatcher vulture
            medium lawyer pursuer witch assassin thief ninja nekoKabocha serialKiller evilTracker undertaker mimicK mimicA
            bomber evilSwapper evilHacker trapper blackmailer yoyo zephyr fortuneTeller veteran sprinter sherlock niceYasuna
            taskMaster teleporter busker noisemaker archaeologist collator jailor evilYasuna opportunist corruptedShifter
            moriarty akujo plagueDoctor jekyllAndHyde cupid fox immoralist schrodingersCat kataomoi doomsayer pelican yandere
            bloody antiTeleportPostfix tiebreaker sunglasses lover mini vip invert chameleon multitasker diseased radar armored
            madmate
        """.trim().split(Regex("\\s+"))
        for (key in nameKeys) {
            val label = assertNotNull(torRoleLabel(key), key)
            assertTrue(label.contains(' ') && label.first().code > 127, "$key hat kein Emoji: $label")
        }
        // Madmate ohne eigene Rolle
        val round = ModRound.parse(torRound("t5", "CREW", "CrewmatesByVote", emptySet(), ""))
        assertEquals("🤪 Madmate", playerRoleLabel(round.player(3)!!.copy(modifiers = listOf("madmate"))))
        assertEquals("🤠 Sheriff (Madmate)", playerRoleLabel(round.player(1)!!.copy(modifiers = listOf("madmate"))))
        // Ohne TOR-Rolle: Rolle des Spiels wie bisher
        assertEquals(roleLabel("Crewmate"), playerRoleLabel(round.player(3)!!.copy(modifiers = emptyList(), modRole = null)))
    }

    @Test
    fun specialWinTitles() {
        val reasons = listOf(
            "LoversWin", "TeamJackalWin", "MiniLose", "JesterWin", "ArsonistWin", "VultureWin", "MoriartyWin", "AkujoWin",
            "PlagueDoctorWin", "JekyllAndHydeWin", "LawyerSoloWin", "FoxWin", "KataomoiWin", "DoomsayerWin", "PelicanWin", "YandereWin",
        )
        for (reason in reasons) assertNotEquals("🟣 SONDERSIEG", specialWinTitle(reason), reason)
        assertEquals("💼 LAWYER SIEG", specialWinTitle("LawyerSoloWin"))
        assertEquals("🟣 SONDERSIEG", specialWinTitle("TheOtherRoles99"))
    }

    @Test
    fun autoMuteSilencesBlackmailedInMeetings() {
        val event = assertNotNull(
            AutoMuteEvent.parse(
                """{"crewstats":"automute","v":1,"phase":"meeting","sender":"a#1","dead":["b#2"],"silenced":["c#3", 5]}"""
            )
        )
        assertEquals(setOf("c#3"), event.silenced)
        assertEquals(emptySet(), AutoMuteEvent.parse("""{"crewstats":"automute","phase":"tasks","dead":[]}""")!!.silenced)

        assertEquals(true to false, targetState("meeting", dead = false, silenced = true)) // redet nicht, hört zu
        assertEquals(false to false, targetState("meeting", dead = false, silenced = false))
        assertEquals(true to true, targetState("tasks", dead = false, silenced = true)) // in den Tasks wie alle Lebenden
        assertEquals(false to false, targetState("end", dead = false, silenced = true))
    }
}
