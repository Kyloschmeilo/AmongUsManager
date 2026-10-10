/*
 * SQLite-Datenbank (data/crewbot.db): ersetzt die alten JSON-Dateien.
 *
 * Alle Zugriffe laufen über diese Klasse. Mehrteilige Änderungen (z.B. eine Mod-Runde auswerten)
 * passieren in einer Transaktion – entweder wird alles gespeichert oder nichts.
 */
package de.amongusmanager.crewbot.db

import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Instant

class Database private constructor(url: String, val path: Path?) : AutoCloseable {
    private val connection: Connection
    private var transactionDepth = 0

    /** Zählt zurückgerollte Transaktionen – Zwischenspeicher (Caches) müssen danach neu laden. */
    @Volatile
    var rollbacks: Long = 0
        private set

    init {
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(5000)
            setSynchronous(SQLiteConfig.SynchronousMode.FULL)
            if (path != null) setJournalMode(SQLiteConfig.JournalMode.WAL)
        }
        connection = config.createConnection(url)
        Schema.migrate(this)
    }

    companion object {
        fun open(file: Path): Database {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            return Database("jdbc:sqlite:${file.toAbsolutePath()}", file)
        }

        /** Für Tests: Datenbank nur im Speicher. */
        fun inMemory(): Database = Database("jdbc:sqlite::memory:", null)
    }

    // ---------- Transaktionen ----------

    /** Führt [block] atomar aus. Verschachtelte Aufrufe laufen in der äußeren Transaktion mit. */
    @Synchronized
    fun <T> transaction(block: () -> T): T {
        if (transactionDepth > 0) {
            transactionDepth++
            try {
                return block()
            } finally {
                transactionDepth--
            }
        }
        connection.autoCommit = false
        transactionDepth = 1
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            rollbacks++
            throw e
        } finally {
            transactionDepth = 0
            connection.autoCommit = true
        }
    }

    // ---------- Abfragen ----------

    /** INSERT/UPDATE/DELETE – liefert die Anzahl geänderter Zeilen. */
    @Synchronized
    fun update(sql: String, vararg params: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, params)
            statement.executeUpdate()
        }

    /** INSERT – liefert die neue Zeilen-ID. */
    @Synchronized
    fun insert(sql: String, vararg params: Any?): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement, params)
            statement.executeUpdate()
            statement.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
        }

    @Synchronized
    fun <T> query(sql: String, vararg params: Any?, map: (Row) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, params)
            statement.executeQuery().use { resultSet ->
                val row = Row(resultSet)
                val result = ArrayList<T>()
                while (resultSet.next()) result.add(map(row))
                result
            }
        }

    fun <T> queryOne(sql: String, vararg params: Any?, map: (Row) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

    /** Mehrere Befehle ohne Parameter (für das Schema). */
    @Synchronized
    fun executeScript(sql: String) {
        connection.createStatement().use { statement ->
            for (part in sql.split(";")) {
                if (part.isNotBlank()) statement.execute(part)
            }
        }
    }

    @Synchronized
    fun userVersion(): Int =
        connection.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

    @Synchronized
    fun setUserVersion(version: Int) {
        connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    // ---------- Einstellungen (Schlüssel -> Wert) ----------

    fun getSetting(key: String): String? =
        queryOne("SELECT value FROM settings WHERE key = ?", key) { it.string("value") }

    /** `null` löscht den Eintrag. */
    fun setSetting(key: String, value: String?) {
        if (value == null) {
            update("DELETE FROM settings WHERE key = ?", key)
        } else {
            update(
                "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, value,
            )
        }
    }

    @Synchronized
    override fun close() {
        connection.close()
    }

    private fun bind(statement: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, param ->
            val position = index + 1
            when (param) {
                null -> statement.setNull(position, Types.NULL)
                is Long -> statement.setLong(position, param)
                is Int -> statement.setInt(position, param)
                is Double -> statement.setDouble(position, param)
                is Boolean -> statement.setInt(position, if (param) 1 else 0)
                is String -> statement.setString(position, param)
                is Instant -> statement.setLong(position, param.toEpochMilli())
                else -> throw IllegalArgumentException("Nicht unterstützter Parameter: ${param::class}")
            }
        }
    }
}

/** Eine Ergebniszeile mit typisierten Zugriffen. */
class Row(private val resultSet: ResultSet) {
    fun long(column: String): Long = resultSet.getLong(column)
    fun int(column: String): Int = resultSet.getInt(column)
    fun double(column: String): Double = resultSet.getDouble(column)
    fun string(column: String): String = resultSet.getString(column)
    fun bool(column: String): Boolean = resultSet.getInt(column) != 0
    fun instant(column: String): Instant = Instant.ofEpochMilli(resultSet.getLong(column))

    fun longOrNull(column: String): Long? = resultSet.getLong(column).takeUnless { resultSet.wasNull() }
    fun intOrNull(column: String): Int? = resultSet.getInt(column).takeUnless { resultSet.wasNull() }
    fun doubleOrNull(column: String): Double? = resultSet.getDouble(column).takeUnless { resultSet.wasNull() }
    fun stringOrNull(column: String): String? = resultSet.getString(column)
}
