/* Erklärungen der Rollen aus The Other Roles (/special-rolle) per Dropdown; Discord erlaubt höchstens 25 Einträge pro Menü und 5 Menüs. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.SpecialRole
import de.amongusmanager.crewbot.content.SpecialTeam
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
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

    /** Crew auf zwei Menüs (A–…, …–Z), dann Impostor, Neutrale und zusammen Modifikatoren + Rollen für beide Teams. */
    val MENUS: List<Triple<String, String, List<SpecialRole>>> = run {
        fun of(vararg teams: SpecialTeam) = SPECIAL_ROLES.values.filter { it.team in teams }.sortedBy { it.name }
        val crew = of(SpecialTeam.CREW)
        val (crewA, crewB) = crew.chunked((crew.size + 1) / 2).let { it[0] to it.getOrElse(1) { emptyList() } }
        fun range(roles: List<SpecialRole>) = "${roles.first().name.first()}–${roles.last().name.first()}"
        listOfNotNull(
            Triple("crew", "🟢 Crewmate-Rolle ${range(crewA)} wählen...", crewA),
            if (crewB.isEmpty()) null else Triple("crew2", "🟢 Crewmate-Rolle ${range(crewB)} wählen...", crewB),
            Triple("impostor", "🔴 Impostor-Rolle wählen...", of(SpecialTeam.IMPOSTOR)),
            Triple("neutral", "🟣 Neutrale Rolle wählen...", of(SpecialTeam.NEUTRAL)),
            Triple("other", "🟡 Modifikator oder Rolle für beide Teams wählen...", of(SpecialTeam.BOTH, SpecialTeam.MODIFIER)),
        )
    }

    fun rows(): List<ActionRow> = MENUS.map { (key, placeholder, roles) ->
        ActionRow.of(
            StringSelectMenu.create(PREFIX + key)
                .setPlaceholder(placeholder)
                .addOptions(
                    roles.map { role ->
                        SelectOption.of(role.name, role.id)
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
        SpecialTeam.BOTH -> Palette.INFO
        SpecialTeam.MODIFIER -> Palette.GOLD
    }

    fun embed(role: SpecialRole): MessageEmbed = EmbedBuilder()
        .setTitle("${role.emoji} ${role.name}")
        .setDescription(role.description)
        .setColor(color(role.team))
        .field("Team", role.team.label)
        .setFooter("Rolle aus der Mod The Other Roles")
        .build()

    suspend fun onSelect(event: StringSelectInteractionEvent) {
        val role = SPECIAL_ROLES[event.values.firstOrNull()]
        if (role == null) { // Menü aus der Zeit vor The Other Roles
            event.notify("⌛ Dieses Menü ist veraltet. Öffne es mit /special-rolle neu.", Tone.WARNING)
            return
        }
        event.replyEmbeds(embed(role)).setEphemeral(true).await()
        RoleSelect.logSelection(event, role.name)
    }
}
