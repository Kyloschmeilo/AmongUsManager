/*
 * Komfort-Funktionen von CrewStats (ab 1.7.0): eigene Stats im Spiel, Lobby-Info, XP nach der Runde und
 * Discord-Verknüpfung per Code. Läuft über denselben Weg wie Auto-Join (Anfrage mit ?wait=true, der Bot bearbeitet
 * GENAU diese Webhook-Nachricht, die Mod liest und löscht sie) – über den Auto-Join-Webhook oder den Mod-Webhook.
 *
 *   {"crewstats":"stats","v":1,"me":"name#1234","players":["a#1111",…]}
 *     → {"crewstats":"stats-reply","v":1,"me":{…},"players":[{…},…]}   (players in derselben Reihenfolge)
 *   {"crewstats":"link","v":1,"fc":"name#1234"}
 *     → {"crewstats":"link-reply","v":1,"ok":true,"code":"K7P2QX","minutes":10,"linked":false}
 *
 * Bewusst KEINE Discord-Namen oder -IDs in den Antworten: wer die Auto-Join-URL hat, könnte sonst zu jedem Friendcode
 * den Discord-Account erfahren. Es gibt nur „verknüpft ja/nein“ und die Zahlen, die auch die Stats-Website zeigt.
 * JSON-Format = CrewStats PlayerInfoRules.cs – beide Seiten gemeinsam ändern!
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

const val MAX_INFO_PLAYERS = 24 // The Other Roles erlaubt auf Modded NA bis zu 24 Spieler (sonst 15)
private const val MAX_REPLY_CHARS = 1900 // Discord-Nachrichten: höchstens 2000 Zeichen

sealed interface PlayerInfoRequest {
    data class Stats(val me: String?, val players: List<String>) : PlayerInfoRequest
    data class Link(val friendCode: String) : PlayerInfoRequest
}

object PlayerInfoProtocol {
    const val STATS = "stats"
    const val STATS_REPLY = "stats-reply"
    const val LINK = "link"
    const val LINK_REPLY = "link-reply"

    /** Liest eine Anfrage der Mod; null = keine (oder kaputte) Komfort-Anfrage. Ungültige Friendcodes fallen raus. */
    fun parse(text: String): PlayerInfoRequest? {
        val data = parseJsonOrNull(text) as? JsonObject ?: return null
        fun code(value: String?): String? = value?.let(::normalizeFriendCode)?.takeIf(::isValidFriendCode)
        return when (data.stringField("crewstats")) {
            STATS -> PlayerInfoRequest.Stats(
                code(data.stringField("me")),
                (data["players"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::code) }
                    .take(MAX_INFO_PLAYERS),
            )
            LINK -> code(data.stringField("fc"))?.let { PlayerInfoRequest.Link(it) }
                ?: PlayerInfoRequest.Link("") // kaputter Friendcode → Antwort mit Fehler statt Schweigen
            else -> null
        }
    }

    fun linkReply(code: String?, linked: Boolean, error: String? = null): String = buildJsonObject {
        put("crewstats", LINK_REPLY)
        put("v", 1)
        put("ok", code != null)
        if (code != null) {
            put("code", code)
            put("minutes", LINK_CODE_LIFETIME.toMinutes())
        }
        put("linked", linked)
        if (error != null) put("error", error)
    }.toString()
}

/** Zahlen eines Spielers für die Mod. */
data class PlayerInfo(
    val linked: Boolean,
    val level: Int = 0,
    val xp: Int = 0,
    val rank: String = "",
    val games: Int = 0,
    val wins: Int = 0,
    val kills: Int = 0,
    val lastRound: LastRoundInfo? = null,
)

/** Letzte ausgewertete Runde eines Spielers (für die Rundenzusammenfassung in der Mod). */
data class LastRoundInfo(
    val endedAt: Instant,
    val role: String,
    val team: String,
    val won: Boolean,
    val xp: Int,
    val kills: Int,
    val tasksCompleted: Int,
    val tasksTotal: Int,
)

/** Rechnet die Antworten aus (ohne Discord, getestet in PlayerInfoTest). */
class PlayerInfoService(
    private val db: Database,
    private val links: LinkService,
    private val levels: LevelService,
    private val stats: GameStatsService,
    private val history: RoundHistoryService,
    private val roleLabel: (ModPlayer) -> String,
    private val clock: () -> Instant = Instant::now,
) {
    fun info(friendCode: String, withLastRound: Boolean): PlayerInfo {
        val userId = links.userFor(friendCode) ?: return PlayerInfo(linked = false)
        val level = levels.get(userId)
        val game = stats.get(userId)
        return PlayerInfo(
            linked = true,
            level = level.level,
            xp = level.xp,
            rank = level.rank.name,
            games = game.games,
            wins = game.wins,
            kills = game.kills,
            lastRound = if (withLastRound) lastRound(userId) else null,
        )
    }

    private fun lastRound(userId: Long): LastRoundInfo? {
        val last = history.roundsOf(userId).lastOrNull() ?: return null
        val player = last.playerOf(userId) ?: return null
        return LastRoundInfo(
            endedAt = last.endedAt,
            role = plain(roleLabel(player)),
            team = player.team,
            won = player.won,
            xp = xpOf(last.round.gameId, userId),
            kills = player.kills,
            tasksCompleted = player.tasksCompleted,
            tasksTotal = player.tasksTotal,
        )
    }

    /** XP, die die Runde dem Spieler gebracht hat (Sieg, Rollen-Aktionen, Erfolge – ohne Wetten). */
    fun xpOf(gameId: String, userId: Long): Int {
        val awards = db.query("SELECT xp FROM mod_round_awards WHERE game_id = ? AND user_id = ?", gameId, userId) { it.int("xp") }
        val unlocks = db.query("SELECT xp FROM mod_round_achievements WHERE game_id = ? AND user_id = ?", gameId, userId) { it.int("xp") }
        return awards.sum() + unlocks.sum()
    }

    fun statsReply(request: PlayerInfoRequest.Stats): String {
        val now = clock()
        val me = request.me?.let { info(it, withLastRound = true) }
        val players = request.players.map { info(it, withLastRound = false) }
        // Sicherheitsnetz für Discords 2000-Zeichen-Grenze: notfalls hinten Spieler weglassen
        var count = players.size
        while (true) {
            val text = statsJson(me, players.take(count), now)
            if (text.length <= MAX_REPLY_CHARS || count == 0) return text
            count--
        }
    }

    private fun statsJson(me: PlayerInfo?, players: List<PlayerInfo>, now: Instant): String = buildJsonObject {
        put("crewstats", PlayerInfoProtocol.STATS_REPLY)
        put("v", 1)
        put("xpPerLevel", Rules.XP_PER_LEVEL)
        if (me != null) {
            putJsonObject("me") {
                putInfo(me)
                if (me.linked) {
                    put("xp", me.xp)
                    put("rank", me.rank)
                    put("kills", me.kills)
                }
                me.lastRound?.let { last ->
                    putJsonObject("last") {
                        put("ago", Duration.between(last.endedAt, now).seconds.coerceAtLeast(0))
                        put("role", last.role)
                        put("team", last.team)
                        put("won", last.won)
                        put("xp", last.xp)
                        put("kills", last.kills)
                        put("tasks", last.tasksCompleted)
                        put("tasksTotal", last.tasksTotal)
                    }
                }
            }
        }
        put("players", buildJsonArray {
            for (p in players) add(buildJsonObject { putInfo(p) })
        })
    }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putInfo(info: PlayerInfo) {
        put("linked", info.linked)
        if (!info.linked) return
        put("level", info.level)
        put("games", info.games)
        put("wins", info.wins)
    }

    companion object {
        private val EMOJI = Regex("[\\p{So}\\p{Cs}\\p{Cn}\\uFE0F\\u200D]")

        /** Ohne Emojis – die Mod zeigt den Text mit einer Schrift ohne Emojis an. */
        fun plain(text: String): String = text.replace(EMOJI, "").replace(Regex("\\s+"), " ").trim()
    }
}

val LINK_CODE_LIFETIME: Duration = Duration.ofMinutes(10)
private const val LINK_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // ohne 0/O, 1/I
private const val MAX_OPEN_LINK_CODES = 500

/**
 * Verknüpfen per Code: die Mod holt für ihren Friendcode einen Code, der Spieler gibt ihn bei /verknuepfen-code ein.
 * Nur im Speicher (Neustart = Code ungültig, einfach neu holen), ein Code pro Friendcode, 10 Minuten gültig.
 */
class LinkCodes(private val clock: () -> Instant = Instant::now) {
    private data class Pending(val friendCode: String, val expires: Instant)

    private val random = SecureRandom()
    private val pending = LinkedHashMap<String, Pending>()

    fun create(friendCode: String): String {
        val now = clock()
        prune(now)
        pending.entries.removeIf { it.value.friendCode == friendCode }
        while (pending.size >= MAX_OPEN_LINK_CODES) pending.remove(pending.keys.first())
        var code: String
        do {
            code = (1..6).map { LINK_CODE_ALPHABET[random.nextInt(LINK_CODE_ALPHABET.length)] }.joinToString("")
        } while (code in pending)
        pending[code] = Pending(friendCode, now + LINK_CODE_LIFETIME)
        return code
    }

    /** Löst einen Code ein (einmalig): der Friendcode dazu oder null (falsch/abgelaufen). */
    fun redeem(code: String): String? {
        val now = clock()
        prune(now)
        val key = code.trim().uppercase().replace(" ", "").replace("-", "")
        return pending.remove(key)?.friendCode
    }

    private fun prune(now: Instant) {
        pending.entries.removeIf { it.value.expires <= now }
    }
}
