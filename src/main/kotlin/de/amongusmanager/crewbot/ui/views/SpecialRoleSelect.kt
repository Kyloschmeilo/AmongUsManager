/* Erklärungen der Mod-Rollen (/special-rolle) per Dropdown; Discord erlaubt höchstens 25 Einträge pro Menü. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.SpecialRole
import de.amongusmanager.crewbot.content.SpecialTeam
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

object SpecialRoleSelect {
    const val PREFIX = "special_role_dropdown:"

    /** Ein Menü für die Crew, eins für Impostor, Neutrale und Modifikatoren. */
    val MENUS: List<Triple<String, String, List<SpecialRole>>> = listOf(
        Triple("crew", "🟢 Crewmate-Rolle wählen...", SPECIAL_ROLES.values.filter { it.team == SpecialTeam.CREW }),
        Triple("other", "🔴 Impostor-, Neutral- oder Zusatz-Rolle wählen...", SPECIAL_ROLES.values.filter { it.team != SpecialTeam.CREW }),
    )

    fun rows(): List<ActionRow> = MENUS.map { (key, placeholder, roles) ->
        ActionRow.of(
            StringSelectMenu.create(PREFIX + key)
                .setPlaceholder(placeholder)
                .addOptions(
                    roles.map { role ->
                        SelectOption.of(role.name, role.name)
                            .withEmoji(Emoji.fromUnicode(role.emoji))
                            .withDescription(role.team.label)
                    }
                )
                .build()
        )
    }

    fun color(team: SpecialTeam): Int = when (team) {
        SpecialTeam.CREW -> Palette.SUCCESS
        SpecialTeam.IMPOSTOR -> Palette.ERROR
        SpecialTeam.NEUTRAL -> Palette.PURPLE
        SpecialTeam.MODIFIER -> Palette.GOLD
    }

    fun embed(role: SpecialRole): MessageEmbed = EmbedBuilder()
        .setTitle("${role.emoji} ${role.name}")
        .setDescription(role.description)
        .setColor(color(role.team))
        .field("Team", role.team.label)
        .setFooter("Spezialrolle aus unserer Rollen-Mod")
        .build()

    suspend fun onSelect(event: StringSelectInteractionEvent) {
        val role = SPECIAL_ROLES[event.values.firstOrNull()] ?: error("Unbekannte Spezialrolle: ${event.values}")
        event.replyEmbeds(embed(role)).setEphemeral(true).await()
        RoleSelect.logSelection(event, role.name)
    }
}
