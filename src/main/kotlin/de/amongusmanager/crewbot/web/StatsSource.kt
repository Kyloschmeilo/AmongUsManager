/*
 * Stats-Website: woher die Runden kommen.
 *  - [DatabaseSource]: die Datenbank des Bots (nur lesen): round_raw (Rohdaten seit dem Rundenarchiv),
 *    round_history (ältere Runden, höchstens 1000), friend_links (Friendcode -> Discord) und players (Level).
 *  - [DirectorySource]: ein Ordner mit Rundendateien der Mod (z.B. BepInEx/CrewStats/sent) – zum Ausprobieren
 *    ohne Datenbank.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.HistoryRound
import de.amongusmanager.crewbot.services.LeaderboardExclusions
import de.amongusmanager.crewbot.services.RoundHistoryService
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

data class ArchiveEntry(val gameId: String, val receivedAt: Instant, val undone: Boolean)

interface StatsSource {
    val description: String

    /** Alle archivierten Runden (ohne Inhalt). */
    fun archived(): List<ArchiveEntry>

    /** Rohdaten (JSON der Mod) zu den Runden. */
    fun archivedJson(gameIds: Collection<String>): Map<String, String>

    /** Ändert sich, sobald im Rundenverlauf Runden dazukommen oder wegfallen. */
    fun historySignature(): String

    fun history(): List<HistoryRound>

    fun friendLinks(): Map<String, Long>

    fun levels(): Map<Long, BotLevel>

    /** Von Admins aus den Bestenlisten ausgenommene Spielerschlüssel (services/LeaderboardExclusions.kt). */
    fun hiddenPlayers(): Set<String> = emptySet()
}

class DatabaseSource(private val db: Database) : StatsSource {
    override val description: String get() = db.description

    private fun hasTable(name: String): Boolean = db.tableNames().any { it.equals(name, ignoreCase = true) }

    override fun archived(): List<ArchiveEntry> {
        if (!hasTable("round_raw")) return emptyList()
        return db.query("SELECT game_id, received_at, undone FROM round_raw") { row ->
            ArchiveEntry(row.string("game_id"), row.instant("received_at"), row.bool("undone"))
        }
    }

    override fun archivedJson(gameIds: Collection<String>): Map<String, String> {
        val result = HashMap<String, String>()
        for (chunk in gameIds.chunked(20)) {
            val placeholders = chunk.joinToString(", ") { "?" }
            db.query("SELECT game_id, json FROM round_raw WHERE game_id IN ($placeholders)", *chunk.toTypedArray()) { row ->
                result[row.string("game_id")] = row.string("json")
            }
        }
        return result
    }

    override fun historySignature(): String {
        if (!hasTable("round_history")) return "none"
        return db.queryOne("SELECT COUNT(*) AS n, COALESCE(MAX(id), 0) AS m FROM round_history") { "${it.long("n")}:${it.long("m")}" } ?: "none"
    }

    override fun history(): List<HistoryRound> =
        if (hasTable("round_history")) RoundHistoryService(db).rounds() else emptyList()

    override fun friendLinks(): Map<String, Long> {
        if (!hasTable("friend_links")) return emptyMap()
        return db.query("SELECT friend_code, user_id FROM friend_links") { it.string("friend_code") to it.long("user_id") }.toMap()
    }

    override fun levels(): Map<Long, BotLevel> {
        if (!hasTable("players")) return emptyMap()
        return db.query("SELECT user_id, xp, level FROM players") { it.long("user_id") to BotLevel(it.int("level"), it.int("xp")) }.toMap()
    }

    override fun hiddenPlayers(): Set<String> =
        if (hasTable("settings")) LeaderboardExclusions.parse(db.getSetting(LeaderboardExclusions.KEY)) else emptySet()
}

/** Liest alle *.json-Dateien eines Ordners (mit Unterordnern) als Runden – Empfangszeit = Änderungszeit der Datei. */
class DirectorySource(private val dir: Path) : StatsSource {
    private val log = LoggerFactory.getLogger(DirectorySource::class.java)
    private data class FileInfo(val modified: Long, val gameId: String?)

    private val files = HashMap<Path, FileInfo>()
    private val paths = HashMap<String, Path>()

    override val description: String get() = "Ordner ${dir.toAbsolutePath()}"

    override fun archived(): List<ArchiveEntry> {
        val current = Files.walk(dir).use { stream -> stream.filter { it.isRegularFile() && it.extension == "json" }.toList() }
        files.keys.retainAll(current.toSet())
        paths.clear()
        val result = ArrayList<ArchiveEntry>()
        for (file in current) {
            val modified = Files.getLastModifiedTime(file).toMillis()
            val info = files[file]?.takeIf { it.modified == modified } ?: FileInfo(modified, readGameId(file)).also { files[file] = it }
            val gameId = info.gameId ?: continue
            if (paths.putIfAbsent(gameId, file) == null) result += ArchiveEntry(gameId, Instant.ofEpochMilli(modified), false)
        }
        return result
    }

    private fun readGameId(file: Path): String? = try {
        (parseJson(Files.readString(file)) as? JsonObject)?.str("gameId")
    } catch (e: Exception) {
        log.warn("Keine Rundendatei: {} ({})", file.fileName, e.message)
        null
    }

    override fun archivedJson(gameIds: Collection<String>): Map<String, String> =
        gameIds.mapNotNull { id -> paths[id]?.let { id to Files.readString(it) } }.toMap()

    override fun historySignature(): String = "none"
    override fun history(): List<HistoryRound> = emptyList()
    override fun friendLinks(): Map<String, Long> = emptyMap()
    override fun levels(): Map<Long, BotLevel> = emptyMap()
}
