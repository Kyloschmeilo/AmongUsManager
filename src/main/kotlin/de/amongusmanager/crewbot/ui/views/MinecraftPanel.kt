/* /dc: Minecraft-Ankündigungen einstellen – Kanal, Nachrichtenarten, Vorschau und Webhook fürs Plugin. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.discordTimestamp
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.AuditLog
import de.amongusmanager.crewbot.services.McSetupError
import de.amongusmanager.crewbot.services.MinecraftService
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildMinecraftEmbed
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.sampleMinecraftEvents
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import java.time.Duration

const val PLUGIN_CONFIG_PATH = "plugins/CoreHold/config.yml"

private fun typesText(types: Set<McEventType>): String =
    McEventType.entries.filter { it in types }.joinToString(", ") { it.info.label }.ifEmpty { "keine" }

class MinecraftPanelView(
    private val service: MinecraftService,
    private val audit: AuditLog,
    private val actor: Member,
) : SessionView(actor.idLong, Duration.ofSeconds(600)) {

    fun buildEmbed(note: String? = null): MessageEmbed {
        val status = when {
            service.webhookId == null -> "❌ Noch nicht verbunden – klick auf **Webhook-URL**"
            service.lastReceived != null -> "✅ Letzte Nachricht ${service.lastReceived!!.discordTimestamp("R")}"
            else -> "⏳ Seit dem Bot-Start noch nichts empfangen"
        }
        val embed = EmbedBuilder()
            .setTitle("⛏️ Minecraft → Discord")
            .setDescription("Ankündigungen vom CoreSMP-Server. Hier stellst du ein, **wo** sie erscheinen und **welche**.")
            .setColor(Palette.SUCCESS)
            .field("📢 Kanal", "<#${service.channelId}>")
            .field("🔌 Plugin", status)
            .field(
                "📨 Nachrichten",
                McEventType.entries.joinToString("\n") {
                    "${if (service.isEnabled(it)) "✅" else "❌"} ${it.info.emoji} ${it.info.label}"
                },
                inline = false,
            )
        if (note != null) embed.setFooter(note)
        return embed.build()
    }

    override fun render(): List<ActionRow> {
        val channelSelect = EntitySelectMenu.create(
            key("channel") { onChannel(it as EntitySelectInteractionEvent) },
            EntitySelectMenu.SelectTarget.CHANNEL,
        )
            .setPlaceholder("📢 Anderen Kanal für die Ankündigungen wählen")
            .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
            .build()

        val typeSelect = StringSelectMenu.create(key("types") { onTypes(it as StringSelectInteractionEvent) })
            .setPlaceholder("📨 Welche Nachrichten sollen gesendet werden?")
            .setRequiredRange(0, McEventType.entries.size)
            .addOptions(
                McEventType.entries.map { type ->
                    SelectOption.of(type.info.label, type.value)
                        .withEmoji(Emoji.fromUnicode(type.info.emoji))
                        .withDescription(type.info.description)
                        .withDefault(service.isEnabled(type))
                }
            )
            .build()

        return listOf(
            ActionRow.of(channelSelect),
            ActionRow.of(typeSelect),
            ActionRow.of(
                button("preview", ButtonStyle.SECONDARY, "👀 Vorschau") { preview(it as ButtonInteractionEvent) },
                button("webhook", ButtonStyle.PRIMARY, "🔗 Webhook-URL") { webhook(it as ButtonInteractionEvent) },
            ),
        )
    }

    private suspend fun refresh(event: GenericComponentInteractionCreateEvent, note: String? = null) {
        event.editMessageEmbeds(buildEmbed(note)).setComponents(components()).await()
    }

    // ---------- Callbacks ----------

    private suspend fun onChannel(event: EntitySelectInteractionEvent) {
        val guild = event.guild ?: return
        val picked = event.mentions.channels.firstOrNull()
        val channel = picked?.let { guild.getGuildChannelById(it.idLong) } as? GuildMessageChannel
        if (channel == null) {
            refresh(event, "❌ Diesen Kanal kann ich nicht sehen.")
            return
        }
        val canPost = guild.selfMember.hasPermission(
            channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS,
        )
        if (!canPost) {
            refresh(event, "❌ In #${channel.name} fehlen mir Rechte (Nachrichten senden + Links einbetten).")
            return
        }

        val old = service.channelId
        service.setChannel(channel.idLong)
        refresh(event, "✅ Ankündigungen kommen ab jetzt in #${channel.name}.")
        if (old != channel.idLong) {
            audit.log(Actor.of(actor), "Minecraft-Kanal geändert", "<#$old> → ${channel.asMention}")
        }
    }

    private suspend fun onTypes(event: StringSelectInteractionEvent) {
        val before = service.enabled
        val after = event.values.mapNotNull { McEventType.fromValue(it) }.toSet()
        service.setEnabled(after)
        refresh(event, if (after.isNotEmpty()) "✅ Auswahl gespeichert." else "🔕 Alle Minecraft-Nachrichten sind aus.")
        if (before != after) {
            audit.log(
                Actor.of(actor),
                "Minecraft-Nachrichten geändert",
                "**Vorher:** ${typesText(before)}\n**Jetzt:** ${typesText(after)}",
            )
        }
    }

    private suspend fun preview(event: ButtonInteractionEvent) {
        var samples = sampleMinecraftEvents().filter { service.isEnabled(it.type) }
        var text = "👀 So sehen die Nachrichten aus (Beispiele, nur für dich sichtbar):"
        if (samples.isEmpty()) {
            samples = sampleMinecraftEvents()
            text = "🔕 Gerade ist alles aus – so *würden* die Nachrichten aussehen:"
        }
        val embeds = samples.map { buildMinecraftEmbed(it, "CoreSMP") }.take(10)
        event.reply(text).setEmbeds(embeds).setEphemeral(true).await()
    }

    private suspend fun webhook(event: ButtonInteractionEvent) {
        event.deferEdit().await()
        val url = try {
            service.webhookUrl(event.jda, reason = "/dc von ${Actor.tagOf(event.user)}")
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (e.isForbidden()) {
                event.notify("❌ Mir fehlt im Log-Channel das Recht **Webhooks verwalten**.", Tone.ERROR)
            } else {
                event.notify("❌ Webhook konnte nicht eingerichtet werden: ${if (e is McSetupError) e.message else e.describe()}", Tone.ERROR)
            }
            return
        }

        event.hook.editOriginalEmbeds(buildEmbed()).setComponents(components()).await()
        val view = WebhookView(service, audit, actor)
        event.hook.sendMessageEmbeds(webhookEmbed(url).build()).setComponents(view.components()).setEphemeral(true).await()
    }
}

fun webhookEmbed(url: String): EmbedBuilder =
    EmbedBuilder()
        .setTitle("🔗 Plugin mit dem Bot verbinden")
        .setDescription(
            "1. Auf dem Minecraft-Server `$PLUGIN_CONFIG_PATH` öffnen\n" +
                "2. Ganz unten bei `discord:` die URL eintragen (mit Anführungszeichen):\n" +
                "```yaml\ndiscord:\n  webhook-url: \"$url\"\n```" +
                "3. `/special reload` ausführen (oder Server neu starten)\n\n" +
                "⚠️ **Die URL ist geheim!** Wer sie kennt, kann gefälschte Nachrichten einschleusen." +
                " Falls sie rausgerutscht ist: **Neue URL erzeugen** – die alte funktioniert dann nicht mehr."
        )
        .setColor(Palette.INFO)

/** Unter der Webhook-URL: bei Bedarf eine neue erzeugen (die alte wird ungültig). */
class WebhookView(
    private val service: MinecraftService,
    private val audit: AuditLog,
    private val actor: Member,
) : SessionView(actor.idLong, Duration.ofSeconds(600)) {

    override fun render(): List<ActionRow> = listOf(
        ActionRow.of(button("renew", ButtonStyle.DANGER, "♻️ Neue URL erzeugen") { renew(it as ButtonInteractionEvent) })
    )

    private suspend fun renew(event: ButtonInteractionEvent) {
        event.deferEdit().await()
        val url = try {
            service.webhookUrl(event.jda, reason = "/dc von ${Actor.tagOf(event.user)}: neue URL", renew = true)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (e.isForbidden()) {
                event.notify("❌ Mir fehlt im Log-Channel das Recht **Webhooks verwalten**.", Tone.ERROR)
            } else {
                event.notify("❌ Webhook konnte nicht erneuert werden: ${if (e is McSetupError) e.message else e.describe()}", Tone.ERROR)
            }
            return
        }

        val embed = webhookEmbed(url).setFooter("♻️ Neue URL erzeugt – die alte ist ab sofort ungültig.").build()
        event.hook.editOriginalEmbeds(embed).setComponents(components()).await()
        audit.log(Actor.of(actor), "Minecraft-Webhook erneuert", "Die alte Plugin-URL ist ungültig.")
    }
}
