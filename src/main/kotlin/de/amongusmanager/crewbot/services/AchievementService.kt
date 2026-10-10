/*
 * Erfolge: prüft nach jeder Mod-Runde, wer etwas Neues freigeschaltet hat, und vergibt XP.
 *
 * Freigeschaltete Erfolge stehen in der Tabelle `achievements`. Wird eine Runde zurückgesetzt,
 * nimmt `revoke` die dort freigeschalteten Erfolge samt XP zurück.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.content.ACHIEVEMENTS
import de.amongusmanager.crewbot.content.Achievement
import de.amongusmanager.crewbot.content.AchievementKind
import de.amongusmanager.crewbot.content.STREAK_LENGTH
import de.amongusmanager.crewbot.db.Database
import java.time.Instant

data class Unlock(val userId: Long, val achievement: Achievement, val result: XpResult)

// ---------- Prüfungen für Runden-Erfolge: (Runde, Spieler) -> geschafft? ----------

private fun doubleKill(history: HistoryRound, player: ModPlayer): Boolean =
    player.playerId in fastestDoubleKill(history.round)

private fun invisible(history: HistoryRound, player: ModPlayer): Boolean {
    val round = history.round
    return player.isImpostor && player.won && round.meetings.isNotEmpty() &&
        (votesReceived(round)[player.playerId] ?: 0) == 0
}

private fun loneWolf(history: HistoryRound, player: ModPlayer): Boolean {
    val partners = history.round.players.filter { it.isImpostor && it.playerId != player.playerId }
    return player.isImpostor && player.won && !player.isDead && partners.isNotEmpty() && partners.all { it.isDead }
}

private fun sharpEye(history: HistoryRound, player: ModPlayer): Boolean {
    val (right, wrong) = voteAccuracy(history.round)[player.playerId] ?: (0 to 0)
    return right >= 2 && wrong == 0
}

val ROUND_CHECKS: Map<String, (HistoryRound, ModPlayer) -> Boolean> = mapOf(
    "double_kill" to ::doubleKill,
    "invisible" to ::invisible,
    "lone_wolf" to ::loneWolf,
    "sharp_eye" to ::sharpEye,
)

// ---------- Serien: (Spieler in einer Runde) -> zählt für die Serie? ----------

val STREAK_CHECKS: Map<String, (ModPlayer) -> Boolean> = mapOf(
    "survivor_streak" to { p -> !p.isDead && !p.disconnected },
    "win_streak" to { p -> p.won },
)

class AchievementService(
    private val db: Database,
    private val levels: LevelService,
    private val clock: () -> Instant = Instant::now,
) {
    /** Freigeschaltete Erfolge eines Spielers (id -> Zeitpunkt). */
    fun unlocked(userId: Long): Map<String, Instant> {
        val result = LinkedHashMap<String, Instant>()
        db.query(
            "SELECT achievement_id, unlocked_at FROM achievements WHERE user_id = ? ORDER BY rowid",
            userId,
        ) { result[it.string("achievement_id")] = it.instant("unlocked_at") }
        return result
    }

    /**
     * Prüft alle verknüpften Spieler einer (bereits gespeicherten) Runde und schreibt XP gut.
     * Level-Rollen vergibt der Aufrufer danach (siehe ModRoundService.apply).
     */
    fun evaluate(
        history: HistoryRound,
        statsOf: (Long) -> GameStats,
        roundsOf: (Long) -> List<HistoryRound>,
    ): List<Unlock> = db.transaction {
        val unlocks = ArrayList<Unlock>()
        for (player in history.round.players) {
            val userId = history.discordIds[player.playerId] ?: continue
            val have = unlocked(userId).keys
            val stats = statsOf(userId)
            val streakRounds = roundsOf(userId).takeLast(STREAK_LENGTH)
            for (achievement in ACHIEVEMENTS) {
                if (achievement.id in have || !earned(achievement, history, player, stats, userId, streakRounds)) continue
                val result = levels.addXp(userId, achievement.xp)
                db.update(
                    "${db.insertOrReplace} INTO achievements (user_id, achievement_id, unlocked_at) VALUES (?, ?, ?)",
                    userId, achievement.id, clock(),
                )
                unlocks.add(Unlock(userId, achievement, result))
            }
        }
        unlocks
    }

    fun revoke(userId: Long, achievementId: String) {
        db.update("DELETE FROM achievements WHERE user_id = ? AND achievement_id = ?", userId, achievementId)
    }

    companion object {
        /** (aktuell, Ziel) für Gesamtziele, sonst null. */
        fun progress(achievement: Achievement, stats: GameStats): Pair<Int, Int>? {
            if (achievement.kind != AchievementKind.STAT || achievement.stat == null) return null
            return minOf(stats[achievement.stat], achievement.target) to achievement.target
        }

        fun earned(
            achievement: Achievement,
            history: HistoryRound,
            player: ModPlayer,
            stats: GameStats,
            userId: Long,
            streakRounds: List<HistoryRound>,
        ): Boolean = when (achievement.kind) {
            AchievementKind.STAT -> achievement.stat != null && stats[achievement.stat] >= achievement.target
            AchievementKind.ROUND -> ROUND_CHECKS[achievement.id]?.invoke(history, player) ?: false
            AchievementKind.STREAK -> {
                val check = STREAK_CHECKS[achievement.id]
                check != null && streakRounds.size >= STREAK_LENGTH &&
                    streakRounds.all { round -> round.playerOf(userId)?.let(check) ?: false }
            }
        }
    }
}
