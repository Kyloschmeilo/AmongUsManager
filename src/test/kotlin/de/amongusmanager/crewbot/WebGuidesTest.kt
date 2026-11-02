package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.Tutorial
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.ui.commandsIn
import de.amongusmanager.crewbot.web.DirectorySource
import de.amongusmanager.crewbot.web.Downloads
import de.amongusmanager.crewbot.web.GitHubReleases
import de.amongusmanager.crewbot.web.ReleaseAsset
import de.amongusmanager.crewbot.web.ReleaseInfo
import de.amongusmanager.crewbot.web.ReleaseSource
import de.amongusmanager.crewbot.web.StatsServer
import de.amongusmanager.crewbot.web.StatsStore
import de.amongusmanager.crewbot.web.WebGuides
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stats-Website, Bereich „Hilfe & Mods“: Tutorials, Rollen-Lexikon, Downloads (eigene Dateien + GitHub-Versionen). */
class WebGuidesTest {
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun close() = closers.reversed().forEach { it.close() }

    private fun json(bytes: ByteArray): JsonObject = parseJson(String(bytes, Charsets.UTF_8)).jsonObject

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.takeIf { it.isString }?.content

    private val crewStatsRelease = ReleaseInfo(
        tag = "v1.5.0",
        name = "CrewStats 1.5.0",
        publishedAt = "2026-11-01T19:06:28Z",
        notes = "Neu in 1.5.0:\n\n- Zeitleiste\n- Laufwege",
        url = "https://github.com/Kyloschmeilo/CrewStats/releases/tag/v1.5.0",
        assets = listOf(
            ReleaseAsset("CrewStats-Installer.bat", 11392, "https://github.com/Kyloschmeilo/CrewStats/releases/download/v1.5.0/CrewStats-Installer.bat"),
            ReleaseAsset("CrewStats.dll", 193024, "https://github.com/Kyloschmeilo/CrewStats/releases/download/v1.5.0/CrewStats.dll"),
        ),
    )
    private val torRelease = ReleaseInfo("1.4.1", null, "2026-10-11T10:00:00Z", null, "https://github.com/GMIA-Nexus/TheOtherRolesGMIA/releases/tag/1.4.1", emptyList())

    /** Download-Ordner wie auf dem Server: Installer, weitere Dateien – und was nicht ausgeliefert werden darf (auch die Messer-Mod). */
    private fun downloadsDir(withInstaller: Boolean = true): Path {
        val dir = Files.createTempDirectory("crewstats-downloads")
        if (withInstaller) Files.write(dir.resolve("TheOtherRoles-Installer.bat"), "@echo off\r\necho hallo\r\n".toByteArray())
        Files.write(dir.resolve("knifemod-1.1.0+1.21.11.jar"), ByteArray(10) { 1 })
        Files.write(dir.resolve("knifemod-1.1.0+26.3.jar"), ByteArray(12) { 2 })
        Files.write(dir.resolve("knifemod-1.1.0+26.2.jar"), ByteArray(11) { 3 })
        Files.writeString(dir.resolve("Regeln.txt"), "Fair spielen")
        Files.write(dir.resolve("Karte+Tipps.pdf"), ByteArray(12) { 4 })
        Files.writeString(dir.resolve(".env"), "DISCORD_TOKEN=geheim")
        Files.createDirectories(dir.resolve("sub")).resolve("x.txt").also { Files.writeString(it, "nein") }
        Files.writeString(dir.parent.resolve("draussen-${dir.fileName}.txt"), "nein")
        return dir
    }

    private val sameThread = Executor { it.run() }

    @Test
    fun tutorialsAreTheBotTextsForTheWeb() {
        val tutorials = json(WebGuides(null).tutorialsJson)["tutorials"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("befehle", "verknuepfen", "special-rollen", "crewstats-host", "autojoin"),
            tutorials.map { it.str("key") },
        )
        val text = tutorials.toString()
        assertFalse("<#" in text, "keine Discord-Channel-Erwähnungen auf der Website")
        assertContains(text, "den Lobby-Code-Channel")
        assertContains(text, "Modded NA")
        assertFalse("Modded EU" in text)
        assertContains(text, "TheOtherRoles-Installer.bat")
        // Discord-Sätze, die die Website weglässt, stehen noch so im Bot (sonst greift das Weglassen nicht mehr)
        val botText = TUTORIALS.joinToString { t -> t.intro + t.footer + t.sections.joinToString { it.lines.joinToString() } }
        for (sentence in WebGuides.DISCORD_ONLY) {
            assertContains(botText, sentence)
            assertFalse(sentence in text)
        }
    }

    @Test
    fun webOnlyTutorialsOnlyNameExistingCommands() {
        val commands = CrewBot(Database.inMemory()).modules.flatMap { it.commands }.map { it.name }.toSet()
        fun mentioned(t: Tutorial) = (listOf(t.intro, t.footer) + t.sections.flatMap { it.lines }).flatMap(::commandsIn).toSet()
        assertTrue("verknuepfen" in mentioned(WebGuides.HOST_TUTORIAL))
        assertTrue("autojoin" in mentioned(WebGuides.AUTOJOIN_TUTORIAL))
        for (t in listOf(WebGuides.HOST_TUTORIAL, WebGuides.AUTOJOIN_TUTORIAL)) assertEquals(emptySet(), mentioned(t) - commands, t.key)
    }

    @Test
    fun lexiconHasEveryRole() {
        val roles = json(WebGuides(null).lexiconJson)["roles"]!!.jsonArray.map { it.jsonObject }
        assertEquals(ROLES.size + SPECIAL_ROLES.size, roles.size)
        assertEquals(roles.size, roles.map { it.str("id") }.toSet().size)
        for (role in SPECIAL_ROLES.values) assertNotNull(roles.firstOrNull { it.str("id") == "tor:${role.id}" }, role.name)
        for (role in ROLES.values) assertNotNull(roles.firstOrNull { it.str("source") == "game" && it.str("name") == role.name }, role.name)

        fun role(id: String) = roles.first { it.str("id") == id }
        fun ids(role: JsonObject, field: String) = role[field]!!.jsonArray.map { it.jsonPrimitive.content }
        val guesser = role("tor:guesser")
        assertEquals("BOTH", guesser.str("team"))
        assertEquals(listOf("tor:guesser", "tor:niceGuesser", "tor:evilGuesser"), ids(guesser, "statIds"))
        assertEquals(listOf("Nice Guesser", "Evil Guesser"), ids(guesser, "variants"))
        assertEquals(listOf("tor:mafia", "tor:godfather", "tor:mafioso", "tor:janitor"), ids(role("tor:mafia"), "statIds"))
        assertEquals(listOf("game:SpiritGuide"), ids(role("game:SpiritGuide"), "statIds"))
        assertEquals("true", role("game:GuardianAngel")["ghost"]!!.jsonPrimitive.content)
        assertEquals("MODIFIER", role("tor:lovers").str("team"))
    }

    @Test
    fun downloadsCombineLocalFilesAndGitHub() {
        val dir = downloadsDir()
        val downloads = Downloads(dir, { repo -> if (repo == Downloads.CREWSTATS_REPO) crewStatsRelease else torRelease }, executor = sameThread)
        downloads.refreshNow()
        val items = json(downloads.json())["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("theotherroles", "crewstats", "weitere"), items.map { it.str("id") })
        assertFalse("knifemod" in items.toString(), "Messer-Mod gibt es auf der Website nicht")
        fun files(item: JsonObject) = item["files"]!!.jsonArray.map { it.jsonObject }

        val tor = items[0]
        assertEquals("1.4.1", tor.str("version"))
        assertNull(tor.str("missing"))
        assertEquals(listOf("/downloads/TheOtherRoles-Installer.bat"), files(tor).map { it.str("url") })
        assertContains(tor.toString(), "Modded NA")

        val crewStats = items[1]
        assertEquals("v1.5.0", crewStats.str("version"))
        assertEquals(crewStatsRelease.assets.map { it.url }, files(crewStats).map { it.str("url") })
        assertContains(crewStats.str("notes")!!, "Laufwege")

        // Weitere Dateien: keine versteckten Dateien, keine Unterordner, keine Messer-Mod; „+“ bleibt in der Adresse erhalten
        val others = files(items[2])
        assertEquals(listOf("Karte+Tipps.pdf", "Regeln.txt"), others.map { it.str("name") })
        assertEquals("/downloads/Karte%2BTipps.pdf", others[0].str("url"))

        assertNotNull(downloads.localFile("Karte+Tipps.pdf"))
        for (bad in listOf(".env", "sub/x.txt", "sub", "../draussen-${dir.fileName}.txt", "..", "", "gibtsnicht.bat", "x\\y.txt", "knifemod-1.1.0+26.3.jar")) {
            assertNull(downloads.localFile(bad), bad)
        }
    }

    @Test
    fun withoutGitHubOrInstallerTheLinksStillWork() {
        var fail = false
        val downloads = Downloads(downloadsDir(withInstaller = false), {
            if (fail) throw IllegalStateException("HTTP 403") else crewStatsRelease
        }, executor = sameThread)
        downloads.refreshNow()
        fail = true
        downloads.refreshNow() // Fehler: alte Infos bleiben
        val items = json(downloads.json())["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("v1.5.0", items.first { it.str("id") == "crewstats" }.str("version"))
        val tor = items.first { it.str("id") == "theotherroles" }
        assertEquals(0, tor["files"]!!.jsonArray.size)
        assertNotNull(tor.str("missing"))

        // nie erreicht: feste Links auf das neueste Release
        val offline = Downloads(Files.createTempDirectory("leer").resolve("fehlt"), null)
        val crewStats = json(offline.json())["items"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == "crewstats" }
        assertNull(crewStats.str("version"))
        assertEquals(
            "https://github.com/Kyloschmeilo/CrewStats/releases/latest/download/CrewStats-Installer.bat",
            crewStats["files"]!!.jsonArray[0].jsonObject.str("url"),
        )
    }

    @Test
    fun releaseLinksOnlyFromTheSameRepo() {
        val release = GitHubReleases.parseRelease(
            "Kyloschmeilo/CrewStats",
            parseJson(
                """{"tag_name":"v1.5.0","html_url":"https://evil.example/x","body":"Notizen\r\n- a",
                   "assets":[{"name":"CrewStats.dll","size":5,"browser_download_url":"https://github.com/Kyloschmeilo/CrewStats/releases/download/v1.5.0/CrewStats.dll"},
                             {"name":"Boese.exe","size":5,"browser_download_url":"https://github.com/someone/else/releases/download/v1/Boese.exe"}]}""",
            ).jsonObject,
        )!!
        assertEquals(listOf("CrewStats.dll"), release.assets.map { it.name })
        assertEquals("https://github.com/Kyloschmeilo/CrewStats/releases/latest", release.url)
        assertEquals("Notizen\n- a", release.notes)
        assertNull(GitHubReleases.parseRelease("a/b", parseJson("""{"tag_name":""}""").jsonObject))
    }

    @Test
    fun serverDeliversGuidesAndFiles() {
        val dir = downloadsDir()
        val store = StatsStore(DirectorySource(Files.createTempDirectory("runden")))
        store.refresh()
        val guides = WebGuides(Downloads(dir, null, executor = sameThread))
        val server = StatsServer(store, 0, "127.0.0.1", guides = guides)
        server.start()
        closers += AutoCloseable { server.stop() }
        val client = HttpClient.newHttpClient()
        fun get(path: String, method: String = "GET", etag: String? = null): HttpResponse<ByteArray> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }

        for (route in listOf("tutorials", "lexicon", "downloads")) {
            val response = get("/api/$route")
            assertEquals(200, response.statusCode(), route)
            val etag = response.headers().firstValue("ETag").orElseThrow()
            assertEquals(304, get("/api/$route", etag = etag).statusCode(), route)
        }

        val installer = get("/downloads/TheOtherRoles-Installer.bat")
        assertEquals(200, installer.statusCode())
        assertEquals("@echo off\r\necho hallo\r\n", String(installer.body())) // CRLF bleibt
        assertContains(installer.headers().firstValue("Content-Disposition").orElse(""), "attachment")
        assertEquals("application/octet-stream", installer.headers().firstValue("Content-Type").orElse(""))
        assertEquals(304, get("/downloads/TheOtherRoles-Installer.bat", etag = installer.headers().firstValue("ETag").orElseThrow()).statusCode())
        assertEquals(200, get("/downloads/TheOtherRoles-Installer.bat", method = "HEAD").statusCode())

        // „+“ wörtlich oder kodiert
        assertEquals(12, get("/downloads/Karte+Tipps.pdf").body().size)
        assertEquals(12, get("/downloads/Karte%2BTipps.pdf").body().size)

        for (bad in listOf("/downloads/knifemod-1.1.0+26.3.jar", "/downloads/knifemod-1.1.0%2B26.3.jar", "/downloads/.env", "/downloads/sub/x.txt", "/downloads/sub%2Fx.txt", "/downloads/%2E%2E/draussen-${dir.fileName}.txt", "/downloads/gibtsnicht.bat")) {
            assertEquals(404, get(bad).statusCode(), bad)
        }
        assertEquals(405, get("/downloads/TheOtherRoles-Installer.bat", method = "POST").statusCode())

        // App-Seiten des Bereichs liefern die Seite
        assertEquals(200, get("/hilfe/downloads").statusCode())

        // ohne Hilfe-Bereich: 404 statt Fehler
        val plain = StatsServer(store, 0, "127.0.0.1")
        plain.start()
        closers += AutoCloseable { plain.stop() }
        val response = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${plain.port}/api/tutorials")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(404, response.statusCode())
    }
}
