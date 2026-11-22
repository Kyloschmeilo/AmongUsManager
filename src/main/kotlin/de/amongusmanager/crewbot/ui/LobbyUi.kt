/* Embed der automatischen Lobby-Ankündigung (siehe services/Lobby.kt). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.LobbyPing
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.Instant

fun buildLobbyEmbed(event: LobbyEvent, host: String, now: Instant = Instant.now()): MessageEmbed {
    val embed = EmbedBuilder()
    if (event.state == "closed") {
        embed.setTitle("⬛ Lobby geschlossen")
            .setDescription("~~`${event.code}`~~ • Danke fürs Mitspielen!")
            .setColor(Palette.DARK_GREY)
    } else {
        val started = event.state == "started"
        embed.setTitle(if (started) "🚀 Runde läuft" else "🎮 Among-Us-Lobby offen")
            .setDescription("## `${event.code}`" + if (started) "\n🔒 Wetten geschlossen bis zum Rundenende" else "")
            .setColor(if (started) Palette.BRAND else Palette.SUCCESS)
        embed.field("🗺️ Map", event.map, inline = true)
        embed.field("👑 Host", host, inline = true)
        if (event.maxPlayers != 0) {
            embed.field(
                "👥 Spieler ${event.players}/${event.maxPlayers}",
                progressBar(event.players, event.maxPlayers),
                inline = false,
            )
        }
    }
    embed.setFooter("Aktualisiert sich automatisch • CrewStats-Mod")
    embed.setTimestamp(now)
    return embed.build()
}

/**
 * DM der Lobby-Benachrichtigung (`/lobby-benachrichtigung`, services/LobbyPing.kt). [host] wie in der Ankündigung,
 * [website] = Adresse der Stats-Website (Live-Lobbys), wenn sie läuft.
 */
fun buildLobbyPingEmbed(event: LobbyEvent, ping: LobbyPing, host: String, website: String?, now: Instant = Instant.now()): MessageEmbed {
    val friends = ping.friends.map { "<@$it>" }
    val title = when {
        friends.isEmpty() -> "🎮 Neue Among-Us-Lobby"
        friends.size == 1 -> "🎮 Dein Freund ist in einer Lobby"
        else -> "🎮 ${friends.size} deiner Freunde sind in einer Lobby"
    }
    val lines = mutableListOf("## `${event.code}`")
    if (friends.isNotEmpty()) lines += "Dabei: " + friends.joinToString(", ")
    if (website != null) lines += "[Live auf der Website ansehen](${website.trimEnd('/')}/amongus/lobbys)"
    val embed = EmbedBuilder()
        .setTitle(title)
        .setDescription(lines.joinToString("\n"))
        .setColor(Palette.ACCENT)
    embed.field("🗺️ Map", event.map, inline = true)
    embed.field("👑 Host", host, inline = true)
    if (event.maxPlayers != 0) embed.field("👥 Spieler", "${event.players}/${event.maxPlayers}", inline = true)
    embed.setFooter("Abbestellen oder Freunde ändern: /lobby-benachrichtigung")
    embed.setTimestamp(now)
    return embed.build()
}
