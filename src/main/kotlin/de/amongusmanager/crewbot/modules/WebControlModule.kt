/*
 * Stats-Website → Bot: holt die Aufträge der Website aus der Datenbank (services/WebQueue.kt), prüft die Rechte über
 * die Discord-Rollen des Mitglieds und führt sie aus. Die Website vertraut der Bot nicht: jede Aktion schaut selbst
 * nach, ob die angemeldete Person (Discord-ID aus dem Login) auf dem Server ist und die nötige Rolle hat.
 *
 *   Alle Mitglieder: me, events.list, events.join, events.leave
 *   Event Maker (Rolle Roles.EVENT_MANAGER) und Admins: events.create
 *   Admins (Recht „Administrator“): admin.* – Spieler-XP/Level/Reset, Discord-Rollen, Rollen für die nächste Runde
 *   (/rolle-zuweisen), Voice (Mute/Unmute/Verschieben), Runde beenden/abbrechen, MVP-Voting, Panels posten,
 *   Event-Channel, Mod-Setup. Jede Admin-Aktion landet im Log-Channel („über die Website“).
 *
 * Spieler werden über denselben Schlüssel wie auf der Website angesprochen (WebAuth.playerKeyOf) – Discord-IDs von
 * Mitgliedern gehen nie an die Website. Channel- und Rollen-IDs schon (als Text, JavaScript kann keine 64-Bit-Zahlen).
 */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.GAME_PING_ROLES
import de.amongusmanager.crewbot.LEVEL_ROLE_REWARDS
import de.amongusmanager.crewbot.Roles
import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.SELF_ROLE_GROUPS
import de.amongusmanager.crewbot.TIMEZONE
import de.amongusmanager.crewbot.content.Changelog
import de.amongusmanager.crewbot.content.TOR_WISH_ROLES
import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.content.WISH_ROLES
import de.amongusmanager.crewbot.content.tutorial
import de.amongusmanager.crewbot.content.wishRole
import de.amongusmanager.crewbot.core.CommandError
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.isNotFound
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.LeaderboardExclusions
import de.amongusmanager.crewbot.services.PlayerInfoProtocol
import de.amongusmanager.crewbot.services.isValidFriendCode
import de.amongusmanager.crewbot.services.normalizeFriendCode
import de.amongusmanager.crewbot.services.QueuedRequest
import de.amongusmanager.crewbot.services.RoleWishError
import de.amongusmanager.crewbot.services.RoundService
import de.amongusmanager.crewbot.services.WebQueue
import de.amongusmanager.crewbot.services.moveMembers
import de.amongusmanager.crewbot.services.planRoleToggle
import de.amongusmanager.crewbot.services.selfRoleGroupOf
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.buildChangelogEmbed
import de.amongusmanager.crewbot.ui.buildTutorialEmbeds
import de.amongusmanager.crewbot.ui.escapeMarkdown
import de.amongusmanager.crewbot.ui.simpleEmbed
import de.amongusmanager.crewbot.ui.views.CLOSED_PREFIX
import de.amongusmanager.crewbot.ui.views.ControlPanel
import de.amongusmanager.crewbot.ui.views.EventRoster
import de.amongusmanager.crewbot.ui.views.EventSignup
import de.amongusmanager.crewbot.ui.views.SignupResult
import de.amongusmanager.crewbot.ui.views.evaluateRound
import de.amongusmanager.crewbot.ui.views.isEventOver
import de.amongusmanager.crewbot.web.WebAuth
import de.amongusmanager.crewbot.web.obj
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Fehlermeldung für die Website (wird dort direkt angezeigt). */
class WebActionError(message: String) : Exception(message)

class WebControlModule(private val bot: CrewBot) : CrewModule("Website", "🌐") {
    private val log = LoggerFactory.getLogger(WebControlModule::class.java)
    private val queue = WebQueue(bot.db)
    private var loopStarted = false
    private var lastBeat = Instant.EPOCH
    private var lastCleanup = Instant.EPOCH
    private var lastRequest = Instant.EPOCH

    /** Event-Nachrichten (Spiel, Ersteller, Teilnehmer stehen im Embed) – kurz zwischengespeichert. */
    private val eventMessages = HashMap<Long, Pair<Message, Instant>>()

    /** Wer wie oft Events erstellt hat (gegen versehentliches Spammen). */
    private val createdEvents = HashMap<Long, MutableList<Instant>>()

    /** Spieler-Schlüssel → Discord-ID (Schlüssel = Hash, wird bei Bedarf neu berechnet). */
    private val keys = HashMap<String, Long>()

    // ==========================================
    // ABHOLEN
    // ==========================================

    override suspend fun onReady() {
        if (loopStarted) return
        loopStarted = true
        EventSignup.onChange = { message -> eventMessages[message.idLong] = message to Instant.now() }
        bot.scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                    log.warn("Website-Aufträge nicht abrufbar: {}", e.message)
                }
                // Kurz nach einem Auftrag schnell nachsehen, sonst gemütlich (spart Datenbank-Abfragen)
                val busy = Duration.between(lastRequest, Instant.now()) < Duration.ofMinutes(3)
                delay(if (busy) 250 else 1000)
            }
        }
    }

    private fun tick() {
        val now = Instant.now()
        if (Duration.between(lastBeat, now) >= WebQueue.HEARTBEAT_INTERVAL) {
            queue.heartbeat(now)
            lastBeat = now
        }
        if (Duration.between(lastCleanup, now) >= Duration.ofMinutes(1)) {
            queue.cleanup(now)
            lastCleanup = now
        }
        for (request in queue.open()) {
            if (!queue.claim(request.id)) continue
            lastRequest = now
            if (request.createdAt.plus(WebQueue.MAX_AGE).isBefore(now)) {
                queue.complete(request.id, error("Zu spät angekommen – bitte nochmal versuchen.").toString())
                continue
            }
            bot.scope.launch { run(request) }
        }
    }

    private suspend fun run(request: QueuedRequest) {
        val result = try {
            val payload = parseJson(request.payload) as? JsonObject ?: JsonObject(emptyMap())
            withTimeout(25_000) { dispatch(request.userId, request.action, payload) }
        } catch (e: WebActionError) {
            error(e.message ?: "Das geht gerade nicht.")
        } catch (e: CommandError) {
            error(plain(e.message ?: "Das geht gerade nicht."))
        } catch (e: RoleWishError) {
            error(plain(e.message ?: "Das geht gerade nicht."))
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Website-Auftrag {} fehlgeschlagen", request.action, e)
            error("Unerwarteter Fehler im Bot. Bitte versuch es nochmal.")
        }
        try {
            queue.complete(request.id, result.toString())
        } catch (e: Exception) {
            log.warn("Antwort an die Website nicht gespeichert: {}", e.message)
        }
    }

    private fun error(message: String): JsonObject = obj("error" to message)

    // ==========================================
    // VERTEILEN
    // ==========================================

    private suspend fun dispatch(userId: Long, action: String, p: JsonObject): JsonObject {
        if (action == MOD_LINK) return modLink(userId, p)
        val guild = guild() ?: throw WebActionError("Der Bot ist gerade mit keinem Server verbunden.")
        if (userId == GUEST && action == "events.list") return listEvents(guild, null) // nicht angemeldet: nur ansehen
        val member = guild.getMemberById(userId) ?: run {
            if (action == "me") return obj("inGuild" to false, "admin" to false, "eventMaker" to false)
            throw WebActionError("Du bist nicht (mehr) auf unserem Discord-Server.")
        }
        if (action.startsWith("admin.") && !isAdmin(member)) throw WebActionError("Dafür brauchst du die Admin-Rolle.")
        return when (action) {
            "me" -> me(member)
            "events.list" -> listEvents(guild, member)
            "events.join" -> signup(member, p, join = true)
            "events.leave" -> signup(member, p, join = false)
            "events.create" -> createEvent(member, p)
            "admin.overview" -> adminOverview(guild)
            "admin.members" -> adminMembers(guild)
            "admin.player.set" -> setPlayer(member, p)
            "admin.player.reset" -> resetPlayer(member, p)
            "admin.player.role" -> togglePlayerRole(member, p)
            "admin.wish" -> assignRole(member, p)
            "admin.voice" -> voice(member, p)
            "admin.round" -> finishRound(member, p)
            "admin.round.cancel" -> cancelRound(member, p)
            "admin.mvp" -> mvp(member, p)
            "admin.post" -> post(member, p)
            "admin.settings" -> settings(member, p)
            "admin.modsetup" -> modSetup(member, p)
            "admin.leaderboard" -> leaderboard(member, p)
            else -> throw WebActionError("Unbekannte Aktion.")
        }
    }

    /**
     * CrewStats „Mit Discord verknüpfen“ über die Website (services/ModApi.kt): Code erzeugen. Die Website schickt das
     * ohne angemeldete Person (ID 0) – der Code verknüpft erst, wenn jemand ihn bei /verknuepfen-code eingibt.
     */
    private fun modLink(userId: Long, p: JsonObject): JsonObject {
        if (userId != GUEST) throw WebActionError("Unbekannte Aktion.")
        val friendCode = p.text("fc")?.let(::normalizeFriendCode)?.takeIf(::isValidFriendCode)
        val text = if (friendCode == null) {
            PlayerInfoProtocol.linkReply(null, linked = false, error = "friendcode")
        } else {
            PlayerInfoProtocol.linkReply(bot.linkCodes.create(friendCode), linked = bot.links.userFor(friendCode) != null)
        }
        log.info("CrewStats: Verknüpfungs-Code über die Website beantwortet")
        return parseJson(text) as JsonObject
    }

    // ==========================================
    // RECHTE
    // ==========================================

    private fun guild(): Guild? = bot.jda.getTextChannelById(Channels.CODES)?.guild ?: bot.jda.guilds.firstOrNull()

    private fun isAdmin(member: Member): Boolean = member.hasPermission(Permission.ADMINISTRATOR)

    private fun isEventMaker(member: Member): Boolean = isAdmin(member) || member.roles.any { it.idLong == Roles.EVENT_MANAGER }

    private fun me(member: Member): JsonObject = obj(
        "inGuild" to true,
        "name" to member.effectiveName,
        "admin" to isAdmin(member),
        "eventMaker" to isEventMaker(member),
        "eventMakerRole" to member.guild.getRoleById(Roles.EVENT_MANAGER)?.name,
    )

    // ==========================================
    // EVENTS
    // ==========================================

    private suspend fun listEvents(guild: Guild, member: Member?): JsonObject {
        val now = Instant.now()
        val events = bot.events.all().sortedBy { it.startsAt }.mapNotNull { open ->
            val message = eventMessage(open.channelId, open.messageId) ?: return@mapNotNull null
            val embed = message.embeds.firstOrNull() ?: return@mapNotNull null
            val roster = EventRoster.fromEmbed(embed)
            val ids = roster.memberIds()
            val people = roster.entries.map { entry ->
                val id = MENTION.matchEntire(entry)?.groupValues?.get(1)?.toLongOrNull()
                val person = id?.let { guild.getMemberById(it) }
                obj(
                    "key" to id?.let { WebAuth.playerKeyOf(it) },
                    "name" to (person?.effectiveName ?: if (id != null) "Ehemaliges Mitglied" else entry),
                )
            }
            obj(
                "id" to open.messageId.toString(),
                "game" to gameOf(embed.title),
                "startsAt" to open.startsAt,
                "endsAt" to open.endsAt,
                "running" to open.isRunning(now),
                "closed" to isEventOver(embed, now),
                "creator" to creatorOf(embed.footer?.text),
                "channel" to (message.channel as? GuildMessageChannel)?.name,
                "messageUrl" to message.jumpUrl,
                "discordEventUrl" to DISCORD_EVENT.find(embed.description ?: "")?.value,
                "joined" to (member != null && (member.idLong in ids || roster.entries.any { it == member.effectiveName })),
                "roster" to people,
            )
        }
        return obj(
            "events" to events,
            "games" to GAME_PING_ROLES.keys.toList(),
            "canCreate" to (member != null && isEventMaker(member)),
            "eventChannel" to eventChannel(guild)?.name,
            "minAttendance" to Rules.EVENT_MIN_ATTENDANCE_MINUTES,
            "attendanceXp" to Rules.EVENT_ATTENDANCE_XP,
            "durationHours" to Rules.EVENT_DURATION_HOURS,
        )
    }

    private suspend fun signup(member: Member, p: JsonObject, join: Boolean): JsonObject {
        val id = p.id("event") ?: throw WebActionError("Welches Event?")
        val open = bot.events.get(id) ?: throw WebActionError("Dieses Event ist schon vorbei oder gelöscht.")
        val result = EventSignup.signup(id, member, join) { fetchEventMessage(open.channelId, id) }
        return when (result) {
            SignupResult.DONE -> obj(
                "ok" to true,
                "message" to if (join) {
                    "Du bist dabei! Sei beim Event mindestens ${Rules.EVENT_MIN_ATTENDANCE_MINUTES} Minuten im Voice, dann bekommst du " +
                        "+${Rules.EVENT_ATTENDANCE_XP} XP. Eine Erinnerung kommt ${Rules.EVENT_REMINDER_MINUTES} Minuten vor Start im Discord."
                } else {
                    "Du wurdest von der Liste gestrichen."
                },
            )
            SignupResult.ALREADY -> obj("ok" to true, "message" to "Du bist schon dabei.")
            SignupResult.NOT_LISTED -> obj("ok" to true, "message" to "Du stehst nicht auf der Liste.")
            SignupResult.CLOSED -> throw WebActionError("Dieses Event ist bereits vorbei – keine An- oder Abmeldung mehr möglich.")
            SignupResult.GONE -> throw WebActionError("Die Event-Nachricht gibt es nicht mehr.")
        }
    }

    private suspend fun createEvent(member: Member, p: JsonObject): JsonObject {
        if (!isEventMaker(member)) {
            val role = member.guild.getRoleById(Roles.EVENT_MANAGER)?.name ?: "Event Maker"
            throw WebActionError("Events erstellen dürfen nur Mitglieder mit der Rolle „$role“ und Admins.")
        }
        val game = p.text("game")?.takeIf { it in GAME_PING_ROLES } ?: throw WebActionError("Bitte ein Spiel auswählen.")
        val start = parseWebStart(p.text("start")) ?: throw WebActionError("Bitte Datum und Uhrzeit angeben.")
        val now = ZonedDateTime.now(TIMEZONE)
        if (!start.isAfter(now)) throw WebActionError("Der Zeitpunkt liegt in der Vergangenheit.")
        if (start.isAfter(now.plusDays(180))) throw WebActionError("Events gehen höchstens ein halbes Jahr im Voraus.")
        val recent = createdEvents.getOrPut(member.idLong) { mutableListOf() }
        recent.removeIf { it.plus(Duration.ofHours(1)).isBefore(Instant.now()) }
        if (recent.size >= 5) throw WebActionError("Du hast in der letzten Stunde schon 5 Events erstellt – bitte später nochmal.")
        val channel = eventChannel(member.guild)
            ?: throw WebActionError("Es ist noch kein Event-Channel eingestellt. Ein Admin kann ihn im Admin-Panel festlegen.")
        val created = createGameEvent(bot, channel, game, start, member)
        recent.add(Instant.now())
        eventMessages[created.message.idLong] = created.message to Instant.now()
        bot.audit.log(Actor.of(member), "Event erstellt (Website)", "**$game** am <t:${start.toEpochSecond()}:F> in ${channel.asMention}\n${created.message.jumpUrl}")
        return obj(
            "ok" to true,
            "id" to created.message.idLong.toString(),
            "message" to "Event erstellt in #${channel.name}." +
                if (created.discordEventUrl == null) " Das Discord-Event konnte nicht angelegt werden (Rechte des Bots?)." else "",
        )
    }

    /** Channel für Events von der Website: im Admin-Panel eingestellt, sonst der Channel des letzten Events. */
    private fun eventChannel(guild: Guild): GuildMessageChannel? {
        val configured = bot.db.getSetting(EVENT_CHANNEL_KEY)?.toLongOrNull()
        val fallback = (bot.events.all().map { it.channelId to it.messageId } +
            bot.events.pendingRecaps().map { it.channelId to it.messageId }).maxByOrNull { it.second }?.first
        val id = configured ?: fallback ?: return null
        return guild.getChannelById(GuildMessageChannel::class.java, id)?.takeIf { it.canTalk() }
    }

    private suspend fun eventMessage(channelId: Long, messageId: Long): Message? {
        val cached = eventMessages[messageId]
        if (cached != null && cached.second.plus(EVENT_CACHE).isAfter(Instant.now())) return cached.first
        return try {
            fetchEventMessage(channelId, messageId)?.also { eventMessages[messageId] = it to Instant.now() }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Event-Nachricht {} nicht abrufbar: {}", messageId, e.describe())
            cached?.first
        }
    }

    private suspend fun fetchEventMessage(channelId: Long, messageId: Long): Message? {
        val channel = bot.jda.getChannelById(GuildMessageChannel::class.java, channelId) ?: return null
        return try {
            channel.retrieveMessageById(messageId).await().also { eventMessages[messageId] = it to Instant.now() }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (e.isNotFound()) null else throw e
        }
    }

    // ==========================================
    // ADMIN: ÜBERSICHT
    // ==========================================

    private fun adminOverview(guild: Guild): JsonObject {
        val self = guild.selfMember
        val text = guild.textChannels.filter { it.canTalk() }.map { channel ->
            obj("id" to channel.id, "name" to channel.name, "category" to channel.parentCategory?.name)
        }
        val voice = (guild.voiceChannels + guild.stageChannels).map { channel ->
            obj(
                "id" to channel.id,
                "name" to channel.name,
                "amongUs" to (channel.idLong == Channels.AMONG_US_VOICE),
                "members" to channel.members.filter { !it.user.isBot }.map { m ->
                    obj(
                        "key" to keyOf(m),
                        "name" to m.effectiveName,
                        "muted" to (m.voiceState?.isGuildMuted == true),
                        "deafened" to (m.voiceState?.isGuildDeafened == true),
                    )
                },
            )
        }
        bot.roleWishes.expireOld()
        val wishes = bot.roleWishes.pending().map { wish ->
            obj(
                "key" to WebAuth.playerKeyOf(wish.userId),
                "name" to (guild.getMemberById(wish.userId)?.effectiveName ?: "Ehemaliges Mitglied"),
                "role" to wish.roleName,
                "label" to wish.label,
                "admin" to wish.isAdmin,
                "by" to if (wish.isAdmin) guild.getMemberById(wish.createdBy)?.effectiveName else null,
                "cost" to wish.cost,
                "reserved" to bot.roleWishes.isReserved(wish),
            )
        }
        val bets = bot.bets.active.map { bet ->
            obj(
                "key" to WebAuth.playerKeyOf(bet.userId),
                "name" to (guild.getMemberById(bet.userId)?.effectiveName ?: "?"),
                "team" to bet.team.label,
                "amount" to bet.amount,
            )
        }
        return obj(
            "server" to guild.name,
            "textChannels" to text,
            "voiceChannels" to voice,
            "eventChannel" to eventChannel(guild)?.id,
            "eventChannelSet" to (bot.db.getSetting(EVENT_CHANNEL_KEY) != null),
            "rolesChannel" to guild.getTextChannelById(Channels.ROLES)?.name,
            "tutorials" to TUTORIALS.map { obj("key" to it.key, "label" to it.label) },
            "changelogVersion" to Changelog.VERSION,
            "wishRoles" to (WISH_ROLES + TOR_WISH_ROLES).map {
                obj("id" to it.gameName, "label" to it.label, "team" to it.team?.label, "special" to (it in TOR_WISH_ROLES))
            },
            "wishes" to wishes,
            "bets" to bets,
            "betsLocked" to bot.bets.locked,
            "pendingRecaps" to bot.events.pendingRecaps().count { it.startsAt <= Instant.now() },
            "modChannel" to bot.modSettings.channelId?.let { guild.getTextChannelById(it)?.name },
            "roleGroups" to managedRoleGroups(guild).map { (title, roles) ->
                obj(
                    "title" to title,
                    "roles" to roles.map { (emoji, role) ->
                        obj("id" to role.id, "name" to role.name, "emoji" to emoji, "manageable" to self.canInteract(role))
                    },
                )
            },
        )
    }

    private fun adminMembers(guild: Guild): JsonObject {
        val managed = managedRoleGroups(guild).flatMap { group -> group.second.map { it.second.idLong } }.toSet()
        val linked = bot.links.byUser().keys
        val pending = bot.roleWishes.pending().associateBy { it.userId }
        val hidden = bot.leaderboardExclusions.keys()
        val members = guild.members.filter { !it.user.isBot }.map { member ->
            val stats = bot.levels.get(member.idLong)
            obj(
                "key" to keyOf(member),
                "name" to member.effectiveName,
                "username" to member.user.name,
                "level" to stats.level,
                "xp" to stats.xp,
                "wins" to stats.wins,
                "participations" to stats.participations,
                "rank" to stats.rank.name,
                "linked" to (member.idLong in linked),
                "admin" to isAdmin(member),
                "roles" to member.roles.filter { it.idLong in managed }.map { it.id },
                "wish" to pending[member.idLong]?.let { obj("role" to it.roleName, "label" to it.label, "admin" to it.isAdmin) },
                "voice" to member.voiceState?.channel?.name,
                "hidden" to (keyOf(member) in hidden),
            )
        }.sortedWith(compareByDescending<JsonObject> { (it["xp"] as JsonPrimitive).content.toInt() }.thenBy { (it["name"] as JsonPrimitive).content.lowercase() })
        return obj("members" to members, "xpPerLevel" to Rules.XP_PER_LEVEL, "leaderboardHidden" to hidden.toList())
    }

    /** Rollen, die das Admin-Panel vergeben darf: Rollen-Panel-Gruppen, Event Maker, Level-Belohnungen. */
    private fun managedRoleGroups(guild: Guild): List<Pair<String, List<Pair<String, Role>>>> {
        val groups = SELF_ROLE_GROUPS.map { group ->
            group.title to group.roles.mapNotNull { r -> guild.getRoleById(r.roleId)?.let { r.emoji to it } }
        }
        val special = (listOf(Roles.EVENT_MANAGER) + LEVEL_ROLE_REWARDS.values).distinct()
            .mapNotNull { id -> guild.getRoleById(id)?.let { "⭐" to it } }
        return groups + ("⭐ Besondere Rollen" to special)
    }

    // ==========================================
    // ADMIN: SPIELER
    // ==========================================

    private suspend fun setPlayer(admin: Member, p: JsonObject): JsonObject {
        val target = target(admin.guild, p)
        val before = bot.levels.get(target.idLong)
        val xp = p.int("xp")
        val level = p.int("level")
        val result = when {
            xp != null -> bot.levels.setXp(target.idLong, xp.coerceIn(0, 10_000_000))
            level != null -> bot.levels.setLevel(target.idLong, level.coerceIn(1, 100_000))
            else -> throw WebActionError("XP oder Level fehlt.")
        }
        if (result.leveledUp) bot.levels.grantLevelRoles(target, result.stats.level)
        val now = result.stats
        bot.audit.log(
            Actor.of(admin), "XP/Level geändert (Website)",
            "**Spieler:** ${target.asMention}\n**XP:** ${before.xp} → ${now.xp}\n**Level:** ${before.level} → ${now.level}",
            Palette.ORANGE,
        )
        return obj("ok" to true, "message" to "${target.effectiveName}: Level ${now.level}, ${now.xp} XP.")
    }

    private suspend fun resetPlayer(admin: Member, p: JsonObject): JsonObject {
        val target = target(admin.guild, p)
        val old = bot.levels.get(target.idLong)
        bot.levels.reset(target.idLong)
        bot.audit.log(
            Actor.of(admin), "Spielerdaten zurückgesetzt (Website)",
            "**Spieler:** ${target.asMention}\n**Vorher:** Level ${old.level}, ${old.xp} XP, ${old.wins} Siege, ${old.participations} Teilnahmen",
            Palette.RED,
        )
        return obj("ok" to true, "message" to "Daten von ${target.effectiveName} wurden zurückgesetzt.")
    }

    private suspend fun togglePlayerRole(admin: Member, p: JsonObject): JsonObject {
        val guild = admin.guild
        val target = target(guild, p)
        val roleId = p.id("role") ?: throw WebActionError("Welche Rolle?")
        val allowed = managedRoleGroups(guild).flatMap { it.second.map { r -> r.second } }.firstOrNull { it.idLong == roleId }
            ?: throw WebActionError("Diese Rolle kann das Admin-Panel nicht vergeben.")
        val give = p.flag("give") ?: (allowed !in target.roles)
        if (!guild.selfMember.canInteract(allowed)) {
            throw WebActionError("Ich darf die Rolle „${allowed.name}“ nicht vergeben – meine Bot-Rolle muss darüber stehen.")
        }
        // Exklusive Gruppen (Clans): die anderen Rollen der Gruppe gehen weg, wie beim Rollen-Panel
        val group = selfRoleGroupOf(roleId)
        val remove = if (give && group != null) {
            planRoleToggle(group, target.roles.map { it.idLong }.toSet(), roleId).remove.mapNotNull { guild.getRoleById(it) }
        } else {
            emptyList()
        }
        try {
            if (give) guild.modifyMemberRoles(target, listOf(allowed), remove).reason("Admin-Panel (Website) von ${admin.user.name}").await()
            else guild.removeRoleFromMember(target, allowed).reason("Admin-Panel (Website) von ${admin.user.name}").await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (!e.isForbidden()) throw e
            throw WebActionError("Discord verbietet mir, die Rolle „${allowed.name}“ zu ändern (Rechte des Bots).")
        }
        bot.rolePanel.requestRefresh()
        val change = if (give) "bekommt **${allowed.name}**" else "verliert **${allowed.name}**"
        val replaced = if (remove.isEmpty()) "" else " (statt ${remove.joinToString(", ") { it.name }})"
        bot.audit.log(Actor.of(admin), "Rolle geändert (Website)", "${target.asMention} $change$replaced")
        return obj("ok" to true, "message" to "${target.effectiveName} ${if (give) "hat jetzt" else "hat nicht mehr"} „${allowed.name}“$replaced.")
    }

    /** /rolle-zuweisen: Among-Us-Rolle für die nächste Runde festlegen oder entfernen. */
    private suspend fun assignRole(admin: Member, p: JsonObject): JsonObject {
        val target = target(admin.guild, p)
        val wishes = bot.roleWishes
        wishes.expireOld()
        val roleName = p.text("role")
        if (roleName == null) {
            val removed = wishes.unassign(target.idLong)
            val refund = if (removed.cost > 0) " – die ${removed.cost} XP sind erstattet" else ""
            bot.audit.log(Actor.of(admin), "🎭 Rolle entfernt (Website)", "${target.asMention}: ${if (removed.isAdmin) "Zuweisung" else "Wunsch"} **${removed.label}** entfernt$refund")
            if (removed.cost > 0) dm(target, "↩️ Ein Admin hat deinen Rollenwunsch **${removed.label}** entfernt – du bekommst deine **${removed.cost} XP** zurück.")
            return obj("ok" to true, "message" to "${plain(removed.label)} für ${target.effectiveName} entfernt$refund.")
        }
        val role = wishRole(roleName) ?: throw WebActionError("Unbekannte Rolle.")
        val result = wishes.assign(admin.idLong, target.idLong, role)
        val replaced = result.replaced
        val note = when {
            replaced == null -> ""
            replaced.cost > 0 -> " Der Wunsch ${plain(replaced.label)} wurde ersetzt, die ${replaced.cost} XP sind erstattet."
            else -> " (Vorher zugewiesen: ${plain(replaced.label)})"
        }
        bot.audit.log(Actor.of(admin), "🎭 Rolle zugewiesen (Website)", "${target.asMention} → **${role.label}** (nächste Runde)$note")
        if (replaced != null && replaced.cost > 0) {
            dm(target, "↩️ Ein Admin hat deinen Rollenwunsch **${replaced.label}** ersetzt – du bekommst deine **${replaced.cost} XP** zurück.")
        }
        return obj("ok" to true, "message" to "${target.effectiveName} bekommt in der nächsten Runde ${role.label}.$note")
    }

    private suspend fun dm(member: Member, text: String) {
        try {
            member.user.openPrivateChannel().await().sendMessage(text).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled() // DMs aus – nicht schlimm
        }
    }

    // ==========================================
    // ADMIN: VOICE & RUNDE
    // ==========================================

    private suspend fun voice(admin: Member, p: JsonObject): JsonObject {
        val channel = audioChannel(admin.guild, p)
        val op = p.text("op")
        if (op == "move") {
            val target = admin.guild.getVoiceChannelById(Channels.AMONG_US_VOICE) ?: throw WebActionError("Den Among-Us-Channel finde ich nicht.")
            if (channel.idLong == target.idLong) throw WebActionError("Das ist schon der Among-Us-Channel.")
            val report = moveMembers(channel.members, target)
            bot.audit.log(Actor.of(admin), "Alle verschoben (Website)", "${channel.asMention} → ${target.asMention}: ${report.succeeded} Spieler")
            return obj("ok" to true, "message" to plain(report.describe("nach #${target.name} verschoben")))
        }
        val muted = when (op) {
            "mute" -> true
            "unmute" -> false
            else -> throw WebActionError("Unbekannte Aktion.")
        }
        val targets = channel.members.filter { m ->
            val state = m.voiceState ?: return@filter false
            if (muted) !state.isGuildMuted else state.isGuildMuted || state.isGuildDeafened
        }
        if (targets.isEmpty()) return obj("ok" to true, "message" to "Alle in #${channel.name} sind bereits ${if (muted) "gemutet" else "entmutet"}.")
        val report = if (muted) bot.workers.setMute(targets, true) else bot.workers.release(targets)
        if (channel.idLong == Channels.AMONG_US_VOICE) bot.voiceStatus.refresh(muted)
        return obj("ok" to true, "message" to plain(report.describe(if (muted) "gemutet" else "entmutet")))
    }

    private suspend fun finishRound(admin: Member, p: JsonObject): JsonObject {
        val channel = audioChannel(admin.guild, p)
        val team = when (p.text("team")) {
            "crew" -> Team.CREW
            "impostor" -> Team.IMPOSTOR
            else -> throw WebActionError("Wer hat gewonnen?")
        }
        val impostors = (p["impostors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            .distinct().map { key -> memberByKey(admin.guild, key) ?: throw WebActionError("Einen der Impostor finde ich nicht mehr.") }
        if (impostors.size > 3) throw WebActionError("Höchstens 3 Impostor.")
        if (team == Team.IMPOSTOR && impostors.isEmpty()) throw WebActionError("Bitte die Impostor auswählen.")
        val winners = RoundService.winnersFor(team, impostors, channel.members)
        if (winners.isEmpty()) throw WebActionError("Keine Gewinner – ist jemand im Voice-Channel?")
        val embed = evaluateRound(bot, admin, team, winners)
        postResult(channel, embed)
        return obj("ok" to true, "message" to "Runde ausgewertet: ${team.label}-Sieg, ${winners.size} Gewinner. Ergebnis im Chat von #${channel.name}.")
    }

    private suspend fun cancelRound(admin: Member, p: JsonObject): JsonObject {
        val refunded = bot.bets.refundAll()
        if (refunded.isEmpty()) return obj("ok" to true, "message" to "Es sind keine Wetten aktiv.")
        val embed = EmbedBuilder()
            .setTitle("🛑 Runde abgebrochen")
            .setDescription(refunded.joinToString("\n") { "↩️ <@${it.userId}> bekommt **${it.amount} XP** zurück." })
            .setColor(Palette.WARNING)
            .build()
        p.id("channel")?.let { id -> admin.guild.getChannelById(AudioChannel::class.java, id) }?.let { postResult(it, embed) }
        bot.audit.log(Actor.of(admin), "Runde abgebrochen (Website)", "${refunded.size} Wetteinsätze erstattet.", Palette.WARNING)
        return obj("ok" to true, "message" to "Runde abgebrochen, ${refunded.size} Wetteinsätze erstattet.")
    }

    private suspend fun mvp(admin: Member, p: JsonObject): JsonObject {
        val channel = audioChannel(admin.guild, p)
        val chat = channel as? GuildMessageChannel ?: throw WebActionError("In diesem Voice-Channel gibt es keinen Chat.")
        val players = channel.members.filter { !it.user.isBot }
        if (players.size < 2) throw WebActionError("Es müssen mindestens 2 Spieler im Voice sein.")
        val rounds = bot.modules.filterIsInstance<RoundsModule>().firstOrNull() ?: throw WebActionError("Das geht gerade nicht.")
        rounds.mvpVotingIn(chat, players)
        bot.audit.log(Actor.of(admin), "MVP-Voting gestartet (Website)", "in ${channel.asMention} mit ${players.size} Spielern")
        return obj("ok" to true, "message" to "MVP-Voting läuft im Chat von #${channel.name} (${Rules.MVP_VOTING_SECONDS} Sekunden).")
    }

    private suspend fun postResult(channel: AudioChannel, embed: net.dv8tion.jda.api.entities.MessageEmbed) {
        val chat = channel as? GuildMessageChannel ?: return
        try {
            chat.sendMessageEmbeds(embed).setAllowedMentions(emptySet()).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Ergebnis nicht gepostet: {}", e.describe())
        }
    }

    // ==========================================
    // ADMIN: POSTEN & EINSTELLUNGEN
    // ==========================================

    private suspend fun post(admin: Member, p: JsonObject): JsonObject {
        val what = p.text("what")
        if (what == "rolepanel") {
            val (text, _) = postRolePanel(bot)
            bot.audit.log(Actor.of(admin), "Rollen-Panel gepostet (Website)", text)
            return obj("ok" to text.startsWith("✅"), "message" to plain(text))
        }
        if (what == "recap") {
            val events = bot.modules.filterIsInstance<EventsModule>().firstOrNull() ?: throw WebActionError("Das geht gerade nicht.")
            if (bot.events.pendingRecaps().none { it.startsAt <= Instant.now() }) {
                throw WebActionError("Kein Spieleabend-Recap offen – es läuft kein Event, oder sein Recap ist schon gepostet.")
            }
            val posted = events.postStartedRecaps()
            if (posted.isEmpty()) return obj("ok" to true, "message" to "Seit Event-Start wurden keine Mod-Runden aufgezeichnet – kein Recap.")
            bot.audit.log(Actor.of(admin), "Spieleabend-Recap gepostet (Website)", posted.joinToString(" ") { it.jumpUrl })
            return obj("ok" to true, "message" to "Spieleabend-Recap gepostet.")
        }
        val channel = p.id("channel")?.let { admin.guild.getChannelById(GuildMessageChannel::class.java, it) }
            ?.takeIf { it.canTalk() } ?: throw WebActionError("Bitte einen Channel auswählen, in den ich schreiben darf.")
        val label = when (what) {
            "panel" -> {
                channel.sendMessageEmbeds(ControlPanel.buildEmbed(openBets = bot.bets.active.size)).setComponents(ControlPanel.rows()).await()
                "Among-Us-Steuerpult"
            }
            "changelog" -> {
                channel.sendMessageEmbeds(buildChangelogEmbed(bot::mentionCommand)).await()
                "Changelog ${Changelog.VERSION}"
            }
            "tutorial" -> {
                val tutorial = tutorial(p.text("topic")) ?: throw WebActionError("Dieses Tutorial gibt es nicht.")
                for (embed in buildTutorialEmbeds(tutorial, bot::mentionCommand)) channel.sendMessageEmbeds(embed).await()
                "Tutorial „${tutorial.label}“"
            }
            else -> throw WebActionError("Was soll gepostet werden?")
        }
        bot.audit.log(Actor.of(admin), "$label gepostet (Website)", "in ${channel.asMention}")
        return obj("ok" to true, "message" to "$label in #${channel.name} gepostet.")
    }

    private suspend fun settings(admin: Member, p: JsonObject): JsonObject {
        val id = p.id("eventChannel")
        if (id == null) {
            bot.db.setSetting(EVENT_CHANNEL_KEY, null)
            return obj("ok" to true, "message" to "Event-Channel zurückgesetzt (es gilt der Channel des letzten Events).")
        }
        val channel = admin.guild.getChannelById(GuildMessageChannel::class.java, id)?.takeIf { it.canTalk() }
            ?: throw WebActionError("In diesen Channel darf ich nicht schreiben.")
        bot.db.setSetting(EVENT_CHANNEL_KEY, channel.id)
        bot.audit.log(Actor.of(admin), "Event-Channel geändert (Website)", "Events von der Website erscheinen in ${channel.asMention}")
        return obj("ok" to true, "message" to "Events von der Website erscheinen jetzt in #${channel.name}.")
    }

    private suspend fun modSetup(admin: Member, p: JsonObject): JsonObject {
        val channel = p.id("channel")?.let { admin.guild.getChannelById(StandardGuildMessageChannel::class.java, it) }
            ?: throw WebActionError("Bitte einen Text-Channel auswählen.")
        val url = setupModWebhook(bot, channel, admin)
        return obj(
            "ok" to true,
            "message" to "Rundenergebnisse erscheinen ab jetzt in #${channel.name}. Die alte Webhook-URL ist ungültig.",
            "webhookUrl" to url,
        )
    }

    /** Spieler aus den Bestenlisten (Website + /leaderboard) ausnehmen oder wieder aufnehmen. */
    private suspend fun leaderboard(admin: Member, p: JsonObject): JsonObject {
        val key = p.text("player")?.takeIf(LeaderboardExclusions.KEY_PATTERN::matches) ?: throw WebActionError("Welcher Spieler?")
        val hidden = p.flag("hidden") ?: throw WebActionError("Ausblenden oder einblenden?")
        val member = memberByKey(admin.guild, key)
        // Name nur fürs Log: Discord-Mitglied, sonst der Anzeigename von der Website
        val name = member?.effectiveName ?: p.text("name")?.take(64)?.let(::plain)?.ifEmpty { null } ?: "Spieler $key"
        val changed = bot.leaderboardExclusions.set(key, hidden)
        if (changed) {
            bot.audit.log(
                Actor.of(admin),
                if (hidden) "Aus den Bestenlisten ausgenommen (Website)" else "Wieder in den Bestenlisten (Website)",
                "**Spieler:** ${member?.asMention ?: escapeMarkdown(name)}",
                if (hidden) Palette.ORANGE else Palette.SUCCESS,
            )
        }
        val message = if (hidden) "$name erscheint nicht mehr in den Bestenlisten." else "$name erscheint wieder in den Bestenlisten."
        return obj("ok" to true, "message" to message, "hidden" to hidden, "leaderboardHidden" to bot.leaderboardExclusions.keys().toList())
    }

    // ==========================================
    // HELFER
    // ==========================================

    private fun keyOf(member: Member): String = WebAuth.playerKeyOf(member.idLong).also { keys[it] = member.idLong }

    private fun memberByKey(guild: Guild, key: String): Member? {
        keys[key]?.let { id -> guild.getMemberById(id)?.let { return it } }
        for (member in guild.members) if (keyOf(member) == key) return member
        return null
    }

    private fun target(guild: Guild, p: JsonObject): Member {
        val key = p.text("player") ?: throw WebActionError("Welcher Spieler?")
        val member = memberByKey(guild, key) ?: throw WebActionError("Diesen Spieler gibt es auf dem Server nicht (mehr).")
        if (member.user.isBot) throw WebActionError("Bots spielen nicht mit.")
        return member
    }

    private fun audioChannel(guild: Guild, p: JsonObject): AudioChannel =
        p.id("channel")?.let { guild.getChannelById(AudioChannel::class.java, it) } ?: throw WebActionError("Bitte einen Voice-Channel auswählen.")

    companion object {
        const val EVENT_CHANNEL_KEY = "web.event_channel"

        /** Nicht angemeldet (nur events.list). */
        const val GUEST = 0L

        /** Verknüpfungs-Code für CrewStats (nur von der Mod-API der Website, siehe services/ModApi.kt). */
        const val MOD_LINK = "mod.link"
        private val EVENT_CACHE: Duration = Duration.ofSeconds(60)
        private val MENTION = Regex("<@!?(\\d+)>")
        private val DISCORD_EVENT = Regex("https://discord\\.com/events/\\d+/\\d+")
        private val MARKDOWN = Regex("\\*\\*|__|~~|`")

        /** Startzeit aus dem Formular der Website (2026-11-05T19:00, deutsche Zeit). */
        fun parseWebStart(text: String?): ZonedDateTime? = try {
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it).atZone(TIMEZONE) }
        } catch (e: DateTimeParseException) {
            null
        }

        /** Spiel aus dem Event-Titel („🎮 AMONG US EVENT“, ggf. „🔒 … (BEENDET)“) – mit der Schreibweise aus der Spiele-Liste. */
        fun gameOf(title: String?): String {
            val raw = (title ?: "").removePrefix(CLOSED_PREFIX).removePrefix("🎮 ").removeSuffix(" (BEENDET)").removeSuffix(" EVENT").trim()
            return GAME_PING_ROLES.keys.firstOrNull { it.uppercase() == raw } ?: raw.ifEmpty { "Event" }
        }

        /** „Erstellt von Kilian • Anmeldung geschlossen“ → „Kilian“. */
        fun creatorOf(footer: String?): String? =
            footer?.substringAfter("Erstellt von ", "")?.substringBefore(" • ")?.trim()?.takeIf { it.isNotEmpty() }

        /** Discord-Formatierung (fett, Code …) für die Website entfernen. */
        fun plain(text: String): String = text.replace(MARKDOWN, "").replace(Regex("<#\\d+>"), "dem Channel").trim()

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.id(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toInt()

        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}
