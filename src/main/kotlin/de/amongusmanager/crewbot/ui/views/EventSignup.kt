/* Event-Anmeldung: Teilnehmerliste im Embed, An-/Abmelden und der Fun-Button. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.TIMEZONE
import de.amongusmanager.crewbot.content.FUN_TEXTS
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.discordTimestamp
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random

private val WEEKDAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
private val MENTION_PATTERN = Regex("<@!?(\\d+)>")
private val TIMESTAMP_PATTERN = Regex("<t:(\\d+):")
private val LEGACY_DATE_PATTERN = Regex("\\((\\d{2}\\.\\d{2}\\.\\d{4})\\)")
private val LEGACY_TIME_PATTERN = Regex("UM (\\d{2}:\\d{2}) UHR")
private val LEGACY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
const val CLOSED_PREFIX = "🔒 "
private const val CLOSED_NOTE = "Anmeldung geschlossen"

fun eventEnd(start: Instant): Instant = start + Duration.ofHours(Rules.EVENT_DURATION_HOURS)

/** Liest die Startzeit aus dem Event-Embed (neues und altes Format). */
fun eventStartFromEmbed(embed: MessageEmbed): Instant? {
    val text = embed.description ?: ""
    TIMESTAMP_PATTERN.find(text)?.let { return Instant.ofEpochSecond(it.groupValues[1].toLong()) }
    val date = LEGACY_DATE_PATTERN.find(text)
    val time = LEGACY_TIME_PATTERN.find(text)
    if (date != null && time != null) {
        return try {
            LocalDateTime.parse("${date.groupValues[1]} ${time.groupValues[1]}", LEGACY_FORMAT).atZone(TIMEZONE).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }
    }
    return null
}

fun isEventOver(embed: MessageEmbed, now: Instant = Instant.now()): Boolean {
    if ((embed.title ?: "").startsWith(CLOSED_PREFIX)) return true
    val start = eventStartFromEmbed(embed)
    return start != null && now >= eventEnd(start)
}

object EventButtons {
    const val JOIN = "evt_yes_p"
    const val LEAVE = "evt_no_p"
    const val FUN = "evt_fun_p"

    /** Die dauerhaften Buttons unter einem Event (custom_ids nie ändern – alte Nachrichten!). */
    fun row(closed: Boolean = false): ActionRow = ActionRow.of(
        Button.of(ButtonStyle.SUCCESS, JOIN, "Ich bin dabei").withEmoji(Emoji.fromUnicode("✅")).withDisabled(closed),
        Button.of(ButtonStyle.DANGER, LEAVE, "Diesmal nicht").withEmoji(Emoji.fromUnicode("❌")).withDisabled(closed),
        Button.of(ButtonStyle.PRIMARY, FUN, "Fun").withEmoji(Emoji.fromUnicode("🎲")),
    )
}

/** Markiert das Event als beendet und sperrt An- und Abmeldung. */
suspend fun closeEventMessage(message: Message) {
    val original = message.embeds.firstOrNull() ?: return
    message.editMessageEmbeds(closedEventEmbed(original)).setComponents(EventButtons.row(closed = true)).await()
}

/** Das Event-Embed im Zustand „beendet“ (grau, 🔒 im Titel, Hinweis im Footer – nur einmal). */
fun closedEventEmbed(original: MessageEmbed): MessageEmbed {
    val embed = EmbedBuilder(original)
    if (!(original.title ?: "").startsWith(CLOSED_PREFIX)) {
        embed.setTitle("$CLOSED_PREFIX${original.title ?: "EVENT"} (BEENDET)")
    }
    embed.setColor(Palette.DARK_GREY)
    val footer = original.footer?.text ?: ""
    if (!footer.endsWith(CLOSED_NOTE)) embed.setFooter("$footer • $CLOSED_NOTE".trimStart(' ', '•'))
    return embed.build()
}

fun buildEventEmbed(game: String, start: ZonedDateTime, creator: String, eventUrl: String? = null): MessageEmbed {
    val local = start.withZoneSameInstant(TIMEZONE)
    val lines = mutableListOf(
        "# 📅 ${WEEKDAYS[local.dayOfWeek.value - 1]} (${local.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))})",
        "# ⏰ UM ${local.format(DateTimeFormatter.ofPattern("HH:mm"))} UHR",
        "⏳ Start ${start.toInstant().discordTimestamp("R")}",
        "",
        "**Wir spielen zusammen! Sei dabei!**",
    )
    if (eventUrl != null) lines += listOf("", "📌 **[Zum Discord Event]($eventUrl)**")

    val embed = EmbedBuilder()
        .setTitle("🎮 ${game.uppercase()} EVENT")
        .setDescription(lines.joinToString("\n"))
        .setColor(Palette.EVENT)
    EventRoster(mutableListOf()).writeTo(embed)
    embed.setFooter("Erstellt von $creator")
    return embed.build()
}

/**
 * Die Teilnehmerliste, die direkt im Event-Embed gespeichert ist.
 *
 * Neue Einträge sind Erwähnungen (<@id>), damit Namensänderungen nichts
 * kaputt machen. Alte Einträge (nur Anzeigename) werden weiter erkannt.
 */
class EventRoster(val entries: MutableList<String>) {
    companion object {
        const val COUNT_FIELD = "✅ Dabei"
        const val LIST_FIELD = "📝 Crewliste"
        const val EMPTY_TEXT = "Noch niemand..."

        fun fromEmbed(embed: MessageEmbed?): EventRoster {
            val value = embed?.fields?.getOrNull(1)?.value ?: ""
            if (value.isEmpty() || EMPTY_TEXT in value) return EventRoster(mutableListOf())
            return EventRoster(value.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList())
        }
    }

    /** IDs aller Einträge, die als Erwähnung gespeichert sind. */
    fun memberIds(): List<Long> = entries.mapNotNull { MENTION_PATTERN.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }

    fun contains(member: Member): Boolean = indexOf(member) != null

    fun add(member: Member) {
        entries.add(member.asMention)
    }

    fun remove(member: Member): Boolean {
        val index = indexOf(member) ?: return false
        entries.removeAt(index)
        return true
    }

    fun writeTo(embed: EmbedBuilder): EmbedBuilder {
        val count = "## ${entries.size}"
        val names = entries.joinToString("\n").ifEmpty { EMPTY_TEXT }
        if (embed.fields.size >= 2) {
            embed.fields[0] = MessageEmbed.Field(COUNT_FIELD, count, true)
            embed.fields[1] = MessageEmbed.Field(LIST_FIELD, names, true)
        } else {
            embed.clearFields()
            embed.addField(COUNT_FIELD, count, true)
            embed.addField(LIST_FIELD, names, true)
        }
        return embed
    }

    private fun indexOf(member: Member): Int? {
        entries.forEachIndexed { index, entry ->
            val match = MENTION_PATTERN.matchEntire(entry)
            if ((match != null && match.groupValues[1].toLongOrNull() == member.idLong) || entry == member.effectiveName) {
                return index
            }
        }
        return null
    }
}

/** Ergebnis einer An- oder Abmeldung (Button im Discord oder Stats-Website). */
enum class SignupResult { DONE, ALREADY, NOT_LISTED, CLOSED, GONE }

/** Klicks auf die dauerhaften Event-Buttons. */
object EventSignup {
    // Ein Lock pro Event-Nachricht verhindert, dass sich gleichzeitige Klicks gegenseitig überschreiben.
    private val locks = HashMap<Long, Mutex>()

    private fun lockFor(messageId: Long): Mutex = locks.getOrPut(messageId) { Mutex() }

    /** Wird nach jeder Änderung der Teilnehmerliste aufgerufen (Website: Zwischenspeicher aktualisieren). */
    var onChange: (Message) -> Unit = {}

    /**
     * An- ([join] = true) oder abmelden. [fetch] holt die aktuelle Nachricht (im Lock, damit nichts verloren geht),
     * null = gelöscht. Ist das Event vorbei, wird die Nachricht gesperrt.
     */
    suspend fun signup(messageId: Long, member: Member, join: Boolean, fetch: suspend () -> Message?): SignupResult =
        lockFor(messageId).withLock {
            val message = fetch() ?: return@withLock SignupResult.GONE
            val embed = message.embeds.firstOrNull() ?: return@withLock SignupResult.GONE
            if (isEventOver(embed)) {
                closeEventMessage(message)
                return@withLock SignupResult.CLOSED
            }
            val roster = EventRoster.fromEmbed(embed)
            if (join) {
                if (roster.contains(member)) return@withLock SignupResult.ALREADY
                roster.add(member)
            } else if (!roster.remove(member)) {
                return@withLock SignupResult.NOT_LISTED
            }
            val edited = message.editMessageEmbeds(roster.writeTo(EmbedBuilder(embed)).build()).await()
            onChange(edited)
            SignupResult.DONE
        }

    suspend fun join(event: ButtonInteractionEvent) {
        event.deferEdit().await()
        val member = event.member ?: return
        when (signup(event.messageIdLong, member, join = true) { freshMessage(event) }) {
            SignupResult.ALREADY -> event.notify("Du bist schon dabei! 😉")
            SignupResult.CLOSED -> rejectClosed(event)
            SignupResult.DONE ->
                // XP gibt es erst nach dem Event – und nur, wenn man wirklich im Voice war.
                event.notify(
                    "🚀 Du bist auf der Liste!\n🎁 Sei beim Event mindestens ${Rules.EVENT_MIN_ATTENDANCE_MINUTES} Minuten im" +
                        " Voice, dann bekommst du **+${Rules.EVENT_ATTENDANCE_XP} XP**. Eine Erinnerung kommt" +
                        " ${Rules.EVENT_REMINDER_MINUTES} Minuten vor Start.",
                    Tone.SUCCESS,
                )
            else -> Unit
        }
    }

    suspend fun leave(event: ButtonInteractionEvent) {
        event.deferEdit().await()
        val member = event.member ?: return
        when (signup(event.messageIdLong, member, join = false) { freshMessage(event) }) {
            SignupResult.NOT_LISTED -> event.notify("Du stehst sowieso nicht auf der Liste! 😉")
            SignupResult.CLOSED -> rejectClosed(event)
            SignupResult.DONE -> event.notify("📋 Du wurdest von der Liste gestrichen.")
            else -> Unit
        }
    }

    suspend fun funButton(event: ButtonInteractionEvent) {
        event.deferEdit().await()
        if (Random.nextDouble() >= Rules.FUN_TIMEOUT_CHANCE) {
            event.notify(FUN_TEXTS.random())
            return
        }

        val member = event.member ?: return
        try {
            member.timeoutFor(Duration.ofMinutes(Rules.FUN_TIMEOUT_MINUTES)).reason("Pech beim Fun-Button!").await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            if (!e.isForbidden()) throw e
            event.notify(
                "🛡️ Du hättest eigentlich einen Timeout bekommen, aber mir fehlen die Rechte dafür!",
                Tone.WARNING,
            )
            return
        }
        event.channel.sendMessage(
            "💀 **💥 KABOOM!** ${member.asMention} hatte Pech und wurde für ${Rules.FUN_TIMEOUT_MINUTES} Minuten getimeouted!"
        ).await()
    }

    private suspend fun rejectClosed(event: ButtonInteractionEvent) {
        event.notify("🔒 Dieses Event ist bereits vorbei – keine An- oder Abmeldung mehr möglich.", Tone.WARNING)
    }

    /** Holt die aktuelle Version der Nachricht (nicht den Stand beim Klick). */
    private suspend fun freshMessage(event: ButtonInteractionEvent): Message =
        try {
            event.channel.retrieveMessageById(event.messageIdLong).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            event.message
        }
}
