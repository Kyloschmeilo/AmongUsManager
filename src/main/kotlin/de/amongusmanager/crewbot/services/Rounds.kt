/* Rundenende: Gewinner bestimmen, XP & Siege vergeben und Wetten auszahlen. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member

data class RoundOutcome(
    val winningTeam: Team?, // null = Sondersieg einer neutralen Rolle (The Other Roles) – alle Wetten verloren
    val xpPerWinner: Int,
    val winners: List<Pair<Long, XpResult>>, // (Discord-ID, Ergebnis)
    val bets: List<Pair<Bet, XpResult?>>, // null = Wette verloren
    val winnerXp: Map<Long, Int> = emptyMap(), // Sieg-XP je Spieler, wenn sie sich unterscheiden (The Other Roles)
    val bonuses: List<RoleBonus> = emptyList(), // Extra-XP für Rollen-Aktionen (The Other Roles)
) {
    /** Sieg-XP eines Gewinners. */
    fun xpOf(userId: Long): Int = winnerXp[userId] ?: xpPerWinner

    /** Alle XP-Vergaben (für die Level-Rollen nach dem Speichern). */
    val awards: List<Pair<Long, XpResult>>
        get() = winners + bets.mapNotNull { (bet, result) -> result?.let { bet.userId to it } } +
            bonuses.map { it.userId to it.result }

    /** Alle Ergebnisse mit dem Endstand der Spieler (siehe [XpResult.withFinalStats]). */
    fun withFinalStats(levels: LevelService): RoundOutcome = copy(
        winners = winners.map { (userId, result) -> userId to result.withFinalStats(levels, userId) },
        bets = bets.map { (bet, result) -> bet to result?.withFinalStats(levels, bet.userId) },
        bonuses = bonuses.map { it.copy(result = it.result.withFinalStats(levels, it.userId)) },
    )
}

/** Extra-XP für eine Rollen-Aktion, z.B. Sheriff erschießt einen Impostor ([death] = der Tod laut Mod). */
data class RoleBonus(val userId: Long, val xp: Int, val death: ModDeath, val result: XpResult)

/** Eine geplante Rollen-Aktions-Belohnung (noch nicht vergeben). */
data class RoleAction(val userId: Long, val death: ModDeath)

class RoundService(
    private val db: Database,
    private val levels: LevelService,
    private val bets: BetService,
) {
    companion object {
        /** Sieg-XP nach Team: Impostor 25, Crew 10, neutrale Rolle (The Other Roles) 30. */
        fun winXp(team: String): Int = when (team) {
            Team.IMPOSTOR.value -> Rules.IMPOSTOR_WIN_XP
            NEUTRAL_TEAM -> Rules.NEUTRAL_WIN_XP
            else -> Rules.CREW_WIN_XP
        }

        /** Impostor-Sieg: die Impostor. Crew-Sieg: alle Spieler außer den Impostorn. */
        fun winnersFor(team: Team, impostors: List<Member>, players: List<Member>): List<Member> {
            if (team == Team.IMPOSTOR) return impostors
            val impostorIds = impostors.map { it.idLong }.toSet()
            return players.filter { !it.user.isBot && it.idLong !in impostorIds }
        }
    }

    /** Speichert XP, Siege und Wett-Auszahlungen (atomar), vergibt aber noch keine Rollen. */
    fun finishWithoutRoles(winningTeam: Team, winnerIds: List<Long>): RoundOutcome {
        val xp = winXp(winningTeam.value)
        return finishWithXpWithoutRoles(winningTeam, winnerIds.map { it to xp })
    }

    /**
     * Wie [finishWithoutRoles], aber mit eigener Sieg-XP je Gewinner (The Other Roles: nach dem Team der Rolle)
     * und Rollen-Aktionen. Reihenfolge: Siege, Wetten, Rollen-Aktionen. `winningTeam = null` = Sondersieg.
     */
    fun finishWithXpWithoutRoles(
        winningTeam: Team?,
        winners: List<Pair<Long, Int>>,
        actions: List<RoleAction> = emptyList(),
    ): RoundOutcome = db.transaction {
        val defaultXp = winningTeam?.let { winXp(it.value) } ?: Rules.NEUTRAL_WIN_XP
        val winnerResults = winners.map { (userId, xp) -> userId to levels.addXp(userId, xp, win = true) }
        val betResults = bets.settle().map { bet ->
            bet to if (bet.team == winningTeam) levels.addXp(bet.userId, bet.payout) else null
        }
        val bonuses = actions.map { RoleBonus(it.userId, Rules.ROLE_ACTION_XP, it.death, levels.addXp(it.userId, Rules.ROLE_ACTION_XP)) }
        val winnerXp = winners.filter { (_, xp) -> xp != defaultXp }.toMap()
        RoundOutcome(winningTeam, defaultXp, winnerResults, betResults, winnerXp, bonuses).withFinalStats(levels)
    }

    suspend fun finish(guild: Guild?, winningTeam: Team, winnerIds: List<Long>): RoundOutcome {
        val outcome = finishWithoutRoles(winningTeam, winnerIds)
        grantRoles(guild, outcome.awards)
        return outcome
    }

    /** Level-Rollen für alle XP-Vergaben mit Level-Up. */
    suspend fun grantRoles(guild: Guild?, awards: List<Pair<Long, XpResult>>) {
        for ((userId, result) in awards) levels.grantRolesIfLeveledUp(guild, userId, result)
    }
}
