/*
 * Minecraft-Kopplung: Ereignisse des CoreSMP-Plugins (CoreHold) lesen und die /dc-Einstellungen speichern.
 *
 * Das Plugin schickt seine Ereignisse als JSON über einen Webhook im Log-Channel. Der Bot löscht diese
 * Rohdaten wieder und postet daraus Embeds im eingestellten Channel – aber nur die eingeschalteten Arten.
 * JSON-Format = `DiscordBridge.java` / `DiscordListener.java` im CoreSMP-Projekt. Beide Seiten gemeinsam ändern!
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.isNotFound
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.slf4j.LoggerFactory
import java.time.Instant

/** Die Webhook-Nachricht ist kein gültiges Ereignis-Paket. */
class McPayloadError(message: String) : Exception(message)

/** Der Webhook für das Plugin konnte nicht eingerichtet werden. */
class McSetupError(message: String) : Exception(message)

data class McEvent(
    val type: McEventType,
    val player: String,
    val data: Map<String, JsonElement> = emptyMap(), // alle Felder aus dem JSON
) {
    /** Rohwert eines Felds (null, wenn es fehlt oder null ist). */
    fun get(key: String): JsonElement? = data[key]?.takeUnless { it is JsonNull }

    /** Feld als Text, wie Python `str(...)`. */
    fun text(key: String): String? = get(key)?.pyStr()

    /** Python-Wahrheitswert eines Felds. */
    fun truthy(key: String): Boolean = get(key).pyTruthy()
}

data class McPayload(val server: String, val events: List<McEvent>)

/** Liest den Nachrichtentext des Plugin-Webhooks: {"v": 1, "server": "...", "events": [...]}. */
fun parsePayload(text: String): McPayload {
    val raw = try {
        parseJson(text)
    } catch (e: Exception) {
        throw McPayloadError("kein gültiges JSON (${e.message})")
    }
    if (raw !is JsonObject || raw["events"] !is JsonArray) throw McPayloadError("Feld 'events' fehlt")

    val events = ArrayList<McEvent>()
    for (item in raw["events"] as JsonArray) {
        if (item !is JsonObject) continue
        val typeValue = (item["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val type = McEventType.fromValue(typeValue) ?: continue // Art aus einer neueren Plugin-Version – überspringen
        val player = item["player"].let { if (it.pyTruthy()) it.pyStr() else "?" }
        events.add(McEvent(type, player, item))
    }
    val server = raw["server"].let { if (it.pyTruthy()) it.pyStr() else "Minecraft" }
    return McPayload(server, events)
}

/** Webhook, Ziel-Channel und eingeschaltete Nachrichtenarten (Tabelle `settings`, Schlüssel `minecraft.*`). */
class MinecraftService(
    private val db: Database,
    private val defaultChannelId: Long,
    private val inboxChannelId: Long, // hier landen die Rohdaten des Plugins
) {
    companion object {
        const val WEBHOOK_NAME = "CoreSMP Plugin"
    }

    private val log = LoggerFactory.getLogger(MinecraftService::class.java)

    /** Nur zur Anzeige in /dc, wird nicht gespeichert. */
    var lastReceived: Instant? = null
        private set

    val webhookId: Long? get() = db.getSetting("minecraft.webhook_id")?.toLongOrNull()
    private val storedWebhookUrl: String? get() = db.getSetting("minecraft.webhook_url")

    val channelId: Long get() = db.getSetting("minecraft.channel_id")?.toLongOrNull() ?: defaultChannelId

    val enabled: Set<McEventType>
        get() {
            val stored = db.getSetting("minecraft.enabled") ?: return McEventType.entries.toSet()
            val values = stored.split(",").filter { it.isNotEmpty() }.toSet()
            return McEventType.entries.filter { it.value in values }.toSet()
        }

    // ---------- Einstellungen ----------

    fun isEnabled(type: McEventType): Boolean = type in enabled

    fun setChannel(channelId: Long) {
        db.setSetting("minecraft.channel_id", channelId.toString())
    }

    fun setEnabled(types: Set<McEventType>) {
        db.setSetting("minecraft.enabled", McEventType.entries.filter { it in types }.joinToString(",") { it.value })
    }

    fun markReceived() {
        lastReceived = Instant.now()
    }

    // ---------- Webhook fürs Plugin ----------

    /** URL für die config.yml des Plugins. Legt den Webhook bei Bedarf (neu) an – eine alte URL wird dann ungültig. */
    suspend fun webhookUrl(jda: JDA, reason: String, renew: Boolean = false): String {
        val currentId = webhookId
        if (currentId != null && !renew) {
            try {
                val webhook = jda.retrieveWebhookById(currentId).await()
                val url = webhookUrlOf(webhook) ?: storedWebhookUrl
                if (url != null) return url
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                if (!e.isNotFound()) throw e // im Discord gelöscht -> neu anlegen
            }
        }

        val channel = jda.getTextChannelById(inboxChannelId)
            ?: throw McSetupError("Log-Channel $inboxChannelId nicht gefunden.")
        if (currentId != null) {
            try {
                jda.retrieveWebhookById(currentId).await().delete().reason(reason).await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
            }
        }
        val webhook = channel.createWebhook(WEBHOOK_NAME).reason(reason).await() // fehlende Rechte -> Aufrufer
        val url = webhookUrlOf(webhook) ?: throw McSetupError("Discord hat keinen Webhook-Token geliefert.")
        db.transaction {
            db.setSetting("minecraft.webhook_id", webhook.id)
            db.setSetting("minecraft.webhook_url", url)
        }
        return url
    }

    /** Verschiebt einen bestehenden Webhook in den Inbox-Channel. Die URL bleibt dabei gleich. */
    suspend fun moveWebhookToInbox(jda: JDA) {
        val currentId = webhookId ?: return
        val channel: TextChannel = jda.getTextChannelById(inboxChannelId) ?: return
        val webhook = jda.retrieveWebhookById(currentId).await()
        if (webhook.channel.idLong != channel.idLong) {
            webhook.manager.setChannel(channel).reason("Minecraft-Rohdaten in den Log-Channel verschoben").await()
            log.info("Minecraft-Webhook nach #{} verschoben", channel.name)
        }
    }
}

/**
 * Webhook-URL im Format https://discord.com/api/webhooks/<id>/<token>.
 * Bewusst selbst gebaut: JDA liefert „…/api/v10/webhooks/…“, die Mod akzeptiert aber nur „/api/webhooks/“.
 */
fun webhookUrlOf(webhook: Webhook): String? {
    val token = webhook.token ?: return null
    return "https://discord.com/api/webhooks/${webhook.id}/$token"
}
