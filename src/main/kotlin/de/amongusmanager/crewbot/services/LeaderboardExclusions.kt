/*
 * Spieler, die ein Admin aus den Bestenlisten ausgenommen hat (Kilian, 07.11.2026: „gebe admins die option leute aus
 * bestenlisten auszunehmen“).
 *
 * Gespeichert als JSON-Liste von Website-Spielerschlüsseln in `settings` (`leaderboard.hidden`) – so gehen auch Spieler
 * ohne Discord-Verknüpfung (Schlüssel aus Friendcode/Name). Discord-Mitglieder haben den Schlüssel
 * [WebAuth.playerKeyOf]. Wirkung:
 *  - Website: Bestenliste, Top-Listen auf der Startseite und Rekorde zeigen sie nicht (Profil, Runden, Vergleich bleiben).
 *  - Bot: `/leaderboard` überspringt sie, sie bekommen keine Medaille im Nickname.
 * Gesetzt wird nur über das Admin-Panel der Website (Aktion `admin.leaderboard`, der Bot prüft das Admin-Recht).
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.web.WebAuth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class LeaderboardExclusions(private val db: Database) {

    /** Alle ausgenommenen Spielerschlüssel. */
    fun keys(): Set<String> = parse(db.getSetting(KEY))

    fun isHidden(discordId: Long): Boolean = WebAuth.playerKeyOf(discordId) in keys()

    /** Nimmt [key] aus (`hidden = true`) oder wieder auf. `true`, wenn sich etwas geändert hat. */
    fun set(key: String, hidden: Boolean): Boolean = db.transaction {
        val current = keys()
        val next = if (hidden) current + key else current - key
        if (next == current) return@transaction false
        db.setSetting(KEY, if (next.isEmpty()) null else JsonArray(next.sorted().map { JsonPrimitive(it) }).toString())
        true
    }

    companion object {
        const val KEY = "leaderboard.hidden"

        /** Gleiche Form wie die Spielerschlüssel der Website (12 Hex-Zeichen). */
        val KEY_PATTERN = Regex("[0-9a-f]{12}")

        fun parse(value: String?): Set<String> {
            val array = value?.let { parseJsonOrNull(it) } as? JsonArray ?: return emptySet()
            return array.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(KEY_PATTERN::matches) }
        }
    }
}
