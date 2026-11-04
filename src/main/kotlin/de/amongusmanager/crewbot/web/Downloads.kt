/*
 * Stats-Website, Bereich „Hilfe & Mods“: Mod-Downloads.
 *
 * Zwei Quellen:
 *  - eigene Dateien im Ordner `downloads/` neben dem JAR (WEB_DOWNLOADS_DIR) – z.B. der TheOtherRoles-Installer, der
 *    bewusst nicht auf GitHub liegt. Die Website liefert sie selbst aus. Die Messer-Mod (Minecraft) bietet sie bewusst
 *    nicht an (Kilian, 02.11.2026) – liegen ihre JARs noch im Ordner, werden sie weder gezeigt noch ausgeliefert.
 *  - GitHub-Releases (CrewStats, The Other Roles GMIA): nur die Release-Infos (Version, Datum, Notizen) holt der Server
 *    alle 30 Minuten von der GitHub-API, die Dateien lädt der Browser direkt von github.com.
 * Ohne GitHub (Fehler, Rate-Limit) bleiben die Links trotzdem gültig: …/releases/latest/download/<Datei>.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.content.TOR_GITHUB
import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Neueste Version eines GitHub-Repos. */
data class ReleaseInfo(
    val tag: String,
    val name: String?,
    val publishedAt: String?,
    val notes: String?,
    val url: String,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(val name: String, val size: Long, val url: String)

/** Wo die Release-Infos herkommen (im Test ein Fake). */
fun interface ReleaseSource {
    /** null = Repo hat (noch) kein Release; Fehler = Exception. */
    fun latest(repo: String): ReleaseInfo?
}

/** GitHub-REST-API ohne Token (60 Anfragen/Stunde reichen: 2 Repos alle 30 Minuten). */
class GitHubReleases : ReleaseSource {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun latest(repo: String): ReleaseInfo? {
        val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$repo/releases/latest"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CrewStats-Website")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) throw IllegalStateException("GitHub antwortet mit HTTP ${response.statusCode()}")
        return parseRelease(repo, parseJson(response.body()) as? JsonObject ?: return null)
    }

    companion object {
        /** Antwort von `releases/latest` → [ReleaseInfo]; Datei-Links nur aus genau diesem Repo. */
        fun parseRelease(repo: String, json: JsonObject): ReleaseInfo? {
            val tag = json.str("tag_name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val assetPrefix = "https://github.com/$repo/releases/download/"
            val assets = (json["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
                val asset = element as? JsonObject ?: return@mapNotNull null
                val name = asset.str("name") ?: return@mapNotNull null
                val url = asset.str("browser_download_url")?.takeIf { it.startsWith(assetPrefix) } ?: return@mapNotNull null
                ReleaseAsset(name, (asset["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0, url)
            }
            return ReleaseInfo(
                tag = tag,
                name = json.str("name")?.trim()?.takeIf { it.isNotEmpty() },
                publishedAt = json.str("published_at"),
                notes = json.str("body")?.replace("\r\n", "\n")?.trim()?.take(MAX_NOTES)?.takeIf { it.isNotEmpty() },
                url = json.str("html_url")?.takeIf { it.startsWith("https://github.com/$repo/") } ?: "https://github.com/$repo/releases/latest",
                assets = assets,
            )
        }

        private const val MAX_NOTES = 2000
    }
}

class Downloads(
    /** Ordner mit eigenen Dateien (darf fehlen – dann gibt es nur die GitHub-Downloads). */
    val dir: Path,
    private val releases: ReleaseSource?,
    private val now: () -> Instant = Instant::now,
    /** Hier laufen die GitHub-Abfragen, damit keine Seite darauf wartet. */
    private val executor: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "web-downloads").apply { isDaemon = true } },
) {
    private val log = LoggerFactory.getLogger(Downloads::class.java)

    private class Cached(val info: ReleaseInfo?, val at: Instant, val ok: Boolean)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val refreshing = AtomicBoolean(false)
    private val lastErrors = ConcurrentHashMap<String, String>()

    /** Eine eigene Datei aus [dir] (nur direkt im Ordner, keine versteckten, keine Links nach draußen), sonst null. */
    fun localFile(name: String): Path? {
        if (!offered(name) || ".." in name) return null
        if (!Files.isDirectory(dir)) return null
        val root = dir.toRealPath()
        val file = root.resolve(name).normalize()
        if (file.parent != root || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return file
    }

    /** Alle eigenen Dateien (Name → Größe), alphabetisch. */
    fun localFiles(): List<Pair<String, Long>> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { offered(it.fileName.toString()) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { it.fileName.toString() to Files.size(it) }
                .sortedBy { it.first.lowercase() }
        }
    }

    /** Release-Infos jetzt holen (wartet darauf) – beim Start und in Tests. */
    fun refreshNow() {
        val source = releases ?: return
        for (repo in REPOS) {
            try {
                cache[repo] = Cached(source.latest(repo), now(), ok = true)
                if (lastErrors.remove(repo) != null) log.info("GitHub-Release von {} wieder lesbar", repo)
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                if (lastErrors.put(repo, message) != message) log.warn("GitHub-Release von {} nicht lesbar: {}", repo, message)
                // alte Infos behalten, nur den Zeitpunkt merken (neuer Versuch nach RETRY)
                cache[repo] = Cached(cache[repo]?.info, now(), ok = false)
            }
        }
    }

    /** Im Hintergrund auffrischen, wenn etwas veraltet ist (höchstens eine Abfrage gleichzeitig). */
    private fun refreshIfStale() {
        if (releases == null) return
        val time = now()
        val stale = REPOS.any { repo ->
            val entry = cache[repo]
            entry == null || entry.at.plus(if (entry.ok) FRESH else RETRY).isBefore(time)
        }
        if (!stale || !refreshing.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    refreshNow()
                } finally {
                    refreshing.set(false)
                }
            }
        } catch (e: Exception) {
            refreshing.set(false)
        }
    }

    private fun release(repo: String): ReleaseInfo? = cache[repo]?.info

    /** Antwort für /api/downloads. */
    fun json(): ByteArray {
        refreshIfStale()
        val files = localFiles()
        val used = HashSet<String>()
        fun local(name: String): Pair<String, Long>? = files.firstOrNull { it.first.equals(name, ignoreCase = true) }?.also { used += it.first }

        val items = mutableListOf<Any>()

        // ---------- The Other Roles (Installer liegt nur hier, nicht auf GitHub) ----------
        val tor = release(TOR_REPO)
        val torInstaller = local(TOR_INSTALLER)
        items += obj(
            "id" to "theotherroles",
            "title" to "The Other Roles",
            "game" to "Among Us",
            "audience" to "Für alle Spieler",
            "summary" to "Über 90 neue Rollen für Among Us (Steam oder Epic Games). Bei Steam legt der Installer eine eigene " +
                "Kopie von Among Us mit der Mod an – dein normales Among Us bleibt unverändert.",
            "version" to tor?.tag,
            "versionNote" to tor?.let { "Der Installer lädt immer die neueste Version der Mod (gerade ${it.tag})." },
            "publishedAt" to tor?.publishedAt,
            "files" to listOfNotNull(torInstaller?.let { localFileJson(it, "Installer herunterladen", primary = true) }),
            "missing" to if (torInstaller == null) "Den Installer gibt es gerade nur im Discord – frag einen Admin." else null,
            "steps" to listOf(
                "Installer herunterladen und doppelklicken. Warnt Windows, auf **Weitere Informationen → Trotzdem ausführen** klicken",
                "Enter drücken – der Installer findet Among Us über Steam oder Epic Games und installiert alles",
                "Spielen über die neue Desktop-Verknüpfung **Among Us - The Other Roles** (Steam: Steam muss laufen). Der erste Start dauert 1–2 Minuten",
            ),
            "points" to listOf(
                "Gespielt wird auf **Modded NA** – der Installer stellt den Server schon ein",
                "Epic Games: Die Mod kommt direkt in dein Among Us. Ohne Mod spielen: Verknüpfung **Among Us - ohne The Other Roles**. " +
                    "Fragt Windows nach Administratorrechten, erlauben",
                "**Alle in der Lobby** brauchen die Mod in derselben Version",
                "Hosts und alle mit Auto-Join: erst CrewStats installieren, dann diesen Installer – er übernimmt CrewStats " +
                    "samt URLs. Hosts: lass es an, es verhindert Kicks, wenn jemand der Lobby beitritt",
                "Neue Version oder Among-Us-Update? Installer einfach nochmal starten. Deinstallieren: Installer starten und `2` wählen",
            ),
            "notes" to null,
            "links" to listOf(
                obj("label" to "Anleitung", "to" to "/hilfe?t=special-rollen"),
                obj("label" to "Alle Rollen", "to" to "/rollen"),
                obj("label" to "Mod auf GitHub", "href" to TOR_GITHUB),
            ),
        )

        // ---------- CrewStats (öffentliches Repo, Dateien direkt von GitHub) ----------
        val crewStats = release(CREWSTATS_REPO)
        fun crewStatsFile(name: String, label: String, primary: Boolean): JsonObject {
            val asset = crewStats?.assets?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            return obj(
                "name" to name,
                "label" to label,
                "url" to (asset?.url ?: "https://github.com/$CREWSTATS_REPO/releases/latest/download/$name"),
                "size" to asset?.size,
                "external" to true,
                "primary" to primary,
            )
        }
        items += obj(
            "id" to "crewstats",
            "title" to "CrewStats",
            "game" to "Among Us",
            "audience" to "Für Hosts · Auto-Join für alle",
            "summary" to "Zeichnet als Host jede Runde auf und schickt sie an den Bot: XP, Wetten, /stats und diese Website. " +
                "Dazu Discord-Auto-Mute, Rollenwünsche und Auto-Join: Among Us tritt der neuesten Lobby von selbst bei.",
            "version" to crewStats?.tag,
            "versionNote" to null,
            "publishedAt" to crewStats?.publishedAt,
            "files" to listOf(
                crewStatsFile(CREWSTATS_INSTALLER, "Installer herunterladen", primary = true),
                crewStatsFile("CrewStats.dll", "Nur die DLL", primary = false),
            ),
            "missing" to null,
            "steps" to listOf(
                "Installer herunterladen und doppelklicken (Warnung: **Weitere Informationen → Trotzdem ausführen**)",
                "Enter drücken – er findet Among Us (Steam oder Epic Games) und installiert BepInEx (64-Bit) und die Mod",
                "Nur Hosts: Die **Webhook-URL** bekommst du von einem Admin. Sie ist geheim – nicht weitergeben. " +
                    "Alle anderen drücken Enter",
                "Für Auto-Join bei **Auto-Join-URL** die URL einfügen, die dir /autojoin im Discord zeigt",
                "Einmal im Discord den Friendcode verknüpfen: /verknuepfen",
            ),
            "points" to listOf(
                "Aktualisiert sich beim Spielstart selbst",
                "Auto-Join im Hauptmenü: **F5** = sofort beitreten, **F6** = an/aus",
                "Auto-Mute an/aus im Spiel: **ESC → Allgemein → Auto-Mute**",
                "Spielst du The Other Roles: danach den TheOtherRoles-Installer starten, er übernimmt CrewStats samt URLs",
            ),
            "notes" to crewStats?.notes,
            "links" to listOf(
                obj("label" to "Anleitung für Hosts", "to" to "/hilfe?t=crewstats-host"),
                obj("label" to "Auto-Join einrichten", "to" to "/hilfe?t=autojoin"),
                obj("label" to "Alle Versionen", "href" to "https://github.com/$CREWSTATS_REPO/releases"),
            ),
        )

        // ---------- alles andere im Ordner ----------
        val others = files.filter { it.first !in used }
        if (others.isNotEmpty()) {
            items += obj(
                "id" to "weitere",
                "title" to "Weitere Dateien",
                "game" to null,
                "audience" to null,
                "summary" to "Vom Admin hochgeladen.",
                "version" to null,
                "versionNote" to null,
                "publishedAt" to null,
                "files" to others.mapIndexed { index, file -> localFileJson(file, file.first, primary = index == 0) },
                "missing" to null,
                "steps" to emptyList<String>(),
                "points" to emptyList<String>(),
                "notes" to null,
                "links" to emptyList<Any>(),
            )
        }
        return bytes(obj("items" to items))
    }

    private fun localFileJson(file: Pair<String, Long>, label: String, primary: Boolean): JsonObject = obj(
        "name" to file.first,
        "label" to label,
        "url" to "/downloads/" + URLEncoder.encode(file.first, Charsets.UTF_8).replace("+", "%20"),
        "size" to file.second,
        "external" to false,
        "primary" to primary,
    )

    companion object {
        const val TOR_REPO = "GMIA-Nexus/TheOtherRolesGMIA"
        const val CREWSTATS_REPO = "Kyloschmeilo/CrewStats"
        const val TOR_INSTALLER = "TheOtherRoles-Installer.bat"
        const val CREWSTATS_INSTALLER = "CrewStats-Installer.bat"
        val REPOS = listOf(CREWSTATS_REPO, TOR_REPO)

        private val FRESH: Duration = Duration.ofMinutes(30)
        private val RETRY: Duration = Duration.ofMinutes(5)

        /** Dateinamen, die ausgeliefert werden: keine versteckten, keine Pfade. */
        private val SAFE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._+() -]{0,150}""")

        /** Messer-Mod-JARs (knifemod-1.1.0+26.3.jar …): soll es auf der Website nicht geben, auch wenn sie noch im Ordner liegen. */
        private val HIDDEN = Regex("""(?i)knifemod-.*\.jar""")

        private fun offered(name: String) = SAFE_NAME.matches(name) && !HIDDEN.matches(name)

        /** Content-Type für eigene Dateien (alles wird als Download angeboten). */
        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jar" -> "application/java-archive"
            "zip" -> "application/zip"
            "txt", "md" -> "text/plain; charset=utf-8"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
