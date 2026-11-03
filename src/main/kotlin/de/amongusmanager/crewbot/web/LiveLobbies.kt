/*
 * Stats-Website: Live-Anzeige der Lobbys – wer gerade in welcher Lobby ist, mit „Beitreten“-Knopf.
 *
 *   GET  /api/lobbies        offene/laufende Lobbys (Host-Meldungen von CrewStats, services/ModApi.kt → LobbyStore.live)
 *   POST /api/lobbies/join   {"code":"ABCDEF"} – nur angemeldet + verknüpft, nur von der eigenen Seite
 *
 * Beitreten: Die Website kann das Spiel nicht selbst starten. Sie merkt sich die Wahl für die Friendcodes der Person
 * ([JoinPicks]); die Mod fragt im Hauptmenü ohnehin alle paar Sekunden über /api/mod/<Schlüssel> nach (Auto-Join) und
 * bekommt dann genau diese Lobby mit einer pick-id → sie tritt bei, auch wenn automatisches Beitreten aus ist.
 *
 * Datenschutz wie überall auf der Website: keine Friendcodes oder Discord-IDs an den Browser, nur Spieler-Schlüssel von
 * Spielern, die es auf der Website gibt. Den Lobby-Code sehen nur angemeldete, verknüpfte Spieler (die Seite ist
 * öffentlich, im Discord steht der Code ohnehin im Codes-Channel).
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.LobbyStore
import de.amongusmanager.crewbot.services.normalizeFriendCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** „Beitreten“ auf der Website, bis die Mod der Person danach fragt (nur im Speicher, verfällt nach [TTL]). */
class JoinPicks(private val clock: () -> Instant = Instant::now) {
    data class Pick(val id: String, val code: String, val userId: Long, val friendCodes: Set<String>, val at: Instant, val deliveredAt: Instant?)

    private val byUser = ConcurrentHashMap<Long, Pick>()
    private val random = SecureRandom()

    fun set(userId: Long, friendCodes: Collection<String>, code: String): Pick {
        cleanup()
        val id = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))
        val pick = Pick(id, code.uppercase(), userId, friendCodes.map(::normalizeFriendCode).toSet(), clock(), null)
        byUser[userId] = pick
        return pick
    }

    fun forUser(userId: Long): Pick? = byUser[userId]?.takeIf { fresh(it) }

    fun cancel(userId: Long) {
        byUser.remove(userId)
    }

    /** Die Mod mit diesem Friendcode fragt: offene Wahl abholen (jede nur einmal). */
    fun take(friendCode: String): Pick? {
        val fc = normalizeFriendCode(friendCode)
        if (fc.isEmpty()) return null
        for ((user, pick) in byUser) {
            if (fc !in pick.friendCodes || pick.deliveredAt != null || !fresh(pick)) continue
            val delivered = pick.copy(deliveredAt = clock())
            if (byUser.replace(user, pick, delivered)) return delivered
        }
        return null
    }

    private fun fresh(pick: Pick): Boolean = pick.at.plus(TTL).isAfter(clock())

    private fun cleanup() {
        byUser.entries.removeIf { !fresh(it.value) }
    }

    companion object {
        /** So lange wartet die Wahl auf die Mod (Spiel muss im Hauptmenü sein). */
        val TTL: Duration = Duration.ofMinutes(3)
    }
}

class LiveLobbies(
    db: Database,
    private val auth: WebAuth?,
    private val store: StatsStore,
    private val picks: JoinPicks,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(LiveLobbies::class.java)
    private val lobbies = LobbyStore(db)
    private val links = LinkService(db)
    private val calls = ConcurrentHashMap<Long, ArrayDeque<Instant>>()
    private val codesCache = ConcurrentHashMap<Long, Pair<List<String>, Instant>>()

    @Volatile
    private var cached: Pair<List<Pair<LobbyEvent, Instant>>, Instant>? = null

    @Volatile
    private var linkCache: Pair<Map<String, Long>, Instant>? = null

    fun handles(path: String): Boolean = path == PATH || path == "$PATH/join"

    fun handle(request: WebRequest): WebResponse = when (request.path) {
        PATH -> if (request.method == "GET" || request.method == "HEAD") list(request) else notAllowed("GET")
        else -> if (request.method == "POST") join(request) else notAllowed("POST")
    }

    // ---------- Anzeige ----------

    private fun list(request: WebRequest): WebResponse {
        val now = clock()
        val userId = auth?.userOf(request)
        val myCodes = userId?.let { codesOf(it) } ?: emptyList()
        val linked = myCodes.isNotEmpty()
        val live = live(now)
        val mine = myCodes.toSet()
        val inLobby = live.firstOrNull { (event, _) -> event.list.any { it.friendCode in mine } || event.sender.lowercase() in mine }?.first?.code
        val pick = userId?.let { picks.forUser(it) }
        val body = obj(
            "lobbies" to live.map { (event, at) -> lobbyJson(event, at, now, showCode = linked, mine = mine) },
            "me" to obj(
                "loggedIn" to (userId != null),
                "linked" to linked,
                "inLobby" to if (linked) inLobby else null,
                "join" to pick?.let {
                    obj(
                        "code" to it.code,
                        "sent" to (it.deliveredAt != null),
                        "age" to Duration.between(it.at, now).seconds.coerceAtLeast(0),
                    )
                },
            ),
            "updatedAt" to now.toString(),
        )
        return json(200, body)
    }

    private fun lobbyJson(event: LobbyEvent, at: Instant, now: Instant, showCode: Boolean, mine: Set<String>): JsonObject {
        val snapshot = store.snapshot
        val linkMap = linkMap()
        val host = event.sender.lowercase()
        val players = event.list.map { p ->
            val key = keyOf(p.friendCode, linkMap)?.takeIf { snapshot.hasPlayer(it) }
            val summary = key?.let { snapshot.summaryOf(it) }
            obj(
                "name" to p.name,
                "key" to key,
                "profile" to (key != null),
                "colorId" to (p.colorId.takeIf { it >= 0 } ?: key?.let { snapshot.colorOf(it) }),
                "avatar" to key?.let { k -> snapshot.avatarVersionOf(k)?.let { "/api/avatar/$k?v=$it" } },
                "level" to summary?.get("level")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() },
                "games" to summary?.get("games")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() },
                "wins" to summary?.get("wins")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() },
                "host" to (p.friendCode.isNotEmpty() && p.friendCode == host),
                "me" to (p.friendCode.isNotEmpty() && p.friendCode in mine),
            )
        }
        val full = event.maxPlayers > 0 && event.players >= event.maxPlayers
        return obj(
            "id" to shortHash("lobby:" + event.code + ":" + host),
            "code" to if (showCode) event.code else null,
            "state" to event.state,
            "map" to event.map,
            "players" to event.players,
            "max" to event.maxPlayers,
            "host" to event.host,
            "region" to event.region.ifEmpty { null },
            "age" to Duration.between(at, now).seconds.coerceAtLeast(0),
            "full" to full,
            "joinable" to (event.state == "open" && !full),
            "list" to players,
        )
    }

    // ---------- Beitreten ----------

    private fun join(request: WebRequest): WebResponse {
        val handler = auth ?: return json(503, obj("error" to "Auf dieser Website ist die Anmeldung aus."))
        handler.sameOrigin(request)?.let { return it }
        if (request.header("content-type")?.startsWith("application/json") != true) return json(415, obj("error" to "Nur JSON"))
        if (request.body.size > 1024) return json(413, obj("error" to "Zu groß"))
        val userId = handler.userOf(request) ?: return json(401, obj("error" to "Bitte zuerst mit Discord anmelden.", "login" to true))
        val payload = runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return json(400, obj("error" to "Ungültige Eingabe"))
        if (!allow(userId)) return json(429, obj("error" to "Zu viele Klicks – warte kurz."))
        val code = (payload["code"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase().orEmpty()
        if ((payload["cancel"] as? JsonPrimitive)?.contentOrNull == "true") {
            picks.cancel(userId)
            return json(200, obj("ok" to true, "message" to "Abgebrochen."))
        }
        val myCodes = codesOf(userId, fresh = true)
        if (myCodes.isEmpty()) {
            return json(409, obj("error" to "Verknüpfe zuerst deinen Among-Us-Account: /verknuepfen im Discord oder per Code aus dem F7-Menü von CrewStats."))
        }
        val now = clock()
        val lobby = live(now, fresh = true).firstOrNull { it.first.code.equals(code, ignoreCase = true) }?.first
            ?: return json(404, obj("error" to "Diese Lobby gibt es nicht mehr."))
        if (lobby.state == "started") return json(409, obj("error" to "In dieser Lobby läuft gerade eine Runde – warte, bis sie vorbei ist."))
        if (lobby.maxPlayers > 0 && lobby.players >= lobby.maxPlayers) return json(409, obj("error" to "Die Lobby ist voll."))
        if (lobby.list.any { it.friendCode in myCodes }) return json(409, obj("error" to "Du bist schon in dieser Lobby."))
        picks.set(userId, myCodes, lobby.code)
        log.info("Website: Beitreten zu Lobby {} gewünscht", lobby.code)
        return json(200, obj(
            "ok" to true,
            "code" to lobby.code,
            "message" to "Ist unterwegs zu deinem Spiel – sobald Among Us im Hauptmenü ist, trittst du bei.",
        ))
    }

    // ---------- Helfer ----------

    private fun live(now: Instant, fresh: Boolean = false): List<Pair<LobbyEvent, Instant>> {
        if (!fresh) cached?.let { (list, at) -> if (at.plus(LIST_CACHE).isAfter(now)) return list }
        val list = try {
            lobbies.live(now)
        } catch (e: Exception) {
            log.warn("Lobbys nicht lesbar: {}", e.message)
            cached?.first ?: emptyList()
        }
        cached = list to now
        return list
    }

    private fun codesOf(userId: Long, fresh: Boolean = false): List<String> {
        val now = clock()
        if (!fresh) codesCache[userId]?.let { (codes, at) -> if (at.plus(CODES_CACHE).isAfter(now)) return codes }
        val codes = runCatching { links.codesOf(userId).map(::normalizeFriendCode) }.getOrElse { codesCache[userId]?.first ?: emptyList() }
        if (codesCache.size > 2000) codesCache.clear()
        codesCache[userId] = codes to now
        return codes
    }

    /** Friendcode → Discord-ID (wie StatsStore), höchstens alle 30 s neu gelesen. */
    private fun linkMap(): Map<String, Long> {
        val now = clock()
        linkCache?.let { (map, at) -> if (at.plus(CODES_CACHE).isAfter(now)) return map }
        val map = runCatching {
            links.byUser().flatMap { (user, codes) -> codes.map { normalizeFriendCode(it) to user } }.toMap()
        }.getOrElse { linkCache?.first ?: emptyMap() }
        linkCache = map to now
        return map
    }

    private fun keyOf(friendCode: String, linkMap: Map<String, Long>): String? {
        if (friendCode.isEmpty()) return null
        val discord = linkMap[friendCode]
        return if (discord != null) WebAuth.playerKeyOf(discord) else shortHash("crewstats-web:f:$friendCode")
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
        const val PATH = "/api/lobbies"
        private const val RATE = 12
        private val LIST_CACHE: Duration = Duration.ofSeconds(2)
        private val CODES_CACHE: Duration = Duration.ofSeconds(30)
        private val NO_STORE = "Cache-Control" to "no-store"
    }
}
