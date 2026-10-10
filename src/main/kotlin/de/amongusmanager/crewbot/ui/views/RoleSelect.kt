/* Rollen-Erklärungen per Dropdown, inklusive Log im Rollen-Log-Channel. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.content.AmongUsRole
import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.slf4j.LoggerFactory

object RoleSelect {
    const val CUSTOM_ID = "persistent_role_dropdown"
    private val log = LoggerFactory.getLogger(RoleSelect::class.java)

    fun row(): ActionRow = ActionRow.of(
        StringSelectMenu.create(CUSTOM_ID)
            .setPlaceholder("Wähle eine Rolle aus...")
            .addOptions(
                ROLES.values.map { role ->
                    SelectOption.of(role.name, role.name)
                        .withEmoji(Emoji.fromUnicode(role.emoji))
                        .withDescription("Team ${role.team.label}")
                }
            )
            .build()
    )

    suspend fun onSelect(event: StringSelectInteractionEvent) {
        val role = ROLES[event.values.firstOrNull()] ?: error("Unbekannte Rolle: ${event.values}")
        val embed = EmbedBuilder()
            .setTitle("${role.emoji} ${role.name}")
            .setDescription(role.description)
            .setColor(Palette.forTeam(role.team))
            .field("Team", role.team.label)
            .build()
        event.replyEmbeds(embed).setEphemeral(true).await()
        logSelection(event, role)
    }

    private suspend fun logSelection(event: StringSelectInteractionEvent, role: AmongUsRole) {
        val channel = event.jda.getChannelById(GuildMessageChannel::class.java, Channels.ROLE_LOG) ?: return
        val user = event.user
        val displayName = event.member?.effectiveName ?: user.effectiveName
        val embed = EmbedBuilder()
            .setTitle("🔍 Rollen-Auswahl")
            .setDescription("${user.asMention} ($displayName) hat sich die Rolle **${role.name}** anzeigen lassen.")
            .setColor(Palette.INFO)
            .setFooter("User-ID: ${user.id}")
            .build()
        try {
            channel.sendMessageEmbeds(embed).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Rollen-Log konnte nicht gesendet werden: {}", e.describe())
        }
    }
}
