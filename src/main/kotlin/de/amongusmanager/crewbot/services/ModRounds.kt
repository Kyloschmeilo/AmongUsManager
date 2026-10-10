/*
 * Automatische Auswertung der Runden, die die CrewStats-Mod schickt.
 *
 * Ablauf: Mod -> Discord-Webhook (JSON-Datei) -> Bot liest sie -> XP, Siege,
 * Wetten & Statistiken werden vergeben. Jede Auswertung wird mitprotokolliert,
 * damit sie per Button komplett zurückgesetzt werden kann.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import net.dv8tion.jda.api.entities.Guild
import java.time.Instant

const val MAX_STORED_ROUNDS = 200

// ==========================================
// ERGEBNIS
// ==========================================

data class ModRoundResult(
    val round: ModRound,
    val discordIds: Map<Int, Long>, // Ingame-PlayerId -> Discord-ID (nur verknüpfte)
    val outcome: RoundOutcome?, // null, wenn der Sieger unbekannt ist
    val unlinked: List<ModPlayer> = emptyList(),
    val achievements: List<Unlock> = emptyList(), // in dieser Runde freigeschaltet
) {
    fun mention(player: ModPlayer): String {
        val userId = discordIds[player.playerId]
        return if (userId != null) "<@$userId>" else "*${player.name}*"
    }
}

// ==========================================
// SERVICE
// ==========================================

class ModRoundService(
    private val db: Database,
    private val levels: LevelService,
    private val bets: BetService,
    private val rounds: RoundService,
    private val links: LinkService,
    private val stats: GameStatsService,
    private val history: RoundHistoryService,
    private val achievements: AchievementService,
    private val clock: () -> Instant = Instant::now,
) {
    fun isKnown(gameId: String): Boolean =
        db.queryOne("SELECT 1 AS found FROM mod_rounds WHERE game_id = ?", gameId) { true } != null

    /** Wertet eine Runde aus (alles in einer Transaktion) und vergibt danach die Level-Rollen. */
    suspend fun apply(guild: Guild?, round: ModRound): ModRoundResult {
        val result = applyWithoutRoles(round)
        val awards = (result.outcome?.awards ?: emptyList()) + result.achievements.map { it.userId to it.result }
        rounds.grantRoles(guild, awards)
        return result
    }

    fun applyWithoutRoles(round: ModRound): ModRoundResult = db.transaction {
        if (isKnown(round.gameId)) throw ModRoundError("Diese Runde wurde bereits ausgewertet.")

        val discordIds = LinkedHashMap<Int, Long>()
        for (player in round.players) {
            val userId = links.userFor(player.friendCode)
            if (userId != null) discordIds[player.playerId] = userId
        }
        val unlinked = round.players.filter { it.playerId !in discordIds }

        val outcome = round.winningTeam?.let { team ->
            val winnerIds = round.players.filter { it.won && it.playerId in discordIds }.map { discordIds.getValue(it.playerId) }
            rounds.finishWithoutRoles(team, winnerIds)
        }

        val deltas = statDeltas(round, discordIds)
        stats.apply(deltas)
        val entry = history.add(round, discordIds)
        val unlocks = achievements.evaluate(entry, stats::get, history::roundsOf)
            .map { it.copy(result = it.result.withFinalStats(levels, it.userId)) }
        val finalOutcome = outcome?.withFinalStats(levels)
        remember(round.gameId, finalOutcome, deltas, unlocks)
        ModRoundResult(round, discordIds, finalOutcome, unlinked, unlocks)
    }

    fun attachMessage(gameId: String, channelId: Long, messageId: Long) {
        db.update("UPDATE mod_rounds SET channel_id = ?, message_id = ? WHERE game_id = ?", channelId, messageId, gameId)
    }

    /**
     * Nimmt eine automatische Auswertung komplett zurück (XP, Siege, Wetten, Statistik, Verlauf, Erfolge).
     * Gespeichert ist pro Vergabe der Level davor und der End-Level der Runde – in Vergabe-Reihenfolge
     * zurückgenommen landet der Spieler dadurch wieder genau beim Level vor der Runde (wie im alten Bot).
     */
    fun undo(gameId: String) {
        db.transaction {
            val undone = db.queryOne("SELECT undone FROM mod_rounds WHERE game_id = ?", gameId) { it.bool("undone") }
                ?: throw ModRoundError("Diese Runde ist nicht (mehr) gespeichert.")
            if (undone) throw ModRoundError("Diese Runde wurde bereits zurückgesetzt.")

            val awards = db.query(
                "SELECT user_id, xp, win, old_level, new_level FROM mod_round_awards WHERE game_id = ? ORDER BY seq",
                gameId,
            ) { row ->
                LedgerAward(row.long("user_id"), row.int("xp"), row.bool("win"), row.int("old_level"), row.int("new_level"))
            }
            for (award in awards) {
                levels.revertXp(award.userId, award.xp, oldLevel = award.oldLevel, newLevel = award.newLevel, win = award.win)
            }

            val unlocks = db.query(
                "SELECT user_id, achievement_id, xp, old_level, new_level FROM mod_round_achievements" +
                    " WHERE game_id = ? ORDER BY seq",
                gameId,
            ) { row ->
                LedgerUnlock(
                    row.long("user_id"), row.string("achievement_id"), row.int("xp"),
                    row.int("old_level"), row.int("new_level"),
                )
            }
            for (unlock in unlocks) {
                levels.revertXp(unlock.userId, unlock.xp, oldLevel = unlock.oldLevel, newLevel = unlock.newLevel)
                achievements.revoke(unlock.userId, unlock.achievementId)
            }

            val restored = db.query(
                "SELECT user_id, team, amount FROM mod_round_bets WHERE game_id = ? ORDER BY seq",
                gameId,
            ) { Bet(it.long("user_id"), Team.fromValue(it.string("team")) ?: Team.CREW, it.int("amount")) }
            bets.restore(restored)

            val deltas = LinkedHashMap<Long, LinkedHashMap<String, Int>>()
            db.query("SELECT user_id, stat, delta FROM mod_round_stats WHERE game_id = ? ORDER BY rowid", gameId) {
                deltas.getOrPut(it.long("user_id")) { LinkedHashMap() }[it.string("stat")] = it.int("delta")
            }
            stats.apply(deltas, sign = -1)
            history.remove(gameId)

            db.update("UPDATE mod_rounds SET undone = 1 WHERE game_id = ?", gameId)
        }
    }

    /** Nachricht mit dem „Zurücksetzen“-Button einer Runde (für spätere Funktionen). */
    fun messageOf(gameId: String): Pair<Long, Long>? =
        db.queryOne("SELECT channel_id, message_id FROM mod_rounds WHERE game_id = ?", gameId) { row ->
            val channel = row.longOrNull("channel_id")
            val message = row.longOrNull("message_id")
            if (channel != null && message != null) channel to message else null
        }

    // ---------- intern ----------

    private data class LedgerAward(val userId: Long, val xp: Int, val win: Boolean, val oldLevel: Int, val newLevel: Int)

    private data class LedgerUnlock(
        val userId: Long,
        val achievementId: String,
        val xp: Int,
        val oldLevel: Int,
        val newLevel: Int,
    )

    private fun remember(gameId: String, outcome: RoundOutcome?, deltas: StatDeltas, unlocks: List<Unlock>) {
        db.update("INSERT INTO mod_rounds (game_id, created_at, undone) VALUES (?, ?, 0)", gameId, clock())

        var awardSeq = 0
        var betSeq = 0
        fun award(userId: Long, xp: Int, win: Boolean, result: XpResult) {
            db.update(
                "INSERT INTO mod_round_awards (game_id, seq, user_id, xp, win, old_level, new_level) VALUES (?, ?, ?, ?, ?, ?, ?)",
                gameId, awardSeq++, userId, xp, win, result.oldLevel, result.stats.level,
            )
        }
        if (outcome != null) {
            for ((userId, result) in outcome.winners) award(userId, outcome.xpPerWinner, true, result)
            for ((bet, result) in outcome.bets) {
                db.update(
                    "INSERT INTO mod_round_bets (game_id, seq, user_id, team, amount) VALUES (?, ?, ?, ?, ?)",
                    gameId, betSeq++, bet.userId, bet.team.value, bet.amount,
                )
                if (result != null) award(bet.userId, bet.payout, false, result)
            }
        }
        for ((userId, delta) in deltas) {
            for ((stat, value) in delta) {
                db.update(
                    "INSERT INTO mod_round_stats (game_id, user_id, stat, delta) VALUES (?, ?, ?, ?)",
                    gameId, userId, stat, value,
                )
            }
        }
        unlocks.forEachIndexed { index, unlock ->
            db.update(
                "INSERT INTO mod_round_achievements (game_id, seq, user_id, achievement_id, xp, old_level, new_level)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
                gameId, index, unlock.userId, unlock.achievement.id, unlock.achievement.xp,
                unlock.result.oldLevel, unlock.result.stats.level,
            )
        }
        // Nur die letzten Runden aufheben
        db.update(
            "DELETE FROM mod_rounds WHERE id NOT IN (SELECT id FROM mod_rounds ORDER BY id DESC LIMIT ?)",
            MAX_STORED_ROUNDS,
        )
    }

    companion object {
        /** Statistik-Änderungen einer Runde pro verknüpftem Spieler (wie im alten Bot). */
        fun statDeltas(round: ModRound, discordIds: Map<Int, Long>): StatDeltas {
            val deltas = LinkedHashMap<Long, LinkedHashMap<String, Int>>()
            fun add(userId: Long, field: String, value: Int) {
                val delta = deltas.getOrPut(userId) { LinkedHashMap() }
                delta[field] = (delta[field] ?: 0) + value
            }

            for (player in round.players) {
                val userId = discordIds[player.playerId] ?: continue
                val isImpostor = player.team == Team.IMPOSTOR.value
                add(userId, "games", 1)
                add(userId, if (isImpostor) "impostor_games" else "crew_games", 1)
                if (player.won) add(userId, if (isImpostor) "impostor_wins" else "crew_wins", 1)
                add(userId, "kills", player.kills)
                add(userId, "deaths", if (player.deathCause == "kill") 1 else 0)
                add(userId, "ejected", if (player.deathCause == "ejected") 1 else 0)
                add(userId, "survived", if (!player.isDead && !player.disconnected) 1 else 0)
                add(userId, "tasks_completed", player.tasksCompleted)
            }

            for (meeting in round.meetings) {
                discordIds[meeting.caller]?.let { userId ->
                    add(userId, if (meeting.body != null) "bodies_reported" else "meetings_called", 1)
                }
                for (vote in meeting.votes) {
                    val voter = round.player(vote.voter)
                    val target = round.player(vote.target)
                    val userId = discordIds[vote.voter]
                    if (userId == null || voter == null || target == null || voter.team != Team.CREW.value) continue
                    add(userId, if (target.team == Team.IMPOSTOR.value) "votes_correct" else "votes_wrong", 1)
                }
            }
            return deltas
        }
    }
}

/** Merkt sich den Webhook, über den die Mod Runden schickt. */
class ModSettings(private val db: Database) {
    val webhookId: Long? get() = db.getSetting("mod.webhook_id")?.toLongOrNull()
    val channelId: Long? get() = db.getSetting("mod.channel_id")?.toLongOrNull()

    fun set(webhookId: Long, channelId: Long) {
        db.transaction {
            db.setSetting("mod.webhook_id", webhookId.toString())
            db.setSetting("mod.channel_id", channelId.toString())
        }
    }
}
