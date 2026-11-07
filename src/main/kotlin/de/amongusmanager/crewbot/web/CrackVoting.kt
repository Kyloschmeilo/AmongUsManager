/*
 * Stats-Website: Crack-Voting – nach jeder Runde dürfen angemeldete Nutzer 15 Minuten lang abstimmen, wer in dieser
 * Runde „am meisten Crack geraucht“ hat (Kilian, 07.11.2026). Eine Stimme je Discord-Konto und Runde, ändern und
 * zurückziehen geht, solange das Voting offen ist; nicht für sich selbst. Die Stimmen bleiben in der Website-Datenbank
 * (`data/website.db`, Tabelle `web_crack_votes`), das Ergebnis (Sieger, Stimmen) wird daraus berechnet und fließt nach
 * dem Ende des Votings in die Bestenliste und ins Profil.
 *
 *   GET  /api/crack            offene Votings (neueste Runde zuerst) + meine Stimme
 *   GET  /api/crack/<gameId>   Stand eines Votings; Stimmen je Spieler erst nach dem Ende (vorher nur die Gesamtzahl)
 *   POST /api/crack/<gameId>   {"playerId": 3} abstimmen bzw. {"playerId": null} zurückziehen – nur angemeldet, JSON,
 *                              gleiche Herkunft
 *
 * Wer für wen gestimmt hat, geht nie an den Browser (nur die eigene Stimme). Gespeichert wird die playerId der Runde,
 * nicht der Spieler-Schlüssel – verknüpft sich jemand später mit Discord, zählen seine Crack-Siege trotzdem.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Wann das Voting einer Runde läuft. */
object CrackPoll {
    /** So lange läuft das Voting nach dem Eintreffen der Runde. */
    val WINDOW: Duration = Duration.ofMinutes(15)

    /** Kommt eine Runde so viel später als ihr Ende an (Offline-Puffer der Mod), gibt es kein Voting mehr. */
    val MAX_DELAY: Duration = Duration.ofMinutes(30)

    /** Beginn und Ende des Votings, null = diese Runde hat keins (alte/nachgereichte Runde). */
    fun window(round: WebRound): Pair<Instant, Instant>? {
        if (round.fromHistory) return null
        if (Duration.between(round.endedAt, round.receivedAt) > MAX_DELAY) return null
        return round.receivedAt to round.receivedAt.plus(WINDOW)
    }

    fun isOpen(round: WebRound, now: Instant): Boolean = window(round)?.let { (from, to) -> !now.isBefore(from) && now.isBefore(to) } == true

    fun isClosed(round: WebRound, now: Instant): Boolean = window(round)?.let { (_, to) -> !now.isBefore(to) } == true

    /** Sieger eines Votings: alle mit den meisten Stimmen (Gleichstand = mehrere), leer ohne Stimmen. */
    fun winners(tally: Map<Int, Int>): Set<Int> {
        val top = tally.values.maxOrNull()?.takeIf { it > 0 } ?: return emptySet()
        return tally.filterValues { it == top }.keys
    }
}

/** Gespeicherte Stimmen (Website-Datenbank). */
class CrackVotes(private val db: Database, private val clock: () -> Instant = Instant::now) {
    init {
        db.update(
            "CREATE TABLE IF NOT EXISTS web_crack_votes (game_id TEXT NOT NULL, voter_id INTEGER NOT NULL, " +
                "target_id INTEGER NOT NULL, voted_at INTEGER NOT NULL, PRIMARY KEY (game_id, voter_id))",
        )
    }

    fun vote(gameId: String, voterId: Long, targetId: Int) {
        db.transaction {
            db.update("DELETE FROM web_crack_votes WHERE game_id = ? AND voter_id = ?", gameId, voterId)
            db.update(
                "INSERT INTO web_crack_votes (game_id, voter_id, target_id, voted_at) VALUES (?, ?, ?, ?)",
                gameId, voterId, targetId, clock(),
            )
        }
    }

    fun withdraw(gameId: String, voterId: Long) {
        db.update("DELETE FROM web_crack_votes WHERE game_id = ? AND voter_id = ?", gameId, voterId)
    }

    /** Eigene Stimme (playerId), null = keine. */
    fun voteOf(gameId: String, voterId: Long): Int? =
        db.queryOne("SELECT target_id FROM web_crack_votes WHERE game_id = ? AND voter_id = ?", gameId, voterId) { it.int("target_id") }

    /** Stimmen je Spieler einer Runde. */
    fun tally(gameId: String): Map<Int, Int> =
        db.query("SELECT target_id, COUNT(*) AS n FROM web_crack_votes WHERE game_id = ? GROUP BY target_id", gameId) {
            it.int("target_id") to it.int("n")
        }.toMap()

    /** Alle Runden: gameId → (playerId → Stimmen). */
    fun tallies(): Map<String, Map<Int, Int>> {
        val result = HashMap<String, HashMap<Int, Int>>()
        db.query("SELECT game_id, target_id, COUNT(*) AS n FROM web_crack_votes GROUP BY game_id, target_id") {
            Triple(it.string("game_id"), it.int("target_id"), it.int("n"))
        }.forEach { (game, target, n) -> result.getOrPut(game) { HashMap() }[target] = n }
        return result
    }
}

class CrackVoting(
    private val votes: CrackVotes,
    private val auth: WebAuth?,
    private val store: StatsStore,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(CrackVoting::class.java)
    private val calls = ConcurrentHashMap<Long, ArrayDeque<Instant>>()

    fun handles(path: String): Boolean = path == PATH || (path.startsWith("$PATH/") && path.removePrefix("$PATH/").let { it.isNotEmpty() && '/' !in it })

    fun handle(request: WebRequest): WebResponse {
        if (request.path == PATH) return if (request.method == "GET" || request.method == "HEAD") json(200, openPolls(request)) else notAllowed("GET")
        val gameId = request.path.removePrefix("$PATH/")
        return when (request.method) {
            "GET", "HEAD" -> poll(gameId, auth?.userOf(request))?.let { json(200, it) } ?: json(404, obj("error" to "Diese Runde gibt es nicht."))
            "POST" -> vote(request, gameId)
            else -> notAllowed("GET, POST")
        }
    }

    // ---------- Anzeige ----------

    private fun openPolls(request: WebRequest): JsonObject {
        val now = clock()
        val userId = auth?.userOf(request)
        val snapshot = store.snapshot
        val open = snapshot.views.asSequence().take(50).filter { CrackPoll.isOpen(it.round, now) }.toList()
        return obj(
            "now" to now,
            "loggedIn" to (userId != null),
            "open" to open.map { view ->
                val r = view.round
                obj(
                    "gameId" to r.gameId,
                    "map" to r.map,
                    "mapId" to r.mapId,
                    "endedAt" to r.endedAt,
                    "closesAt" to CrackPoll.window(r)?.second,
                    "total" to votes.tally(r.gameId).values.sum(),
                    "voted" to (userId != null && votes.voteOf(r.gameId, userId) != null),
                    "roster" to r.players.mapIndexed { i, p -> obj("key" to view.keys[i], "name" to p.name, "colorId" to (p.colorId ?: snapshot.colorOf(view.keys[i]))) },
                )
            },
        )
    }

    /** Stand eines Votings, null = Runde unbekannt. */
    internal fun poll(gameId: String, userId: Long?): JsonObject? {
        val snapshot = store.snapshot
        val view = snapshot.view(gameId) ?: return null
        val r = view.round
        val now = clock()
        val window = CrackPoll.window(r)
        val state = when {
            window == null -> "none"
            CrackPoll.isOpen(r, now) -> "open"
            now.isBefore(window.first) -> "none"
            else -> "closed"
        }
        val tally = if (window != null) votes.tally(gameId) else emptyMap()
        val winners = if (state == "closed") CrackPoll.winners(tally) else emptySet()
        val ownKey = userId?.let { WebAuth.playerKeyOf(it) }
        return obj(
            "gameId" to gameId,
            "state" to state,
            "now" to now,
            "opensAt" to window?.first,
            "closesAt" to window?.second,
            "total" to tally.values.sum(),
            "loggedIn" to (userId != null),
            "loginEnabled" to (auth != null),
            "myVote" to if (userId != null && window != null) votes.voteOf(gameId, userId) else null,
            "players" to r.players.mapIndexed { i, p ->
                val key = view.keys[i]
                obj(
                    "playerId" to p.playerId,
                    "key" to key,
                    "name" to p.name,
                    "colorId" to (p.colorId ?: snapshot.colorOf(key)),
                    "avatar" to snapshot.avatarUrlOf(key),
                    "me" to (key == ownKey),
                    // Stimmen erst nach dem Ende – sonst laufen alle dem Ersten hinterher
                    "votes" to if (state == "closed") (tally[p.playerId] ?: 0) else null,
                    "winner" to (p.playerId in winners),
                )
            },
        )
    }

    // ---------- Abstimmen ----------

    private fun vote(request: WebRequest, gameId: String): WebResponse {
        val handler = auth ?: return json(503, obj("error" to "Auf dieser Website ist die Anmeldung aus."))
        handler.sameOrigin(request)?.let { return it }
        if (request.header("content-type")?.startsWith("application/json") != true) return json(415, obj("error" to "Nur JSON"))
        if (request.body.size > 512) return json(413, obj("error" to "Zu groß"))
        val userId = handler.userOf(request) ?: return json(401, obj("error" to "Melde dich mit Discord an, um abzustimmen.", "login" to true))
        val payload = runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return json(400, obj("error" to "Ungültige Eingabe"))
        if (!allow(userId)) return json(429, obj("error" to "Zu viele Klicks – warte kurz."))
        val view = store.snapshot.view(gameId) ?: return json(404, obj("error" to "Diese Runde gibt es nicht."))
        if (!CrackPoll.isOpen(view.round, clock())) {
            return json(409, obj("error" to if (CrackPoll.window(view.round) == null) "Für diese Runde gibt es kein Crack-Voting." else "Das Voting ist schon vorbei."))
        }
        val target = payload["playerId"]
        if (target == null || target is JsonNull) {
            votes.withdraw(gameId, userId)
        } else {
            val playerId = (target as? JsonPrimitive)?.intOrNull ?: return json(400, obj("error" to "Ungültige Eingabe"))
            val index = view.round.players.indexOfFirst { it.playerId == playerId }
            if (index < 0) return json(400, obj("error" to "Dieser Spieler war nicht in der Runde."))
            if (view.keys[index] == WebAuth.playerKeyOf(userId)) return json(409, obj("error" to "Für dich selbst kannst du nicht stimmen."))
            votes.vote(gameId, userId, playerId)
            log.info("Crack-Voting: Stimme für Runde {}", gameId)
        }
        return json(200, poll(gameId, userId)!!)
    }

    private fun allow(userId: Long): Boolean {
        val now = clock()
        val window = calls.getOrPut(userId) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first().plus(Duration.ofMinutes(1)).isBefore(now)) window.removeFirst()
            if (window.size >= RATE) return false
            window.addLast(now)
            return true
        }
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun notAllowed(allowed: String) = WebResponse(405, body = bytes(obj("error" to "Nur $allowed")), headers = listOf("Allow" to allowed, NO_STORE))

    companion object {
        const val PATH = "/api/crack"
        private const val RATE = 20
        private val NO_STORE = "Cache-Control" to "no-store"
    }
}
