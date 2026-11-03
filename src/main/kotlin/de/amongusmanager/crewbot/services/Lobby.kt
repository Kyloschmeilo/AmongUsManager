/*
 * Lobby-Ankündigung: Die Mod meldet als Host den Zustand der Lobby, der Bot hält dazu eine
 * Nachricht im Codes-Channel aktuell (offen → Runde läuft → wieder offen … → geschlossen).
 *
 * Nebenbei steuert der Rundenstart das Wettbüro: „started“ sperrt neue Wetten, sobald die Lobby
 * danach wieder offen/geschlossen ist, öffnet es nach kurzer Wartezeit (falls kein Ergebnis kam –
 * normalerweise öffnet schon die Rundenauswertung).
 *
 * JSON-Format = mod/CrewStats/LobbyReporter.cs – beide Seiten gemeinsam ändern!
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.intOrNull
import de.amongusmanager.crewbot.core.isNotFound
import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.core.pyInt
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import org.slf4j.LoggerFactory

val LOBBY_STATES = listOf("open", "started", "closed")

/** Ohne neue Meldung gilt eine Lobby danach als verschwunden (der Host meldet jede Änderung der Spielerzahl). */
val LOBBY_STALE_AFTER: java.time.Duration = java.time.Duration.ofHours(1)

data class LobbyEvent(
    val state: String, // open | started | closed
    val code: String,
    val map: String,
    val players: Int,
    val maxPlayers: Int,
    val host: String, // Ingame-Name des Hosts
    val sender: String, // Friendcode des Hosts
    val region: String = "", // Server-Region des Hosts (CrewStats ≥ 1.7.1), für Auto-Join
    val server: String = "", // Adresse des Servers dieser Region
    /** Wer in der Lobby ist (CrewStats ≥ 1.9.0, sonst leer) – für die Live-Anzeige der Website. */
    val list: List<LobbyPlayer> = emptyList(),
) {
    companion object {
        /** Liest die Nachricht der Mod; null, wenn es keine Lobby-Nachricht ist. */
        fun parse(text: String): LobbyEvent? {
            val data = parseJsonOrNull(text) as? JsonObject ?: return null
            if (data.stringField("crewstats") != "lobby") return null
            val state = data.stringField("state")
            if (state !in LOBBY_STATES) return null
            return try {
                LobbyEvent(
                    state = state!!,
                    code = data["code"].orDefault(""),
                    map = data["map"].orDefault("?"),
                    players = data["players"].let { if (it.pyTruthy()) it.pyInt() else 0 },
                    maxPlayers = data["max"].let { if (it.pyTruthy()) it.pyInt() else 0 },
                    host = data["host"].orDefault(""),
                    sender = data["sender"].orDefault(""),
                    region = data["region"].orDefault(""),
                    server = data["server"].orDefault(""),
                    list = parsePlayers(data["list"]),
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Spielerliste der Lobby; Fehlerhaftes wird übersprungen, höchstens [MAX_LOBBY_PLAYERS]. */
        fun parsePlayers(element: JsonElement?): List<LobbyPlayer> {
            val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return array.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val name = obj["name"].orDefault("").trim().take(32)
                if (name.isEmpty()) return@mapNotNull null
                val color = runCatching { obj["color"].intOrNull() }.getOrNull() ?: -1
                LobbyPlayer(name, normalizeFriendCode(obj["fc"].orDefault("")), color.takeIf { it in 0..63 } ?: -1)
            }.take(MAX_LOBBY_PLAYERS)
        }

        /** `str(data.get(key) or default)` */
        private fun JsonElement?.orDefault(default: String): String = if (pyTruthy()) pyStr() else default
    }
}

/** Ein Spieler in der Lobby: Ingame-Name, Friendcode (nur intern, nie an den Browser), Spielfarbe (-1 = unbekannt). */
data class LobbyPlayer(val name: String, val friendCode: String, val colorId: Int)

const val MAX_LOBBY_PLAYERS = 20

typealias LobbyEmbedBuilder = (LobbyEvent, String) -> MessageEmbed

class LobbyAnnouncer(
    private val jda: () -> JDA,
    private val channelId: Long,
    private val links: LinkService,
    private val bets: BetService,
    private val buildEmbed: LobbyEmbedBuilder,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MIN_EDIT_MILLIS = 2000L // Discord mag keine Dauer-Edits
    }

    private val log = LoggerFactory.getLogger(LobbyAnnouncer::class.java)
    private var state: String? = null
    private var message: Message? = null
    private var shown: LobbyEvent? = null
    private var pending: LobbyEvent? = null
    private var job: Job? = null
    private var lastEdit: Long? = null
    private var latest: LobbyEvent? = null
    private var latestAt: java.time.Instant? = null

    /**
     * Neueste Lobby, die eine Mod gemeldet hat (für Auto-Join), oder null, wenn gerade keine offen ist.
     * Nur im Speicher: nach einem Bot-Neustart erst wieder, sobald der Host etwas meldet.
     */
    fun current(now: java.time.Instant = java.time.Instant.now()): Pair<LobbyEvent, java.time.Instant>? {
        val event = latest ?: return null
        val at = latestAt ?: return null
        if (event.state == "closed" || event.code.isEmpty()) return null
        if (java.time.Duration.between(at, now) > LOBBY_STALE_AFTER) return null // Host abgestürzt o.ä.
        return event to at
    }

    /** Neuer Zustand von der Mod. Das Wettbüro reagiert sofort, die Nachricht gedrosselt. */
    fun apply(event: LobbyEvent) {
        if (event.state == "started") {
            bets.lock()
        } else if (state == "started") {
            bets.unlock(afterSeconds = Rules.BET_UNLOCK_GRACE_SECONDS)
        }
        state = event.state
        latest = event
        latestAt = java.time.Instant.now()

        pending = event
        if (job?.isActive != true) job = scope.launch { run() }
    }

    private suspend fun run() {
        while (pending != null) {
            val wait = lastEdit?.let { it + MIN_EDIT_MILLIS - monotonicMillis() } ?: 0L
            if (wait > 0) delay(wait)
            val event = pending ?: return
            pending = null
            try {
                show(event)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.warn("Lobby-Nachricht konnte nicht aktualisiert werden: {}", e.describe())
            }
            lastEdit = monotonicMillis()
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

    private suspend fun show(event: LobbyEvent) {
        // Neue Lobby (anderer Code): die alte Nachricht als geschlossen markieren
        val previous = shown
        if (message != null && previous != null && event.code != previous.code) {
            edit(closed(previous))
            message = null
        }

        if (event.state == "closed") {
            if (message != null) edit(event)
            message = null
            shown = null
            return
        }

        if (message == null || !edit(event)) {
            val channel = jda().getChannelById(GuildMessageChannel::class.java, channelId)
            if (channel == null) {
                log.warn("Codes-Channel {} nicht gefunden – keine Lobby-Ankündigung", channelId)
                return
            }
            message = channel.sendMessageEmbeds(buildEmbed(event, hostText(event)))
                .setAllowedMentions(emptySet())
                .await()
        }
        shown = event
    }

    /** Bearbeitet die aktuelle Nachricht. false, wenn sie inzwischen gelöscht wurde. */
    private suspend fun edit(event: LobbyEvent): Boolean {
        val current = message ?: return false
        return try {
            message = current.editMessageEmbeds(buildEmbed(event, hostText(event))).await()
            true
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (!e.isNotFound()) throw e
            message = null
            false
        }
    }

    private fun hostText(event: LobbyEvent): String {
        val userId = if (event.sender.isNotEmpty()) links.userFor(event.sender) else null
        return if (userId != null) "<@$userId>" else event.host.ifEmpty { "?" }
    }

    private fun closed(event: LobbyEvent): LobbyEvent = event.copy(state = "closed", players = 0)
}
