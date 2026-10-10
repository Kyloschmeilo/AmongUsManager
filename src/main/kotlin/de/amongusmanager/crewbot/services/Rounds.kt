/* Rundenende: Gewinner bestimmen, XP & Siege vergeben und Wetten auszahlen. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member

data class RoundOutcome(
    val winningTeam: Team,
    val xpPerWinner: Int,
    val winners: List<Pair<Long, XpResult>>, // (Discord-ID, Ergebnis)
    val bets: List<Pair<Bet, XpResult?>>, // null = Wette verloren
) {
    /** Alle XP-Vergaben (für die Level-Rollen nach dem Speichern). */
    val awards: List<Pair<Long, XpResult>>
        get() = winners + bets.mapNotNull { (bet, result) -> result?.let { bet.userId to it } }

    /** Alle Ergebnisse mit dem Endstand der Spieler (siehe [XpResult.withFinalStats]). */
    fun withFinalStats(levels: LevelService): RoundOutcome = copy(
        winners = winners.map { (userId, result) -> userId to result.withFinalStats(levels, userId) },
        bets = bets.map { (bet, result) -> bet to result?.withFinalStats(levels, bet.userId) },
    )
}

class RoundService(
    private val db: Database,
    private val levels: LevelService,
    private val bets: BetService,
) {
    companion object {
        /** Impostor-Sieg: die Impostor. Crew-Sieg: alle Spieler außer den Impostorn. */
        fun winnersFor(team: Team, impostors: List<Member>, players: List<Member>): List<Member> {
            if (team == Team.IMPOSTOR) return impostors
            val impostorIds = impostors.map { it.idLong }.toSet()
            return players.filter { !it.user.isBot && it.idLong !in impostorIds }
        }
    }

    /** Speichert XP, Siege und Wett-Auszahlungen (atomar), vergibt aber noch keine Rollen. */
    fun finishWithoutRoles(winningTeam: Team, winnerIds: List<Long>): RoundOutcome = db.transaction {
        val xp = if (winningTeam == Team.IMPOSTOR) Rules.IMPOSTOR_WIN_XP else Rules.CREW_WIN_XP
        val winnerResults = winnerIds.map { userId -> userId to levels.addXp(userId, xp, win = true) }
        val betResults = bets.settle().map { bet ->
            bet to if (bet.team == winningTeam) levels.addXp(bet.userId, bet.payout) else null
        }
        RoundOutcome(winningTeam, xp, winnerResults, betResults).withFinalStats(levels)
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
