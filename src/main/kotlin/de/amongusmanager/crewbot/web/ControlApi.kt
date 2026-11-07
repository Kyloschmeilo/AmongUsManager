/*
 * Stats-Website: Bot-Funktionen steuern (Event-Tab, Event erstellen, Admin-Panel).
 *
 *   POST /api/bot/<aktion>   (JSON, nur angemeldet, nur von der eigenen Seite)
 *
 * Die Website führt selbst nichts aus: Sie legt einen Auftrag mit der Discord-ID der angemeldeten Person in die
 * Datenbank des Bots (services/WebQueue.kt) und wartet auf die Antwort. Der Bot prüft die Rechte über die Discord-Rollen
 * (modules/WebControlModule.kt) – selbst eine manipulierte Website könnte also nur das, was die Person im Discord darf.
 * Fremde Seiten können wegen Origin-Prüfung, JSON-Pflicht und SameSite-Cookie nichts im Namen anderer auslösen.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.services.WebQueue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Antwort des Bots: HTTP-Status + JSON. */
data class BotReply(val status: Int, val body: JsonObject)

/** Website-Seite der Warteschlange: Auftrag abgeben und auf die Antwort warten. */
class BotLink(
    private val queue: WebQueue,
    private val wait: Duration = Duration.ofSeconds(30),
    private val pollMillis: Long = 100,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(BotLink::class.java)

    fun call(userId: Long, action: String, payload: JsonObject): BotReply {
        val alive = try {
            queue.botAlive(clock())
        } catch (e: Exception) {
            log.warn("Auftrags-Tabelle nicht lesbar: {}", e.message)
            return BotReply(503, obj("error" to "Der Bot ist nicht erreichbar (läuft schon die neue Bot-Version mit Website-Steuerung?).", "offline" to true))
        }
        if (!alive) return BotReply(503, obj("error" to "Der Bot ist gerade offline. Versuch es gleich nochmal.", "offline" to true))
        val id = queue.submit(userId, action, payload.toString(), clock())
        val deadline = System.nanoTime() + wait.toNanos()
        var cancelled = false
        while (true) {
            val result = queue.take(id)
            if (result != null) {
                val body = runCatching { parseJson(result) as? JsonObject }.getOrNull() ?: obj("error" to "Ungültige Antwort vom Bot")
                return BotReply(if (body.containsKey("error")) 400 else 200, body)
            }
            if (System.nanoTime() > deadline) {
                // Noch nicht angefangen → abbrechen; läuft schon → noch etwas länger warten
                if (!cancelled && queue.cancel(id)) return BotReply(504, obj("error" to "Der Bot antwortet gerade nicht. Versuch es gleich nochmal."))
                if (cancelled) return BotReply(504, obj("error" to "Der Bot braucht ungewöhnlich lange – schau im Discord nach, ob es geklappt hat."))
                cancelled = true
            }
            Thread.sleep(pollMillis)
        }
    }
}

class ControlApi(
    private val auth: WebAuth,
    private val link: BotLink,
    private val store: StatsStore,
    private val clock: () -> Instant = Instant::now,
) {
    private val calls = ConcurrentHashMap<Long, ArrayDeque<Instant>>()
    private val meCache = ConcurrentHashMap<Long, Pair<BotReply, Instant>>()

    @Volatile
    private var guestCache: Pair<BotReply, Instant>? = null

    fun handles(path: String): Boolean = path.startsWith(PREFIX)

    fun handle(request: WebRequest): WebResponse {
        val action = request.path.removePrefix(PREFIX).trim('/')
        if (action !in ACTIONS) return json(404, obj("error" to "Unbekannte Aktion"))
        if (request.method != "POST") return WebResponse(405, body = bytes(obj("error" to "Nur POST")), headers = listOf("Allow" to "POST", NO_STORE))
        auth.sameOrigin(request)?.let { return it }
        if (request.header("content-type")?.startsWith("application/json") != true) return json(415, obj("error" to "Nur JSON"))
        if (request.body.size > MAX_BODY) return json(413, obj("error" to "Zu groß"))
        val userId = auth.userOf(request)
            ?: if (action in GUEST_ACTIONS) GUEST else return json(401, obj("error" to "Bitte zuerst mit Discord anmelden.", "login" to true))
        val payload = if (request.body.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: return json(400, obj("error" to "Ungültige Eingabe"))
        }
        if (action == "me") {
            meCache[userId]?.let { (reply, at) -> if (at.plus(ME_CACHE).isAfter(clock())) return json(reply.status, reply.body) }
        }
        if (userId == GUEST) {
            guestCache?.let { (reply, at) -> if (at.plus(GUEST_CACHE).isAfter(clock())) return json(reply.status, enrich(reply.body) as JsonObject) }
        }
        if (!allow(userId)) return json(429, obj("error" to "Zu viele Klicks – warte kurz."))
        val reply = link.call(userId, action, payload)
        // Bestenliste sofort neu (sonst erst beim nächsten Abgleich)
        if (action == "admin.leaderboard" && reply.status == 200) store.refreshQuietly()
        if (userId == GUEST && reply.status == 200) guestCache = reply to clock()
        if (action == "me" && reply.status == 200) meCache[userId] = reply to clock()
        // Nach Änderungen sofort frische Rechte/Daten (z.B. Rolle vergeben → Event Maker)
        if (action != "me" && reply.status == 200 && !action.endsWith(".list") && action != "admin.overview" && action != "admin.members") meCache.clear()
        return json(reply.status, enrich(reply.body) as JsonObject)
    }

    /** Höchstens [RATE] Aufträge pro Minute und Person. */
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

    /** Spieler-Schlüssel um Profilbild und Profil-Link der Website ergänzen (falls es den Spieler gibt). */
    private fun enrich(element: JsonElement): JsonElement {
        val snapshot = store.snapshot
        return when (element) {
            is JsonArray -> JsonArray(element.map { enrich(it) })
            is JsonObject -> {
                val map = LinkedHashMap<String, JsonElement>()
                for ((k, v) in element) map[k] = enrich(v)
                val key = (element["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (key != null && element.containsKey("name")) {
                    val known = snapshot.hasPlayer(key)
                    map["profile"] = JsonPrimitive(known)
                    snapshot.avatarVersionOf(key)?.let { map["avatar"] = JsonPrimitive("/api/avatar/$key?v=$it") }
                    snapshot.colorOf(key)?.let { map["colorId"] = JsonPrimitive(it) }
                }
                JsonObject(map)
            }
            else -> element
        }
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    companion object {
        const val PREFIX = "/api/bot/"
        private const val MAX_BODY = 8 * 1024
        private const val RATE = 60
        private val ME_CACHE: Duration = Duration.ofSeconds(20)
        private val GUEST_CACHE: Duration = Duration.ofSeconds(10)

        /** Nicht angemeldet (Discord-ID 0): nur die Event-Liste ansehen. */
        const val GUEST = 0L
        val GUEST_ACTIONS = setOf("events.list")
        private val NO_STORE = "Cache-Control" to "no-store"

        /** Was die Website weiterreicht – der Bot kennt dieselben Namen (WebControlModule.dispatch). */
        val ACTIONS = setOf(
            "me", "events.list", "events.join", "events.leave", "events.create",
            "admin.overview", "admin.members", "admin.player.set", "admin.player.reset", "admin.player.role", "admin.wish",
            "admin.voice", "admin.round", "admin.round.cancel", "admin.mvp", "admin.post", "admin.settings", "admin.modsetup",
            "admin.leaderboard",
        )
    }
}
