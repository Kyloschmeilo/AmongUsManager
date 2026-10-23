/* Embeds für /tutorial (Inhalt: content/Tutorials.kt) im Standard-Design: Akzentfarbe, Abschnitte in Großbuchstaben. */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.Tutorial
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Platz, den ein Embed höchstens belegt (Discord: 6000 Zeichen pro Nachricht), mit etwas Reserve. */
private const val EMBED_BUDGET = 5800

/** Befehle in allen Tutorials, z.B. für den Test, ob es sie gibt. */
fun tutorialCommands(): Set<String> = TUTORIALS.flatMap { t ->
    listOf(t.intro, t.footer) + t.sections.flatMap { it.lines }
}.flatMap(::commandsIn).toSet()

/**
 * Baut das Tutorial als ein oder mehrere Embeds – jedes kommt in eine eigene Nachricht, weil Discords Grenze von
 * 6000 Zeichen für die ganze Nachricht gilt. Das erste trägt Titel und Einleitung, das letzte den Footer.
 * `mention` macht aus einem Befehlsnamen einen anklickbaren Befehl (`bot.mentionCommand`).
 */
fun buildTutorialEmbeds(tutorial: Tutorial, mention: (String) -> String): List<MessageEmbed> {
    // Erst alle Felder sammeln: lange Abschnitte (z.B. alle Crew-Rollen) gehen auf mehrere Felder über
    val fields = EmbedBuilder()
    for (section in tutorial.sections) {
        // Nummerierte Schritte behalten ihre Nummer, alles andere bekommt einen schlichten Punkt
        val lines = section.lines.map { line ->
            val text = linkCommands(line, mention)
            if (line.first().isDigit()) text else "• $text"
        }
        fields.addChunked(section.title, lines)
    }

    val embeds = mutableListOf<EmbedBuilder>()
    var current = EmbedBuilder()
        .setAuthor("TUTORIAL")
        .setTitle(tutorial.title)
        .setDescription(linkCommands(tutorial.intro, mention))
        .setColor(Palette.ACCENT)
    for (field in fields.fields) {
        val size = field.name.orEmpty().length + field.value.orEmpty().length
        if (current.fields.isNotEmpty() &&
            (current.length() + size + tutorial.footer.length > EMBED_BUDGET || current.fields.size >= 25)
        ) {
            embeds += current
            current = EmbedBuilder().setColor(Palette.ACCENT)
        }
        current.addField(field)
    }
    embeds += current.setFooter(tutorial.footer)
    return embeds.map(::safeBuild)
}
