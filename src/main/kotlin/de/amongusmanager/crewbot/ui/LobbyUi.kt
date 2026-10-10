/* Embed der automatischen Lobby-Ankündigung (siehe services/Lobby.kt). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.services.LobbyEvent
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
