/* Einheitliche Farben, Rückmeldungen und kleine Anzeige-Helfer. */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.core.await
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

object Palette {
    const val BRAND = 0xC51111 // Among-Us-Rot
    const val SUCCESS = 0x2ECC71
    const val ERROR = 0xE74C3C
    const val WARNING = 0xF1C40F
    const val INFO = 0x3498DB
    const val GOLD = 0xF1C40F
    const val PURPLE = 0x9B59B6
    const val EVENT = 0xFF00FF
    const val CARD = 0xF5F5F7

    // Weitere Farben wie in discord.py
    const val BLURPLE = 0x5865F2
    const val DARK_GREY = 0x607D8B
    const val RED = 0xE74C3C
    const val ORANGE = 0xE67E22
    const val TEAL = 0x1ABC9C
    const val DARK_RED = 0x992D22

    fun forTeam(team: Team): Int = if (team == Team.CREW) SUCCESS else ERROR
}

enum class Tone(val color: Int) {
    INFO(Palette.INFO),
    SUCCESS(Palette.SUCCESS),
    WARNING(Palette.WARNING),
    ERROR(Palette.ERROR),
}

/** Kurze farbige Rückmeldung – funktioniert egal ob schon geantwortet wurde. */
suspend fun IReplyCallback.notify(text: String, tone: Tone = Tone.INFO, ephemeral: Boolean = true) {
    val embed = EmbedBuilder().setDescription(text).setColor(tone.color).build()
    if (isAcknowledged) {
        hook.sendMessageEmbeds(embed).setEphemeral(ephemeral).await()
    } else {
        replyEmbeds(embed).setEphemeral(ephemeral).await()
    }
}

/** Einfaches Embed nur mit Text und Farbe. */
fun simpleEmbed(text: String, color: Int): MessageEmbed = EmbedBuilder().setDescription(text).setColor(color).build()

fun progressBar(value: Int, total: Int, length: Int = 10): String {
    val filled = if (total != 0) minOf(length, (value.toDouble() / total * length).toInt()) else 0
    return "🟦".repeat(maxOf(0, filled)) + "⬜".repeat(maxOf(0, length - filled))
}

/** Feld hinzufügen; wie in discord.py stehen Felder standardmäßig nebeneinander. */
fun EmbedBuilder.field(name: String, value: String, inline: Boolean = true): EmbedBuilder =
    addField(name, value, inline)

/** Kürzt auf höchstens [maxLength] Zeichen, ohne ein Emoji zu zerschneiden. */
fun String.takeSafe(maxLength: Int): String {
    if (length <= maxLength) return this
    var end = maxOf(0, maxLength)
    if (end > 0 && Character.isHighSurrogate(this[end - 1])) end--
    return substring(0, end)
}

/**
 * Zeilen zusammenfügen, ohne das Discord-Limit pro Feld zu sprengen (wie `_fit` im alten Bot):
 * Passt eine Zeile nicht mehr, endet der Text mit „… und N weitere“.
 */
fun fitLines(lines: List<String>, empty: String = "", limit: Int = MessageEmbed.VALUE_MAX_LENGTH, reserve: Int = 30): String {
    val text = StringBuilder()
    lines.forEachIndexed { index, line ->
        if (text.length + line.length + reserve > limit) return "$text… und ${lines.size - index} weitere"
        text.append(line).append('\n')
    }
    return text.toString().trim().ifEmpty { empty }
}

/** Verteilt lange Listen auf mehrere Felder statt abzuschneiden. */
fun EmbedBuilder.addChunked(name: String, lines: List<String>, limit: Int = MessageEmbed.VALUE_MAX_LENGTH) {
    var fieldName = name
    var chunk = ""
    for (line in lines) {
        if (chunk.isNotEmpty() && chunk.length + line.length + 1 > limit) {
            field(fieldName, chunk, inline = false)
            fieldName = "​" // Folgefelder ohne Überschrift
            chunk = ""
        }
        chunk = if (chunk.isNotEmpty()) "$chunk\n$line" else line.takeSafe(limit)
    }
    if (chunk.isNotEmpty()) field(fieldName, chunk, inline = false)
}

/**
 * Baut das Embed und kürzt notfalls die längsten Felder, damit Discords Gesamtgrenze
 * (6000 Zeichen) nie überschritten wird – sonst würde die ganze Nachricht abgelehnt.
 */
fun safeBuild(builder: EmbedBuilder): MessageEmbed {
    while (builder.length() > MessageEmbed.EMBED_MAX_LENGTH_BOT && builder.fields.isNotEmpty()) {
        val index = builder.fields.indices.maxBy { builder.fields[it].value?.length ?: 0 }
        val field = builder.fields[index]
        val value = field.value ?: ""
        val excess = builder.length() - MessageEmbed.EMBED_MAX_LENGTH_BOT
        if (value.length <= excess + 2) {
            builder.fields.removeAt(index)
        } else {
            builder.fields[index] = MessageEmbed.Field(field.name, value.takeSafe(value.length - excess - 1) + "…", field.isInline)
        }
    }
    return builder.build()
}
