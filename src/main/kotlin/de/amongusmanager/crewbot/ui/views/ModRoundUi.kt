/* Anzeige automatisch ausgewerteter Mod-Runden + „Zurücksetzen“-Button. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.content.roleLabel
import de.amongusmanager.crewbot.content.specialRoleFor
import de.amongusmanager.crewbot.content.torRoleLabel
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.AuditLog
import de.amongusmanager.crewbot.services.ModDeath
import de.amongusmanager.crewbot.services.ModPlayer
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundError
import de.amongusmanager.crewbot.services.ModRoundResult
import de.amongusmanager.crewbot.services.ModRoundService
import de.amongusmanager.crewbot.services.roundHighlights
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.fitLines
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.safeBuild
import de.amongusmanager.crewbot.ui.simpleEmbed
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import java.time.Duration
import java.time.Instant

val REASON_TEXTS = mapOf(
    "CrewmatesByVote" to "Alle Impostor rausgevotet",
    "CrewmatesByTask" to "Alle Tasks erledigt",
    "ImpostorsByVote" to "Crew hat sich selbst rausgevotet",
    "ImpostorsByKill" to "Alle Crewmates getötet",
    "ImpostorsBySabotage" to "Sabotage nicht rechtzeitig repariert",
    "ImpostorDisconnect" to "Impostor hat das Spiel verlassen",
    "CrewmateDisconnect" to "Crewmates haben das Spiel verlassen",
)

/** Eigene Spielenden von The Other Roles (Sondersiege). */
val TOR_REASON_TEXTS = mapOf(
    "LoversWin" to "Das Liebespaar hat bis zum Schluss überlebt",
    "TeamJackalWin" to "Team Jackal hat übernommen",
    "MiniLose" to "Das Mini wurde rausgewählt, bevor es erwachsen war",
    "JesterWin" to "Der Jester wurde rausgewählt",
    "ArsonistWin" to "Alle wurden angezündet",
    "VultureWin" to "Der Vulture hat genug Leichen gefressen",
    "LawyerSoloWin" to "Der Klient des Lawyers wurde rausgewählt",
)
private val DEATH_ICONS = mapOf(
    "kill" to "💀", "ejected" to "🚪", "disconnect" to "🔌",
    // The Other Roles
    "guess" to "🎯", "jailed" to "⛓️", "suicide" to "🩸",
)
private const val OTHER_DEATH_ICON = "☠️"

/** Rolle eines Spielers: mit The Other Roles deren Rolle (+ Modifikatoren, Rollenwechsel), sonst die des Spiels. */
fun playerRoleLabel(p: ModPlayer): String {
    fun label(nameKey: String?): String? = nameKey?.let { torRoleLabel(it) }
    val madmate = "madmate" in p.modifiers
    var role = label(p.modRole)?.let { if (madmate) "$it (Madmate)" else it }
        ?: if (madmate) label("madmate")!! else roleLabel(p.role)
    label(p.modStartRole)?.let { role = "$it → $role" }
    val modifiers = p.modifiers.filter { it != "madmate" }.mapNotNull { label(it) }
    return if (modifiers.isEmpty()) role else "$role + ${modifiers.joinToString(", ")}"
}

/** Titel eines Sondersiegs (The Other Roles), z.B. „🤡 JESTER SIEG“. */
fun specialWinTitle(endReason: String): String = when (endReason) {
    "LoversWin" -> "💞 SIEG DES LIEBESPAARS"
    "MiniLose" -> "🧒 ALLE VERLIEREN"
    "TeamJackalWin" -> "🐺 TEAM JACKAL SIEG"
    else -> {
        // „JesterWin“ -> jester, „LawyerSoloWin“ -> lawyer, „PlagueDoctorWin“ -> plagueDoctor
        val id = endReason.removeSuffix("Win").removeSuffix("Solo").replaceFirstChar { it.lowercase() }
        specialRoleFor(id)?.let { "${it.emoji} ${it.name.uppercase()} SIEG" } ?: "🟣 SONDERSIEG"
    }
}

/** Was eine Rollen-Aktion war, z.B. „hat *Rot* erschossen“. */
private fun actionText(death: ModDeath, round: ModRound, mention: (ModPlayer) -> String): String {
    val victim = round.player(death.victim)?.let(mention) ?: "jemanden"
    return when (death.reason) {
        "Guess" -> "🎯 hat $victim richtig erraten"
        "Jailed" -> "⛓️ hat $victim hingerichtet"
        else -> "🔫 hat $victim ausgeschaltet"
    }
}
const val UNDONE_PREFIX = "↩️ ZURÜCKGESETZT • "
private const val LINK_HINT = "\n→ Mit `/verknuepfen` den Among-Us-Friendcode hinterlegen!"

private fun fit(lines: List<String>, empty: String = "–", limit: Int = MessageEmbed.VALUE_MAX_LENGTH) =
    fitLines(lines, empty, limit)

fun buildModRoundEmbed(result: ModRoundResult, now: Instant = Instant.now()): MessageEmbed {
    val round = result.round
    val (title, color) = when (round.winningTeam) {
        Team.IMPOSTOR -> "🔪 IMPOSTOR SIEG" to Palette.forTeam(Team.IMPOSTOR)
        Team.CREW -> "🚀 CREWMATE SIEG" to Palette.forTeam(Team.CREW)
        null -> if (round.neutralWin) specialWinTitle(round.endReason) to Palette.PURPLE
            else "❔ RUNDE OHNE KLAREN SIEGER" to Palette.INFO
    }

    val total = round.durationSeconds.toInt()
    val minutes = Math.floorDiv(total, 60)
    val seconds = Math.floorMod(total, 60)
    val reason = REASON_TEXTS[round.endReason] ?: TOR_REASON_TEXTS[round.endReason] ?: round.endReason
    val embed = EmbedBuilder()
        .setTitle(title)
        .setDescription("🗺️ **${round.map}** • ⏱️ $minutes:${seconds.toString().padStart(2, '0')} min • $reason")
        .setColor(color)

    val impostors = round.players.filter { it.team == Team.IMPOSTOR.value }
    val neutrals = round.players.filter { it.isNeutral }
    val crew = round.players.filter { it.team != Team.IMPOSTOR.value && !it.isNeutral }

    fun death(p: ModPlayer): String = (DEATH_ICONS[p.deathCause] ?: if (p.deathCause != null) OTHER_DEATH_ICON else null)
        ?.let { " $it" } ?: ""
    fun kills(p: ModPlayer): String = if (p.kills > 0) " • ${p.kills} Kill(s)" else ""

    embed.field(
        "🔪 Impostor",
        fit(impostors.map { "${result.mention(it)} – ${playerRoleLabel(it)} • ${it.kills} Kill(s)${death(it)}" }),
        inline = false,
    )
    embed.field(
        "🚀 Crew",
        fit(crew.map { "${result.mention(it)} – ${playerRoleLabel(it)} • 🧰 ${it.tasksCompleted}/${it.tasksTotal}${kills(it)}${death(it)}" }),
        inline = false,
    )
    if (neutrals.isNotEmpty()) {
        embed.field(
            "🟣 Neutral",
            fit(neutrals.map { "${result.mention(it)} – ${playerRoleLabel(it)}${kills(it)}${death(it)}" }),
            inline = false,
        )
    }

    // Highlights
    val highlights = ArrayList<String>()
    highlights.add("🧰 Tasks: **${crew.sumOf { it.tasksCompleted }}/${crew.sumOf { it.tasksTotal }}**")
    val reports = round.meetings.count { it.body != null }
    highlights.add("📢 Meetings: **${round.meetings.size}** (${reports}× Leiche gemeldet)")
    for (meeting in round.meetings) {
        val exiled = round.player(meeting.exiled) ?: continue
        val verdict = when {
            exiled.team == Team.IMPOSTOR.value -> "✅ war Impostor"
            exiled.isNeutral -> "🟣 war neutral (${playerRoleLabel(exiled)})"
            else -> "❌ war unschuldig"
        }
        highlights.add("🚪 ${result.mention(exiled)} rausgewählt – $verdict")
    }
    highlights.addAll(roundHighlights(round, result::mention))
    embed.field("📊 Highlights", fit(highlights), inline = false)

    // Belohnungen
    val outcome = result.outcome
    if (outcome != null) {
        val rewards = outcome.winners.map { (userId, xpResult) ->
            var line = "<@$userId> +${outcome.xpOf(userId)} XP"
            if (xpResult.leveledUp) line += " 🎊 Level ${xpResult.stats.level}!"
            line
        }
        // Rollen-Aktionen: „Level X!“ nur, wenn der Spieler nicht schon oben als Gewinner steht
        val shown = outcome.winners.map { it.first }.toMutableSet()
        val bonusLines = outcome.bonuses.map { bonus ->
            var line = "<@${bonus.userId}> +${bonus.xp} XP ${actionText(bonus.death, round, result::mention)}"
            if (bonus.result.leveledUp && shown.add(bonus.userId)) line += " 🎊 Level ${bonus.result.stats.level}!"
            line
        }
        embed.field("🏆 Belohnungen", fit(rewards + bonusLines, "Keine verknüpften Gewinner"), inline = false)
        if (outcome.bets.isNotEmpty()) {
            val betLines = outcome.bets.map { (bet, res) ->
                if (res != null) "✅ <@${bet.userId}> gewinnt **${bet.payout} XP**" else "❌ <@${bet.userId}> verliert ${bet.amount} XP"
            }
            embed.field("🎰 Wettbüro", fit(betLines), inline = false)
        }
    }

    if (result.achievements.isNotEmpty()) {
        val lines = result.achievements.map { unlock ->
            var line = "<@${unlock.userId}> ${unlock.achievement.label} (+${unlock.achievement.xp} XP)"
            if (unlock.result.leveledUp) line += " 🎊 Level ${unlock.result.stats.level}!"
            line
        }
        embed.field("🏅 Erfolge freigeschaltet", fit(lines), inline = false)
    }

    if (result.unlinked.isNotEmpty()) {
        embed.field(
            "⚠️ Nicht verknüpft (bekommen keine XP)",
            fit(result.unlinked.map { "*${it.name}*" }, limit = MessageEmbed.VALUE_MAX_LENGTH - LINK_HINT.length) + LINK_HINT,
            inline = false,
        )
    }

    var footer = "Automatisch ausgewertet per CrewStats-Mod"
    round.mod?.let { footer += " • $it" }
    if (round.lobbyCode.isNotEmpty()) footer += " • Lobby ${round.lobbyCode}"
    embed.setFooter(footer)
    embed.setTimestamp(now)
    return safeBuild(embed)
}

// ==========================================
// ZURÜCKSETZEN
// ==========================================

/** Das Runden-Embed nach dem Zurücksetzen: grau, Hinweis im Titel und wer es war. */
fun undoneRoundEmbed(original: MessageEmbed, byName: String): MessageEmbed =
    safeBuild(
        EmbedBuilder(original)
            .setTitle(UNDONE_PREFIX + (original.title ?: ""))
            .setColor(Palette.DARK_GREY)
            .setFooter("Zurückgesetzt von $byName • ${original.footer?.text ?: ""}")
    )

object UndoRoundButton {
    const val PREFIX = "crewstats:undo:"
    val PATTERN = Regex("crewstats:undo:([0-9a-f]+)")

    /** Dauerhafter Button – funktioniert über die feste custom_id auch nach Bot-Neustarts. */
    fun row(gameId: String, disabled: Boolean = false): ActionRow =
        ActionRow.of(
            Button.of(ButtonStyle.DANGER, "$PREFIX$gameId", "Zurücksetzen")
                .withEmoji(Emoji.fromUnicode("↩️"))
                .withDisabled(disabled)
        )

    suspend fun onClick(event: ButtonInteractionEvent, gameId: String, modRounds: ModRoundService, audit: AuditLog) {
        val member = event.member
        if (member == null || !member.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
            event.notify("🚫 Nur Hosts dürfen Runden zurücksetzen.", Tone.ERROR)
            return
        }
        val confirm = UndoConfirmView(member.idLong, gameId, event.message, modRounds, audit)
        event.replyEmbeds(
            simpleEmbed(
                "⚠️ **Runde wirklich zurücksetzen?**\nAlle XP, Siege, Wetten-Auszahlungen und" +
                    " Statistiken dieser Runde werden wieder entfernt. Offene Wetten werden wiederhergestellt.",
                Palette.WARNING,
            )
        ).setComponents(confirm.components()).setEphemeral(true).await()
    }
}

class UndoConfirmView(
    ownerId: Long,
    private val gameId: String,
    private val summary: Message,
    private val modRounds: ModRoundService,
    private val audit: AuditLog,
) : SessionView(ownerId, Duration.ofSeconds(60)) {

    override fun render(): List<ActionRow> = listOf(
        ActionRow.of(
            button("confirm", ButtonStyle.DANGER, "Ja, zurücksetzen", "↩️") { confirm(it as ButtonInteractionEvent) },
            button("cancel", ButtonStyle.SECONDARY, "Abbrechen") { event ->
                stop()
                (event as ButtonInteractionEvent).editMessageEmbeds(simpleEmbed("Abgebrochen.", Palette.INFO))
                    .setComponents().await()
            },
        )
    )

    private suspend fun confirm(event: ButtonInteractionEvent) {
        stop()
        try {
            modRounds.undo(gameId)
        } catch (e: ModRoundError) {
            event.editMessageEmbeds(simpleEmbed("❌ ${e.message}", Palette.ERROR)).setComponents().await()
            return
        }
        event.deferEdit().await()

        val original = summary.embeds.firstOrNull()
        if (original != null) {
            val embed = undoneRoundEmbed(original, event.member?.effectiveName ?: event.user.effectiveName)
            try {
                summary.editMessageEmbeds(embed).setComponents(UndoRoundButton.row(gameId, disabled = true)).await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
            }
        }

        val actor = event.member?.let { Actor.of(it) } ?: Actor.of(event.user)
        audit.log(
            actor,
            "Mod-Runde zurückgesetzt",
            "[Zur Runde](${summary.jumpUrl}) – XP, Siege, Wetten und Statistiken wurden entfernt.",
            Palette.WARNING,
        )
        event.hook.editOriginalEmbeds(simpleEmbed("✅ Runde wurde zurückgesetzt.", Palette.SUCCESS))
            .setComponents().await()
    }
}
