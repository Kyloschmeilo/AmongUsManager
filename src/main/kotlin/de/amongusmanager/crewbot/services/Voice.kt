/* Voice-Hilfen: Spieler verschieben und den Status im Channel-Namen anzeigen. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import org.slf4j.LoggerFactory

/** Wie viele Aktionen (Mute, Move, ...) geklappt haben und wie viele nicht. */
data class ActionReport(val succeeded: Int, val failed: Int) {
    fun describe(verb: String): String {
        var text = "✅ **$succeeded** Spieler $verb."
        if (failed > 0) text += "\n⚠️ Bei **$failed** Spieler(n) hat es nicht geklappt (fehlende Rechte?)."
        return text
    }

    companion object {
        fun fromResults(results: List<Boolean>): ActionReport =
            ActionReport(results.count { it }, results.count { !it })
    }
}

/** Voice-Channel, in dem das Mitglied gerade ist (oder null). */
fun voiceChannelOf(member: Member?): AudioChannel? = member?.voiceState?.channel

/** Mitglieder eines Voice-Channels (aus dem Cache – wer gerade drin ist). */
fun membersIn(channel: AudioChannel): List<Member> = channel.members

suspend fun moveMembers(members: List<Member>, target: AudioChannel): ActionReport = coroutineScope {
    val movers = members.filter { it.voiceState?.channel != null && it.voiceState?.channel?.idLong != target.idLong }
    val results = movers.map { member ->
        async {
            try {
                member.guild.moveVoiceMember(member, target).await()
                true
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                false
            }
        }
    }.awaitAll()
    ActionReport.fromResults(results)
}

/**
 * Zeigt im Namen des Among-Us-Channels an, ob gerade Runde oder Meeting ist.
 *
 * Discord erlaubt nur ~2 Umbenennungen pro 10 Minuten. Deshalb läuft immer
 * höchstens ein Umbenennungs-Job, der am Ende den *neuesten* Wunschnamen
 * setzt, statt dass sich viele Anfragen im Rate-Limit stauen.
 */
class VoiceStatusUpdater(
    private val jda: () -> JDA,
    private val channelId: Long,
    private val scope: CoroutineScope,
) {
    companion object {
        const val EMPTY_NAME = "⬜ Amongus⬜"
        const val ROUND_NAME = "🤫RUNDE🤫"
        const val MEETING_NAME = "🎙️MEETING🎙️"
    }

    private val log = LoggerFactory.getLogger(VoiceStatusUpdater::class.java)
    private var desired: String? = null
    private var job: Job? = null

    /** Namen neu berechnen. `muted` überschreibt den Zustand aus dem Cache. */
    fun refresh(muted: Boolean? = null) {
        val channel = jda().getVoiceChannelById(channelId) ?: return
        desired = nameFor(channel, muted)
        if (job?.isActive != true) job = scope.launch { apply(channel) }
    }

    private fun nameFor(channel: VoiceChannel, muted: Boolean?): String {
        val players = channel.members.filter { !it.user.isBot }
        if (players.isEmpty()) return EMPTY_NAME
        val isMuted = muted ?: players.any { it.voiceState?.isGuildMuted == true }
        return if (isMuted) ROUND_NAME else MEETING_NAME
    }

    private suspend fun apply(channel: VoiceChannel) {
        var current = channel.name
        while (true) {
            val target = desired ?: return
            if (target == current) return
            try {
                channel.manager.setName(target).await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.warn("Channel konnte nicht umbenannt werden: {}", e.describe())
                return
            }
            current = target
        }
    }
}
