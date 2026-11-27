/* /crewstats-sperre (Admin): Personen für CrewStats sperren – alles von ihnen wird ignoriert (services/PlayerBans.kt). */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.PlayerBan
import de.amongusmanager.crewbot.services.PlayerBans
import de.amongusmanager.crewbot.services.isValidFriendCode
import de.amongusmanager.crewbot.services.normalizeFriendCode
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.escapeMarkdown
import de.amongusmanager.crewbot.ui.fitLines
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.safeBuild
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData

/** Befehle, mit denen Spieler etwas an CrewStats geben – für Gesperrte gesperrt (Admins ausgenommen). */
val CREWSTATS_INPUT_COMMANDS = setOf(
    "verknuepfen", "verknuepfen-code", "rollenwunsch", "special-rollenwunsch", "autojoin", "lobby-benachrichtigung",
)

class BanCommands(private val bot: CrewBot) {

    val commands = listOf(
        SlashCommand(
            "crewstats-sperre", "Sperrt eine Person für CrewStats: alles, was von ihr kommt, wird ignoriert", Permission.ADMINISTRATOR,
            options = listOf(
                OptionData(OptionType.STRING, "aktion", "Was soll passieren?", true)
                    .addChoices(
                        Command.Choice("Sperren", "sperren"),
                        Command.Choice("Entsperren", "entsperren"),
                        Command.Choice("Liste zeigen", "liste"),
                    ),
                OptionData(OptionType.USER, "spieler", "Discord-Konto (gilt für alle seine Friendcodes)", false),
                OptionData(OptionType.STRING, "friendcode", "Oder ein Friendcode, z.B. für Spieler ohne Verknüpfung", false),
                OptionData(OptionType.STRING, "grund", "Warum? (steht in der Liste und im Log)", false).setMaxLength(PlayerBans.MAX_REASON),
            ),
        ) { event ->
            val admin = event.author
            val action = event.stringOption("aktion") ?: "liste"
            if (action == "liste") {
                event.replyEmbeds(listEmbed(bot.bans.all())).setEphemeral(true).await()
                return@SlashCommand
            }

            val user = event.getOption("spieler")?.asUser
            val rawCode = event.stringOption("friendcode")?.takeIf { it.isNotBlank() }
            if ((user == null) == (rawCode == null)) {
                event.notify("❌ Gib **entweder** `spieler` **oder** `friendcode` an.", Tone.ERROR)
                return@SlashCommand
            }
            if (rawCode != null && !isValidFriendCode(rawCode)) {
                event.notify("❌ Das sieht nicht nach einem Friendcode aus. Format: `name#1234`", Tone.ERROR)
                return@SlashCommand
            }
            val code = rawCode?.let(::normalizeFriendCode)
            val target = user?.asMention ?: "`$code`"

            if (action == "entsperren") {
                if (!bot.bans.unban(user?.idLong, code)) {
                    event.notify("ℹ️ $target ist nicht gesperrt.", Tone.WARNING)
                    return@SlashCommand
                }
                bot.audit.log(Actor.of(admin), "CrewStats-Sperre aufgehoben", target, Palette.SUCCESS)
                event.notify("✅ $target ist für CrewStats wieder freigeschaltet.", Tone.SUCCESS)
                return@SlashCommand
            }

            if (user != null && (user.isBot || user.idLong == admin.idLong)) {
                event.notify("🙃 Das geht nicht.", Tone.WARNING)
                return@SlashCommand
            }
            val reason = event.stringOption("grund")?.trim().orEmpty()
            val isNew = bot.bans.ban(user?.idLong, code, reason, admin.idLong)
            val linked = when {
                user != null -> bot.links.codesOf(user.idLong).joinToString(", ") { "`$it`" }.ifEmpty { "noch keine" }
                else -> bot.links.userFor(code!!)?.let { "<@$it>" } ?: "niemand"
            }
            bot.audit.log(
                Actor.of(admin),
                if (isNew) "Für CrewStats gesperrt" else "CrewStats-Sperre geändert",
                "$target" + (if (reason.isNotEmpty()) "\nGrund: ${escapeMarkdown(reason)}" else ""),
                Palette.ERROR,
            )
            event.notify(
                (if (isNew) "⛔ $target ist für CrewStats gesperrt." else "⛔ $target war schon gesperrt – Grund aktualisiert.") +
                    "\n${if (user != null) "Verknüpfte Friendcodes" else "Verknüpft mit"}: $linked\n\n" +
                    "Ignoriert werden ab jetzt: Runden, Auto-Mute, Lobby-Meldungen und Voice, wenn die Person hostet;" +
                    " XP, Stats und Erfolge aus Runden anderer Hosts; Rollenwünsche; Auto-Join, Lobby-Stats und" +
                    " Verknüpfen aus dem Spiel; die CrewStats-Befehle im Discord; Bestenlisten, Live-Beitreten und" +
                    " Voice auf der Website. Bisherige XP und Stats bleiben.",
                Tone.SUCCESS,
            )
        },
    )

    private fun listEmbed(bans: List<PlayerBan>): net.dv8tion.jda.api.entities.MessageEmbed {
        val lines = bans.sortedByDescending { it.at }.map { ban ->
            val who = ban.userId?.let { "<@$it>" } ?: "`${ban.friendCode}`"
            val reason = ban.reason.takeIf { it.isNotEmpty() }?.let { " – ${escapeMarkdown(it)}" } ?: ""
            val since = if (ban.at.toEpochMilli() > 0) " (<t:${ban.at.epochSecond}:d>)" else ""
            "$who$reason$since"
        }
        val embed = EmbedBuilder()
            .setTitle("⛔ Für CrewStats gesperrt")
            .setColor(Palette.ERROR)
            .setDescription(fitLines(lines, limit = 4000, reserve = 40).ifEmpty { "Niemand ist gesperrt." })
            .setFooter("${bans.size} Sperre(n) • /crewstats-sperre")
        return safeBuild(embed)
    }
}
