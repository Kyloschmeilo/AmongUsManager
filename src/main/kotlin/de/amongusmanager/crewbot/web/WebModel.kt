/*
 * Stats-Website: schlankes Modell einer Runde, wie die Website sie auswertet.
 *
 * Quelle sind die Rohdaten der CrewStats-Mod (Tabelle round_raw, Format docs/rundenformat.md im Mod-Repo,
 * Version 1 oder 2) oder – für Runden von vor dem Archiv – der Rundenverlauf des Bots (round_history).
 * Im Speicher bleibt nur, was für Ranglisten und Profile nötig ist; Laufwege und Zeitleiste liest die Website
 * erst für die Detailansicht einer Runde wieder aus der Datenbank.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.services.HistoryRound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import kotlin.math.floor

/** Kennzahlen eines Spielers, die die Mod ab Rundenformat 2 mitschickt (`players[].summary`). */
data class WebSummary(
    val distance: Double,
    val ventUses: Int,
    val ventSeconds: Double,
    val sabotages: Int,
    val sabotagesFixed: Int,
    val firstTaskSeconds: Double?,
    val allTasksSeconds: Double?,
)

data class WebPlayer(
    val playerId: Int,
    val name: String,
    /** Nur intern (Zuordnung über Runden hinweg) – geht nie an den Browser. */
    val friendCode: String,
    /** Discord-ID, wie sie beim Speichern im Rundenverlauf galt (nur alte Runden aus round_history). */
    val storedDiscordId: Long?,
    val colorId: Int?,
    val role: String,
    val modRole: String?,
    val modStartRole: String?,
    val modifiers: List<String>,
    val team: String,
    val won: Boolean,
    val isDead: Boolean,
    val disconnected: Boolean,
    val deathCause: String?,
    val killedBy: Int?,
    val diedAt: Double?,
    val kills: Int,
    val tasksTotal: Int,
    val tasksCompleted: Int,
    val isHost: Boolean,
    val summary: WebSummary?,
    /** Kosmetik der Runde (ab Rundenformat 2), null = unbekannt (alte Runden). */
    val look: CrewLook? = null,
) {
    val isCrew: Boolean get() = team == CREW
    val isImpostor: Boolean get() = team == IMPOSTOR
    val isNeutral: Boolean get() = team == NEUTRAL

    /** Gestorben (ohne Verlassen des Spiels). */
    val died: Boolean get() = isDead && deathCause != "disconnect"
}

data class WebKill(
    val atSeconds: Double,
    val killer: Int,
    val victim: Int,
    val x: Double?,
    val y: Double?,
    val room: String?,
)

data class WebVote(val voter: Int, val target: Int?, val skipped: Boolean)

data class WebMeeting(
    val atSeconds: Double,
    val caller: Int,
    val body: Int?,
    val exiled: Int?,
    val tie: Boolean,
    val votes: List<WebVote>,
    /** Wo der Auslöser stand (Rundenformat 2), sonst null. */
    val x: Double? = null,
    val y: Double? = null,
    val room: String? = null,
)

data class WebSabotage(val system: String, val atSeconds: Double, val durationSeconds: Double?, val endReason: String?)

/**
 * Wo auf der Map etwas passiert ist (für die Map-Ansicht): Rasterzellen der Laufwege mit Anzahl Punkten,
 * Mittelpunkte der Räume (aus Ereignissen mit Raum) und Vent-Positionen.
 */
class MapSample(
    val cells: IntArray,
    val counts: IntArray,
    val rooms: Map<String, DoubleArray>, // Raum -> [Summe x, Summe y, Anzahl]
    val vents: Map<Int, DoubleArray>, // Vent-Id -> [Summe x, Summe y, Anzahl]
)

class WebRound(
    val gameId: String,
    val endedAt: Instant,
    val lobbyCode: String,
    val map: String,
    /** 0 Skeld, 1 MIRA HQ, 2 Polus, 3 dlekS, 4 Airship, 5 Fungle, -1 unbekannt. */
    val mapId: Int,
    val durationSeconds: Double,
    val endReason: String,
    /** CREW, IMPOSTOR, NEUTRAL (Sondersieg mit The Other Roles) oder UNKNOWN. */
    val winningTeam: String,
    val mod: String?,
    val modGameMode: String?,
    val server: String?,
    val version: Int,
    /** Alte Runde aus dem Rundenverlauf des Bots (ohne Rohdaten, also ohne Replay). */
    val fromHistory: Boolean,
    val players: List<WebPlayer>,
    val kills: List<WebKill>,
    val meetings: List<WebMeeting>,
    val sabotages: List<WebSabotage>,
    val eventCount: Int,
    val hasTracks: Boolean,
    val mapSample: MapSample?,
    /** Wann der Bot die Runde bekommen hat (Uhr des Bots, nicht des Hosts) – Beginn des Crack-Votings. */
    val receivedAt: Instant = endedAt,
) {
    fun player(playerId: Int?): WebPlayer? = if (playerId == null) null else players.firstOrNull { it.playerId == playerId }
}

const val CREW = "CREW"
const val IMPOSTOR = "IMPOSTOR"
const val NEUTRAL = "NEUTRAL"
const val UNKNOWN = "UNKNOWN"

/** Rastergröße der Map-Ansicht in Spiel-Einheiten. */
const val MAP_CELL = 0.5

private const val CELL_OFFSET = 2048
private const val CELL_SPAN = 4096

fun packCell(x: Double, y: Double): Int =
    (floor(x / MAP_CELL).toInt() + CELL_OFFSET) * CELL_SPAN + (floor(y / MAP_CELL).toInt() + CELL_OFFSET)

fun cellX(cell: Int): Int = cell / CELL_SPAN - CELL_OFFSET
fun cellY(cell: Int): Int = cell % CELL_SPAN - CELL_OFFSET

/** Map-Namen der Mod (GameRecorder.MapName) -> Id. */
private val MAP_IDS = mapOf(
    "The Skeld" to 0, "Skeld" to 0,
    "MIRA HQ" to 1, "Mira HQ" to 1,
    "Polus" to 2,
    "dlekS ehT" to 3, "dlekS" to 3,
    "The Airship" to 4, "Airship" to 4,
    "The Fungle" to 5, "Fungle" to 5,
)

val MAP_NAMES = mapOf(0 to "The Skeld", 1 to "MIRA HQ", 2 to "Polus", 3 to "dlekS ehT", 4 to "The Airship", 5 to "The Fungle")

fun mapIdOf(name: String): Int = MAP_IDS[name.trim()] ?: Regex("""Map (\d+)""").matchEntire(name.trim())?.groupValues?.get(1)?.toInt() ?: -1

object WebRounds {
    /**
     * Liest eine Runde aus den Rohdaten der Mod. `null`, wenn sie für die Website nicht zählt
     * (Freeplay/Test) – kaputte Daten werfen eine Exception.
     */
    fun fromJson(json: String, receivedAt: Instant): WebRound? = fromJson(parseJson(json) as JsonObject, receivedAt)

    fun fromJson(data: JsonObject, receivedAt: Instant): WebRound? {
        val lobbyCode = data.str("lobbyCode") ?: ""
        if (lobbyCode.equals("FREEPLAY", ignoreCase = true) || data.str("endedBy") == "freeplay-exit") return null
        val gameId = data.str("gameId") ?: error("gameId fehlt")
        val map = data.str("map") ?: "?"
        val events = data.objects("events")
        val tracks = data.objects("tracks")

        val players = data.objects("players").map { p ->
            val summary = p["summary"] as? JsonObject
            WebPlayer(
                playerId = p.int("playerId") ?: error("playerId fehlt"),
                name = p.str("name") ?: "?",
                friendCode = p.str("friendCode") ?: "",
                storedDiscordId = null,
                colorId = p.int("colorId"),
                role = p.str("role") ?: "",
                modRole = p.str("modRole")?.takeIf { it.isNotEmpty() },
                modStartRole = p.str("modStartRole")?.takeIf { it.isNotEmpty() },
                modifiers = (p["modifiers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { s -> s.isString }?.content } ?: emptyList(),
                team = p.str("team") ?: CREW,
                won = p.bool("won") ?: false,
                isDead = p.bool("isDead") ?: false,
                disconnected = p.bool("disconnected") ?: false,
                deathCause = p.str("deathCause"),
                killedBy = p.int("killedBy"),
                diedAt = p.num("diedAtSeconds"),
                kills = p.int("kills") ?: 0,
                tasksTotal = p.int("tasksTotal") ?: 0,
                tasksCompleted = p.int("tasksCompleted") ?: 0,
                isHost = p.bool("isHost") ?: false,
                look = if ("hat" in p || "skin" in p || "visor" in p || "pet" in p) CrewLook(p.str("hat"), p.str("visor"), p.str("skin"), p.str("pet")) else null,
                summary = summary?.let {
                    WebSummary(
                        distance = it.num("distance") ?: 0.0,
                        ventUses = it.int("ventUses") ?: 0,
                        ventSeconds = it.num("ventSeconds") ?: 0.0,
                        sabotages = it.int("sabotages") ?: 0,
                        sabotagesFixed = it.int("sabotagesFixed") ?: 0,
                        firstTaskSeconds = it.num("firstTaskSeconds"),
                        allTasksSeconds = it.num("allTasksSeconds"),
                    )
                },
            )
        }

        val kills = data.objects("kills").map { k ->
            WebKill(
                atSeconds = k.num("atSeconds") ?: 0.0,
                killer = k.int("killer") ?: -1,
                victim = k.int("victim") ?: -1,
                x = k.num("x"),
                y = k.num("y"),
                room = k.str("room"),
            )
        }.sortedBy { it.atSeconds }

        val meetings = data.objects("meetings").map { m ->
            WebMeeting(
                atSeconds = m.num("atSeconds") ?: 0.0,
                caller = m.int("caller") ?: 255,
                body = m.int("body"),
                exiled = m.int("exiled"),
                tie = m.bool("tie") ?: false,
                votes = m.objects("votes").map { v ->
                    val target = v.int("target")
                    // Format 1 kennt kein `skipped`: ohne Ziel zählte die Stimme dort als „übersprungen“
                    WebVote(v.int("voter") ?: -1, target, target == null && (v.bool("skipped") ?: true))
                },
                x = m.num("callerX"),
                y = m.num("callerY"),
                room = m.str("callerRoom"),
            )
        }.sortedBy { it.atSeconds }

        val sabotages = ArrayList<WebSabotage>()
        val open = HashMap<String, Pair<Int, Double>>() // System -> (Index in sabotages, Beginn)
        for (e in events) {
            val system = e.str("system") ?: continue
            when (e.str("type")) {
                "sabotage" -> {
                    open[system] = sabotages.size to (e.num("atSeconds") ?: 0.0)
                    sabotages += WebSabotage(system, e.num("atSeconds") ?: 0.0, null, null)
                }
                "sabotageEnd" -> open.remove(system)?.let { (index, start) ->
                    val duration = e.num("durationSeconds") ?: ((e.num("atSeconds") ?: start) - start)
                    sabotages[index] = sabotages[index].copy(durationSeconds = duration, endReason = e.str("reason"))
                }
            }
        }

        return WebRound(
            gameId = gameId,
            endedAt = data.str("endedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: receivedAt,
            lobbyCode = lobbyCode,
            map = map,
            mapId = data.int("mapId") ?: mapIdOf(map),
            durationSeconds = data.num("durationSeconds") ?: 0.0,
            endReason = data.str("endReason") ?: "",
            winningTeam = data.str("winningTeam")?.takeIf { it in setOf(CREW, IMPOSTOR, NEUTRAL) } ?: UNKNOWN,
            mod = data.str("mod")?.takeIf { it.isNotEmpty() },
            modGameMode = data.str("modGameMode")?.takeIf { it.isNotEmpty() },
            server = data.str("server")?.takeIf { it.isNotEmpty() },
            version = data.int("version") ?: 1,
            fromHistory = false,
            players = players,
            kills = kills,
            meetings = meetings,
            sabotages = sabotages,
            eventCount = events.size,
            hasTracks = tracks.any { ((it["points"] as? JsonArray)?.size ?: 0) >= 6 },
            mapSample = sampleMap(events, tracks, kills),
            receivedAt = receivedAt,
        )
    }

    /** Alte Runde aus round_history (Format 1 ohne Positionen; Discord-Zuordnung von damals). */
    fun fromHistory(history: HistoryRound): WebRound {
        val round = history.round
        return WebRound(
            gameId = round.gameId,
            endedAt = history.endedAt,
            lobbyCode = round.lobbyCode,
            map = round.map,
            mapId = mapIdOf(round.map),
            durationSeconds = round.durationSeconds,
            endReason = round.endReason,
            winningTeam = round.winningTeam?.value ?: if (round.neutralWin) NEUTRAL else UNKNOWN,
            mod = round.mod,
            modGameMode = null,
            server = null,
            version = 1,
            fromHistory = true,
            players = round.players.map { p ->
                WebPlayer(
                    playerId = p.playerId, name = p.name, friendCode = p.friendCode,
                    storedDiscordId = history.discordIds[p.playerId], colorId = null, role = p.role,
                    modRole = p.modRole, modStartRole = p.modStartRole, modifiers = p.modifiers, team = p.team,
                    won = p.won, isDead = p.isDead, disconnected = p.disconnected, deathCause = p.deathCause,
                    killedBy = p.killedBy, diedAt = p.diedAt, kills = p.kills, tasksTotal = p.tasksTotal,
                    tasksCompleted = p.tasksCompleted, isHost = false, summary = null,
                )
            },
            kills = round.kills.map { WebKill(it.atSeconds, it.killer, it.victim, null, null, null) },
            meetings = round.meetings.map { m ->
                WebMeeting(m.atSeconds, m.caller, m.body, m.exiled, m.tie, m.votes.map { WebVote(it.voter, it.target, it.target == null) })
            },
            sabotages = emptyList(),
            eventCount = 0,
            hasTracks = false,
            mapSample = null,
        )
    }

    private fun sampleMap(events: List<JsonObject>, tracks: List<JsonObject>, kills: List<WebKill>): MapSample? {
        val cells = HashMap<Int, Int>()
        val rooms = HashMap<String, DoubleArray>()
        val vents = HashMap<Int, DoubleArray>()
        fun add(x: Double, y: Double, weight: Int = 1) {
            if (!x.isFinite() || !y.isFinite() || x !in -400.0..400.0 || y !in -400.0..400.0) return
            cells.merge(packCell(x, y), weight, Int::plus)
        }
        fun room(name: String?, x: Double?, y: Double?) {
            if (name == null || x == null || y == null) return
            val sum = rooms.getOrPut(name) { DoubleArray(3) }
            sum[0] += x; sum[1] += y; sum[2] += 1.0
        }
        for (track in tracks) {
            val points = track["points"] as? JsonArray ?: continue
            var i = 0
            while (i + 2 < points.size) {
                val x = (points[i + 1] as? JsonPrimitive)?.doubleOrNull
                val y = (points[i + 2] as? JsonPrimitive)?.doubleOrNull
                if (x != null && y != null) add(x, y)
                i += 3
            }
        }
        for (e in events) {
            val x = e.num("x") ?: continue
            val y = e.num("y") ?: continue
            add(x, y)
            room(e.str("room"), x, y)
            if (e.str("type") == "ventEnter" || e.str("type") == "ventExit") {
                val vent = e.int("vent") ?: continue
                val sum = vents.getOrPut(vent) { DoubleArray(3) }
                sum[0] += x; sum[1] += y; sum[2] += 1.0
            }
        }
        for (kill in kills) {
            if (kill.x != null && kill.y != null) add(kill.x, kill.y)
        }
        if (cells.isEmpty()) return null
        val keys = cells.keys.toIntArray()
        return MapSample(keys, IntArray(keys.size) { cells.getValue(keys[it]) }, rooms, vents)
    }
}

// ---------- JSON lesen (tolerant: falscher Typ = fehlt) ----------

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

internal fun JsonObject.int(key: String): Int? = num(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
