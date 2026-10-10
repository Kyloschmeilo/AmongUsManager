/*
 * Datenmodell einer Runde, wie die CrewStats-Mod sie schickt.
 * JSON-Format = mod/CrewStats/RoundRecord.cs (camelCase) – beide Seiten gemeinsam ändern!
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.core.JsonKeyError
import de.amongusmanager.crewbot.core.JsonValueError
import de.amongusmanager.crewbot.core.getStr
import de.amongusmanager.crewbot.core.intOrNull
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.core.pyFloat
import de.amongusmanager.crewbot.core.pyInt
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import de.amongusmanager.crewbot.core.require
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Fehler mit einer Nachricht, die direkt angezeigt werden kann. */
class ModRoundError(message: String) : Exception(message)

data class ModPlayer(
    val playerId: Int,
    val name: String,
    val friendCode: String,
    val role: String,
    val team: String, // CREW | IMPOSTOR
    val won: Boolean,
    val isDead: Boolean,
    val disconnected: Boolean,
    val deathCause: String?,
    val killedBy: Int?,
    val kills: Int,
    val tasksTotal: Int,
    val tasksCompleted: Int,
    val diedAt: Double? = null, // Sekunden seit Rundenstart
) {
    val isImpostor: Boolean get() = team == Team.IMPOSTOR.value

    companion object {
        fun fromJson(data: JsonObject): ModPlayer = ModPlayer(
            playerId = data.require("playerId").pyInt(),
            name = data.getStr("name", "?"),
            friendCode = data.getStr("friendCode", ""),
            role = data.getStr("role", ""),
            team = data.getStr("team", "CREW"),
            won = data["won"].pyTruthy(),
            isDead = data["isDead"].pyTruthy(),
            disconnected = data["disconnected"].pyTruthy(),
            deathCause = data["deathCause"].let { if (it == null || it is JsonNull) null else it.pyStr() },
            killedBy = data["killedBy"].intOrNull(),
            kills = data["kills"]?.pyInt() ?: 0,
            tasksTotal = data["tasksTotal"]?.pyInt() ?: 0,
            tasksCompleted = data["tasksCompleted"]?.pyInt() ?: 0,
            diedAt = data["diedAtSeconds"].let { if (it == null || it is JsonNull) null else it.pyFloat() },
        )
    }
}

data class ModKill(val atSeconds: Double, val killer: Int, val victim: Int) {
    companion object {
        fun fromJson(data: JsonObject): ModKill = ModKill(
            atSeconds = data["atSeconds"]?.pyFloat() ?: 0.0,
            killer = data.require("killer").pyInt(),
            victim = data.require("victim").pyInt(),
        )
    }
}

data class ModVote(val voter: Int, val target: Int?)

data class ModMeeting(
    val caller: Int,
    val body: Int?,
    val votes: List<ModVote>,
    val exiled: Int?,
    val tie: Boolean,
    val atSeconds: Double = 0.0,
) {
    companion object {
        fun fromJson(data: JsonObject): ModMeeting = ModMeeting(
            caller = data["caller"]?.pyInt() ?: 255,
            body = data["body"].intOrNull(),
            votes = data.list("votes").map { vote ->
                val obj = vote.asObject()
                ModVote(obj.require("voter").pyInt(), obj["target"].intOrNull())
            },
            exiled = data["exiled"].intOrNull(),
            tie = data["tie"].pyTruthy(),
            atSeconds = data["atSeconds"]?.pyFloat() ?: 0.0,
        )
    }
}

data class ModRound(
    val gameId: String,
    val lobbyCode: String,
    val map: String,
    val durationSeconds: Double,
    val endReason: String,
    val winningTeam: Team?,
    val players: List<ModPlayer>,
    val meetings: List<ModMeeting>,
    val kills: List<ModKill> = emptyList(), // nach Zeitpunkt sortiert
) {
    fun player(playerId: Int?): ModPlayer? = if (playerId == null) null else players.firstOrNull { it.playerId == playerId }

    companion object {
        fun parse(text: String): ModRound {
            val element = try {
                parseJson(text)
            } catch (e: SerializationException) {
                throw ModRoundError("Ungültiges JSON: ${e.message}")
            }
            return fromJson(element)
        }

        fun fromJson(element: JsonElement): ModRound {
            try {
                val data = element.asObject()
                val team = data["winningTeam"].let { if (it is JsonPrimitive && it.isString) it.content else null }
                return ModRound(
                    gameId = data.require("gameId").pyStr(),
                    lobbyCode = data.getStr("lobbyCode", ""),
                    map = data.getStr("map", "?"),
                    durationSeconds = data["durationSeconds"]?.pyFloat() ?: 0.0,
                    endReason = data.getStr("endReason", ""),
                    winningTeam = Team.fromValue(team),
                    players = data.list("players").map { ModPlayer.fromJson(it.asObject()) },
                    meetings = data.list("meetings").map { ModMeeting.fromJson(it.asObject()) },
                    kills = data.list("kills").map { ModKill.fromJson(it.asObject()) }.sortedBy { it.atSeconds },
                )
            } catch (e: JsonKeyError) {
                throw ModRoundError("Ungültige Rundendaten: ${e.message}")
            } catch (e: JsonValueError) {
                throw ModRoundError("Ungültige Rundendaten: ${e.message}")
            }
        }
    }
}

private fun JsonElement.asObject(): JsonObject =
    this as? JsonObject ?: throw JsonValueError("Objekt erwartet, bekommen: $this")

/** `data.get(key, [])` – fehlt das Feld oder ist es null, gibt es eine leere Liste. */
private fun JsonObject.list(key: String): List<JsonElement> = when (val value = this[key]) {
    null -> emptyList()
    is JsonArray -> value
    is JsonNull -> throw JsonValueError("'NoneType' object is not iterable")
    else -> throw JsonValueError("Liste erwartet bei '$key'")
}
