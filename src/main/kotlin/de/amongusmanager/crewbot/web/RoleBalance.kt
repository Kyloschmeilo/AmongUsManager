package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.content.torRoleName
import kotlinx.serialization.json.JsonObject
import kotlin.math.sqrt

// Balancing der Rollen mit The Other Roles (/api/balance): wie oft kommt jede Rolle vor und gewinnt sie öfter oder
// seltener als der Rest ihres Teams? Gezählt werden nur Runden mit The Other Roles, die Rolle zu Rundenbeginn.
// Alles reine Rechnung auf den Runden des Snapshots, nichts wird gespeichert.

/** Ab so vielen Runden mit einer Rolle gibt es eine Einschätzung (vorher „zu wenig Daten“). */
const val BALANCE_MIN_GAMES = 8

/** Ab dieser Abweichung zur Team-Quote gilt eine Rolle als auffällig, auch wenn es statistisch noch nicht sicher ist. */
const val BALANCE_TREND = 0.15

/** Zeitraum „zuletzt“: die neuesten so vielen Runden mit The Other Roles. */
const val BALANCE_RECENT_ROUNDS = 50

private class BalanceAcc(val id: String) {
    val teams = HashMap<String, Int>()
    val players = HashSet<String>()
    var games = 0
    var rounds = 0
    var wins = 0
    var kills = 0
    var survived = 0
    var killed = 0
    var ejected = 0
    var guessed = 0
    val team: String get() = teams.maxByOrNull { it.value }?.key ?: UNKNOWN
}

/** Runde mit The Other Roles (normales Spiel, kein Freeplay – das filtert schon der Snapshot). */
internal fun isTorRound(round: WebRound): Boolean =
    round.mod?.contains("other", ignoreCase = true) == true || round.players.any { it.modRole != null || it.modStartRole != null }

/** 95-%-Wilson-Intervall einer Quote (stabil auch bei wenigen Runden). */
internal fun wilson(wins: Int, games: Int, z: Double = 1.96): Pair<Double, Double> {
    if (games <= 0) return 0.0 to 1.0
    val n = games.toDouble()
    val p = wins / n
    val denom = 1 + z * z / n
    val center = (p + z * z / (2 * n)) / denom
    val half = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (center - half).coerceAtLeast(0.0) to (center + half).coerceAtMost(1.0)
}

/**
 * Einschätzung einer Rolle gegen die Vergleichsquote [baseline]:
 * strong/weak = Intervall liegt ganz darüber/darunter, trend_strong/trend_weak = Abweichung ≥ [BALANCE_TREND],
 * ok = unauffällig, few = weniger als [BALANCE_MIN_GAMES] Runden.
 */
internal fun balanceVerdict(wins: Int, games: Int, baseline: Double?): String {
    if (games < BALANCE_MIN_GAMES || baseline == null) return "few"
    val (low, high) = wilson(wins, games)
    val delta = wins.toDouble() / games - baseline
    return when {
        low > baseline -> "strong"
        high < baseline -> "weak"
        delta >= BALANCE_TREND -> "trend_strong"
        delta <= -BALANCE_TREND -> "trend_weak"
        else -> "ok"
    }
}

/** Antwort von /api/balance für die Runden [views] (neueste zuerst). */
internal fun roleBalance(views: List<RoundView>): JsonObject {
    val tor = views.filter { isTorRound(it.round) }
    return obj(
        "minGames" to BALANCE_MIN_GAMES,
        "trend" to BALANCE_TREND,
        "recentRounds" to BALANCE_RECENT_ROUNDS,
        "all" to balancePeriod(tor),
        "recent" to balancePeriod(tor.take(BALANCE_RECENT_ROUNDS)),
    )
}

private fun balancePeriod(views: List<RoundView>): JsonObject {
    val roles = HashMap<String, BalanceAcc>()
    val modifiers = HashMap<String, BalanceAcc>()
    val teamGames = HashMap<String, Int>()
    val teamWins = HashMap<String, Int>()
    val winners = HashMap<String, Int>()
    val specialWins = HashMap<String, Int>()
    var playerGames = 0
    var playerWins = 0
    var duration = 0.0

    for (view in views) {
        val r = view.round
        winners.merge(r.winningTeam, 1, Int::plus)
        if (r.winningTeam == NEUTRAL) specialWins.merge(r.endReason.ifEmpty { "?" }, 1, Int::plus)
        duration += r.durationSeconds
        val seen = HashSet<String>()
        r.players.forEachIndexed { index, p ->
            val key = view.keys[index]
            playerGames++
            if (p.won) playerWins++
            teamGames.merge(p.team, 1, Int::plus)
            if (p.won) teamWins.merge(p.team, 1, Int::plus)
            val roleId = roleIdOf(p)
            val acc = roles.getOrPut(roleId) { BalanceAcc(roleId) }
            acc.games++
            if (seen.add(roleId)) acc.rounds++
            acc.players += key
            acc.teams.merge(p.team, 1, Int::plus)
            if (p.won) acc.wins++
            acc.kills += p.kills
            if (!p.isDead && !p.disconnected) acc.survived++
            when (p.deathCause) {
                "ejected" -> acc.ejected++
                "guess", "guessed" -> acc.guessed++
                "kill" -> acc.killed++
            }
            for (modifier in p.modifiers.distinct()) {
                val m = modifiers.getOrPut(modifier) { BalanceAcc(modifier) }
                m.games++
                m.rounds++
                m.players += key
                m.teams.merge(p.team, 1, Int::plus)
                if (p.won) m.wins++
            }
        }
    }

    fun row(acc: BalanceAcc, baseline: Double?, name: String): JsonObject {
        val (low, high) = wilson(acc.wins, acc.games)
        val rate = if (acc.games > 0) acc.wins.toDouble() / acc.games else 0.0
        return obj(
            "id" to acc.id,
            "name" to name,
            "team" to acc.team,
            "mod" to acc.id.startsWith("tor:"),
            "games" to acc.games,
            "rounds" to acc.rounds,
            "share" to if (views.isEmpty()) 0.0 else acc.rounds.toDouble() / views.size,
            "players" to acc.players.size,
            "wins" to acc.wins,
            "winRate" to rate,
            "low" to low,
            "high" to high,
            "baseline" to baseline,
            "delta" to baseline?.let { rate - it },
            "verdict" to balanceVerdict(acc.wins, acc.games, baseline),
            "kills" to acc.kills,
            "survived" to acc.survived,
            "killed" to acc.killed,
            "ejected" to acc.ejected,
            "guessed" to acc.guessed,
        )
    }

    // Vergleich: alle anderen Spieler desselben Teams im selben Zeitraum (ohne die Rolle selbst)
    val roleRows = roles.values.sortedByDescending { it.games }.map { acc ->
        val team = acc.team
        val otherGames = (teamGames[team] ?: 0) - (acc.teams[team] ?: 0)
        val otherWins = (teamWins[team] ?: 0) - roleWinsInTeam(acc, team, views)
        val baseline = if (otherGames > 0) otherWins.toDouble() / otherGames else null
        row(acc, baseline, roleName(acc.id))
    }
    // Modifikatoren: Vergleich mit allen Spielern ohne diesen Modifikator
    val modifierRows = modifiers.values.sortedByDescending { it.games }.map { m ->
        val otherGames = playerGames - m.games
        val baseline = if (otherGames > 0) (playerWins - m.wins).toDouble() / otherGames else null
        row(m, baseline, torRoleName(m.id) ?: m.id)
    }

    return obj(
        "rounds" to views.size,
        "from" to views.lastOrNull()?.round?.endedAt?.toString(),
        "to" to views.firstOrNull()?.round?.endedAt?.toString(),
        "avgDuration" to if (views.isEmpty()) 0.0 else duration / views.size,
        "winners" to obj(
            "CREW" to (winners[CREW] ?: 0),
            "IMPOSTOR" to (winners[IMPOSTOR] ?: 0),
            "NEUTRAL" to (winners[NEUTRAL] ?: 0),
            "UNKNOWN" to (winners[UNKNOWN] ?: 0),
        ),
        "teams" to listOf(CREW, IMPOSTOR, NEUTRAL).map { team ->
            obj("team" to team, "games" to (teamGames[team] ?: 0), "wins" to (teamWins[team] ?: 0))
        },
        "specialWins" to specialWins.entries.sortedByDescending { it.value }.map { obj("reason" to it.key, "count" to it.value) },
        "roles" to roleRows,
        "modifiers" to modifierRows,
    )
}

/** Siege einer Rolle, die sie im Team [team] geholt hat (Rollen wechseln selten das Team, z.B. Madmate). */
private fun roleWinsInTeam(acc: BalanceAcc, team: String, views: List<RoundView>): Int {
    if (acc.teams.size <= 1) return acc.wins
    var wins = 0
    for (view in views) for (p in view.round.players) if (p.won && p.team == team && roleIdOf(p) == acc.id) wins++
    return wins
}
