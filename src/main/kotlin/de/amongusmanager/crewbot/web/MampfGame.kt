/*
 * MAMPF! – Mehrspieler-Teil des Blob-Fress-Spiels (Seite seiten/mampf.html, Welt in MampfWorld.kt).
 *
 * Der JDK-HttpServer kann kein WebSocket, deshalb wie beim Voice-Chat: der Browser bekommt die Welt als
 * Server-Sent Events (GET /api/mampf/stream, 25 Bilder pro Sekunde, nur der sichtbare Ausschnitt) und schickt
 * seine Eingaben per POST (/api/mampf/input). Ohne Anmeldung, alles nur im Speicher; nur die Rekordliste liegt
 * in data/mampf-rekorde.json. Die Welt läuft nur, solange jemand spielt.
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

class MampfGame(
    /** Datei für die Rekordliste, null = nur im Speicher. */
    private val recordsFile: Path? = null,
    random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(MampfGame::class.java)
    internal val world = MampfWorld(random)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val secure = SecureRandom()
    private var timer: ScheduledExecutorService? = null
    private var lastTick = 0L
    private var lastBoard = 0L
    private var board = "[]"
    @Volatile private var records: List<Record> = loadRecords()

    class Record(val name: String, val mass: Int, val face: Int, val hue: Int)

    internal class Session(val token: String, val ip: String, @Volatile var name: String, @Volatile var face: Int, @Volatile var hue: Int) {
        /** Wird im Takt-Thread angelegt. */
        @Volatile var player: MampfPlayer? = null
        @Volatile var lastSeen = 0L
        @Volatile var wantSpawn = true
        @Volatile var gone = false
        @Volatile var targetX = Double.NaN
        @Volatile var targetY = Double.NaN
        val splits = java.util.concurrent.atomic.AtomicInteger()
        val ejects = java.util.concurrent.atomic.AtomicInteger()
        /** Jede neue Verbindung löst die alte ab. */
        val stream = java.util.concurrent.atomic.AtomicInteger()
        val queue = LinkedBlockingQueue<String>(QUEUE)
        @Volatile var resync = true
        val knownFood = HashSet<Int>()
        val knownPlayers = HashSet<Int>()
        var deathSent = true
        var death: String? = null
    }

    val online: Int get() = sessions.size

    // ---------- HTTP ----------

    fun handle(request: WebRequest, client: String): WebResponse {
        val route = request.path.removePrefix(PATH).trim('/')
        if (request.method == "GET" || request.method == "HEAD") {
            if (route.isEmpty()) return json(200, obj("online" to sessions.size, "max" to MAX_PLAYERS, "records" to recordsJson()))
            return json(404, obj("error" to "Nicht gefunden"))
        }
        if (request.method != "POST") return json(405, obj("error" to "Nur GET und POST"))
        val body = try {
            Json.parseToJsonElement(String(request.body, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return json(400, obj("error" to "Kein JSON"))
        }
        return when (route) {
            "join" -> join(body, client)
            "spawn" -> withSession(body) { session ->
                look(session, body)
                session.wantSpawn = true
                json(200, obj("ok" to true))
            }
            "input" -> withSession(body) { session ->
                val x = body["x"]?.jsonPrimitive?.doubleOrNull
                val y = body["y"]?.jsonPrimitive?.doubleOrNull
                if (x != null && y != null && x.isFinite() && y.isFinite()) {
                    session.targetX = x
                    session.targetY = y
                }
                val splits = body["s"]?.jsonPrimitive?.intOrNull ?: 0
                val ejects = body["e"]?.jsonPrimitive?.intOrNull ?: 0
                if (splits > 0) session.splits.updateAndGet { (it + splits).coerceAtMost(4) }
                if (ejects > 0) session.ejects.updateAndGet { (it + ejects).coerceAtMost(12) }
                json(200, obj("ok" to true))
            }
            "leave" -> withSession(body) { session ->
                session.gone = true
                json(200, obj("ok" to true))
            }
            else -> json(404, obj("error" to "Nicht gefunden"))
        }
    }

    private fun join(body: JsonObject, client: String): WebResponse {
        if (sessions.size >= MAX_PLAYERS) return json(503, obj("error" to "Die Petrischale ist voll. Versuch es gleich nochmal."))
        if (sessions.values.count { it.ip == client } >= MAX_PER_IP) return json(429, obj("error" to "Zu viele Blobs von deinem Anschluss."))
        val bytes = ByteArray(18)
        secure.nextBytes(bytes)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val session = Session(token, client, "", 0, 0)
        look(session, body)
        session.lastSeen = clock()
        sessions[token] = session
        ensureRunning()
        return json(200, obj("token" to token, "size" to world.size.toInt()))
    }

    private fun look(session: Session, body: JsonObject) {
        body["name"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let { session.name = cleanName(it) }
        if (session.name.isEmpty()) session.name = "Namenloser Blob"
        body["face"]?.jsonPrimitive?.intOrNull?.let { session.face = it.coerceIn(0, MampfNames.FACES - 1) }
        body["hue"]?.jsonPrimitive?.intOrNull?.let { session.hue = ((it % 360) + 360) % 360 }
    }

    private inline fun withSession(body: JsonObject, action: (Session) -> WebResponse): WebResponse {
        val token = body["t"]?.jsonPrimitive?.takeIf { it.isString }?.content
        val session = token?.let { sessions[it] } ?: return json(410, obj("error" to "Sitzung abgelaufen", "gone" to true))
        session.lastSeen = clock()
        return action(session)
    }

    private fun json(status: Int, body: JsonObject): WebResponse = WebResponse(status, StatsServer.TYPE_JSON, body.toString().toByteArray(Charsets.UTF_8))

    private fun recordsJson(): JsonArray = JsonArray(records.map { obj("name" to it.name, "mass" to it.mass, "face" to it.face, "hue" to it.hue) })

    // ---------- Stream ----------

    internal fun session(token: String?): Session? = token?.let { sessions[it] }

    /** Läuft im Thread der Anfrage, bis der Browser weg ist oder eine neue Verbindung diese ablöst. */
    internal fun pump(session: Session, out: OutputStream) {
        val mine = session.stream.incrementAndGet()
        session.queue.clear()
        session.resync = true
        try {
            out.write((": " + " ".repeat(2048) + "\n\n").toByteArray())
            out.flush()
            while (session.stream.get() == mine && !session.gone) {
                val frame = session.queue.poll(5, TimeUnit.SECONDS)
                if (frame == null) {
                    out.write(": ping\n\n".toByteArray())
                } else {
                    out.write("data: ".toByteArray())
                    out.write(frame.toByteArray(Charsets.UTF_8))
                    out.write("\n\n".toByteArray())
                }
                out.flush()
            }
            if (session.gone) {
                out.write("event: bye\ndata: {}\n\n".toByteArray())
                out.flush()
            }
        } catch (e: IOException) {
            // Browser weg – die Sitzung läuft ab, wenn er nicht wiederkommt
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------- Takt ----------

    @Synchronized
    private fun ensureRunning() {
        if (timer != null) return
        lastTick = clock()
        timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "mampf").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({
                try {
                    tick()
                } catch (e: Exception) {
                    log.warn("MAMPF-Takt: {}", e.toString())
                }
            }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow()
        timer = null
        for (session in sessions.values) session.gone = true
        sessions.clear()
    }

    /** Ein Schritt der Welt + ein Bild an jeden Spieler. Tests rufen das direkt mit eigener Uhr. */
    @Synchronized
    internal fun tick() {
        val now = clock()
        val dt = ((now - lastTick) / 1000.0).coerceIn(0.001, 0.1)
        lastTick = now

        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.gone || now - session.lastSeen > TIMEOUT_MS) {
                session.gone = true
                session.player?.let { world.removePlayer(it) }
                iterator.remove()
                continue
            }
            val player = session.player ?: world.addPlayer(session.name, session.face, session.hue).also { session.player = it }
            if (session.wantSpawn) {
                session.wantSpawn = false
                if (!player.alive) {
                    player.name = session.name
                    player.face = session.face
                    player.hue = session.hue
                    world.spawn(player)
                    session.targetX = Double.NaN
                    session.deathSent = false
                    session.death = null
                    session.knownPlayers.remove(player.id) // Name/Gesicht können neu sein
                    for (other in sessions.values) other.knownPlayers.remove(player.id)
                }
            }
            if (player.alive) {
                if (!session.targetX.isNaN()) {
                    player.targetX = session.targetX
                    player.targetY = session.targetY
                }
                player.splits += session.splits.getAndSet(0)
                player.ejects += session.ejects.getAndSet(0)
            } else {
                session.splits.set(0)
                session.ejects.set(0)
            }
        }
        if (sessions.isEmpty()) {
            // niemand da: Welt anhalten, Takt beenden
            timer?.shutdown()
            timer = null
            return
        }

        world.step(dt)

        val feed = StringBuilder()
        for (kill in world.kills) {
            if (feed.isNotEmpty()) feed.append(',')
            feed.append('[').append(kill.eater.id).append(',').append(quote(kill.eater.name)).append(',')
                .append(kill.victim.id).append(',').append(quote(kill.victim.name)).append(']')
            if (!kill.victim.bot) {
                record(kill.victim, kill.peak)
                sessions.values.firstOrNull { it.player === kill.victim }?.death =
                    "{\"by\":${quote(kill.eater.name)},\"peak\":${kill.peak.roundToInt()},\"secs\":${kill.seconds.roundToInt()},\"eaten\":${kill.victim.eaten}}"
            }
        }
        world.kills.clear()

        if (now - lastBoard >= 500) {
            lastBoard = now
            val top = world.players.values.filter { it.alive }.sortedByDescending { it.mass }
            board = top.take(10).joinToString(",", "[", "]") { "[${it.id},${quote(it.name)},${it.mass.roundToInt()}]" }
            for (session in sessions.values) {
                val player = session.player ?: continue
                session.queue.offer("{\"lb\":$board,\"rank\":${top.indexOf(player) + 1},\"n\":${top.size}}")
            }
        }
        val time = (world.time * 1000).toLong()
        for (session in sessions.values) send(session, time, feed)
    }

    private fun send(session: Session, time: Long, feed: CharSequence) {
        val player = session.player ?: return
        if (session.queue.remainingCapacity() < 2) {
            // Browser kommt nicht hinterher: Rückstand wegwerfen und alles neu schicken
            session.queue.clear()
            session.resync = true
        }
        val full = session.resync
        if (full) {
            session.resync = false
            session.knownFood.clear()
            session.knownPlayers.clear()
        }
        val scale = MampfRules.viewScale(if (player.alive) player.totalRadius else 60.0)
        val halfW = MampfRules.VIEW_HALF_W / scale
        val halfH = MampfRules.VIEW_HALF_H / scale
        val x0 = player.x - halfW
        val x1 = player.x + halfW
        val y0 = player.y - halfH
        val y1 = player.y + halfH

        val out = StringBuilder(2048)
        out.append("{\"t\":").append(time)
        if (full) out.append(",\"full\":1,\"you\":").append(player.id)
        out.append(",\"me\":[").append(player.x.roundToInt()).append(',').append(player.y.roundToInt()).append(',')
            .append(player.mass.roundToInt()).append(',').append(if (player.alive) 1 else 0).append(']')

        // Blobs
        val seen = ArrayList<MampfPlayer>()
        out.append(",\"c\":[")
        var first = true
        for (other in world.players.values) {
            var visible = false
            for (cell in other.cells) {
                val r = cell.radius
                if (cell.x + r < x0 || cell.x - r > x1 || cell.y + r < y0 || cell.y - r > y1) continue
                if (!first) out.append(',')
                first = false
                out.append(cell.id).append(',').append(cell.x.roundToInt()).append(',').append(cell.y.roundToInt()).append(',')
                    .append((r * 10).roundToInt()).append(',').append(other.id)
                visible = true
            }
            if (visible && session.knownPlayers.add(other.id)) seen += other
        }
        out.append(']')
        if (seen.isNotEmpty()) {
            out.append(",\"p\":[")
            seen.forEachIndexed { index, other ->
                if (index > 0) out.append(',')
                out.append('[').append(other.id).append(',').append(quote(other.name)).append(',').append(other.face).append(',')
                    .append(other.hue).append(',').append(if (other.bot) 1 else 0).append(']')
            }
            out.append(']')
        }
        things(out, "v", world.viruses, x0, y0, x1, y1, withFed = true)
        things(out, "e", world.spit, x0, y0, x1, y1, withFed = false)

        // Krümel: nur was neu im Bild ist oder verschwunden ist
        val now = HashSet<Int>(session.knownFood.size + 16)
        val added = StringBuilder()
        world.foodIn(x0, y0, x1, y1) { thing ->
            now += thing.id
            if (thing.id !in session.knownFood) {
                if (added.isNotEmpty()) added.append(',')
                added.append(thing.id).append(',').append(thing.x.roundToInt()).append(',').append(thing.y.roundToInt()).append(',')
                    .append(thing.hue).append(',').append((thing.radius * 10).roundToInt())
            }
        }
        if (added.isNotEmpty()) out.append(",\"fa\":[").append(added).append(']')
        var removed = false
        for (id in session.knownFood) {
            if (id in now) continue
            out.append(if (removed) "," else ",\"fr\":[").append(id)
            removed = true
        }
        if (removed) out.append(']')
        session.knownFood.clear()
        session.knownFood.addAll(now)

        if (feed.isNotEmpty()) out.append(",\"k\":[").append(feed).append(']')
        if (!player.alive && !session.deathSent) {
            session.deathSent = true
            out.append(",\"dead\":").append(session.death ?: "{}")
        }
        out.append('}')
        session.queue.offer(out.toString())
    }

    private fun things(out: StringBuilder, key: String, list: List<MampfThing>, x0: Double, y0: Double, x1: Double, y1: Double, withFed: Boolean) {
        var first = true
        for (thing in list) {
            val r = thing.radius
            if (thing.x + r < x0 || thing.x - r > x1 || thing.y + r < y0 || thing.y - r > y1) continue
            out.append(if (first) ",\"$key\":[" else ",")
            first = false
            out.append(thing.id).append(',').append(thing.x.roundToInt()).append(',').append(thing.y.roundToInt()).append(',')
                .append(if (withFed) thing.fed else thing.hue)
        }
        if (!first) out.append(']')
    }

    // ---------- Rekorde ----------

    private fun record(player: MampfPlayer, peak: Double) {
        val mass = peak.roundToInt()
        val current = records
        if (mass < 100 || (current.size >= RECORDS && mass <= current.last().mass)) return
        records = (current + Record(player.name, mass, player.face, player.hue)).sortedByDescending { it.mass }.take(RECORDS)
        val file = recordsFile ?: return
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            Files.writeString(file, recordsJson().toString())
        } catch (e: Exception) {
            log.warn("MAMPF-Rekorde nicht gespeichert: {}", e.toString())
        }
    }

    private fun loadRecords(): List<Record> {
        val file = recordsFile ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            Json.parseToJsonElement(Files.readString(file)).jsonArray.mapNotNull { element ->
                val entry = element.jsonObject
                Record(
                    cleanName(entry["name"]?.jsonPrimitive?.content ?: return@mapNotNull null),
                    entry["mass"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    (entry["face"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, MampfNames.FACES - 1),
                    (entry["hue"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 359),
                )
            }.sortedByDescending { it.mass }.take(RECORDS)
        } catch (e: Exception) {
            log.warn("MAMPF-Rekorde nicht lesbar: {}", e.toString())
            emptyList()
        }
    }

    companion object {
        const val PATH = "/api/mampf"
        const val STREAM_PATH = "$PATH/stream"
        const val MAX_PLAYERS = 40
        const val MAX_PER_IP = 6
        const val TICK_MS = 40L
        const val TIMEOUT_MS = 12_000L
        const val RECORDS = 10
        private const val QUEUE = 50

        fun quote(text: String): String = JsonPrimitive(text).toString()

        /** Höchstens 16 Zeichen, keine Steuerzeichen, keine doppelten Leerzeichen. */
        fun cleanName(raw: String): String =
            raw.filter { !it.isISOControl() && it != '​' && it != '‮' }.trim().replace(Regex("\\s+"), " ").let {
                if (it.codePointCount(0, it.length) <= 16) it else it.substring(0, it.offsetByCodePoints(0, 16)).trim()
            }
    }
}
