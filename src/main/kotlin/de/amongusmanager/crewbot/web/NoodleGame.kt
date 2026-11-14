/*
 * „Nudelsalat“: Schlangen-Arena zum Mitspielen im Browser (Seite seiten/slither.html). Jeder steuert eine Nudel, frisst
 * Krümel, wächst, kann boosten (kostet Länge) – wer mit dem Kopf in eine andere Nudel oder den Tellerrand fährt, wird
 * selbst zu Krümeln. Bots füllen die Arena auf.
 *
 *   GET  /api/nudelsalat          Status: Spieler online + ewige Bestenliste
 *   GET  /api/nudelsalat/stream   Server-Sent Events: erst „init“ (ganze Arena + eigener Schlüssel), dann 20×/s der Stand
 *   POST /api/nudelsalat/join     {"k":Schlüssel,"name":…,"skin":0–11} → eigene Nudel entsteht
 *   POST /api/nudelsalat/steer    Text „Schlüssel;Winkel;Boost(0|1)“
 *
 * Kein WebSocket (der JDK-HttpServer kann das nicht) – wie beim Voice: Server → Browser per SSE, Browser → Server per POST.
 * Die Arena lebt nur im Speicher und rechnet nur, solange jemand zuschaut. Ohne Anmeldung (nur ein Spitzname).
 * Der Server rechnet alles selbst (Bewegung, Fressen, Zusammenstöße) – der Browser schickt nur Richtung und Boost.
 *
 * Protokoll (kurz gehalten, Zahlen ganzzahlig): Jede Nudel ist ein Pfad aus Punkten (ein Punkt je Tick). „init“ schickt
 * alle Pfade, danach kommt je Tick nur der neue Kopf + die Länge; der Browser hängt den Punkt an und kürzt hinten selbst.
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NoodleGame(
    /** Datei für die ewige Bestenliste (JSON), null = nur im Speicher. */
    private val scoresFile: Path? = null,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(NoodleGame::class.java)
    private var ticker: ScheduledExecutorService? = null

    /** Pfad einer Nudel: Punkte vom Schwanz (alt) zum Kopf (neu), dazu die Bogenlänge. */
    internal class Body {
        var xs = DoubleArray(128)
        var ys = DoubleArray(128)
        private var seg = DoubleArray(128) // Abstand zum Punkt davor
        var start = 0
        var end = 0
        var total = 0.0
        val size: Int get() = end - start

        fun add(x: Double, y: Double) {
            if (end == xs.size) {
                val count = size
                val capacity = if (count * 2 > xs.size) xs.size * 2 else xs.size
                val nx = DoubleArray(capacity)
                val ny = DoubleArray(capacity)
                val ns = DoubleArray(capacity)
                System.arraycopy(xs, start, nx, 0, count)
                System.arraycopy(ys, start, ny, 0, count)
                System.arraycopy(seg, start, ns, 0, count)
                xs = nx; ys = ny; seg = ns; start = 0; end = count
            }
            val step = if (size == 0) 0.0 else hypot(x - xs[end - 1], y - ys[end - 1])
            seg[end] = step
            total += step
            xs[end] = x
            ys[end] = y
            end++
        }

        /** Kürzt hinten, bis der Pfad gerade noch [length] lang ist. */
        fun trim(length: Double) {
            while (size > 2 && total - seg[start + 1] >= length) {
                total -= seg[start + 1]
                start++
                seg[start] = 0.0
            }
        }
    }

    internal class Snake(val id: Int, val name: String, val skin: Int, val bot: Boolean) {
        var x = 0.0
        var y = 0.0
        var angle = 0.0
        var target = 0.0
        var boost = false
        var boosting = false
        var length = START_LENGTH
        var best = START_LENGTH
        var kills = 0
        val body = Body()
        var client: Client? = null
        var dropAt = 0L

        // nur Bots
        var wander = 0.0
        var boostUntil = 0L
        var skill = 1.0

        val radius: Double get() = radiusOf(length)
    }

    internal class Food(val id: Int, val x: Double, val y: Double, val color: Int, val value: Int, val expires: Long)

    class Client internal constructor(val token: String, val ip: String) {
        internal val queue = LinkedBlockingQueue<String>()
        @Volatile internal var closed = false
        internal var snake: Snake? = null
        internal var activeAt = 0L
    }

    class Score(val name: String, val score: Int, val at: Long)

    private val snakes = LinkedHashMap<Int, Snake>()
    private val foods = LinkedHashMap<Int, Food>()
    private val clients = LinkedHashMap<String, Client>()
    private var scores: List<Score> = loadScores()
    private var nextId = 1
    private var nextFood = 1
    private var tickNo = 0L
    private val botDue = ArrayList<Long>()

    // seit dem letzten Tick passiert (geht mit dem nächsten Stand raus)
    private val born = ArrayList<Snake>()
    private val dead = ArrayList<IntArray>()
    private val foodAdded = ArrayList<Food>()
    private val foodGone = ArrayList<IntArray>()
    private var scoresChanged = false

    // ---------- HTTP ----------

    fun handle(request: WebRequest, ip: String): WebResponse = when (request.path) {
        PATH -> if (request.method == "GET" || request.method == "HEAD") json(200, status()) else notAllowed("GET")
        "$PATH/join" -> if (request.method == "POST") join(request) else notAllowed("POST")
        "$PATH/steer" -> if (request.method == "POST") steer(request) else notAllowed("POST")
        else -> json(404, obj("error" to "Nicht gefunden"))
    }

    @Synchronized
    private fun status(): JsonObject = obj(
        "players" to snakes.values.count { !it.bot },
        "watching" to clients.size,
        "top" to scoresJson(),
    )

    private fun join(request: WebRequest): WebResponse {
        val body = try {
            Json.parseToJsonElement(String(request.body, Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return json(400, obj("error" to "Kein JSON"))
        val token = (body["k"] as? JsonPrimitive)?.contentOrNull ?: ""
        val name = cleanName((body["name"] as? JsonPrimitive)?.contentOrNull ?: "")
        val skin = ((body["skin"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, SKINS - 1)
        val id = join(token, name, skin) ?: return json(409, obj("error" to "Verbindung weg – Seite neu laden."))
        return json(200, obj("id" to id))
    }

    private fun steer(request: WebRequest): WebResponse {
        val parts = String(request.body, Charsets.UTF_8).split(';')
        val angle = parts.getOrNull(1)?.toDoubleOrNull()
        if (parts.size < 3 || angle == null || !angle.isFinite()) return json(400, obj("error" to "Format: Schlüssel;Winkel;Boost"))
        steer(parts[0], angle, parts[2] == "1")
        return json(200, obj("ok" to true))
    }

    // ---------- Spieler ----------

    /** Neuer Zuschauer (Stream), null = voll. Die erste Nachricht in seiner Warteschlange ist „init“. */
    @Synchronized
    fun connect(ip: String): Client? {
        if (clients.size >= MAX_CLIENTS || clients.values.count { it.ip == ip } >= MAX_PER_IP) return null
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val client = Client(bytes.joinToString("") { "%02x".format(it) }, ip)
        client.activeAt = clock()
        if (clients.isEmpty()) wake()
        clients[client.token] = client
        client.queue.offer(initMessage(client))
        return client
    }

    /** Eigene Nudel für diesen Zuschauer, null = unbekannter Schlüssel. Wer schon eine hat, behält sie. */
    @Synchronized
    fun join(token: String, name: String, skin: Int): Int? {
        val client = clients[token]?.takeUnless { it.closed } ?: return null
        client.activeAt = clock()
        client.snake?.let { return it.id }
        val snake = spawn(cleanName(name).ifEmpty { "Nudel ${random.nextInt(100, 1000)}" }, skin, bot = false)
        snake.client = client
        client.snake = snake
        return snake.id
    }

    @Synchronized
    fun steer(token: String, angle: Double, boost: Boolean) {
        val client = clients[token] ?: return
        client.activeAt = clock()
        val snake = client.snake ?: return
        snake.target = angle
        snake.boost = boost
    }

    @Synchronized
    fun disconnect(client: Client) {
        client.closed = true
        if (clients.remove(client.token) == null) return
        client.snake?.let { kill(it, LEFT) }
    }

    /** Schickt dem Zuschauer den Stand, bis der Browser geht. Läuft im Thread der Anfrage. */
    fun pump(client: Client, out: OutputStream) {
        try {
            out.write((": " + " ".repeat(2048) + "\n\n").toByteArray()) // Polster für Proxys, die erst ab ein paar KB weiterreichen
            out.flush()
            while (!client.closed) {
                val next = client.queue.poll(15, TimeUnit.SECONDS)
                if (next == null) {
                    out.write(": ping\n\n".toByteArray())
                } else {
                    out.write("data: ".toByteArray())
                    out.write(next.toByteArray(Charsets.UTF_8))
                    out.write("\n\n".toByteArray())
                    // was schon wartet, gleich mitschicken (ein Paket statt vieler)
                    while (true) {
                        val more = client.queue.poll() ?: break
                        out.write("data: ".toByteArray())
                        out.write(more.toByteArray(Charsets.UTF_8))
                        out.write("\n\n".toByteArray())
                    }
                }
                out.flush()
            }
        } catch (e: IOException) {
            // Browser weg
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            disconnect(client)
        }
    }

    fun start() {
        if (ticker != null) return
        ticker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "nudelsalat").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({
                try {
                    tick()
                } catch (e: Exception) {
                    log.warn("Nudelsalat: Fehler im Tick: {}", e.toString())
                }
            }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        ticker?.shutdownNow()
        ticker = null
        synchronized(this) { clients.values.forEach { it.closed = true } }
    }

    // ---------- Spiel ----------

    /** Ein Schritt (50 ms Spielzeit). Ohne Zuschauer steht die Arena still. */
    @Synchronized
    fun tick() {
        if (clients.isEmpty()) return
        tickNo++
        val list = snakes.values.toList()
        for (snake in list) if (snake.bot && (tickNo + snake.id) % 3 == 0L) think(snake)
        for (snake in list) move(snake)
        for (snake in list) eat(snake)
        collide(list)
        refillFood()
        fillBots()
        dropIdle()
        broadcast()
    }

    private fun wake() {
        // nach einer Pause: abgelaufene Krümel weg, Bots wieder da
        botDue.clear()
        refillFood(all = true)
        fillBots(now = true)
    }

    private fun move(snake: Snake) {
        val turn = TURN_RATE * DT / (1 + snake.radius / 60)
        var diff = (snake.target - snake.angle) % TAU
        if (diff > PI) diff -= TAU
        if (diff < -PI) diff += TAU
        snake.angle += diff.coerceIn(-turn, turn)
        snake.boosting = snake.boost && snake.length > MIN_BOOST_LENGTH
        val speed = if (snake.boosting) BOOST_SPEED else SPEED
        snake.x += cos(snake.angle) * speed * DT
        snake.y += sin(snake.angle) * speed * DT
        if (snake.boosting) {
            snake.length -= BOOST_COST * DT
            if (tickNo >= snake.dropAt && snake.body.size > 2) {
                snake.dropAt = tickNo + 5
                val body = snake.body
                addFood(body.xs[body.start] + jitter(6.0), body.ys[body.start] + jitter(6.0), snake.skin, 3, tickNo + 30 * TICKS)
            }
        }
        snake.body.add(snake.x, snake.y)
        snake.body.trim(snake.length)
    }

    private fun eat(snake: Snake) {
        val reach = snake.radius + EAT_REACH
        val reach2 = reach * reach
        var eaten: ArrayList<Food>? = null
        for (food in foods.values) {
            val dx = food.x - snake.x
            if (dx > reach || dx < -reach) continue
            val dy = food.y - snake.y
            if (dx * dx + dy * dy <= reach2) (eaten ?: ArrayList<Food>().also { eaten = it }).add(food)
        }
        for (food in eaten ?: return) {
            foods.remove(food.id)
            foodGone += intArrayOf(food.id, snake.id)
            snake.length = min(MAX_LENGTH, snake.length + food.value)
        }
        if (snake.length > snake.best) snake.best = snake.length
    }

    private fun collide(list: List<Snake>) {
        val hits = ArrayList<Pair<Snake, Int>>()
        for (snake in list) {
            if (hypot(snake.x, snake.y) > RADIUS - snake.radius) {
                hits += snake to WALL
                continue
            }
            val killer = list.firstOrNull { other -> other !== snake && touches(snake, other) }
            if (killer != null) hits += snake to killer.id
        }
        for ((snake, killer) in hits) kill(snake, killer)
    }

    /** Kopf von [snake] steckt im Körper von [other]. */
    private fun touches(snake: Snake, other: Snake): Boolean {
        val limit = other.radius + snake.radius * 0.6
        val limit2 = limit * limit
        val body = other.body
        for (i in body.start until body.end) {
            val dx = body.xs[i] - snake.x
            if (dx > limit || dx < -limit) continue
            val dy = body.ys[i] - snake.y
            if (dx * dx + dy * dy < limit2) return true
        }
        return false
    }

    private fun kill(snake: Snake, killer: Int) {
        if (snakes.remove(snake.id) == null) return
        dead += intArrayOf(snake.id, killer)
        snakes[killer]?.let { it.kills++ }
        // der Körper wird zu Krümeln
        val body = snake.body
        var walked = 0.0
        val spread = snake.radius * 0.8
        for (i in body.start + 1 until body.end) {
            walked += hypot(body.xs[i] - body.xs[i - 1], body.ys[i] - body.ys[i - 1])
            if (walked < DEATH_FOOD_EVERY) continue
            walked = 0.0
            if (hypot(body.xs[i], body.ys[i]) < RADIUS - 20) {
                addFood(body.xs[i] + jitter(spread), body.ys[i] + jitter(spread), snake.skin, DEATH_FOOD_VALUE, tickNo + 60 * TICKS)
            }
        }
        if (snake.bot) {
            botDue += tickNo + random.nextInt(2 * TICKS, 6 * TICKS)
        } else {
            snake.client?.snake = null
            record(snake.name, scoreOf(snake.best))
        }
    }

    private fun spawn(name: String, skin: Int, bot: Boolean): Snake {
        val snake = Snake(nextId++, name, skin, bot)
        var bestGap = -1.0
        repeat(24) {
            val distance = sqrt(random.nextDouble()) * RADIUS * 0.82
            val direction = random.nextDouble() * TAU
            val x = cos(direction) * distance
            val y = sin(direction) * distance
            var gap = Double.MAX_VALUE
            for (other in snakes.values) {
                val body = other.body
                var i = body.end - 1
                while (i >= body.start) {
                    gap = min(gap, hypot(body.xs[i] - x, body.ys[i] - y))
                    i -= 4
                }
            }
            if (gap > bestGap) {
                bestGap = gap
                snake.x = x
                snake.y = y
            }
        }
        // Richtung grob zur Mitte, damit niemand sofort in den Rand fährt
        snake.angle = atan2(-snake.y, -snake.x) + (random.nextDouble() - 0.5) * 2.0
        snake.target = snake.angle
        snake.wander = snake.angle
        snake.skill = 0.55 + random.nextDouble() * 0.45
        snake.body.add(snake.x, snake.y)
        snakes[snake.id] = snake
        born += snake
        return snake
    }

    private fun fillBots(now: Boolean = false) {
        if (now) {
            while (snakes.size < MIN_SNAKES) spawnBot()
            return
        }
        // nachrücken, wenn eine Bot-Nudel gestorben ist (mit kurzer Pause) oder Menschen gegangen sind
        val due = botDue.count { it <= tickNo }
        botDue.removeAll { it <= tickNo }
        repeat(due) { if (snakes.size < MIN_SNAKES) spawnBot() }
        if (botDue.isEmpty() && snakes.size < MIN_SNAKES && tickNo % TICKS == 0L) spawnBot()
        // zu viele (Menschen sind dazugekommen): die kleinste Bot-Nudel geht
        if (snakes.size > MIN_SNAKES && tickNo % (4 * TICKS) == 0L) {
            snakes.values.filter { it.bot }.minByOrNull { it.length }?.let { bot ->
                kill(bot, LEFT)
                botDue.clear()
            }
        }
    }

    private fun spawnBot() {
        val used = snakes.values.map { it.name }.toSet()
        val name = BOT_NAMES.filter { it !in used }.randomOrNull(random) ?: "Nudel ${random.nextInt(100, 1000)}"
        spawn(name, random.nextInt(SKINS), bot = true)
    }

    private fun refillFood(all: Boolean = false) {
        if (all || tickNo % TICKS == 0L) {
            val gone = foods.values.filter { it.expires in 1..tickNo }
            for (food in gone) {
                foods.remove(food.id)
                foodGone += intArrayOf(food.id, 0)
            }
        }
        var budget = if (all) FOOD_TARGET else 5
        while (budget-- > 0 && foods.size < FOOD_TARGET) {
            val distance = sqrt(random.nextDouble()) * (RADIUS - 40)
            val direction = random.nextDouble() * TAU
            val value = if (random.nextInt(12) == 0) 9 else 4 + random.nextInt(3)
            addFood(cos(direction) * distance, sin(direction) * distance, random.nextInt(SKINS), value, 0)
        }
    }

    private fun addFood(x: Double, y: Double, color: Int, value: Int, expires: Long) {
        if (foods.size >= MAX_FOOD) return
        val food = Food(nextFood++, x, y, color, value, expires)
        foods[food.id] = food
        foodAdded += food
    }

    private fun dropIdle() {
        if (tickNo % TICKS != 0L) return
        val now = clock()
        for (client in clients.values.toList()) {
            val tooSlow = client.queue.size > MAX_QUEUE
            val idle = client.snake == null && now - client.activeAt > IDLE_MS
            if (!tooSlow && !idle) continue
            client.queue.clear()
            client.queue.offer("{\"t\":\"bye\",\"why\":\"${if (idle) "idle" else "slow"}\"}")
            clients.remove(client.token)
            client.closed = true
            client.snake?.let { kill(it, LEFT) }
        }
    }

    // ---------- Bots ----------

    private fun think(bot: Snake) {
        val r = bot.radius
        // was in der Nähe im Weg ist
        val near = ArrayList<DoubleArray>()
        for (other in snakes.values) {
            if (other === bot) continue
            val body = other.body
            val reach = 230 + other.radius
            for (i in body.start until body.end) {
                val dx = body.xs[i] - bot.x
                if (dx > reach || dx < -reach) continue
                val dy = body.ys[i] - bot.y
                if (dy > reach || dy < -reach) continue
                near += doubleArrayOf(body.xs[i], body.ys[i], other.radius)
            }
        }
        var desired: Double
        var tasty = false
        if (hypot(bot.x, bot.y) > RADIUS - 380) {
            desired = atan2(-bot.y, -bot.x)
        } else {
            var best: Food? = null
            var bestWorth = 0.0
            for (food in foods.values) {
                val dx = food.x - bot.x
                val dy = food.y - bot.y
                if (abs(dx) > 420 || abs(dy) > 420) continue
                val worth = food.value / (hypot(dx, dy) + 50)
                if (worth > bestWorth) {
                    bestWorth = worth
                    best = food
                }
            }
            if (best != null) {
                desired = atan2(best.y - bot.y, best.x - bot.x)
                tasty = best.value >= DEATH_FOOD_VALUE
            } else {
                bot.wander += (random.nextDouble() - 0.5) * 0.5
                desired = bot.wander
            }
        }
        var dodging = false
        if (random.nextDouble() < bot.skill + 0.42) { // schwächere Bots übersehen ab und zu etwas
            for (offset in DODGE) {
                val direction = desired + offset
                if (clear(bot, direction, near, r)) {
                    dodging = offset != 0.0
                    desired = direction
                    break
                }
            }
        }
        bot.target = desired
        bot.wander = desired
        if (tasty && !dodging && bot.length > 260 && random.nextInt(4) == 0) bot.boostUntil = tickNo + TICKS
        if (dodging && abs(desired - bot.angle) > 1.2) bot.boostUntil = 0
        bot.boost = tickNo < bot.boostUntil
    }

    private fun clear(bot: Snake, direction: Double, near: List<DoubleArray>, r: Double): Boolean {
        val cx = cos(direction)
        val cy = sin(direction)
        for (distance in PROBES) {
            val px = bot.x + cx * distance
            val py = bot.y + cy * distance
            if (hypot(px, py) > RADIUS - 70) return false
            for (point in near) {
                val limit = point[2] + r + 14
                val dx = point[0] - px
                val dy = point[1] - py
                if (dx * dx + dy * dy < limit * limit) return false
            }
        }
        return true
    }

    // ---------- Nachrichten ----------

    private fun initMessage(client: Client): String {
        val out = StringBuilder(64 * 1024)
        out.append("{\"t\":\"init\",\"k\":\"").append(client.token).append("\",\"r\":").append(RADIUS.toInt())
        out.append(",\"tick\":").append(TICK_MS).append(",\"snakes\":[")
        var first = true
        for (snake in snakes.values) {
            if (!first) out.append(',')
            first = false
            out.append('[').append(snake.id).append(',').append(JsonPrimitive(snake.name)).append(',').append(snake.skin)
            out.append(',').append(if (snake.bot) 1 else 0).append(',').append(snake.length.roundToInt())
            out.append(',').append(if (snake.boosting) 1 else 0).append(",[")
            val body = snake.body
            for (i in body.start until body.end) {
                if (i > body.start) out.append(',')
                out.append(body.xs[i].roundToInt()).append(',').append(body.ys[i].roundToInt())
            }
            out.append("]]")
        }
        out.append("],\"food\":[")
        appendFood(out, foods.values)
        out.append("],\"top\":").append(scoresJson()).append('}')
        return out.toString()
    }

    private fun appendFood(out: StringBuilder, list: Collection<Food>) {
        var first = true
        for (food in list) {
            if (!first) out.append(',')
            first = false
            out.append(food.id).append(',').append(food.x.roundToInt()).append(',').append(food.y.roundToInt())
            out.append(',').append(food.color).append(',').append(food.value)
        }
    }

    private fun broadcast() {
        val out = StringBuilder(2048)
        out.append("{\"t\":\"s\"")
        if (born.isNotEmpty()) {
            out.append(",\"n\":[")
            born.forEachIndexed { index, snake ->
                if (index > 0) out.append(',')
                out.append('[').append(snake.id).append(',').append(JsonPrimitive(snake.name)).append(',').append(snake.skin)
                out.append(',').append(if (snake.bot) 1 else 0).append(']')
            }
            out.append(']')
        }
        out.append(",\"s\":[")
        var first = true
        for (snake in snakes.values) {
            if (!first) out.append(',')
            first = false
            out.append(snake.id).append(',').append(snake.x.roundToInt()).append(',').append(snake.y.roundToInt())
            out.append(',').append(snake.length.roundToInt()).append(',').append(if (snake.boosting) 1 else 0)
        }
        out.append(']')
        if (dead.isNotEmpty()) {
            out.append(",\"d\":[")
            dead.forEachIndexed { index, pair ->
                if (index > 0) out.append(',')
                out.append(pair[0]).append(',').append(pair[1])
            }
            out.append(']')
        }
        if (foodGone.isNotEmpty()) {
            out.append(",\"fr\":[")
            foodGone.forEachIndexed { index, pair ->
                if (index > 0) out.append(',')
                out.append(pair[0]).append(',').append(pair[1])
            }
            out.append(']')
        }
        if (foodAdded.isNotEmpty()) {
            out.append(",\"fa\":[")
            appendFood(out, foodAdded)
            out.append(']')
        }
        if (scoresChanged) out.append(",\"top\":").append(scoresJson())
        out.append('}')
        born.clear()
        dead.clear()
        foodGone.clear()
        foodAdded.clear()
        scoresChanged = false
        val message = out.toString()
        for (client in clients.values) client.queue.offer(message)
    }

    // ---------- Bestenliste ----------

    private fun record(name: String, score: Int) {
        if (score < MIN_SCORE) return
        val key = name.lowercase()
        val old = scores.firstOrNull { it.name.lowercase() == key }
        if (old != null && old.score >= score) return
        val next = (scores.filter { it !== old } + Score(name, score, clock())).sortedByDescending { it.score }.take(TOP)
        if (next.none { it.name.lowercase() == key && it.score == score }) return
        scores = next
        scoresChanged = true
        val file = scoresFile ?: return
        try {
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, JsonArray(next.map { obj("name" to it.name, "score" to it.score, "at" to it.at) }).toString())
        } catch (e: Exception) {
            log.warn("Nudelsalat: Bestenliste nicht gespeichert: {}", e.message)
        }
    }

    private fun loadScores(): List<Score> {
        val file = scoresFile?.takeIf { Files.isRegularFile(it) } ?: return emptyList()
        return try {
            (Json.parseToJsonElement(Files.readString(file)) as JsonArray).mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.let { cleanName(it) }?.ifEmpty { null } ?: return@mapNotNull null
                val score = (item["score"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                Score(name, score, (item["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0)
            }.sortedByDescending { it.score }.take(TOP)
        } catch (e: Exception) {
            log.warn("Nudelsalat: Bestenliste nicht lesbar: {}", e.message)
            emptyList()
        }
    }

    private fun scoresJson(): JsonArray = JsonArray(scores.map { obj("name" to it.name, "score" to it.score) })

    @Synchronized
    fun topScores(): List<Score> = scores

    // ---------- für Tests ----------

    @Synchronized internal fun snakeOf(client: Client): Snake? = client.snake
    @Synchronized internal fun allSnakes(): List<Snake> = snakes.values.toList()
    @Synchronized internal fun foodCount(): Int = foods.size

    private fun jitter(amount: Double) = (random.nextDouble() - 0.5) * 2 * amount

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun notAllowed(allowed: String) = WebResponse(405, body = bytes(obj("error" to "Nur $allowed")), headers = listOf("Allow" to allowed, NO_STORE))

    companion object {
        const val PATH = "/api/nudelsalat"
        const val STREAM_PATH = "$PATH/stream"
        const val MAX_CLIENTS = 40
        const val MAX_PER_IP = 8

        const val TICK_MS = 50L
        const val TICKS = 20 // Ticks je Sekunde
        const val DT = TICK_MS / 1000.0
        const val RADIUS = 2400.0
        const val SKINS = 12
        const val START_LENGTH = 160.0
        const val MAX_LENGTH = 9000.0
        const val MIN_BOOST_LENGTH = 190.0
        const val SPEED = 170.0
        const val BOOST_SPEED = 340.0
        const val BOOST_COST = 16.0
        const val TURN_RATE = 4.0
        const val EAT_REACH = 16.0
        const val DEATH_FOOD_EVERY = 26.0
        const val DEATH_FOOD_VALUE = 14
        const val FOOD_TARGET = 800
        const val MAX_FOOD = 3000
        const val MIN_SNAKES = 14
        const val TOP = 10
        const val MIN_SCORE = 120
        const val MAX_QUEUE = 200
        const val IDLE_MS = 5 * 60 * 1000L
        const val WALL = 0
        const val LEFT = -1
        private const val TAU = 2 * PI
        private val NO_STORE = "Cache-Control" to "no-store"
        private val DODGE = doubleArrayOf(0.0, 0.5, -0.5, 1.0, -1.0, 1.7, -1.7, 2.6, -2.6)
        private val PROBES = doubleArrayOf(45.0, 90.0, 140.0)

        fun radiusOf(length: Double): Double = 8 + min(14.0, sqrt(length) / 5.5)

        fun scoreOf(length: Double): Int = (length / 2).roundToInt()

        /** Spitzname: ohne Steuerzeichen, einfache Leerzeichen, höchstens 16 Zeichen. */
        fun cleanName(raw: String): String =
            raw.filter { !it.isISOControl() && it != '​' && it != ' ' && it != ' ' && it != '﻿' }
                .replace(Regex("\\s+"), " ").trim().take(16).trim()

        val BOT_NAMES = listOf(
            "Skill Issue", "Lag war's", "Emergency Meeting", "Ich war in Elektro", "Trust me bro", "AFK Andi", "NPC #37",
            "Opa Günther", "Mettbrötchen", "Chantal", "Kevin", "404 Hirn", "Spaghetti-Code", "Kein Impostor", "Erster!",
            "WLAN-Kabel", "Digga was", "Nudel des Todes", "Al Dente", "Penne Lane", "Der Rand war's", "Tutorial-Boss",
            "sus", "Maggi fix", "Ohne Witz", "Bitte nicht", "Nur mal gucken", "Gabel-Opfer", "Carbonara-Karl", "Ping 999",
        )
    }
}
