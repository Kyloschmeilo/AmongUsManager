/*
 * Stats-Website: Lobby-Planer mit Terminabstimmung (Kilian, 21.11.2026) – wie eine Doodle-Umfrage für den nächsten
 * Among-Us-Abend. Angemeldete Nutzer schlagen bis zu 12 Termine vor, alle Angemeldeten stimmen je Termin mit
 * Ja / Vielleicht / Nein ab, der Ersteller legt am Ende den Termin fest (Event Maker können daraus ein Discord-Event machen,
 * das läuft über die normale Event-Seite). Gespeichert in der Website-Datenbank (`data/website.db`):
 * `web_plans`, `web_plan_slots`, `web_plan_votes`.
 *
 *   GET  /api/planer         aktuelle Planungen (Termine noch nicht länger als 2 Tage vorbei) mit Zählern
 *   GET  /api/planer/<id>    eine Planung mit allen Abstimmenden (Discord-Name, kein Konto-Kennzeichen)
 *   POST /api/planer         {"action":"create","title","note","slots":[ISO-Zeit …]}
 *   POST /api/planer/<id>    {"action":"vote","answers":{"<slotId>":2|1|0|null}} · {"action":"choose","slot":<id>|null}
 *                            · {"action":"delete"} (die letzten beiden nur der Ersteller)
 *
 * POST nur angemeldet, JSON, gleiche Herkunft. Discord-IDs gehen nie an den Browser.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Antworten einer Terminabstimmung. */
object PlanAnswer {
    const val NO = 0
    const val MAYBE = 1
    const val YES = 2
}

class PlanException(message: String) : Exception(message)

data class PlanSlot(val id: Int, val startsAt: Instant)

data class Plan(
    val id: String,
    val ownerId: Long,
    val title: String,
    val note: String,
    val createdAt: Instant,
    val chosenSlot: Int?,
    val slots: List<PlanSlot>,
) {
    /** Letzter Termin – danach gilt die Planung als vorbei. */
    val lastSlot: Instant get() = slots.maxOf { it.startsAt }
}

/** Gespeicherte Planungen (Website-Datenbank). */
class LobbyPlans(private val db: Database, private val clock: () -> Instant = Instant::now) {
    private val random = SecureRandom()

    init {
        db.update(
            "CREATE TABLE IF NOT EXISTS web_plans (id TEXT PRIMARY KEY, owner_id INTEGER NOT NULL, title TEXT NOT NULL, " +
                "note TEXT NOT NULL, created_at INTEGER NOT NULL, chosen_slot INTEGER, deleted INTEGER NOT NULL DEFAULT 0)",
        )
        db.update(
            "CREATE TABLE IF NOT EXISTS web_plan_slots (plan_id TEXT NOT NULL, slot_id INTEGER NOT NULL, starts_at INTEGER NOT NULL, " +
                "PRIMARY KEY (plan_id, slot_id))",
        )
        db.update(
            "CREATE TABLE IF NOT EXISTS web_plan_votes (plan_id TEXT NOT NULL, slot_id INTEGER NOT NULL, voter_id INTEGER NOT NULL, " +
                "answer INTEGER NOT NULL, voted_at INTEGER NOT NULL, PRIMARY KEY (plan_id, slot_id, voter_id))",
        )
    }

    /** Neue Planung, prüft alle Eingaben ([PlanException] mit Text für den Nutzer). */
    fun create(ownerId: Long, title: String, note: String, slots: List<Instant>): Plan {
        val now = clock()
        val cleanTitle = title.trim().replace(Regex("""\s+"""), " ")
        val cleanNote = note.trim()
        if (cleanTitle.length < 3) throw PlanException("Gib der Planung einen Titel (mindestens 3 Zeichen).")
        if (cleanTitle.length > MAX_TITLE) throw PlanException("Der Titel darf höchstens $MAX_TITLE Zeichen haben.")
        if (cleanNote.length > MAX_NOTE) throw PlanException("Die Notiz darf höchstens $MAX_NOTE Zeichen haben.")
        if (cleanNote.lines().size > 6) throw PlanException("Die Notiz darf höchstens 6 Zeilen haben.")
        if ((cleanTitle + cleanNote).any { it.isISOControl() && it != '\n' }) throw PlanException("Bitte keine Steuerzeichen.")
        val times = slots.map { it.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) }.distinct().sorted()
        if (times.isEmpty()) throw PlanException("Schlag mindestens einen Termin vor.")
        if (times.size > MAX_SLOTS) throw PlanException("Höchstens $MAX_SLOTS Termine.")
        if (times.first().isBefore(now.minus(Duration.ofMinutes(5)))) throw PlanException("Termine müssen in der Zukunft liegen.")
        if (times.last().isAfter(now.plus(MAX_AHEAD))) throw PlanException("Termine höchstens ${MAX_AHEAD.toDays()} Tage im Voraus.")
        if (active().count { it.ownerId == ownerId } >= MAX_OPEN_PER_USER) {
            throw PlanException("Du hast schon $MAX_OPEN_PER_USER laufende Planungen – lösch erst eine alte.")
        }
        val id = (1..8).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
        db.transaction {
            db.update(
                "INSERT INTO web_plans (id, owner_id, title, note, created_at, chosen_slot, deleted) VALUES (?, ?, ?, ?, ?, NULL, 0)",
                id, ownerId, cleanTitle, cleanNote, now,
            )
            times.forEachIndexed { i, t -> db.update("INSERT INTO web_plan_slots (plan_id, slot_id, starts_at) VALUES (?, ?, ?)", id, i + 1, t) }
        }
        return get(id)!!
    }

    fun get(id: String): Plan? {
        val row = db.queryOne("SELECT * FROM web_plans WHERE id = ? AND deleted = 0", id) {
            Plan(it.string("id"), it.long("owner_id"), it.string("title"), it.string("note"), it.instant("created_at"), it.intOrNull("chosen_slot"), emptyList())
        } ?: return null
        val slots = db.query("SELECT slot_id, starts_at FROM web_plan_slots WHERE plan_id = ? ORDER BY starts_at", id) {
            PlanSlot(it.int("slot_id"), it.instant("starts_at"))
        }
        return if (slots.isEmpty()) null else row.copy(slots = slots)
    }

    /** Planungen, deren letzter Termin höchstens [KEEP_AFTER] vorbei ist – nächster Termin zuerst. */
    fun active(): List<Plan> {
        val since = clock().minus(KEEP_AFTER)
        val ids = db.query(
            "SELECT p.id FROM web_plans p JOIN web_plan_slots s ON s.plan_id = p.id WHERE p.deleted = 0 " +
                "GROUP BY p.id HAVING MAX(s.starts_at) >= ?",
            since,
        ) { it.string("id") }
        return ids.mapNotNull { get(it) }.sortedBy { plan -> plan.slots.firstOrNull { !it.startsAt.isBefore(since) }?.startsAt ?: plan.lastSlot }
    }

    /** Eigene Antworten setzen: slotId → Antwort (null = Antwort löschen). */
    fun vote(plan: Plan, voterId: Long, answers: Map<Int, Int?>) {
        val valid = plan.slots.map { it.id }.toSet()
        if (answers.keys.any { it !in valid }) throw PlanException("Diesen Termin gibt es nicht.")
        if (answers.values.any { it != null && it !in PlanAnswer.NO..PlanAnswer.YES }) throw PlanException("Ungültige Antwort.")
        val now = clock()
        db.transaction {
            for ((slot, answer) in answers) {
                db.update("DELETE FROM web_plan_votes WHERE plan_id = ? AND slot_id = ? AND voter_id = ?", plan.id, slot, voterId)
                if (answer != null) {
                    db.update(
                        "INSERT INTO web_plan_votes (plan_id, slot_id, voter_id, answer, voted_at) VALUES (?, ?, ?, ?, ?)",
                        plan.id, slot, voterId, answer, now,
                    )
                }
            }
        }
    }

    /** voterId → (slotId → Antwort), in der Reihenfolge der ersten Stimme. */
    fun votes(planId: String): Map<Long, Map<Int, Int>> {
        val result = LinkedHashMap<Long, HashMap<Int, Int>>()
        db.query("SELECT voter_id, slot_id, answer FROM web_plan_votes WHERE plan_id = ? ORDER BY voted_at, voter_id", planId) {
            Triple(it.long("voter_id"), it.int("slot_id"), it.int("answer"))
        }.forEach { (voter, slot, answer) -> result.getOrPut(voter) { HashMap() }[slot] = answer }
        return result
    }

    fun choose(plan: Plan, slot: Int?) {
        if (slot != null && plan.slots.none { it.id == slot }) throw PlanException("Diesen Termin gibt es nicht.")
        db.update("UPDATE web_plans SET chosen_slot = ? WHERE id = ?", slot, plan.id)
    }

    fun delete(plan: Plan) {
        db.update("UPDATE web_plans SET deleted = 1 WHERE id = ?", plan.id)
    }

    companion object {
        const val MAX_TITLE = 80
        const val MAX_NOTE = 300
        const val MAX_SLOTS = 12
        const val MAX_OPEN_PER_USER = 5
        val MAX_AHEAD: Duration = Duration.ofDays(90)
        val KEEP_AFTER: Duration = Duration.ofDays(2)
        private const val ID_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}

class LobbyPlanner(
    private val plans: LobbyPlans,
    private val auth: WebAuth?,
    private val accounts: WebAccounts?,
    private val store: StatsStore,
) {
    private val calls = ConcurrentHashMap<Long, ArrayDeque<Instant>>()

    fun handles(path: String): Boolean =
        path == PATH || (path.startsWith("$PATH/") && path.removePrefix("$PATH/").let { it.isNotEmpty() && '/' !in it && it.length <= 16 })

    fun handle(request: WebRequest): WebResponse {
        val userId = auth?.userOf(request)
        if (request.path == PATH) {
            return when (request.method) {
                "GET", "HEAD" -> json(200, list(userId))
                "POST" -> post(request, null)
                else -> notAllowed()
            }
        }
        val id = request.path.removePrefix("$PATH/")
        return when (request.method) {
            "GET", "HEAD" -> plans.get(id)?.let { json(200, detail(it, userId)) } ?: json(404, obj("error" to "Diese Planung gibt es nicht (mehr)."))
            "POST" -> post(request, id)
            else -> notAllowed()
        }
    }

    // ---------- Anzeige ----------

    private fun list(userId: Long?): JsonObject = obj(
        "loginEnabled" to (auth != null),
        "loggedIn" to (userId != null),
        "plans" to plans.active().map { summary(it, plans.votes(it.id), userId) },
    )

    private fun summary(plan: Plan, votes: Map<Long, Map<Int, Int>>, userId: Long?): JsonObject {
        val counts = plan.slots.associate { slot ->
            slot.id to IntArray(3).also { c -> votes.values.forEach { answers -> answers[slot.id]?.let { c[it]++ } } }
        }
        // Bester Termin: die meisten Ja, dann die meisten Vielleicht, dann der frühere
        val best = plan.slots.maxWithOrNull(
            compareBy<PlanSlot> { counts[it.id]!![PlanAnswer.YES] }.thenBy { counts[it.id]!![PlanAnswer.MAYBE] }.thenByDescending { it.startsAt },
        )?.takeIf { counts[it.id]!![PlanAnswer.YES] + counts[it.id]!![PlanAnswer.MAYBE] > 0 }
        return obj(
            "id" to plan.id,
            "title" to plan.title,
            "note" to plan.note,
            "owner" to nameOf(plan.ownerId),
            "mine" to (userId == plan.ownerId),
            "createdAt" to plan.createdAt,
            "chosen" to plan.chosenSlot,
            "best" to best?.id,
            "voters" to votes.size,
            "answered" to (userId != null && userId in votes),
            "slots" to plan.slots.map { slot ->
                val c = counts[slot.id]!!
                obj("id" to slot.id, "at" to slot.startsAt, "yes" to c[PlanAnswer.YES], "maybe" to c[PlanAnswer.MAYBE], "no" to c[PlanAnswer.NO])
            },
        )
    }

    private fun detail(plan: Plan, userId: Long?): JsonObject {
        val votes = plans.votes(plan.id)
        val snapshot = store.snapshot
        val people = votes.entries.sortedByDescending { it.key == userId }.map { (voter, answers) ->
            val key = WebAuth.playerKeyOf(voter)
            val known = snapshot.hasPlayer(key)
            obj(
                "name" to nameOf(voter),
                "me" to (voter == userId),
                "owner" to (voter == plan.ownerId),
                "key" to if (known) key else null,
                "avatar" to if (known) snapshot.avatarUrlOf(key) else null,
                "colorId" to if (known) snapshot.colorOf(key) else null,
                "answers" to answers.mapKeys { it.key.toString() },
            )
        }
        return JsonObject(
            summary(plan, votes, userId) + mapOf(
                "loginEnabled" to (auth != null).toJson(),
                "loggedIn" to (userId != null).toJson(),
                "people" to people.toJson(),
                "myAnswers" to (userId?.let { votes[it] }?.mapKeys { it.key.toString() } ?: emptyMap<String, Int>()).toJson(),
            ),
        )
    }

    private fun nameOf(discordId: Long): String = accounts?.discordUser(discordId)?.shownName ?: "Discord-Konto"

    // ---------- Ändern ----------

    private fun post(request: WebRequest, id: String?): WebResponse {
        val handler = auth ?: return json(503, obj("error" to "Auf dieser Website ist die Anmeldung aus."))
        handler.sameOrigin(request)?.let { return it }
        if (request.header("content-type")?.startsWith("application/json") != true) return json(415, obj("error" to "Nur JSON"))
        if (request.body.size > 4096) return json(413, obj("error" to "Zu groß"))
        val userId = handler.userOf(request) ?: return json(401, obj("error" to "Melde dich mit Discord an, um mitzumachen.", "login" to true))
        val payload = runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return json(400, obj("error" to "Ungültige Eingabe"))
        if (!allow(userId)) return json(429, obj("error" to "Zu viele Klicks – warte kurz."))
        val action = (payload["action"] as? JsonPrimitive)?.contentOrNull
        return try {
            if (id == null) {
                if (action != "create") return json(400, obj("error" to "Unbekannte Aktion"))
                val slots = (payload["slots"] as? JsonArray)?.map { element ->
                    val text = (element as? JsonPrimitive)?.contentOrNull ?: throw PlanException("Ungültiger Termin.")
                    runCatching { Instant.parse(text) }.getOrElse { throw PlanException("Ungültiger Termin.") }
                } ?: throw PlanException("Schlag mindestens einen Termin vor.")
                if (slots.size > LobbyPlans.MAX_SLOTS * 2) throw PlanException("Höchstens ${LobbyPlans.MAX_SLOTS} Termine.")
                val plan = plans.create(userId, text(payload, "title"), text(payload, "note"), slots)
                json(200, detail(plan, userId))
            } else {
                val plan = plans.get(id) ?: return json(404, obj("error" to "Diese Planung gibt es nicht (mehr)."))
                when (action) {
                    "vote" -> {
                        val raw = payload["answers"] as? JsonObject ?: throw PlanException("Ungültige Eingabe")
                        val answers = raw.entries.associate { (slot, value) ->
                            val slotId = slot.toIntOrNull() ?: throw PlanException("Diesen Termin gibt es nicht.")
                            slotId to if (value is JsonNull) null else ((value as? JsonPrimitive)?.intOrNull ?: throw PlanException("Ungültige Antwort."))
                        }
                        plans.vote(plan, userId, answers)
                        json(200, detail(plan, userId))
                    }
                    "choose" -> {
                        if (plan.ownerId != userId) return json(403, obj("error" to "Nur wer die Planung angelegt hat, legt den Termin fest."))
                        val slot = payload["slot"].let { if (it == null || it is JsonNull) null else (it as? JsonPrimitive)?.intOrNull ?: throw PlanException("Ungültiger Termin.") }
                        plans.choose(plan, slot)
                        json(200, detail(plans.get(id)!!, userId))
                    }
                    "delete" -> {
                        if (plan.ownerId != userId) return json(403, obj("error" to "Nur wer die Planung angelegt hat, kann sie löschen."))
                        plans.delete(plan)
                        json(200, obj("ok" to true))
                    }
                    else -> json(400, obj("error" to "Unbekannte Aktion"))
                }
            }
        } catch (e: PlanException) {
            json(400, obj("error" to e.message))
        }
    }

    private fun text(payload: JsonObject, name: String): String = (payload[name] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun allow(userId: Long): Boolean {
        val now = Instant.now()
        val window = calls.getOrPut(userId) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first().plus(Duration.ofMinutes(1)).isBefore(now)) window.removeFirst()
            if (window.size >= RATE) return false
            window.addLast(now)
            return true
        }
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun notAllowed() = WebResponse(405, body = bytes(obj("error" to "Nur GET, POST")), headers = listOf("Allow" to "GET, POST", NO_STORE))

    companion object {
        const val PATH = "/api/planer"
        private const val RATE = 40
        private val NO_STORE = "Cache-Control" to "no-store"
    }
}
