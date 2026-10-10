/* Among-Us-Wissen: Vent-Karten, Map-Infos und Rollen-Erklärungen. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.content.MAPS
import de.amongusmanager.crewbot.core.CommandError
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.views.RoleSelect
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData

class KnowledgeModule : CrewModule("Among Us Wissen", "📚") {
    private fun mapOption(): OptionData =
        OptionData(OptionType.STRING, "map_name", "Welche Map?", true)
            .addChoices(MAPS.keys.map { net.dv8tion.jda.api.interactions.commands.Command.Choice(it, it) })

    private fun pickedMap(event: SlashCommandInteractionEvent) =
        MAPS[event.stringOption("map_name")] ?: throw CommandError("❌ Unbekannte Map.")

    override val commands = listOf(
        SlashCommand("vents", "Zeigt das Vent-Netzwerk einer Map", options = listOf(mapOption())) { event ->
            val map = pickedMap(event)
            val embed = EmbedBuilder()
                .setTitle("🕳️ Vents: ${map.name}")
                .setColor(Palette.INFO)
                .setImage(map.ventImage)
                .build()
            event.replyEmbeds(embed).await()
        },
        SlashCommand("map-info", "Tipps, Kameras und Todesfallen einer Map", options = listOf(mapOption())) { event ->
            val map = pickedMap(event)
            val embed = EmbedBuilder()
                .setTitle("🗺️ MAP INFO: ${map.name} 🗺️")
                .setDescription(map.description)
                .setColor(Palette.INFO)
                .field("👁️ Überwachung / Cams", map.cameras, inline = false)
                .field("☠️ Gefahrenzonen", map.dangerZones, inline = false)
                .field("📝 Tasks / Besonderheiten", map.tasks, inline = false)
                .setImage(map.ventImage)
                .setFooter("Inklusive Vent-Netzwerk auf der Karte")
                .build()
            event.replyEmbeds(embed).await()
        },
        SlashCommand("rolle", "Erklärt dir eine Among Us Rolle") { event ->
            event.reply("Wähle eine Rolle aus dem Menü, um ihre Erklärung zu lesen:")
                .setComponents(RoleSelect.row())
                .setEphemeral(true)
                .await()
        },
    )
}
