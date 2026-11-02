/* Events: Gaming-Event mit Anmelde-Buttons und echtem Discord-Event erstellen. */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.GAME_PING_ROLES
import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.TIMEZONE
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.CrewModule
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.discordTimestamp
import de.amongusmanager.crewbot.core.isNotFound
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.OpenEvent
import de.amongusmanager.crewbot.services.PendingRecap
import de.amongusmanager.crewbot.services.buildRecap
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildRecapEmbed
import de.amongusmanager.crewbot.ui.field
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.views.EventButtons
import de.amongusmanager.crewbot.ui.views.EventRoster
import de.amongusmanager.crewbot.ui.views.buildEventEmbed
import de.amongusmanager.crewbot.ui.views.closeEventMessage
import de.amongusmanager.crewbot.ui.views.eventEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** DD.MM.YYYY HH:MM – einstellige Tage/Monate/Stunden gehen wie früher auch (5.8.2026 9:00). */
private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d.M.uuuu H:m").withResolverStyle(ResolverStyle.STRICT)

/** Startzeit aus der Eingabe von /create-event (deutsche Zeit) oder null bei ungültigem Format. */
fun parseEventStart(text: String): ZonedDateTime? =
    try {
        // wie Pythons strptime: beliebig viele Leerzeichen zwischen Datum und Uhrzeit
        LocalDateTime.parse(text.trim().replace(Regex("\\s+"), " "), DATE_FORMAT).atZone(TIMEZONE)
    } catch (e: DateTimeParseException) {
        null
    }

private val eventLog = LoggerFactory.getLogger(EventsModule::class.java)

data class CreatedEvent(val message: Message, val discordEventUrl: String?)

/** Event-Nachricht mit Anmelde-Buttons + Discord-Event anlegen (/create-event und Stats-Website). */
suspend fun createGameEvent(bot: CrewBot, channel: MessageChannel, game: String, start: ZonedDateTime, creator: Member): CreatedEvent {
    val eventUrl = createScheduledEvent(creator.guild, game, start)
    val embed = buildEventEmbed(game, start, creator.effectiveName, eventUrl)

    // Der Ping muss im Nachrichtentext stehen – in einem Embed pingt Discord nicht.
    val roleId = GAME_PING_ROLES[game]
    val content = MessageCreateBuilder()
        .setEmbeds(embed)
        .setComponents(EventButtons.row())
        .setAllowedMentions(listOf(Message.MentionType.ROLE, Message.MentionType.USER))
    if (roleId != null) content.setContent("🔔 <@&$roleId>")
    val message = channel.sendMessage(content.build()).await()
    bot.events.add(message.idLong, message.channel.idLong, start.toInstant(), eventEnd(start.toInstant()), Instant.now())
    return CreatedEvent(message, eventUrl)
}

private suspend fun createScheduledEvent(guild: Guild, game: String, start: ZonedDateTime): String? =
    try {
        val scheduled = guild.createScheduledEvent(
            "$game Event",
            "Voice Channels",
            start.toOffsetDateTime(),
            start.plusHours(Rules.EVENT_DURATION_HOURS).toOffsetDateTime(),
        ).setDescription("🎮 Gemeinsame Runde $game! Melde dich im Channel an.").await()
        "https://discord.com/events/${guild.id}/${scheduled.id}"
    } catch (e: Exception) {
        e.rethrowIfCancelled()
        eventLog.warn("Discord-Event konnte nicht erstellt werden: {}", e.describe())
        null
    }

class EventsModule(private val bot: CrewBot) : CrewModule("Events", "📅") {
    private val log = LoggerFactory.getLogger(EventsModule::class.java)
    private var loopStarted = false

    override val commands = listOf(
        SlashCommand(
            "create-event", "Erstellt ein Gaming-Event mit Anmeldung & Discord-Event",
            options = listOf(
                OptionData(OptionType.STRING, "spiel", "Welches Spiel?", true)
                    .addChoices(GAME_PING_ROLES.keys.map { Command.Choice(it, it) }),
                OptionData(
                    OptionType.STRING, "datum_zeit",
                    "Datum und Startzeit: DD.MM.YYYY HH:MM (z.B. 25.08.2026 19:00)", true,
                ),
            ),
        ) { event ->
            val game = event.stringOption("spiel")!!
            val start = parseEventStart(event.stringOption("datum_zeit")!!)
            if (start == null) {
                event.notify(
                    "❌ **Ungültiges Format!** Bitte nutze exakt: `DD.MM.YYYY HH:MM` (z.B. `25.08.2026 19:00`)",
                    Tone.ERROR,
                )
                return@SlashCommand
            }
            if (!start.isAfter(ZonedDateTime.now(TIMEZONE))) {
                event.notify("❌ Der Zeitpunkt liegt in der Vergangenheit!", Tone.ERROR)
                return@SlashCommand
            }

            event.deferReply(true).await()

            val created = createGameEvent(bot, event.channel, game, start, event.author)
            val note = if (created.discordEventUrl != null) "" else "\n⚠️ Das Discord-Event konnte nicht erstellt werden (Rechte?)."
            event.notify("✅ Event erstellt: ${created.message.jumpUrl}$note", Tone.SUCCESS)
        },
        SlashCommand(
            "spieleabend-recap",
            "Postet jetzt den Spieleabend-Recap (sonst automatisch ${Rules.RECAP_AFTER_HOURS} Stunden nach Event-Start)",
            Permission.ADMINISTRATOR,
        ) { event ->
            val started = bot.events.pendingRecaps().filter { it.startsAt <= Instant.now() }
            if (started.isEmpty()) {
                event.notify(
                    "ℹ️ Kein Spieleabend-Recap offen – es läuft kein Event, oder sein Recap ist schon gepostet." +
                        "\nRecap der letzten Stunden ohne Event: ${bot.mentionCommand("recap")}",
                    Tone.WARNING,
                )
                return@SlashCommand
            }
            event.deferReply(true).await()
            val posted = postStartedRecaps()
            if (posted.isEmpty()) {
                event.notify("ℹ️ Seit Event-Start wurden keine Mod-Runden aufgezeichnet – kein Recap.", Tone.WARNING)
            } else {
                event.notify("✅ Spieleabend-Recap gepostet: ${posted.joinToString(" ") { it.jumpUrl }}", Tone.SUCCESS)
            }
        },
    )

    /** Recaps aller schon gestarteten Events jetzt posten (/spieleabend-recap, Website). */
    suspend fun postStartedRecaps(): List<Message> =
        bot.events.pendingRecaps().filter { it.startsAt <= Instant.now() }.mapNotNull { postRecap(it) }

    // ==========================================
    // EVENT-ABLAUF (läuft jede Minute)
    // ==========================================

    override suspend fun onReady() {
        if (loopStarted) return
        loopStarted = true
        bot.scope.launch {
            while (isActive) {
                val started = System.nanoTime()
                lifecycle()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                delay(maxOf(0L, 60_000L - elapsed))
            }
        }
    }

    /** Erinnern -> Anwesenheit messen -> nach Ende abrechnen & sperren -> Spieleabend-Recap, wenn fällig. */
    private suspend fun lifecycle() {
        // Jeder Schritt ist einzeln abgesichert: ein Fehler darf nie die ganze Schleife stoppen.
        val now = Instant.now()
        for (event in bot.events.needingReminder(now)) {
            try {
                sendReminder(event)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.error("Erinnerung für Event {} fehlgeschlagen", event.messageId, e)
                bot.events.markReminded(event.messageId)
            }
        }
        try {
            trackAttendance(now)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Anwesenheit konnte nicht erfasst werden", e)
        }
        for (event in bot.events.due(now)) {
            try {
                finishEvent(event)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.error("Event {} konnte nicht abgeschlossen werden", event.messageId, e)
            }
        }
        for (pending in bot.events.pendingRecaps().filter { it.dueAt <= now }) {
            try {
                postRecap(pending)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.error("Recap für Event {} fehlgeschlagen", pending.messageId, e)
            }
        }
    }

    private suspend fun sendReminder(event: OpenEvent) {
        val message = fetchMessage(event)
        if (message != null) {
            val playerIds = EventRoster.fromEmbed(message.embeds.firstOrNull()).memberIds()
            if (playerIds.isNotEmpty()) {
                val mentions = playerIds.joinToString(" ") { "<@$it>" }
                message.reply("⏰ **Gleich geht's los!** Das Event startet ${event.startsAt.discordTimestamp("R")}.\n$mentions")
                    .setAllowedMentions(listOf(Message.MentionType.USER))
                    .await()
            }
        }
        bot.events.markReminded(event.messageId)
    }

    /** Schreibt allen, die gerade in einem Voice-Channel sind, eine Minute gut. */
    private fun trackAttendance(now: Instant) {
        for (event in bot.events.running(now)) {
            val channel = bot.jda.getGuildChannelById(event.channelId) ?: continue
            val guild = channel.guild
            val present = LinkedHashSet<Long>()
            val afk = guild.afkChannel?.idLong
            for (voice in guild.voiceChannels + guild.stageChannels) {
                if (voice.idLong == afk) continue
                for (member in voice.members) if (!member.user.isBot) present.add(member.idLong)
            }
            bot.events.recordAttendance(event.messageId, present)
        }
    }

    private suspend fun finishEvent(event: OpenEvent) {
        val message = try {
            fetchMessage(event)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Event {} nicht abrufbar, neuer Versuch in 1 Min: {}", event.messageId, e.describe())
            return
        }

        // Ab hier wird nicht wiederholt – so kann niemand doppelt XP bekommen.
        bot.events.remove(event.messageId)
        if (message == null) return // Nachricht wurde gelöscht
        try {
            closeEventMessage(message)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Event {} konnte nicht gesperrt werden: {}", event.messageId, e.describe())
        }
        payAttendance(message, event)
        // Der Spieleabend-Recap kommt später: per /spieleabend-recap oder RECAP_AFTER_HOURS nach Start
    }

    private suspend fun payAttendance(message: Message, event: OpenEvent) {
        val playerIds = EventRoster.fromEmbed(message.embeds.firstOrNull()).memberIds()
        if (playerIds.isEmpty()) return

        val present = ArrayList<String>()
        val absent = ArrayList<String>()
        for (userId in playerIds) {
            if (!event.attended(userId)) {
                absent.add("<@$userId>")
                continue
            }
            val result = bot.levels.award(message.guild, userId, Rules.EVENT_ATTENDANCE_XP, participation = true)
            var line = "<@$userId>"
            if (result.leveledUp) line += " 🎊 Level ${result.stats.level}!"
            present.add(line)
        }

        val embed = EmbedBuilder()
            .setTitle("🏁 Event beendet – Danke fürs Mitspielen!")
            .setColor(Palette.SUCCESS)
            .field("✅ Dabei gewesen (+${Rules.EVENT_ATTENDANCE_XP} XP)", present.joinToString("\n").ifEmpty { "Niemand 😢" })
            .field("👻 Nicht erschienen", absent.joinToString("\n").ifEmpty { "Niemand 🎉" })
            .setFooter("Anwesend = mind. ${Rules.EVENT_MIN_ATTENDANCE_MINUTES} Minuten im Voice")
            .build()
        message.replyEmbeds(embed).setAllowedMentions(emptySet()).mentionRepliedUser(false).await()
    }

    /**
     * Spieleabend-Recap aus den Mod-Runden seit Event-Start als Antwort auf die Event-Nachricht.
     * Danach ist er erledigt (auch ohne Runden). null = nichts gepostet. Wirft nur, wenn Discord die
     * Event-Nachricht gerade nicht liefert – dann bleibt der Recap offen (nächster Versuch in 1 Min).
     */
    private suspend fun postRecap(pending: PendingRecap): Message? {
        val message = fetchMessage(pending.channelId, pending.messageId)
        bot.events.removePendingRecap(pending.messageId) // ab hier nicht wiederholen – nie doppelt posten
        if (message == null) return null // Nachricht wurde gelöscht
        val rounds = bot.roundHistory.rounds(since = pending.startsAt, until = Instant.now())
        if (rounds.isEmpty()) return null
        val embed = buildRecapEmbed(buildRecap(rounds), "🌙 Spieleabend-Recap")
        return message.replyEmbeds(embed).setAllowedMentions(emptySet()).mentionRepliedUser(false).await()
    }

    private suspend fun fetchMessage(event: OpenEvent): Message? = fetchMessage(event.channelId, event.messageId)

    /** Holt die Event-Nachricht. null, wenn sie (oder ihr Channel) gelöscht wurde. */
    private suspend fun fetchMessage(channelId: Long, messageId: Long): Message? {
        val channel = bot.jda.getChannelById(GuildMessageChannel::class.java, channelId) ?: return null
        return try {
            channel.retrieveMessageById(messageId).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (e.isNotFound()) null else throw e
        }
    }
}
