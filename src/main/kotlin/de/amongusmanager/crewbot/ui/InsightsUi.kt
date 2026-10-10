/* Anzeige der Auswertungen: Rivalen in /stats, Spieleabend-Recap und /erfolge. */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.ACHIEVEMENTS
import de.amongusmanager.crewbot.core.discordTimestamp
import de.amongusmanager.crewbot.services.AchievementService
import de.amongusmanager.crewbot.services.GameStats
import de.amongusmanager.crewbot.services.PlayerKey
import de.amongusmanager.crewbot.services.PlayerNight
import de.amongusmanager.crewbot.services.Recap
import de.amongusmanager.crewbot.services.Rivals
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.Instant

/** Discord-ID -> Erwähnung, Ingame-Name (unverknüpft) -> kursiv. */
fun who(key: PlayerKey): String = when (key) {
    is PlayerKey.User -> "<@${key.id}>"
    is PlayerKey.Name -> "*${key.name}*"
}

// ==========================================
// RIVALEN
// ==========================================

fun rivalsText(rivals: Rivals): String {
    val lines = ArrayList<String>()
    rivals.nemesis?.let { lines.add("😈 **Nemesis:** ${who(it.first)} hat dich ${it.second}× getötet") }
    rivals.favoriteVictim?.let { lines.add("🎯 **Lieblingsopfer:** ${who(it.first)} (${it.second}× getötet)") }
    rivals.biggestDoubter?.let { lines.add("🤨 **Misstraut dir am meisten:** ${who(it.first)} (${it.second} Stimmen)") }
    rivals.mySuspect?.let { lines.add("🔍 **Dein Hauptverdächtiger:** ${who(it.first)} (${it.second} Stimmen)") }
    return lines.joinToString("\n")
}

// ==========================================
// SPIELEABEND-RECAP
// ==========================================

private class RecapAward(
    val emoji: String,
    val label: String,
    val unit: String,
    val minimum: Int,
    val value: (PlayerNight) -> Int,
)

private val RECAP_AWARDS = listOf(
    RecapAward("🏆", "Meiste Siege", "Siege", 1) { it.wins },
    RecapAward("🔪", "Gefährlichster Impostor", "Kills", 1) { it.kills },
    RecapAward("🕵️", "Bester Detektiv", "richtige Stimmen", 1) { it.correctVotes },
    RecapAward("🛡️", "Überlebenskünstler", "× überlebt", 1) { it.survived },
    RecapAward("🤡", "Sus-Magnet", "Stimmen als Crewmate", 2) { it.susVotes },
    RecapAward("🥀", "Pechvogel", "× als Erste(r) gestorben", 2) { it.firstDeaths },
)

private val MEDALS = listOf("🥇", "🥈", "🥉")

fun buildRecapEmbed(recap: Recap, title: String, since: Instant? = null): MessageEmbed {
    val embed = EmbedBuilder().setTitle(title).setColor(Palette.PURPLE)
    if (recap.rounds == 0) {
        embed.setDescription("Keine aufgezeichneten Runden in diesem Zeitraum.")
        return embed.build()
    }

    embed.setDescription(
        "🎮 **${recap.rounds}** Runden • 🚀 Crew **${recap.crewWins}** : **${recap.impostorWins}** Impostor 🔪" +
            " • 🩸 ${recap.totalKills} Kills"
    )

    val awards = RECAP_AWARDS.mapNotNull { award ->
        recap.best(award.minimum, award.value)?.let { (key, value) ->
            "${award.emoji} **${award.label}:** ${who(key)} ($value ${award.unit})"
        }
    }
    embed.field("🏅 Auszeichnungen", awards.joinToString("\n").ifEmpty { "–" }, inline = false)

    val candidates = recap.mvpCandidates()
    if (candidates.isNotEmpty()) {
        embed.field(
            "👑 MVP-Kandidaten (aus den Stats)",
            candidates.mapIndexed { index, (key, night) ->
                "${MEDALS[index]} ${who(key)} – ${night.wins} Siege, ${night.kills} Kills, ${night.correctVotes} richtige Stimmen"
            }.joinToString("\n"),
            inline = false,
        )
    }
    if (since != null) {
        embed.setFooter("Runden seit")
        embed.setTimestamp(since)
    }
    return safeBuild(embed)
}

/** Kurzer Hinweis für das MVP-Voting, wer laut Stats vorne liegt. */
fun mvpSuggestionText(recap: Recap): String {
    val candidates = recap.mvpCandidates()
    if (candidates.isEmpty()) return ""
    return "\n\n💡 **Laut Stats heute stark:** ${candidates.joinToString(", ") { who(it.first) }}"
}

// ==========================================
// ERFOLGE
// ==========================================

fun buildAchievementsEmbed(
    displayName: String,
    avatarUrl: String,
    unlocked: Map<String, Instant>,
    stats: GameStats,
): MessageEmbed {
    val embed = EmbedBuilder()
        .setTitle("🏅 Erfolge: $displayName")
        .setDescription("**${unlocked.size}/${ACHIEVEMENTS.size}** freigeschaltet")
        .setColor(Palette.GOLD)
        .setThumbnail(avatarUrl)

    val done = ArrayList<String>()
    val open = ArrayList<String>()
    for (achievement in ACHIEVEMENTS) {
        val at = unlocked[achievement.id]
        if (at != null) {
            done.add("${achievement.label} – ${achievement.description} (${at.discordTimestamp("d")})")
            continue
        }
        var line = "🔒 **${achievement.name}** – ${achievement.description} • +${achievement.xp} XP"
        AchievementService.progress(achievement, stats)?.let { (current, target) ->
            line += "\n${progressBar(current, target)} $current/$target"
        }
        open.add(line)
    }

    embed.addChunked("✅ Geschafft", done.ifEmpty { listOf("Noch keine – spiel eine Runde mit der Mod!") })
    if (open.isNotEmpty()) embed.addChunked("🎯 Noch offen", open)
    return safeBuild(embed)
}
