package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.web.MemeGame
import de.amongusmanager.crewbot.web.MemePhotos
import de.amongusmanager.crewbot.web.MemeRules
import de.amongusmanager.crewbot.web.MemeTemplates
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemeGameTest {
    private var now = 1_000_000L
    private val game = MemeGame(clock = { now }, random = Random(3))

    private fun call(body: JsonObject): JsonObject {
        val response = game.handle(WebRequest("POST", MemeGame.PATH, emptyMap(), emptyMap(), body.toString().toByteArray()), "1.2.3.4")
        assertEquals(200, response.status)
        return Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
    }

    private fun msg(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is List<*> -> put(key, buildJsonArray { value.forEach { add(kotlinx.serialization.json.JsonPrimitive(it as String)) } })
            null -> Unit
            else -> error("Typ")
        }
    }

    private class Seat(val code: String, val token: String, val id: Int)

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.boolean

    private fun create(name: String): Seat {
        val res = call(msg("a" to "create", "name" to name))
        assertTrue(res.ok(), res.toString())
        return Seat(res.str("code"), res.str("token"), res["you"]!!.jsonPrimitive.int)
    }

    private fun join(code: String, name: String): Seat {
        val res = call(msg("a" to "join", "room" to code, "name" to name))
        assertTrue(res.ok(), res.toString())
        return Seat(res.str("code"), res.str("token"), res["you"]!!.jsonPrimitive.int)
    }

    private fun act(seat: Seat, vararg pairs: Pair<String, Any?>) = call(msg("room" to seat.code, "token" to seat.token, *pairs))

    private fun sync(vararg seats: Seat) = seats.map { act(it, "a" to "sync") }.last()

    @Test
    fun wholeGameFromWritingToPodium() {
        val host = create("Kilian")
        val b = join(host.code, "Timo")
        val c = join(host.code, "kilian")
        assertEquals(3, act(host, "a" to "sync")["players"]!!.jsonArray.size)
        // gleicher Name wird nummeriert
        assertTrue(act(host, "a" to "sync")["players"]!!.jsonArray.any { it.jsonObject.str("name") == "kilian 2" })

        // nur der Host startet, und nur in der Lobby
        act(b, "a" to "settings", "rounds" to 1)
        assertEquals(3, sync(host)["settings"]!!.jsonObject["rounds"]!!.jsonPrimitive.int)
        act(host, "a" to "settings", "rounds" to 1, "seconds" to 60)
        val started = act(host, "a" to "start")
        assertEquals("write", started.str("phase"))
        val tplHost = started["me"]!!.jsonObject.str("tpl")
        // Standard: echte Meme-Pakete
        assertTrue(tplHost in MemePhotos.BY_ID)
        // jeder hat eine andere Vorlage
        val tpls = listOf(host, b, c).map { game.templateOf(host.code, it.id) }
        assertEquals(3, tpls.toSet().size)

        // neu würfeln ändert die Vorlage und zählt runter
        val rerolled = act(b, "a" to "reroll")["me"]!!.jsonObject
        assertEquals(1, rerolled["rerolls"]!!.jsonPrimitive.int)
        assertTrue(rerolled.str("tpl") != tpls[1])

        fun texts(seat: Seat, text: String): List<String> = List(game.slotsOf(game.templateOf(host.code, seat.id))) { "$text ${it + 1}" }
        act(host, "a" to "write", "captions" to texts(host, "Host"), "done" to true)
        act(b, "a" to "write", "captions" to texts(b, "Timo"), "done" to true)
        // leer abgeben geht nicht
        assertFalse(act(c, "a" to "write", "captions" to listOf("", ""), "done" to true)["me"]!!.jsonObject["done"]!!.jsonPrimitive.boolean)
        val voting = act(c, "a" to "write", "captions" to texts(c, "Drei"), "done" to true)
        assertEquals("vote", voting.str("phase"))
        assertEquals(3, voting["of"]!!.jsonPrimitive.int)

        // reihum: Autor erst nach dem Aufdecken, eigene Stimme zählt nicht
        val seats = listOf(host, b, c)
        repeat(3) { index ->
            val state = sync(host)
            val meme = state["meme"]!!.jsonObject
            assertEquals(JsonNull, meme["author"])
            assertEquals(JsonNull, meme["name"])
            assertEquals(index + 1, state["at"]!!.jsonPrimitive.int)
            val author = seats.first { s -> meme["captions"]!!.jsonArray.first().jsonPrimitive.content.startsWith(
                when (s) { host -> "Host"; b -> "Timo"; else -> "Drei" }) }
            for (s in seats) act(s, "a" to "vote", "v" to 4)
            val voted = sync(host)
            assertEquals(2, voted["voted"]!!.jsonPrimitive.int)
            now += MemeGame.ALL_VOTED_MS + 1
            val revealed = sync(host)
            assertTrue(revealed["revealed"]!!.jsonPrimitive.boolean)
            assertEquals(author.id, revealed["meme"]!!.jsonObject["author"]!!.jsonPrimitive.int)
            assertEquals(300, revealed["meme"]!!.jsonObject["points"]!!.jsonPrimitive.int)
            now += MemeGame.REVEAL_MS + 1
            sync(host, b, c)
        }
        val scores = sync(host, b, c)
        assertEquals("scores", scores.str("phase"))
        assertNotNull(scores["best"])
        assertTrue(scores["players"]!!.jsonArray.all { it.jsonObject["score"]!!.jsonPrimitive.int == 300 })

        now += MemeGame.SCORES_MS + 1
        val final = sync(host, b, c)
        assertEquals("final", final.str("phase"))
        assertEquals(3, final["gallery"]!!.jsonArray.size)

        act(host, "a" to "again")
        assertEquals("lobby", sync(host).str("phase"))
    }

    @Test
    fun captionsOfOthersStaySecretWhileWriting() {
        val host = create("A")
        val b = join(host.code, "B")
        act(host, "a" to "start")
        act(host, "a" to "write", "captions" to listOf("GEHEIM", "GEHEIM"))
        val other = act(b, "a" to "sync").toString()
        assertFalse("GEHEIM" in other)
        // ohne Text kein Meme: nur eins wird gezeigt, wenn die Zeit um ist
        repeat(16) {
            now += 5_000
            act(b, "a" to "sync")
            act(host, "a" to "sync")
        }
        val vote = act(host, "a" to "sync")
        assertEquals("vote", vote.str("phase"))
        assertEquals(1, vote["of"]!!.jsonPrimitive.int)
        assertTrue(vote["mine"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun statusListsJoinableRoomsForTheLiveBar() {
        fun status() = Json.parseToJsonElement(game.handle(WebRequest("GET", MemeGame.PATH, emptyMap(), emptyMap()), "1.2.3.4").body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(0, status()["rooms"]!!.jsonPrimitive.int)
        val host = create("A")
        join(host.code, "B")
        val s = status()
        assertEquals(1, s["rooms"]!!.jsonPrimitive.int)
        assertEquals(2, s["players"]!!.jsonPrimitive.int)
        // die Live-Leiste der Startseite führt mit dem Code direkt in den Raum – Namen gehen nicht raus
        val room = s["list"]!!.jsonArray.single().jsonObject
        assertEquals(host.code, room.str("code"))
        assertEquals(2, room["players"]!!.jsonPrimitive.int)
        assertEquals(MemeRules.MAX_PLAYERS, room["max"]!!.jsonPrimitive.int)
        assertFalse(room["playing"]!!.jsonPrimitive.boolean)
        assertFalse("\"A\"" in s.toString() || "\"B\"" in s.toString())
        now += MemeGame.OFFLINE_MS + 1
        assertEquals(0, status()["rooms"]!!.jsonPrimitive.int)
    }

    @Test
    fun needsTwoPlayersAndKnownRooms() {
        val host = create("Allein")
        val res = act(host, "a" to "start")
        assertEquals("lobby", res.str("phase"))
        assertNotNull(res["error"])
        val wrong = call(msg("a" to "join", "room" to "ZZZZ", "name" to "X"))
        assertFalse(wrong.ok())
        val gone = call(msg("a" to "sync", "room" to host.code, "token" to "falsch"))
        assertTrue(gone["gone"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun customImagesOnlyHttps() {
        val list = MemeRules.customImages("https://example.org/a.jpg\nhttp://unsicher.de/b.png\njavascript:alert(1)\nhttps://x.de/\"onerror=\nhttps://example.org/a.jpg")
        assertEquals(listOf("https://example.org/a.jpg"), list)
        val host = create("A")
        join(host.code, "B")
        act(host, "a" to "settings", "custom" to "https://example.org/a.jpg", "packs" to emptyList<String>())
        val started = act(host, "a" to "start")
        val me = started["me"]!!.jsonObject
        assertEquals("https://example.org/a.jpg", me.str("url"))
        assertEquals(2, me["captions"]!!.jsonArray.size)
        // ohne eigene Bilder bleibt mindestens ein Paket an
        val h2 = create("C")
        assertNotNull(act(h2, "a" to "settings", "packs" to emptyList<String>())["error"])
        assertTrue(act(h2, "a" to "sync")["settings"]!!.jsonObject["packs"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun photoPacksAreCompleteAndServed() {
        assertTrue(MemePhotos.ALL.size >= 100, "echte Vorlagen fehlen")
        assertTrue(MemePhotos.PACKS.all { p -> MemePhotos.count(p.id) >= 10 })
        assertTrue(MemePhotos.ALL.none { it.id in MemeTemplates.BY_ID }, "ids doppelt mit den Zeichnungen")
        val root = Json.parseToJsonElement(MemePhotos.json.toString(Charsets.UTF_8)).jsonObject
        for (t in root["templates"]!!.jsonArray.map { it.jsonObject }) {
            val id = t.str("id")
            assertTrue(t.str("pack") in MemePhotos.PACKS.map { it.id }, id)
            assertTrue(t["w"]!!.jsonPrimitive.int > 100 && t["h"]!!.jsonPrimitive.int > 100, id)
            for (b in t["boxes"]!!.jsonArray.map { it.jsonObject }) {
                val (x, y, w, h) = listOf("x", "y", "w", "h").map { b[it]!!.jsonPrimitive.content.toDouble() }
                assertTrue(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1.0001 && y + h <= 1.0001, "$id $b")
            }
            val image = MemePhotos.image(id)
            assertNotNull(image, id)
            assertTrue(image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte(), "$id ist kein JPEG")
        }
        fun get(path: String) = game.handle(WebRequest("GET", path, emptyMap(), emptyMap()), "1.2.3.4")
        val list = get(MemeGame.PATH + "/vorlagen")
        assertEquals(200, list.status)
        assertTrue(list.headers.any { it.first == "Cache-Control" && "public" in it.second })
        val img = get(MemeGame.PATH + "/bild/drake.jpg")
        assertEquals(200, img.status)
        assertEquals("image/jpeg", img.type)
        assertEquals(404, get(MemeGame.PATH + "/bild/gibtsnicht.jpg").status)
        assertEquals(404, get(MemeGame.PATH + "/bild/..%2Fvorlagen.json.jpg").status)
        assertEquals(404, get(MemeGame.PATH + "/bild/../web/index.html").status)
    }

    @Test
    fun onlyChosenPacksAreDealt() {
        val host = create("A")
        val b = join(host.code, "B")
        val res = act(host, "a" to "settings", "packs" to listOf("tiere", "gibtsnicht"))
        assertEquals(listOf("tiere"), res["settings"]!!.jsonObject["packs"]!!.jsonArray.map { it.jsonPrimitive.content })
        val packs = res["settings"]!!.jsonObject["packList"]!!.jsonArray.map { it.jsonObject.str("id") }
        assertTrue("zeichnungen" in packs && "klassiker" in packs)
        // Gäste können die Pakete nicht ändern
        act(b, "a" to "settings", "packs" to listOf("film"))
        act(host, "a" to "start")
        for (seat in listOf(host, b)) {
            val tpl = game.templateOf(host.code, seat.id)
            assertEquals("tiere", MemePhotos.BY_ID[tpl]?.pack, tpl)
            assertEquals(MemePhotos.BY_ID[tpl]!!.slots, act(seat, "a" to "sync")["me"]!!.jsonObject["captions"]!!.jsonArray.size)
        }
        // nur Zeichnungen geht auch
        val h2 = create("C")
        join(h2.code, "D")
        act(h2, "a" to "settings", "packs" to listOf("zeichnungen"))
        act(h2, "a" to "start")
        assertTrue(game.templateOf(h2.code, h2.id) in MemeTemplates.BY_ID)
    }

    @Test
    fun captionCleaning() {
        assertEquals("a b\nc", MemeRules.cleanCaption("  a   b \n\n\n c\u0007 "))
        assertEquals("1\n2\n3 4", MemeRules.cleanCaption("1\n2\n3\n4"))
        assertEquals(MemeRules.MAX_CAPTION, MemeRules.cleanCaption("x".repeat(500)).length)
        assertEquals(0, MemeRules.points(emptyList()))
        assertEquals(25 + 150, MemeRules.points(listOf(1, 4)))
    }

    @Test
    fun absentPlayersEndTheGame() {
        val host = create("A")
        join(host.code, "B")
        act(host, "a" to "start")
        now += MemeGame.OFFLINE_MS + 1
        val res = act(host, "a" to "sync")
        assertEquals("final", res.str("phase"))
        assertNotNull(res["note"])
    }

    @Test
    fun pageHasEveryTemplateOfTheServer() {
        val html = Files.readString(Path.of("seiten/memes.html"))
        val block = html.substringAfter("const TEMPLATES = {").substringBefore("\n};")
        val ids = Regex("^  ([a-z]+): \\{ name: '[^']+', slots: (.+?),\\s*$", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(MemeTemplates.ALL.map { it.id }.sorted(), ids.sorted())
        // Anzahl der Textfelder je Vorlage gleich
        for (t in MemeTemplates.ALL) {
            val line = block.lines().first { it.startsWith("  ${t.id}: {") }
            val slots = when {
                "slots: TOPBOT" in line -> 2
                "[0, 1, 2, 3].map" in line -> 4
                else -> Regex("\\{ x: ").findAll(line).count()
            }
            assertEquals(t.slots, slots, t.id)
        }
        // innerHTML nur für die eigenen SVG-Zeichnungen, Texte der Spieler immer per textContent
        assertEquals(2, Regex("innerHTML").findAll(html).count())
        assertTrue("<title>" in html && "name=\"description\"" in html)
    }
}
