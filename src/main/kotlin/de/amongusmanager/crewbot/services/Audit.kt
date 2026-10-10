/* Audit-Log: protokolliert Admin- und Host-Aktionen im Log-Channel. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.safeBuild
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import org.slf4j.LoggerFactory
import java.time.Instant

/** Wer etwas getan hat: Name, Avatar und ID (Mitglied, Nutzer oder der Bot selbst). */
data class Actor(val displayName: String, val avatarUrl: String, val tag: String, val id: Long) {
    companion object {
        fun of(member: Member): Actor = Actor(member.effectiveName, member.effectiveAvatarUrl, tagOf(member.user), member.idLong)
        fun of(user: User): Actor = Actor(user.effectiveName, user.effectiveAvatarUrl, tagOf(user), user.idLong)

        /** Wie `str(user)` im alten Bot: neuer Benutzername bzw. name#1234 bei alten Accounts/Bots. */
        fun tagOf(user: User): String =
            if (user.discriminator == "0000" || user.discriminator == "0") user.name else "${user.name}#${user.discriminator}"
    }
}

class AuditLog(private val jda: () -> JDA, private val channelId: Long) {
    private val log = LoggerFactory.getLogger(AuditLog::class.java)

    suspend fun log(actor: Actor, title: String, text: String, color: Int = Palette.BLURPLE) {
        val channel = jda().getChannelById(GuildMessageChannel::class.java, channelId)
        if (channel == null) {
            log.warn("Log-Channel {} nicht gefunden", channelId)
            return
        }
        try {
            val embed = EmbedBuilder()
                .setTitle("📝 $title")
                .setDescription(text)
                .setColor(color)
                .setAuthor(actor.displayName, null, actor.avatarUrl)
                .setFooter("Ausgeführt von ${actor.tag} • ID ${actor.id}")
                .setTimestamp(Instant.now())
            // Erwähnungen im Log sollen niemanden anpingen
            channel.sendMessageEmbeds(safeBuild(embed)).setAllowedMentions(emptySet()).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Audit-Log konnte nicht gesendet werden: {}", e.describe())
        }
    }
}
