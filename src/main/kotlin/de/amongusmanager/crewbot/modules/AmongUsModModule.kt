/* Among-Us-Mod: Webhook einrichten, Accounts verknüpfen, Statistiken, automatische Auswertung und Rollenwünsche. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.intOption
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.AutoMuteEvent
import de.amongusmanager.crewbot.services.GameSettingsError
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundError
import de.amongusmanager.crewbot.services.SETTINGS_FILE_NAME
import de.amongusmanager.crewbot.services.WishProtocol
import de.amongusmanager.crewbot.services.buildRecap
import de.amongusmanager.crewbot.services.isValidFriendCode
import de.amongusmanager.crewbot.services.normalizeFriendCode
import de.amongusmanager.crewbot.services.rivalsOf
import de.amongusmanager.crewbot.services.voiceChannelOf
import de.amongusmanager.crewbot.services.webhookUrlOf
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildAchievementsEmbed
import de.amongusmanager.crewbot.ui.buildRecapEmbed
import de.amongusmanager.crewbot.ui.buildStatsEmbed
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.fitLines
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.rivalsText
import de.amongusmanager.crewbot.ui.safeBuild
import de.amongusmanager.crewbot.ui.views.GameSettingsView
import de.amongusmanager.crewbot.ui.views.UndoRoundButton
import de.amongusmanager.crewbot.ui.views.buildModRoundEmbed
import de.amongusmanager.crewbot.ui.views.buildSettingsEmbed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private const val MOD_CONFIG_PATH = """Among Us\BepInEx\config\de.amongusmanager.crewstats.cfg"""
private val AUTOMUTE_MAX_AGE: Duration = Duration.ofSeconds(60)

class AmongUsModModule(private val bot: CrewBot) : CrewModule("Among Us Mod", "🛰️") {
    private val log = LoggerFactory.getLogger(AmongUsModModule::class.java)
    private val roundLock = Mutex() // Runden nacheinander auswerten (doppelt gesendete Runden zählen nur einmal)
    private val wishes = RoleWishCommands(bot) // /rollenwunsch, /rolle-zuweisen
    private val autoJoin = AutoJoinCommands(bot) // /autojoin + Lobby-Anfragen der Mod

    override val commands = listOf(
        // ==========================================
        // EINRICHTUNG
        // ==========================================
        SlashCommand(
            "mod-setup", "Richtet den Empfang der CrewStats-Mod in einem Channel ein", Permission.ADMINISTRATOR,
            options = listOf(
                OptionData(OptionType.CHANNEL, "kanal", "Hier landen die automatischen Rundenergebnisse", true)
                    .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)
            ),
        ) { event ->
            val channel = event.getOption("kanal")!!.asChannel as StandardGuildMessageChannel
            event.deferReply(true).await()
            val settings = bot.modSettings

            // Alten Webhook entfernen, damit die alte URL ungültig wird
            settings.webhookId?.let { oldId ->
                try {
                    event.jda.retrieveWebhookById(oldId).await().delete().reason("CrewStats-Webhook neu eingerichtet").await()
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                }
            }

            val webhook = try {
                channel.createWebhook("CrewStats Mod").reason("/mod-setup von ${Actor.tagOf(event.user)}").await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                if (!e.isForbidden()) throw e
                event.notify("❌ Mir fehlt das Recht **Webhooks verwalten** in diesem Channel.", Tone.ERROR)
                return@SlashCommand
            }
            val url = webhookUrlOf(webhook) ?: error("Discord hat keinen Webhook-Token geliefert")

            settings.set(webhook.idLong, channel.idLong)
            bot.audit.log(Actor.of(event.author), "CrewStats-Mod eingerichtet", "Ergebnis-Channel: ${channel.asMention}")

            val embed = EmbedBuilder()
                .setTitle("🛰️ CrewStats-Mod eingerichtet")
                .setDescription(
                    "Rundenergebnisse erscheinen ab jetzt in ${channel.asMention}.\n\n" +
                        "**So verbindest du die Mod:**\n" +
                        "1. Among Us einmal mit der Mod starten und wieder schließen\n" +
                        "2. Datei öffnen: `$MOD_CONFIG_PATH`\n" +
                        "3. Bei `WebhookUrl =` diese URL eintragen:\n" +
                        "```$url```\n" +
                        "⚠️ **Die URL ist geheim!** Wer sie hat, kann Runden einschicken." +
                        " Bei Bedarf einfach `/mod-setup` erneut ausführen – die alte URL wird dann ungültig.\n\n" +
                        "🔇 **Auto-Mute:** im Spiel unter **ESC → Einstellungen → Auto-Mute** an-/ausschalten." +
                        " Runde: Lebende stumm + taub, Tote reden. Meeting: Lebende reden, Tote stumm." +
                        " Gilt im Voice des Spielers mit der Mod (muss mit `/verknuepfen` verknüpft sein)," +
                        " sonst im Among-Us-Channel. Als tot erkannt werden nur verknüpfte Spieler."
                )
                .setColor(Palette.SUCCESS)
                .build()
            event.hook.sendMessageEmbeds(embed).setEphemeral(true).await()
        },

        // ==========================================
        // VERKNÜPFEN & STATISTIK
        // ==========================================
        SlashCommand(
            "verknuepfen", "Verknüpft deinen Among-Us-Account (Friendcode) mit Discord",
            options = listOf(
                OptionData(OptionType.STRING, "friendcode", "Dein Friendcode, z.B. happyfox#1234 (Among Us → Freunde-Menü)", true),
                OptionData(OptionType.USER, "spieler", "Nur Admins: jemand anderen verknüpfen", false),
            ),
        ) { event ->
            val user = event.author
            val isAdmin = user.hasPermission(Permission.ADMINISTRATOR)
            val target = event.memberOption("spieler") ?: user
            if (target.idLong != user.idLong && !isAdmin) {
                event.notify("🚫 Nur Admins dürfen andere Spieler verknüpfen.", Tone.ERROR)
                return@SlashCommand
            }
            val friendCode = event.stringOption("friendcode")!!
            if (!isValidFriendCode(friendCode)) {
                event.notify(
                    "❌ Das sieht nicht nach einem Friendcode aus. Format: `name#1234`\n" +
                        "Du findest ihn in Among Us im **Freunde-Menü** oben.",
                    Tone.ERROR,
                )
                return@SlashCommand
            }

            val code = normalizeFriendCode(friendCode)
            val owner = bot.links.userFor(code)
            if (owner != null && owner != target.idLong && !isAdmin) {
                event.notify("❌ Dieser Friendcode ist bereits mit <@$owner> verknüpft.", Tone.ERROR)
                return@SlashCommand
            }

            bot.links.link(code, target.idLong)
            if (target.idLong != user.idLong) {
                bot.audit.log(Actor.of(user), "Account verknüpft", "`$code` → ${target.asMention}")
            }
            event.notify(
                "✅ `$code` ist jetzt mit ${target.asMention} verknüpft. Ab der nächsten Runde gibt es XP & Statistiken!",
                Tone.SUCCESS,
            )
        },
        SlashCommand(
            "verknuepfungen", "Zeigt, wer seinen Among-Us-Account verknüpft hat", Permission.VOICE_MUTE_OTHERS,
            options = listOf(
                OptionData(OptionType.USER, "spieler", "Nur diesen Spieler prüfen", false),
                OptionData(OptionType.STRING, "friendcode", "Herausfinden, wem ein Friendcode gehört", false),
            ),
        ) { event ->
            val links = bot.links
            val guild = event.guild!!

            // Einzelabfrage: Friendcode -> Discord
            val friendCode = event.stringOption("friendcode")
            if (!friendCode.isNullOrEmpty()) {
                val code = normalizeFriendCode(friendCode)
                val owner = links.userFor(code)
                val text = if (owner != null) "✅ `$code` gehört zu <@$owner>." else "❌ `$code` ist mit niemandem verknüpft."
                event.notify(text, if (owner != null) Tone.SUCCESS else Tone.WARNING)
                return@SlashCommand
            }

            // Einzelabfrage: Discord -> Friendcode
            val player = event.memberOption("spieler")
            if (player != null) {
                val codes = links.codesOf(player.idLong)
                val text = if (codes.isNotEmpty()) {
                    "✅ ${player.asMention} ist verknüpft mit ${codes.joinToString(", ") { "`$it`" }}."
                } else {
                    "❌ ${player.asMention} ist **nicht** verknüpft und bekommt keine XP aus Mod-Runden."
                }
                event.notify(text, if (codes.isNotEmpty()) Tone.SUCCESS else Tone.WARNING)
                return@SlashCommand
            }

            // Übersicht
            val grouped = links.byUser()
            val lines = grouped.entries.sortedBy { sortName(guild, it.key) }.map { (userId, codes) ->
                val gone = if (guild.getMemberById(userId) != null) "" else " *(nicht mehr auf dem Server)*"
                "<@$userId> – ${codes.joinToString(", ") { "`$it`" }}$gone"
            }
            val embed = EmbedBuilder()
                .setTitle("🔗 Verknüpfte Among-Us-Accounts")
                .setColor(Palette.INFO)
                .setDescription(fitLines(lines, limit = 4000, reserve = 40).ifEmpty { "Noch niemand hat sich verknüpft." })

            // Wer sitzt gerade im Voice und ist noch nicht verknüpft?
            val voice = voiceChannelOf(event.author)
            if (voice != null) {
                val players = voice.members.filter { !it.user.isBot }
                val missing = players.filter { it.idLong !in grouped }
                embed.field(
                    "🎙️ ${voice.name}: ${players.size - missing.size}/${players.size} verknüpft",
                    fitLines(missing.map { "❌ ${it.asMention}" }).ifEmpty { "✅ Alle im Voice sind verknüpft!" },
                    inline = false,
                )
            }
            embed.setFooter("${grouped.size} Spieler verknüpft • Verknüpfen: /verknuepfen")
            event.replyEmbeds(safeBuild(embed)).setEphemeral(true).await()
        },
        SlashCommand(
            "stats", "Among-Us-Statistiken aus den aufgezeichneten Runden",
            options = listOf(OptionData(OptionType.USER, "spieler", "Wessen Statistik? (leer = deine eigene)", false)),
        ) { event ->
            val target = event.memberOption("spieler") ?: event.author
            val s = bot.gameStats.get(target.idLong)
            val codes = bot.links.codesOf(target.idLong)
            val rivals = if (s.games == 0) "" else rivalsText(rivalsOf(target.idLong, bot.roundHistory.roundsOf(target.idLong)))
            val unlocked = bot.achievements.unlocked(target.idLong).size
            event.replyEmbeds(
                buildStatsEmbed(target.effectiveName, target.effectiveAvatarUrl, s, codes, rivals, unlocked)
            ).await()
        },
        SlashCommand(
            "erfolge", "Zeigt freigeschaltete und offene Among-Us-Erfolge",
            options = listOf(OptionData(OptionType.USER, "spieler", "Wessen Erfolge? (leer = deine eigenen)", false)),
        ) { event ->
            val target = event.memberOption("spieler") ?: event.author
            val embed = buildAchievementsEmbed(
                target.effectiveName,
                target.effectiveAvatarUrl,
                bot.achievements.unlocked(target.idLong),
                bot.gameStats.get(target.idLong),
            )
            event.replyEmbeds(embed).await()
        },
        SlashCommand(
            "recap", "Zusammenfassung der letzten Runden: Auszeichnungen & MVP-Kandidaten",
            options = listOf(
                OptionData(OptionType.INTEGER, "stunden", "Wie viele Stunden zurück? (Standard: 6)", false)
                    .setRequiredRange(1, 168)
            ),
        ) { event ->
            val hours = event.intOption("stunden") ?: 6
            val since = Instant.now() - Duration.ofHours(hours.toLong())
            val recap = buildRecap(bot.roundHistory.rounds(since = since))
            event.replyEmbeds(buildRecapEmbed(recap, "🌙 Recap der letzten $hours Stunden", since)).await()
        },
        SlashCommand("rundensettings", "Zeigt die Lobbyeinstellungen der letzten Among-Us-Runde") { event ->
            val settings = bot.gameSettings.latest
            if (settings == null) {
                event.notify(
                    "ℹ️ Noch keine Einstellungen gespeichert. Sie kommen automatisch beim nächsten" +
                        " Rundenstart, wenn der Host die CrewStats-Mod hat.",
                    Tone.WARNING,
                )
                return@SlashCommand
            }
            val view = GameSettingsView(settings)
            event.replyEmbeds(buildSettingsEmbed(settings)).setComponents(view.components()).await()
            view.message = try {
                event.hook.retrieveOriginal().await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                null
            }
        },
    ) + wishes.commands + autoJoin.commands

    override suspend fun onReady() {
        wishes.startExpiry()
    }

    private fun sortName(guild: Guild, userId: Long): String =
        guild.getMemberById(userId)?.effectiveName?.lowercase() ?: "~" // Ehemalige ans Ende

    // ==========================================
    // EMPFANG DER RUNDENDATEN
    // ==========================================

    override suspend fun onMessage(event: MessageReceivedEvent) {
        val message = event.message
        if (!message.isWebhookMessage) return
        val autoJoinId = bot.autoJoin.webhookId
        if (autoJoinId != null && message.author.idLong == autoJoinId) {
            autoJoin.onMessage(message, autoJoinId, isFresh(message))
            return
        }
        val webhookId = bot.modSettings.webhookId ?: return
        if (!message.isWebhookMessage || message.author.idLong != webhookId) return
        val guild = event.guild

        WishProtocol.parse(message.contentRaw)?.let { wish ->
            wishes.onModMessage(message, webhookId, wish, isFresh(message))
            return
        }
        AutoMuteEvent.parse(message.contentRaw)?.let { automute ->
            handleAutomute(guild, message, automute)
            return
        }
        LobbyEvent.parse(message.contentRaw)?.let { lobby ->
            if (isFresh(message)) bot.lobby.apply(lobby)
            delete(message)
            return
        }
        message.attachments.firstOrNull { it.fileName == SETTINGS_FILE_NAME }?.let { attachment ->
            handleSettings(message, attachment)
            return
        }
        val attachment = message.attachments.firstOrNull { it.fileName.endsWith(".json") } ?: return

        roundLock.withLock {
            val result = try {
                val raw = download(attachment)
                val round = ModRound.parse(raw)
                if (bot.modRounds.isKnown(round.gameId)) {
                    delete(message) // doppelt gesendet
                    return
                }
                bot.modRounds.apply(guild, round, raw)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                val reason = if (e is ModRoundError) e.message else e.describe()
                log.warn("Mod-Runde konnte nicht ausgewertet werden: {}", reason)
                try {
                    message.reply("⚠️ Rundendaten konnten nicht ausgewertet werden: $reason").await()
                } catch (inner: Exception) {
                    inner.rethrowIfCancelled()
                }
                return
            }

            val round = result.round
            val summary = message.channel.sendMessageEmbeds(buildModRoundEmbed(result))
                .setComponents(UndoRoundButton.row(round.gameId))
                .await()
            bot.modRounds.attachMessage(round.gameId, summary.channel.idLong, summary.idLong)
            delete(message)

            val winner = round.winningTeam?.label ?: "unbekannt"
            bot.audit.log(
                Actor.of(event.jda.selfUser),
                "Runde automatisch ausgewertet",
                "[Zur Runde](${summary.jumpUrl}) • Sieger: $winner",
            )
        }
    }

    private suspend fun handleSettings(message: Message, attachment: Message.Attachment) {
        val settings = try {
            bot.gameSettings.update(download(attachment))
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            val reason = if (e is GameSettingsError) e.message else e.describe()
            log.warn("Rundeneinstellungen konnten nicht gelesen werden: {}", reason)
            return // Nachricht stehen lassen, damit man den Fehler nachvollziehen kann
        }
        log.info("Rundeneinstellungen gespeichert ({}, Lobby {})", settings.map, settings.lobbyCode)
        delete(message)
    }

    private suspend fun handleAutomute(guild: Guild, message: Message, event: AutoMuteEvent) {
        // Nachrichten, die z.B. während eines Bot-Neustarts liegen geblieben sind, nicht mehr ausführen
        if (isFresh(message)) {
            val result = bot.automute.apply(guild, event)
            if (result != null) {
                log.info(
                    "Auto-Mute {} in {}: {} geändert, {} Fehler, {} im Meeting stumm",
                    event.phase, result.channel.name, result.report.succeeded, result.report.failed, event.silenced.size,
                )
            }
        }
        delete(message)
    }

    private fun isFresh(message: Message): Boolean =
        Duration.between(message.timeCreated.toInstant(), Instant.now()) <= AUTOMUTE_MAX_AGE

    private suspend fun download(attachment: Message.Attachment): String {
        val bytes = attachment.proxy.download().await().use { stream ->
            withContext(Dispatchers.IO) { stream.readBytes() }
        }
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    private suspend fun delete(message: Message) {
        try {
            message.delete().await()
        } catch (e: Exception) {
            e.rethrowIfCancelled() // fehlendes Recht „Nachrichten verwalten“ – nicht schlimm
        }
    }
}
