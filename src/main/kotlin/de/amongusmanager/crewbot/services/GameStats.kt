/* Among-Us-Statistiken pro Spieler (aus der Mod). */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.db.Database
import java.math.BigDecimal
import java.math.RoundingMode

/** Python `f"{value:.Nf}"`: exakt gerundet (bei genau .5 zur geraden Ziffer, wie Python). */
fun formatFixed(value: Double, decimals: Int): String =
    BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()

data class GameStats(
    val games: Int = 0,
    val crewGames: Int = 0,
    val crewWins: Int = 0,
    val impostorGames: Int = 0,
    val impostorWins: Int = 0,
    val kills: Int = 0,
    val deaths: Int = 0, // getötet
    val ejected: Int = 0,
    val survived: Int = 0,
    val tasksCompleted: Int = 0,
    val meetingsCalled: Int = 0, // Notfall-Knopf
    val bodiesReported: Int = 0,
    val votesCorrect: Int = 0, // als Crewmate einen Impostor gewählt
    val votesWrong: Int = 0, // als Crewmate einen Crewmate gewählt
) {
    val wins: Int get() = crewWins + impostorWins

    /** Wert eines Felds über den Namen aus der Datenbank / dem alten JSON (z.B. "impostor_wins"). */
    operator fun get(field: String): Int = when (field) {
        "games" -> games
        "crew_games" -> crewGames
        "crew_wins" -> crewWins
        "impostor_games" -> impostorGames
        "impostor_wins" -> impostorWins
        "kills" -> kills
        "deaths" -> deaths
        "ejected" -> ejected
        "survived" -> survived
        "tasks_completed" -> tasksCompleted
        "meetings_called" -> meetingsCalled
        "bodies_reported" -> bodiesReported
        "votes_correct" -> votesCorrect
        "votes_wrong" -> votesWrong
        else -> throw IllegalArgumentException("Unbekanntes Statistik-Feld: $field")
    }

    companion object {
        /** Alle Felder in der Reihenfolge der Datenbank-Spalten. */
        val FIELDS: List<String> = listOf(
            "games", "crew_games", "crew_wins", "impostor_games", "impostor_wins", "kills", "deaths", "ejected",
            "survived", "tasks_completed", "meetings_called", "bodies_reported", "votes_correct", "votes_wrong",
        )

        fun fromMap(values: Map<String, Int>): GameStats = GameStats(
            games = values["games"] ?: 0,
            crewGames = values["crew_games"] ?: 0,
            crewWins = values["crew_wins"] ?: 0,
            impostorGames = values["impostor_games"] ?: 0,
            impostorWins = values["impostor_wins"] ?: 0,
            kills = values["kills"] ?: 0,
            deaths = values["deaths"] ?: 0,
            ejected = values["ejected"] ?: 0,
            survived = values["survived"] ?: 0,
            tasksCompleted = values["tasks_completed"] ?: 0,
            meetingsCalled = values["meetings_called"] ?: 0,
            bodiesReported = values["bodies_reported"] ?: 0,
            votesCorrect = values["votes_correct"] ?: 0,
            votesWrong = values["votes_wrong"] ?: 0,
        )

        /** Quote als Prozent (wie Pythons `f"{part / total:.0%}"`), "–" ohne Spiele. */
        fun rate(part: Int, total: Int): String {
            if (total == 0) return "–"
            return "${formatFixed(part.toDouble() / total * 100, 0)}%"
        }
    }
}

typealias StatDeltas = Map<Long, Map<String, Int>>

class GameStatsService(private val db: Database) {
    fun get(userId: Long): GameStats =
        db.queryOne("SELECT * FROM game_stats WHERE user_id = ?", userId) { row ->
            GameStats.fromMap(GameStats.FIELDS.associateWith { row.int(it) })
        } ?: GameStats()

    /** Addiert (sign = 1) oder entfernt (sign = -1) die Werte einer Runde. Werte fallen nie unter 0. */
    fun apply(deltas: StatDeltas, sign: Int = 1) {
        db.transaction {
            for ((userId, delta) in deltas) {
                db.update("INSERT OR IGNORE INTO game_stats (user_id) VALUES (?)", userId)
                for ((field, value) in delta) {
                    if (field !in GameStats.FIELDS) continue // unbekannte Felder aus alten Daten ignorieren
                    db.update(
                        "UPDATE game_stats SET $field = MAX(0, $field + ?) WHERE user_id = ?",
                        sign.toLong() * value, userId,
                    )
                }
            }
        }
    }
}
