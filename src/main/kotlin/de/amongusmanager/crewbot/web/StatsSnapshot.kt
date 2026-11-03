/*
 * Stats-Website: alle Auswertungen über alle Runden (Bestenliste, Profile, Rollen, Maps, Rekorde).
 *
 * Ein Snapshot ist unveränderlich und wird nach jeder Änderung der Daten komplett neu berechnet
 * (StatsStore, höchstens einmal pro Minute) – Anfragen lesen nur fertige Ergebnisse.
 * Spieler werden über Discord (Verknüpfung per /verknuepfen) bzw. ihren Friendcode erkannt; nach außen gehen
 * nur gehashte Schlüssel, nie Friendcodes oder Discord-IDs.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.TIMEZONE
import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.content.SpecialTeam
import de.amongusmanager.crewbot.content.specialRoleFor
import de.amongusmanager.crewbot.content.torRoleName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class BotLevel(val level: Int, val xp: Int)

/** So viele letzte Runden zeigt ein Profil (alle weiteren über /api/rounds?player=…). */
const val PROFILE_ROUNDS = 12

/** Eine Runde mit den Schlüsseln ihrer Spieler (gleiche Reihenfolge wie `round.players`). */
class RoundView(val round: WebRound, val keys: List<String>) {
    fun keyOf(playerId: Int?): String? {
        if (playerId == null) return null
        val index = round.players.indexOfFirst { it.playerId == playerId }
        return if (index < 0) null else keys[index]
    }
}

private class RoleAcc(val id: String) {
    val teams = HashMap<String, Int>()
    var games = 0
    var wins = 0
    var kills = 0
    var survived = 0
}

private class PlayerAcc(val key: String) {
    var discordId: Long? = null
    val names = LinkedHashMap<String, Int>() // neuester Name zuerst
    val colors = HashMap<Int, Int>()
    var games = 0
    var wins = 0
    var crewGames = 0
    var crewWins = 0
    var impostorGames = 0
    var impostorWins = 0
    var neutralGames = 0
    var neutralWins = 0
    var kills = 0
    var impostorKills = 0
    var firstKills = 0
    var deaths = 0
    var killed = 0
    var ejected = 0
    var survived = 0
    var disconnects = 0
    var tasksDone = 0
    var tasksTotal = 0
    var meetingsCalled = 0
    var bodiesReported = 0
    var votes = 0
    var skips = 0
    var crewVotes = 0
    var correctVotes = 0
    var distance = 0.0
    var detailGames = 0 // Runden mit Kennzahlen (Format 2)
    var ventUses = 0
    var sabotages = 0
    var sabotagesFixed = 0
    var playSeconds = 0.0
    var firstSeen: Instant? = null
    var lastSeen: Instant? = null
    val roles = HashMap<String, RoleAcc>()
    val maps = HashMap<Int, IntArray>() // games, wins
    val victims = HashMap<String, Int>()
    val killers = HashMap<String, Int>()
    val partners = HashMap<String, IntArray>() // Impostor-Partner: games, wins
    val deathCauses = HashMap<String, Int>()
    val rounds = ArrayList<Pair<RoundView, Int>>() // neueste zuerst, Index des Spielers

    var decoration: PlayerDecoration? = null

    /** Neuester Ingame-Name. */
    val name: String get() = names.keys.firstOrNull() ?: "?"

    /** Name auf der Website: selbst gewählter Anzeigename, sonst der neueste Ingame-Name. */
    val shownName: String get() = decoration?.displayName?.takeIf { it.isNotBlank() } ?: name
    val colorId: Int? get() = colors.maxByOrNull { it.value }?.key

    /** Discord-Profilbild (über die Website ausgeliefert), null = farbiger Crewmate. */
    val avatar: String? get() = decoration?.avatarVersion?.let { "/api/avatar/$key?v=$it" }
}

private class MapAcc(val mapId: Int, val name: String) {
    var rounds = 0
    var crewWins = 0
    var impostorWins = 0
    var neutralWins = 0
    var durationSum = 0.0
    var kills = 0
    var detailRounds = 0
    val cells = HashMap<Int, Int>()
    val rooms = HashMap<String, DoubleArray>()
    val vents = HashMap<Int, DoubleArray>()
    val killPoints = ArrayList<WebKill>()
    val roomKills = HashMap<String, Int>()
    val sabotages = HashMap<String, DoubleArray>() // Anzahl, Summe Dauer, Anzahl mit Dauer
}

/** Rekord aus einer einzelnen Runde. */
private data class Record(val id: String, val value: Double, val playerKey: String?, val view: RoundView)

class StatsSnapshot private constructor(
    val version: Long,
    val builtAt: Instant,
    /** Neueste Runde zuerst. */
    val views: List<RoundView>,
    private val players: Map<String, PlayerAcc>,
    private val maps: Map<Int, MapAcc>,
    private val roles: Map<String, RoleAcc>,
    private val modifiers: Map<String, RoleAcc>,
    private val records: List<Record>,
    private val levels: Map<Long, BotLevel>,
) {
    private val byGameId = views.associateBy { it.round.gameId }
    private val profileCache = ConcurrentHashMap<String, ByteArray>()
    private val mapCache = ConcurrentHashMap<Int, ByteArray>()

    val roundCount: Int get() = views.size

    fun view(gameId: String): RoundView? = byGameId[gameId]

    fun hasPlayer(key: String): Boolean = key in players

    /** Discord-ID eines verknüpften Spielers (nur intern, geht nie an den Browser). */
    fun discordIdOf(key: String): Long? = players[key]?.discordId

    /** Version des gezeigten Profilbilds, null = keins. */
    fun avatarVersionOf(key: String): String? = players[key]?.decoration?.avatarVersion

    /** Adresse des gezeigten Profilbilds, null = keins. */
    fun avatarUrlOf(key: String): String? = players[key]?.avatar

    /** Alle verknüpften Spieler (für Namen und Profilbilder aus Discord). */
    fun linkedDiscordIds(): Set<Long> = players.values.mapNotNullTo(LinkedHashSet()) { it.discordId }

    /** Kurzfassung eines Spielers wie in der Bestenliste, null = unbekannt. */
    fun summaryOf(key: String): JsonObject? = players[key]?.let { playerSummary(it) }

    /** Was ein Spieler in seinem Profil auswählen darf. */
    fun profileOptions(key: String, discord: DiscordUser?): ProfileOptions {
        val player = players[key]
        val names = (player?.names?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()).take(10) + (discord?.names ?: emptyList())
        return ProfileOptions(
            names = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            roles = roles.keys.associateWith { roleName(it) }.entries.sortedBy { it.value.lowercase() }.associate { it.key to it.value },
            maps = MAP_NAMES.toSortedMap(),
        )
    }

    /** Häufigste Farbe eines Spielers (für alte Runden ohne Farbe). */
    fun colorOf(key: String?): Int? = key?.let { players[it]?.colorId }

    // ---------- Antworten ----------

    val overviewJson: ByteArray by lazy { bytes(overview()) }
    val playersJson: ByteArray by lazy { bytes(obj("players" to players.values.sortedByDescending { it.games }.map { playerSummary(it) })) }
    val rolesJson: ByteArray by lazy { bytes(rolesResponse()) }
    val balanceJson: ByteArray by lazy { bytes(roleBalance(views)) }
    val mapsJson: ByteArray by lazy { bytes(obj("maps" to maps.values.sortedBy { it.mapId }.map { mapSummary(it) })) }

    fun playerJson(key: String): ByteArray? {
        val player = players[key] ?: return null
        return profileCache.getOrPut(key) { bytes(profile(player)) }
    }

    fun mapJson(mapId: Int): ByteArray? {
        val map = maps[mapId] ?: return null
        return mapCache.getOrPut(mapId) { bytes(mapDetail(map)) }
    }

    /** Rundenliste (neueste zuerst), optional nur eine Map, ein Spieler oder ein Sieger-Team. */
    fun roundsJson(offset: Int, limit: Int, mapId: Int?, playerKey: String?, team: String?): ByteArray {
        val filtered = views.filter { view ->
            (mapId == null || view.round.mapId == mapId) &&
                (playerKey == null || playerKey in view.keys) &&
                (team == null || view.round.winningTeam == team)
        }
        val page = filtered.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 100))
        return bytes(obj("total" to filtered.size, "offset" to offset, "items" to page.map { roundItem(it) }))
    }

    private fun overview(): JsonObject {
        val rounds = views.map { it.round }
        val teams = linkedMapOf(CREW to 0, IMPOSTOR to 0, NEUTRAL to 0, UNKNOWN to 0)
        rounds.forEach { teams.merge(it.winningTeam, 1, Int::plus) }
        val reasons = rounds.groupingBy { it.endReason.ifEmpty { "?" } }.eachCount().entries.sortedByDescending { it.value }
        val activity = views.groupingBy { gameNight(it.round.endedAt) }.eachCount().toSortedMap().entries.toList().takeLast(90)
        val meetings = rounds.sumOf { it.meetings.size }
        return obj(
            "updatedAt" to builtAt,
            "rounds" to rounds.size,
            "replays" to rounds.count { it.hasTracks },
            "players" to players.size,
            "firstRoundAt" to rounds.lastOrNull()?.endedAt,
            "lastRoundAt" to rounds.firstOrNull()?.endedAt,
            "totals" to obj(
                "kills" to rounds.sumOf { r -> r.players.sumOf { it.kills } },
                "deaths" to rounds.sumOf { r -> r.players.count { it.died } },
                "meetings" to meetings,
                "emergencies" to rounds.sumOf { r -> r.meetings.count { it.body == null } },
                "reports" to rounds.sumOf { r -> r.meetings.count { it.body != null } },
                "ejections" to rounds.sumOf { r -> r.meetings.count { it.exiled != null } },
                "votes" to rounds.sumOf { r -> r.meetings.sumOf { m -> m.votes.count { it.target != null } } },
                "skips" to rounds.sumOf { r -> r.meetings.sumOf { m -> m.votes.count { it.skipped } } },
                "tasks" to rounds.sumOf { r -> r.players.sumOf { it.tasksCompleted } },
                "sabotages" to rounds.sumOf { it.sabotages.size },
                "playSeconds" to rounds.sumOf { it.durationSeconds },
                "distance" to rounds.sumOf { r -> r.players.sumOf { it.summary?.distance ?: 0.0 } },
                "avgDuration" to if (rounds.isEmpty()) 0.0 else rounds.sumOf { it.durationSeconds } / rounds.size,
            ),
            "teams" to teams,
            "endReasons" to reasons.map { obj("reason" to it.key, "count" to it.value) },
            "maps" to maps.values.sortedByDescending { it.rounds }.map { mapSummary(it) },
            "activity" to activity.map { obj("date" to it.key, "rounds" to it.value) },
            "records" to records.map { record ->
                obj(
                    "id" to record.id,
                    "value" to record.value,
                    "player" to record.playerKey?.let { ref(it) },
                    "round" to roundRef(record.view),
                )
            },
            "recent" to views.take(8).map { roundItem(it) },
        )
    }

    private fun ref(key: String): JsonObject? {
        val player = players[key] ?: return null
        return obj("key" to key, "name" to player.shownName, "colorId" to player.colorId, "avatar" to player.avatar)
    }

    private fun roundRef(view: RoundView): JsonObject = obj(
        "gameId" to view.round.gameId,
        "map" to view.round.map,
        "mapId" to view.round.mapId,
        "endedAt" to view.round.endedAt,
    )

    private fun roundItem(view: RoundView): JsonObject {
        val r = view.round
        return obj(
            "gameId" to r.gameId,
            "endedAt" to r.endedAt,
            "map" to r.map,
            "mapId" to r.mapId,
            "duration" to r.durationSeconds,
            "winningTeam" to r.winningTeam,
            "endReason" to r.endReason,
            "mod" to r.mod,
            "kills" to r.kills.size,
            "meetings" to r.meetings.size,
            "replay" to r.hasTracks,
            "legacy" to r.fromHistory,
            "roster" to r.players.mapIndexed { i, p ->
                obj("key" to view.keys[i], "name" to p.name, "colorId" to (p.colorId ?: players[view.keys[i]]?.colorId), "team" to p.team, "won" to p.won)
            },
        )
    }

    private fun playerSummary(p: PlayerAcc): JsonObject {
        val level = p.discordId?.let { levels[it] }
        val custom = p.decoration?.profile
        return obj(
            "key" to p.key,
            "name" to p.shownName,
            "colorId" to p.colorId,
            "avatar" to p.avatar,
            "linked" to (p.discordId != null),
            "level" to level?.level,
            "games" to p.games,
            "wins" to p.wins,
            "crewGames" to p.crewGames,
            "crewWins" to p.crewWins,
            "impostorGames" to p.impostorGames,
            "impostorWins" to p.impostorWins,
            "neutralGames" to p.neutralGames,
            "neutralWins" to p.neutralWins,
            "kills" to p.kills,
            "impostorKills" to p.impostorKills,
            "firstKills" to p.firstKills,
            "deaths" to p.deaths,
            "killed" to p.killed,
            "ejected" to p.ejected,
            "survived" to p.survived,
            "disconnects" to p.disconnects,
            "tasksDone" to p.tasksDone,
            "tasksTotal" to p.tasksTotal,
            "meetingsCalled" to p.meetingsCalled,
            "bodiesReported" to p.bodiesReported,
            "votes" to p.votes,
            "skips" to p.skips,
            "crewVotes" to p.crewVotes,
            "correctVotes" to p.correctVotes,
            "distance" to p.distance,
            "detailGames" to p.detailGames,
            "ventUses" to p.ventUses,
            "sabotages" to p.sabotages,
            "sabotagesFixed" to p.sabotagesFixed,
            "playSeconds" to p.playSeconds,
            "firstSeen" to p.firstSeen,
            "lastSeen" to p.lastSeen,
            "favoriteRole" to (custom?.favoriteRole?.let { roleName(it) }
                ?: p.roles.values.filter { !isPlainRole(it.id) }.maxByOrNull { it.games }?.let { roleName(it.id) }),
        )
    }

    private fun profile(p: PlayerAcc): JsonObject {
        fun top(counts: Map<String, Int>) = counts.entries.sortedByDescending { it.value }.take(5)
            .mapNotNull { (key, count) -> ref(key)?.let { obj("player" to it, "count" to count) } }
        val custom = p.decoration?.profile
        return obj(
            "player" to playerSummary(p),
            "ingameName" to p.name,
            "custom" to custom?.let { c ->
                obj(
                    "bio" to c.bio.takeIf { it.isNotBlank() },
                    "colorId" to c.colorId,
                    "favoriteRole" to c.favoriteRole?.let { obj("id" to it, "name" to roleName(it)) },
                    "favoriteMap" to c.favoriteMap?.let { obj("mapId" to it, "map" to (MAP_NAMES[it] ?: maps[it]?.name ?: "?")) },
                    "showcase" to c.showcase,
                )
            },
            "names" to p.names.keys.toList(),
            "roles" to p.roles.values.sortedByDescending { it.games }.map { roleRow(it) },
            "maps" to p.maps.entries.sortedByDescending { it.value[0] }
                .map { (mapId, v) -> obj("mapId" to mapId, "map" to (maps[mapId]?.name ?: "?"), "games" to v[0], "wins" to v[1]) },
            "victims" to top(p.victims),
            "killers" to top(p.killers),
            "partners" to p.partners.entries.sortedByDescending { it.value[0] }.take(5).mapNotNull { (key, v) ->
                ref(key)?.let { obj("player" to it, "games" to v[0], "wins" to v[1]) }
            },
            "deathCauses" to p.deathCauses.entries.sortedByDescending { it.value }.map { obj("cause" to it.key, "count" to it.value) },
            "form" to p.rounds.take(20).map { (view, index) -> view.round.players[index].won },
            "rounds" to p.rounds.take(PROFILE_ROUNDS).map { (view, index) ->
                val me = view.round.players[index]
                obj(
                    "round" to roundItem(view),
                    "me" to obj(
                        "role" to roleName(roleIdOf(me)),
                        "team" to me.team,
                        "won" to me.won,
                        "kills" to me.kills,
                        "dead" to me.isDead,
                        "deathCause" to me.deathCause,
                        "tasksDone" to me.tasksCompleted,
                        "tasksTotal" to me.tasksTotal,
                    ),
                )
            },
        )
    }

    private fun roleRow(role: RoleAcc): JsonObject = obj(
        "id" to role.id,
        "name" to roleName(role.id),
        "team" to role.teams.maxByOrNull { it.value }?.key,
        "mod" to role.id.startsWith("tor:"),
        "games" to role.games,
        "wins" to role.wins,
        "kills" to role.kills,
        "survived" to role.survived,
    )

    private fun rolesResponse(): JsonObject = obj(
        "roles" to roles.values.sortedByDescending { it.games }.map { role ->
            val row = roleRow(role)
            JsonObject(row + ("description" to roleDescription(role.id).toJson()))
        },
        "modifiers" to modifiers.values.sortedByDescending { it.games }.map { m ->
            obj("id" to m.id, "name" to (torRoleName(m.id) ?: m.id), "games" to m.games, "wins" to m.wins, "description" to specialRoleFor(m.id)?.description)
        },
    )

    private fun mapSummary(m: MapAcc): JsonObject = obj(
        "mapId" to m.mapId,
        "map" to m.name,
        "rounds" to m.rounds,
        "crewWins" to m.crewWins,
        "impostorWins" to m.impostorWins,
        "neutralWins" to m.neutralWins,
        "avgDuration" to if (m.rounds == 0) 0.0 else m.durationSum / m.rounds,
        "kills" to m.kills,
        "layout" to m.cells.isNotEmpty(),
    )

    private fun mapDetail(m: MapAcc): JsonObject {
        val cells = ArrayList<Int>(m.cells.size * 3)
        for ((cell, count) in m.cells) {
            cells += cellX(cell); cells += cellY(cell); cells += count
        }
        return obj(
            "map" to mapSummary(m),
            "cellSize" to MAP_CELL,
            "cells" to cells,
            "rooms" to m.rooms.entries.filter { it.value[2] >= 2 }.map { (room, sum) ->
                obj("room" to room, "x" to sum[0] / sum[2], "y" to sum[1] / sum[2], "kills" to (m.roomKills[room] ?: 0))
            },
            "vents" to m.vents.entries.map { (id, sum) -> obj("id" to id, "x" to sum[0] / sum[2], "y" to sum[1] / sum[2]) },
            "kills" to m.killPoints.takeLast(3000).map { obj("x" to it.x, "y" to it.y, "room" to it.room, "at" to it.atSeconds) },
            "roomKills" to m.roomKills.entries.sortedByDescending { it.value }.map { obj("room" to it.key, "count" to it.value) },
            "sabotages" to m.sabotages.entries.sortedByDescending { it.value[0] }.map { (system, v) ->
                obj("system" to system, "count" to v[0].toInt(), "avgDuration" to if (v[2] > 0) v[1] / v[2] else null)
            },
        )
    }

    companion object {
        private val versions = AtomicLong()

        fun empty(now: Instant): StatsSnapshot = build(emptyList(), emptyMap(), emptyMap(), now)

        /** Wertet alle [rounds] aus. [links]: Friendcode -> Discord-ID, [levels]: Discord-ID -> Level im Bot. */
        fun build(
            rounds: List<WebRound>,
            links: Map<String, Long>,
            levels: Map<Long, BotLevel>,
            now: Instant,
            decorations: Map<Long, PlayerDecoration> = emptyMap(),
        ): StatsSnapshot {
            val sorted = rounds.sortedByDescending { it.endedAt }
            val players = LinkedHashMap<String, PlayerAcc>()
            val maps = HashMap<Int, MapAcc>()
            val roles = HashMap<String, RoleAcc>()
            val modifiers = HashMap<String, RoleAcc>()
            val best = HashMap<String, Record>()

            fun record(id: String, value: Double, key: String?, view: RoundView, lowerIsBetter: Boolean = false) {
                val current = best[id]
                // gleicher Wert: die ältere Runde behält den Rekord (Runden kommen neueste zuerst)
                val better = current == null || if (lowerIsBetter) value <= current.value else value >= current.value
                if (better) best[id] = Record(id, value, key, view)
            }

            val views = sorted.map { round ->
                val keys = round.players.map { p ->
                    val discord = p.storedDiscordId ?: p.friendCode.takeIf { it.isNotBlank() }?.let { links[it] }
                    val identity = when {
                        discord != null -> "d:$discord"
                        p.friendCode.isNotBlank() -> "f:${p.friendCode.lowercase()}"
                        else -> "n:${p.name.lowercase()}"
                    }
                    val key = shortHash("crewstats-web:$identity")
                    players.getOrPut(key) { PlayerAcc(key) }.also { if (discord != null) it.discordId = discord }
                    key
                }
                RoundView(round, keys)
            }
            for (acc in players.values) acc.decoration = acc.discordId?.let { decorations[it] }

            for (view in views) {
                val r = view.round
                val firstKill = r.kills.firstOrNull()
                val map = maps.getOrPut(r.mapId) { MapAcc(r.mapId, MAP_NAMES[r.mapId] ?: r.map) }
                map.rounds++
                when (r.winningTeam) {
                    CREW -> map.crewWins++
                    IMPOSTOR -> map.impostorWins++
                    NEUTRAL -> map.neutralWins++
                }
                map.durationSum += r.durationSeconds
                map.kills += r.kills.size
                r.kills.forEach { kill ->
                    if (kill.x != null && kill.y != null) map.killPoints += kill
                    kill.room?.let { map.roomKills.merge(it, 1, Int::plus) }
                }
                r.sabotages.forEach { s ->
                    val v = map.sabotages.getOrPut(s.system) { DoubleArray(3) }
                    v[0] += 1.0
                    s.durationSeconds?.let { v[1] += it; v[2] += 1.0 }
                }
                r.mapSample?.let { sample ->
                    map.detailRounds++
                    for (i in sample.cells.indices) map.cells.merge(sample.cells[i], sample.counts[i], Int::plus)
                    for ((room, sum) in sample.rooms) map.rooms.getOrPut(room) { DoubleArray(3) }.let { for (i in 0..2) it[i] += sum[i] }
                    for ((vent, sum) in sample.vents) map.vents.getOrPut(vent) { DoubleArray(3) }.let { for (i in 0..2) it[i] += sum[i] }
                }

                if (r.durationSeconds > 0 && r.winningTeam != UNKNOWN) {
                    record("longestRound", r.durationSeconds, null, view)
                    record("shortestRound", r.durationSeconds, null, view, lowerIsBetter = true)
                }
                if (firstKill != null) record("fastestKill", firstKill.atSeconds, view.keyOf(firstKill.killer), view, lowerIsBetter = true)
                if (r.meetings.isNotEmpty()) record("mostMeetings", r.meetings.size.toDouble(), null, view)

                r.players.forEachIndexed { index, p ->
                    val key = view.keys[index]
                    val acc = players.getValue(key)
                    acc.rounds += view to index
                    acc.names.merge(p.name, 1, Int::plus)
                    p.colorId?.let { acc.colors.merge(it, 1, Int::plus) }
                    acc.games++
                    if (p.won) acc.wins++
                    when {
                        p.isImpostor -> { acc.impostorGames++; if (p.won) acc.impostorWins++; acc.impostorKills += p.kills }
                        p.isNeutral -> { acc.neutralGames++; if (p.won) acc.neutralWins++ }
                        else -> { acc.crewGames++; if (p.won) acc.crewWins++ }
                    }
                    acc.kills += p.kills
                    if (firstKill != null && firstKill.killer == p.playerId) acc.firstKills++
                    if (p.died) {
                        acc.deaths++
                        p.deathCause?.let { acc.deathCauses.merge(it, 1, Int::plus) }
                        if (p.deathCause == "ejected") acc.ejected++
                    }
                    if (p.disconnected || p.deathCause == "disconnect") acc.disconnects++
                    if (!p.isDead && !p.disconnected) acc.survived++
                    if (p.isCrew) {
                        acc.tasksDone += p.tasksCompleted
                        acc.tasksTotal += p.tasksTotal
                    }
                    acc.playSeconds += r.durationSeconds
                    if (acc.lastSeen == null) acc.lastSeen = r.endedAt
                    acc.firstSeen = r.endedAt
                    p.summary?.let { s ->
                        acc.detailGames++
                        acc.distance += s.distance
                        acc.ventUses += s.ventUses
                        acc.sabotages += s.sabotages
                        acc.sabotagesFixed += s.sabotagesFixed
                        record("mostDistance", s.distance, key, view)
                        if (s.allTasksSeconds != null && p.isCrew && p.tasksTotal >= 3) {
                            record("fastestTasks", s.allTasksSeconds, key, view, lowerIsBetter = true)
                        }
                    }
                    if (p.kills > 0) record("mostKills", p.kills.toDouble(), key, view)
                    r.mapIdStats(acc, p)

                    val roleId = roleIdOf(p)
                    for (target in listOf(roles.getOrPut(roleId) { RoleAcc(roleId) }, acc.roles.getOrPut(roleId) { RoleAcc(roleId) })) {
                        target.games++
                        if (p.won) target.wins++
                        target.kills += p.kills
                        if (!p.isDead && !p.disconnected) target.survived++
                        target.teams.merge(p.team, 1, Int::plus)
                    }
                    for (modifier in p.modifiers.distinct()) {
                        val m = modifiers.getOrPut(modifier) { RoleAcc(modifier) }
                        m.games++
                        if (p.won) m.wins++
                    }
                    if (p.isImpostor) {
                        r.players.forEachIndexed { otherIndex, other ->
                            if (otherIndex != index && other.isImpostor) {
                                acc.partners.getOrPut(view.keys[otherIndex]) { IntArray(2) }.let { it[0]++; if (p.won) it[1]++ }
                            }
                        }
                    }
                }

                for (kill in r.kills) {
                    val killer = view.keyOf(kill.killer) ?: continue
                    val victim = view.keyOf(kill.victim) ?: continue
                    if (killer == victim) continue
                    players.getValue(killer).victims.merge(victim, 1, Int::plus)
                    players.getValue(victim).killers.merge(killer, 1, Int::plus)
                    players.getValue(victim).killed++
                }

                for (meeting in r.meetings) {
                    view.keyOf(meeting.caller)?.let { key ->
                        val acc = players.getValue(key)
                        if (meeting.body == null) acc.meetingsCalled++ else acc.bodiesReported++
                    }
                    for (vote in meeting.votes) {
                        val voterKey = view.keyOf(vote.voter) ?: continue
                        val acc = players.getValue(voterKey)
                        val voter = r.player(vote.voter) ?: continue
                        if (vote.target == null) {
                            if (vote.skipped) acc.skips++
                            continue
                        }
                        acc.votes++
                        val target = r.player(vote.target) ?: continue
                        if (voter.isCrew && !target.isNeutral) {
                            acc.crewVotes++
                            if (target.isImpostor) acc.correctVotes++
                        }
                    }
                }
            }

            return StatsSnapshot(
                version = versions.incrementAndGet(),
                builtAt = now,
                views = views,
                players = players,
                maps = maps,
                roles = roles,
                modifiers = modifiers,
                records = best.values.sortedBy { RECORD_ORDER.indexOf(it.id) },
                levels = levels,
            )
        }

        private val RECORD_ORDER = listOf("mostKills", "fastestKill", "fastestTasks", "mostDistance", "longestRound", "shortestRound", "mostMeetings")

        private fun WebRound.mapIdStats(acc: PlayerAcc, p: WebPlayer) {
            acc.maps.getOrPut(mapId) { IntArray(2) }.let { it[0]++; if (p.won) it[1]++ }
        }
    }
}

// ---------- Rollen ----------

/** Rollen-Id: „tor:<nameKey>“ für Rollen von The Other Roles, sonst „game:<Rolle des Spiels>“ (Startrolle). */
internal fun roleIdOf(p: WebPlayer): String {
    val mod = p.modStartRole ?: p.modRole
    if (mod != null && torRoleName(mod) != null) return "tor:$mod"
    return "game:" + p.role.ifEmpty { if (p.isImpostor) "Impostor" else "Crewmate" }
}

private val GAME_ROLE_NAMES = mapOf("GuardianAngel" to "Guardian Angel", "SpiritGuide" to "Influencer")

internal fun roleName(roleId: String): String = when {
    roleId.startsWith("tor:") -> torRoleName(roleId.removePrefix("tor:")) ?: roleId.removePrefix("tor:")
    else -> roleId.removePrefix("game:").let { GAME_ROLE_NAMES[it] ?: it }
}

private fun isPlainRole(roleId: String): Boolean = roleId == "game:Crewmate" || roleId == "game:Impostor"

private fun roleDescription(roleId: String): String? = when {
    roleId.startsWith("tor:") -> specialRoleFor(roleId.removePrefix("tor:"))?.takeIf { it.team != SpecialTeam.MODIFIER }?.description
    else -> ROLES[roleName(roleId)]?.description
}

/** Spieleabend einer Runde (geht bis 6 Uhr morgens, wie beim Bot). */
internal fun gameNight(time: Instant): String =
    time.atZone(TIMEZONE).minusHours(Rules.GAME_NIGHT_END_HOUR.toLong()).toLocalDate().toString()

internal fun bytes(element: JsonElement): ByteArray = element.toString().toByteArray(Charsets.UTF_8)
