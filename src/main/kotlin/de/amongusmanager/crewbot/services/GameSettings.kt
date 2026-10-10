/*
 * Die Lobbyeinstellungen der letzten Runde, die die CrewStats-Mod beim Rundenstart schickt.
 *
 * JSON-Format = mod/CrewStats/SettingsReporter.cs – beide Seiten gemeinsam ändern!
 * Gespeichert wird immer nur der neueste Stand (überschreibt den alten).
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.JSON
import de.amongusmanager.crewbot.core.JsonKeyError
import de.amongusmanager.crewbot.core.JsonValueError
import de.amongusmanager.crewbot.core.getStr
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.core.pyInt
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import de.amongusmanager.crewbot.core.require
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

const val SETTINGS_FILE_NAME = "crewstats-settings.json"

val OPTION_KINDS = listOf("ints", "floats", "bools")

/** Fehler mit einer Nachricht, die direkt angezeigt werden kann. */
class GameSettingsError(message: String) : Exception(message)

data class RoleRate(val count: Int, val chance: Int) {
    val active: Boolean get() = count > 0 && chance > 0
}

data class GameSettings(
    val capturedAt: Instant,
    val lobbyCode: String,
    val map: String,
    val players: Int,
    val maxPlayers: Int,
    val numImpostors: Int,
    val options: Map<String, Map<String, JsonElement>>, // "ints" | "floats" | "bools" -> Name -> Wert
    val roles: Map<String, RoleRate> = emptyMap(),
) {
    fun value(kind: String, key: String): JsonElement? = options[kind]?.get(key)?.takeUnless { it is JsonNull }

    companion object {
        fun parse(text: String): GameSettings {
            val element = try {
                parseJson(text)
            } catch (e: SerializationException) {
                throw GameSettingsError("Ungültiges JSON: ${e.message}")
            }
            return fromJson(element)
        }

        fun fromJson(element: JsonElement): GameSettings {
            val data = element as? JsonObject ?: throw GameSettingsError("Das sind keine Rundeneinstellungen.")
            val marker = (data["crewstats"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (marker != "settings") throw GameSettingsError("Das sind keine Rundeneinstellungen.")
            try {
                return GameSettings(
                    capturedAt = parseCapturedAt(data.require("capturedAt").pyStr()),
                    lobbyCode = data.getStr("lobbyCode", ""),
                    map = data.getStr("map", "?"),
                    players = data["players"]?.pyInt() ?: 0,
                    maxPlayers = data["maxPlayers"]?.pyInt() ?: 0,
                    numImpostors = data["numImpostors"]?.pyInt() ?: 0,
                    options = OPTION_KINDS.associateWithTo(LinkedHashMap()) { kind -> optionMap(data[kind], kind) },
                    roles = roleMap(data["roles"]),
                )
            } catch (e: JsonKeyError) {
                throw GameSettingsError("Ungültige Rundeneinstellungen: ${e.message}")
            } catch (e: JsonValueError) {
                throw GameSettingsError("Ungültige Rundeneinstellungen: ${e.message}")
            } catch (e: DateTimeParseException) {
                throw GameSettingsError("Ungültige Rundeneinstellungen: ${e.message}")
            }
        }

        /** `dict(data.get(kind) or {})` */
        private fun optionMap(value: JsonElement?, kind: String): Map<String, JsonElement> {
            if (!value.pyTruthy()) return emptyMap()
            val obj = value as? JsonObject ?: throw JsonValueError("'$kind' ist kein Objekt")
            return LinkedHashMap(obj)
        }

        private fun roleMap(value: JsonElement?): Map<String, RoleRate> {
            if (!value.pyTruthy()) return emptyMap()
            val obj = value as? JsonObject ?: throw JsonValueError("'roles' ist kein Objekt")
            val result = LinkedHashMap<String, RoleRate>()
            for ((name, rate) in obj) {
                val rateObj = rate as? JsonObject ?: throw JsonValueError("Rolle '$name' ist kein Objekt")
                result[name] = RoleRate(rateObj["count"]?.pyInt() ?: 0, rateObj["chance"]?.pyInt() ?: 0)
            }
            return result
        }

        /** .NET schickt z.B. "2026-10-10T18:30:12.1234567Z"; ohne Zeitzone gilt UTC. */
        fun parseCapturedAt(text: String): Instant =
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (e: DateTimeParseException) {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
            }
    }
}

class GameSettingsService(private val db: Database) {
    private var cached: GameSettings? = null
    private var loaded = false

    val latest: GameSettings?
        get() {
            if (!loaded) {
                cached = load()
                loaded = true
            }
            return cached
        }

    /** Prüft und speichert neue Einstellungen (ersetzt die alten). */
    fun update(text: String): GameSettings = store(GameSettings.parse(text))

    fun store(settings: GameSettings): GameSettings {
        db.transaction {
            db.update("DELETE FROM game_settings")
            db.update("DELETE FROM game_setting_options")
            db.update("DELETE FROM game_setting_roles")
            db.update(
                "INSERT INTO game_settings (id, captured_at, lobby_code, map, players, max_players, num_impostors)" +
                    " VALUES (1, ?, ?, ?, ?, ?, ?)",
                settings.capturedAt, settings.lobbyCode, settings.map, settings.players, settings.maxPlayers,
                settings.numImpostors,
            )
            var seq = 0
            for ((kind, values) in settings.options) {
                for ((key, value) in values) {
                    db.update(
                        "INSERT INTO game_setting_options (seq, kind, key, value) VALUES (?, ?, ?, ?)",
                        seq++, kind, key, value.toString(),
                    )
                }
            }
            settings.roles.entries.forEachIndexed { index, (name, rate) ->
                db.update(
                    "INSERT INTO game_setting_roles (seq, name, count, chance) VALUES (?, ?, ?, ?)",
                    index, name, rate.count, rate.chance,
                )
            }
        }
        cached = settings
        loaded = true
        return settings
    }

    private fun load(): GameSettings? = db.transaction {
        db.queryOne("SELECT * FROM game_settings WHERE id = 1") { row ->
            val options = OPTION_KINDS.associateWithTo(LinkedHashMap()) { LinkedHashMap<String, JsonElement>() }
            db.query("SELECT kind, key, value FROM game_setting_options ORDER BY seq") {
                options.getOrPut(it.string("kind")) { LinkedHashMap() }[it.string("key")] =
                    JSON.parseToJsonElement(it.string("value"))
            }
            val roles = LinkedHashMap<String, RoleRate>()
            db.query("SELECT name, count, chance FROM game_setting_roles ORDER BY seq") {
                roles[it.string("name")] = RoleRate(it.int("count"), it.int("chance"))
            }
            GameSettings(
                capturedAt = row.instant("captured_at"),
                lobbyCode = row.string("lobby_code"),
                map = row.string("map"),
                players = row.int("players"),
                maxPlayers = row.int("max_players"),
                numImpostors = row.int("num_impostors"),
                options = options,
                roles = roles,
            )
        }
    }
}
