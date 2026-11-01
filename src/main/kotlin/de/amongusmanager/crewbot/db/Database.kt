/*
 * Datenbank des Bots: entweder die SQLite-Datei data/crewbot.db (Standard) oder eine MySQL/MariaDB-
 * Datenbank, z.B. die des Pelican-Panels (Zugangsdaten in der .env, siehe Config.kt).
 *
 * Alle Zugriffe laufen über diese Klasse. Mehrteilige Änderungen (z.B. eine Mod-Runde auswerten)
 * passieren in einer Transaktion – entweder wird alles gespeichert oder nichts.
 * SQL, das sich zwischen SQLite und MySQL unterscheidet, kommt aus den Dialekt-Helfern unten.
 */
package de.amongusmanager.crewbot.db

import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.util.Properties

enum class Dialect { SQLITE, MYSQL }

class Database private constructor(
    val dialect: Dialect,
    /** Beschreibung für das Log (ohne Passwort). */
    val description: String,
    /** Nur lesen (Stats-Website): Schema wird weder angelegt noch geändert, die Verbindung ist schreibgeschützt. */
    val readOnly: Boolean,
    private val connect: () -> Connection,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Database::class.java)
    private var connection: Connection = connect()
    private var transactionDepth = 0
    private var lastUsed = System.nanoTime()

    /** Zählt zurückgerollte Transaktionen – Zwischenspeicher (Caches) müssen danach neu laden. */
    @Volatile
    var rollbacks: Long = 0
        private set

    init {
        if (!readOnly) Schema.migrate(this)
    }

    companion object {
        /** MySQL trennt ungenutzte Verbindungen nach einiger Zeit – vorher kurz prüfen. */
        private const val VALIDATE_AFTER_NANOS = 30_000_000_000L

        fun open(file: Path): Database {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            return sqlite("jdbc:sqlite:${file.toAbsolutePath()}", wal = true, description = file.toAbsolutePath().toString())
        }

        /** Die Datei nur lesen (Stats-Website neben dem laufenden Bot) – sie muss schon existieren. */
        fun openReadOnly(file: Path): Database {
            require(Files.exists(file)) { "Datenbank nicht gefunden: ${file.toAbsolutePath()}" }
            val config = SQLiteConfig().apply {
                setReadOnly(true)
                setBusyTimeout(5000)
            }
            val url = "jdbc:sqlite:${file.toAbsolutePath()}"
            return Database(Dialect.SQLITE, "SQLite ${file.toAbsolutePath()} (nur lesen)", readOnly = true) { config.createConnection(url) }
        }

        /** Für Tests: Datenbank nur im Speicher. */
        fun inMemory(): Database = sqlite("jdbc:sqlite::memory:", wal = false, description = "Arbeitsspeicher")

        private fun sqlite(url: String, wal: Boolean, description: String): Database {
            val config = SQLiteConfig().apply {
                enforceForeignKeys(true)
                setBusyTimeout(5000)
                setSynchronous(SQLiteConfig.SynchronousMode.FULL)
                if (wal) setJournalMode(SQLiteConfig.JournalMode.WAL)
            }
            return Database(Dialect.SQLITE, "SQLite $description", readOnly = false) { config.createConnection(url) }
        }

        /** MySQL/MariaDB, z.B. host = "45.81.233.92:3306". [readOnly]: nur lesen (Stats-Website), kein Schema anlegen. */
        fun mysql(host: String, name: String, user: String, password: String, readOnly: Boolean = false): Database {
            val url = "jdbc:mariadb://$host/$name"
            val properties = Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("allowPublicKeyRetrieval", "true") // MySQL 8 ohne SSL (caching_sha2_password)
                setProperty("connectTimeout", "10000")
                setProperty("socketTimeout", "60000")
            }
            // Treiber direkt benutzen statt über DriverManager – im Fat-JAR ist auf die Service-Registrierung kein Verlass
            val driver = org.mariadb.jdbc.Driver()
            val description = "MySQL $name auf $host" + if (readOnly) " (nur lesen)" else ""
            return Database(Dialect.MYSQL, description, readOnly) {
                (driver.connect(url, properties) ?: error("MariaDB-Treiber lehnt die URL ab: $url")).also { connection ->
                    connection.createStatement().use { it.execute("SET NAMES utf8mb4 COLLATE utf8mb4_bin") }
                    if (readOnly) connection.isReadOnly = true
                }
            }
        }
    }

    // ---------- Dialekt-Helfer ----------

    /** „Einfügen, falls noch nicht vorhanden“. */
    val insertIgnore: String get() = if (dialect == Dialect.MYSQL) "INSERT IGNORE" else "INSERT OR IGNORE"

    /** „Einfügen oder vorhandene Zeile ersetzen“. */
    val insertOrReplace: String get() = if (dialect == Dialect.MYSQL) "REPLACE" else "INSERT OR REPLACE"

    /** Größerer von zwei Werten (SQL-Funktion). */
    val greatest: String get() = if (dialect == Dialect.MYSQL) "GREATEST" else "MAX"

    /** Anfang eines „bei Konflikt aktualisieren“-Teils, danach folgen die Zuweisungen. */
    fun onConflictUpdate(conflictColumns: String): String =
        if (dialect == Dialect.MYSQL) "ON DUPLICATE KEY UPDATE" else "ON CONFLICT($conflictColumns) DO UPDATE SET"

    /** Der Wert, der gerade eingefügt werden sollte (in einem [onConflictUpdate]-Teil). */
    fun excluded(column: String): String = if (dialect == Dialect.MYSQL) "VALUES($column)" else "excluded.$column"

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
        ensureConnection()
        try {
            connection.autoCommit = false
        } catch (e: SQLException) {
            if (dialect != Dialect.MYSQL || !isConnectionError(e)) throw e
            log.warn("Datenbankverbindung verloren ({}) – verbinde neu", e.message)
            reconnect()
            connection.autoCommit = false
        }
        transactionDepth = 1
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackError: SQLException) {
                log.warn("Rollback fehlgeschlagen: {}", rollbackError.message)
            }
            rollbacks++
            throw e
        } finally {
            transactionDepth = 0
            try {
                connection.autoCommit = true
            } catch (e: SQLException) {
                log.warn("Verbindung nach der Transaktion unbrauchbar: {}", e.message)
            }
        }
    }

    // ---------- Abfragen ----------

    /** INSERT/UPDATE/DELETE – liefert die Anzahl geänderter Zeilen. */
    @Synchronized
    fun update(sql: String, vararg params: Any?): Int = withConnection { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement, params)
            statement.executeUpdate()
        }
    }

    /** INSERT – liefert die neue Zeilen-ID. */
    @Synchronized
    fun insert(sql: String, vararg params: Any?): Long = withConnection { connection ->
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement, params)
            statement.executeUpdate()
            statement.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
        }
    }

    @Synchronized
    fun <T> query(sql: String, vararg params: Any?, map: (Row) -> T): List<T> = withConnection { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement, params)
            statement.executeQuery().use { resultSet ->
                val row = Row(resultSet)
                val result = ArrayList<T>()
                while (resultSet.next()) result.add(map(row))
                result
            }
        }
    }

    /** Dasselbe INSERT für viele Zeilen in wenigen Anfragen (schnell auch über das Netzwerk). */
    @Synchronized
    fun batch(sql: String, rows: List<List<Any?>>) {
        withConnection { connection ->
            connection.prepareStatement(sql).use { statement ->
                for ((index, row) in rows.withIndex()) {
                    bind(statement, row.toTypedArray())
                    statement.addBatch()
                    if (index % 500 == 499) statement.executeBatch()
                }
                statement.executeBatch()
            }
        }
    }

    fun <T> queryOne(sql: String, vararg params: Any?, map: (Row) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

    /** Mehrere Befehle ohne Parameter (für das Schema). */
    @Synchronized
    fun executeScript(sql: String) {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                for (part in sql.split(";")) {
                    if (part.isNotBlank()) statement.execute(part)
                }
            }
        }
    }

    /** Schema-Version (SQLite: `PRAGMA user_version`, MySQL: Tabelle `schema_version`). */
    @Synchronized
    fun userVersion(): Int = withConnection { connection ->
        connection.createStatement().use { statement ->
            if (dialect == Dialect.SQLITE) {
                statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            } else {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL) ENGINE=InnoDB")
                statement.executeQuery("SELECT MAX(version) FROM schema_version").use { it.next(); it.getInt(1) }
            }
        }
    }

    @Synchronized
    fun setUserVersion(version: Int) {
        if (dialect == Dialect.SQLITE) {
            withConnection { connection -> connection.createStatement().use { it.execute("PRAGMA user_version = $version") } }
        } else {
            update("DELETE FROM schema_version")
            update("INSERT INTO schema_version (version) VALUES (?)", version)
        }
    }

    /** Namen aller Tabellen des Bots (für Kopie und Prüfungen). */
    @Synchronized
    fun tableNames(): List<String> = withConnection { connection ->
        connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE")).use { tables ->
            val result = ArrayList<String>()
            while (tables.next()) result.add(tables.getString("TABLE_NAME"))
            result
        }
    }

    /** Liest beliebige Zeilen als Spalte -> Wert (für die Kopie SQLite -> MySQL). */
    @Synchronized
    fun queryRaw(sql: String): List<LinkedHashMap<String, Any?>> = withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                val meta = resultSet.metaData
                val result = ArrayList<LinkedHashMap<String, Any?>>()
                while (resultSet.next()) {
                    val row = LinkedHashMap<String, Any?>()
                    for (i in 1..meta.columnCount) row[meta.getColumnLabel(i)] = resultSet.getObject(i)
                    result.add(row)
                }
                result
            }
        }
    }

    /** Behält nur die [limit] neuesten Zeilen (höchste `id`) einer Tabelle – funktioniert in SQLite und MySQL. */
    fun keepNewest(table: String, limit: Int) {
        val cutoff = queryOne("SELECT id FROM $table ORDER BY id DESC LIMIT 1 OFFSET ?", limit - 1) { it.long("id") }
            ?: return
        update("DELETE FROM $table WHERE id < ?", cutoff)
    }

    // ---------- Einstellungen (Schlüssel -> Wert) ----------

    fun getSetting(key: String): String? =
        queryOne("SELECT `value` FROM settings WHERE `key` = ?", key) { it.string("value") }

    /** `null` löscht den Eintrag. */
    fun setSetting(key: String, value: String?) {
        if (value == null) {
            update("DELETE FROM settings WHERE `key` = ?", key)
        } else {
            update(
                "INSERT INTO settings (`key`, `value`) VALUES (?, ?) ${onConflictUpdate("`key`")} `value` = ${excluded("`value`")}",
                key, value,
            )
        }
    }

    @Synchronized
    override fun close() {
        connection.close()
    }

    // ---------- Verbindung ----------

    /**
     * Führt [action] aus. Bei MySQL wird eine abgelaufene Verbindung vorher erneuert; reißt sie mitten in
     * einer einzelnen Abfrage ab (außerhalb einer Transaktion), wird einmal neu verbunden und wiederholt.
     */
    private inline fun <T> withConnection(action: (Connection) -> T): T {
        ensureConnection()
        try {
            return action(connection).also { lastUsed = System.nanoTime() }
        } catch (e: SQLException) {
            if (dialect != Dialect.MYSQL || transactionDepth > 0 || !isConnectionError(e)) throw e
            log.warn("Datenbankverbindung verloren ({}) – verbinde neu", e.message)
            reconnect()
            return action(connection).also { lastUsed = System.nanoTime() }
        }
    }

    private fun ensureConnection() {
        if (dialect != Dialect.MYSQL || transactionDepth > 0) return
        if (System.nanoTime() - lastUsed < VALIDATE_AFTER_NANOS) return
        val valid = try {
            connection.isValid(5)
        } catch (e: SQLException) {
            false
        }
        if (!valid) {
            log.info("Datenbankverbindung abgelaufen – verbinde neu")
            reconnect()
        }
        lastUsed = System.nanoTime()
    }

    private fun reconnect() {
        try {
            connection.close()
        } catch (e: SQLException) {
            // alte Verbindung ist ohnehin kaputt
        }
        connection = connect()
    }

    private fun isConnectionError(e: SQLException): Boolean =
        e.sqlState?.startsWith("08") == true || e is java.sql.SQLNonTransientConnectionException ||
            e is java.sql.SQLTransientConnectionException

    private fun bind(statement: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, param ->
            val position = index + 1
            when (param) {
                null -> statement.setNull(position, Types.NULL)
                is Long -> statement.setLong(position, param)
                is Int -> statement.setInt(position, param)
                is Short -> statement.setInt(position, param.toInt())
                is Double -> statement.setDouble(position, param)
                is Float -> statement.setDouble(position, param.toDouble())
                is Boolean -> statement.setInt(position, if (param) 1 else 0)
                is String -> statement.setString(position, param)
                is Instant -> statement.setLong(position, param.toEpochMilli())
                is java.math.BigDecimal -> statement.setBigDecimal(position, param)
                is java.math.BigInteger -> statement.setLong(position, param.toLong())
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
