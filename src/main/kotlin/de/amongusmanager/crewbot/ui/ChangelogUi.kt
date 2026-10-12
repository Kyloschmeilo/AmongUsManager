/* Embed für /changelog (Inhalt: content/Changelog.kt). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.Changelog
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** „/befehl“ im Text (nicht in Links oder Mentions). */
private val COMMAND = Regex("""(?<![\w</:])/([a-z0-9-]+)""")

/** Befehle im Text, z.B. für den Test, ob es sie gibt. */
fun changelogCommands(): Set<String> =
    Changelog.sections.flatMap { it.lines }.flatMap { line -> COMMAND.findAll(line).map { it.groupValues[1] } }.toSet()

/** `mention` macht aus einem Befehlsnamen einen anklickbaren Befehl (`bot.mentionCommand`). */
fun buildChangelogEmbed(mention: (String) -> String): MessageEmbed {
    val embed = EmbedBuilder()
        .setTitle("📢 Update ${Changelog.VERSION} – das ist neu")
        .setColor(Palette.BRAND)
    for (section in Changelog.sections) {
        val lines = section.lines.map { line -> "• " + COMMAND.replace(line) { mention(it.groupValues[1]) } }
        embed.field(section.title, fitLines(lines), inline = false)
    }
    return safeBuild(embed)
}
