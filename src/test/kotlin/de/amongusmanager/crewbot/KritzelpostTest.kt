package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.web.Kritzelpost
import de.amongusmanager.crewbot.web.WebRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kritzelpost (Stille Post mit Zeichnungen): Räume, Ablauf, Sichtbarkeit, Prüfung der Zeichnungen. */
class KritzelpostTest {
    private var now = 1_000_000L
    private val game = Kritzelpost(clock = { now }, random = Random(7))

    private class Me(val room: String, val id: Int, val key: String)

    private fun call(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): Pair<Int, JsonObject> {
        val headers = if (body != null) mapOf("content-type" to "application/json") else emptyMap()
        val response = game.handle(WebRequest(method, Kritzelpost.PATH + path, query, headers, body?.toByteArray() ?: ByteArray(0)), "1.2.3.4")
        return response.status to parseJson(String(response.body, Charsets.UTF_8)).jsonObject
    }

    private fun JsonObject.s(name: String) = this[name]!!.jsonPrimitive.content
    private fun JsonObject.i(name: String) = this[name]!!.jsonPrimitive.int

    private fun create(name: String): Me {
        val (status, body) = call("POST", "/create", """{"name":"$name","color":3,"face":2}""")
        assertEquals(200, status, body.toString())
        return Me(body.s("room"), body.i("id"), body.s("key"))
    }

    private fun join(room: String, name: String): Me {
        val (status, body) = call("POST", "/join", """{"room":"${room.lowercase()}","name":"$name"}""")
        assertEquals(200, status, body.toString())
        return Me(body.s("room"), body.i("id"), body.s("key"))
    }

    private fun state(me: Me): JsonObject {
        val (status, body) = call("GET", "/state", query = mapOf("room" to me.room, "key" to me.key))
        assertEquals(200, status, body.toString())
        return body
    }

    private fun act(me: Me, type: String, extra: String = ""): Pair<Int, JsonObject> =
        call("POST", "/act", """{"room":"${me.room}","key":"${me.key}","type":"$type"${if (extra.isEmpty()) "" else ",$extra"}}""")

    private fun drawing(me: Me, id: String): Int =
        game.handle(WebRequest("GET", Kritzelpost.PATH + "/drawing", mapOf("room" to me.room, "key" to me.key, "id" to id), emptyMap()), "x").status

    private fun submitText(me: Me, text: String) {
        val s = state(me)
        val (status, body) = act(me, "submit", """"game":${s.i("game")},"step":${s.i("step")},"text":"$text"""")
        assertEquals(200, status, body.toString())
    }

    private fun submitDrawing(me: Me) {
        val s = state(me)
        val (status, body) = act(me, "submit", """"game":${s.i("game")},"step":${s.i("step")},"drawing":{"ops":[{"t":"s","c":"#FF0000","w":8.4,"p":[10,10,200.6,300]},{"t":"f","c":"#00ff00","x":5,"y":5}]}""")
        assertEquals(200, status, body.toString())
    }

    @Test
    fun wholeGameFromLobbyToGallery() {
        val anna = create("Anna")
        val ben = join(anna.room, "Ben")
        val cem = join(anna.room, "  anna \u0000")
        assertEquals(4, anna.room.length)
        // gleicher Name bekommt eine Nummer
        assertEquals(listOf("Anna", "Ben", "anna 2"), state(anna)["players"]!!.jsonArray.map { it.jsonObject.s("name") })
        assertEquals(true, state(anna)["me"]!!.jsonObject["host"]!!.jsonPrimitive.content.toBoolean())

        // nur der Host stellt ein und startet
        assertEquals(403, act(ben, "start").first)
        assertEquals(200, act(anna, "settings", """"write":30,"draw":60""").first)
        assertEquals(409, act(anna, "submit").first)
        assertEquals(200, act(anna, "start").first)

        val first = state(anna)
        assertEquals("play", first.s("phase"))
        assertEquals("write", first.s("kind"))
        assertEquals(3, first.i("steps"))
        assertEquals(30_000, first.i("left"))

        // Runde 1: schreiben – zu alte Abgaben werden abgelehnt
        assertEquals(409, act(anna, "submit", """"game":1,"step":5,"text":"x"""").first)
        submitText(anna, "Ein Hai beim Zahnarzt")
        submitText(ben, "Oma gewinnt")
        assertEquals("write", state(cem).s("kind"))
        submitText(cem, "Toaster in der Krise")

        // Runde 2: zeichnen – jeder sieht den Satz eines anderen
        val drawState = state(anna)
        assertEquals("draw", drawState.s("kind"))
        val task = drawState["task"]!!.jsonObject.s("text")
        assertTrue(task in listOf("Oma gewinnt", "Toaster in der Krise"), task)
        // kaputte Zeichnungen fliegen raus
        assertEquals(400, act(anna, "submit", """"game":1,"step":1,"drawing":{"ops":[{"t":"s","c":"red","w":8,"p":[1,2,3,4]}]}""").first)
        assertEquals(400, act(anna, "submit", """"game":1,"step":1,"drawing":{"ops":[{"t":"x","c":"#ffffff"}]}""").first)
        submitDrawing(anna)
        submitDrawing(ben)
        // Abgabe zurücknehmen und neu abgeben
        assertEquals(200, act(ben, "edit").first)
        submitDrawing(ben)
        submitDrawing(cem)

        // Runde 3: beschreiben – nur die eigene Vorlage ist abrufbar
        val guess = state(anna)
        assertEquals("describe", guess.s("kind"))
        val mine = guess["task"]!!.jsonObject.s("drawing")
        assertEquals(200, drawing(anna, mine))
        val other = (0..2).map { "1-$it-1" }.first { it != mine }
        assertEquals(404, drawing(anna, other))
        submitText(anna, "Ein Ding")
        submitText(ben, "Noch ein Ding")
        submitText(cem, "Kunst")

        // Aufdecken: Host blättert, alle sehen dasselbe
        assertEquals("reveal", state(ben).s("phase"))
        assertEquals(404, drawing(ben, "1-0-1"))
        assertEquals(403, act(ben, "next", """"chain":0,"shown":0""").first)
        repeat(2) { act(anna, "next", """"chain":0,"shown":$it""") }
        // doppelter Klick mit altem Stand blättert nicht doppelt
        act(anna, "next", """"chain":0,"shown":1""")
        val reveal = state(ben)["reveal"]!!.jsonObject
        assertEquals(2, reveal.i("shown"))
        assertEquals(3, reveal.i("total"))
        assertEquals(200, drawing(ben, "1-0-1"))
        val entries = reveal["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", entries[0].s("kind"))
        assertEquals("draw", entries[1].s("kind"))
        val text = state(ben).toString()
        assertFalse(anna.key in text || ben.key in text, "keine Schlüssel in der Antwort")

        // Reaktionen
        assertEquals(200, act(cem, "react", """"emoji":2""").first)
        assertEquals(1, state(anna)["reactions"]!!.jsonArray.size)

        var guard = 0
        while (state(anna).s("phase") == "reveal" && guard++ < 20) {
            val r = state(anna)["reveal"]!!.jsonObject
            act(anna, "next", """"chain":${r.i("chain")},"shown":${r.i("shown")}""")
        }
        val end = state(cem)
        assertEquals("end", end.s("phase"))
        val albums = end["albums"]!!.jsonArray
        assertEquals(3, albums.size)
        // jedes Buch: Satz → Zeichnung → Beschreibung, von drei verschiedenen Leuten
        for (album in albums) {
            val list = album.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "draw", "text"), list.map { it.s("kind") })
            assertEquals(3, list.map { it.s("author") }.toSet().size)
        }
        assertEquals("""{"ops":[{"t":"s","c":"#ff0000","w":8,"p":[10,10,201,300]},{"t":"f","c":"#00ff00","x":5,"y":5}]}""",
            String(game.handle(WebRequest("GET", Kritzelpost.PATH + "/drawing", mapOf("room" to cem.room, "key" to cem.key, "id" to "1-0-1"), emptyMap()), "x").body))

        // nochmal
        assertEquals(200, act(anna, "again").first)
        assertEquals("lobby", state(ben).s("phase"))
    }

    @Test
    fun timeRunsOutAndAbsentPlayersAreSkipped() {
        val anna = create("Anna")
        val ben = join(anna.room, "Ben")
        act(anna, "settings", """"write":30""")
        act(anna, "start")
        submitText(anna, "Satz")
        // Zeit vorbei (+ Gnadenfrist): Ben bekommt einen Ersatz-Satz
        now += 34_000
        val s = state(anna)
        assertEquals("draw", s.s("kind"))
        assertNotNull(s["task"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        // Anna zeichnet, Ben ist weg (keine Abfragen mehr) → die Runde wartet nicht auf ihn
        submitDrawing(anna)
        now += 5_000
        state(anna)
        now += 13_000
        val after = state(anna)
        assertEquals("reveal", after.s("phase"))
        val owner = after["reveal"]!!.jsonObject
        assertEquals(2, owner.i("chains"))
        // Ben ist offline → Anna wird nicht Host-los: sie war und bleibt Host
        assertTrue(after["me"]!!.jsonObject["host"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, after["players"]!!.jsonArray.first { it.jsonObject.s("name") == "Ben" }.jsonObject["online"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, game.roomCount())
    }

    @Test
    fun hostMovesOnAndEmptyRoomsVanish() {
        val anna = create("Anna")
        val ben = join(anna.room, "Ben")
        // Anna fragt nicht mehr ab → nach 30 s wird Ben Host
        repeat(4) { now += 10_000; state(ben) }
        assertTrue(state(ben)["me"]!!.jsonObject["host"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(200, act(ben, "kick", """"player":${anna.id}""").first)
        assertEquals(1, state(ben)["players"]!!.jsonArray.size)
        assertEquals(404, call("GET", "/state", query = mapOf("room" to anna.room, "key" to anna.key)).first)
        // niemand mehr da → Raum weg
        now += 11 * 60_000
        call("GET", "")
        assertEquals(0, game.roomCount())
        assertEquals(404, call("POST", "/join", """{"room":"${anna.room}","name":"Cem"}""").first)
    }

    @Test
    fun rejectsBadInput() {
        assertEquals(400, call("POST", "/create", """{"name":"   "}""").first)
        assertEquals(415, game.handle(WebRequest("POST", Kritzelpost.PATH + "/create", emptyMap(), emptyMap(), "{}".toByteArray()), "x").status)
        assertEquals(405, call("GET", "/create").first)
        val anna = create("Anna")
        assertEquals(409, act(anna, "start").first) // allein
        assertEquals(403, call("POST", "/act", """{"room":"${anna.room}","key":"falsch","type":"start"}""").first)
        val long = "x".repeat(500)
        join(anna.room, long)
        assertEquals(Kritzelpost.MAX_NAME, state(anna)["players"]!!.jsonArray[1].jsonObject.s("name").length)
        // Zeichnungen: Grenzen
        assertNull(Kritzelpost.cleanDrawing(parseJson("""{"ops":[{"t":"s","c":"#000000","w":0,"p":[1,1]}]}""")))
        assertNull(Kritzelpost.cleanDrawing(parseJson("""{"ops":[{"t":"s","c":"#000000","w":5,"p":[1,1,2]}]}""")))
        assertNull(Kritzelpost.cleanDrawing(parseJson("""{"ops":[{"t":"f","c":"#000000","x":800,"y":1}]}""")))
        assertNull(Kritzelpost.cleanDrawing(parseJson("""{"ops":[{"t":"s","c":"#000000\"<x>","w":5,"p":[1,1]}]}""")))
        val big = (1..40000).joinToString(",") { "${it % 700},${it % 500}" }
        assertNull(Kritzelpost.cleanDrawing(parseJson("""{"ops":[{"t":"s","c":"#000000","w":5,"p":[$big]}]}""")))
        assertEquals("""{"ops":[]}""", Kritzelpost.cleanDrawing(parseJson("""{"ops":[]}""")))
        // nur 20 Räume je Minute von einer Adresse
        repeat(25) { call("POST", "/create", """{"name":"Spam"}""") }
        assertEquals(429, call("POST", "/create", """{"name":"Spam"}""").first)
    }
}
