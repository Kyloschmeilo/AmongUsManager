package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.BallerRules
import de.amongusmanager.crewbot.web.BallerWorld
import de.amongusmanager.crewbot.web.Ballerbude
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BallerbudeTest {
    private fun post(path: String, body: String, query: Map<String, String> = emptyMap()) =
        WebRequest("POST", path, query, emptyMap(), body.toByteArray())

    @Test
    fun levelsAndClassesFitTogether() {
        assertEquals(1, BallerRules.levelOf(0))
        assertEquals(2, BallerRules.levelOf(BallerRules.TOTAL[2]))
        assertEquals(BallerRules.MAX_LEVEL, BallerRules.levelOf(Int.MAX_VALUE))
        for (level in 2..BallerRules.MAX_LEVEL) assertTrue(BallerRules.TOTAL[level] > BallerRules.TOTAL[level - 1])
        // jede Klasse ist erreichbar und hat Rohre
        for (def in BallerRules.CLASSES) {
            assertTrue(def.barrels.isNotEmpty(), def.name)
            if (def.from >= 0) assertTrue(BallerRules.CLASSES[def.from].level < def.level, def.name)
        }
        assertEquals(4, BallerRules.choices(0, 10).size)
        assertTrue(BallerRules.choices(0, 9).isEmpty())
        assertEquals("Namenlos", BallerRules.cleanName("  <> "))
        assertEquals(16, BallerRules.cleanName("x".repeat(40)).length)
    }

    @Test
    fun shootingSnacksGivesXpAndLevels() {
        val world = BallerWorld(Random(1))
        val tank = world.spawnTank("Kilian", bot = false)
        world.snacks.clear()
        val snack = world.spawnSnack(0, tank.x + 150, tank.y)
        tank.angle = 0.0
        tank.fire = true
        repeat(80) { world.step() }
        assertTrue(snack !in world.snacks, "Keks wurde getroffen")
        assertTrue(tank.score >= BallerRules.SNACK_TYPES[0].xp)
        world.addScore(tank, BallerRules.TOTAL[10])
        assertTrue(tank.level >= 10)
        assertTrue(tank.points >= 9)
        assertTrue(world.upgrade(tank, 4))
        assertTrue(world.choose(tank, 1))
        assertFalse(world.choose(tank, 7), "Railgun nur über Camper")
    }

    @Test
    fun killsGoToTheShooter() {
        val world = BallerWorld(Random(2))
        val a = world.spawnTank("A", bot = false, level = 20)
        val b = world.spawnTank("B", bot = false)
        world.snacks.clear()
        a.protect = 0.0; b.protect = 0.0
        a.x = 1000.0; a.y = 1000.0; b.x = 1250.0; b.y = 1000.0
        a.angle = 0.0
        a.fire = true
        var steps = 0
        while (b in world.tanks && steps++ < 2000) {
            b.x = 1250.0; b.y = 1000.0; b.mx = 0.0; b.my = 0.0
            world.snacks.clear()
            world.step()
        }
        assertFalse(b in world.tanks, "B ist raus")
        assertEquals(1, a.kills)
        assertEquals("A", world.kills.last().killer)
    }

    @Test
    fun playersJoinPlayAndLeaveOverHttp() {
        var now = 0L
        val arena = Ballerbude(Random(3), autoRun = false) { now }
        val join = arena.handle(post("/api/arena/join", """{"name":"Kilian<script>"}"""))
        assertEquals(200, join.status)
        val data = Json.parseToJsonElement(join.body.toString(Charsets.UTF_8)).jsonObject
        val token = data["token"]!!.jsonPrimitive.content
        assertEquals("Kilianscript", data["name"]!!.jsonPrimitive.content)
        assertTrue(arena.world.tanks.count { it.bot } >= Ballerbude.MIN_BOTS)
        assertEquals(200, arena.handle(post("/api/arena/input", """{"m":[1,0],"a":0.5,"f":1}""", mapOf("t" to token))).status)
        val tank = arena.world.tanks.first { !it.bot }
        assertEquals(0.5, tank.angle)
        repeat(40) { now += 25; arena.tick() }
        val snap = Json.parseToJsonElement(arena.snapshot(arena.javaClass.getDeclaredField("players").let { field ->
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(arena) as Map<String, Ballerbude.Player>).values.first()
        })).jsonObject
        assertNotNull(snap["me"])
        assertTrue(snap["T"].toString().contains("["))
        assertEquals(410, arena.handle(post("/api/arena/input", "{}", mapOf("t" to "falsch"))).status)
        // ohne Lebenszeichen fliegt man raus, die Bots gehen mit
        now += Ballerbude.TIMEOUT_MS + 1
        arena.tick()
        assertEquals(0, arena.playerCount)
        assertTrue(arena.world.tanks.isEmpty())
    }
}
