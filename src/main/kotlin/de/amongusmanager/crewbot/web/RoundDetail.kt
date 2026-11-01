/*
 * Stats-Website: Detailansicht einer Runde = Rohdaten der Mod (mit Zeitleiste und Laufwegen),
 * aber ohne Friendcodes/PUIDs. Jeder Spieler bekommt seinen Schlüssel fürs Profil und lesbare Rollen-Namen.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.content.torRoleName
import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object RoundDetail {
    /** Felder, die nie an den Browser gehen. */
    private val SECRET_FIELDS = setOf("friendCode", "puid")

    fun fromRaw(json: String, view: RoundView, snapshot: StatsSnapshot): ByteArray {
        val data = parseJson(json) as? JsonObject ?: error("Runde ist kein JSON-Objekt")
        val result = LinkedHashMap<String, JsonElement>(data)
        result["players"] = JsonArray((data["players"] as? JsonArray ?: JsonArray(emptyList())).map { element ->
            val p = element as? JsonObject ?: return@map element
            val player = view.round.player(p.int("playerId"))
            val key = view.keyOf(p.int("playerId"))
            val fields = LinkedHashMap<String, JsonElement>()
            for ((name, value) in p) if (name !in SECRET_FIELDS) fields[name] = value
            fields["key"] = key.toJson()
            if (p.int("colorId") == null) fields["colorId"] = snapshot.colorOf(key).toJson()
            fields["roleName"] = player?.let { roleName(roleIdOf(it)) }.toJson()
            fields["modRoleName"] = player?.modRole?.let { torRoleName(it) }.toJson()
            JsonObject(fields)
        })
        result["web"] = obj("legacy" to false)
        return bytes(JsonObject(result))
    }

    /** Alte Runde aus dem Rundenverlauf (Format 1, ohne Positionen). */
    fun fromHistory(view: RoundView, snapshot: StatsSnapshot): ByteArray {
        val r = view.round
        return bytes(
            obj(
                "version" to 1,
                "gameId" to r.gameId,
                "lobbyCode" to r.lobbyCode,
                "map" to r.map,
                "mapId" to r.mapId,
                "endedAt" to r.endedAt,
                "durationSeconds" to r.durationSeconds,
                "endReason" to r.endReason,
                "winningTeam" to r.winningTeam,
                "mod" to r.mod,
                "players" to r.players.mapIndexed { i, p ->
                    obj(
                        "playerId" to p.playerId, "key" to view.keys[i], "name" to p.name,
                        "colorId" to snapshot.colorOf(view.keys[i]), "role" to p.role, "team" to p.team,
                        "modRole" to p.modRole, "modStartRole" to p.modStartRole, "modifiers" to p.modifiers,
                        "won" to p.won, "isDead" to p.isDead, "disconnected" to p.disconnected,
                        "deathCause" to p.deathCause, "killedBy" to p.killedBy, "diedAtSeconds" to p.diedAt,
                        "kills" to p.kills, "tasksTotal" to p.tasksTotal, "tasksCompleted" to p.tasksCompleted,
                        "roleName" to roleName(roleIdOf(p)), "modRoleName" to p.modRole?.let { torRoleName(it) },
                    )
                },
                "kills" to r.kills.map { obj("atSeconds" to it.atSeconds, "killer" to it.killer, "victim" to it.victim) },
                "meetings" to r.meetings.map { m ->
                    obj(
                        "atSeconds" to m.atSeconds, "caller" to m.caller, "body" to m.body, "exiled" to m.exiled, "tie" to m.tie,
                        "votes" to m.votes.map { obj("voter" to it.voter, "target" to it.target, "skipped" to it.skipped) },
                    )
                },
                "web" to obj("legacy" to true),
            ),
        )
    }
}
