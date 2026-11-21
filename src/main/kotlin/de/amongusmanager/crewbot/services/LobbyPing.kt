/*
 * Lobby-Benachrichtigung per DM (21.11.2026, Komfort-Ideen, Kilian: Punkt 5 „Discord-Benachrichtigung, wenn Freunde eine
 * Lobby öffnen“). Nur wer sich mit `/lobby-benachrichtigung` anmeldet (Opt-in), bekommt eine DM:
 *  - Modus „alle“: bei jeder neuen offenen Lobby,
 *  - Modus „freunde“: nur, wenn einer der eingetragenen Freunde die Lobby hostet oder schon drin ist.
 *
 * Grundlage ist die Lobby-Meldung der Mod (services/Lobby.kt): Host = Friendcode `sender`, Spieler = `list` (CrewStats ≥ 1.9.0),
 * beides über `/verknuepfen` zu Discord. Wer selbst hostet oder schon in der Lobby ist, bekommt nichts. Je Lobby-Code höchstens
 * eine DM pro Person, dazwischen mindestens [LobbyPingRules.COOLDOWN] (Host macht neue Lobbys auf → kein DM-Gewitter).
 * Volle Lobbys lösen nichts aus. Gespeichert in `settings` (`lobby.ping`, JSON), wer schon benachrichtigt wurde, nur im Speicher.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.MessageEmbed
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

enum class LobbyPingMode(val value: String) {
    ALL("alle"), FRIENDS("freunde"), OFF("aus");

    companion object {
        fun of(value: String?): LobbyPingMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}

/** Einstellung einer Person: Modus + Freunde (Discord-IDs), die Freunde bleiben auch bei „aus“ gespeichert. */
data class LobbyPingSub(val mode: LobbyPingMode = LobbyPingMode.OFF, val friends: List<Long> = emptyList())

/** Eine DM, die rausgehen soll: an [userId], [friends] = welche der eigenen Freunde in der Lobby sind (Host zuerst). */
data class LobbyPing(val userId: Long, val friends: List<Long>)

object LobbyPingRules {
    const val MAX_FRIENDS = 15
    val COOLDOWN: Duration = Duration.ofMinutes(5)

    /** Wer ist in der Lobby (Discord-IDs, Host zuerst)? Unverknüpfte Spieler fehlen. */
    fun present(event: LobbyEvent, userOf: (String) -> Long?): List<Long> {
        val result = LinkedHashSet<Long>()
        if (event.sender.isNotEmpty()) userOf(event.sender)?.let(result::add)
        for (player in event.list) if (player.friendCode.isNotEmpty()) userOf(player.friendCode)?.let(result::add)
        return result.toList()
    }

    /**
     * Wer bekommt zu dieser Meldung eine DM? Nur offene, nicht volle Lobbys; nicht, wer selbst drin ist;
     * [alreadyNotified] = schon für diesen Code oder vor Kurzem benachrichtigt.
     */
    fun recipients(
        event: LobbyEvent,
        subs: Map<Long, LobbyPingSub>,
        userOf: (String) -> Long?,
        alreadyNotified: (Long) -> Boolean,
    ): List<LobbyPing> {
        if (event.state != "open" || event.code.isEmpty()) return emptyList()
        if (event.maxPlayers > 0 && event.players >= event.maxPlayers) return emptyList()
        val present = present(event, userOf)
        return subs.mapNotNull { (userId, sub) ->
            if (sub.mode == LobbyPingMode.OFF || userId in present) return@mapNotNull null
            val friends = present.filter { it in sub.friends }
            if (sub.mode == LobbyPingMode.FRIENDS && friends.isEmpty()) return@mapNotNull null
            if (alreadyNotified(userId)) return@mapNotNull null
            LobbyPing(userId, friends)
        }.sortedBy { it.userId }
    }
}

class LobbyPingStore(private val db: Database) {
    fun all(): Map<Long, LobbyPingSub> = parse(db.getSetting(KEY))

    fun of(userId: Long): LobbyPingSub = all()[userId] ?: LobbyPingSub()

    /** Speichert die Einstellung von [userId] (aus + keine Freunde = Eintrag weg). */
    fun update(userId: Long, change: (LobbyPingSub) -> LobbyPingSub): LobbyPingSub = db.transaction {
        val subs = all().toMutableMap()
        val next = change(subs[userId] ?: LobbyPingSub())
        if (next.mode == LobbyPingMode.OFF && next.friends.isEmpty()) subs.remove(userId) else subs[userId] = next
        db.setSetting(KEY, if (subs.isEmpty()) null else serialize(subs))
        next
    }

    companion object {
        const val KEY = "lobby.ping"

        fun parse(value: String?): Map<Long, LobbyPingSub> {
            val obj = value?.let { parseJsonOrNull(it) } as? JsonObject ?: return emptyMap()
            return obj.mapNotNull { (key, entry) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                val data = entry as? JsonObject ?: return@mapNotNull null
                val mode = LobbyPingMode.of((data["mode"] as? JsonPrimitive)?.content)
                val friends = (data["friends"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull ?: (it as? JsonPrimitive)?.content?.toLongOrNull() }
                    ?.distinct()?.take(LobbyPingRules.MAX_FRIENDS).orEmpty()
                id to LobbyPingSub(mode, friends)
            }.toMap()
        }

        fun serialize(subs: Map<Long, LobbyPingSub>): String = buildJsonObject {
            for ((id, sub) in subs.toSortedMap()) {
                put(id.toString(), buildJsonObject {
                    put("mode", sub.mode.value)
                    put("friends", JsonArray(sub.friends.map { JsonPrimitive(it.toString()) }))
                })
            }
        }.toString()
    }
}

/** Schickt die DMs (nacheinander, mit kleiner Pause wegen Discords Rate-Limits). */
class LobbyNotifier(
    private val jda: () -> JDA,
    private val store: LobbyPingStore,
    private val links: LinkService,
    private val buildEmbed: (LobbyEvent, LobbyPing) -> MessageEmbed,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(LobbyNotifier::class.java)
    private val notified = HashMap<Long, Pair<String, Instant>>() // Person → letzter Code + Zeitpunkt

    /** Neue Lobby-Meldung der Mod. Gibt zurück, wer benachrichtigt wird (für Tests). */
    fun onLobby(event: LobbyEvent, now: Instant = Instant.now(), send: Boolean = true): List<LobbyPing> {
        val subs = try {
            store.all()
        } catch (e: Exception) {
            log.warn("Lobby-Benachrichtigungen nicht lesbar: {}", e.message)
            return emptyList()
        }
        if (subs.isEmpty()) return emptyList()
        notified.entries.removeIf { Duration.between(it.value.second, now) > Duration.ofHours(6) }
        val pings = LobbyPingRules.recipients(event, subs, links::userFor) { userId ->
            val last = notified[userId] ?: return@recipients false
            last.first == event.code || Duration.between(last.second, now) < LobbyPingRules.COOLDOWN
        }
        for (ping in pings) notified[ping.userId] = event.code to now
        if (send && pings.isNotEmpty()) scope.launch { deliver(event, pings) }
        return pings
    }

    private suspend fun deliver(event: LobbyEvent, pings: List<LobbyPing>) {
        for (ping in pings) {
            try {
                val user = jda().retrieveUserById(ping.userId).await()
                user.openPrivateChannel().await().sendMessageEmbeds(buildEmbed(event, ping)).await()
            } catch (e: Exception) {
                e.rethrowIfCancelled() // DMs zu, Person weg … nicht schlimm
                log.info("Lobby-Benachrichtigung an {} nicht zugestellt: {}", ping.userId, e.message)
            }
            delay(250)
        }
    }
}
