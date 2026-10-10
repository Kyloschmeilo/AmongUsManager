/*
 * Rundenverlauf: jede ausgewertete Mod-Runde mit allen Details (für Highlights, Rivalen,
 * Erfolge und den Spieleabend-Recap). „Zurücksetzen“ entfernt eine Runde wieder.
 *
 * Gespeichert werden die Daten der Mod plus die Zuordnung Ingame-Spieler -> Discord-ID,
 * wie sie zum Zeitpunkt der Runde galt (spätere /verknuepfen ändern alte Runden nicht).
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import java.time.Instant

const val MAX_HISTORY_ROUNDS = 1000 // ältere Runden fallen raus

data class HistoryRound(
    val round: ModRound,
    val discordIds: Map<Int, Long>, // Ingame-PlayerId -> Discord-ID (nur verknüpfte)
    val endedAt: Instant,
) {
    fun userOf(playerId: Int?): Long? = if (playerId == null) null else discordIds[playerId]

    fun playerOf(userId: Long): ModPlayer? = round.players.firstOrNull { discordIds[it.playerId] == userId }
}

class RoundHistoryService(private val db: Database, private val clock: () -> Instant = Instant::now) {
    private var cache: List<HistoryRound>? = null
    private var cacheRollbacks = -1L // Stand von db.rollbacks, als der Cache geladen wurde

    fun add(round: ModRound, discordIds: Map<Int, Long>, endedAt: Instant? = null): HistoryRound {
        val ended = endedAt ?: clock()
        db.transaction {
            db.update("DELETE FROM round_history WHERE game_id = ?", round.gameId)
            insert(round, discordIds, ended)
            db.update(
                "DELETE FROM round_history WHERE id NOT IN (SELECT id FROM round_history ORDER BY id DESC LIMIT ?)",
                MAX_HISTORY_ROUNDS,
            )
        }
        cache = null
        return HistoryRound(round, LinkedHashMap(discordIds), ended)
    }

    fun remove(gameId: String) {
        if (db.update("DELETE FROM round_history WHERE game_id = ?", gameId) > 0) cache = null
    }

    /** Alle Runden (älteste zuerst), optional nur in einem Zeitraum (Grenzen eingeschlossen). */
    fun rounds(since: Instant? = null, until: Instant? = null): List<HistoryRound> =
        all().filter { (since == null || it.endedAt >= since) && (until == null || it.endedAt <= until) }

    fun roundsOf(userId: Long): List<HistoryRound> = all().filter { userId in it.discordIds.values }

    /** Schreibt eine Runde in die Tabellen (auch für den Import der alten JSON-Daten). */
    internal fun insert(round: ModRound, discordIds: Map<Int, Long>, endedAt: Instant) {
        val roundId = db.insert(
            "INSERT INTO round_history (game_id, ended_at, lobby_code, map, duration_seconds, end_reason, winning_team)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?)",
            round.gameId, endedAt, round.lobbyCode, round.map, round.durationSeconds, round.endReason,
            round.winningTeam?.value,
        )
        round.players.forEachIndexed { seq, p ->
            db.update(
                "INSERT INTO round_history_players (round_id, seq, player_id, discord_id, name, friend_code, role, team," +
                    " won, is_dead, disconnected, death_cause, killed_by, died_at, kills, tasks_total, tasks_completed)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                roundId, seq, p.playerId, discordIds[p.playerId], p.name, p.friendCode, p.role, p.team,
                p.won, p.isDead, p.disconnected, p.deathCause, p.killedBy, p.diedAt, p.kills, p.tasksTotal,
                p.tasksCompleted,
            )
        }
        round.kills.forEachIndexed { seq, kill ->
            db.update(
                "INSERT INTO round_history_kills (round_id, seq, at_seconds, killer, victim) VALUES (?, ?, ?, ?, ?)",
                roundId, seq, kill.atSeconds, kill.killer, kill.victim,
            )
        }
        round.meetings.forEachIndexed { seq, meeting ->
            db.update(
                "INSERT INTO round_history_meetings (round_id, seq, at_seconds, caller, body, exiled, tie)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
                roundId, seq, meeting.atSeconds, meeting.caller, meeting.body, meeting.exiled, meeting.tie,
            )
            meeting.votes.forEachIndexed { voteSeq, vote ->
                db.update(
                    "INSERT INTO round_history_votes (round_id, meeting_seq, seq, voter, target) VALUES (?, ?, ?, ?, ?)",
                    roundId, seq, voteSeq, vote.voter, vote.target,
                )
            }
        }
    }

    /** Lädt alle Runden auf einmal (5 Abfragen statt einer pro Runde) und merkt sie sich bis zur nächsten Änderung. */
    private fun all(): List<HistoryRound> {
        // Wurde eine Transaktion zurückgerollt, kann der Cache Runden enthalten, die nie gespeichert wurden
        if (cacheRollbacks == db.rollbacks) cache?.let { return it }
        val rollbacksBefore = db.rollbacks
        val loaded = db.transaction {
            val players = HashMap<Long, MutableList<Pair<ModPlayer, Long?>>>()
            db.query("SELECT * FROM round_history_players ORDER BY round_id, seq") { row ->
                players.getOrPut(row.long("round_id")) { ArrayList() }.add(
                    ModPlayer(
                        playerId = row.int("player_id"),
                        name = row.string("name"),
                        friendCode = row.string("friend_code"),
                        role = row.string("role"),
                        team = row.string("team"),
                        won = row.bool("won"),
                        isDead = row.bool("is_dead"),
                        disconnected = row.bool("disconnected"),
                        deathCause = row.stringOrNull("death_cause"),
                        killedBy = row.intOrNull("killed_by"),
                        kills = row.int("kills"),
                        tasksTotal = row.int("tasks_total"),
                        tasksCompleted = row.int("tasks_completed"),
                        diedAt = row.doubleOrNull("died_at"),
                    ) to row.longOrNull("discord_id")
                )
            }
            val kills = HashMap<Long, MutableList<ModKill>>()
            db.query("SELECT * FROM round_history_kills ORDER BY round_id, seq") { row ->
                kills.getOrPut(row.long("round_id")) { ArrayList() }
                    .add(ModKill(row.double("at_seconds"), row.int("killer"), row.int("victim")))
            }
            val votes = HashMap<Pair<Long, Int>, MutableList<ModVote>>()
            db.query("SELECT * FROM round_history_votes ORDER BY round_id, meeting_seq, seq") { row ->
                votes.getOrPut(row.long("round_id") to row.int("meeting_seq")) { ArrayList() }
                    .add(ModVote(row.int("voter"), row.intOrNull("target")))
            }
            val meetings = HashMap<Long, MutableList<ModMeeting>>()
            db.query("SELECT * FROM round_history_meetings ORDER BY round_id, seq") { row ->
                val roundId = row.long("round_id")
                meetings.getOrPut(roundId) { ArrayList() }.add(
                    ModMeeting(
                        caller = row.int("caller"),
                        body = row.intOrNull("body"),
                        votes = votes[roundId to row.int("seq")] ?: emptyList(),
                        exiled = row.intOrNull("exiled"),
                        tie = row.bool("tie"),
                        atSeconds = row.double("at_seconds"),
                    )
                )
            }
            db.query("SELECT * FROM round_history ORDER BY id") { row ->
                val roundId = row.long("id")
                val roundPlayers = players[roundId] ?: emptyList()
                val discordIds = LinkedHashMap<Int, Long>()
                for ((player, discordId) in roundPlayers) {
                    if (discordId != null) discordIds[player.playerId] = discordId
                }
                HistoryRound(
                    round = ModRound(
                        gameId = row.string("game_id"),
                        lobbyCode = row.string("lobby_code"),
                        map = row.string("map"),
                        durationSeconds = row.double("duration_seconds"),
                        endReason = row.string("end_reason"),
                        winningTeam = Team.fromValue(row.stringOrNull("winning_team")),
                        players = roundPlayers.map { it.first },
                        meetings = meetings[roundId] ?: emptyList(),
                        kills = kills[roundId] ?: emptyList(),
                    ),
                    discordIds = discordIds,
                    endedAt = row.instant("ended_at"),
                )
            }
        }
        cache = loaded
        cacheRollbacks = rollbacksBefore
        return loaded
    }
}
