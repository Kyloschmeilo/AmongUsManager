/* Kleine Module: Spaß, Admin und die automatisch erzeugte Hilfe. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.content.verdictFor
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.safeBuild
import de.amongusmanager.crewbot.ui.views.AdminPanelView
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import kotlin.random.Random

/** Spaß-Befehle. */
class FunModule : CrewModule("Spaß", "🎲") {
    override val commands = listOf(
        SlashCommand("impostor-test", "Berechnet deine heutige Impostor-Wahrscheinlichkeit") { event ->
            val percent = Random.nextInt(1, 101)
            val embed = EmbedBuilder()
                .setTitle("🔬 LÜGENDETEKTOR-TEST 🔬")
                .setDescription(
                    "Untersuchung von ${event.user.asMention} abgeschlossen.\n\n" +
                        "Das System sagt...\n# 📊 Verdachtsstufe: $percent%\n\n" +
                        verdictFor(percent).text
                )
                .setColor(if (percent < 75) Palette.PURPLE else Palette.ERROR)
                .setThumbnail(event.author.effectiveAvatarUrl)
                .build()
            event.replyEmbeds(embed).await()
        },
    )
}

/** Admin-Werkzeuge. */
class AdminModule(private val bot: CrewBot) : CrewModule("Admin", "🛠️") {
    override val commands = listOf(
        SlashCommand(
            "admin-panel", "XP/Level eines Spielers ändern oder zurücksetzen", Permission.ADMINISTRATOR,
            options = listOf(OptionData(OptionType.USER, "user", "Welcher Spieler?", true)),
        ) { event ->
            val target = event.memberOption("user")!!
            val view = AdminPanelView(bot.levels, bot.audit, target, event.author)
            event.replyEmbeds(view.buildEmbed()).setComponents(view.components()).setEphemeral(true).await()
        },
    )
}

/** /hilfe: automatisch generierte Übersicht aller Befehle. */
class HelpModule(private val bot: CrewBot) : CrewModule("Hilfe", "📖") {
    override val commands = listOf(
        SlashCommand("hilfe", "Zeigt alle Befehle des Bots") { event ->
            val embed = EmbedBuilder()
                .setTitle("📖 Crew-Handbuch")
                .setDescription("Alle Befehle auf einen Blick. Einfach anklicken zum Benutzen!")
                .setColor(Palette.BRAND)
            for (module in bot.modules) {
                if (module === this) continue
                val lines = module.commands.map { command ->
                    "${bot.mentionCommand(command.name)}${if (command.permission != null) " 🔒" else ""} – ${command.description}"
                }
                if (lines.isNotEmpty()) embed.field("${module.emoji} ${module.name}", lines.joinToString("\n"), inline = false)
            }
            embed.setFooter("🔒 = nur für Hosts/Admins")
            event.replyEmbeds(safeBuild(embed)).setEphemeral(true).await()
        },
    )
}
