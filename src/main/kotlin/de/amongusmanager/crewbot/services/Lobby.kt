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

data class LobbyEvent(
    val state: String, // open | started | closed
    val code: String,
    val map: String,
    val players: Int,
    val maxPlayers: Int,
    val host: String, // Ingame-Name des Hosts
    val sender: String, // Friendcode des Hosts
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
                )
            } catch (e: Exception) {
                null
            }
        }

        /** `str(data.get(key) or default)` */
        private fun JsonElement?.orDefault(default: String): String = if (pyTruthy()) pyStr() else default
    }
}

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

    /** Neuer Zustand von der Mod. Das Wettbüro reagiert sofort, die Nachricht gedrosselt. */
    fun apply(event: LobbyEvent) {
        if (event.state == "started") {
            bets.lock()
        } else if (state == "started") {
            bets.unlock(afterSeconds = Rules.BET_UNLOCK_GRACE_SECONDS)
        }
        state = event.state

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
