/* Slash-Commands und Feature-Module (früher: Cogs). Name + Emoji eines Moduls erscheinen automatisch in /hilfe. */
package de.amongusmanager.crewbot.core

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.guild.voice.GenericGuildVoiceEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

/** Fehler, dessen Nachricht direkt (als rote Meldung) angezeigt wird. */
class CommandError(message: String) : Exception(message)

class SlashCommand(
    val name: String,
    val description: String,
    /** Nur wer dieses Recht hat, sieht/benutzt den Befehl (🔒 in /hilfe). */
    val permission: Permission? = null,
    val options: List<OptionData> = emptyList(),
    val handler: suspend (SlashCommandInteractionEvent) -> Unit,
) {
    fun data(): SlashCommandData {
        val data = Commands.slash(name, description)
            .addOptions(options)
            .setContexts(InteractionContextType.GUILD)
        if (permission != null) data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(permission))
        return data
    }
}

abstract class CrewModule(val name: String, val emoji: String) {
    open val commands: List<SlashCommand> = emptyList()

    /** Jede Nachricht in einem Server-Channel (für die Webhooks von Mod und Minecraft-Plugin). */
    open suspend fun onMessage(event: MessageReceivedEvent) {}

    /** Jede Voice-Änderung (Beitreten, Verlassen, Mute, Taub …). */
    open suspend fun onVoice(event: GenericGuildVoiceEvent) {}

    /** Einmal, sobald der Bot verbunden ist. */
    open suspend fun onReady() {}
}

// ---------- Optionen lesen ----------

fun SlashCommandInteractionEvent.stringOption(name: String): String? = getOption(name)?.asString

fun SlashCommandInteractionEvent.intOption(name: String): Int? = getOption(name)?.asLong?.toInt()

/** Mitglied aus einer Nutzer-Option; ist die Person nicht (mehr) auf dem Server, gibt es eine Fehlermeldung. */
fun SlashCommandInteractionEvent.memberOption(name: String): Member? {
    val option = getOption(name) ?: return null
    return option.asMember ?: throw CommandError("❌ ${option.asUser.asMention} ist nicht (mehr) auf dem Server.")
}

/** Das Mitglied, das den Befehl ausführt (Befehle gibt es nur auf dem Server). */
val SlashCommandInteractionEvent.author: Member
    get() = member ?: throw CommandError("🚫 Dieser Bot funktioniert nur auf dem Server.")

fun Member.hasPermissionIn(channel: GuildChannel?, permission: Permission): Boolean =
    if (channel != null) hasPermission(channel, permission) else hasPermission(permission)
