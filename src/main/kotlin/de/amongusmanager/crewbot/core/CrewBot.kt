/* Die Haupt-Bot-Klasse: verbindet Datenbank, Services, Module und Discord (JDA). */
package de.amongusmanager.crewbot.core

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.Env
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.modules.AdminModule
import de.amongusmanager.crewbot.modules.AmongUsModModule
import de.amongusmanager.crewbot.modules.EventsModule
import de.amongusmanager.crewbot.modules.FunModule
import de.amongusmanager.crewbot.modules.HelpModule
import de.amongusmanager.crewbot.modules.KnowledgeModule
import de.amongusmanager.crewbot.modules.MinecraftModule
import de.amongusmanager.crewbot.modules.ProgressionModule
import de.amongusmanager.crewbot.modules.RoundsModule
import de.amongusmanager.crewbot.modules.VoiceModule
import de.amongusmanager.crewbot.services.AchievementService
import de.amongusmanager.crewbot.services.AuditLog
import de.amongusmanager.crewbot.services.RoundArchiveService
import de.amongusmanager.crewbot.services.AutoRoleService
import de.amongusmanager.crewbot.services.AutoMuteService
import de.amongusmanager.crewbot.services.BetService
import de.amongusmanager.crewbot.services.EventService
import de.amongusmanager.crewbot.services.GameSettingsService
import de.amongusmanager.crewbot.services.GameStatsService
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.LobbyAnnouncer
import de.amongusmanager.crewbot.services.MinecraftService
import de.amongusmanager.crewbot.services.ModRoundService
import de.amongusmanager.crewbot.services.ModSettings
import de.amongusmanager.crewbot.services.RoundHistoryService
import de.amongusmanager.crewbot.services.RolePanelService
import de.amongusmanager.crewbot.services.RoleWishService
import de.amongusmanager.crewbot.services.RoundService
import de.amongusmanager.crewbot.services.VoiceStatusUpdater
import de.amongusmanager.crewbot.services.WelcomeService
import de.amongusmanager.crewbot.services.WorkerManager
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildLobbyEmbed
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.views.ControlPanel
import de.amongusmanager.crewbot.ui.views.EventButtons
import de.amongusmanager.crewbot.ui.views.EventSignup
import de.amongusmanager.crewbot.ui.views.RolePanel
import de.amongusmanager.crewbot.ui.views.RoleSelect
import de.amongusmanager.crewbot.ui.views.SpecialRoleSelect
import de.amongusmanager.crewbot.ui.views.UndoRoundButton
import de.amongusmanager.crewbot.ui.views.ViewRegistry
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdatePendingEvent
import net.dv8tion.jda.api.events.guild.voice.GenericGuildVoiceEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class CrewBot(val db: Database) {
    private val log = LoggerFactory.getLogger(CrewBot::class.java)
    private val jdaRef = AtomicReference<JDA>()

    /** Die Discord-Verbindung des Haupt-Bots. */
    val jda: JDA get() = jdaRef.get() ?: error("Bot ist noch nicht verbunden")

    val scope = BotThread.scope

    // Services – überall erreichbar über bot.<name>
    val levels = LevelService(db)
    val bets = BetService(db, levels)
    val events = EventService(db)
    val rounds = RoundService(db, levels, bets)
    val audit = AuditLog({ jda }, Channels.LOG)

    // Among-Us-Mod
    val links = LinkService(db)
    val gameStats = GameStatsService(db)
    val modSettings = ModSettings(db)
    val gameSettings = GameSettingsService(db)
    val roundHistory = RoundHistoryService(db)
    val achievements = AchievementService(db, levels)
    val roundArchive = RoundArchiveService(db)
    val modRounds = ModRoundService(db, levels, bets, rounds, links, gameStats, roundHistory, achievements, roundArchive)
    val roleWishes = RoleWishService(db, levels, links, { gameSettings.latest })
    val lobby = LobbyAnnouncer({ jda }, Channels.CODES, links, bets, { event, host -> buildLobbyEmbed(event, host) }, scope)

    // Minecraft-Plugin (CoreSMP): Rohdaten kommen im Inbox-Channel an, Ankündigungen gehen in den /dc-Channel
    val minecraft = MinecraftService(db, Channels.MINECRAFT, Channels.MINECRAFT_INBOX)
    val workers = WorkerManager({ jda }, Env.workerTokens, scope)
    val voiceStatus = VoiceStatusUpdater({ jda }, Channels.AMONG_US_VOICE, scope)
    val automute = AutoMuteService(workers, links, voiceStatus, Channels.AMONG_US_VOICE)

    // Rollen-Panel (/rollen-panel): Rollen per Button, die Buttons zeigen die Mitgliederzahl
    val rolePanel = RolePanelService(db, { jda }, RolePanel::components, scope)
    val autoRoles = AutoRoleService()
    val welcome = WelcomeService(::mentionCommand) // Willkommens-/Abschiedsnachricht

    /** Reihenfolge = Reihenfolge in /hilfe. */
    val modules: List<CrewModule> = listOf(
        VoiceModule(this),
        KnowledgeModule(),
        ProgressionModule(this),
        RoundsModule(this),
        EventsModule(this),
        FunModule(),
        AmongUsModModule(this),
        MinecraftModule(this),
        AdminModule(this),
        HelpModule(this),
    )

    private val commandsByName: Map<String, SlashCommand> =
        modules.flatMap { it.commands }.associateBy { it.name }
    private var commandIds: Map<String, Long> = emptyMap()
    private var ready = false

    /** Klickbare Befehls-Erwähnung (</name:id>), sonst einfach /name. */
    fun mentionCommand(name: String): String = commandIds[name]?.let { "</$name:$it>" } ?: "/$name"

    // ==========================================
    // START & STOPP
    // ==========================================

    fun start(token: String) {
        val jda = JDABuilder.createDefault(
            token,
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_VOICE_STATES,
            GatewayIntent.GUILD_PRESENCES,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
        )
            .setMemberCachePolicy(MemberCachePolicy.ALL)
            .setChunkingFilter(ChunkingFilter.ALL)
            .enableCache(CacheFlag.VOICE_STATE, CacheFlag.ONLINE_STATUS, CacheFlag.MEMBER_OVERRIDES)
            .disableCache(
                CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.SCHEDULED_EVENTS,
                CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SOUNDBOARD_SOUNDS,
            )
            .addEventListeners(Router())
            .build()
        jdaRef.compareAndSet(null, jda)
    }

    fun shutdown() {
        jdaRef.get()?.shutdown()
        workers.close()
    }

    private suspend fun onReady() {
        if (ready) return
        ready = true
        log.info("✅ Eingeloggt als {}", jda.selfUser.name)

        workers.start()
        try {
            val synced = jda.updateCommands().addCommands(commandsByName.values.map { it.data() }).await()
            commandIds = synced.associate { it.name to it.idLong }
            log.info("⚙️ {} Slash-Commands synchronisiert.", synced.size)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Slash-Commands konnten nicht synchronisiert werden", e)
        }

        voiceStatus.refresh()
        try {
            rolePanel.refreshAll() // Rollen können sich geändert haben, während der Bot aus war
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Rollen-Panels konnten nicht aktualisiert werden", e)
        }
        // Auto-Rollen für alle, die beim Start noch keine haben (läuft nebenher, JDA drosselt die Anfragen)
        scope.launch { for (guild in jda.guilds) autoRoles.syncAll(guild) }
        for (module in modules) {
            try {
                module.onReady()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.error("Fehler beim Start von Modul {}", module.name, e)
            }
        }
    }

    // ==========================================
    // EREIGNISSE
    // ==========================================

    private suspend fun dispatchCommand(event: SlashCommandInteractionEvent) {
        val command = commandsByName[event.name] ?: return
        try {
            if (!event.isFromGuild || event.member == null) {
                event.notify("🚫 Dieser Bot funktioniert nur auf dem Server.", Tone.ERROR)
                return
            }
            val permission = command.permission
            if (permission != null && !event.member!!.hasPermissionIn(event.guildChannel, permission)) {
                event.notify("🚫 Dafür fehlen dir die Rechte.", Tone.ERROR)
                return
            }
            command.handler(event)
        } catch (e: CommandError) {
            safeNotify(event, e.message ?: "❌ Fehler", Tone.ERROR)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Fehler in /{}", event.name, e)
            safeNotify(event, "❌ Unerwarteter Fehler. Bitte versuch es nochmal.", Tone.ERROR)
        }
    }

    private suspend fun dispatchComponent(event: GenericComponentInteractionCreateEvent) {
        try {
            if (ViewRegistry.dispatch(event)) return
            val id = event.componentId
            when {
                event is ButtonInteractionEvent && id == ControlPanel.MUTE -> ControlPanel.onMute(event, this)
                event is ButtonInteractionEvent && id == ControlPanel.UNMUTE -> ControlPanel.onUnmute(event, this)
                event is ButtonInteractionEvent && id == ControlPanel.MOVE -> ControlPanel.onMove(event, this)
                event is ButtonInteractionEvent && id == ControlPanel.FINISH -> ControlPanel.onFinish(event, this)
                event is ButtonInteractionEvent && id == EventButtons.JOIN -> EventSignup.join(event)
                event is ButtonInteractionEvent && id == EventButtons.LEAVE -> EventSignup.leave(event)
                event is ButtonInteractionEvent && id == EventButtons.FUN -> EventSignup.funButton(event)
                event is StringSelectInteractionEvent && id == RoleSelect.CUSTOM_ID -> RoleSelect.onSelect(event)
                event is StringSelectInteractionEvent && id.startsWith(SpecialRoleSelect.PREFIX) ->
                    SpecialRoleSelect.onSelect(event)
                event is ButtonInteractionEvent && RolePanel.PATTERN.matches(id) -> {
                    val roleId = RolePanel.PATTERN.matchEntire(id)!!.groupValues[1].toLong()
                    RolePanel.onClick(event, roleId, this)
                }
                event is ButtonInteractionEvent && UndoRoundButton.PATTERN.matches(id) -> {
                    val gameId = UndoRoundButton.PATTERN.matchEntire(id)!!.groupValues[1]
                    UndoRoundButton.onClick(event, gameId, modRounds, audit)
                }
                else -> log.debug("Unbekannte Komponente: {}", id)
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Fehler bei Komponente {}", event.componentId, e)
            safeNotify(event, "❌ Da ist etwas schiefgelaufen. Versuch es nochmal!", Tone.ERROR)
        }
    }

    private suspend fun safeNotify(
        event: net.dv8tion.jda.api.interactions.callbacks.IReplyCallback,
        text: String,
        tone: Tone,
    ) {
        try {
            event.notify(text, tone)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
        }
    }

    /** Leitet JDA-Ereignisse auf den Bot-Thread weiter (dort laufen alle Handler nacheinander). */
    private inner class Router : ListenerAdapter() {
        private fun attach(jda: JDA) {
            jdaRef.compareAndSet(null, jda)
        }

        override fun onReady(event: ReadyEvent) {
            attach(event.jda)
            scope.launch { onReady() }
        }

        override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
            attach(event.jda)
            scope.launch { dispatchCommand(event) }
        }

        override fun onGenericComponentInteractionCreate(event: GenericComponentInteractionCreateEvent) {
            attach(event.jda)
            scope.launch { dispatchComponent(event) }
        }

        override fun onMessageReceived(event: MessageReceivedEvent) {
            attach(event.jda)
            if (!event.isFromGuild) return
            scope.launch {
                for (module in modules) {
                    try {
                        module.onMessage(event)
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                        log.error("Fehler beim Verarbeiten einer Nachricht in {}", module.name, e)
                    }
                }
            }
        }

        // Rollen-Panel: Zahlen nachziehen, wenn sich eine Panel-Rolle ändert (auch durch Admins)
        override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent) {
            attach(event.jda)
            if (event.roles.any { it.idLong in rolePanel.roleIds }) scope.launch { rolePanel.requestRefresh() }
        }

        override fun onGuildMemberRoleRemove(event: GuildMemberRoleRemoveEvent) {
            attach(event.jda)
            if (event.roles.any { it.idLong in rolePanel.roleIds }) scope.launch { rolePanel.requestRefresh() }
        }

        override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
            attach(event.jda)
            scope.launch { autoRoles.apply(event.member) }
            scope.launch { welcome.welcome(event.member) }
        }

        // Mitgliederprüfung (Regeln bestätigt): erst jetzt gibt es die Auto-Rollen
        override fun onGuildMemberUpdatePending(event: GuildMemberUpdatePendingEvent) {
            attach(event.jda)
            if (!event.newPending) scope.launch { autoRoles.apply(event.member) }
        }

        override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
            attach(event.jda)
            scope.launch { rolePanel.requestRefresh() }
            scope.launch { welcome.farewell(event.guild, event.user, event.member) }
        }

        override fun onGenericGuildVoice(event: GenericGuildVoiceEvent) {
            attach(event.jda)
            scope.launch {
                for (module in modules) {
                    try {
                        module.onVoice(event)
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                        log.error("Fehler bei Voice-Ereignis in {}", module.name, e)
                    }
                }
            }
        }
    }
}
