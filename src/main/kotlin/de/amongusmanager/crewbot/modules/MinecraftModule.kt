/* Minecraft-Kopplung: /dc und Empfang der Ankündigungen vom CoreSMP-Plugin (CoreHold). */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.McPayloadError
import de.amongusmanager.crewbot.services.parsePayload
import de.amongusmanager.crewbot.ui.buildMinecraftEmbed
import de.amongusmanager.crewbot.ui.views.MinecraftPanelView
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.slf4j.LoggerFactory

class MinecraftModule(private val bot: CrewBot) : CrewModule("Minecraft", "⛏️") {
    private val log = LoggerFactory.getLogger(MinecraftModule::class.java)
    private val sendLock = Mutex() // Reihenfolge der Ankündigungen beibehalten

    override val commands = listOf(
        SlashCommand("dc", "Minecraft-Ankündigungen: Kanal und Nachrichten einstellen", Permission.ADMINISTRATOR) { event ->
            val view = MinecraftPanelView(bot.minecraft, bot.audit, event.author)
            event.replyEmbeds(view.buildEmbed()).setComponents(view.components()).setEphemeral(true).await()
        },
    )

    // ==========================================
    // EMPFANG DER PLUGIN-NACHRICHTEN
    // ==========================================

    override suspend fun onReady() {
        // Liegt der Webhook noch im alten Channel, zieht er um – die URL im Plugin bleibt gleich
        try {
            bot.minecraft.moveWebhookToInbox(bot.jda)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Minecraft-Webhook konnte nicht verschoben werden (Recht „Webhooks verwalten“?): {}", e.describe())
        }
    }

    override suspend fun onMessage(event: MessageReceivedEvent) {
        val service = bot.minecraft
        val message = event.message
        val webhookId = service.webhookId ?: return
        if (!message.isWebhookMessage || message.author.idLong != webhookId) return
        if (message.contentRaw.isEmpty()) {
            log.warn("Minecraft-Nachricht ohne Text – ist der Message Content Intent aktiv?")
            return
        }
        val payload = try {
            parsePayload(message.contentRaw)
        } catch (e: McPayloadError) {
            log.warn("Minecraft-Nachricht unlesbar: {}", e.message)
            return
        }
        service.markReceived()

        val embeds = payload.events
            .filter { service.isEnabled(it.type) }
            .map { buildMinecraftEmbed(it, payload.server, message.timeCreated.toInstant()) }
        if (embeds.isNotEmpty()) {
            val channel = bot.jda.getChannelById(GuildMessageChannel::class.java, service.channelId)
            if (channel == null) {
                log.warn("Minecraft-Channel {} nicht gefunden – /dc prüfen", service.channelId)
                return // Rohdaten bleiben im Log-Channel liegen
            }
            try {
                sendLock.withLock {
                    for (chunk in embeds.chunked(10)) { // max. 10 Embeds pro Nachricht
                        channel.sendMessageEmbeds(chunk).setAllowedMentions(emptySet()).await()
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.warn("Minecraft-Ankündigung konnte nicht gesendet werden: {}", e.describe())
                return
            }
        }

        try {
            message.delete().await()
        } catch (e: Exception) {
            e.rethrowIfCancelled() // fehlendes Recht „Nachrichten verwalten“ – nicht schlimm
        }
    }
}
