/*
 * CrewStats-Sperre (Kilian, 27.11.2026: „baue eine sperr funktion ein das jeglich ein gaben von einer Person geblockt
 * werden koennen bei Crewstats“).
 *
 * Admins sperren eine Person per Discord-Konto (gilt für alle ihre verknüpften Friendcodes, auch später verknüpfte)
 * oder per Friendcode (für Spieler ohne Verknüpfung). Danach wird alles, was von ihr über CrewStats kommt, ignoriert:
 *  - Host-Meldungen ihrer Mod (Runden, Auto-Mute, Lobby, Einstellungen, Rollenwünsche, Voice) → gelöscht, nichts passiert
 *  - in Runden anderer Hosts: keine XP, keine Siege/Stats, keine Erfolge, im Verlauf nur unter dem Ingame-Namen
 *  - Anfragen ihrer Mod (Auto-Join, Lobby-Stats, Verknüpfungs-Code) → leere Antwort bzw. „gesperrt“
 *  - Rollenwünsche/Admin-Zuweisungen gehen nicht an die Mod, CrewStats-Befehle im Discord sind gesperrt
 *  - Website: nicht in Bestenlisten, kein Beitreten über „Live“, kein Proximity-Voice
 * Gespeichert als JSON-Liste in `settings` (`crewstats.bans`), gesetzt nur per `/crewstats-sperre` (Admin).
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.shortHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/** Eine Sperre: genau eins von [userId] (Discord-Konto) oder [friendCode] ist gesetzt. */
data class PlayerBan(
    val userId: Long?,
    val friendCode: String?,
    val reason: String,
    val bannedBy: Long,
    val at: Instant,
)

class PlayerBans(private val db: Database, private val links: LinkService) {

    fun all(): List<PlayerBan> = parse(db.getSetting(KEY))

    fun isUserBanned(userId: Long): Boolean = all().any { it.userId == userId }

    /** Gesperrt, wenn der Friendcode selbst gesperrt ist oder zu einem gesperrten Discord-Konto gehört. */
    fun isCodeBanned(friendCode: String): Boolean {
        if (friendCode.isBlank()) return false
        return banned(all(), friendCode)
    }

    /** Welche der [friendCodes] gesperrt sind (eine Abfrage der Liste für viele Codes, z.B. eine ganze Runde). */
    fun bannedCodes(friendCodes: Collection<String>): Set<String> {
        val bans = all()
        if (bans.isEmpty()) return emptySet()
        return friendCodes.filter { it.isNotBlank() && banned(bans, it) }.mapTo(HashSet(), ::normalizeFriendCode)
    }

    private fun banned(bans: List<PlayerBan>, friendCode: String): Boolean {
        if (bans.isEmpty()) return false
        val code = normalizeFriendCode(friendCode)
        if (bans.any { it.friendCode == code }) return true
        val owner = links.userFor(code) ?: return false
        return bans.any { it.userId == owner }
    }

    /** Sperrt ein Konto bzw. einen Friendcode. `false`, wenn die Sperre schon bestand (Grund wird dann aktualisiert). */
    fun ban(userId: Long?, friendCode: String?, reason: String, by: Long, now: Instant = Instant.now()): Boolean = db.transaction {
        require((userId == null) != (friendCode == null)) { "Genau ein Ziel (Konto oder Friendcode)" }
        val code = friendCode?.let(::normalizeFriendCode)
        val current = all()
        val existing = current.firstOrNull { matches(it, userId, code) }
        val entry = PlayerBan(userId, code, reason.trim().take(MAX_REASON), by, existing?.at ?: now)
        save(current.filterNot { matches(it, userId, code) } + entry)
        existing == null
    }

    /** Hebt die Sperre auf. `false`, wenn es keine gab. */
    fun unban(userId: Long?, friendCode: String?): Boolean = db.transaction {
        val code = friendCode?.let(::normalizeFriendCode)
        val current = all()
        val next = current.filterNot { matches(it, userId, code) }
        if (next.size == current.size) return@transaction false
        save(next)
        true
    }

    /** Website-Spielerschlüssel der Gesperrten (für die Bestenlisten), mit den Verknüpfungen [linkMap] (Friendcode → Discord). */
    fun playerKeys(linkMap: Map<String, Long>): Set<String> = keysOf(all(), linkMap)

    private fun matches(ban: PlayerBan, userId: Long?, code: String?): Boolean =
        (userId != null && ban.userId == userId) || (code != null && ban.friendCode == code)

    private fun save(bans: List<PlayerBan>) {
        if (bans.isEmpty()) {
            db.setSetting(KEY, null)
            return
        }
        db.setSetting(KEY, buildJsonArray {
            for (ban in bans) add(buildJsonObject {
                ban.userId?.let { put("user", it.toString()) }
                ban.friendCode?.let { put("fc", it) }
                put("reason", ban.reason)
                put("by", ban.bannedBy.toString())
                put("at", ban.at.toEpochMilli())
            })
        }.toString())
    }

    companion object {
        const val KEY = "crewstats.bans"
        const val MAX_REASON = 200

        /** Meldung für gesperrte Personen (Website, Discord). */
        const val BANNED_TEXT = "Du bist für CrewStats gesperrt. Wende dich an einen Admin."

        fun parse(value: String?): List<PlayerBan> {
            val array = value?.let { parseJsonOrNull(it) } as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val user = (obj["user"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                val fc = (obj["fc"] as? JsonPrimitive)?.contentOrNull?.let(::normalizeFriendCode)?.takeIf { it.isNotEmpty() }
                if ((user == null) == (fc == null)) return@mapNotNull null
                PlayerBan(
                    userId = user,
                    friendCode = fc,
                    reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    bannedBy = (obj["by"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
                    at = Instant.ofEpochMilli((obj["at"] as? JsonPrimitive)?.longOrNull ?: 0L),
                )
            }
        }

        /** Wie [playerKeys], ohne Datenbank (Website liest die Liste selbst). */
        fun keysOf(bans: List<PlayerBan>, linkMap: Map<String, Long>): Set<String> {
            val keys = LinkedHashSet<String>()
            for (ban in bans) {
                ban.userId?.let { keys.add(WebAuth.playerKeyOf(it)) }
                ban.friendCode?.let { fc ->
                    keys.add(shortHash("crewstats-web:f:$fc"))
                    linkMap[fc]?.let { keys.add(WebAuth.playerKeyOf(it)) }
                }
            }
            return keys
        }
    }
}
