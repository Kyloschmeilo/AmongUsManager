/* Rollen-Panel: Nachrichten mit Rollen-Buttons (Rolle holen/abgeben), die Buttons zeigen die Mitgliederzahl. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.SelfRoleGroup
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.planRoleToggle
import de.amongusmanager.crewbot.services.selfRoleGroupOf
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.slf4j.LoggerFactory

object RolePanel {
    /** custom_id = `selfrole:<Rollen-ID>` – dauerhaft, nie ändern (sonst gehen gepostete Panels kaputt). */
    val PATTERN = Regex("""selfrole:(\d+)""")
    private val log = LoggerFactory.getLogger(RolePanel::class.java)

    fun customId(roleId: Long): String = "selfrole:$roleId"

    fun embed(group: SelfRoleGroup): MessageEmbed {
        val howTo = if (group.exclusive) {
            "Klick auf einen Button, um die Rolle zu bekommen – du kannst nur eine davon haben. " +
                "Nochmal klicken gibt sie wieder ab."
        } else {
            "Klick auf die Buttons, um Rollen zu bekommen – nochmal klicken gibt sie wieder ab. " +
                "Die Zahl zeigt, wie viele die Rolle haben."
        }
        return EmbedBuilder()
            .setTitle(group.title)
            .setDescription("${group.description}\n\n$howTo")
            .setColor(Palette.BLURPLE)
            .build()
    }

    /** Ein Button je Rolle, Beschriftung „Name · Anzahl“; 4 pro Reihe (ab 21 Rollen 5). */
    fun components(group: SelfRoleGroup, counts: Map<Long, Int>): List<ActionRow> {
        require(group.roles.size in 1..25) { "Gruppe ${group.key}: 1 bis 25 Rollen erlaubt" }
        val perRow = if (group.roles.size > 20) 5 else 4
        return group.roles.map { role ->
            Button.of(ButtonStyle.SECONDARY, customId(role.roleId), "${role.name} · ${counts[role.roleId] ?: 0}")
                .withEmoji(Emoji.fromUnicode(role.emoji))
        }.chunked(perRow).map { ActionRow.of(it) }
    }

    suspend fun onClick(event: ButtonInteractionEvent, roleId: Long, bot: CrewBot) {
        val group = selfRoleGroupOf(roleId)
        val member = event.member
        val guild = event.guild
        val role = guild?.getRoleById(roleId)
        if (group == null || member == null || role == null) {
            event.notify("❌ Diese Rolle gibt es nicht mehr.", Tone.ERROR)
            return
        }
        event.deferReply(true).await()

        val toggle = planRoleToggle(group, member.roles.map { it.idLong }.toSet(), roleId)
        val remove = toggle.remove.mapNotNull { guild.getRoleById(it) }
        try {
            if (toggle.add != null) {
                guild.modifyMemberRoles(member, listOf(role), remove).reason("Rollen-Panel").await()
            } else {
                guild.removeRoleFromMember(member, role).reason("Rollen-Panel").await()
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (!e.isForbidden()) throw e
            log.warn("Rollen-Panel: {} konnte nicht geändert werden: {}", role.name, e.describe())
            event.notify(
                "🚫 Ich darf die Rolle ${role.asMention} nicht vergeben. Ein Admin muss meine Rolle " +
                    "über die Spiel-Rollen schieben (und „Rollen verwalten“ geben).",
                Tone.ERROR,
            )
            return
        }

        val text = if (toggle.add != null) {
            val replaced = if (remove.isEmpty()) "" else " (statt ${remove.joinToString(", ") { it.asMention }})"
            "✅ Du hast jetzt die Rolle ${role.asMention}$replaced."
        } else {
            "➖ Du hast die Rolle ${role.asMention} abgegeben."
        }
        event.notify(text, Tone.SUCCESS)
        bot.rolePanel.requestRefresh()
    }
}
