package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.ModRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rundenformat 2 der CrewStats-Mod (ab 1.5.0: Zeitleiste, Laufwege, Kosmetik …): der Bot wertet es wie Version 1 aus
 * und archiviert die Rohdaten unverändert in `round_raw`. Beispiel = docs/beispiel-runde.json im Mod-Repo.
 */
open class RoundFormatV2Test {
    open fun newDb(): Database = Database.inMemory()

    private val example: String =
        javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()

    @Test
    fun parsesVersion2LikeVersion1() {
        val round = ModRound.parse(example)
        assertEquals(2, round.formatVersion)
        assertEquals("4f1c2b8e9d0a4c7f8e1b2a3c4d5e6f70", round.gameId)
        assertEquals(Team.CREW, round.winningTeam)
        assertEquals(3, round.players.size)
        assertEquals("sheriff", round.player(0)!!.modRole)
        assertEquals(listOf("lover"), round.player(2)!!.modifiers)
        assertEquals(1, round.kills.size)
        assertEquals(2, round.meetings.size)
        assertEquals(null, round.meetings[0].votes[0].target) // Skip bleibt „kein Ziel“
        assertEquals(1, round.meetings[1].exiled)
    }

    @Test
    fun archivesRawJsonAndKeepsItOnUndo() {
        val s = TestServices(newDb())
        s.links.link("kilian#1234", 100)
        s.links.link("mia#5678", 101)
        val round = ModRound.parse(example)

        val result = s.modRounds.applyWithoutRoles(round, example)
        assertNotNull(result.outcome)
        val archived = assertNotNull(s.archive.get(round.gameId))
        assertEquals(example, archived.json)
        assertEquals(2, archived.version)
        assertFalse(archived.undone)
        assertEquals(1, s.archive.count())

        s.modRounds.undo(round.gameId)
        assertTrue(s.archive.get(round.gameId)!!.undone)
        assertEquals(1, s.archive.count())
    }

    @Test
    fun roundsWithoutRawAreNotArchived() {
        val s = TestServices(newDb())
        s.modRounds.applyWithoutRoles(ModRound.parse(example))
        assertEquals(0, s.archive.count())
    }

    @Test
    fun largeRoundFitsInDatabase() {
        // Laufwege von 20 Spielern über eine Stunde: mehrere MB Text
        val s = TestServices(newDb())
        val points = (0 until 3600 * 3).joinToString(",") { "-12.34" }
        val tracks = (0 until 20).joinToString(",") { """{"player":$it,"points":[$points]}""" }
        val big = example.replace("\"tracks\": [", "\"tracks\": [$tracks,")
        assertTrue(big.length > 1_000_000)
        val round = ModRound.parse(big)
        s.modRounds.applyWithoutRoles(round, big)
        assertEquals(big, s.archive.get(round.gameId)!!.json)
    }
}
