/* /lobby-benachrichtigung: DM, wenn eine Lobby aufgeht bzw. Freunde in einer Lobby sind (Opt-in, services/LobbyPing.kt). */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.LobbyPingMode
import de.amongusmanager.crewbot.services.LobbyPingRules
import de.amongusmanager.crewbot.services.LobbyPingSub
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.simpleEmbed
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData

class LobbyPingCommands(private val bot: CrewBot) {

    val commands = listOf(
        SlashCommand(
            "lobby-benachrichtigung", "DM, wenn eine Among-Us-Lobby aufgeht oder deine Freunde in einer Lobby sind",
            options = listOf(
                OptionData(OptionType.STRING, "modus", "Wann willst du eine DM bekommen?", true)
                    .addChoices(
                        Command.Choice("Bei jeder neuen Lobby", "alle"),
                        Command.Choice("Nur wenn Freunde da sind", "freunde"),
                        Command.Choice("Aus", "aus"),
                        Command.Choice("Einstellungen zeigen", "zeigen"),
                    ),
                OptionData(OptionType.USER, "freund", "Freund hinzufügen (mit entfernen:True wieder entfernen)", false),
                OptionData(OptionType.BOOLEAN, "entfernen", "Den gewählten Freund aus der Liste nehmen", false),
            ),
        ) { event ->
            val me = event.author
            val choice = event.getOption("modus")?.asString ?: "zeigen"
            val friend = event.memberOption("freund")
            val remove = event.getOption("entfernen")?.asBoolean == true
            val before = bot.lobbyPings.of(me.idLong)

            if (friend != null) {
                when {
                    friend.idLong == me.idLong -> return@SlashCommand event.notify("🙃 Dich selbst brauchst du nicht als Freund eintragen.", Tone.WARNING)
                    friend.user.isBot -> return@SlashCommand event.notify("🤖 Bots spielen kein Among Us.", Tone.WARNING)
                    !remove && friend.idLong !in before.friends && before.friends.size >= LobbyPingRules.MAX_FRIENDS ->
                        return@SlashCommand event.notify("❌ Höchstens ${LobbyPingRules.MAX_FRIENDS} Freunde – entferne zuerst einen.", Tone.ERROR)
                }
            }

            val after = if (choice == "zeigen" && friend == null) before else bot.lobbyPings.update(me.idLong) { sub ->
                val friends = when {
                    friend == null -> sub.friends
                    remove -> sub.friends - friend.idLong
                    else -> (sub.friends + friend.idLong).distinct()
                }
                LobbyPingSub(if (choice == "zeigen") sub.mode else LobbyPingMode.of(choice), friends)
            }

            event.deferReply(true).await()
            val notes = mutableListOf<String>()
            if (friend != null) notes += if (remove) "➖ ${friend.asMention} entfernt." else "➕ ${friend.asMention} ist jetzt dein Freund für Lobby-DMs."
            if (after.mode == LobbyPingMode.FRIENDS && after.friends.isEmpty()) {
                notes += "ℹ️ Noch keine Freunde eingetragen – füge sie mit `freund:@Name` hinzu."
            }
            // Beim Einschalten einmal testen, ob DMs ankommen
            if (before.mode == LobbyPingMode.OFF && after.mode != LobbyPingMode.OFF && !testDm(event.user)) {
                notes += "⚠️ Ich kann dir keine DM schicken. Erlaube in den Server-Einstellungen „Direktnachrichten von Servermitgliedern“."
            }
            event.hook.sendMessageEmbeds(statusEmbed(after, notes)).await()
        },
    )

    private fun statusEmbed(sub: LobbyPingSub, notes: List<String>): MessageEmbed {
        val (title, text) = when (sub.mode) {
            LobbyPingMode.ALL -> "🔔 Lobby-DMs an" to "Du bekommst eine DM bei jeder neuen Among-Us-Lobby."
            LobbyPingMode.FRIENDS -> "🔔 Lobby-DMs für Freunde" to "Du bekommst eine DM, wenn einer deiner Freunde hostet oder in einer Lobby ist."
            LobbyPingMode.OFF -> "🔕 Lobby-DMs aus" to "Du bekommst keine Lobby-DMs."
        }
        val embed = EmbedBuilder().setTitle(title).setColor(if (sub.mode == LobbyPingMode.OFF) Palette.DARK_GREY else Palette.ACCENT)
            .setDescription((listOf(text) + notes).joinToString("\n"))
        if (sub.friends.isNotEmpty()) {
            val lines = sub.friends.map { id ->
                if (bot.links.codesOf(id).isEmpty()) "<@$id> · noch nicht verknüpft, wird nicht erkannt" else "<@$id>"
            }
            embed.field("Freunde (${sub.friends.size}/${LobbyPingRules.MAX_FRIENDS})", lines.joinToString("\n"), inline = false)
        }
        embed.setFooter("Erkannt werden nur Spieler mit /verknuepfen, der Host braucht CrewStats. Volle Lobbys lösen nichts aus.")
        return embed.build()
    }

    private suspend fun testDm(user: User): Boolean = try {
        user.openPrivateChannel().await().sendMessageEmbeds(
            simpleEmbed("🔔 Lobby-Benachrichtigungen sind an. Hier melde ich mich, sobald eine Lobby aufgeht.", Palette.ACCENT),
        ).await()
        true
    } catch (e: Exception) {
        e.rethrowIfCancelled()
        false
    }
}
