package de.amongusmanager.crewbot.ui

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** DM an den Jester der Runde (Jester-Modus von CrewStats im normalen Among Us). Nur er bekommt sie. */
fun buildJesterEmbed(lobbyCode: String): MessageEmbed {
    val lobby = lobbyCode.takeIf { it.isNotBlank() }?.let { " in Lobby **${escapeMarkdown(it)}**" } ?: ""
    return EmbedBuilder()
        .setTitle("🤡 Du bist der Jester")
        .setDescription(
            "Diese Runde$lobby bist du heimlich **Jester**. Im Spiel siehst du dich als Crewmate – das gehört dazu.\n\n" +
                "**Ziel:** Lass dich im Meeting **rauswählen**. Dann endet das Spiel sofort und **nur du gewinnst**.\n" +
                "Wirst du getötet oder gewinnt vorher ein Team, hast du verloren.\n\n" +
                "Verrat es niemandem – auch der Host weiß nicht, wer Jester ist.",
        )
        .setColor(Palette.PURPLE)
        .setFooter("CrewStats • Jester-Modus")
        .build()
}
