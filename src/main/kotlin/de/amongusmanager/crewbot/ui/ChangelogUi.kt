/* Embed für /changelog (Inhalt: content/Changelog.kt). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.Changelog
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Befehle im Text, z.B. für den Test, ob es sie gibt. */
fun changelogCommands(): Set<String> =
    Changelog.sections.flatMap { it.lines }.flatMap(::commandsIn).toSet()

/** `mention` macht aus einem Befehlsnamen einen anklickbaren Befehl (`bot.mentionCommand`). */
fun buildChangelogEmbed(mention: (String) -> String): MessageEmbed {
    val embed = EmbedBuilder()
        .setTitle("📢 Update ${Changelog.VERSION} – das ist neu")
        .setColor(Palette.BRAND)
    for (section in Changelog.sections) {
        val lines = section.lines.map { line -> "• " + linkCommands(line, mention) }
        embed.field(section.title, fitLines(lines), inline = false)
    }
    return safeBuild(embed)
}
