/*
 * MAMPF! – Spielwelt des Blob-Fress-Spiels der Website (Seite seiten/mampf.html, Server-Teil in MampfGame.kt).
 * Reine Rechnung ohne Netz und ohne Uhr: step(dt) rückt die Welt vor. Wird nur von einem Thread benutzt.
 *
 * Regeln: Blobs fressen Krümel und kleinere Blobs (ab 25 % mehr Masse), Leertaste teilt (höchstens 16 Teile,
 * nach einiger Zeit wachsen sie wieder zusammen), W spuckt Masse, Kakteen zerlegen große Blobs und lassen sich
 * mit Spucke füttern, bis sie einen neuen Kaktus abschießen. Bots füllen die Welt auf.
 */
package de.amongusmanager.crewbot.web

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

object MampfRules {
    const val WORLD = 4500.0
    const val START_MASS = 24.0
    const val FOOD_COUNT = 1300
    const val FOOD_GRID = 250.0
    const val VIRUS_COUNT = 16
    const val VIRUS_MAX = 36
    const val VIRUS_MASS = 110.0
    const val VIRUS_FEED = 7
    const val MAX_CELLS = 16
    const val SPLIT_MIN_MASS = 36.0
    const val EJECT_MIN_MASS = 40.0
    const val EJECT_COST = 16.0
    const val EJECT_MASS = 13.0
    /** Fressen geht erst mit so viel mal mehr Masse. */
    const val EAT_FACTOR = 1.25
    /** Spieler + Bots zusammen; darüber kommen keine Bots mehr dazu. */
    const val TARGET_PLAYERS = 12
    /** Halber Bildausschnitt bei Zoom 1 (Welteinheiten) – mampf.html rechnet mit denselben Zahlen. */
    const val VIEW_HALF_W = 720.0
    const val VIEW_HALF_H = 450.0
    const val MAX_MASS = 22_000.0

    fun radius(mass: Double): Double = 4.0 * sqrt(mass)

    /** Einheiten pro Sekunde: kleine Blobs sind flink, dicke träge. */
    fun speed(mass: Double): Double = 620.0 * mass.pow(-0.24)

    /** So lange (Sekunden) bleiben Teile nach dem Teilen getrennt. */
    fun mergeDelay(mass: Double): Double = 13.0 + mass * 0.012

    /** Wie weit die Kamera eines Spielers herauszoomt (1 = nah) – der Browser rechnet genauso. */
    fun viewScale(totalRadius: Double): Double = min(1.0, (64.0 / max(totalRadius, 1.0)).pow(0.4)).coerceAtLeast(0.22)
}

class MampfPlayer(val id: Int, var name: String, var face: Int, var hue: Int, val bot: Boolean) {
    val cells = ArrayList<MampfCell>()
    var targetX = 0.0
    var targetY = 0.0
    var splits = 0
    var ejects = 0
    var peak = 0.0
    var spawnedAt = 0.0
    var eaten = 0
    /** Letzte Mitte (auch nach dem Tod, für die Kamera). */
    var x = 0.0
    var y = 0.0
    // Bots
    var thinkAt = 0.0
    var respawnAt = 0.0
    var wanderX = 0.0
    var wanderY = 0.0
    var nerve = 1.0

    val alive: Boolean get() = cells.isNotEmpty()
    val mass: Double get() = cells.sumOf { it.mass }
    val totalRadius: Double get() = cells.sumOf { it.radius }
}

class MampfCell(val id: Int, val owner: MampfPlayer, var x: Double, var y: Double, mass: Double) {
    var mass: Double = mass
        set(value) {
            field = value
            radius = MampfRules.radius(value)
        }
    var radius: Double = MampfRules.radius(mass)
        private set
    var boostX = 0.0
    var boostY = 0.0
    var mergeAt = 0.0
    var dead = false
}

/** Krümel (liegt still), Spucke (fliegt kurz) oder Kaktus. */
class MampfThing(val id: Int, var x: Double, var y: Double, val hue: Int, var mass: Double) {
    var vx = 0.0
    var vy = 0.0
    var fed = 0
    var dead = false
    val radius: Double get() = MampfRules.radius(mass)
}

class MampfKill(val eater: MampfPlayer, val victim: MampfPlayer, val peak: Double, val seconds: Double)

class MampfWorld(
    private val random: Random = Random.Default,
    val size: Double = MampfRules.WORLD,
    /** Spieler + Bots zusammen. */
    var botTarget: Int = MampfRules.TARGET_PLAYERS,
    var virusTarget: Int = MampfRules.VIRUS_COUNT,
) {
    var time = 0.0
        private set
    private var nextId = 1
    val players = LinkedHashMap<Int, MampfPlayer>()
    val food = HashMap<Long, ArrayList<MampfThing>>()
    var foodCount = 0
        private set
    val spit = ArrayList<MampfThing>()
    val viruses = ArrayList<MampfThing>()
    /** Seit dem letzten Abholen gefressene Spieler. */
    val kills = ArrayList<MampfKill>()

    fun newId(): Int = nextId++

    init {
        // gleich mit vollem Buffet starten
        repeat(MampfRules.FOOD_COUNT) { addFood(random.nextDouble() * size, random.nextDouble() * size) }
    }

    private var botRemovedAt = -100.0

    // ---------- Spieler ----------

    fun addPlayer(name: String, face: Int, hue: Int, bot: Boolean = false): MampfPlayer {
        val player = MampfPlayer(newId(), name, face, hue, bot)
        player.x = size / 2
        player.y = size / 2
        players[player.id] = player
        return player
    }

    fun removePlayer(player: MampfPlayer) {
        // die Reste bleiben als Futter liegen
        for (cell in player.cells) {
            cell.dead = true
            dropFood(cell.x, cell.y, cell.mass * 0.6, cell.radius)
        }
        player.cells.clear()
        players.remove(player.id)
    }

    fun spawn(player: MampfPlayer, mass: Double = MampfRules.START_MASS, at: Pair<Double, Double>? = null) {
        if (player.alive) return
        val (x, y) = at ?: freeSpot()
        player.cells += MampfCell(newId(), player, x, y, mass)
        player.x = x
        player.y = y
        player.targetX = x
        player.targetY = y
        player.peak = mass
        player.eaten = 0
        player.splits = 0
        player.ejects = 0
        player.spawnedAt = time
    }

    private fun freeSpot(): Pair<Double, Double> {
        var best = size / 2 to size / 2
        var bestRoom = -1.0
        repeat(12) {
            val x = 150 + random.nextDouble() * (size - 300)
            val y = 150 + random.nextDouble() * (size - 300)
            var room = Double.MAX_VALUE
            for (player in players.values) for (cell in player.cells) room = min(room, hypot(cell.x - x, cell.y - y) - cell.radius)
            for (virus in viruses) room = min(room, hypot(virus.x - x, virus.y - y) - virus.radius)
            if (room > 450) return x to y
            if (room > bestRoom) {
                bestRoom = room
                best = x to y
            }
        }
        return best
    }

    // ---------- Krümel ----------

    private fun gridKey(x: Double, y: Double): Long =
        ((x / MampfRules.FOOD_GRID).toInt().toLong() shl 32) or ((y / MampfRules.FOOD_GRID).toInt().toLong() and 0xffffffffL)

    fun addFood(x: Double, y: Double, mass: Double = 1.0 + random.nextDouble() * 1.6): MampfThing {
        val thing = MampfThing(newId(), x.coerceIn(8.0, size - 8), y.coerceIn(8.0, size - 8), random.nextInt(360), mass)
        food.getOrPut(gridKey(thing.x, thing.y)) { ArrayList(8) } += thing
        foodCount++
        return thing
    }

    private fun dropFood(x: Double, y: Double, mass: Double, spread: Double) {
        var left = min(mass, 600.0)
        var pieces = 0
        while (left > 1 && pieces < 60) {
            val piece = min(left, 3.0 + random.nextDouble() * 9)
            val angle = random.nextDouble() * Math.PI * 2
            val distance = sqrt(random.nextDouble()) * (spread + 40)
            addFood(x + kotlin.math.cos(angle) * distance, y + kotlin.math.sin(angle) * distance, piece)
            left -= piece
            pieces++
        }
    }

    /** Alle Krümel im Rechteck. */
    inline fun foodIn(x0: Double, y0: Double, x1: Double, y1: Double, action: (MampfThing) -> Unit) {
        val grid = MampfRules.FOOD_GRID
        val gx0 = (max(x0, 0.0) / grid).toInt()
        val gx1 = (min(x1, size) / grid).toInt()
        val gy0 = (max(y0, 0.0) / grid).toInt()
        val gy1 = (min(y1, size) / grid).toInt()
        for (gx in gx0..gx1) for (gy in gy0..gy1) {
            val list = food[(gx.toLong() shl 32) or (gy.toLong() and 0xffffffffL)] ?: continue
            for (thing in list) if (thing.x in x0..x1 && thing.y in y0..y1) action(thing)
        }
    }

    fun addVirus(x: Double, y: Double): MampfThing {
        val virus = MampfThing(newId(), x.coerceIn(60.0, size - 60), y.coerceIn(60.0, size - 60), 120, MampfRules.VIRUS_MASS)
        viruses += virus
        return virus
    }

    // ---------- Takt ----------

    fun step(dt: Double) {
        time += dt
        refill()
        for (player in players.values) {
            if (player.bot) think(player)
            if (!player.alive) continue
            repeat(min(player.splits, 4)) { split(player) }
            repeat(min(player.ejects, 8)) { eject(player) }
            player.splits = 0
            player.ejects = 0
            move(player, dt)
        }
        moveThings(dt)
        for (player in players.values) if (player.alive) separate(player)
        eat()
        for (player in players.values) {
            if (!player.alive) continue
            var sumX = 0.0
            var sumY = 0.0
            var total = 0.0
            for (cell in player.cells) {
                if (cell.mass > 100) cell.mass -= cell.mass * 0.0016 * dt
                if (cell.mass > MampfRules.MAX_MASS) cell.mass = MampfRules.MAX_MASS
                sumX += cell.x * cell.mass
                sumY += cell.y * cell.mass
                total += cell.mass
            }
            player.x = sumX / total
            player.y = sumY / total
            if (total > player.peak) player.peak = total
        }
    }

    private fun refill() {
        var guard = 0
        while (foodCount < MampfRules.FOOD_COUNT && guard++ < 12) addFood(random.nextDouble() * size, random.nextDouble() * size)
        if (viruses.size < virusTarget) {
            val (x, y) = freeSpot()
            addVirus(x, y)
        }
        // Bots: auffüllen, überzählige verschwinden beim nächsten Tod
        val bots = players.values.count { it.bot }
        val humans = players.size - bots
        val wanted = (botTarget - humans).coerceAtLeast(0)
        repeat(wanted - bots) {
            val bot = addPlayer(botName(), random.nextInt(MampfNames.FACES), random.nextInt(360), bot = true)
            bot.nerve = 0.6 + random.nextDouble() * 0.9
            bot.respawnAt = time
        }
        if (bots > wanted) {
            // zu viele: tote Bots sofort weg, sonst alle 2 s der kleinste (zerfällt zu Krümeln)
            val dead = players.values.firstOrNull { it.bot && !it.alive }
            if (dead != null) {
                players.remove(dead.id)
            } else if (time - botRemovedAt > 2) {
                botRemovedAt = time
                players.values.filter { it.bot }.minByOrNull { it.mass }?.let { removePlayer(it) }
            }
        }
        for (player in players.values) {
            if (player.bot && !player.alive && time >= player.respawnAt) {
                player.name = botName()
                player.face = random.nextInt(MampfNames.FACES)
                player.hue = random.nextInt(360)
                spawn(player, MampfRules.START_MASS + random.nextDouble() * 40)
            }
        }
    }

    private fun botName(): String {
        val used = players.values.mapTo(HashSet()) { it.name }
        val free = MampfNames.BOTS.filter { it !in used }
        return (free.ifEmpty { MampfNames.BOTS }).random(random)
    }

    private fun move(player: MampfPlayer, dt: Double) {
        val damp = exp(-3.4 * dt)
        for (cell in player.cells) {
            val dx = player.targetX - cell.x
            val dy = player.targetY - cell.y
            val distance = hypot(dx, dy)
            if (distance > 1) {
                // nah am Zeiger wird der Blob langsamer statt zu zittern
                val speed = MampfRules.speed(cell.mass) * min(1.0, distance / (cell.radius * 0.9 + 12))
                cell.x += dx / distance * speed * dt
                cell.y += dy / distance * speed * dt
            }
            cell.x += cell.boostX * dt
            cell.y += cell.boostY * dt
            cell.boostX *= damp
            cell.boostY *= damp
            cell.x = cell.x.coerceIn(0.0, size)
            cell.y = cell.y.coerceIn(0.0, size)
        }
    }

    private fun moveThings(dt: Double) {
        val damp = exp(-4.2 * dt)
        for (list in listOf(spit, viruses)) for (thing in list) {
            if (thing.vx == 0.0 && thing.vy == 0.0) continue
            thing.x = (thing.x + thing.vx * dt).coerceIn(10.0, size - 10)
            thing.y = (thing.y + thing.vy * dt).coerceIn(10.0, size - 10)
            thing.vx *= damp
            thing.vy *= damp
            if (hypot(thing.vx, thing.vy) < 6) {
                thing.vx = 0.0
                thing.vy = 0.0
            }
        }
    }

    fun split(player: MampfPlayer) {
        for (cell in player.cells.sortedByDescending { it.mass }) {
            if (player.cells.size >= MampfRules.MAX_CELLS) break
            if (cell.mass < MampfRules.SPLIT_MIN_MASS) continue
            val (dx, dy) = direction(cell, player)
            cell.mass /= 2
            launch(player, cell, cell.mass, dx, dy, 520 + cell.radius * 7)
        }
    }

    private fun launch(player: MampfPlayer, from: MampfCell, mass: Double, dx: Double, dy: Double, power: Double): MampfCell {
        val piece = MampfCell(newId(), player, from.x + dx * 2, from.y + dy * 2, mass)
        piece.boostX = dx * power
        piece.boostY = dy * power
        piece.mergeAt = time + MampfRules.mergeDelay(mass)
        from.mergeAt = max(from.mergeAt, time + MampfRules.mergeDelay(from.mass))
        player.cells += piece
        return piece
    }

    fun eject(player: MampfPlayer) {
        for (cell in player.cells) {
            if (cell.mass < MampfRules.EJECT_MIN_MASS) continue
            val (dx, dy) = direction(cell, player)
            cell.mass -= MampfRules.EJECT_COST
            val blob = MampfThing(newId(), cell.x + dx * (cell.radius + 16), cell.y + dy * (cell.radius + 16), player.hue, MampfRules.EJECT_MASS)
            val wobble = (random.nextDouble() - 0.5) * 0.25
            blob.vx = (dx - dy * wobble) * 950
            blob.vy = (dy + dx * wobble) * 950
            spit += blob
        }
    }

    private fun direction(cell: MampfCell, player: MampfPlayer): Pair<Double, Double> {
        val dx = player.targetX - cell.x
        val dy = player.targetY - cell.y
        val distance = hypot(dx, dy)
        return if (distance < 1) 1.0 to 0.0 else dx / distance to dy / distance
    }

    /** Eigene Teile schieben sich auseinander, bis sie wieder zusammenwachsen dürfen. */
    private fun separate(player: MampfPlayer) {
        val cells = player.cells
        for (i in 0 until cells.size) for (j in i + 1 until cells.size) {
            val a = cells[i]
            val b = cells[j]
            if (a.dead || b.dead) continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val distance = hypot(dx, dy)
            val ready = time >= a.mergeAt && time >= b.mergeAt
            if (ready) {
                val big = if (a.mass >= b.mass) a else b
                val small = if (big === a) b else a
                if (distance < big.radius - small.radius * 0.3) {
                    big.x = (big.x * big.mass + small.x * small.mass) / (big.mass + small.mass)
                    big.y = (big.y * big.mass + small.y * small.mass) / (big.mass + small.mass)
                    big.mass += small.mass
                    small.dead = true
                }
                continue
            }
            // frisch abgeschossene Teile fliegen erst durch
            if (hypot(a.boostX, a.boostY) > 160 || hypot(b.boostX, b.boostY) > 160) continue
            val overlap = a.radius + b.radius - distance
            if (overlap <= 0) continue
            val nx = if (distance < 0.01) 1.0 else dx / distance
            val ny = if (distance < 0.01) 0.0 else dy / distance
            val shareA = b.mass / (a.mass + b.mass)
            a.x -= nx * overlap * shareA
            a.y -= ny * overlap * shareA
            b.x += nx * overlap * (1 - shareA)
            b.y += ny * overlap * (1 - shareA)
        }
        cells.removeAll { it.dead }
    }

    private fun eat() {
        val all = ArrayList<MampfCell>()
        for (player in players.values) all += player.cells
        // Krümel
        for (cell in all) {
            val r = cell.radius
            val grid = MampfRules.FOOD_GRID
            val gx0 = (max(cell.x - r, 0.0) / grid).toInt()
            val gx1 = (min(cell.x + r, size) / grid).toInt()
            val gy0 = (max(cell.y - r, 0.0) / grid).toInt()
            val gy1 = (min(cell.y + r, size) / grid).toInt()
            for (gx in gx0..gx1) for (gy in gy0..gy1) {
                val list = food[(gx.toLong() shl 32) or (gy.toLong() and 0xffffffffL)] ?: continue
                val iterator = list.iterator()
                while (iterator.hasNext()) {
                    val thing = iterator.next()
                    val dx = thing.x - cell.x
                    val dy = thing.y - cell.y
                    if (dx * dx + dy * dy < r * r) {
                        cell.mass += thing.mass
                        iterator.remove()
                        foodCount--
                    }
                }
            }
        }
        // Spucke: füttert Kakteen oder wird gefressen
        for (blob in spit) {
            for (virus in viruses) {
                if (blob.dead || virus.dead) continue
                if (hypot(blob.x - virus.x, blob.y - virus.y) < virus.radius) {
                    blob.dead = true
                    virus.fed++
                    if (virus.fed >= MampfRules.VIRUS_FEED && viruses.size < MampfRules.VIRUS_MAX) {
                        virus.fed = 0
                        val speed = hypot(blob.vx, blob.vy)
                        val (dx, dy) = if (speed < 1) 1.0 to 0.0 else blob.vx / speed to blob.vy / speed
                        val shot = MampfThing(newId(), virus.x + dx * 20, virus.y + dy * 20, 120, MampfRules.VIRUS_MASS)
                        shot.vx = dx * 1100
                        shot.vy = dy * 1100
                        born += shot
                    }
                }
            }
            if (blob.dead) continue
            for (cell in all) {
                if (cell.mass < 20) continue
                if (hypot(blob.x - cell.x, blob.y - cell.y) < cell.radius - 4) {
                    cell.mass += blob.mass
                    blob.dead = true
                    break
                }
            }
        }
        spit.removeAll { it.dead }
        viruses += born
        born.clear()
        // Kakteen: zerlegen große Blobs
        for (virus in viruses) {
            for (cell in all) {
                if (cell.dead || virus.dead || cell.mass < virus.mass * 1.2) continue
                if (hypot(virus.x - cell.x, virus.y - cell.y) < cell.radius - virus.radius * 0.4) {
                    virus.dead = true
                    cell.mass += virus.mass * 0.5
                    pop(cell)
                }
            }
        }
        viruses.removeAll { it.dead }
        // Blobs fressen Blobs: die dicksten zuerst
        all.sortByDescending { it.mass }
        for (i in all.indices) {
            val big = all[i]
            if (big.dead) continue
            for (j in i + 1 until all.size) {
                val small = all[j]
                if (small.dead || small.owner === big.owner) continue
                if (big.mass < small.mass * MampfRules.EAT_FACTOR) continue
                val reach = big.radius - small.radius * 0.35
                val dx = small.x - big.x
                val dy = small.y - big.y
                if (dx * dx + dy * dy < reach * reach) {
                    big.mass += small.mass
                    small.dead = true
                    val victim = small.owner
                    victim.cells.remove(small)
                    if (!victim.alive) {
                        big.owner.eaten++
                        kills += MampfKill(big.owner, victim, victim.peak, time - victim.spawnedAt)
                        if (victim.bot) victim.respawnAt = time + 2 + random.nextDouble() * 4
                    }
                }
            }
        }
    }

    private val born = ArrayList<MampfThing>()

    /** Kaktus getroffen: der Blob platzt in viele kleine Teile. */
    private fun pop(cell: MampfCell) {
        val player = cell.owner
        val pieces = min(MampfRules.MAX_CELLS - player.cells.size, 9)
        if (pieces <= 0) return
        val share = cell.mass / (pieces + 2) // das Original behält zwei Anteile
        val start = random.nextDouble() * Math.PI * 2
        for (index in 0 until pieces) {
            val angle = start + index * Math.PI * 2 / pieces
            cell.mass -= share
            launch(player, cell, share, kotlin.math.cos(angle), kotlin.math.sin(angle), 560 + random.nextDouble() * 160)
        }
    }

    // ---------- Bots ----------

    private fun think(bot: MampfPlayer) {
        if (!bot.alive || time < bot.thinkAt) return
        bot.thinkAt = time + 0.15 + random.nextDouble() * 0.15
        val me = bot.cells.maxBy { it.mass }
        val smallest = bot.cells.minOf { it.mass }
        val sight = 520 + me.radius * 3
        var pullX = 0.0
        var pullY = 0.0
        var danger = false
        var prey: MampfCell? = null
        var preyScore = 0.0
        for (other in players.values) {
            if (other === bot) continue
            for (cell in other.cells) {
                val dx = cell.x - me.x
                val dy = cell.y - me.y
                val distance = hypot(dx, dy)
                val gap = distance - cell.radius - me.radius
                if (gap > sight) continue
                if (cell.mass > smallest * MampfRules.EAT_FACTOR) {
                    // Gefahr: weg da – je näher, desto dringender; wer teilen könnte, ist von weiter her gefährlich
                    val reach = if (cell.mass > smallest * 2.7) 420.0 else 160.0
                    if (gap < reach * bot.nerve) {
                        val weight = (reach * bot.nerve - gap) / max(distance, 1.0) * 4
                        pullX -= dx * weight
                        pullY -= dy * weight
                        danger = true
                    }
                } else if (me.mass > cell.mass * MampfRules.EAT_FACTOR) {
                    val score = cell.mass / (gap + 80)
                    if (score > preyScore) {
                        preyScore = score
                        prey = cell
                    }
                }
            }
        }
        if (me.mass > MampfRules.VIRUS_MASS * 1.2) {
            for (virus in viruses) {
                val dx = virus.x - me.x
                val dy = virus.y - me.y
                val distance = hypot(dx, dy)
                if (distance < me.radius + 90) {
                    pullX -= dx / max(distance, 1.0) * 300
                    pullY -= dy / max(distance, 1.0) * 300
                    danger = true
                }
            }
        }
        // nicht in der Ecke kleben
        val edge = 220.0
        if (me.x < edge) pullX += edge - me.x
        if (me.y < edge) pullY += edge - me.y
        if (me.x > size - edge) pullX -= me.x - (size - edge)
        if (me.y > size - edge) pullY -= me.y - (size - edge)

        if (danger) {
            val length = max(hypot(pullX, pullY), 1.0)
            bot.targetX = me.x + pullX / length * 600
            bot.targetY = me.y + pullY / length * 600
            return
        }
        val target = prey
        if (target != null && preyScore > 0.05) {
            bot.targetX = target.x
            bot.targetY = target.y
            val distance = hypot(target.x - me.x, target.y - me.y)
            // Teilen-Angriff: nur wenn die Hälfte noch reicht und die Beute nah genug ist
            if (bot.cells.size <= 2 && me.mass > 90 && me.mass / 2 > target.mass * 1.35 &&
                distance < me.radius * 3.2 + 120 && distance > me.radius && random.nextDouble() < 0.25 * bot.nerve
            ) {
                bot.splits = 1
            }
            return
        }
        // Krümel suchen
        var best: MampfThing? = null
        var bestDistance = Double.MAX_VALUE
        val reach = 420.0
        foodIn(me.x - reach, me.y - reach, me.x + reach, me.y + reach) { thing ->
            val distance = hypot(thing.x - me.x, thing.y - me.y) / (0.6 + thing.mass * 0.3)
            if (distance < bestDistance) {
                bestDistance = distance
                best = thing
            }
        }
        val crumb = best
        if (crumb != null) {
            bot.targetX = crumb.x + pullX
            bot.targetY = crumb.y + pullY
        } else {
            if (hypot(bot.wanderX - me.x, bot.wanderY - me.y) < 150 || bot.wanderX == 0.0) {
                bot.wanderX = 300 + random.nextDouble() * (size - 600)
                bot.wanderY = 300 + random.nextDouble() * (size - 600)
            }
            bot.targetX = bot.wanderX
            bot.targetY = bot.wanderY
        }
    }
}

object MampfNames {
    /** Anzahl der Gesichter – die Liste selbst steht in der Seite (FACES in mampf.html). */
    const val FACES = 16

    val BOTS = listOf(
        "Günther", "Kevin (lagt)", "Dein Ex", "Montagmorgen", "Mama ruft", "WLAN-Kabel", "Brudi", "Gönnjamin",
        "Sigma-Blob", "Bielefeld", "Der Admin", "Fleischkäse", "404 Skill", "Tante Erna", "Dönermann", "Steuererklärung",
        "Kein Impostor", "Rundmail", "Opa am Steuer", "Praktikant", "Hausmeister", "Ping 999", "Mittagspause", "Kartoffel",
        "Nur kurz AFK", "Schwiegermutter", "Bausparvertrag", "Uwe", "Chantal", "Der Endgegner", "Lauch", "Ehrenblob",
        "Pfandflasche", "Deutsche Bahn", "Zonk", "Rüdiger", "Mett-Igel", "Elternabend", "Speckgürtel", "Vollkornbrot",
    )
}
