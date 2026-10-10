/* MVP-Abstimmung: ein Button pro Spieler aus dem Voice-Channel. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import java.time.Duration

/** Kürzt auf höchstens [count] Zeichen (Unicode-Codepoints, wie Python-Slicing). */
fun String.takeCodePoints(count: Int): String {
    if (codePointCount(0, length) <= count) return this
    return substring(0, offsetByCodePoints(0, count))
}

/** Die Abstimmung endet nach der festen Zeit, die im Embed angezeigt wird. */
class MvpVotingView(players: List<Member>, duration: Duration) : SessionView(null, duration, resetTimeoutOnUse = false) {
    companion object {
        const val MAX_CANDIDATES = 25 // Discord erlaubt max. 25 Buttons pro Nachricht
    }

    private val voterIds: Set<Long> = players.map { it.idLong }.toSet()
    private val candidates: List<Member> = players.take(MAX_CANDIDATES)
    private val votes = LinkedHashMap<Long, Long>() // Wähler-ID -> Kandidaten-ID

    val progressText: String get() = "🗳️ ${votes.size}/${voterIds.size} Stimmen abgegeben"

    override fun render(): List<ActionRow> =
        candidates.chunked(5).map { row ->
            ActionRow.of(
                row.map { candidate ->
                    button("vote:${candidate.id}", ButtonStyle.SECONDARY, candidate.effectiveName.takeCodePoints(80)) {
                        castVote(it as ButtonInteractionEvent, candidate)
                    }
                }
            )
        }

    private suspend fun castVote(event: ButtonInteractionEvent, candidate: Member) {
        val voterId = event.user.idLong
        if (voterId !in voterIds) {
            event.notify("🚫 Nur Spieler aus der Runde dürfen abstimmen.", Tone.ERROR)
            return
        }
        if (voterId == candidate.idLong) {
            event.notify("😏 Für dich selbst stimmen gilt nicht!", Tone.WARNING)
            return
        }

        val changed = voterId in votes
        votes[voterId] = candidate.idLong

        val embed = EmbedBuilder(event.message.embeds.first()).setFooter(progressText).build()
        event.editMessageEmbeds(embed).await()
        val verb = if (changed) "🔄 Stimme geändert auf" else "🎯 Du hast gestimmt für"
        event.notify("$verb **${candidate.effectiveName}**!", Tone.SUCCESS)
    }

    /** Gibt (Gewinner-IDs, Stimmen) zurück. Bei Gleichstand gibt es mehrere Gewinner. */
    fun results(): Pair<List<Long>, Int> {
        if (votes.isEmpty()) return emptyList<Long>() to 0
        val counts = LinkedHashMap<Long, Int>()
        for (candidateId in votes.values) counts[candidateId] = (counts[candidateId] ?: 0) + 1
        val top = counts.values.max()
        return counts.filterValues { it == top }.keys.toList() to top
    }
}
