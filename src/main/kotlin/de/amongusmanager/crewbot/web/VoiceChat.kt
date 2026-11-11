/*
 * Stats-Website: Proximity-Voice-Chat im Live-Tab – man hört andere Spieler je nach Entfernung im Spiel.
 *
 *   POST /api/mod/<Schlüssel>     {"crewstats":"voice",…} vom Host (CrewStats ≥ 1.10.0, ~5×/s): Phase + Positionen
 *   GET  /api/voice               bin ich in einer Lobby mit Voice? (angemeldet + verknüpft)
 *   POST /api/voice/join          Raum betreten → eigene peer-id + ICE-Server
 *   GET  /api/voice/stream?peer=  Server-Sent Events: hello, peers, mix (wen ich wie laut höre / wem ich sende), signal, bye
 *   POST /api/voice/signal        {"peer","to","data"} – WebRTC-Angebot/Antwort/ICE an einen anderen Teilnehmer
 *   POST /api/voice/leave         {"peer"}
 *
 * Gesprochen wird per WebRTC direkt zwischen den Browsern (Mesh), die Website vermittelt nur. Positionen bleiben auf dem
 * Server: der Browser bekommt nur Lautstärken (0–1) je Teilnehmer. Wer jemanden gar nicht hören darf, bekommt auch keinen
 * Ton – der Sender schickt nur an Teilnehmer, die ihn hören ([VoiceRules]). Friendcodes/Discord-IDs gehen nie an den Browser.
 *
 * Kein WebSocket (der JDK-HttpServer kann das nicht) – Server → Browser per SSE, Browser → Server per POST.
 * Alles nur im Speicher: Neustart der Website = alle verbinden sich neu.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.normalizeFriendCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Wer wen wie laut hört – reine Regeln (wie BetterCrewLink), getestet in VoiceChatTest. */
object VoiceRules {
    /** Bis zu dieser Entfernung (Spiel-Einheiten) volle Lautstärke … */
    const val FULL_DISTANCE = 1.2

    /** … ab hier nichts mehr (etwa die Sichtweite eines Crewmates bei normalen Einstellungen). */
    const val MAX_DISTANCE = 5.3

    enum class Phase { LOBBY, TASKS, MEETING, END }

    data class Player(
        val fc: String,
        val name: String,
        val color: Int,
        val x: Double,
        val y: Double,
        val dead: Boolean = false,
        /** Sitzt im Vent – ist für andere nicht zu hören (sonst verrät die Stimme das Versteck). */
        val vent: Boolean = false,
        /** Im Meeting stumm (The Other Roles: Blackmailer-Opfer, Jailor-Gefangene). */
        val silenced: Boolean = false,
    )

    /**
     * Lautstärke 0–1, mit der [listener] den [speaker] hört.
     * - Lobby / Rundenende: alle hören alle.
     * - Meeting: Lebende hören Lebende (außer Stummgeschaltete), Geister hören alle, Lebende hören keine Geister.
     * - Runde: Lebende nach Entfernung, wer im Vent sitzt ist nicht zu hören; Geister hören Geister überall und
     *   Lebende nach Entfernung; Lebende hören keine Geister.
     */
    fun gain(phase: Phase, listener: Player, speaker: Player): Double = when (phase) {
        Phase.LOBBY, Phase.END -> 1.0
        Phase.MEETING -> when {
            speaker.dead -> if (listener.dead) 1.0 else 0.0
            speaker.silenced -> 0.0
            else -> 1.0
        }
        Phase.TASKS -> when {
            speaker.dead -> if (listener.dead) 1.0 else 0.0
            speaker.vent -> 0.0
            else -> distanceGain(hypot(listener.x - speaker.x, listener.y - speaker.y))
        }
    }

    fun distanceGain(distance: Double): Double {
        if (distance.isNaN()) return 0.0
        if (distance <= FULL_DISTANCE) return 1.0
        if (distance >= MAX_DISTANCE) return 0.0
        val t = (MAX_DISTANCE - distance) / (MAX_DISTANCE - FULL_DISTANCE)
        return t * t // fällt zum Rand hin schneller ab, klingt natürlicher als linear
    }

    /** Auf 5 % gerundet – kleine Bewegungen erzeugen keine neue Nachricht an den Browser. */
    fun quantize(gain: Double): Double = (gain.coerceIn(0.0, 1.0) * 20).roundToInt() / 20.0

    fun phaseOf(text: String?): Phase? = when (text?.lowercase()) {
        "lobby" -> Phase.LOBBY
        "tasks" -> Phase.TASKS
        "meeting" -> Phase.MEETING
        "end" -> Phase.END
        else -> null
    }
}

/** Meldung des Hosts: `{"crewstats":"voice","v":1,"fc":"<host>","code":"ABCDEF","phase":"tasks","players":[{fc,name,color,x,y,dead,vent,mute}]}`. */
data class VoiceReport(val host: String, val code: String, val phase: VoiceRules.Phase, val players: List<VoiceRules.Player>) {
    companion object {
        const val KIND = "voice"
        const val REPLY = "voice-reply"
        const val MAX_PLAYERS = 20

        fun isReport(text: String): Boolean = text.contains("\"crewstats\"") && text.contains("\"$KIND\"") &&
            (runCatching { ((parseJson(text) as? JsonObject)?.get("crewstats") as? JsonPrimitive)?.contentOrNull }.getOrNull() == KIND)

        fun parse(text: String): VoiceReport? {
            val root = runCatching { parseJson(text) as? JsonObject }.getOrNull() ?: return null
            if (root.str("crewstats") != KIND) return null
            val host = normalizeFriendCode(root.str("fc") ?: return null).takeIf { it.isNotEmpty() } ?: return null
            val phase = VoiceRules.phaseOf(root.str("phase")) ?: return null
            val code = (root.str("code") ?: "").trim().uppercase().take(8)
            val players = (root["players"] as? JsonArray ?: JsonArray(emptyList())).asSequence()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { p ->
                    val fc = normalizeFriendCode(p.str("fc") ?: return@mapNotNull null).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    VoiceRules.Player(
                        fc = fc,
                        name = (p.str("name") ?: "").trim().take(24),
                        color = (p["color"] as? JsonPrimitive)?.intOrNull ?: -1,
                        x = (p["x"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                        y = (p["y"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                        dead = p.bool("dead"),
                        vent = p.bool("vent"),
                        silenced = p.bool("mute"),
                    )
                }
                .distinctBy { it.fc }
                .take(MAX_PLAYERS)
                .toList()
            return VoiceReport(host, code, phase, players)
        }

        private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(name: String): Boolean = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: false
    }
}

class VoiceChat(
    db: Database,
    private val auth: WebAuth?,
    private val store: StatsStore? = null,
    /** ICE-Server für WebRTC (STUN, optional TURN) – aus `VOICE_ICE_SERVERS` in der .env. */
    private val iceServers: JsonArray = DEFAULT_ICE,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(VoiceChat::class.java)
    private val links = LinkService(db)
    private val random = SecureRandom()
    private val rooms = LinkedHashMap<String, Room>() // Host-Friendcode → Raum, alles unter synchronized(this)
    private val members = HashMap<String, Member>() // peer-id → Teilnehmer
    private val reportCalls = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    private val codesCache = ConcurrentHashMap<Long, Pair<List<String>, Instant>>()
    private var ticker: ScheduledExecutorService? = null

    internal class Room(val host: String) {
        var code = ""
        var phase = VoiceRules.Phase.LOBBY
        var players: Map<String, VoiceRules.Player> = emptyMap()
        var reportedAt: Instant = Instant.EPOCH
        val members = LinkedHashMap<String, Member>()
    }

    internal class Member(val peer: String, val userId: Long, var fc: String, val room: Room, joinedAt: Instant) {
        internal val queue = LinkedBlockingQueue<String>(QUEUE_LIMIT)
        internal var streams = 0
        internal var streamGoneAt: Instant? = joinedAt
        internal var missingSince: Instant? = null
        internal var lastMix: String? = null
        internal var closed = false
        internal val signals = ArrayDeque<Instant>()
    }

    // ---------- Host-Meldungen (über ModApi) ----------

    /** Meldung der Host-Mod verarbeiten; Antwort sagt, wie viele zuhören (ohne Zuhörer meldet die Mod seltener). */
    fun report(text: String, client: String): String {
        if (!allowReport(client)) return """{"crewstats":"${VoiceReport.REPLY}","error":"busy"}"""
        val report = VoiceReport.parse(text) ?: return """{"crewstats":"${VoiceReport.REPLY}","error":"request"}"""
        val now = clock()
        val listeners = synchronized(this) {
            val room = rooms.getOrPut(report.host) { Room(report.host) }
            if (room.code != report.code && room.code.isNotEmpty()) log.info("Voice: Host wechselt Lobby {} → {}", room.code, report.code)
            room.code = report.code
            room.phase = report.phase
            room.players = report.players.associateBy { it.fc }
            room.reportedAt = now
            room.members.size
        }
        return """{"crewstats":"${VoiceReport.REPLY}","listeners":$listeners}"""
    }

    // ---------- Browser ----------

    fun handles(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    fun handle(request: WebRequest): WebResponse = when (request.path) {
        PATH -> if (request.method == "GET" || request.method == "HEAD") status(request) else notAllowed("GET")
        "$PATH/join" -> if (request.method == "POST") join(request) else notAllowed("POST")
        "$PATH/signal" -> if (request.method == "POST") signal(request) else notAllowed("POST")
        "$PATH/leave" -> if (request.method == "POST") leave(request) else notAllowed("POST")
        else -> json(404, obj("error" to "Nicht gefunden"))
    }

    private fun status(request: WebRequest): WebResponse {
        val userId = auth?.userOf(request)
        if (auth == null) return json(200, obj("enabled" to false, "loggedIn" to false))
        if (userId == null) return json(200, obj("enabled" to true, "loggedIn" to false))
        val codes = codesOf(userId)
        val now = clock()
        val room = synchronized(this) { roomFor(codes, now) }
        val mine = synchronized(this) { room?.members?.values?.firstOrNull { it.userId == userId }?.peer }
        return json(200, obj(
            "enabled" to true,
            "loggedIn" to true,
            "linked" to codes.isNotEmpty(),
            "room" to room?.let { r ->
                synchronized(this) {
                    obj(
                        "code" to r.code,
                        "mode" to modeOf(r, now),
                        "members" to r.members.size,
                        "players" to r.players.size,
                    )
                }
            },
            "peer" to mine,
        ))
    }

    private fun join(request: WebRequest): WebResponse {
        val handler = auth ?: return json(503, obj("error" to "Auf dieser Website ist die Anmeldung aus."))
        handler.sameOrigin(request)?.let { return it }
        val userId = handler.userOf(request) ?: return json(401, obj("error" to "Bitte zuerst mit Discord anmelden.", "login" to true))
        val codes = codesOf(userId, fresh = true)
        if (codes.isEmpty()) {
            return json(409, obj("error" to "Verknüpfe zuerst deinen Among-Us-Account: /verknuepfen im Discord oder per Code aus dem F7-Menü von CrewStats."))
        }
        val now = clock()
        val member = synchronized(this) {
            val room = roomFor(codes, now)
                ?: return json(409, obj("error" to "Du bist gerade in keiner Lobby mit Voice. Tritt im Spiel einer Lobby bei – der Host braucht CrewStats 1.10.0 mit der Website-Adresse."))
            if (members.size >= MAX_MEMBERS) return json(503, obj("error" to "Voice ist gerade voll – versuch es gleich nochmal."))
            // pro Konto nur ein Platz (neuer Tab ersetzt den alten)
            members.values.filter { it.userId == userId }.forEach { remove(it, "replaced") }
            val fc = codes.first { it in room.players }
            val peer = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also(random::nextBytes))
            val member = Member(peer, userId, fc, room, now)
            members[peer] = member
            room.members[peer] = member
            broadcastPeers(room)
            member
        }
        log.info("Voice: {} Teilnehmer in Lobby {}", member.room.members.size, member.room.code)
        return json(200, obj("ok" to true, "peer" to member.peer, "ice" to iceServers))
    }

    private fun signal(request: WebRequest): WebResponse {
        val member = memberOf(request).let { it.first ?: return it.second!! }
        val body = parseBody(request)
        val to = (body?.get("to") as? JsonPrimitive)?.contentOrNull ?: return json(400, obj("error" to "Ungültige Eingabe"))
        val data = body["data"] as? JsonObject ?: return json(400, obj("error" to "Ungültige Eingabe"))
        val now = clock()
        synchronized(this) {
            if (!allowSignal(member, now)) return json(429, obj("error" to "Zu viele Nachrichten"))
            val target = members[to]?.takeIf { it.room === member.room && !it.closed } ?: return json(404, obj("error" to "Teilnehmer ist weg"))
            target.queue.offer(event("signal", obj("from" to member.peer, "data" to data)))
        }
        return json(200, obj("ok" to true))
    }

    private fun leave(request: WebRequest): WebResponse {
        val member = memberOf(request).let { it.first ?: return it.second!! }
        synchronized(this) { remove(member, "left") }
        return json(200, obj("ok" to true))
    }

    /** Teilnehmer aus `{"peer":…}` (Body) bzw. `?peer=` – muss zur Sitzung gehören. */
    private fun memberOf(request: WebRequest): Pair<Member?, WebResponse?> {
        val handler = auth ?: return null to json(503, obj("error" to "Anmeldung aus"))
        if (request.method == "POST") handler.sameOrigin(request)?.let { return null to it }
        val userId = handler.userOf(request) ?: return null to json(401, obj("error" to "Nicht angemeldet", "login" to true))
        val peer = request.query["peer"] ?: (parseBody(request)?.get("peer") as? JsonPrimitive)?.contentOrNull
            ?: return null to json(400, obj("error" to "peer fehlt"))
        val member = synchronized(this) { members[peer]?.takeIf { it.userId == userId && !it.closed } }
            ?: return null to json(410, obj("error" to "Nicht mehr im Voice", "gone" to true))
        return member to null
    }

    // ---------- Stream (SSE) ----------

    /** Prüft die Anfrage für /api/voice/stream: Teilnehmer oder Fehler-Antwort. */
    internal fun openStream(request: WebRequest): Pair<Member?, WebResponse?> = memberOf(request)

    /**
     * Schickt die Ereignisse des Teilnehmers, bis der Browser die Verbindung schließt oder der Teilnehmer gehen muss.
     * Läuft im Thread der Anfrage (blockiert ihn). Bricht die Verbindung ab, darf der Browser sie innerhalb von
     * [RECONNECT] wieder öffnen (EventSource macht das selbst).
     */
    internal fun pump(member: Member, out: OutputStream, keepAlive: Duration = KEEP_ALIVE) {
        synchronized(this) {
            if (member.closed) return
            member.streams++
            member.streamGoneAt = null
            member.lastMix = null // nach Wiederverbinden alles neu schicken (wartende Signale bleiben)
            member.queue.offer(event("hello", obj("you" to member.peer)))
            peersEvent(member.room, member)?.let { member.queue.offer(it) }
        }
        try {
            // Polster für Proxys, die erst ab ein paar KB weiterreichen
            out.write((": " + " ".repeat(2048) + "\n\n").toByteArray())
            out.flush()
            while (true) {
                val next = member.queue.poll(keepAlive.toMillis(), TimeUnit.MILLISECONDS)
                if (next == null) {
                    if (member.closed) return
                    out.write(": ping\n\n".toByteArray())
                } else {
                    out.write(next.toByteArray(Charsets.UTF_8))
                    if (next.startsWith("event: bye")) {
                        out.flush()
                        return
                    }
                }
                out.flush()
            }
        } catch (e: IOException) {
            // Browser weg – Teilnehmer bleibt [RECONNECT] lang, falls er wiederkommt
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(this) {
                member.streams--
                if (member.streams <= 0) member.streamGoneAt = clock()
            }
        }
    }

    // ---------- Takt: Lautstärken, Aufräumen ----------

    fun start() {
        if (ticker != null) return
        ticker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "voice").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (e: Exception) {
                    log.warn("Voice: {}", e.toString())
                }
            }, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        ticker?.shutdownNow()
        ticker = null
    }

    /** Alle 200 ms: Lautstärken neu berechnen und nur bei Änderung schicken; verschwundene Teilnehmer entfernen. */
    @Synchronized
    fun tick() {
        val now = clock()
        for (member in members.values.toList()) {
            val room = member.room
            val gone = member.streamGoneAt
            if (gone != null && Duration.between(gone, now) > RECONNECT) {
                remove(member, "timeout")
                continue
            }
            val fresh = !stale(room, now)
            if (fresh && member.fc !in room.players) {
                // in einer anderen Lobby? Dann gleich dorthin, sonst nach kurzer Zeit raus
                val since = member.missingSince ?: now.also { member.missingSince = it }
                if (Duration.between(since, now) > MISSING) remove(member, "left-lobby")
            } else {
                member.missingSince = null
            }
        }
        val iterator = rooms.values.iterator()
        while (iterator.hasNext()) {
            val room = iterator.next()
            val silentFor = Duration.between(room.reportedAt, now)
            if (silentFor > ROOM_GONE) {
                room.members.values.toList().forEach { remove(it, "host-gone") }
                iterator.remove()
                continue
            }
            if (room.members.isEmpty()) continue
            val mode = modeOf(room, now)
            for (member in room.members.values) {
                val mix = mixOf(room, member, now)
                val text = obj("mode" to mode, "hear" to mix.first, "send" to mix.second).toString()
                if (text == member.lastMix) continue
                member.lastMix = text
                member.queue.offer("event: mix\ndata: $text\n\n")
            }
        }
    }

    /** Wen [member] wie laut hört, und an wen er senden soll (wer ihn hört). */
    private fun mixOf(room: Room, member: Member, now: Instant): Pair<Map<String, Double>, List<String>> {
        val others = room.members.values.filter { it !== member }
        if (stale(room, now)) return others.associate { it.peer to 1.0 } to others.map { it.peer }
        val me = room.players[member.fc]
        val hear = LinkedHashMap<String, Double>()
        val send = ArrayList<String>()
        for (other in others) {
            val them = room.players[other.fc]
            if (me == null || them == null) {
                hear[other.peer] = 0.0
                continue
            }
            hear[other.peer] = VoiceRules.quantize(VoiceRules.gain(room.phase, me, them))
            if (VoiceRules.gain(room.phase, them, me) > 0.0) send += other.peer
        }
        return hear to send
    }

    // ---------- Helfer ----------

    /** Raum mit der frischesten Meldung, in dem einer der Friendcodes mitspielt. */
    private fun roomFor(codes: List<String>, now: Instant): Room? = rooms.values
        .filter { room -> !stale(room, now) && codes.any { it in room.players } }
        .maxByOrNull { it.reportedAt }

    private fun stale(room: Room, now: Instant) = Duration.between(room.reportedAt, now) > STALE

    /** lobby | round | meeting | free (Host meldet nichts mehr → alle hören alle). Verrät nichts, was man nicht sieht. */
    private fun modeOf(room: Room, now: Instant): String = if (stale(room, now)) "free" else when (room.phase) {
        VoiceRules.Phase.LOBBY, VoiceRules.Phase.END -> "lobby"
        VoiceRules.Phase.TASKS -> "round"
        VoiceRules.Phase.MEETING -> "meeting"
    }

    private fun remove(member: Member, reason: String) {
        if (member.closed) return
        member.closed = true
        members.remove(member.peer)
        val room = member.room
        room.members.remove(member.peer)
        member.queue.clear()
        member.queue.offer(event("bye", obj("reason" to reason)))
        broadcastPeers(room)
    }

    private fun broadcastPeers(room: Room) {
        for (member in room.members.values) peersEvent(room, member)?.let { member.queue.offer(it) }
    }

    private fun peersEvent(room: Room, member: Member): String? {
        if (member.closed) return null
        val snapshot = store?.snapshot
        val list = room.members.values.filter { it !== member }.map { other ->
            val player = room.players[other.fc]
            val key = WebAuth.playerKeyOf(other.userId).takeIf { snapshot?.hasPlayer(it) == true }
            obj(
                "id" to other.peer,
                "name" to (player?.name?.ifEmpty { null } ?: "Spieler"),
                "color" to player?.color?.takeIf { it >= 0 },
                "key" to key,
                // wer höher ist, startet die Verbindung (feste Regel, damit nie beide gleichzeitig anbieten)
                "offer" to (member.peer > other.peer),
            )
        }
        return event("peers", obj("list" to list))
    }

    private fun allowSignal(member: Member, now: Instant): Boolean {
        val window = member.signals
        while (window.isNotEmpty() && window.first().plus(Duration.ofMinutes(1)).isBefore(now)) window.removeFirst()
        if (window.size >= SIGNAL_RATE) return false
        window.addLast(now)
        return true
    }

    private fun allowReport(client: String): Boolean {
        val now = clock()
        if (reportCalls.size > 1000) reportCalls.clear()
        val window = reportCalls.getOrPut(client) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first().plusSeconds(1).isBefore(now)) window.removeFirst()
            if (window.size >= REPORT_RATE) return false
            window.addLast(now)
            return true
        }
    }

    private fun codesOf(userId: Long, fresh: Boolean = false): List<String> {
        val now = clock()
        if (!fresh) codesCache[userId]?.let { (codes, at) -> if (at.plus(CODES_CACHE).isAfter(now)) return codes }
        val codes = runCatching { links.codesOf(userId).map(::normalizeFriendCode) }.getOrElse { codesCache[userId]?.first ?: emptyList() }
        if (codesCache.size > 2000) codesCache.clear()
        codesCache[userId] = codes to now
        return codes
    }

    private fun parseBody(request: WebRequest): JsonObject? {
        if (request.body.isEmpty() || request.body.size > MAX_BODY) return null
        return runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }

    private fun event(name: String, data: JsonElement) = "event: $name\ndata: $data\n\n"

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun notAllowed(allowed: String) = WebResponse(405, body = bytes(obj("error" to "Nur $allowed")), headers = listOf("Allow" to allowed, NO_STORE))

    /** Für Tests: Teilnehmer der Lobby eines Hosts. */
    @Synchronized
    internal fun membersOf(host: String): List<String> = rooms[normalizeFriendCode(host)]?.members?.keys?.toList() ?: emptyList()

    companion object {
        const val PATH = "/api/voice"
        const val STREAM_PATH = "$PATH/stream"
        const val MAX_BODY = 16 * 1024
        const val MAX_MEMBERS = 60
        private const val QUEUE_LIMIT = 2000
        private const val SIGNAL_RATE = 1500
        private const val REPORT_RATE = 20
        val TICK: Duration = Duration.ofMillis(200)
        val KEEP_ALIVE: Duration = Duration.ofSeconds(15)
        /** So lange darf der Browser ohne offenen Stream sein (Neuladen, kurzer Verbindungsabbruch). */
        val RECONNECT: Duration = Duration.ofSeconds(20)
        /** Ohne Meldung des Hosts so lange: alle hören alle („free“). */
        val STALE: Duration = Duration.ofSeconds(6)
        /** Ohne Meldung so lange: Raum zu. */
        val ROOM_GONE: Duration = Duration.ofMinutes(5)
        /** Friendcode nicht mehr in der Lobby so lange → raus. */
        val MISSING: Duration = Duration.ofSeconds(10)
        private val CODES_CACHE: Duration = Duration.ofSeconds(30)
        private val NO_STORE = "Cache-Control" to "no-store"

        val DEFAULT_ICE: JsonArray = JsonArray(listOf(obj("urls" to "stun:stun.cloudflare.com:3478")))

        /** `VOICE_ICE_SERVERS` = JSON-Liste wie in RTCConfiguration.iceServers, leer → [DEFAULT_ICE]. */
        fun iceFromEnv(value: String): JsonArray {
            if (value.isBlank()) return DEFAULT_ICE
            val parsed = runCatching { parseJson(value) as? JsonArray }.getOrNull()
            return parsed?.takeIf { list -> list.isNotEmpty() && list.all { it is JsonObject && "urls" in it } } ?: DEFAULT_ICE
        }
    }
}
