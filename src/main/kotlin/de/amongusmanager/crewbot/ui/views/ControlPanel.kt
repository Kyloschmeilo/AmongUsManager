/* Among-Us-Steuerpult: Runde (Mute), Meeting (Unmute), verschieben, Runde beenden. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.moveMembers
import de.amongusmanager.crewbot.services.voiceChannelOf
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.simpleEmbed
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

object ControlPanel {
    // custom_ids bleiben gleich, damit bereits gepostete Panels weiter funktionieren
    const val MUTE = "persistent_control_panel:mute"
    const val UNMUTE = "persistent_control_panel:unmute"
    const val MOVE = "bt_move"
    const val FINISH = "persistent_control_panel:finish"

    const val IDLE_STATUS = "⏸️ Bereit"

    fun buildEmbed(status: String = IDLE_STATUS, openBets: Int = 0): MessageEmbed =
        EmbedBuilder()
            .setTitle("🎙️ Among Us Steuerung")
            .setDescription(
                "Nutze die Buttons, um die Crew in deinem Voice-Channel zu verwalten.\n\n" +
                    "🤫 **RUNDE** – alle stummschalten\n" +
                    "🔊 **MEETING** – alle wieder entmuten\n" +
                    "🚀 **Verschieben** – alle in den Among-Us-Channel holen\n" +
                    "🏁 **Runde beenden** – Gewinner eintragen, Wetten auswerten, alle entmuten"
            )
            .setColor(Palette.BRAND)
            .field("📊 Status", status, inline = true)
            .field("🎰 Offene Wetten", openBets.toString(), inline = true)
            .build()

    fun rows(): List<ActionRow> = listOf(
        ActionRow.of(
            Button.of(ButtonStyle.DANGER, MUTE, "RUNDE (Mute)").withEmoji(Emoji.fromUnicode("🤫")),
            Button.of(ButtonStyle.SUCCESS, UNMUTE, "MEETING (Unmute)").withEmoji(Emoji.fromUnicode("🔊")),
            Button.of(ButtonStyle.PRIMARY, MOVE, "Alle verschieben").withEmoji(Emoji.fromUnicode("🚀")),
        ),
        ActionRow.of(
            Button.of(ButtonStyle.SECONDARY, FINISH, "Runde beenden").withEmoji(Emoji.fromUnicode("🏁")),
        ),
    )

    /** Aktualisiert Status und Wetten-Zähler im Panel. */
    suspend fun updatePanel(message: Message, bot: CrewBot, status: String) {
        try {
            message.editMessageEmbeds(buildEmbed(status, bot.bets.active.size)).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled() // Panel gelöscht o.ä. – nicht schlimm
        }
    }

    suspend fun onMute(event: ButtonInteractionEvent, bot: CrewBot) = setMute(event, bot, muted = true)

    suspend fun onUnmute(event: ButtonInteractionEvent, bot: CrewBot) = setMute(event, bot, muted = false)

    suspend fun onMove(event: ButtonInteractionEvent, bot: CrewBot) = moveEveryoneToAmongUs(event, event.member, bot)

    suspend fun onFinish(event: ButtonInteractionEvent, bot: CrewBot) {
        val member = event.member ?: return
        if (!member.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
            event.notify("🚫 Nur Hosts dürfen eine Runde beenden.", Tone.ERROR)
            return
        }
        val voice = voiceChannelOf(member)
        if (voice == null) {
            event.notify("⚠️ Du musst im Voice-Channel der Runde sein!", Tone.WARNING)
            return
        }
        val view = RoundFlowView(bot, member, voice.idLong, event.message)
        event.replyEmbeds(view.buildEmbed()).setComponents(view.components()).setEphemeral(true).await()
    }

    private suspend fun setMute(event: ButtonInteractionEvent, bot: CrewBot, muted: Boolean) {
        val channel = voiceChannelOf(event.member)
        if (channel == null) {
            event.notify("⚠️ Du musst in einem Voice-Channel sein!", Tone.WARNING)
            return
        }

        // Entmuten hebt auch „taub“ auf (setzt der Auto-Mute während der Runde)
        val targets = channel.members.filter { member ->
            val voice = member.voiceState ?: return@filter false
            if (muted) !voice.isGuildMuted else voice.isGuildMuted || voice.isGuildDeafened
        }
        if (targets.isEmpty()) {
            event.notify("ℹ️ Alle Spieler sind bereits ${if (muted) "gemutet" else "entmutet"}.")
            return
        }

        val headline = if (muted) "🤫 **Runde gestartet!**" else "🔊 **Meeting gestartet!**"
        event.notify("$headline Multi-Bot-${if (muted) "Mute" else "Unmute"} läuft...")

        val report = if (muted) bot.workers.setMute(targets, true) else bot.workers.release(targets)
        if (channel.idLong == Channels.AMONG_US_VOICE) bot.voiceStatus.refresh(muted)
        updatePanel(event.message, bot, if (muted) "🤫 Runde läuft" else "🎙️ Meeting")

        event.hook.editOriginalEmbeds(
            simpleEmbed(
                "$headline\n${report.describe(if (muted) "gemutet" else "entmutet")}",
                if (muted) Palette.ERROR else Palette.SUCCESS,
            )
        ).await()
    }
}

/** Verschiebt alle aus dem Voice des Nutzers in den Among-Us-Channel (/moveall und Panel-Button). */
suspend fun moveEveryoneToAmongUs(event: IReplyCallback, member: Member?, bot: CrewBot) {
    val source = voiceChannelOf(member)
    val target = bot.jda.getVoiceChannelById(Channels.AMONG_US_VOICE)
    if (source == null) {
        event.notify("❌ Du bist in keinem Voice-Channel!", Tone.ERROR)
        return
    }
    if (target == null) {
        event.notify("❌ Der Among-Us-Channel wurde nicht gefunden.", Tone.ERROR)
        return
    }

    event.deferReply(true).await()
    val report = moveMembers(source.members, target)
    event.notify(report.describe("nach ${target.asMention} verschoben"), Tone.SUCCESS)
}
