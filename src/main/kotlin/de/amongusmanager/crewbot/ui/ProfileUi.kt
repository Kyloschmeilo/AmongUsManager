/* Embeds für /profile, /crew-card und /stats (reine Funktionen, damit sie testbar sind). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.content.ACHIEVEMENTS
import de.amongusmanager.crewbot.services.GameStats
import de.amongusmanager.crewbot.services.PlayerStats
import de.amongusmanager.crewbot.services.formatFixed
import de.amongusmanager.crewbot.services.nextRank
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

fun buildProfileEmbed(displayName: String, avatarUrl: String, stats: PlayerStats): MessageEmbed {
    val embed = EmbedBuilder()
        .setTitle("📊 Profil: $displayName")
        .setColor(Palette.PURPLE)
        .setThumbnail(avatarUrl)
        .field("Rang", stats.rank.name, inline = false)
        .field("Level", "**${stats.level}**")
        .field("Gesamt XP", "**${stats.xp} XP**")
        .field("Siege", "🔪 ${stats.wins}")
        .field(
            "Fortschritt zum nächsten Level (${stats.xpInLevel}/${Rules.XP_PER_LEVEL} XP)",
            progressBar(stats.xpInLevel, Rules.XP_PER_LEVEL),
            inline = false,
        )
    val upcoming = nextRank(stats.level)
    if (upcoming != null) {
        embed.setFooter(
            "Nächster Rang: ${upcoming.name} ab Level ${upcoming.minLevel} • ${stats.participations} Event-Teilnahmen"
        )
    } else {
        embed.setFooter("Höchster Rang erreicht! • ${stats.participations} Event-Teilnahmen")
    }
    return embed.build()
}

fun buildCrewCardEmbed(displayName: String, avatarUrl: String, userId: Long, offline: Boolean, stats: PlayerStats): MessageEmbed {
    val line = "─".repeat(34)
    val card = listOf(
        "NAME:     ${displayName.uppercase()}",
        "STATUS:   ${if (offline) "INAKTIV" else "AKTIV"}",
        "RANG:     ${stats.rank.name.uppercase()}",
        line,
        "LEVEL:    ${stats.level.toString().padEnd(5)} |  XP GESAMT: ${stats.xp}",
        line,
        "SIEGE:    🔪 ${stats.wins}",
        "EVENTS:   📅 ${stats.participations}",
    ).joinToString("\n")
    return EmbedBuilder()
        .setTitle("💳 MIRA HQ • IDENTIFICATION CARD")
        .setDescription("```\n$card\n```") // Code-Block = saubere Spalten
        .setColor(Palette.CARD)
        .setThumbnail(avatarUrl)
        .setFooter("ID: $userId  •  AUSGESTELLT DURCH AMONG US SEKTOR 9")
        .build()
}

fun buildStatsEmbed(
    displayName: String,
    avatarUrl: String,
    s: GameStats,
    codes: List<String>,
    rivals: String,
    unlockedCount: Int,
): MessageEmbed {
    val embed = EmbedBuilder()
        .setTitle("📈 Among-Us-Statistik: $displayName")
        .setColor(Palette.PURPLE)
        .setThumbnail(avatarUrl)
    if (s.games == 0) {
        embed.setDescription(
            "Noch keine aufgezeichneten Runden." +
                if (codes.isNotEmpty()) "" else "\nVerknüpfe zuerst deinen Account mit `/verknuepfen`!"
        )
    } else {
        val rate = GameStats::rate
        embed.field("🎮 Spiele", "**${s.games}**\n🚀 ${s.crewGames} • 🔪 ${s.impostorGames}")
        embed.field("🏆 Siege", "**${s.wins}** (${rate(s.wins, s.games)})")
        embed.field("🛡️ Überlebt", "${s.survived} (${rate(s.survived, s.games)})")
        embed.field("🚀 Crew-Winrate", rate(s.crewWins, s.crewGames))
        embed.field("🔪 Impostor-Winrate", rate(s.impostorWins, s.impostorGames))
        val killsPerGame = if (s.impostorGames != 0) formatFixed(s.kills.toDouble() / s.impostorGames, 1) else "–"
        embed.field("🩸 Kills", "${s.kills} (Ø $killsPerGame pro Impostor-Runde)")
        embed.field("💀 Getötet", s.deaths.toString())
        embed.field("🚪 Rausgewählt", s.ejected.toString())
        embed.field("🧰 Tasks erledigt", s.tasksCompleted.toString())
        embed.field("📢 Notfall-Meetings", s.meetingsCalled.toString())
        embed.field("🩸 Leichen gemeldet", s.bodiesReported.toString())
        val votes = s.votesCorrect + s.votesWrong
        embed.field("🎯 Vote-Trefferquote", "${rate(s.votesCorrect, votes)} (${s.votesCorrect}/$votes)")
        if (rivals.isNotEmpty()) embed.field("⚔️ Rivalen", rivals, inline = false)
        embed.field("🏅 Erfolge", "$unlockedCount/${ACHIEVEMENTS.size} – Details: `/erfolge`", inline = false)
    }
    embed.setFooter(if (codes.isNotEmpty()) "Verknüpft: ${codes.joinToString(", ")}" else "Nicht verknüpft – /verknuepfen")
    return safeBuild(embed)
}
