/*
 * Community-Abschnitt der Spiele-Startseite (21./22.11.2026, Kilian: „nur aus den Zeichnungen und Memes und changelog“,
 * klein auf der Startseite statt eigener Seite): Galerien mit den besten Memes der Meme-Schmiede und den beliebtesten
 * Zeichnungen der Kritzelkiste plus /changelog des Bots.
 *
 *   GET  /api/community                    Übersicht: ich (angemeldet/Admin), Changelog, neueste Bilder
 *   GET  /api/community/galerie?art=memes|zeichnungen&sort=neu|beste&offset=0
 *   GET  /api/community/bild/<id>          das Bild (versteckte nur für Admins)
 *   POST /api/community/herz               {id, on}  – nur angemeldet
 *   POST /api/community/meme               {room, token, n, image}  – vom Host-Browser der Meme-Schmiede am Ende einer Partie
 *   POST /api/community/admin              {a: hide|show|delete, id}  – nur Admins (Recht ADMINISTRATOR im Discord, fragt den Bot)
 *
 * Woher die Bilder kommen: Kritzelkiste-Zeichnungen malt der Server selbst aus den Strichen (DrawingRender), Memes schickt
 * der Browser des Hosts als Bild (die Vorlagen zeichnet nur die Seite) – der Server prüft dabei, dass Raum, Spieler und
 * Meme stimmen und das Meme zu den besten der Partie gehört; Autor und Punkte nimmt er aus dem Spiel, nicht vom Browser.
 * Alles liegt in data/website.db. Wer was mit Herz versehen hat, geht nie an den Browser (nur „ich“ und die Anzahl).
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.content.Changelog
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.db.Row
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** Gespeicherte Galerie und Herzen (Website-Datenbank). */
class CommunityStore(private val db: Database, private val clock: () -> Instant = Instant::now) {
    class Item(
        val id: Long, val kind: String, val title: String, val author: String, val info: String,
        val createdAt: Instant, val width: Int, val height: Int, val hidden: Boolean, val likes: Int,
    )

    init {
        db.update(
            "CREATE TABLE IF NOT EXISTS web_gallery (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, source TEXT NOT NULL UNIQUE, " +
                "title TEXT NOT NULL, author TEXT NOT NULL, info TEXT NOT NULL, created_at INTEGER NOT NULL, image BLOB NOT NULL, " +
                "image_type TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, " +
                "hidden_by INTEGER, hidden_at INTEGER)",
        )
        db.update("CREATE INDEX IF NOT EXISTS web_gallery_kind ON web_gallery (kind, hidden, created_at)")
        db.update(
            "CREATE TABLE IF NOT EXISTS web_gallery_likes (item_id INTEGER NOT NULL, voter_id INTEGER NOT NULL, " +
                "liked_at INTEGER NOT NULL, PRIMARY KEY (item_id, voter_id))",
        )
    }

    /** Neues Bild, null = gab es schon (gleiche Quelle, z.B. zweimal hochgeladen). */
    fun add(kind: String, source: String, title: String, author: String, info: String, image: ByteArray, type: String, width: Int, height: Int): Long? {
        if (db.queryOne("SELECT id FROM web_gallery WHERE source = ?", source) { it.long("id") } != null) return null
        return runCatching {
            db.insert(
                "INSERT INTO web_gallery (kind, source, title, author, info, created_at, image, image_type, width, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                kind, source, title, author, info, clock(), image, type, width, height,
            )
        }.getOrNull()
    }

    private val columns = "g.id, g.kind, g.title, g.author, g.info, g.created_at, g.width, g.height, g.hidden, " +
        "(SELECT COUNT(*) FROM web_gallery_likes l WHERE l.item_id = g.id) AS likes"

    private fun item(row: Row) = Item(
        row.long("id"), row.string("kind"), row.string("title"), row.string("author"), row.string("info"),
        row.instant("created_at"), row.int("width"), row.int("height"), row.bool("hidden"), row.int("likes"),
    )

    fun list(kind: String?, best: Boolean, withHidden: Boolean, offset: Int, limit: Int): List<Item> {
        val where = buildList {
            if (kind != null) add("g.kind = ?")
            if (!withHidden) add("g.hidden = 0")
        }.joinToString(" AND ").let { if (it.isEmpty()) "" else "WHERE $it" }
        val order = if (best) "likes DESC, g.created_at DESC" else "g.created_at DESC, g.id DESC"
        val params = listOfNotNull(kind).toTypedArray<Any?>()
        return db.query("SELECT $columns FROM web_gallery g $where ORDER BY $order LIMIT $limit OFFSET $offset", *params) { item(it) }
    }

    fun count(kind: String?, withHidden: Boolean): Int {
        val where = buildList {
            if (kind != null) add("kind = ?")
            if (!withHidden) add("hidden = 0")
        }.joinToString(" AND ").let { if (it.isEmpty()) "" else "WHERE $it" }
        return db.queryOne("SELECT COUNT(*) AS n FROM web_gallery $where", *listOfNotNull(kind).toTypedArray<Any?>()) { it.int("n") } ?: 0
    }

    fun get(id: Long): Item? = db.queryOne("SELECT $columns FROM web_gallery g WHERE g.id = ?", id) { item(it) }

    /** Bild + Medientyp + versteckt? */
    fun image(id: Long): Triple<ByteArray, String, Boolean>? =
        db.queryOne("SELECT image, image_type, hidden FROM web_gallery WHERE id = ?", id) {
            Triple(it.bytesOrNull("image") ?: ByteArray(0), it.string("image_type"), it.bool("hidden"))
        }

    fun like(id: Long, voter: Long, on: Boolean) {
        db.update("DELETE FROM web_gallery_likes WHERE item_id = ? AND voter_id = ?", id, voter)
        if (on) db.update("INSERT INTO web_gallery_likes (item_id, voter_id, liked_at) VALUES (?, ?, ?)", id, voter, clock())
    }

    /** Welche dieser Bilder [voter] mit Herz versehen hat. */
    fun likedBy(voter: Long, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val marks = ids.joinToString(",") { "?" }
        return db.query("SELECT item_id FROM web_gallery_likes WHERE voter_id = ? AND item_id IN ($marks)", voter, *ids.toTypedArray()) { it.long("item_id") }.toSet()
    }

    fun setHidden(id: Long, hidden: Boolean, by: Long): Boolean =
        db.update("UPDATE web_gallery SET hidden = ?, hidden_by = ?, hidden_at = ? WHERE id = ?", hidden, if (hidden) by else null, if (hidden) clock() else null, id) > 0

    fun delete(id: Long): Boolean = db.transaction {
        db.update("DELETE FROM web_gallery_likes WHERE item_id = ?", id)
        db.update("DELETE FROM web_gallery WHERE id = ?", id) > 0
    }

}

/** Prüfregeln (ohne Datenbank, getestet in `CommunityTest`). */
object CommunityRules {
    const val MAX_IMAGE = 900 * 1024
    const val MAX_SIDE = 1600

    class Decoded(val bytes: ByteArray, val type: String, val width: Int, val height: Int)

    /** `data:image/png;base64,…` oder `data:image/jpeg;base64,…` → echtes, lesbares Bild in erlaubter Größe, sonst null. */
    fun decodeImage(dataUrl: String): Decoded? {
        val type = when {
            dataUrl.startsWith("data:image/png;base64,") -> "image/png"
            dataUrl.startsWith("data:image/jpeg;base64,") -> "image/jpeg"
            else -> return null
        }
        val bytes = runCatching { Base64.getDecoder().decode(dataUrl.substringAfter(',')) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE) return null
        val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        if ((type == "image/png" && !png) || (type == "image/jpeg" && !jpeg)) return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width < 50 || image.height < 50 || image.width > MAX_SIDE || image.height > MAX_SIDE) return null
        return Decoded(bytes, type, image.width, image.height)
    }

    /** Text ohne Steuerzeichen (Zeilenumbrüche bleiben), gekürzt. */
    fun clean(raw: String, max: Int, lines: Boolean): String {
        val text = raw.replace("\r\n", "\n").filter { (lines && it == '\n') || !it.isISOControl() }.replace('‮', ' ')
        val tidy = if (lines) text.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n") else text.replace(Regex("\\s+"), " ")
        return tidy.trim().take(max).trim()
    }

}

class Community(
    private val store: CommunityStore,
    private val auth: WebAuth?,
    private val accounts: WebAccounts?,
    /** Spiele, aus denen Bilder kommen (null = ohne). */
    private val memes: MemeGame?,
    /** Ist diese Discord-ID Admin? (fragt den Bot über die Auftrags-Tabelle, plus COMMUNITY_ADMINS aus der .env) */
    private val isAdmin: (Long) -> Boolean,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(Community::class.java)
    private val calls = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    private val renderer = Executors.newSingleThreadExecutor { task -> Thread(task, "galerie").apply { isDaemon = true } }

    fun handles(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    /** Kritzelkiste: beliebteste Zeichnungen einer Partie (läuft im Spiel-Lock → Malen im eigenen Thread). */
    fun drawings(pictures: List<DrawGame.Picture>) {
        renderer.execute {
            for (p in pictures) {
                try {
                    val png = DrawingRender.png(p.ops)
                    val info = buildList {
                        add("${p.likes}× gefeiert")
                        if (p.guessers > 0) add("${p.guessed} von ${p.guessers} erraten")
                    }.joinToString(" · ")
                    store.add("drawing", p.source, p.word, p.drawer, info, png, "image/png", DrawingRender.W, DrawingRender.H)
                } catch (e: Exception) {
                    log.warn("Zeichnung nicht gespeichert: {}", e.toString())
                }
            }
        }
    }

    fun handle(request: WebRequest, client: String): WebResponse {
        val sub = request.path.removePrefix(PATH).trim('/')
        if (request.method == "GET" || request.method == "HEAD") {
            return when {
                sub.isEmpty() -> json(200, overview(request))
                sub == "galerie" -> json(200, gallery(request))
                sub.startsWith("bild/") -> image(request, sub.removePrefix("bild/"))
                else -> json(404, obj("error" to "Nicht gefunden"))
            }
        }
        if (request.method != "POST") return WebResponse(405, body = bytes(obj("error" to "Nur GET oder POST")), headers = listOf("Allow" to "GET, POST", NO_STORE))
        if (sub !in setOf("herz", "meme", "admin")) return json(404, obj("error" to "Nicht gefunden"))
        auth?.sameOrigin(request)?.let { return it }
        if (request.header("content-type")?.startsWith("application/json") != true) return json(415, obj("error" to "Nur JSON"))
        val body = runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return json(400, obj("error" to "Ungültige Eingabe"))
        return when (sub) {
            "meme" -> {
                if (!allow("ip:$client", 20)) return json(429, obj("error" to "Zu viele Bilder – warte kurz."))
                meme(body)
            }
            "herz" -> {
                val user = auth?.userOf(request) ?: return json(401, obj("error" to "Zum Verteilen von Herzen bitte mit Discord anmelden.", "login" to true))
                if (!allow("u:$user", 60)) return json(429, obj("error" to "Zu viele Klicks – warte kurz."))
                heart(body, user)
            }
            else -> {
                val user = auth?.userOf(request) ?: return json(401, obj("error" to "Bitte zuerst mit Discord anmelden.", "login" to true))
                if (!isAdmin(user)) return json(403, obj("error" to "Dafür brauchst du die Admin-Rolle."))
                if (!allow("a:$user", 60)) return json(429, obj("error" to "Zu viele Klicks – warte kurz."))
                admin(body, user)
            }
        }
    }

    // ---------- Anzeige ----------

    private fun viewer(request: WebRequest): Pair<Long?, Boolean> {
        val user = auth?.userOf(request)
        return user to (user != null && isAdmin(user))
    }

    private fun overview(request: WebRequest): JsonObject {
        val (user, admin) = viewer(request)
        val latestMemes = store.list("meme", best = false, withHidden = admin, offset = 0, limit = LATEST)
        val latestDrawings = store.list("drawing", best = false, withHidden = admin, offset = 0, limit = LATEST)
        val liked = if (user != null) store.likedBy(user, (latestMemes + latestDrawings).map { it.id }) else emptySet()
        return obj(
            "me" to obj(
                "loginEnabled" to (auth?.loginEnabled == true),
                "loggedIn" to (user != null),
                "admin" to admin,
                "name" to user?.let { accounts?.discordUser(it)?.shownName },
            ),
            "counts" to obj("memes" to store.count("meme", false), "drawings" to store.count("drawing", false)),
            "latest" to obj("memes" to latestMemes.map { itemJson(it, liked, admin) }, "drawings" to latestDrawings.map { itemJson(it, liked, admin) }),
            "changelog" to obj(
                "version" to Changelog.VERSION,
                "sections" to Changelog.sections.map { obj("title" to it.title, "lines" to it.lines) },
            ),
        )
    }

    private fun gallery(request: WebRequest): JsonObject {
        val (user, admin) = viewer(request)
        val kind = when (request.query["art"]) {
            "memes" -> "meme"
            "zeichnungen" -> "drawing"
            else -> null
        }
        val best = request.query["sort"] == "beste"
        val offset = (request.query["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 100_000)
        val items = store.list(kind, best, withHidden = admin, offset = offset, limit = PAGE + 1)
        val page = items.take(PAGE)
        val liked = if (user != null) store.likedBy(user, page.map { it.id }) else emptySet()
        return obj(
            "items" to page.map { itemJson(it, liked, admin) },
            "more" to (items.size > PAGE),
            "total" to store.count(kind, admin),
            "loggedIn" to (user != null),
            "admin" to admin,
        )
    }

    private fun itemJson(item: CommunityStore.Item, liked: Set<Long>, admin: Boolean): JsonObject = obj(
        "id" to item.id,
        "kind" to if (item.kind == "meme") "meme" else "drawing",
        "title" to item.title,
        "author" to item.author,
        "info" to item.info,
        "at" to item.createdAt,
        "w" to item.width,
        "h" to item.height,
        "likes" to item.likes,
        "liked" to (item.id in liked),
        "src" to "/api/community/bild/${item.id}",
        "hidden" to if (admin) item.hidden else null,
    )

    private fun image(request: WebRequest, raw: String): WebResponse {
        val id = raw.toLongOrNull() ?: return json(404, obj("error" to "Nicht gefunden"))
        val (bytes, type, hidden) = store.image(id) ?: return json(404, obj("error" to "Nicht gefunden"))
        if (hidden && !viewer(request).second) return json(404, obj("error" to "Nicht gefunden"))
        // versteckte Bilder nicht zwischenspeichern, sonst sieht man sie nach dem Ausblenden noch
        val cache = if (hidden) "private, no-store" else "public, max-age=300"
        return WebResponse(200, type = type, body = bytes, headers = listOf("Cache-Control" to cache))
    }

    // ---------- Aktionen ----------

    private fun meme(body: JsonObject): WebResponse {
        val game = memes ?: return json(404, obj("error" to "Nicht gefunden"))
        val share = game.share(body.str("room") ?: "", body.str("token") ?: "", body.int("n") ?: -1)
            ?: return json(403, obj("error" to "Dieses Meme gehört nicht zu den besten einer beendeten Partie."))
        val image = CommunityRules.decodeImage(body.str("image") ?: "") ?: return json(400, obj("error" to "Kein gültiges Bild"))
        val title = CommunityRules.clean(share.captions.filter { it.isNotBlank() }.joinToString(" / "), 160, lines = false)
        val info = "Runde ${share.round} · ${share.points} Punkte"
        val id = store.add("meme", share.source, title, share.author, info, image.bytes, image.type, image.width, image.height)
        return json(200, obj("ok" to true, "id" to id, "already" to (id == null)))
    }

    private fun heart(body: JsonObject, user: Long): WebResponse {
        val id = body.long("id") ?: return json(400, obj("error" to "Ungültige Eingabe"))
        val item = store.get(id)?.takeIf { !it.hidden } ?: return json(404, obj("error" to "Dieses Bild gibt es nicht mehr."))
        store.like(item.id, user, body.bool("on") != false)
        val fresh = store.get(id)!!
        return json(200, obj("id" to id, "likes" to fresh.likes, "liked" to (body.bool("on") != false)))
    }

    private fun admin(body: JsonObject, user: Long): WebResponse {
        return when (body.str("a")) {
            "hide", "show" -> {
                val id = body.long("id") ?: return json(400, obj("error" to "Ungültige Eingabe"))
                if (!store.setHidden(id, body.str("a") == "hide", user)) return json(404, obj("error" to "Dieses Bild gibt es nicht mehr."))
                log.info("Galerie: Bild {} {} (Admin {})", id, if (body.str("a") == "hide") "ausgeblendet" else "wieder gezeigt", shortHash("log:$user"))
                json(200, obj("ok" to true))
            }
            "delete" -> {
                val id = body.long("id") ?: return json(400, obj("error" to "Ungültige Eingabe"))
                if (!store.delete(id)) return json(404, obj("error" to "Dieses Bild gibt es nicht mehr."))
                log.info("Galerie: Bild {} gelöscht (Admin {})", id, shortHash("log:$user"))
                json(200, obj("ok" to true))
            }
            else -> json(400, obj("error" to "Unbekannte Aktion"))
        }
    }

    private fun allow(key: String, perMinute: Int): Boolean {
        val now = clock()
        if (calls.size > 5000) calls.clear()
        val window = calls.getOrPut(key) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first().plus(Duration.ofMinutes(1)).isBefore(now)) window.removeFirst()
            if (window.size >= perMinute) return false
            window.addLast(now)
            return true
        }
    }

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(NO_STORE))

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val PATH = "/api/community"
        const val PAGE = 24

        /** So viele neueste Bilder je Art zeigt der kleine Abschnitt auf der Startseite. */
        const val LATEST = 6

        /** Größter Anfrage-Inhalt (Meme als Bild, base64). */
        const val MAX_BODY = 1_300_000
        private val NO_STORE = "Cache-Control" to "no-store"

        /** Admins aus der .env (COMMUNITY_ADMINS=Discord-ID,Discord-ID) – falls der Bot nicht erreichbar ist. */
        fun adminIds(raw: String): Set<Long> = raw.split(',', ' ', ';').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }
}
