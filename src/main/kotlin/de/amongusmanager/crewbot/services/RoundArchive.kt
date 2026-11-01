/*
 * Archiv der Rohdaten jeder Mod-Runde: die JSON-Datei der CrewStats-Mod, unverändert und ohne Obergrenze
 * (anders als round_history mit höchstens 1000 Runden). Grundlage für eine spätere Stats-Website –
 * Format: docs/rundenformat.md im Mod-Repo (Kyloschmeilo/CrewStats).
 * Zurückgesetzte Runden bleiben gespeichert, sind aber als `undone` markiert.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.db.Database
import java.time.Instant

data class ArchivedRound(val gameId: String, val receivedAt: Instant, val version: Int, val undone: Boolean, val json: String)

class RoundArchiveService(private val db: Database, private val clock: () -> Instant = Instant::now) {
    /** Speichert die Rohdaten (in der Transaktion der Auswertung). Doppelt gesendete Runden überschreiben nichts. */
    fun store(gameId: String, version: Int, json: String) {
        db.update(
            "${db.insertIgnore} INTO round_raw (game_id, received_at, version, undone, json) VALUES (?, ?, ?, 0, ?)",
            gameId, clock(), version, json,
        )
    }

    fun markUndone(gameId: String) {
        db.update("UPDATE round_raw SET undone = 1 WHERE game_id = ?", gameId)
    }

    fun get(gameId: String): ArchivedRound? =
        db.queryOne("SELECT game_id, received_at, version, undone, json FROM round_raw WHERE game_id = ?", gameId) { row ->
            ArchivedRound(row.string("game_id"), row.instant("received_at"), row.int("version"), row.bool("undone"), row.string("json"))
        }

    fun count(): Int = db.queryOne("SELECT COUNT(*) AS n FROM round_raw") { it.int("n") } ?: 0
}
