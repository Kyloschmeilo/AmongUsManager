/*
 * Willkommens- und Abschiedsnachricht: wer dem Server beitritt, wird begrüßt (und dabei angepingt), wer geht, wird
 * kurz verabschiedet. Channel = `Channels.WELCOME`, bei 0 der Systemkanal des Servers. Bots werden übergangen.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.ui.buildLeaveEmbed
import de.amongusmanager.crewbot.ui.buildWelcomeEmbed
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.requests.RestAction
import org.slf4j.LoggerFactory
import java.util.EnumSet

class WelcomeService(
    /** Befehlsname → anklickbarer Befehl (`bot.mentionCommand`). */
    private val mentionCommand: (String) -> String,
    private val channelId: Long = Channels.WELCOME,
) {
    private val log = LoggerFactory.getLogger(WelcomeService::class.java)
    private var warned = false

    suspend fun welcome(member: Member) {
        if (member.user.isBot) return
        val channel = channelOf(member.guild) ?: return
        val embed = buildWelcomeEmbed(member.asMention, member.effectiveAvatarUrl, member.guild.memberCount, mentionCommand("hilfe"))
        send(channel) {
            // nur das neue Mitglied anpingen
            channel.sendMessage(member.asMention).setEmbeds(embed).setAllowedMentions(EnumSet.of(Message.MentionType.USER))
        }
    }

    suspend fun farewell(guild: Guild, user: User, member: Member?) {
        if (user.isBot) return
        val channel = channelOf(guild) ?: return
        send(channel) { channel.sendMessageEmbeds(buildLeaveEmbed(member?.effectiveName ?: user.effectiveName, user.effectiveAvatarUrl)) }
    }

    private fun channelOf(guild: Guild): GuildMessageChannel? {
        val channel = if (channelId != 0L) guild.getChannelById(GuildMessageChannel::class.java, channelId) else guild.systemChannel
        if (channel == null) warnOnce("kein Channel (Channels.WELCOME setzen oder einen Systemkanal einstellen)")
        return channel
    }

    private suspend fun send(channel: GuildMessageChannel, action: () -> RestAction<*>) {
        try {
            action().await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (e.isForbidden()) warnOnce("keine Schreibrechte in #${channel.name}") else log.warn("Willkommensnachricht: {}", e.describe())
        }
    }

    private fun warnOnce(problem: String) {
        if (warned) return
        warned = true
        log.warn("Willkommens-/Abschiedsnachrichten: {}", problem)
    }
}
