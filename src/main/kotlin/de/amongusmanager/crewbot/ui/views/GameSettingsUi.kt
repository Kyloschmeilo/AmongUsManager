/* /rundensettings: Lobbyeinstellungen der letzten Runde, umschaltbar per Kategorie-Buttons. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.content.CATEGORIES
import de.amongusmanager.crewbot.content.Category
import de.amongusmanager.crewbot.content.GENERAL
import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.content.ROLES_CATEGORY
import de.amongusmanager.crewbot.content.ROLE_SETTINGS
import de.amongusmanager.crewbot.content.Setting
import de.amongusmanager.crewbot.content.knownOptionKeys
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.GameSettings
import de.amongusmanager.crewbot.services.OPTION_KINDS
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.fitLines
import de.amongusmanager.crewbot.ui.safeBuild
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import java.time.Duration

fun buildSettingsEmbed(settings: GameSettings, category: Category = GENERAL): MessageEmbed {
    val embed = EmbedBuilder()
        .setTitle("⚙️ Rundeneinstellungen · ${category.emoji} ${category.label}")
        .setColor(Palette.BRAND)
        .setTimestamp(settings.capturedAt)
    if (category == ROLES_CATEGORY) {
        addRoles(embed, settings)
    } else {
        if (category == GENERAL) {
            embed.setDescription(
                "🗺️ **Map:** ${settings.map}\n" +
                    "👥 **Spieler:** ${settings.players}/${settings.maxPlayers}\n" +
                    "🔪 **Impostor:** ${settings.numImpostors}"
            )
        }
        for (setting in category.settings) {
            format(settings, setting)?.let { embed.field(setting.label, it, inline = true) }
        }
        if (category == GENERAL) {
            val extra = unknownOptions(settings)
            if (extra.isNotEmpty()) embed.field("🧩 Weitere Einstellungen", extra, inline = false)
        }
    }

    val lobby = if (settings.lobbyCode.isNotEmpty()) "Lobby ${settings.lobbyCode} • " else ""
    embed.setFooter("${lobby}Stand beim Start der letzten Runde")
    return safeBuild(embed)
}

private fun format(settings: GameSettings, setting: Setting): String? =
    settings.value(setting.kind, setting.key)?.let(setting.format)

private fun roleName(name: String): String {
    val info = ROLES[name]
    return if (info != null) "${info.emoji} $name" else "🎭 $name"
}

private fun addRoles(embed: EmbedBuilder, settings: GameSettings) {
    for ((impostor, title) in listOf(false to "🟢 Crew-Rollen", true to "🔴 Impostor-Rollen")) {
        val active = ArrayList<String>()
        val inactive = ArrayList<String>()
        for (role in ROLE_SETTINGS) {
            val rate = settings.roles[role.gameName]
            if (role.impostor != impostor || rate == null) continue
            if (!rate.active) {
                inactive.add(role.name)
                continue
            }
            val details = role.settings.mapNotNull { s -> format(settings, s)?.let { "${s.label} $it" } }.joinToString(" · ")
            val line = "**${roleName(role.name)}** – ${rate.count}× · ${rate.chance} %"
            active.add(line + if (details.isNotEmpty()) "\n└ $details" else "")
        }
        val lines = if (active.isEmpty()) mutableListOf("Keine aktiv") else active
        if (inactive.isNotEmpty()) lines.add("*Aus: ${inactive.joinToString(", ")}*")
        embed.field(title, fitLines(lines), inline = false)
    }

    // Rollen aus neueren Spielversionen, die der Bot noch nicht kennt
    val known = ROLE_SETTINGS.map { it.gameName }.toSet()
    val unknown = settings.roles.filter { (name, rate) -> name !in known && rate.active }
        .map { (name, rate) -> "**${roleName(name)}** – ${rate.count}× · ${rate.chance} %" }
    if (unknown.isNotEmpty()) embed.field("🆕 Weitere Rollen", fitLines(unknown), inline = false)
}

private fun unknownOptions(settings: GameSettings): String {
    val known = knownOptionKeys()
    val lines = OPTION_KINDS.flatMap { kind ->
        (settings.options[kind] ?: emptyMap()).filterKeys { it !in known }.map { (key, value) -> "`$key`: ${value.pyStr()}" }
    }
    return fitLines(lines)
}

/** Ein Button pro Kategorie; der aktive ist hervorgehoben. Jeder darf umschalten. */
class GameSettingsView(private val settings: GameSettings) : SessionView(null, Duration.ofSeconds(600)) {
    private var current: Category = GENERAL
    private var expired = false

    /** Die gesendete Nachricht – zum Deaktivieren der Buttons nach Ablauf. */
    var message: Message? = null

    override fun render(): List<ActionRow> = listOf(
        ActionRow.of(
            CATEGORIES.map { category ->
                button(
                    "cat:${category.key}",
                    if (category == current) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                    category.label,
                    category.emoji,
                    disabled = expired,
                ) { event ->
                    current = category
                    (event as ButtonInteractionEvent)
                        .editMessageEmbeds(buildSettingsEmbed(settings, category))
                        .setComponents(components())
                        .await()
                }
            }
        )
    )

    override suspend fun onTimeout() {
        expired = true
        val target = message ?: return
        try {
            target.editMessageComponents(components()).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
        }
    }
}
