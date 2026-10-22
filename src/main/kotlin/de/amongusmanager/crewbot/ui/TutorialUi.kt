/* Embed für /tutorial (Inhalt: content/Tutorials.kt) im Standard-Design: Akzentfarbe, Abschnitte in Großbuchstaben. */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.Tutorial
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Befehle in allen Tutorials, z.B. für den Test, ob es sie gibt. */
fun tutorialCommands(): Set<String> = TUTORIALS.flatMap { t ->
    listOf(t.intro, t.footer) + t.sections.flatMap { it.lines }
}.flatMap(::commandsIn).toSet()

/** `mention` macht aus einem Befehlsnamen einen anklickbaren Befehl (`bot.mentionCommand`). */
fun buildTutorialEmbed(tutorial: Tutorial, mention: (String) -> String): MessageEmbed {
    val embed = EmbedBuilder()
        .setAuthor("TUTORIAL")
        .setTitle(tutorial.title)
        .setDescription(linkCommands(tutorial.intro, mention))
        .setColor(Palette.ACCENT)
        .setFooter(tutorial.footer)
    for (section in tutorial.sections) {
        // Nummerierte Schritte behalten ihre Nummer, alles andere bekommt einen schlichten Punkt
        val lines = section.lines.map { line ->
            val text = linkCommands(line, mention)
            if (line.first().isDigit()) text else "• $text"
        }
        embed.field(section.title, fitLines(lines), inline = false)
    }
    return safeBuild(embed)
}
