/* Runden-Assistent: Runde in 2 Schritten beenden (Sieger-Team -> Impostor wählen). */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.RoundOutcome
import de.amongusmanager.crewbot.services.RoundService
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.safeBuild
import de.amongusmanager.crewbot.ui.simpleEmbed
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import java.time.Duration

// ==========================================
// AUSWERTUNG (auch von /crew-win und /impostor-win genutzt)
// ==========================================

fun roundResultEmbed(outcome: RoundOutcome): MessageEmbed {
    val team = outcome.winningTeam ?: Team.CREW // manuelle Runden haben immer ein Team (Sondersiege nur bei Mod-Runden)
    val embed = EmbedBuilder()
        .setTitle(if (team == Team.IMPOSTOR) "🔪 IMPOSTOR SIEG" else "🚀 CREWMATE SIEG")
        .setColor(Palette.forTeam(team))

    val winnerLines = outcome.winners.map { (userId, result) ->
        var line = "• <@$userId> (+${outcome.xpOf(userId)} XP)"
        if (result.leveledUp) line += " 🎊 **Level ${result.stats.level}!**"
        line
    }
    embed.field("🏆 Gewinner", winnerLines.joinToString("\n").ifEmpty { "Keine Gewinner erfasst." }, inline = false)

    val betLines = outcome.bets.map { (bet, result) ->
        if (result == null) {
            "❌ <@${bet.userId}> verliert den Einsatz von *${bet.amount} XP*."
        } else {
            var line = "✅ <@${bet.userId}> gewinnt **${bet.payout} XP**!"
            if (result.leveledUp) line += " 🎊 **Level ${result.stats.level}!**"
            line
        }
    }
    embed.field(
        "🎰 Wettbüro",
        betLines.joinToString("\n").ifEmpty { "ℹ️ In dieser Runde waren keine Wetten aktiv." },
        inline = false,
    )
    return safeBuild(embed)
}

/** Wertet die Runde aus, schreibt ins Audit-Log und liefert das Ergebnis-Embed. */
suspend fun evaluateRound(bot: CrewBot, host: Member, team: Team, winners: List<Member>): MessageEmbed {
    val outcome = bot.rounds.finish(host.guild, team, winners.map { it.idLong })
    val names = outcome.winners.joinToString(", ") { "<@${it.first}>" }.ifEmpty { "–" }
    bot.audit.log(
        Actor.of(host),
        "Runde ausgewertet",
        "**Sieger:** ${team.label}\n**Gewinner:** $names\n**Ausgewertete Wetten:** ${outcome.bets.size}",
        Palette.forTeam(team),
    )
    return roundResultEmbed(outcome)
}

// ==========================================
// ASSISTENT
// ==========================================

class RoundFlowView(
    private val bot: CrewBot,
    private val host: Member,
    private val voiceId: Long,
    private val panelMessage: Message?,
) : SessionView(host.idLong, Duration.ofSeconds(300)) {
    private var team: Team? = null
    private var impostors: List<Member> = emptyList()

    private val voice: AudioChannel?
        get() = host.guild.getChannelById(AudioChannel::class.java, voiceId)

    private val winners: List<Member>
        get() = team?.let { RoundService.winnersFor(it, impostors, voice?.members ?: emptyList()) } ?: emptyList()

    fun buildEmbed(): MessageEmbed {
        val embed = EmbedBuilder().setTitle("🏁 Runde beenden").setColor(Palette.BRAND)
        val currentTeam = team
        if (currentTeam == null) {
            embed.setDescription("**Schritt 1/2:** Welches Team hat gewonnen?")
            return embed.build()
        }

        embed.setDescription("**Sieger:** ${currentTeam.label}\n**Schritt 2/2:** Wer war Impostor?")
        embed.field(
            "🔪 Impostor",
            impostors.joinToString(", ") { it.asMention }.ifEmpty { "*noch niemand gewählt*" },
            inline = false,
        )
        val winners = winners
        embed.field(
            "🏆 Bekommen einen Sieg (${winners.size})",
            winners.joinToString(", ") { it.asMention }.ifEmpty { "–" },
            inline = false,
        )
        return safeBuild(embed)
    }

    override fun render(): List<ActionRow> {
        if (team == null) {
            return listOf(
                ActionRow.of(
                    button("crew", ButtonStyle.SUCCESS, "Crewmates", "🟢") { pick(it as ButtonInteractionEvent, Team.CREW) },
                    button("impostor", ButtonStyle.DANGER, "Impostor", "🔴") { pick(it as ButtonInteractionEvent, Team.IMPOSTOR) },
                    button("cancel", ButtonStyle.SECONDARY, "Abbrechen") { event ->
                        stop()
                        (event as ButtonInteractionEvent).editMessageEmbeds(simpleEmbed("Abgebrochen.", Palette.INFO))
                            .setComponents().await()
                    },
                )
            )
        }

        val select = EntitySelectMenu.create(
            key("impostors") { event ->
                val selected = (event as EntitySelectInteractionEvent).mentions.members
                impostors = selected
                refresh(event)
            },
            EntitySelectMenu.SelectTarget.USER,
        )
            .setPlaceholder("Wer war Impostor? (1–3 Spieler)")
            .setRequiredRange(1, 3)
            // bleibt nach Neu-Zeichnen ausgewählt
            .setDefaultValues(impostors.map { EntitySelectMenu.DefaultValue.user(it.idLong) })
            .build()

        return listOf(
            ActionRow.of(select),
            ActionRow.of(
                button("confirm", ButtonStyle.SUCCESS, "Auswerten", "✅", disabled = impostors.isEmpty()) {
                    confirm(it as ButtonInteractionEvent)
                },
                button("back", ButtonStyle.SECONDARY, "Zurück") { event ->
                    team = null
                    refresh(event as ButtonInteractionEvent)
                },
            ),
        )
    }

    // ---------- Callbacks ----------

    private suspend fun pick(event: ButtonInteractionEvent, picked: Team) {
        team = picked
        refresh(event)
    }

    private suspend fun confirm(event: ButtonInteractionEvent) {
        val currentTeam = team ?: return
        stop()
        event.editMessageEmbeds(simpleEmbed("⏳ Runde wird ausgewertet...", Palette.INFO)).setComponents().await()

        val result = evaluateRound(bot, host, currentTeam, winners)
        event.channel.sendMessageEmbeds(result).await()

        // Runde vorbei -> alle wieder entmuten (und „taub“ vom Auto-Mute aufheben)
        val report = bot.workers.release(voice?.members ?: emptyList())
        if (voiceId == Channels.AMONG_US_VOICE) bot.voiceStatus.refresh(false)

        panelMessage?.let { ControlPanel.updatePanel(it, bot, "🏁 Runde beendet – bereit für die nächste") }

        event.hook.editOriginalEmbeds(
            simpleEmbed("✅ Runde ausgewertet!\n${report.describe("entmutet")}", Palette.SUCCESS)
        ).await()
    }

    private suspend fun refresh(event: net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent) {
        event.editMessageEmbeds(buildEmbed()).setComponents(components()).await()
    }
}
