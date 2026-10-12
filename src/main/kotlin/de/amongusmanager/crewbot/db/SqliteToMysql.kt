/*
 * Umzug SQLite -> MySQL: Lief der Bot schon mit data/crewbot.db und wird dann auf MySQL umgestellt,
 * kopiert er beim ersten Start alle Daten in die (noch leere) MySQL-Datenbank. Die SQLite-Datei bleibt
 * unverändert liegen. Ohne SQLite-Datei übernimmt danach wie gewohnt der JSON-Import.
 */
package de.amongusmanager.crewbot.db

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object SqliteToMysql {
    private val log = LoggerFactory.getLogger(SqliteToMysql::class.java)

    /** Reihenfolge wegen Fremdschlüsseln: erst die Haupttabellen, dann die abhängigen. */
    val TABLES = listOf(
        "settings", "players", "bets", "events", "event_attendance", "friend_links", "game_stats",
        "mod_rounds", "mod_round_awards", "mod_round_bets", "mod_round_stats", "mod_round_achievements",
        "round_history", "round_history_players", "round_history_kills", "round_history_meetings",
        "round_history_votes", "achievements", "game_settings", "game_setting_options", "game_setting_roles",
        "role_wishes",
    )

    /** In MySQL eigene Spalte `rowid` (Reihenfolge), in SQLite die eingebaute rowid. */
    private val ROWID_TABLES = setOf("event_attendance", "friend_links", "mod_round_stats", "achievements")

    /** Kopiert die SQLite-Datei in die MySQL-Datenbank, falls diese noch leer ist. Liefert die Anzahl Zeilen oder null. */
    fun copyIfNeeded(target: Database, sqliteFile: Path): Int? {
        if (target.dialect != Dialect.MYSQL || !Files.exists(sqliteFile)) return null
        if (target.getSetting(JsonImport.MARKER) != null || count(target, "players") > 0) return null
        Database.open(sqliteFile).use { source ->
            if (source.getSetting(JsonImport.MARKER) == null && count(source, "players") == 0) return null
            log.info("📦 Übernehme die Daten aus {} in die MySQL-Datenbank …", sqliteFile)
            val copied = copy(source, target)
            log.info("📦 {} Zeilen nach MySQL kopiert. {} bleibt als Backup liegen.", copied, sqliteFile.fileName)
            return copied
        }
    }

    /** Kopiert alle Tabellen (in einer Transaktion). */
    fun copy(source: Database, target: Database): Int = target.transaction {
        var total = 0
        for (table in TABLES) {
            val select = if (table in ROWID_TABLES) {
                "SELECT rowid AS rowid, * FROM $table ORDER BY rowid"
            } else {
                "SELECT * FROM $table ORDER BY rowid"
            }
            val rows = source.queryRaw(select)
            if (rows.isEmpty()) continue
            val columns = rows.first().keys.toList()
            val sql = "INSERT INTO $table (${columns.joinToString(", ") { "`$it`" }}) " +
                "VALUES (${columns.joinToString(", ") { "?" }})"
            target.batch(sql, rows.map { row -> columns.map { row[it] } })
            total += rows.size
        }
        total
    }

    private fun count(db: Database, table: String): Int =
        db.queryOne("SELECT COUNT(*) AS n FROM $table") { it.int("n") } ?: 0
}
