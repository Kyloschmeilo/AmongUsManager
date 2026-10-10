/* Runden: Wetten, Sieg-Auswertung und MVP-Voting. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.discordTimestamp
import de.amongusmanager.crewbot.core.intOption
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.BetError
import de.amongusmanager.crewbot.services.RoundService
import de.amongusmanager.crewbot.services.buildRecap
import de.amongusmanager.crewbot.services.voiceChannelOf
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.mvpSuggestionText
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.views.MvpVotingView
import de.amongusmanager.crewbot.ui.views.evaluateRound
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.time.Duration
import java.time.Instant

class RoundsModule(private val bot: CrewBot) : CrewModule("Runden & Wetten", "🎰") {

    override val commands = listOf(
        // ==========================================
        // WETTEN
        // ==========================================
        SlashCommand(
            "bet", "Wette XP auf den Ausgang der aktuellen Runde",
            options = listOf(
                OptionData(OptionType.STRING, "team", "Wer gewinnt?", true)
                    .addChoices(Team.entries.map { Command.Choice(it.label, it.value) }),
                OptionData(OptionType.INTEGER, "einsatz", "Wie viele XP? (max. ${Rules.MAX_BET})", true)
                    .setRequiredRange(1, Rules.MAX_BET.toLong()),
            ),
        ) { event ->
            val team = Team.fromValue(event.stringOption("team")) ?: Team.CREW
            val bet = try {
                bot.bets.place(event.user.idLong, team, event.intOption("einsatz")!!)
            } catch (e: BetError) {
                event.notify("❌ ${e.message}", Tone.ERROR)
                return@SlashCommand
            }
            event.notify(
                "🎰 Wette platziert! Du setzt **${bet.amount} XP** auf einen Sieg der **${bet.team.label}**.\n" +
                    "Bei einem Sieg bekommst du **${bet.payout} XP** zurück.",
                Tone.SUCCESS,
            )
        },
        SlashCommand(
            "runde-abbrechen", "Bricht die Runde ab und erstattet alle Wetteinsätze", Permission.VOICE_MUTE_OTHERS,
        ) { event ->
            val refunded = bot.bets.refundAll()
            if (refunded.isEmpty()) {
                event.notify("ℹ️ Es sind keine Wetten aktiv.")
                return@SlashCommand
            }
            val lines = refunded.map { "↩️ <@${it.userId}> bekommt **${it.amount} XP** zurück." }
            val embed = EmbedBuilder()
                .setTitle("🛑 Runde abgebrochen")
                .setDescription(lines.joinToString("\n"))
                .setColor(Palette.WARNING)
                .build()
            event.replyEmbeds(embed).await()
            bot.audit.log(
                Actor.of(event.author), "Runde abgebrochen", "${refunded.size} Wetteinsätze erstattet.", Palette.WARNING,
            )
        },

        // ==========================================
        // RUNDENENDE
        // ==========================================
        SlashCommand(
            "impostor-win", "Runde endet mit Impostor-Sieg (XP, Siege & Wetten)", Permission.VOICE_MUTE_OTHERS,
            options = listOf(
                OptionData(OptionType.USER, "impostor1", "Impostor dieser Runde", true),
                OptionData(OptionType.USER, "impostor2", "Zweiter Impostor (optional)", false),
            ),
        ) { event ->
            val winners = listOfNotNull(event.memberOption("impostor1"), event.memberOption("impostor2"))
            finishRound(event, Team.IMPOSTOR, winners)
        },
        SlashCommand(
            "crew-win", "Runde endet mit Crew-Sieg (XP, Siege & Wetten)", Permission.VOICE_MUTE_OTHERS,
            options = listOf(
                OptionData(OptionType.USER, "impostor1", "Impostor dieser Runde (bekommt keinen Sieg)", false),
                OptionData(OptionType.USER, "impostor2", "Zweiter Impostor (optional)", false),
            ),
        ) { event ->
            // Gewinner = alle im Voice des Hosts außer den Impostorn
            val voice = voiceChannelOf(event.author)
            val impostors = listOfNotNull(event.memberOption("impostor1"), event.memberOption("impostor2"))
            val winners = RoundService.winnersFor(Team.CREW, impostors, voice?.members ?: emptyList())
            finishRound(event, Team.CREW, winners)
        },

        // ==========================================
        // MVP
        // ==========================================
        SlashCommand(
            "mvp-voting", "Abstimmung für den MVP des Abends im Voice-Channel", Permission.VOICE_MUTE_OTHERS,
        ) { event -> mvpVoting(event) },
    )

    private suspend fun finishRound(event: SlashCommandInteractionEvent, team: Team, winners: List<Member>) {
        event.deferReply().await()
        val embed = evaluateRound(bot, event.author, team, winners)
        event.hook.sendMessageEmbeds(embed).await()
    }

    private suspend fun mvpVoting(event: SlashCommandInteractionEvent) {
        val voice = voiceChannelOf(event.author)
        if (voice == null) {
            event.notify("❌ Du musst im Voice-Channel sein, um das Voting zu starten!", Tone.ERROR)
            return
        }
        val players = voice.members.filter { !it.user.isBot }
        if (players.size < 2) {
            event.notify("❌ Es müssen mindestens 2 Spieler im Voice sein!", Tone.ERROR)
            return
        }

        val duration = Duration.ofSeconds(Rules.MVP_VOTING_SECONDS)
        val view = MvpVotingView(players, duration)
        val now = Instant.now()
        val endsAt = now + duration
        val tonight = bot.roundHistory.rounds(since = now - Duration.ofHours(Rules.RECAP_HOURS))
        val embed = EmbedBuilder()
            .setTitle("👑 MVP OF THE NIGHT VOTING 👑")
            .setDescription(
                "Wer hat heute die krassesten Plays gemacht, die härtesten Lügen entlarvt oder als Impostor alle rasiert?" +
                    mvpSuggestionText(buildRecap(tonight)) +
                    "\n\n⏳ Voting endet ${endsAt.discordTimestamp("R")}"
            )
            .setColor(Palette.PURPLE)
            .setFooter(view.progressText)
            .build()
        event.replyEmbeds(embed).setComponents(view.components()).await()

        view.await()
        event.hook.editOriginalEmbeds(mvpResult(event.guild!!, view)).setComponents().await()
    }

    private suspend fun mvpResult(guild: Guild, view: MvpVotingView): MessageEmbed {
        val embed = EmbedBuilder().setTitle("👑 MVP OF THE NIGHT 👑").setColor(Palette.GOLD)
        val (winnerIds, votes) = view.results()
        if (winnerIds.isEmpty()) {
            embed.setDescription("⏱️ **Das Voting ist beendet.**\nEs wurden leider keine Stimmen abgegeben.")
            embed.setColor(Palette.PURPLE)
            return embed.build()
        }

        val lines = winnerIds.map { userId ->
            val result = bot.levels.award(guild, userId, Rules.MVP_XP)
            var line = "🏆 <@$userId> mit **$votes Stimme(n)** → **+${Rules.MVP_XP} XP**"
            if (result.leveledUp) line += " 🎊 Level ${result.stats.level}!"
            line
        }

        val headline = if (winnerIds.size > 1) "Gleichstand! Die MVPs des Abends sind:" else "Der **MVP des Abends** ist:"
        embed.setDescription("⏱️ **Das Voting ist beendet!**\n\n$headline\n" + lines.joinToString("\n"))
        if (winnerIds.size == 1) guild.getMemberById(winnerIds[0])?.let { embed.setThumbnail(it.effectiveAvatarUrl) }
        embed.setFooter(view.progressText)
        return embed.build()
    }
}
