/*
 * Auto-Join: /autojoin gibt Spielern die Auto-Join-URL für CrewStats und beantwortet die Lobby-Anfragen der Mod.
 * Läuft die Stats-Website, ist die URL eine Website-Adresse (services/ModApi.kt) – dort antwortet die Website selbst.
 * Ältere Webhook-URLs funktionieren weiter (onMessage).
 */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.AutoJoinProtocol
import de.amongusmanager.crewbot.services.PlayerInfoProtocol
import de.amongusmanager.crewbot.services.PlayerInfoRequest
import de.amongusmanager.crewbot.services.webhookUrlOf
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory

private const val AUTOJOIN_CLEANUP_MS = 30_000L // die Mod löscht ihre Anfrage selbst – sonst räumt der Bot danach auf
private const val AUTOJOIN_CONFIG = """Among Us\BepInEx\config\de.amongusmanager.crewstats.cfg"""

class AutoJoinCommands(private val bot: CrewBot) {
    private val log = LoggerFactory.getLogger(AutoJoinCommands::class.java)
    private val webhookTokens = HashMap<Long, String>() // nur im Speicher (Auto-Join- und Mod-Webhook)

    val commands = listOf(
        SlashCommand(
            "autojoin", "Zeigt die Auto-Join-URL für CrewStats (tritt automatisch der neuesten Lobby bei)",
            options = listOf(
                OptionData(OptionType.BOOLEAN, "neu", "Nur Admins: neue URL erzeugen, die alte wird ungültig", false),
            ),
        ) { event ->
            val renew = event.getOption("neu")?.asBoolean == true
            if (renew && !event.author.hasPermission(Permission.ADMINISTRATOR)) {
                event.notify("🚫 Nur Admins dürfen eine neue Auto-Join-URL erzeugen.", Tone.ERROR)
                return@SlashCommand
            }
            event.deferReply(true).await()

            // Läuft die Stats-Website, fragt die Mod dort (eine Anfrage statt Webhook-Nachricht + Warten + Lesen)
            if (renew) bot.modApi.renewKey()
            val websiteUrl = bot.modApi.playerUrl()
            var webhook = if (renew || websiteUrl != null) null else existing(event.jda)
            if (webhook == null && (websiteUrl == null || renew)) {
                val channelId = bot.modSettings.channelId
                val channel = channelId?.let { event.jda.getChannelById(StandardGuildMessageChannel::class.java, it) }
                if (channel == null && websiteUrl == null) {
                    event.notify("❌ Auto-Join ist noch nicht eingerichtet – ein Admin muss zuerst `/mod-setup` ausführen.", Tone.ERROR)
                    return@SlashCommand
                }
                bot.autoJoin.webhookId?.let { oldId -> // alte URL ungültig machen
                    try {
                        event.jda.retrieveWebhookById(oldId).await().delete().reason("Auto-Join-URL erneuert").await()
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                    }
                }
                if (channel != null) {
                    webhook = try {
                        channel.createWebhook("CrewStats Auto-Join").reason("/autojoin von ${Actor.tagOf(event.user)}").await()
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                        if (!e.isForbidden()) throw e
                        if (websiteUrl == null) {
                            event.notify("❌ Mir fehlt das Recht **Webhooks verwalten** in ${channel.asMention}.", Tone.ERROR)
                            return@SlashCommand
                        }
                        null // geht auch ohne: die Mod fragt über die Website
                    }
                    webhook?.let { bot.autoJoin.set(it.idLong) }
                }
                bot.audit.log(
                    Actor.of(event.author), "Auto-Join-URL ${if (renew) "erneuert" else "eingerichtet"}",
                    if (websiteUrl != null) "über die Stats-Website" else "Webhook in ${channel?.asMention}",
                )
            }
            val url = websiteUrl ?: webhook?.let(::webhookUrlOf) ?: error("Discord hat keinen Webhook-Token geliefert")

            val embed = EmbedBuilder()
                .setTitle("Auto-Join für CrewStats")
                .setColor(Palette.ACCENT)
                .setDescription(
                    "Mit Auto-Join tritt Among Us im Hauptmenü **automatisch der neuesten Lobby** bei, die der Bot" +
                        " im Codes-Channel ankündigt – ohne Code abzutippen.\n\n" +
                        "**EINRICHTEN**\n" +
                        "1. Among Us schließen\n" +
                        "2. `CrewStats-Installer.bat` starten und bei **Auto-Join-URL** diese URL einfügen\n" +
                        "   (oder in `$AUTOJOIN_CONFIG` bei `[AutoJoin]` → `Url =`)\n" +
                        "```$url```\n" +
                        "**IM SPIEL**\n" +
                        "Im Hauptmenü wartet die Mod auf eine offene Lobby und tritt ihr bei (oben links steht, was sie macht)." +
                        " Jede Lobby nur einmal automatisch – nach dem Verlassen bleibst du draußen. **F5** im Hauptmenü" +
                        " tritt sofort der neuesten Lobby bei, **F6** schaltet das automatische Beitreten an/aus.\n\n" +
                        "**AUSSERDEM** (ab CrewStats 1.7.0)\n" +
                        "Mit derselben URL zeigt die Mod in der Lobby deine Stats und wer verknüpft ist, nach der Runde" +
                        " deine XP – und **F7** → **Mit Discord verknüpfen** gibt dir einen Code für `/verknuepfen-code`.\n\n" +
                        (if (websiteUrl != null) "Die URL läuft über unsere Stats-Website und braucht **CrewStats 1.8.0** (kommt per Auto-Update).\n\n" else "") +
                        "Die URL bitte nicht außerhalb des Servers teilen." +
                        " Server-Region im Spiel muss die der Lobby sein (bei uns **Modded NA**)."
                )
                .build()
            event.hook.sendMessageEmbeds(embed).setEphemeral(true).await()
        },
    )

    /** Webhook-Nachricht des Auto-Join-Webhooks: Anfragen beantworten, alles andere wegräumen. */
    suspend fun onMessage(message: Message, webhookId: Long, fresh: Boolean) {
        if (fresh) {
            PlayerInfoProtocol.parse(message.contentRaw)?.let { request ->
                answerPlayerInfo(message, webhookId, request)
                return
            }
        }
        if (!fresh || !AutoJoinProtocol.isRequest(message.contentRaw)) {
            delete(message)
            return
        }
        val current = bot.lobby.current()
        if (reply(message, webhookId, AutoJoinProtocol.reply(current))) {
            log.info("Auto-Join: Lobby {} an die Mod geschickt", current?.first?.let { "${it.code} (${it.state})" } ?: "–")
        }
    }

    /**
     * Komfort-Anfragen von CrewStats (eigene Stats, Lobby-Info, Code zum Verknüpfen). Kommen über den Auto-Join-
     * oder den Mod-Webhook; `webhookId` ist der Webhook, über den die Anfrage kam (damit wird sie bearbeitet).
     */
    suspend fun answerPlayerInfo(message: Message, webhookId: Long, request: PlayerInfoRequest) {
        val text = when (request) {
            is PlayerInfoRequest.Stats -> bot.playerInfo.statsReply(request)
            is PlayerInfoRequest.Link ->
                if (request.friendCode.isEmpty()) {
                    PlayerInfoProtocol.linkReply(null, linked = false, error = "friendcode")
                } else {
                    val linked = bot.links.userFor(request.friendCode) != null
                    PlayerInfoProtocol.linkReply(bot.linkCodes.create(request.friendCode), linked)
                }
        }
        if (reply(message, webhookId, text)) {
            log.info("CrewStats: {} beantwortet", if (request is PlayerInfoRequest.Stats) "Stats-Anfrage (${request.players.size} Spieler)" else "Verknüpfungs-Code")
        }
    }

    /** Bearbeitet die Anfrage-Nachricht zur Antwort; aufgeräumt wird nach 30 s (falls die Mod es nicht selbst tut). */
    private suspend fun reply(message: Message, webhookId: Long, text: String): Boolean {
        val token = tokenOf(message.jda, webhookId)
        if (token == null) {
            delete(message)
            return false
        }
        val ok = try {
            WebhookClient.createClient(message.jda, webhookId.toString(), token).editMessageById(message.idLong, text).await()
            true
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("CrewStats: Antwort an die Mod fehlgeschlagen: {}", e.describe())
            false
        }
        bot.scope.launch {
            delay(AUTOJOIN_CLEANUP_MS)
            delete(message)
        }
        return ok
    }

    private suspend fun existing(jda: JDA): Webhook? {
        val id = bot.autoJoin.webhookId ?: return null
        return try {
            jda.retrieveWebhookById(id).await().also { hook -> hook.token?.let { webhookTokens[id] = it } }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            null // gelöscht – wird neu angelegt
        }
    }

    private suspend fun tokenOf(jda: JDA, webhookId: Long): String? {
        webhookTokens[webhookId]?.let { return it }
        val token = try {
            jda.retrieveWebhookById(webhookId).await().token
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("CrewStats: Webhook nicht abrufbar ({}) – fehlt das Recht „Webhooks verwalten“?", e.describe())
            null
        }
        if (token != null) webhookTokens[webhookId] = token
        return token
    }

    private suspend fun delete(message: Message) {
        try {
            message.delete().await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
        }
    }
}
