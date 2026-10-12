/* Willkommens- und Abschiedsnachricht (services/Welcome.kt). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.Channels
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** `help` = anklickbarer /hilfe-Befehl. */
fun buildWelcomeEmbed(mention: String, avatarUrl: String?, memberCount: Int, help: String): MessageEmbed =
    EmbedBuilder()
        .setTitle("👋 Willkommen auf dem Server!")
        .setDescription(
            "Schön, dass du da bist, $mention!\n" +
                "Hol dir im <#${Channels.ROLES}> deine Rollen – alle Befehle des Bots findest du mit $help."
        )
        .setThumbnail(avatarUrl)
        .setColor(Palette.SUCCESS)
        .setFooter("Mitglied Nr. $memberCount")
        .build()

fun buildLeaveEmbed(name: String, avatarUrl: String?): MessageEmbed =
    EmbedBuilder()
        .setAuthor(name.takeSafe(MessageEmbed.AUTHOR_MAX_LENGTH), null, avatarUrl)
        .setDescription("🚪 **${escapeMarkdown(name)}** hat den Server verlassen. Bis bald!")
        .setColor(Palette.DARK_GREY)
        .build()
