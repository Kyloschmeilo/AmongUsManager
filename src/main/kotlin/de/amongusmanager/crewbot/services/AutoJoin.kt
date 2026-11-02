/*
 * Auto-Join: CrewStats fragt den Bot nach der neuesten Lobby und tritt ihr bei.
 *
 * Eigener Webhook („CrewStats Auto-Join“), getrennt vom Mod-Webhook: dessen URL ist geheim (wer sie hat, kann Runden
 * einschicken), die Auto-Join-URL bekommt dagegen jeder Spieler per /autojoin. Über sie beantwortet der Bot nur
 * Lobby-Anfragen – Runden, Auto-Mute usw. kommen darüber nicht an.
 *
 * Ablauf wie bei den Rollenwünschen (kein offener Port):
 *   1. Mod schickt   {"crewstats":"join","v":1}   (mit ?wait=true, kennt dadurch die Nachrichten-ID)
 *   2. Bot bearbeitet GENAU diese Webhook-Nachricht zu
 *                    {"crewstats":"join-reply","v":1,"state":"open|started|none","code","map","players","max","host","age",
 *                     "region","server"}   (region/server nur, wenn der Host sie meldet – Codes gelten nur auf diesem Server)
 *   3. Mod liest sie (GET …/messages/<id>) und löscht sie; sonst räumt der Bot nach 30 s auf.
 *
 * Lobby-Codes stehen ohnehin im Codes-Channel, deshalb ohne Verschlüsselung.
 * JSON-Format = mod/CrewStats/AutoJoin.cs – beide Seiten gemeinsam ändern!
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

object AutoJoinProtocol {
    const val REQUEST = "join"
    const val REPLY = "join-reply"

    /** true, wenn der Text eine Auto-Join-Anfrage der Mod ist. */
    fun isRequest(text: String): Boolean {
        val data = parseJsonOrNull(text) as? JsonObject ?: return false
        return data.stringField("crewstats") == REQUEST
    }

    /** Antwort auf eine Anfrage: die neueste Lobby (offen oder Runde läuft) oder „none“. */
    fun reply(current: Pair<LobbyEvent, Instant>?, now: Instant = Instant.now()): String = buildJsonObject {
        put("crewstats", REPLY)
        put("v", 1)
        if (current == null) {
            put("state", "none")
            return@buildJsonObject
        }
        val (lobby, at) = current
        put("state", lobby.state)
        put("code", lobby.code)
        put("map", lobby.map)
        put("players", lobby.players)
        put("max", lobby.maxPlayers)
        put("host", lobby.host)
        put("age", Duration.between(at, now).seconds.coerceAtLeast(0))
        if (lobby.region.isNotEmpty()) put("region", lobby.region)
        if (lobby.server.isNotEmpty()) put("server", lobby.server)
    }.toString()
}

class AutoJoinSettings(private val db: Database) {
    val webhookId: Long? get() = db.getSetting("autojoin.webhook_id")?.toLongOrNull()

    fun set(webhookId: Long) = db.setSetting("autojoin.webhook_id", webhookId.toString())
}
