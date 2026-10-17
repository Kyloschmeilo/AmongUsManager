/* Offene Events: Erinnerung, Anwesenheit im Voice und Abschluss nach Event-Ende. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.db.Database
import java.time.Duration
import java.time.Instant

data class OpenEvent(
    val messageId: Long,
    val channelId: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val reminded: Boolean = false,
    /** user_id -> Minuten, die der Spieler während des Events im Voice war. */
    val attendance: Map<Long, Int> = emptyMap(),
) {
    val remindAt: Instant get() = startsAt - Duration.ofMinutes(Rules.EVENT_REMINDER_MINUTES)

    fun isRunning(now: Instant): Boolean = startsAt <= now && now < endsAt

    fun attended(userId: Long): Boolean = (attendance[userId] ?: 0) >= Rules.EVENT_MIN_ATTENDANCE_MINUTES
}

/** Event, dessen Spieleabend-Recap noch aussteht. */
data class PendingRecap(val messageId: Long, val channelId: Long, val startsAt: Instant) {
    /** Ohne /spieleabend-recap kommt der Recap automatisch zu diesem Zeitpunkt. */
    val dueAt: Instant get() = startsAt + Duration.ofHours(Rules.RECAP_AFTER_HOURS)
}

class EventService(private val db: Database) {
    fun get(messageId: Long): OpenEvent? = all().firstOrNull { it.messageId == messageId }

    fun add(messageId: Long, channelId: Long, startsAt: Instant, endsAt: Instant, now: Instant): OpenEvent {
        val draft = OpenEvent(messageId, channelId, startsAt, endsAt)
        // Wird das Event kurzfristig erstellt, ist eine Erinnerung überflüssig
        val event = draft.copy(reminded = now >= draft.remindAt)
        db.transaction {
            db.update("DELETE FROM events WHERE message_id = ?", messageId)
            db.update(
                "INSERT INTO events (message_id, channel_id, starts_at, ends_at, reminded) VALUES (?, ?, ?, ?, ?)",
                messageId, channelId, startsAt, endsAt, event.reminded,
            )
            savePendingRecaps(pendingRecaps().filter { it.messageId != messageId } + PendingRecap(messageId, channelId, startsAt))
        }
        return event
    }

    fun needingReminder(now: Instant): List<OpenEvent> =
        all().filter { !it.reminded && it.remindAt <= now && now < it.startsAt }

    fun running(now: Instant): List<OpenEvent> = all().filter { it.isRunning(now) }

    /** Alle Events, deren Ende erreicht ist. */
    fun due(now: Instant): List<OpenEvent> = all().filter { it.endsAt <= now }

    fun markReminded(messageId: Long) {
        db.update("UPDATE events SET reminded = 1 WHERE message_id = ?", messageId)
    }

    /** Schreibt allen übergebenen Spielern Anwesenheits-Minuten gut. */
    fun recordAttendance(messageId: Long, userIds: Collection<Long>, minutes: Int = 1) {
        db.transaction {
            if (db.queryOne("SELECT 1 AS found FROM events WHERE message_id = ?", messageId) { true } == null) {
                return@transaction
            }
            for (userId in userIds) {
                db.update(
                    "INSERT INTO event_attendance (message_id, user_id, minutes) VALUES (?, ?, ?)" +
                        " ${db.onConflictUpdate("message_id, user_id")} minutes = minutes + ${db.excluded("minutes")}",
                    messageId, userId, minutes,
                )
            }
        }
    }

    /** Event abgeschlossen (Anwesenheit abgerechnet) – sein Recap bleibt offen. */
    fun remove(messageId: Long) {
        db.transaction {
            if (db.getSetting(RECAP_KEY) == null) savePendingRecaps(pendingRecaps()) // Recaps alter Events festhalten
            db.update("DELETE FROM events WHERE message_id = ?", messageId)
        }
    }

    // ---------- Spieleabend-Recap ----------
    // Jedes Event bekommt beim Erstellen einen offenen Recap. Gepostet wird er per /spieleabend-recap oder
    // automatisch RECAP_AFTER_HOURS nach dem Start – unabhängig vom Abschluss des Events (Anwesenheits-XP).

    /** Offene Recaps in der Reihenfolge, in der die Events erstellt wurden. */
    fun pendingRecaps(): List<PendingRecap> {
        // Noch nie gespeichert: Events von vor dieser Funktion bekommen ihren Recap auch
        val stored = db.getSetting(RECAP_KEY) ?: return all().map { PendingRecap(it.messageId, it.channelId, it.startsAt) }
        return stored.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val messageId = parts[0].toLongOrNull() ?: return@mapNotNull null
            val channelId = parts[1].toLongOrNull() ?: return@mapNotNull null
            val startsAt = parts[2].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
            PendingRecap(messageId, channelId, startsAt)
        }
    }

    fun removePendingRecap(messageId: Long) {
        db.transaction { savePendingRecaps(pendingRecaps().filter { it.messageId != messageId }) }
    }

    private fun savePendingRecaps(recaps: List<PendingRecap>) {
        db.setSetting(RECAP_KEY, recaps.joinToString(";") { "${it.messageId}:${it.channelId}:${it.startsAt.toEpochMilli()}" })
    }

    /** Alle offenen Events in der Reihenfolge, in der sie erstellt wurden. */
    fun all(): List<OpenEvent> = db.transaction {
        val attendance = HashMap<Long, LinkedHashMap<Long, Int>>()
        db.query("SELECT message_id, user_id, minutes FROM event_attendance ORDER BY rowid") {
            attendance.getOrPut(it.long("message_id")) { LinkedHashMap() }[it.long("user_id")] = it.int("minutes")
        }
        db.query("SELECT message_id, channel_id, starts_at, ends_at, reminded FROM events ORDER BY message_id") {
            val messageId = it.long("message_id")
            OpenEvent(
                messageId = messageId,
                channelId = it.long("channel_id"),
                startsAt = it.instant("starts_at"),
                endsAt = it.instant("ends_at"),
                reminded = it.bool("reminded"),
                attendance = attendance[messageId] ?: emptyMap(),
            )
        }
    }

    companion object {
        const val RECAP_KEY = "events.pending_recaps"
    }
}
