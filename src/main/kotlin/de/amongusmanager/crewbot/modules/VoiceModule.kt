/* Voice & Steuerung: Control Panel, Verschieben, Lobby-Code. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.editMemberVoice
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.views.ControlPanel
import de.amongusmanager.crewbot.ui.views.moveEveryoneToAmongUs
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.guild.voice.GenericGuildVoiceEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory

class VoiceModule(private val bot: CrewBot) : CrewModule("Voice & Steuerung", "🎙️") {
    private val log = LoggerFactory.getLogger(VoiceModule::class.java)

    override val commands = listOf(
        SlashCommand("panel", "Postet das Among Us Control Panel", Permission.VOICE_MUTE_OTHERS) { event ->
            val embed = ControlPanel.buildEmbed(openBets = bot.bets.active.size)
            event.channel.sendMessageEmbeds(embed).setComponents(ControlPanel.rows()).await()
            event.notify("✅ Panel gesendet.", Tone.SUCCESS)
        },
        SlashCommand("moveall", "Verschiebt alle aus deinem Voice in den Among-Us-Channel") { event ->
            moveEveryoneToAmongUs(event, event.author, bot)
        },
        SlashCommand(
            "code", "Teilt den Lobby-Code im Code-Channel",
            options = listOf(
                OptionData(OptionType.STRING, "code", "Der Lobby-Code aus Among Us (z.B. ABCDEF)", true)
                    .setRequiredLength(1, 20)
            ),
        ) { event ->
            val code = event.stringOption("code")!!
            val channel = bot.jda.getTextChannelById(Channels.CODES)
            if (channel == null) {
                event.notify("❌ Code-Channel wurde nicht gefunden.", Tone.ERROR)
                return@SlashCommand
            }
            channel.sendMessage("🎮 **Code:** `${code.uppercase()}` - Host: ${event.author.asMention}").await()
            event.notify("✅ Code in ${channel.asMention} geteilt.", Tone.SUCCESS)
        },
    )

    override suspend fun onVoice(event: GenericGuildVoiceEvent) {
        val member = event.member
        val voice = event.voiceState

        // 1. Auto-Unmute: wer den Unmute-Channel neu betritt, wird entmutet (und ggf. nicht mehr taub)
        if (event is GuildVoiceUpdateEvent) {
            val joined = event.channelJoined
            if (joined != null && joined.idLong == Channels.AUTO_UNMUTE_VOICE &&
                (voice.isGuildMuted || voice.isGuildDeafened)
            ) {
                try {
                    editMemberVoice(event.jda, event.guild.idLong, member.idLong, mute = false, deafen = false)
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                    log.warn("Konnte {} nicht entmuten: {}", member.effectiveName, e.describe())
                }
            }
        }

        // 2. Status im Namen des Among-Us-Channels aktualisieren
        val touched = if (event is GuildVoiceUpdateEvent) {
            listOfNotNull(event.channelLeft?.idLong, event.channelJoined?.idLong)
        } else {
            listOfNotNull(voice.channel?.idLong)
        }
        if (Channels.AMONG_US_VOICE in touched) bot.voiceStatus.refresh()
    }
}
