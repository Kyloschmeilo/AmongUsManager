/* Fortschritt: Profil, Crew-Card und Leaderboard (inkl. Medaillen im Nickname). */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.PlayerStats
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildCrewCardEmbed
import de.amongusmanager.crewbot.ui.buildProfileEmbed
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.views.takeCodePoints
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory
import java.time.Instant

val MEDALS = listOf("🥇", "🥈", "🥉")
const val MAX_NICK_LENGTH = 32

/** Entfernt alle Medaillen am Namensanfang (auch mehrere, z.B. „🥇 🥈 Name“). */
fun stripMedal(name: String): String {
    var result = name
    var stripped = true
    while (stripped) {
        stripped = false
        for (medal in MEDALS) {
            if (result.startsWith(medal)) {
                result = result.substring(medal.length).trimStart()
                stripped = true
            }
        }
    }
    return result
}

class ProgressionModule(private val bot: CrewBot) : CrewModule("Level & Fortschritt", "📈") {
    private val log = LoggerFactory.getLogger(ProgressionModule::class.java)
    private val medalLock = Mutex() // zwei /leaderboard gleichzeitig dürfen sich nicht in die Quere kommen

    override val commands = listOf(
        SlashCommand(
            "profile", "Zeigt Crew-Level und Fortschritt",
            options = listOf(OptionData(OptionType.USER, "user", "Wessen Profil? (leer = dein eigenes)", false)),
        ) { event ->
            val target = event.memberOption("user") ?: event.author
            val stats = bot.levels.get(target.idLong)
            event.replyEmbeds(buildProfileEmbed(target.effectiveName, target.effectiveAvatarUrl, stats)).await()
        },
        SlashCommand(
            "crew-card", "Deine persönliche Astronauten-Visitenkarte",
            options = listOf(OptionData(OptionType.USER, "spieler", "Wessen Crew-Card? (leer = deine eigene)", false)),
        ) { event ->
            val target = event.memberOption("spieler") ?: event.author
            val stats = bot.levels.get(target.idLong)
            val offline = target.onlineStatus == OnlineStatus.OFFLINE
            event.replyEmbeds(buildCrewCardEmbed(target.effectiveName, target.effectiveAvatarUrl, target.idLong, offline, stats))
                .await()
        },
        SlashCommand("leaderboard", "Top 10 der Crew – die Top 3 bekommen Medaillen im Nickname") { event ->
            val ranking = bot.levels.ranking()
            if (ranking.isEmpty()) {
                event.notify("📊 Das Leaderboard ist aktuell leer!", Tone.INFO)
                return@SlashCommand
            }
            val guild = event.guild!!

            event.deferReply().await()
            medalLock.withLock { syncMedals(guild, ranking) }

            val entries = ranking.take(10).mapIndexed { index, (userId, stats) ->
                val position = index + 1
                val member = guild.getMemberById(userId)
                val name = if (member != null) stripMedal(member.effectiveName) else "Unbekannter Astronaut ($userId)"
                val badge = if (position <= 3) MEDALS[position - 1] else "**#$position**"
                "$badge **$name**\n↳ 📊 *Lvl ${stats.level}* | ✨ *${stats.xp} XP* | 🔪 *${stats.wins} Siege* | ${stats.rank.name}"
            }

            val ownPosition = ranking.indexOfFirst { it.first == event.user.idLong }.takeIf { it >= 0 }?.plus(1)
            val embed = EmbedBuilder()
                .setTitle("🏆 AMONG US LEADERBOARD 🏆")
                .setDescription(
                    "Die aktuellen Top 10. Die Server-Nicknames der Top 3 wurden synchronisiert!\n\n" +
                        entries.joinToString("\n\n")
                )
                .setColor(Palette.GOLD)
                .setFooter(if (ownPosition != null) "Dein Platz: #$ownPosition" else "Du bist noch nicht im Ranking")
                .setTimestamp(Instant.now()) // Discord zeigt es in der Ortszeit des Lesers
                .build()
            event.hook.sendMessageEmbeds(embed).await()
        },
    )

    /**
     * Top 3 bekommen 🥇🥈🥉 vor den Nickname, alle anderen verlieren ihre Medaille.
     *
     * Geprüft wird jedes Server-Mitglied mit Medaille – auch wer nicht (mehr) im Ranking
     * steht, z.B. nach einem Reset im Admin-Panel oder bei selbst eingetragenen Medaillen.
     */
    private suspend fun syncMedals(guild: Guild, ranking: List<Pair<Long, PlayerStats>>) {
        val top3 = LinkedHashMap<Long, String>()
        ranking.take(3).forEachIndexed { index, (userId, _) -> top3.putIfAbsent(userId, MEDALS[index]) }
        val members = LinkedHashMap<Long, Member>()
        for (member in guild.members) {
            if (stripMedal(member.effectiveName) != member.effectiveName) members[member.idLong] = member
        }
        for (userId in top3.keys) guild.getMemberById(userId)?.let { members.putIfAbsent(userId, it) }

        for ((userId, member) in members) {
            val base = stripMedal(member.effectiveName)
            val medal = top3[userId]
            val wanted = if (medal != null) "$medal $base".takeCodePoints(MAX_NICK_LENGTH) else base
            if (member.effectiveName == wanted) continue
            // Ohne Medaille wieder den normalen Namen zeigen statt eines Nicknames, der ihm nur gleicht
            val nick = if (medal == null && base == (member.user.globalName ?: member.user.name)) null else wanted
            try {
                member.modifyNickname(nick).await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.warn(
                    "Nickname von {} nicht änderbar (Rolle über dem Bot / Server-Owner?): {}",
                    member.effectiveName, e.describe(),
                )
            }
        }
    }
}
