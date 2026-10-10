/*
 * Auto-Mute: setzt Mute/Taub im Voice passend zur Spielphase, die die CrewStats-Mod meldet.
 *
 * Ablauf: Mod -> Discord-Webhook (JSON im Nachrichtentext) -> Bot schaltet um.
 *   tasks   -> Lebende stumm + taub, Tote reden miteinander
 *   meeting -> Lebende reden, Tote stumm (hören aber zu)
 *   end     -> alle frei (Spielende, Lobby, Mod-Schalter aus)
 * Tot ist, wer in der `dead`-Liste steht und verknüpft ist – alle anderen zählen als lebend.
 * Die Mod schickt die Toten nur beim Phasenwechsel, ein Kill mitten in der Runde ändert also
 * nichts am Voice (sonst würden die Discord-Symbole den Tod verraten).
 * Gemutet wird im Voice-Channel des Spielers mit der Mod (über /verknuepfen gefunden),
 * sonst im zuletzt benutzten bzw. im Among-Us-Channel.
 *
 * JSON-Format = mod/CrewStats/AutoMute.cs – beide Seiten gemeinsam ändern!
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel

val PHASES = listOf("tasks", "meeting", "end")

/** Wert eines Felds, wenn es ein Text ist (sonst null). */
internal fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

data class AutoMuteEvent(
    val phase: String, // tasks | meeting | end
    val sender: String, // Friendcode des Spielers mit der Mod
    val dead: Set<String>, // Friendcodes der toten Spieler (Stand beim Phasenwechsel)
) {
    companion object {
        /** Liest die Nachricht der Mod; null, wenn es keine Auto-Mute-Nachricht ist. */
        fun parse(text: String): AutoMuteEvent? {
            val data = parseJsonOrNull(text) as? JsonObject ?: return null
            if (data.stringField("crewstats") != "automute") return null
            val phase = data.stringField("phase")
            if (phase !in PHASES) return null
            val dead = data["dead"].let { if (it.pyTruthy()) it else null }
            val codes = when (dead) {
                null -> emptyList()
                is JsonArray -> dead.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                is JsonObject -> dead.keys.toList() // Python iteriert über die Schlüssel eines Objekts
                is JsonPrimitive -> if (dead.isString) dead.content.map { it.toString() } else return null
            }
            return AutoMuteEvent(
                phase = phase!!,
                sender = data["sender"].let { if (it.pyTruthy()) it.pyStr() else "" },
                dead = codes.toSet(),
            )
        }
    }
}

data class AutoMuteResult(val channel: AudioChannel, val report: ActionReport)

/** (stumm, taub) für ein Mitglied. Wer nicht verknüpft ist, zählt als lebend. */
fun targetState(phase: String, dead: Boolean): Pair<Boolean, Boolean> = when (phase) {
    "tasks" -> if (dead) false to false else true to true // Tote reden, Lebende hören nichts
    "meeting" -> if (dead) true to false else false to false // Tote hören nur zu
    else -> false to false
}

class AutoMuteService(
    private val workers: WorkerManager,
    private val links: LinkService,
    private val voiceStatus: VoiceStatusUpdater,
    private val fallbackChannelId: Long,
) {
    private var lastChannelId: Long? = null
    private val lock = Mutex()
    private var sequence = 0

    /** Setzt die Mutes. Kommen Phasen schnell hintereinander, gewinnt immer die neueste. */
    suspend fun apply(guild: Guild, event: AutoMuteEvent): AutoMuteResult? {
        val mySequence = ++sequence
        return lock.withLock {
            if (mySequence != sequence) return@withLock null // inzwischen ist eine neuere Phase angekommen

            val channel = channelFor(guild, event.sender) ?: return@withLock null
            lastChannelId = channel.idLong

            val deadIds = event.dead.mapNotNull { links.userFor(it) }.toSet()
            val changes = channel.members.mapNotNull { member ->
                val voice = member.voiceState
                if (member.user.isBot || voice == null) return@mapNotNull null
                val (mute, deafen) = targetState(event.phase, dead = member.idLong in deadIds)
                val change = VoiceChange(
                    member,
                    mute = if (voice.isGuildMuted != mute) mute else null,
                    deafen = if (voice.isGuildDeafened != deafen) deafen else null,
                )
                if (change.mute == null && change.deafen == null) null else change
            }

            val report = workers.editVoice(changes)
            if (channel.idLong == fallbackChannelId) voiceStatus.refresh(event.phase == "tasks")
            AutoMuteResult(channel, report)
        }
    }

    private fun channelFor(guild: Guild, sender: String): AudioChannel? {
        // 1. Voice-Channel des Spielers mit der Mod
        val userId = if (sender.isNotEmpty()) links.userFor(sender) else null
        val member = userId?.let { guild.getMemberById(it) }
        member?.voiceState?.channel?.let { return it }

        // 2. zuletzt benutzter Channel (z.B. Host hat den Voice am Spielende schon verlassen), 3. Among-Us-Channel
        for (channelId in listOfNotNull(lastChannelId, fallbackChannelId)) {
            guild.getChannelById(AudioChannel::class.java, channelId)?.let { return it }
        }
        return null
    }
}
