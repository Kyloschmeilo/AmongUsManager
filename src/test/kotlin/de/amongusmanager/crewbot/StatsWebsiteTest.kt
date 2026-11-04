package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.web.CrewLook
import de.amongusmanager.crewbot.web.Cosmetics
import de.amongusmanager.crewbot.web.DatabaseSource
import de.amongusmanager.crewbot.web.PROFILE_ROUNDS
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.shortHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stats-Website (eigenes StatsWebsite.jar): liest die Bot-Datenbank nur, zählt Rohdaten (`round_raw`) und ältere
 * Runden (`round_history`) je einmal, lässt zurückgesetzte und Freeplay-Runden weg und gibt nie Friendcodes/PUIDs heraus.
 */
open class StatsWebsiteTest {
    /** Bot-Datenbank (mit Schema) und eine Nur-Lese-Verbindung darauf, wie sie die Website öffnet. */
    open fun newDatabases(): Pair<Database, () -> Database> {
        val file = Files.createTempDirectory("crewstats-web").resolve("crewbot.db")
        return Database.open(file) to { Database.openReadOnly(file) }
    }

    /** Eine leere Datenbank (ohne Tabellen), nur lesend geöffnet. */
    open fun emptyReadOnly(): Database {
        val file = Files.createTempDirectory("crewstats-web").resolve("leer.db")
        Files.createFile(file)
        return Database.openReadOnly(file)
    }

    private val example: String = javaClass.getResource("/crewstats/beispiel-runde.json")!!.readText()
    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        opened.reversed().forEach { runCatching { it.close() } }
    }

    private fun variant(gameId: String, endedAt: String? = null, lobby: String? = null): String {
        var json = example.replace(EXAMPLE_ID, gameId)
        if (endedAt != null) json = json.replace("\"endedAt\": \"2026-11-01T19:06:40Z\"", "\"endedAt\": \"$endedAt\"")
        if (lobby != null) json = json.replace("\"lobbyCode\": \"ABCDEF\"", "\"lobbyCode\": \"$lobby\"")
        return json
    }

    private class Setup(val bot: TestServices, val reader: Database, val store: StatsStore)

    private fun setup(): Setup {
        val (db, reader) = newDatabases()
        opened += db
        val bot = TestServices(db)
        bot.links.link("kilian#1234", 100)
        bot.links.link("mia#5678", 101)
        // A: Runde mit Rohdaten (ab CrewStats 1.5.0) – steht in round_raw UND round_history
        bot.modRounds.applyWithoutRoles(ModRound.parse(example), example)
        // B: ältere Runde ohne Rohdaten – nur im Verlauf
        bot.modRounds.applyWithoutRoles(ModRound.parse(variant(LEGACY_ID, endedAt = "2026-10-20T19:06:40Z")))
        // C: ausgewertet und zurückgesetzt
        val undone = variant(UNDONE_ID)
        bot.modRounds.applyWithoutRoles(ModRound.parse(undone), undone)
        bot.modRounds.undo(UNDONE_ID)
        // D: Freeplay (wertet der Bot nie aus, hier direkt archiviert)
        bot.archive.store(FREEPLAY_ID, 2, variant(FREEPLAY_ID, lobby = "FREEPLAY"))
        val readDb = reader().also { opened += it }
        return Setup(bot, readDb, StatsStore(DatabaseSource(readDb)))
    }

    private fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.takeIf { it.isString }?.content

    @Test
    fun countsArchivedAndLegacyRoundsOnce() {
        val s = setup()
        assertTrue(s.store.refresh())
        val snapshot = s.store.snapshot
        assertEquals(setOf(EXAMPLE_ID, LEGACY_ID), snapshot.views.map { it.round.gameId }.toSet())
        assertFalse(snapshot.view(EXAMPLE_ID)!!.round.fromHistory)
        assertTrue(snapshot.view(LEGACY_ID)!!.round.fromHistory)

        val overview = json(snapshot.overviewJson)
        assertEquals(2, overview.int("rounds"))
        assertEquals(1, overview.int("replays"))
        assertEquals(3, overview.int("players"))

        val players = json(snapshot.playersJson).getValue("players").jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        val kilian = players.getValue("Kilian")
        assertEquals(shortHash("crewstats-web:d:100"), kilian.str("key"))
        assertEquals("true", kilian.getValue("linked").jsonPrimitive.content)
        assertEquals(s.bot.levels.get(100).level, kilian.int("level"))
        assertEquals(2, kilian.int("games"))
        val tom = players.getValue("Tom")
        assertEquals(shortHash("crewstats-web:f:tom#9012"), tom.str("key"))
        assertEquals("false", tom.getValue("linked").jsonPrimitive.content)
        assertEquals(2, tom.int("games"))

        // nichts Neues → kein neuer Snapshot
        assertFalse(s.store.refresh())
    }

    @Test
    fun mapHasKillAndMeetingPositions() {
        val s = setup()
        s.store.refresh()
        val map = json(s.store.snapshot.mapJson(0)!!)
        // nur die Archiv-Runde hat Orte (Verlauf ohne Rohdaten zählt nur mit)
        val meetings = map.getValue("meetingPoints").jsonArray.map { it.jsonObject }
        assertEquals(2, meetings.size)
        assertEquals(listOf("Electrical", "Cafeteria"), meetings.map { it.str("room") })
        assertEquals(listOf("true", "false"), meetings.map { it.getValue("report").jsonPrimitive.content })
        assertEquals(-7.9, meetings[0].getValue("x").jsonPrimitive.content.toDouble(), 1e-6)
        val rooms = map.getValue("roomMeetings").jsonArray.map { it.jsonObject.str("room") }.toSet()
        assertEquals(setOf("Electrical", "Cafeteria"), rooms)
        assertTrue(map.getValue("kills").jsonArray.isNotEmpty())
        assertTrue(map.getValue("map").jsonObject.int("meetings") >= 2)
    }

    @Test
    fun followsNewRoundsUndoAndLinks() {
        val s = setup()
        s.store.refresh()
        val newId = "e0000000000000000000000000000005"
        val newRound = variant(newId, endedAt = "2026-11-01T20:06:40Z")
        s.bot.modRounds.applyWithoutRoles(ModRound.parse(newRound), newRound)
        assertTrue(s.store.refresh())
        assertEquals(3, s.store.snapshot.roundCount)

        s.bot.modRounds.undo(newId)
        assertTrue(s.store.refresh())
        assertEquals(2, s.store.snapshot.roundCount)
        assertNull(s.store.snapshot.view(newId))

        // Tom verknüpft sich → sein Schlüssel kommt jetzt aus der Discord-ID
        s.bot.links.link("tom#9012", 102)
        assertTrue(s.store.refresh())
        val keys = s.store.snapshot.views.flatMap { it.keys }.toSet()
        assertContains(keys, shortHash("crewstats-web:d:102"))
        assertFalse(shortHash("crewstats-web:f:tom#9012") in keys)
    }

    @Test
    fun detailsNeverContainFriendCodes() {
        val s = setup()
        s.store.refresh()
        for (gameId in listOf(EXAMPLE_ID, LEGACY_ID)) {
            val text = String(assertNotNull(s.store.roundDetail(gameId)), Charsets.UTF_8)
            for (secret in listOf("kilian#1234", "mia#5678", "tom#9012", "friendCode", "puid")) {
                assertFalse(secret in text, "$gameId enthält $secret")
            }
            val detail = Json.parseToJsonElement(text).jsonObject
            val names = detail.getValue("players").jsonArray.map { it.jsonObject.str("name") }
            assertEquals(listOf("Kilian", "Mia", "Tom"), names)
            assertTrue(detail.getValue("players").jsonArray.all { it.jsonObject.str("key") != null })
        }
        // Profile und Listen auch nicht
        val all = listOf(s.store.snapshot.overviewJson, s.store.snapshot.playersJson, s.store.snapshot.rolesJson, s.store.snapshot.mapsJson) +
            s.store.snapshot.views.flatMap { it.keys }.distinct().map { s.store.snapshot.playerJson(it)!! }
        for (bytes in all) {
            val text = String(bytes, Charsets.UTF_8)
            for (code in listOf("kilian#1234", "mia#5678", "tom#9012")) assertFalse(code in text, "Friendcode in der Ausgabe")
        }
        assertNull(s.store.roundDetail(UNDONE_ID))
        assertNull(s.store.roundDetail(FREEPLAY_ID))
    }

    @Test
    fun profileShowsRecentRounds() {
        val s = setup()
        s.store.refresh()
        val profile = json(s.store.snapshot.playerJson(shortHash("crewstats-web:d:100"))!!)
        val rounds = profile.getValue("rounds").jsonArray
        assertEquals(2, rounds.size)
        assertTrue(rounds.size <= PROFILE_ROUNDS)
        // neueste zuerst, wie in der Rundenliste (der Verlauf speichert als Ende die Empfangszeit)
        val expected = s.store.snapshot.views.map { it.round.gameId }
        assertEquals(expected, rounds.map { it.jsonObject.getValue("round").jsonObject.str("gameId") })
        assertNull(s.store.snapshot.playerJson("gibtsnicht"))
    }

    @Test
    fun playersKeepTheirMostUsedColor() {
        val s = setup()
        val kilian = shortHash("crewstats-web:d:100")
        fun play(gameId: String, endedAt: String, colorId: Int) {
            val json = variant(gameId, endedAt = endedAt).replace("\"isHost\": true, \"colorId\": 0", "\"isHost\": true, \"colorId\": $colorId")
            s.bot.modRounds.applyWithoutRoles(ModRound.parse(json), json)
        }
        fun summaryColor(): Int? {
            s.store.refresh()
            val players = json(s.store.snapshot.playersJson).getValue("players").jsonArray.map { it.jsonObject }
            return players.first { it.str("key") == kilian }["colorId"]?.jsonPrimitive?.intOrNull
        }
        assertEquals(0, summaryColor()) // nur eine Runde mit Farbe (Rot), die alte Runde hat keine
        // Gleichstand Rot : Pink → die zuletzt getragene Farbe
        play("e0000000000000000000000000000006", "2026-11-01T20:06:40Z", 3)
        assertEquals(3, summaryColor())
        play("e0000000000000000000000000000007", "2026-11-01T21:06:40Z", 0)
        assertEquals(0, summaryColor())
        // überall dieselbe Farbe: Profil, Rekorde/Verweise und Runden ohne eigene Farbe
        assertEquals(0, json(s.store.snapshot.playerJson(kilian)!!).getValue("player").jsonObject["colorId"]?.jsonPrimitive?.intOrNull)
        assertEquals(0, s.store.snapshot.colorOf(kilian))
        val legacy = json(assertNotNull(s.store.roundDetail(LEGACY_ID)))
        val inLegacy = legacy.getValue("players").jsonArray.map { it.jsonObject }.first { it.str("key") == kilian }
        assertEquals(0, inLegacy["colorId"]?.jsonPrimitive?.intOrNull)
        // in einer Runde mit Farbe zählt die Farbe dieser Runde (wie im Replay)
        val pink = json(assertNotNull(s.store.roundDetail("e0000000000000000000000000000006")))
        assertEquals(3, pink.getValue("players").jsonArray.map { it.jsonObject }.first { it.str("key") == kilian }["colorId"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun playersShowTheirMostWornCosmetics() {
        val s = setup()
        val kilian = shortHash("crewstats-web:d:100")
        fun play(gameId: String, endedAt: String, hat: String, pet: String?) {
            val json = variant(gameId, endedAt = endedAt)
                .replace("\"hat\": \"hat_pk05_Cheese\", \"skin\": null, \"visor\": null,", "\"hat\": \"$hat\", \"skin\": null, \"visor\": \"visor_pk01_Security1Visor\",")
                .replace(Regex("(\"isHost\": true.*?\"pet\": )null", RegexOption.DOT_MATCHES_ALL), "$1" + (pet?.let { "\"$it\"" } ?: "null"))
            s.bot.modRounds.applyWithoutRoles(ModRound.parse(json), json)
        }
        play("e0000000000000000000000000000006", "2026-11-01T20:06:40Z", "hat_paperhat", "pet_HamPet")
        play("e0000000000000000000000000000007", "2026-11-01T21:06:40Z", "hat_paperhat", null)
        s.store.refresh()
        val player = json(s.store.snapshot.playersJson).getValue("players").jsonArray.map { it.jsonObject }.first { it.str("key") == kilian }
        // Hut 2× Papierhut gegen 1× Käse, Haustier 1× Schinken gegen 2× keins, Visier 2× gegen 1× keins
        val url = assertNotNull(player.str("crewmate"))
        assertEquals("/api/crewmate?c=0&h=hat_paperhat&v=visor_pk01_Security1Visor", url)
        val look = json(s.store.snapshot.playerJson(kilian)!!).getValue("look").jsonObject
        assertEquals("Paperhat", look.getValue("hat").jsonObject.str("name"))
        assertEquals(JsonNull, look["pet"])
        // Spieler nur mit alten Runden (ohne Kosmetik-Daten) bekommen kein Bild
        val legacyOnly = s.store.snapshot.views.first { it.round.fromHistory }.keys.toSet() - s.store.snapshot.views.filterNot { it.round.fromHistory }.flatMap { it.keys }.toSet()
        for (key in legacyOnly) assertNull(json(s.store.snapshot.playerJson(key)!!).getValue("player").jsonObject.str("crewmate"))

        // Bild: PNG im festen Rahmen, unbekannte Teile/Farben → nichts
        val png = assertNotNull(Cosmetics.render(0, CrewLook("hat_paperhat", "visor_pk01_Security1Visor", "skin_D2Titan", "pet_HamPet")))
        val image = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(Cosmetics.FRAME_WIDTH, image.width)
        assertEquals(Cosmetics.FRAME_HEIGHT, image.height)
        assertNull(Cosmetics.render(0, CrewLook("hat_gibtsnicht", null, null, null)))
        assertNull(Cosmetics.render(0, CrewLook(null, null, null, "hat_paperhat"))) // falsche Art
        assertNull(Cosmetics.render(18, CrewLook(null, null, null, null)))
        assertNotNull(Cosmetics.render(17, CrewLook("hat_NoHat", "visor_EmptyVisor", "skin_None", "pet_EmptyPet")))
        assertEquals("Cheese", Cosmetics.displayName("hat_pk05_Cheese"))
        assertEquals("Security1", Cosmetics.displayName("visor_pk01_Security1Visor").replace(" ", ""))
        // jede Kosmetik im Bestand lässt sich zeichnen
        assertTrue(Cosmetics.known("hat", "hat_pk05_Cheese"))
    }

    @Test
    fun readOnlyDatabaseIsNotChanged() {
        val s = setup()
        assertFailsWith<Exception> { s.reader.update("DELETE FROM round_raw") }
        val empty = emptyReadOnly().also { opened += it }
        assertEquals(emptyList(), empty.tableNames()) // kein Schema angelegt
        val store = StatsStore(DatabaseSource(empty))
        store.refresh()
        assertEquals(0, store.snapshot.roundCount)
    }

    @Test
    fun serverAnswersApiAndPages() {
        val s = setup()
        s.store.refresh()
        val server = StatsServer(s.store, 0, "127.0.0.1")
        server.start()
        opened += AutoCloseable { server.stop() }
        val client = HttpClient.newHttpClient()
        fun get(path: String, method: String = "GET", etag: String? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        }

        val status = get("/api/status")
        assertEquals(200, status.statusCode())
        assertEquals(2, json(status.body().toByteArray()).int("rounds"))

        val overview = get("/api/overview")
        assertEquals(200, overview.statusCode())
        val etag = assertNotNull(overview.headers().firstValue("ETag").orElse(null))
        assertEquals(304, get("/api/overview", etag = etag).statusCode())

        val rounds = json(get("/api/rounds?limit=1").body().toByteArray())
        assertEquals(2, rounds.int("total"))
        assertEquals(1, rounds.getValue("items").jsonArray.size)
        assertEquals(2, json(get("/api/rounds?map=0&team=crew").body().toByteArray()).int("total"))
        assertEquals(0, json(get("/api/rounds?team=impostor").body().toByteArray()).int("total"))
        assertEquals(0, json(get("/api/rounds?player=gibtsnicht").body().toByteArray()).int("total"))

        val detail = get("/api/rounds/$EXAMPLE_ID")
        assertEquals(200, detail.statusCode())
        assertFalse("kilian#1234" in detail.body())
        assertEquals(404, get("/api/rounds/$UNDONE_ID").statusCode())
        assertEquals(404, get("/api/gibtsnicht").statusCode())
        assertEquals(200, get("/api/players/${shortHash("crewstats-web:d:100")}").statusCode())
        assertEquals(405, get("/api/overview", method = "POST").statusCode())

        // Seiten: index.html für jede App-Adresse, fehlende Dateien 404, nichts außerhalb von web/
        val home = get("/")
        assertEquals(200, home.statusCode())
        assertContains(home.body(), "<div id=\"root\">")
        assertEquals(home.body(), get("/spieler/abc").body())
        assertEquals(404, get("/assets/gibtsnicht.js").statusCode())
        assertEquals(404, get("/%2E%2E/logback.xml").statusCode())
        assertEquals(404, get("/logback.xml").statusCode())
        assertEquals(home.body(), get("/assets").body(), "Ordner werden nicht aufgelistet")
    }

    @Test
    fun voteStatsTellRightFromWrongSuspicion() {
        val (db, reader) = newDatabases()
        opened += db
        val bot = TestServices(db)
        bot.links.link("kilian#1234", 100)
        // Beispielrunde + ein drittes Meeting: Tom (Crew) und Mia (Impostor) wählen Kilian (Crew) raus, Kilian stimmt für Mia.
        val extra = """{"atSeconds": 270.0, "caller": 2, "body": null, "exiled": 0, "tie": false, "alive": [0, 1, 2], "votes": [
            {"voter": 2, "target": 0, "skipped": null}, {"voter": 1, "target": 0, "skipped": null}, {"voter": 0, "target": 1, "skipped": null}]}"""
        val marker = "\"meetings\": ["
        assertTrue(marker in example)
        val round = example.replace(marker, marker + extra + ",")
        bot.modRounds.applyWithoutRoles(ModRound.parse(round), round)
        val store = StatsStore(DatabaseSource(reader().also { opened += it }))
        store.refresh()
        val players = json(store.snapshot.playersJson).getValue("players").jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        val kilian = players.getValue("Kilian")
        assertEquals(2, kilian.int("crewVotes"))
        assertEquals(2, kilian.int("correctVotes"))
        assertEquals(1, kilian.int("crewSkips"))
        assertEquals(1, kilian.int("rightEjections"))
        assertEquals(0, kilian.int("wrongEjections"))
        assertEquals(1, kilian.int("suspectedAsCrew"))
        val tom = players.getValue("Tom")
        assertEquals(1, tom.int("crewVotes"))
        assertEquals(0, tom.int("correctVotes"))
        assertEquals(1, tom.int("wrongEjections"))
        val mia = players.getValue("Mia")
        assertEquals(0, mia.int("crewVotes"))
        assertEquals(2, mia.int("impostorVotes"))
        assertEquals(1, mia.int("deceptions"))
        assertEquals(2, mia.int("suspectedAsImpostor"))

        fun names(profile: JsonObject, list: String) = profile.getValue(list).jsonArray.map {
            it.jsonObject.getValue("player").jsonObject.str("name") to it.jsonObject.int("count")
        }
        val kilianProfile = json(store.snapshot.playerJson(kilian.str("key")!!)!!)
        assertEquals(listOf("Tom" to 1), names(kilianProfile, "suspectedBy"))
        assertEquals(emptyList(), names(kilianProfile, "wrongSuspects"))
        assertEquals(listOf("Kilian" to 1), names(json(store.snapshot.playerJson(tom.str("key")!!)!!), "wrongSuspects"))
        assertEquals(listOf("Kilian" to 2), names(json(store.snapshot.playerJson(mia.str("key")!!)!!), "caughtBy"))
    }

    @Test
    fun websiteIsBuilt() {
        // Die gebaute Seite liegt im Repo (src/main/resources/web), damit das JAR ohne Node gebaut werden kann
        val index = assertNotNull(javaClass.classLoader.getResource("web/index.html"), "erst in web/: npm install && npm run build").readText()
        val assets = Regex("""/assets/[A-Za-z0-9_.-]+\.(js|css)""").findAll(index).map { it.value }.toList()
        assertTrue(assets.isNotEmpty())
        for (asset in assets) assertNotNull(javaClass.classLoader.getResource("web$asset"), "$asset fehlt – Seite neu bauen")
    }

    companion object {
        const val EXAMPLE_ID = "4f1c2b8e9d0a4c7f8e1b2a3c4d5e6f70"
        const val LEGACY_ID = "b0000000000000000000000000000002"
        const val UNDONE_ID = "c0000000000000000000000000000003"
        const val FREEPLAY_ID = "d0000000000000000000000000000004"
    }
}
