/*
 * Auftrags-Warteschlange zwischen Stats-Website und Bot (Tabelle `web_requests`, Schema v4).
 *
 * Die Website läuft als eigener Server und hat keine Discord-Verbindung. Will jemand dort etwas tun (Event-Anmeldung,
 * Admin-Panel …), schreibt die Website einen Auftrag mit der Discord-ID der angemeldeten Person hinein. Der Bot holt
 * offene Aufträge ab, prüft die Rechte selbst über die Discord-Rollen des Mitglieds, führt sie aus und schreibt die
 * Antwort (JSON) zurück. So braucht keiner der beiden Server einen offenen Port, und alles Discord-seitige macht
 * weiterhin nur der Bot (ein Thread, keine doppelte Verarbeitung).
 *
 * Status: new → working → done. Die Website bricht nach ihrer Wartezeit ab (new → cancelled), der Bot nimmt nur
 * Aufträge mit Status new (atomar per UPDATE … WHERE status = 'new'), ein Auftrag läuft also höchstens einmal.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.db.Database
import java.time.Duration
import java.time.Instant

data class QueuedRequest(val id: Long, val userId: Long, val action: String, val payload: String, val createdAt: Instant)

class WebQueue(private val db: Database) {
    // ---------- Website ----------

    /** Neuer Auftrag; liefert seine ID. */
    fun submit(userId: Long, action: String, payload: String, now: Instant = Instant.now()): Long =
        db.insert(
            "INSERT INTO web_requests (user_id, action, payload, status, created_at) VALUES (?, ?, ?, ?, ?)",
            userId, action, payload, NEW, now,
        )

    /** Antwort des Bots, null = noch nicht fertig. */
    fun result(id: Long): String? =
        db.queryOne("SELECT status, result FROM web_requests WHERE id = ?", id) { row ->
            if (row.string("status") == DONE) row.stringOrNull("result") ?: "{}" else null
        }

    /** Abholen und löschen (die Antwort kann eine geheime Webhook-URL enthalten). */
    fun take(id: Long): String? = db.transaction {
        val result = result(id)
        if (result != null) db.update("DELETE FROM web_requests WHERE id = ?", id)
        result
    }

    /** Wartezeit vorbei: nur abbrechen, wenn der Bot ihn noch nicht angefangen hat. true = abgebrochen. */
    fun cancel(id: Long): Boolean = db.update("UPDATE web_requests SET status = ? WHERE id = ? AND status = ?", CANCELLED, id, NEW) == 1

    /** Läuft der Bot? (er meldet sich regelmäßig, auch ohne Aufträge) */
    fun botAlive(now: Instant = Instant.now(), maxAge: Duration = HEARTBEAT_MAX_AGE): Boolean {
        val beat = db.getSetting(HEARTBEAT_KEY)?.toLongOrNull() ?: return false
        return Instant.ofEpochMilli(beat).plus(maxAge).isAfter(now)
    }

    // ---------- Bot ----------

    fun open(limit: Int = 20): List<QueuedRequest> =
        db.query(
            "SELECT id, user_id, action, payload, created_at FROM web_requests WHERE status = ? ORDER BY id LIMIT $limit", NEW,
        ) { QueuedRequest(it.long("id"), it.long("user_id"), it.string("action"), it.string("payload"), it.instant("created_at")) }

    /** Auftrag übernehmen – false, wenn ihn schon jemand hat oder die Website ihn abgebrochen hat. */
    fun claim(id: Long): Boolean = db.update("UPDATE web_requests SET status = ? WHERE id = ? AND status = ?", WORKING, id, NEW) == 1

    fun complete(id: Long, result: String, now: Instant = Instant.now()) {
        db.update("UPDATE web_requests SET status = ?, result = ?, done_at = ? WHERE id = ?", DONE, result, now, id)
    }

    fun heartbeat(now: Instant = Instant.now()) = db.setSetting(HEARTBEAT_KEY, now.toEpochMilli().toString())

    /** Alte Aufträge aufräumen (abgebrochene, nie abgeholte Antworten). */
    fun cleanup(now: Instant = Instant.now()): Int = db.update("DELETE FROM web_requests WHERE created_at < ?", now - KEEP)

    companion object {
        const val NEW = "new"
        const val WORKING = "working"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
        const val HEARTBEAT_KEY = "web.bot_heartbeat"

        /** Der Bot meldet sich alle 20 s – nach einer Minute ohne Meldung gilt er als offline. */
        val HEARTBEAT_MAX_AGE: Duration = Duration.ofSeconds(60)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(20)

        /** Ältere Aufträge führt der Bot nicht mehr aus (z.B. während eines Neustarts liegen geblieben). */
        val MAX_AGE: Duration = Duration.ofSeconds(30)
        private val KEEP: Duration = Duration.ofMinutes(10)
    }
}
