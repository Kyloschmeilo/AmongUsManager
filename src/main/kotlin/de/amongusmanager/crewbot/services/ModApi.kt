/*
 * Spieler-Anfragen von CrewStats über die Stats-Website statt über einen Discord-Webhook.
 *
 * Auto-Join, eigene Stats und der Verknüpfungs-Code sind Frage/Antwort: Über einen Webhook heißt das Nachricht posten,
 * auf die Bearbeitung durch den Bot warten (Mod fragt alle 0,7 s nach), lesen, löschen – und Discord bremst bei vielen
 * Spielern. Die Website hat einen offenen Port und dieselbe Datenbank, sie antwortet in EINER HTTP-Anfrage:
 *
 *   POST <WEB_PUBLIC_URL>/api/mod/<Schlüssel>   Body = dieselbe JSON wie bisher im Webhook-Text
 *     {"crewstats":"join"}  → join-reply   (Lobby aus der Datenbank, siehe [LobbyStore])
 *     {"crewstats":"stats"} → stats-reply  (rechnet die Website selbst aus, [PlayerInfoService])
 *     {"crewstats":"link"}  → link-reply   (Code erzeugt der Bot – Auftrag „mod.link“ über web_requests)
 *
 * Der Schlüssel (`modapi.key`) ersetzt den Webhook-Token: Wer die URL hat, kann nur diese drei Fragen stellen.
 * Die Website trägt ihre Adresse + ein Lebenszeichen ein (`web.public_url`, `web.heartbeat`); nur wenn sie läuft,
 * zeigt /autojoin die Website-URL, sonst wie bisher die Webhook-URL. Alte Webhook-URLs in der Mod gehen weiter.
 *
 * Host-Meldungen (Runden, Auto-Mute, Lobby, Rollenwünsche) laufen bewusst weiter über den Mod-Webhook: Discord stellt sie
 * dem Bot sofort zu, die Website müsste sie über die Datenbank weiterreichen (der Bot schaut dort nur alle 0,25–1 s nach).
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class ModApiSettings(private val db: Database) {
    /** Schlüssel in der Website-URL; wird beim ersten Bedarf erzeugt. */
    fun key(): String = db.getSetting(KEY)?.takeIf { it.isNotEmpty() } ?: renewKey()

    /** Nur lesen (Website prüft damit), null = noch keiner. */
    fun currentKey(): String? = db.getSetting(KEY)?.takeIf { it.isNotEmpty() }

    /** Neuer Schlüssel – alte Website-URLs gehen danach nicht mehr. */
    fun renewKey(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        db.setSetting(KEY, key)
        return key
    }

    /** Website meldet sich: ihre öffentliche Adresse + Zeitpunkt. */
    fun websiteAlive(publicUrl: String, now: Instant = Instant.now()) {
        db.setSetting(PUBLIC_URL, publicUrl.trim().trimEnd('/'))
        db.setSetting(HEARTBEAT, now.toEpochMilli().toString())
    }

    /** Adresse der Website, wenn sie sich vor kurzem gemeldet hat – sonst null (dann bleibt es beim Webhook). */
    fun websiteUrl(now: Instant = Instant.now()): String? {
        val url = db.getSetting(PUBLIC_URL)?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val beat = db.getSetting(HEARTBEAT)?.toLongOrNull() ?: return null
        if (Instant.ofEpochMilli(beat).plus(WEBSITE_MAX_AGE).isBefore(now)) return null
        return url
    }

    /** Die URL für die Mod (Auto-Join + Komfort) oder null, wenn die Website gerade nicht läuft. */
    fun playerUrl(now: Instant = Instant.now()): String? = websiteUrl(now)?.let { "$it$PATH${key()}" }

    companion object {
        const val PATH = "/api/mod/"
        private const val KEY = "modapi.key"
        private const val PUBLIC_URL = "web.public_url"
        private const val HEARTBEAT = "web.heartbeat"

        /** Die Website meldet sich bei jedem Aktualisieren (Standard alle 60 s). */
        val WEBSITE_MAX_AGE: Duration = Duration.ofMinutes(10)
        val WEBSITE_HEARTBEAT: Duration = Duration.ofMinutes(1)

        /** Schlüssel vergleichen, ohne über die Laufzeit etwas zu verraten. */
        fun sameKey(expected: String?, given: String): Boolean =
            expected != null && java.security.MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }
}

/**
 * Lobby-Meldungen der Hosts in der Datenbank, damit die Website sie selbst zeigen bzw. Auto-Join-Fragen beantworten kann.
 * Der Bot schreibt bei jeder Lobby-Meldung der Mod (höchstens alle paar Sekunden):
 *   `lobby.current` – die neueste Meldung (Auto-Join), ohne Friendcodes
 *   `lobby.live`    – alle offenen/laufenden Lobbys (eine je Host, neueste zuerst) mit Spielerliste für die Live-Anzeige.
 *                     Enthält Friendcodes (nur in der Datenbank – die Website macht daraus Spieler-Schlüssel).
 */
class LobbyStore(private val db: Database) {
    fun save(event: LobbyEvent, at: Instant = Instant.now()) {
        db.setSetting(KEY, buildJsonObject {
            put("crewstats", "lobby")
            put("state", event.state)
            put("code", event.code)
            put("map", event.map)
            put("players", event.players)
            put("max", event.maxPlayers)
            put("host", event.host)
            put("region", event.region)
            put("server", event.server)
            put("at", at.toEpochMilli())
        }.toString()) // ohne Friendcode des Hosts (sender) – braucht niemand
        saveLive(event, at)
    }

    /**
     * Neueste offene/laufende Lobby, null wenn keine (oder seit 1 h nichts gemeldet). Schließt ein Host seine Lobby,
     * bleibt die eines anderen Hosts erhalten (Live-Liste); ohne Live-Liste (Bot vor 2.6) wie [LobbyAnnouncer.current].
     */
    fun current(now: Instant = Instant.now()): Pair<LobbyEvent, Instant>? {
        if (db.getSetting(LIVE_KEY) != null) return live(now).firstOrNull()
        val text = db.getSetting(KEY) ?: return null
        val event = LobbyEvent.parse(text) ?: return null
        val millis = ((parseJsonOrNull(text) as? JsonObject)?.get("at") as? JsonPrimitive)?.longOrNull ?: return null
        val at = Instant.ofEpochMilli(millis)
        if (!isLive(event, at, now)) return null
        return event to at
    }

    /** Alle offenen/laufenden Lobbys, neueste Meldung zuerst. */
    fun live(now: Instant = Instant.now()): List<Pair<LobbyEvent, Instant>> {
        val array = parseJsonOrNull(db.getSetting(LIVE_KEY) ?: return emptyList()) as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val event = LobbyEvent.parse(obj.toString()) ?: return@mapNotNull null
            val at = Instant.ofEpochMilli((obj["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null)
            if (isLive(event, at, now)) event to at else null
        }
    }

    /** Eine Lobby der Live-Liste nach Code. */
    fun find(code: String, now: Instant = Instant.now()): Pair<LobbyEvent, Instant>? =
        live(now).firstOrNull { it.first.code.equals(code.trim(), ignoreCase = true) }

    private fun saveLive(event: LobbyEvent, at: Instant) {
        val owner = ownerOf(event)
        val others = live(at).filter { (other, _) -> ownerOf(other) != owner && !other.code.equals(event.code, ignoreCase = true) }
        val all = (if (isLive(event, at, at)) listOf(event to at) else emptyList()) + others
        db.setSetting(LIVE_KEY, JsonArray(all.take(MAX_LIVE).map { (e, t) -> liveJson(e, t) }).toString())
    }

    private fun liveJson(event: LobbyEvent, at: Instant): JsonObject = buildJsonObject {
        put("crewstats", "lobby")
        put("state", event.state)
        put("code", event.code)
        put("map", event.map)
        put("players", event.players)
        put("max", event.maxPlayers)
        put("host", event.host)
        put("sender", event.sender)
        put("region", event.region)
        put("server", event.server)
        put("list", JsonArray(event.list.map { p ->
            buildJsonObject {
                put("name", p.name)
                put("fc", p.friendCode)
                put("color", p.colorId)
            }
        }))
        put("at", at.toEpochMilli())
    }

    /** Ein Host hat höchstens eine Lobby (Friendcode, bei alten Mods ohne den der Ingame-Name). */
    private fun ownerOf(event: LobbyEvent): String =
        event.sender.ifEmpty { "n:" + event.host }.lowercase()

    private fun isLive(event: LobbyEvent, at: Instant, now: Instant): Boolean =
        event.state != "closed" && event.code.isNotEmpty() && Duration.between(at, now) <= LOBBY_STALE_AFTER

    companion object {
        private const val KEY = "lobby.current"
        private const val LIVE_KEY = "lobby.live"
        private const val MAX_LIVE = 10
    }
}
