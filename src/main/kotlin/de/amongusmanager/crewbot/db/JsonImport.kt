/*
 * Einmaliger Umzug: übernimmt beim ersten Start die Spielerdaten aus den alten JSON-Dateien
 * des Python-Bots (im Ordner data) in die Datenbank.
 *
 * Läuft nur, solange die Datenbank noch nie importiert hat (Eintrag `meta.json_import`).
 * Die JSON-Dateien werden nicht verändert und bleiben als Backup liegen.
 * Alles passiert in einer Transaktion: Bricht der Import ab, bleibt die Datenbank leer
 * und der Bot startet nicht (beim nächsten Start wird es erneut versucht).
 */
package de.amongusmanager.crewbot.db

import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.core.JSON
import de.amongusmanager.crewbot.core.pyInt
import de.amongusmanager.crewbot.core.pyLong
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import de.amongusmanager.crewbot.services.GameSettings
import de.amongusmanager.crewbot.services.GameSettingsError
import de.amongusmanager.crewbot.services.GameSettingsService
import de.amongusmanager.crewbot.services.GameStats
import de.amongusmanager.crewbot.services.MAX_HISTORY_ROUNDS
import de.amongusmanager.crewbot.services.MAX_STORED_ROUNDS
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundError
import de.amongusmanager.crewbot.services.RoundHistoryService
import de.amongusmanager.crewbot.services.normalizeFriendCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

object JsonImport {
    const val MARKER = "meta.json_import"

    /** Die Dateien des alten Bots. */
    val FILES = listOf(
        "levels.json", "bets.json", "events.json", "links.json", "game_stats.json", "mod_rounds.json",
        "mod.json", "game_settings.json", "round_history.json", "achievements.json", "minecraft.json",
    )

    private val log = LoggerFactory.getLogger(JsonImport::class.java)

    /** Importiert die JSON-Dateien, falls das noch nie passiert ist. Liefert eine Zusammenfassung (oder null). */
    fun runIfNeeded(db: Database, dataDir: Path): Map<String, Int>? {
        if (db.getSetting(MARKER) != null) return null
        val present = FILES.filter { Files.exists(dataDir.resolve(it)) }
        val summary = db.transaction {
            val counts = if (present.isEmpty()) emptyMap() else importAll(db, dataDir)
            db.setSetting(MARKER, Instant.now().toString())
            counts
        }
        if (present.isEmpty()) {
            log.info("Keine alten JSON-Daten gefunden – neue, leere Datenbank.")
        } else {
            log.info("📦 Alte JSON-Daten übernommen: {}", summary.entries.joinToString(", ") { "${it.key}=${it.value}" })
            log.info("Die JSON-Dateien in {} bleiben unverändert als Backup liegen.", dataDir)
        }
        return summary
    }

    private fun importAll(db: Database, dataDir: Path): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        fun load(name: String): JsonObject? {
            val file = dataDir.resolve(name)
            if (!Files.exists(file)) return null
            val text = Files.readString(file, Charsets.UTF_8).removePrefix("﻿")
            if (text.isBlank()) return JsonObject(emptyMap())
            return JSON.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("$name enthält kein JSON-Objekt")
        }

        load("levels.json")?.let { counts["Spieler"] = importLevels(db, it) }
        load("bets.json")?.let { counts["Wetten"] = importBets(db, it) }
        load("events.json")?.let { counts["Events"] = importEvents(db, it) }
        load("links.json")?.let { counts["Verknüpfungen"] = importLinks(db, it) }
        load("game_stats.json")?.let { counts["Statistiken"] = importGameStats(db, it) }
        load("mod_rounds.json")?.let { counts["Auswertungen"] = importLedger(db, it) }
        load("mod.json")?.let { counts["Mod-Webhook"] = importModConfig(db, it) }
        load("game_settings.json")?.let { counts["Rundeneinstellungen"] = importGameSettings(db, it) }
        load("round_history.json")?.let { counts["Rundenverlauf"] = importHistory(db, it) }
        load("achievements.json")?.let { counts["Erfolge"] = importAchievements(db, it) }
        load("minecraft.json")?.let { counts["Minecraft"] = importMinecraft(db, it) }
        return counts
    }

    // ---------- einzelne Dateien ----------

    private fun importLevels(db: Database, data: JsonObject): Int {
        val known = setOf("xp", "level", "participations", "wins")
        for ((uid, value) in data) {
            val player = value as JsonObject
            val extra = player.filterKeys { it !in known }
            db.update(
                "INSERT INTO players (user_id, xp, level, participations, wins, extra) VALUES (?, ?, ?, ?, ?, ?)",
                uid.toLong(),
                player["xp"]?.pyInt() ?: 0,
                player["level"]?.pyInt() ?: 1,
                player["participations"]?.pyInt() ?: 0,
                player["wins"]?.pyInt() ?: 0,
                if (extra.isEmpty()) null else JsonObject(extra).toString(),
            )
        }
        return data.size
    }

    private fun importBets(db: Database, data: JsonObject): Int {
        var count = 0
        for ((uid, value) in data) {
            val bet = value as JsonObject
            val team = Team.fromValue(bet["team"]?.pyStr())
            if (team == null) {
                log.warn("Wette von {} übersprungen: unbekanntes Team {}", uid, bet["team"])
                continue
            }
            db.update(
                "INSERT INTO bets (user_id, team, amount) VALUES (?, ?, ?)",
                uid.toLong(), team.value, bet["amount"].pyInt(),
            )
            count++
        }
        return count
    }

    private fun importEvents(db: Database, data: JsonObject): Int {
        for ((messageId, value) in data) {
            val event = value as JsonObject
            val endsAt = parseInstant(event["ends_at"].pyStr())
            val startsAt = event["starts_at"]?.let { parseInstant(it.pyStr()) }
                ?: (endsAt - Duration.ofHours(de.amongusmanager.crewbot.Rules.EVENT_DURATION_HOURS))
            db.update(
                "INSERT INTO events (message_id, channel_id, starts_at, ends_at, reminded) VALUES (?, ?, ?, ?, ?)",
                messageId.toLong(), event["channel_id"].pyLong(), startsAt, endsAt, event["reminded"].pyTruthy(),
            )
            val attendance = event["attendance"] as? JsonObject ?: continue
            for ((userId, minutes) in attendance) {
                db.update(
                    "INSERT INTO event_attendance (message_id, user_id, minutes) VALUES (?, ?, ?)",
                    messageId.toLong(), userId.toLong(), minutes.pyInt(),
                )
            }
        }
        return data.size
    }

    private fun importLinks(db: Database, data: JsonObject): Int {
        for ((code, userId) in data) {
            db.update(
                "INSERT INTO friend_links (friend_code, user_id) VALUES (?, ?)" +
                    " ON CONFLICT(friend_code) DO UPDATE SET user_id = excluded.user_id",
                normalizeFriendCode(code), userId.pyLong(),
            )
        }
        return data.size
    }

    private fun importGameStats(db: Database, data: JsonObject): Int {
        for ((uid, value) in data) {
            val stats = value as JsonObject
            val columns = GameStats.FIELDS
            db.update(
                "INSERT INTO game_stats (user_id, ${columns.joinToString(", ")}) VALUES (?${", ?".repeat(columns.size)})",
                uid.toLong(), *columns.map { stats[it]?.pyInt() ?: 0 }.toTypedArray(),
            )
        }
        return data.size
    }

    private fun importLedger(db: Database, data: JsonObject): Int {
        val entries = data.entries.toList().takeLast(MAX_STORED_ROUNDS)
        for ((gameId, value) in entries) {
            val entry = value as JsonObject
            db.update(
                "INSERT INTO mod_rounds (game_id, created_at, undone, channel_id, message_id) VALUES (?, ?, ?, ?, ?)",
                gameId,
                entry["created_at"]?.let { parseInstant(it.pyStr()) } ?: Instant.now(),
                entry["undone"].pyTruthy(),
                entry["channel_id"].longOrNull(),
                entry["message_id"].longOrNull(),
            )
            (entry["awards"] as? JsonArray)?.forEachIndexed { seq, item ->
                val award = item as JsonObject
                db.update(
                    "INSERT INTO mod_round_awards (game_id, seq, user_id, xp, win, old_level, new_level)" +
                        " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    gameId, seq, award["user_id"].pyLong(), award["xp"].pyInt(), award["win"].pyTruthy(),
                    award["old_level"].pyInt(), award["new_level"].pyInt(),
                )
            }
            (entry["bets"] as? JsonArray)?.forEachIndexed { seq, item ->
                val bet = item as JsonObject
                db.update(
                    "INSERT INTO mod_round_bets (game_id, seq, user_id, team, amount) VALUES (?, ?, ?, ?, ?)",
                    gameId, seq, bet["user_id"].pyLong(), bet["team"].pyStr(), bet["amount"].pyInt(),
                )
            }
            (entry["stats"] as? JsonObject)?.forEach { (uid, delta) ->
                for ((stat, amount) in delta as JsonObject) {
                    db.update(
                        "INSERT INTO mod_round_stats (game_id, user_id, stat, delta) VALUES (?, ?, ?, ?)",
                        gameId, uid.toLong(), stat, amount.pyInt(),
                    )
                }
            }
            (entry["achievements"] as? JsonArray)?.forEachIndexed { seq, item ->
                val unlock = item as JsonObject
                db.update(
                    "INSERT INTO mod_round_achievements (game_id, seq, user_id, achievement_id, xp, old_level, new_level)" +
                        " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    gameId, seq, unlock["user_id"].pyLong(), unlock["id"].pyStr(), unlock["xp"].pyInt(),
                    unlock["old_level"].pyInt(), unlock["new_level"].pyInt(),
                )
            }
        }
        return entries.size
    }

    private fun importModConfig(db: Database, data: JsonObject): Int {
        val webhookId = data["webhook_id"].longOrNull() ?: return 0
        db.setSetting("mod.webhook_id", webhookId.toString())
        data["channel_id"].longOrNull()?.let { db.setSetting("mod.channel_id", it.toString()) }
        return 1
    }

    private fun importGameSettings(db: Database, data: JsonObject): Int {
        if (data.isEmpty()) return 0
        return try {
            GameSettingsService(db).store(GameSettings.fromJson(data))
            1
        } catch (e: GameSettingsError) {
            log.warn("Alte Rundeneinstellungen übersprungen: {}", e.message)
            0
        }
    }

    private fun importHistory(db: Database, data: JsonObject): Int {
        val entries = (data["rounds"] as? JsonArray ?: return 0).takeLast(MAX_HISTORY_ROUNDS)
        val history = RoundHistoryService(db)
        var count = 0
        for (item in entries) {
            try {
                val entry = item as JsonObject
                val round = ModRound.fromJson(entry["round"] ?: continue)
                val discordIds = LinkedHashMap<Int, Long>()
                (entry["discord_ids"] as? JsonObject)?.forEach { (playerId, userId) ->
                    discordIds[playerId.toInt()] = userId.pyLong()
                }
                db.update("DELETE FROM round_history WHERE game_id = ?", round.gameId)
                history.insert(round, discordIds, parseInstant(entry["ended_at"].pyStr()))
                count++
            } catch (e: Exception) {
                // kaputter Eintrag – der alte Bot hat ihn ebenfalls übersprungen
                if (e !is ModRoundError && e !is ClassCastException && e !is NumberFormatException &&
                    e !is java.time.format.DateTimeParseException && e !is de.amongusmanager.crewbot.core.JsonValueError
                ) {
                    throw e
                }
                log.warn("Kaputter Eintrag im Rundenverlauf übersprungen: {}", e.message)
            }
        }
        return count
    }

    private fun importAchievements(db: Database, data: JsonObject): Int {
        var count = 0
        for ((uid, value) in data) {
            for ((achievementId, at) in value as JsonObject) {
                db.update(
                    "INSERT OR REPLACE INTO achievements (user_id, achievement_id, unlocked_at) VALUES (?, ?, ?)",
                    uid.toLong(), achievementId, parseInstant(at.pyStr()),
                )
                count++
            }
        }
        return count
    }

    private fun importMinecraft(db: Database, data: JsonObject): Int {
        data["webhook_id"].longOrNull()?.let { db.setSetting("minecraft.webhook_id", it.toString()) }
        data["webhook_url"]?.takeIf { it !is JsonNull }?.let { db.setSetting("minecraft.webhook_url", it.pyStr()) }
        data["channel_id"].longOrNull()?.let { db.setSetting("minecraft.channel_id", it.toString()) }
        (data["enabled"] as? JsonArray)?.let { enabled ->
            val values = enabled.map { it.pyStr() }.toSet()
            db.setSetting(
                "minecraft.enabled",
                McEventType.entries.filter { it.value in values }.joinToString(",") { it.value },
            )
        }
        return 1
    }

    // ---------- Helfer ----------

    private fun JsonElement?.longOrNull(): Long? = if (this == null || this is JsonNull) null else pyLong()

    /** ISO-Zeitpunkt wie von Pythons `isoformat()` (mit Zeitzone; ohne Zeitzone gilt UTC). */
    fun parseInstant(text: String): Instant =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        }
}
