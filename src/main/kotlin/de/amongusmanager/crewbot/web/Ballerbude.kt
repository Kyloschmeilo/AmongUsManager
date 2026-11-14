/*
 * Ballerbude: Mehrspieler-Arena der Stats-Website (14.11.2026, Kilian: Panzer-Arena mit Level-Ups, Klassen, Bestenliste, Bots).
 * Eigenes Spiel mit eigenem Aussehen (Kulleraugen-Bots ballern auf Snacks), Seite: /seiten/ballerbude.html.
 *
 * Der Server rechnet alles (40 Schritte/s), die Browser zeigen nur an und schicken Eingaben:
 *   GET  /api/arena              Status (Spieler, Bots)
 *   POST /api/arena/join         {name} → Token, Welt, Klassen, Werte
 *   GET  /api/arena/stream?t=    Server-Sent Events: 20 Schnappschüsse/s (nur was der Spieler sieht)
 *   POST /api/arena/input?t=     {m:[x,y], a:Winkel, f:0|1, u:Wert, c:Klasse, r:1 (neu starten)}
 *   POST /api/arena/leave?t=
 * Kein WebSocket (der JDK-HttpServer kann das nicht) – wie beim Voice-Chat SSE + POST.
 * Ohne Anmeldung, nichts wird gespeichert; ohne Spieler steht die Welt still.
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Ein Rohr: Winkel zur Blickrichtung, seitlicher Versatz/Länge/Breite in Körper-Radien, Schuss-Werte als Faktoren. */
class Barrel(
    val angle: Double = 0.0,
    val side: Double = 0.0,
    val len: Double = 1.9,
    val width: Double = 0.8,
    /** Wann im Nachlade-Takt das Rohr schießt (0–1). */
    val delay: Double = 0.0,
    val spread: Double = 0.05,
    val dmg: Double = 1.0,
    val speed: Double = 1.0,
    val size: Double = 1.0,
    val life: Double = 1.0,
    /** Kugeln je Schuss (Schrot). */
    val count: Int = 1,
    /** Durchschlägt so viele Ziele zusätzlich. */
    val pierce: Int = 0,
    /** Schießt nur jeden n-ten Takt. */
    val every: Int = 1,
    val mine: Boolean = false,
)

class TankClass(
    val id: Int,
    val name: String,
    val desc: String,
    /** Ab diesem Level wählbar. */
    val level: Int,
    /** Vorgänger-Klasse, -1 = Startklasse. */
    val from: Int,
    val reload: Double = 1.0,
    val fov: Double = 1.0,
    val barrels: List<Barrel>,
)

object BallerRules {
    const val WORLD = 3600.0
    const val TICKS = 40
    const val DT = 1.0 / TICKS
    const val MAX_LEVEL = 30
    const val STAT_MAX = 7
    const val SNACKS = 170
    const val VIEW_X = 1050.0
    const val VIEW_Y = 650.0

    val STATS = listOf("Regeneration", "Dickes Fell", "Rammbock", "Kugeltempo", "Wumms", "Nachladen", "Flitzen")

    val CLASSES: List<TankClass> = listOf(
        TankClass(0, "Knirps", "Ein Rohr, ein Traum.", 1, -1, barrels = listOf(Barrel())),
        TankClass(
            1, "Doppelwumms", "Zwei Rohre. Doppelt hält besser.", 10, 0,
            barrels = listOf(Barrel(side = 0.5, width = 0.7, dmg = 0.7), Barrel(side = -0.5, width = 0.7, dmg = 0.7, delay = 0.5)),
        ),
        TankClass(
            2, "Camper", "Sitzt im Busch und trifft trotzdem.", 10, 0, reload = 1.6, fov = 1.25,
            barrels = listOf(Barrel(len = 2.5, spread = 0.01, dmg = 1.7, speed = 1.6, life = 1.3)),
        ),
        TankClass(
            3, "Sprühdose", "Zielen ist was für Leute mit Zeit.", 10, 0, reload = 0.5,
            barrels = listOf(Barrel(len = 1.8, width = 1.1, spread = 0.28, dmg = 0.6)),
        ),
        TankClass(4, "Rundumschlag", "Vorne und hinten. Niemand schleicht sich an.", 10, 0, barrels = listOf(Barrel(), Barrel(angle = PI))),
        TankClass(
            5, "Dreizack", "Drei Rohre im Fächer.", 20, 1,
            barrels = listOf(Barrel(angle = -0.32, dmg = 0.75, width = 0.7), Barrel(angle = 0.32, dmg = 0.75, width = 0.7), Barrel(dmg = 0.75, len = 2.1)),
        ),
        TankClass(
            6, "Bleiwand", "Vier Rohre, null Geduld.", 20, 1, reload = 0.9,
            barrels = listOf(
                Barrel(side = 0.75, width = 0.45, dmg = 0.5, size = 0.8), Barrel(side = -0.75, width = 0.45, dmg = 0.5, size = 0.8, delay = 0.5),
                Barrel(side = 0.25, width = 0.45, dmg = 0.5, size = 0.8, len = 2.1, delay = 0.25),
                Barrel(side = -0.25, width = 0.45, dmg = 0.5, size = 0.8, len = 2.1, delay = 0.75),
            ),
        ),
        TankClass(
            7, "Railgun-Rüdiger", "Ein Schuss, drei Ziele, lange Pause.", 20, 2, reload = 2.4, fov = 1.4,
            barrels = listOf(Barrel(len = 3.0, width = 0.6, spread = 0.0, dmg = 2.6, speed = 2.2, life = 1.2, pierce = 3)),
        ),
        TankClass(
            8, "360-NoScope", "Camper, aber mit Energy-Drink.", 20, 2, reload = 1.0, fov = 1.3,
            barrels = listOf(Barrel(len = 2.3, width = 0.7, spread = 0.03, dmg = 1.3, speed = 1.7, life = 1.2)),
        ),
        TankClass(
            9, "Gartenschlauch", "Irgendwas trifft immer.", 20, 3, reload = 0.3,
            barrels = listOf(Barrel(len = 2.0, width = 1.0, spread = 0.4, dmg = 0.45, size = 0.8)),
        ),
        TankClass(
            10, "Schrotti", "Aus der Nähe ein Problem.", 20, 3, reload = 1.5,
            barrels = listOf(Barrel(len = 1.5, width = 1.3, spread = 0.55, dmg = 0.6, speed = 1.2, life = 0.5, size = 0.8, count = 7)),
        ),
        TankClass(
            11, "Kreisverkehr", "Acht Rohre. Blinker braucht hier keiner.", 20, 4,
            barrels = (0 until 8).map { Barrel(angle = it * PI / 4, width = 0.6, len = 1.7, dmg = 0.75, delay = if (it % 2 == 0) 0.0 else 0.5) },
        ),
        TankClass(
            12, "Minen-Manni", "Legt hinten Überraschungen ab.", 20, 4,
            barrels = listOf(
                Barrel(),
                Barrel(angle = PI, len = 1.4, width = 1.3, spread = 0.2, dmg = 4.0, speed = 0.12, size = 1.9, life = 9.0, every = 3, delay = 0.5, mine = true),
            ),
        ),
    )

    /** Snacks: Name, Radius, Leben, XP, Kontaktschaden je Sekunde. */
    class Snack(val name: String, val r: Double, val hp: Double, val xp: Int, val dps: Double)

    val SNACK_TYPES = listOf(
        Snack("Keks", 16.0, 10.0, 10, 8.0),
        Snack("Käse", 21.0, 30.0, 25, 12.0),
        Snack("Donut", 30.0, 110.0, 120, 20.0),
        Snack("Riesenpizza", 95.0, 2500.0, 2500, 40.0),
    )

    val BOT_NAMES = listOf(
        "Bot Bernd", "xX_Kevin_Xx", "Günther69", "Sus-anne", "Ragequit-Ralf", "Lag-Lars", "NoScope-Norbert", "AFK-Achim",
        "Tryhard-Torben", "Opa Heinz", "Chantal", "1 Bier bitte", "Hans im Pech", "Uwe (lädt…)", "Mett-Igel", "Der Dieter",
    )

    /** XP von Level [level] bis zum nächsten. */
    fun need(level: Int): Int = (25 + 9 * (level - 1).toDouble().pow(1.5)).toInt()

    /** Gesamte XP, die man für [level] braucht. */
    val TOTAL: IntArray = IntArray(MAX_LEVEL + 1).also { total -> for (level in 2..MAX_LEVEL) total[level] = total[level - 1] + need(level - 1) }

    fun levelOf(score: Int): Int {
        var level = 1
        while (level < MAX_LEVEL && score >= TOTAL[level + 1]) level++
        return level
    }

    fun choices(clazz: Int, level: Int): List<TankClass> = CLASSES.filter { it.from == clazz && it.level <= level }

    fun cleanName(raw: String): String {
        val text = raw.filter { !it.isISOControl() && it != '<' && it != '>' }.trim().replace(Regex("\\s+"), " ")
        return text.take(16).trim().ifEmpty { "Namenlos" }
    }
}

class BallerWorld(private val random: Random = Random.Default) {
    class Tank(val id: Int, var name: String, val bot: Boolean, val hue: Int) {
        var x = 0.0
        var y = 0.0
        var vx = 0.0
        var vy = 0.0
        var angle = 0.0
        var hp = 50.0
        var level = 1
        var score = 0
        var points = 0
        val stats = IntArray(BallerRules.STATS.size)
        var clazz = 0
        var kills = 0
        var shots = 0
        var protect = 3.0
        var sinceHit = 99.0
        var alive = 0.0

        // Eingaben
        var mx = 0.0
        var my = 0.0
        var fire = false

        // Schießen
        var clock = 0.0
        var fired = IntArray(1)

        // Bots
        var wanderX = 0.0
        var wanderY = 0.0
        var strafe = 1.0
        var think = 0.0
        var spend = 0.0
        var lastHitBy = 0

        val def: TankClass get() = BallerRules.CLASSES[clazz]
        val r: Double get() = 22 + (level - 1) * 0.5
        val maxHp: Double get() = 50.0 + 2 * (level - 1) + 20 * stats[1]
        val bodyDps: Double get() = 30.0 + 10 * stats[2]
        val bulletSpeed: Double get() = 420.0 + 45 * stats[3]
        val bulletDamage: Double get() = 7.0 + 3 * stats[4]
        val reload: Double get() = 0.55 * 0.9.pow(stats[5]) * def.reload
        val moveSpeed: Double get() = 230.0 + 14 * stats[6] - level * 1.5
    }

    class Bullet(val id: Int, val owner: Tank, var x: Double, var y: Double, var vx: Double, var vy: Double, val r: Double, val damage: Double, var life: Double, var pierce: Int, val mine: Boolean) {
        val hit = ArrayList<Int>(0)
    }

    class Snack(val id: Int, val type: Int, var x: Double, var y: Double) {
        val def = BallerRules.SNACK_TYPES[type]
        var hp = def.hp
        var vx = 0.0
        var vy = 0.0
    }

    class Kill(val seq: Int, val killer: String, val victim: String, val killerHue: Int, val victimHue: Int)

    /** Ergebnis eines Todes, für den Spieler dahinter. */
    class Death(val tank: Tank, val by: String)

    val tanks = ArrayList<Tank>()
    val bullets = ArrayList<Bullet>()
    val snacks = ArrayList<Snack>()
    val kills = ArrayDeque<Kill>()
    val deaths = ArrayList<Death>()
    var tick = 0L
        private set
    private var nextId = 1
    private var killSeq = 0
    private var pizzaIn = 20.0
    private var hueCounter = random.nextInt(8)

    fun reset() {
        tanks.clear(); bullets.clear(); snacks.clear(); kills.clear(); deaths.clear()
        pizzaIn = 20.0
    }

    fun spawnTank(name: String, bot: Boolean, level: Int = 1): Tank {
        val tank = Tank(nextId++, name, bot, hueCounter++ % 8)
        tank.level = level.coerceIn(1, BallerRules.MAX_LEVEL)
        tank.score = BallerRules.TOTAL[tank.level]
        tank.points = tank.level - 1
        tank.hp = tank.maxHp
        // freier Platz: weit weg von anderen
        var best = 0.0
        repeat(12) {
            val x = 200 + random.nextDouble() * (BallerRules.WORLD - 400)
            val y = 200 + random.nextDouble() * (BallerRules.WORLD - 400)
            val near = tanks.minOfOrNull { hypot(it.x - x, it.y - y) } ?: 9999.0
            if (near > best) { best = near; tank.x = x; tank.y = y }
        }
        tank.angle = random.nextDouble() * 2 * PI
        tank.wanderX = tank.x
        tank.wanderY = tank.y
        tanks += tank
        return tank
    }

    fun removeTank(tank: Tank) {
        tanks.remove(tank)
        bullets.removeAll { it.owner === tank }
    }

    fun upgrade(tank: Tank, stat: Int): Boolean {
        if (stat !in tank.stats.indices || tank.points <= 0 || tank.stats[stat] >= BallerRules.STAT_MAX) return false
        val before = tank.maxHp
        tank.stats[stat]++
        tank.points--
        if (stat == 1) tank.hp += tank.maxHp - before
        return true
    }

    fun choose(tank: Tank, clazz: Int): Boolean {
        if (BallerRules.choices(tank.clazz, tank.level).none { it.id == clazz }) return false
        tank.clazz = clazz
        tank.fired = IntArray(tank.def.barrels.size)
        tank.clock = 0.0
        return true
    }

    fun addScore(tank: Tank, xp: Int) {
        tank.score += xp
        val level = BallerRules.levelOf(tank.score)
        if (level > tank.level) {
            val before = tank.maxHp
            tank.points += level - tank.level
            tank.level = level
            tank.hp += tank.maxHp - before
        }
    }

    fun spawnSnack(type: Int = -1, x: Double = -1.0, y: Double = -1.0): Snack {
        val mid = BallerRules.WORLD / 2
        var sx = x
        var sy = y
        if (sx < 0) {
            sx = 60 + random.nextDouble() * (BallerRules.WORLD - 120)
            sy = 60 + random.nextDouble() * (BallerRules.WORLD - 120)
        }
        val central = hypot(sx - mid, sy - mid) < 600
        val kind = if (type >= 0) type else {
            val roll = random.nextDouble()
            if (central) (if (roll < 0.3) 0 else if (roll < 0.6) 1 else 2) else (if (roll < 0.62) 0 else if (roll < 0.9) 1 else 2)
        }
        val snack = Snack(nextId++, kind, sx, sy)
        snack.vx = (random.nextDouble() - 0.5) * 8
        snack.vy = (random.nextDouble() - 0.5) * 8
        snacks += snack
        return snack
    }

    // ---------- ein Schritt ----------

    fun step(dt: Double = BallerRules.DT) {
        tick++
        while (snacks.size < BallerRules.SNACKS) spawnSnack()
        pizzaIn -= dt
        if (pizzaIn <= 0) {
            pizzaIn = 90.0
            if (snacks.none { it.type == 3 }) spawnSnack(3, BallerRules.WORLD / 2, BallerRules.WORLD / 2)
        }
        for (tank in tanks) if (tank.bot) think(tank, dt)
        for (tank in tanks) move(tank, dt)
        for (tank in ArrayList(tanks)) shoot(tank, dt)
        moveBullets(dt)
        moveSnacks(dt)
        collide(dt)
    }

    private fun move(tank: Tank, dt: Double) {
        tank.alive += dt
        tank.sinceHit += dt
        if (tank.protect > 0) tank.protect -= dt
        val length = hypot(tank.mx, tank.my)
        val scale = if (length > 1) 1 / length else 1.0
        val ease = min(1.0, 7 * dt)
        tank.vx += (tank.mx * scale * tank.moveSpeed - tank.vx) * ease
        tank.vy += (tank.my * scale * tank.moveSpeed - tank.vy) * ease
        tank.x = (tank.x + tank.vx * dt).coerceIn(tank.r, BallerRules.WORLD - tank.r)
        tank.y = (tank.y + tank.vy * dt).coerceIn(tank.r, BallerRules.WORLD - tank.r)
        val regen = (0.005 + 0.005 * tank.stats[0]) + (if (tank.sinceHit > 6) 0.08 else 0.0)
        tank.hp = min(tank.maxHp, tank.hp + tank.maxHp * regen * dt)
    }

    private fun shoot(tank: Tank, dt: Double) {
        val def = tank.def
        val reload = tank.reload
        if (tank.fired.size != def.barrels.size) tank.fired = IntArray(def.barrels.size)
        // nächster Schuss-Zeitpunkt über alle Rohre: ohne Feuer läuft die Uhr nur bis dorthin (erster Klick schießt sofort)
        var next = Double.MAX_VALUE
        for ((i, barrel) in def.barrels.withIndex()) next = min(next, (tank.fired[i] + barrel.delay) * reload * barrel.every)
        if (!tank.fire) {
            tank.clock = min(next, tank.clock + dt)
            return
        }
        tank.clock += dt
        tank.protect = 0.0
        for ((i, barrel) in def.barrels.withIndex()) {
            val period = reload * barrel.every
            var guard = 0
            while (tank.clock >= (tank.fired[i] + barrel.delay) * period && guard++ < 3) {
                tank.fired[i]++
                fire(tank, barrel)
            }
            // nach langer Pause nicht nachholen
            if (tank.clock > (tank.fired[i] + barrel.delay) * period) tank.fired[i] = (tank.clock / period).toInt() + 1
        }
    }

    private fun fire(tank: Tank, barrel: Barrel) {
        if (barrel.mine && bullets.count { it.owner === tank && it.mine } >= 8) return
        tank.shots++
        val base = tank.angle + barrel.angle
        val r = tank.r
        val ox = tank.x + cos(base) * barrel.len * r - sin(base) * barrel.side * r
        val oy = tank.y + sin(base) * barrel.len * r + cos(base) * barrel.side * r
        repeat(barrel.count) {
            val angle = base + (random.nextDouble() - 0.5) * barrel.spread
            val speed = tank.bulletSpeed * barrel.speed * (if (barrel.count > 1) 0.8 + random.nextDouble() * 0.4 else 1.0)
            bullets += Bullet(
                nextId++, tank, ox, oy, cos(angle) * speed + tank.vx * 0.3, sin(angle) * speed + tank.vy * 0.3,
                (8 + tank.level * 0.12) * barrel.size, tank.bulletDamage * barrel.dmg, 1.6 * barrel.life, barrel.pierce, barrel.mine,
            )
            val kick = 14 * barrel.dmg * barrel.size / barrel.count * (if (barrel.mine) 0.2 else 1.0)
            tank.vx -= cos(angle) * kick
            tank.vy -= sin(angle) * kick
        }
    }

    private fun moveBullets(dt: Double) {
        val iterator = bullets.iterator()
        while (iterator.hasNext()) {
            val bullet = iterator.next()
            bullet.life -= dt
            if (bullet.mine) { bullet.vx *= 1 - 3 * dt; bullet.vy *= 1 - 3 * dt }
            bullet.x += bullet.vx * dt
            bullet.y += bullet.vy * dt
            if (bullet.life <= 0 || bullet.x < -50 || bullet.y < -50 || bullet.x > BallerRules.WORLD + 50 || bullet.y > BallerRules.WORLD + 50) iterator.remove()
        }
    }

    private fun moveSnacks(dt: Double) {
        for (snack in snacks) {
            snack.x += snack.vx * dt
            snack.y += snack.vy * dt
            val speed = hypot(snack.vx, snack.vy)
            if (speed > 6) { snack.vx *= 1 - 2.5 * dt; snack.vy *= 1 - 2.5 * dt }
            val r = snack.def.r
            if (snack.x < r) { snack.x = r; snack.vx = abs(snack.vx) }
            if (snack.y < r) { snack.y = r; snack.vy = abs(snack.vy) }
            if (snack.x > BallerRules.WORLD - r) { snack.x = BallerRules.WORLD - r; snack.vx = -abs(snack.vx) }
            if (snack.y > BallerRules.WORLD - r) { snack.y = BallerRules.WORLD - r; snack.vy = -abs(snack.vy) }
        }
    }

    private fun collide(dt: Double) {
        // Kugeln
        val bulletIterator = bullets.iterator()
        while (bulletIterator.hasNext()) {
            val bullet = bulletIterator.next()
            var gone = false
            for (snack in snacks) {
                if (snack.hp <= 0) continue
                val reach = bullet.r + snack.def.r
                val dx = snack.x - bullet.x
                val dy = snack.y - bullet.y
                if (abs(dx) > reach || abs(dy) > reach || dx * dx + dy * dy > reach * reach || snack.id in bullet.hit) continue
                snack.hp -= bullet.damage
                val push = 60.0 / (snack.def.r / 16).pow(2)
                snack.vx += bullet.vx / 400 * push
                snack.vy += bullet.vy / 400 * push
                if (snack.hp <= 0 && bullet.owner in tanks) addScore(bullet.owner, snack.def.xp)
                if (bullet.pierce > 0) { bullet.pierce--; bullet.hit += snack.id } else { gone = true; break }
            }
            if (!gone) {
                for (tank in tanks) {
                    if (tank === bullet.owner || tank.protect > 0 || tank.hp <= 0) continue
                    val reach = bullet.r + tank.r
                    val dx = tank.x - bullet.x
                    val dy = tank.y - bullet.y
                    if (abs(dx) > reach || abs(dy) > reach || dx * dx + dy * dy > reach * reach || tank.id in bullet.hit) continue
                    hurt(tank, bullet.damage, bullet.owner)
                    tank.vx += bullet.vx * 0.04
                    tank.vy += bullet.vy * 0.04
                    if (bullet.pierce > 0) { bullet.pierce--; bullet.hit += tank.id } else { gone = true; break }
                }
            }
            if (gone) bulletIterator.remove()
        }
        // Körper gegen Snacks
        for (tank in tanks) {
            if (tank.hp <= 0) continue
            for (snack in snacks) {
                if (snack.hp <= 0) continue
                val reach = tank.r + snack.def.r
                val dx = snack.x - tank.x
                val dy = snack.y - tank.y
                if (abs(dx) > reach || abs(dy) > reach) continue
                val dist = hypot(dx, dy)
                if (dist > reach) continue
                val nx = if (dist > 0.01) dx / dist else 1.0
                val ny = if (dist > 0.01) dy / dist else 0.0
                val overlap = reach - dist
                val heavy = snack.type == 3
                if (heavy) { tank.x -= nx * overlap; tank.y -= ny * overlap } else { snack.x += nx * overlap; snack.y += ny * overlap; snack.vx += nx * 40; snack.vy += ny * 40 }
                snack.hp -= tank.bodyDps * dt
                if (tank.protect <= 0) hurt(tank, snack.def.dps * dt, null)
                if (snack.hp <= 0) addScore(tank, snack.def.xp)
            }
        }
        // Körper gegen Körper
        for (i in tanks.indices) {
            val a = tanks[i]
            for (j in i + 1 until tanks.size) {
                val b = tanks[j]
                val reach = a.r + b.r
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (abs(dx) > reach || abs(dy) > reach) continue
                val dist = hypot(dx, dy)
                if (dist > reach) continue
                val nx = if (dist > 0.01) dx / dist else 1.0
                val ny = if (dist > 0.01) dy / dist else 0.0
                val overlap = (reach - dist) / 2
                a.x -= nx * overlap; a.y -= ny * overlap; b.x += nx * overlap; b.y += ny * overlap
                a.vx -= nx * 30; a.vy -= ny * 30; b.vx += nx * 30; b.vy += ny * 30
                if (a.protect > 0 || b.protect > 0 || a.hp <= 0 || b.hp <= 0) continue
                hurt(a, b.bodyDps * 2 * dt, b)
                hurt(b, a.bodyDps * 2 * dt, a)
            }
        }
        snacks.removeAll { it.hp <= 0 }
        // Tote
        val dead = tanks.filter { it.hp <= 0 }
        for (tank in dead) {
            val killer = tanks.firstOrNull { it.id == tank.lastHitBy && it.hp > 0 }
            if (killer != null) {
                killer.kills++
                addScore(killer, 100 + min(tank.score / 2, 6000))
            }
            val by = killer?.name ?: "einem Snack"
            kills.addLast(Kill(++killSeq, by, tank.name, killer?.hue ?: -1, tank.hue))
            while (kills.size > 6) kills.removeFirst()
            deaths += Death(tank, by)
            removeTank(tank)
        }
    }

    private fun hurt(tank: Tank, damage: Double, by: Tank?) {
        tank.hp -= damage
        tank.sinceHit = 0.0
        if (by != null) tank.lastHitBy = by.id else if (tank.hp <= 0 && tank.sinceHit > 0) tank.lastHitBy = 0
    }

    // ---------- Bots ----------

    private fun think(bot: Tank, dt: Double) {
        if (bot.points > 0) {
            bot.spend -= dt
            if (bot.spend <= 0) {
                bot.spend = 0.4
                // jeder Bot hat Vorlieben (aus der id), sonst Zufall
                val favorite = intArrayOf(4, 5, 3, 1, 6, 0, 2)[(bot.id + random.nextInt(3)) % 7]
                if (!upgrade(bot, favorite)) upgrade(bot, random.nextInt(bot.stats.size))
            }
        }
        val options = BallerRules.choices(bot.clazz, bot.level)
        if (options.isNotEmpty()) choose(bot, options[random.nextInt(options.size)].id)

        bot.think -= dt
        if (bot.think <= 0) {
            bot.think = 2 + random.nextDouble() * 3
            bot.strafe = if (random.nextBoolean()) 1.0 else -1.0
            if (hypot(bot.wanderX - bot.x, bot.wanderY - bot.y) < 200 || random.nextInt(4) == 0) {
                bot.wanderX = 300 + random.nextDouble() * (BallerRules.WORLD - 600)
                bot.wanderY = 300 + random.nextDouble() * (BallerRules.WORLD - 600)
            }
        }
        var enemy: Tank? = null
        var enemyDist = 560.0
        for (other in tanks) {
            if (other === bot || other.protect > 0) continue
            val revenge = other.id == bot.lastHitBy && bot.sinceHit < 4
            // Neulinge (deutlich kleiner, erste Minute) lassen Bots in Ruhe, solange sie nicht angegriffen werden
            if (!revenge && !other.bot && other.alive < 60 && other.level + 4 < bot.level) continue
            val dist = hypot(other.x - bot.x, other.y - bot.y) - (if (revenge) 300 else 0)
            if (dist < enemyDist) { enemy = other; enemyDist = dist }
        }
        var mx = 0.0
        var my = 0.0
        var aimX = bot.x + cos(bot.angle)
        var aimY = bot.y + sin(bot.angle)
        var fire = false
        if (enemy != null) {
            val dx = enemy.x - bot.x
            val dy = enemy.y - bot.y
            val dist = max(1.0, hypot(dx, dy))
            val lead = dist / (bot.bulletSpeed * bot.def.barrels[0].speed)
            aimX = enemy.x + enemy.vx * lead
            aimY = enemy.y + enemy.vy * lead
            fire = true
            val brave = bot.hp / bot.maxHp > 0.35 || enemy.hp < bot.hp
            val toward = if (!brave) -1.0 else if (dist > 340) 1.0 else if (dist < 220) -1.0 else 0.0
            mx = dx / dist * toward - dy / dist * bot.strafe * 0.7
            my = dy / dist * toward + dx / dist * bot.strafe * 0.7
        } else {
            var snack: BallerWorld.Snack? = null
            var snackDist = 750.0
            for (candidate in snacks) {
                val dist = hypot(candidate.x - bot.x, candidate.y - bot.y) - candidate.def.r
                if (dist < snackDist && (candidate.type != 3 || bot.level >= 12)) { snack = candidate; snackDist = dist }
            }
            if (snack != null) {
                aimX = snack.x
                aimY = snack.y
                fire = snackDist < 520
                if (snackDist > 190) { mx = (snack.x - bot.x) / (snackDist + snack.def.r); my = (snack.y - bot.y) / (snackDist + snack.def.r) }
            } else {
                val dx = bot.wanderX - bot.x
                val dy = bot.wanderY - bot.y
                val dist = max(1.0, hypot(dx, dy))
                mx = dx / dist; my = dy / dist
                aimX = bot.wanderX; aimY = bot.wanderY
            }
        }
        // weg vom Rand
        if (bot.x < 160) mx += 1.0
        if (bot.y < 160) my += 1.0
        if (bot.x > BallerRules.WORLD - 160) mx -= 1.0
        if (bot.y > BallerRules.WORLD - 160) my -= 1.0
        bot.mx = mx
        bot.my = my
        bot.fire = fire
        // drehen mit begrenztem Tempo und etwas Zittern
        val want = atan2(aimY - bot.y, aimX - bot.x) + sin(tick * 0.07 + bot.id) * 0.1
        var diff = (want - bot.angle) % (2 * PI)
        if (diff > PI) diff -= 2 * PI
        if (diff < -PI) diff += 2 * PI
        bot.angle += diff.coerceIn(-5 * dt, 5 * dt)
    }
}

/** HTTP-Seite der Ballerbude: Spieler, Tokens, Schnappschüsse, Takt. */
class Ballerbude(
    private val random: Random = Random.Default,
    /** false = Tests treiben die Welt selbst mit [tick] an. */
    private val autoRun: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Player(val token: String, var name: String) {
        var tank: BallerWorld.Tank? = null
        var lastSeen = 0L
        var deadBy: String? = null
        var deadScore = 0
        var deadLevel = 1
        var deadKills = 0
        var deadTime = 0.0
        var viewX = BallerRules.WORLD / 2
        var viewY = BallerRules.WORLD / 2
        var fov = 1.0
        var stream = 0
        val queue = ArrayBlockingQueue<String>(6)
        @Volatile var gone = false
    }

    private val log = LoggerFactory.getLogger(Ballerbude::class.java)
    val world = BallerWorld(random)
    private val players = LinkedHashMap<String, Player>()
    private var loop: Thread? = null
    private val secure = SecureRandom()

    val playerCount: Int get() = synchronized(this) { players.size }

    fun handles(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    fun handle(request: WebRequest): WebResponse {
        val route = request.path.removePrefix(PATH).trim('/')
        return when {
            route == "" && (request.method == "GET" || request.method == "HEAD") -> synchronized(this) {
                json(200, obj("players" to players.size, "bots" to world.tanks.count { it.bot }, "max" to MAX_PLAYERS, "names" to players.values.map { it.name }))
            }
            request.method != "POST" -> json(405, obj("error" to "Nur POST"))
            route == "join" -> join(request)
            route == "input" -> input(request)
            route == "leave" -> {
                synchronized(this) { players[request.query["t"]]?.let { drop(it) } }
                json(200, obj("ok" to true))
            }
            else -> json(404, obj("error" to "Nicht gefunden"))
        }
    }

    private fun body(request: WebRequest): JsonObject? = try {
        Json.parseToJsonElement(request.body.toString(Charsets.UTF_8)) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun join(request: WebRequest): WebResponse {
        val data = body(request) ?: return json(400, obj("error" to "Kein JSON"))
        val name = BallerRules.cleanName((data["name"] as? JsonPrimitive)?.content ?: "")
        val player = synchronized(this) {
            if (players.size >= MAX_PLAYERS) return json(503, obj("error" to "Die Bude ist voll ($MAX_PLAYERS Spieler). Versuch es gleich nochmal."))
            if (players.isEmpty()) world.reset()
            val bytes = ByteArray(12).also { secure.nextBytes(it) }
            val created = Player(bytes.joinToString("") { "%02x".format(it) }, name)
            created.lastSeen = clock()
            created.tank = world.spawnTank(name, bot = false)
            players[created.token] = created
            balanceBots()
            startLoop()
            created
        }
        return json(
            200,
            obj(
                "token" to player.token, "id" to player.tank!!.id, "name" to player.name, "world" to BallerRules.WORLD.toInt(),
                "statMax" to BallerRules.STAT_MAX, "stats" to BallerRules.STATS, "maxLevel" to BallerRules.MAX_LEVEL,
                "snacks" to BallerRules.SNACK_TYPES.map { obj("name" to it.name, "r" to it.r, "xp" to it.xp) },
                "classes" to BallerRules.CLASSES.map { def ->
                    obj(
                        "id" to def.id, "name" to def.name, "desc" to def.desc, "level" to def.level, "from" to def.from,
                        "barrels" to def.barrels.map { listOf(it.angle, it.side, it.len, it.width, if (it.mine) 1 else 0) },
                    )
                },
            ),
        )
    }

    private fun input(request: WebRequest): WebResponse {
        val data = body(request) ?: return json(400, obj("error" to "Kein JSON"))
        synchronized(this) {
            val player = players[request.query["t"]] ?: return json(410, obj("error" to "Nicht mehr im Spiel", "gone" to true))
            player.lastSeen = clock()
            val tank = player.tank
            if (tank == null) {
                if (data["r"] != null) respawn(player)
                return json(200, obj("ok" to true))
            }
            (data["m"] as? JsonArray)?.takeIf { it.size == 2 }?.let { move ->
                tank.mx = (move[0].jsonPrimitive.doubleOrNull ?: 0.0).takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
                tank.my = (move[1].jsonPrimitive.doubleOrNull ?: 0.0).takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
            }
            (data["a"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.let { tank.angle = it }
            (data["f"] as? JsonPrimitive)?.intOrNull?.let { tank.fire = it != 0 }
            (data["u"] as? JsonPrimitive)?.intOrNull?.let { world.upgrade(tank, it) }
            (data["c"] as? JsonPrimitive)?.intOrNull?.let { world.choose(tank, it) }
        }
        return json(200, obj("ok" to true))
    }

    private fun respawn(player: Player) {
        val tank = world.spawnTank(player.name, bot = false, level = max(1, player.deadLevel / 2))
        player.tank = tank
        player.deadBy = null
    }

    private fun drop(player: Player) {
        players.remove(player.token)
        player.gone = true
        player.tank?.let { world.removeTank(it) }
        player.queue.clear()
        player.queue.offer(BYE)
        balanceBots()
    }

    private fun balanceBots() {
        val bots = world.tanks.filter { it.bot }
        val want = if (players.isEmpty()) 0 else (8 - players.size).coerceIn(MIN_BOTS, MAX_BOTS)
        if (bots.size > want) bots.sortedBy { it.score }.take(bots.size - want).forEach { world.removeTank(it) }
        repeat(want - bots.size) {
            val used = world.tanks.map { it.name }.toSet()
            val name = BallerRules.BOT_NAMES.filter { it !in used }.randomOrNull(random) ?: "Bot ${random.nextInt(99)}"
            world.spawnTank(name, bot = true, level = 1 + random.nextInt(6))
        }
    }

    // ---------- Takt ----------

    private fun startLoop() {
        if (!autoRun || loop?.isAlive == true) return
        loop = Thread({
            var next = System.nanoTime()
            val stepNanos = 1_000_000_000L / BallerRules.TICKS
            try {
                while (true) {
                    val more = synchronized(this) { tick(); players.isNotEmpty() }
                    if (!more) break
                    next += stepNanos
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else if (wait < -500_000_000L) next = System.nanoTime()
                }
            } catch (e: InterruptedException) {
                // Server fährt herunter
            } catch (e: Exception) {
                log.error("Ballerbude angehalten: {}", e.toString(), e)
                synchronized(this) { ArrayList(players.values).forEach { drop(it) } }
            } finally {
                synchronized(this) { if (loop === Thread.currentThread()) loop = null }
            }
        }, "ballerbude").apply { isDaemon = true; start() }
    }

    fun stop() {
        loop?.interrupt()
    }

    /** Ein Schritt der Welt + Schnappschüsse (jeder zweite Schritt). Der Aufrufer hält die Sperre. */
    fun tick() {
        synchronized(this) {
            val now = clock()
            ArrayList(players.values).forEach { if (now - it.lastSeen > TIMEOUT_MS) drop(it) }
            if (players.isEmpty()) return
            world.step()
            for (death in world.deaths) {
                val tank = death.tank
                val player = players.values.firstOrNull { it.tank === tank }
                if (player != null) {
                    player.tank = null
                    player.deadBy = death.by
                    player.deadScore = tank.score
                    player.deadLevel = tank.level
                    player.deadKills = tank.kills
                    player.deadTime = tank.alive
                    player.viewX = tank.x
                    player.viewY = tank.y
                }
            }
            if (world.deaths.isNotEmpty()) {
                world.deaths.clear()
                balanceBots()
            }
            if (world.tick % 2 == 0L) for (player in players.values) {
                val text = snapshot(player)
                if (!player.queue.offer(text)) {
                    player.queue.poll()
                    player.queue.offer(text)
                }
            }
        }
    }

    /** Was der Spieler gerade sieht, als kompaktes JSON (Zahlen als ganze Zahlen, Positionen der Bots ×10). */
    fun snapshot(player: Player): String {
        val tank = player.tank
        if (tank != null) {
            player.viewX = tank.x
            player.viewY = tank.y
            player.fov = tank.def.fov
        }
        val vx = BallerRules.VIEW_X * player.fov
        val vy = BallerRules.VIEW_Y * player.fov
        val cx = player.viewX
        val cy = player.viewY
        val out = StringBuilder(4096)
        out.append("{\"k\":").append(world.tick).append(",\"me\":{")
        if (tank != null) {
            val level = tank.level
            val into = tank.score - BallerRules.TOTAL[level]
            val need = if (level >= BallerRules.MAX_LEVEL) 0 else BallerRules.need(level)
            out.append("\"id\":").append(tank.id).append(",\"d\":0,\"hp\":").append(tank.hp.roundToInt()).append(",\"mh\":").append(tank.maxHp.roundToInt())
                .append(",\"lv\":").append(level).append(",\"sc\":").append(tank.score).append(",\"xp\":").append(into).append(",\"xn\":").append(need)
                .append(",\"pt\":").append(tank.points).append(",\"cl\":").append(tank.clazz).append(",\"ki\":").append(tank.kills)
                .append(",\"st\":[").append(tank.stats.joinToString(",")).append("],\"ch\":[")
                .append(BallerRules.choices(tank.clazz, level).joinToString(",") { it.id.toString() }).append("]")
        } else {
            out.append("\"id\":0,\"d\":1,\"by\":").append(JsonPrimitive(player.deadBy ?: "").toString()).append(",\"sc\":").append(player.deadScore)
                .append(",\"lv\":").append(player.deadLevel).append(",\"ki\":").append(player.deadKills).append(",\"tm\":").append(player.deadTime.roundToInt())
                .append(",\"nl\":").append(max(1, player.deadLevel / 2))
        }
        out.append(",\"x\":").append(cx.roundToInt()).append(",\"y\":").append(cy.roundToInt()).append(",\"fov\":").append((player.fov * 100).roundToInt()).append("},\"T\":[")
        var first = true
        for (other in world.tanks) {
            if (abs(other.x - cx) > vx + 80 || abs(other.y - cy) > vy + 80) continue
            if (!first) out.append(',')
            first = false
            out.append('[').append(other.id).append(',').append((other.x * 10).roundToInt()).append(',').append((other.y * 10).roundToInt()).append(',')
                .append((normalize(other.angle) * 100).roundToInt()).append(',').append((other.r * 10).roundToInt()).append(',')
                .append((other.hp / other.maxHp * 100).roundToInt().coerceIn(0, 100)).append(',').append(other.clazz).append(',').append(other.hue).append(',')
                .append(other.level).append(',').append((if (other.protect > 0) 1 else 0) + (if (other.bot) 2 else 0)).append(',').append(other.shots % 1000).append(']')
        }
        out.append("],\"B\":[")
        first = true
        for (bullet in world.bullets) {
            if (abs(bullet.x - cx) > vx + 30 || abs(bullet.y - cy) > vy + 30) continue
            if (!first) out.append(',')
            first = false
            out.append('[').append(bullet.id).append(',').append(bullet.x.roundToInt()).append(',').append(bullet.y.roundToInt()).append(',')
                .append(bullet.r.roundToInt()).append(',').append(bullet.owner.hue).append(',').append(if (bullet.mine) 1 else 0).append(']')
        }
        out.append(']')
        if (world.tick % 8 == 0L) {
            out.append(",\"S\":[")
            first = true
            for (snack in world.snacks) {
                if (abs(snack.x - cx) > vx + 150 || abs(snack.y - cy) > vy + 150) continue
                if (!first) out.append(',')
                first = false
                out.append('[').append(snack.id).append(',').append(snack.type).append(',').append(snack.x.roundToInt()).append(',').append(snack.y.roundToInt()).append(',')
                    .append((snack.hp / snack.def.hp * 100).roundToInt().coerceIn(0, 100)).append(']')
            }
            out.append(']')
        }
        if (world.tick % 20 == 0L) {
            out.append(",\"N\":[")
            first = true
            for (other in world.tanks.sortedByDescending { it.score }) {
                if (!first) out.append(',')
                first = false
                out.append('[').append(other.id).append(',').append(JsonPrimitive(other.name).toString()).append(',').append(other.hue).append(',')
                    .append(if (other.bot) 1 else 0).append(',').append(other.score).append(',').append(other.level).append(']')
            }
            out.append("],\"F\":[")
            first = true
            for (kill in world.kills) {
                if (!first) out.append(',')
                first = false
                out.append('[').append(kill.seq).append(',').append(JsonPrimitive(kill.killer).toString()).append(',').append(JsonPrimitive(kill.victim).toString()).append(',')
                    .append(kill.killerHue).append(',').append(kill.victimHue).append(']')
            }
            out.append(']')
            val leader = world.tanks.maxByOrNull { it.score }
            if (leader != null) out.append(",\"ld\":[").append(leader.x.roundToInt()).append(',').append(leader.y.roundToInt()).append(',').append(leader.id).append(']')
            world.snacks.firstOrNull { it.type == 3 }?.let { out.append(",\"pz\":[").append(it.x.roundToInt()).append(',').append(it.y.roundToInt()).append(']') }
        }
        out.append('}')
        return out.toString()
    }

    private fun normalize(angle: Double): Double {
        var value = angle % (2 * PI)
        if (value > PI) value -= 2 * PI
        if (value < -PI) value += 2 * PI
        return value
    }

    // ---------- Stream (SSE) ----------

    internal fun openStream(request: WebRequest): Pair<Player?, WebResponse?> = synchronized(this) {
        val player = players[request.query["t"]] ?: return null to json(410, obj("error" to "Nicht mehr im Spiel", "gone" to true))
        player to null
    }

    /** Schickt die Schnappschüsse des Spielers, bis der Browser geht. Läuft im Thread der Anfrage. */
    internal fun pump(player: Player, out: OutputStream) {
        val mine = synchronized(this) { ++player.stream }
        try {
            out.write((": " + " ".repeat(2048) + "\n\n").toByteArray())
            out.flush()
            while (!player.gone && player.stream == mine) {
                val next = player.queue.poll(1, TimeUnit.SECONDS)
                if (next == null) {
                    out.write(": ping\n\n".toByteArray())
                } else if (next === BYE) {
                    out.write("event: bye\ndata: {}\n\n".toByteArray())
                    out.flush()
                    return
                } else {
                    out.write("data: ".toByteArray())
                    out.write(next.toByteArray(Charsets.UTF_8))
                    out.write("\n\n".toByteArray())
                }
                out.flush()
                player.lastSeen = clock()
            }
        } catch (e: IOException) {
            // Browser weg – der Spieler fliegt nach TIMEOUT_MS raus, wenn er nicht wiederkommt
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, StatsServer.TYPE_JSON, body.toString().toByteArray(Charsets.UTF_8))

    companion object {
        const val PATH = "/api/arena"
        const val STREAM_PATH = "$PATH/stream"
        const val MAX_PLAYERS = 24
        const val MIN_BOTS = 3
        const val MAX_BOTS = 7
        const val TIMEOUT_MS = 12_000L
        private val BYE = String(charArrayOf('b', 'y', 'e'))
    }
}
