/* Kleine Module: Spaß, Admin und die automatisch erzeugte Hilfe. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.SELF_ROLE_GROUPS
import de.amongusmanager.crewbot.content.Changelog
import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.tutorial
import de.amongusmanager.crewbot.content.verdictFor
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.RolePanelMessage
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildChangelogEmbed
import de.amongusmanager.crewbot.ui.buildTutorialEmbeds
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.safeBuild
import de.amongusmanager.crewbot.ui.views.AdminPanelView
import de.amongusmanager.crewbot.ui.views.RolePanel
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.interactions.commands.Command
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
        SlashCommand(
            "rollen-panel", "Postet die Rollen-Auswahl mit Buttons im Rollen-Channel", Permission.ADMINISTRATOR,
        ) { event ->
            event.deferReply(true).await()
            val channel = event.jda.getChannelById(GuildMessageChannel::class.java, Channels.ROLES)
            if (channel == null) {
                event.notify("❌ Den Rollen-Channel (${Channels.ROLES}) finde ich nicht.", Tone.ERROR)
                return@SlashCommand
            }
            val guild = channel.guild
            for (group in SELF_ROLE_GROUPS) {
                val counts = bot.rolePanel.counts(guild, group)
                val message = channel.sendMessageEmbeds(RolePanel.embed(group))
                    .setComponents(RolePanel.components(group, counts)).await()
                bot.rolePanel.track(RolePanelMessage(group.key, message.channel.idLong, message.idLong))
                bot.rolePanel.markShown(message.idLong, counts)
            }
            val missing = SELF_ROLE_GROUPS.flatMap { it.roles }.filter { guild.getRoleById(it.roleId) == null }
            val note = if (missing.isEmpty()) "" else "\n⚠️ Diese Rollen gibt es auf dem Server nicht: " +
                missing.joinToString(", ") { it.name }
            event.notify("✅ Rollen-Panel gepostet in ${channel.asMention}. Ältere Panels funktionieren weiter.$note", Tone.SUCCESS)
        },
        SlashCommand(
            "changelog", "Postet die Neuerungen des letzten Updates in diesen Channel", Permission.ADMINISTRATOR,
        ) { event ->
            event.channel.sendMessageEmbeds(buildChangelogEmbed(bot::mentionCommand)).await()
            event.notify("✅ Changelog ${Changelog.VERSION} gepostet.", Tone.SUCCESS)
        },
        SlashCommand(
            "tutorial", "Postet ein Tutorial für Spieler in diesen Channel oder Thread", Permission.ADMINISTRATOR,
            options = listOf(
                OptionData(OptionType.STRING, "thema", "Welches Tutorial?", true)
                    .addChoices(TUTORIALS.map { Command.Choice(it.label, it.key) }),
            ),
        ) { event ->
            val tutorial = tutorial(event.stringOption("thema"))
            if (tutorial == null) {
                event.notify("❌ Dieses Tutorial gibt es nicht.", Tone.ERROR)
                return@SlashCommand
            }
            // Lange Tutorials (alle Rollen) kommen in mehreren Nachrichten nacheinander
            for (embed in buildTutorialEmbeds(tutorial, bot::mentionCommand)) event.channel.sendMessageEmbeds(embed).await()
            event.notify("✅ Tutorial „${tutorial.label}“ gepostet.", Tone.SUCCESS)
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
