package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.services.GameSettings
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundService
import de.amongusmanager.crewbot.services.PlayerBans
import de.amongusmanager.crewbot.services.PlayerInfoService
import de.amongusmanager.crewbot.services.RoleWishService
import de.amongusmanager.crewbot.content.wishRole
import de.amongusmanager.crewbot.ui.views.playerRoleLabel
import de.amongusmanager.crewbot.web.WebAuth
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** /crewstats-sperre: alles, was von gesperrten Personen über CrewStats kommt, wird ignoriert. */
class PlayerBansTest {
    private fun round(gameId: String, hostId: Int = 0) = ModRound.parse(
        """
        {"version": 2, "gameId": "$gameId", "lobbyCode": "ABCDEF", "map": "Polus", "durationSeconds": 300,
         "endReason": "HumansByTask", "winningTeam": "CREW",
         "players": [
           ${player(0, "Rot", "IMPOSTOR", won = false, host = hostId == 0)},
           ${player(1, "Blau", "CREW", won = true, host = hostId == 1)},
           ${player(2, "Gruen", "CREW", won = true, host = hostId == 2)}
         ],
         "meetings": []}
        """.trimIndent()
    )

    private fun player(id: Int, name: String, team: String, won: Boolean, host: Boolean) = """
        {"playerId": $id, "name": "$name", "friendCode": "fc$id#1", "role": "${if (team == "CREW") "Crewmate" else "Impostor"}",
         "team": "$team", "won": $won, "isDead": false, "disconnected": false, "deathCause": null, "killedBy": null,
         "kills": 0, "tasksTotal": 5, "tasksCompleted": 5, "isHost": $host}
    """.trimIndent()

    private fun setup(): Pair<TestServices, PlayerBans> {
        val s = TestServices()
        for (id in 0..2) s.links.link("fc$id#1", 100L + id)
        return s to PlayerBans(s.db, s.links)
    }

    @Test
    fun sperreNachKontoUndFriendcode() {
        val (s, bans) = setup()
        val now = Instant.parse("2026-11-27T20:00:00Z")
        assertTrue(bans.ban(101, null, "Trollt", by = 1, now = now))
        assertFalse(bans.ban(101, null, "Trollt weiter", by = 1, now = now)) // schon gesperrt, Grund neu
        assertEquals("Trollt weiter", bans.all().single().reason)
        assertTrue(bans.isUserBanned(101))
        assertTrue(bans.isCodeBanned("FC1#1")) // verknüpfter Code des Kontos
        s.links.link("zweit#2", 101)
        assertTrue(bans.isCodeBanned("zweit#2")) // auch später verknüpfte
        assertFalse(bans.isCodeBanned("fc2#1"))

        assertTrue(bans.ban(null, " Fremd#9999 ", "", by = 1, now = now)) // ohne Verknüpfung
        assertTrue(bans.isCodeBanned("fremd#9999"))
        assertEquals(setOf("fc1#1", "fremd#9999"), bans.bannedCodes(listOf("fc0#1", "fc1#1", "FREMD#9999", "")))

        // übersteht Speichern/Lesen
        assertEquals(bans.all(), PlayerBans.parse(s.db.getSetting(PlayerBans.KEY)))
        assertEquals(now, bans.all().first().at)

        assertTrue(bans.unban(101, null))
        assertFalse(bans.unban(101, null))
        assertFalse(bans.isCodeBanned("fc1#1"))
        assertTrue(bans.unban(null, "fremd#9999"))
        assertTrue(bans.all().isEmpty())
        assertNull(s.db.getSetting(PlayerBans.KEY))
    }

    @Test
    fun gesperrteSpielerBekommenAusRundenNichts() {
        val (s, bans) = setup()
        bans.ban(102, null, "", by = 1)
        val service = ModRoundService(s.db, s.levels, s.bets, s.rounds, s.links, s.stats, s.history, s.achievements, s.archive, bans = bans)
        val result = service.applyWithoutRoles(round("r1"))
        assertEquals(setOf(0, 1), result.discordIds.keys) // Gruen (gesperrt) zählt wie unverknüpft
        assertEquals(0, s.levels.get(102).xp)
        assertEquals(0, s.stats.get(102).games)
        assertTrue(s.levels.get(101).xp > 0)
        assertEquals(1, s.stats.get(101).games)
        assertNull(service.bannedHost(round("r2", hostId = 0)))
        assertEquals("Gruen", service.bannedHost(round("r3", hostId = 2))?.name) // Runde des gesperrten Hosts zählt gar nicht
    }

    @Test
    fun keineRollenwuenscheUndKeineStatsFuerGesperrte() {
        val (s, bans) = setup()
        s.levels.addXp(101, 100)
        s.levels.addXp(102, 100)
        val wishes = RoleWishService(s.db, s.levels, s.links, { null as GameSettings? }, bans = bans)
        wishes.wish(101, wishRole("Crewmate")!!)
        wishes.wish(102, wishRole("Impostor")!!)
        bans.ban(null, "fc2#1", "", by = 1)
        assertEquals(listOf("fc1#1"), wishes.reserve(listOf("fc1#1", "fc2#1")).map { it.friendCode })

        val info = PlayerInfoService(s.db, s.links, s.levels, s.stats, s.history, ::playerRoleLabel, bans)
        assertTrue(info.info("fc1#1", withLastRound = false).linked)
        assertFalse(info.info("fc2#1", withLastRound = false).linked)
    }

    @Test
    fun websiteNimmtGesperrteAusDenBestenlisten() {
        val bans = listOf(
            de.amongusmanager.crewbot.services.PlayerBan(5, null, "", 1, Instant.EPOCH),
            de.amongusmanager.crewbot.services.PlayerBan(null, "fc7#1", "", 1, Instant.EPOCH),
        )
        val keys = PlayerBans.keysOf(bans, mapOf("fc7#1" to 7L))
        assertTrue(WebAuth.playerKeyOf(5) in keys)
        assertTrue(WebAuth.playerKeyOf(7) in keys) // Friendcode-Sperre trifft auch das verknüpfte Konto
        assertEquals(3, keys.size)
    }
}
