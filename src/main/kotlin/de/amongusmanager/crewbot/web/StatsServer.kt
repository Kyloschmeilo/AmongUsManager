/*
 * Stats-Website: kleiner HTTP-Server (aus dem JDK) für die JSON-API unter /api/ und die fertig gebaute Seite
 * (React, Quelltext in web/ im Repo, gebaut nach src/main/resources/web). Nur GET/HEAD, nichts wird gespeichert.
 */
package de.amongusmanager.crewbot.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

class StatsServer(private val store: StatsStore, port: Int, host: String = "0.0.0.0") {
    private val log = LoggerFactory.getLogger(StatsServer::class.java)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 64)
    private val executor: ExecutorService = Executors.newFixedThreadPool(8) { task -> Thread(task, "web").apply { isDaemon = true } }
    private val static = StaticFiles("web")

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    fun stop() {
        server.stop(1)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod.uppercase()
            if (method != "GET" && method != "HEAD") {
                exchange.responseHeaders.add("Allow", "GET, HEAD")
                return respond(exchange, 405, TYPE_JSON, error("Nur GET"), cache = "no-store")
            }
            val path = URLDecoder.decode(exchange.requestURI.rawPath ?: "/", Charsets.UTF_8)
            if (path == "/api" || path.startsWith("/api/")) api(exchange, path.removePrefix("/api").trim('/')) else staticFile(exchange, path)
        } catch (e: Exception) {
            log.warn("Fehler bei {} {}: {}", exchange.requestMethod, exchange.requestURI, e.toString())
            try {
                respond(exchange, 500, TYPE_JSON, error("Interner Fehler"), cache = "no-store")
            } catch (ignored: Exception) {
                // Antwort war schon unterwegs
            }
        } finally {
            exchange.close()
        }
    }

    // ---------- API ----------

    private fun api(exchange: HttpExchange, route: String) {
        val snapshot = store.snapshot
        val etag = "W/\"s${snapshot.version}\""
        val parts = route.split('/').filter { it.isNotEmpty() }
        val query = parseQuery(exchange.requestURI.rawQuery)

        if (parts == listOf("status")) {
            val body = bytes(obj("rounds" to snapshot.roundCount, "updatedAt" to snapshot.builtAt, "ok" to (store.lastError == null)))
            return respond(exchange, 200, TYPE_JSON, body, cache = "no-store")
        }
        if (exchange.requestHeaders.getFirst("If-None-Match") == etag) return notModified(exchange, etag)

        val body: ByteArray? = when {
            parts == listOf("overview") -> snapshot.overviewJson
            parts == listOf("players") -> snapshot.playersJson
            parts.size == 2 && parts[0] == "players" -> snapshot.playerJson(parts[1])
            parts == listOf("rounds") -> snapshot.roundsJson(
                offset = query["offset"]?.toIntOrNull() ?: 0,
                limit = query["limit"]?.toIntOrNull() ?: 30,
                mapId = query["map"]?.toIntOrNull(),
                playerKey = query["player"]?.takeIf { it.isNotEmpty() },
                team = query["team"]?.uppercase()?.takeIf { it.isNotEmpty() },
            )
            parts.size == 2 && parts[0] == "rounds" -> store.roundDetail(parts[1])
            parts == listOf("maps") -> snapshot.mapsJson
            parts.size == 2 && parts[0] == "maps" -> parts[1].toIntOrNull()?.let { snapshot.mapJson(it) }
            parts == listOf("roles") -> snapshot.rolesJson
            else -> null
        }
        if (body == null) return respond(exchange, 404, TYPE_JSON, error("Nicht gefunden"), cache = "no-cache")
        respond(exchange, 200, TYPE_JSON, body, cache = "no-cache", etag = etag)
    }

    private fun error(message: String): ByteArray = bytes(obj("error" to message))

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
            if (key.isEmpty()) null else key to value
        }.toMap()
    }

    // ---------- Seite ----------

    private fun staticFile(exchange: HttpExchange, path: String) {
        val file = static.get(path)
        if (file != null) {
            val cache = if (path.startsWith("/assets/")) "public, max-age=31536000, immutable" else "no-cache"
            return respond(exchange, 200, file.type, file.bytes, cache = cache, gzipped = file.gzipped)
        }
        // Seiten der App (z.B. /spieler/abc) liefern immer index.html – den Rest macht der Router im Browser
        val lastSegment = path.substringAfterLast('/')
        val index = static.get("/index.html")
        if (index != null && !lastSegment.contains('.')) {
            return respond(exchange, 200, index.type, index.bytes, cache = "no-cache", gzipped = index.gzipped)
        }
        respond(exchange, 404, "text/plain; charset=utf-8", "Nicht gefunden".toByteArray(), cache = "no-cache")
    }

    // ---------- Antworten ----------

    private fun notModified(exchange: HttpExchange, etag: String) {
        exchange.responseHeaders.add("ETag", etag)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(304, -1)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        type: String,
        body: ByteArray,
        cache: String,
        etag: String? = null,
        gzipped: ByteArray? = null,
    ) {
        val headers = exchange.responseHeaders
        headers.add("Content-Type", type)
        headers.add("Cache-Control", cache)
        headers.add("X-Content-Type-Options", "nosniff")
        headers.add("Referrer-Policy", "same-origin")
        headers.add("Vary", "Accept-Encoding")
        if (type.startsWith("text/html")) {
            headers.add("Content-Security-Policy", CSP)
            headers.add("X-Frame-Options", "DENY")
        }
        etag?.let { headers.add("ETag", it) }

        val acceptsGzip = exchange.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true
        val payload = when {
            !acceptsGzip || !isCompressible(type) || body.size < 1024 -> body
            gzipped != null -> gzipped
            else -> gzip(body)
        }
        if (payload !== body) headers.add("Content-Encoding", "gzip")
        if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    companion object {
        const val TYPE_JSON = "application/json; charset=utf-8"

        // Alles kommt vom eigenen Server (auch die Schriften) – keine fremden Anfragen aus dem Browser
        private const val CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
            "script-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

        fun isCompressible(type: String): Boolean =
            type.startsWith("text/") || type.startsWith("application/json") || type.startsWith("application/javascript") ||
                type.startsWith("image/svg")

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size / 3 + 64)
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}

/** Dateien der gebauten Seite aus dem JAR (Ordner `web/` im Klassenpfad), mit vorab gepackter gzip-Version. */
class StaticFiles(private val root: String) {
    class File(val type: String, val bytes: ByteArray, val gzipped: ByteArray?)

    private val cache = ConcurrentHashMap<String, File>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun get(requestPath: String): File? {
        val path = if (requestPath == "/" || requestPath.isEmpty()) "/index.html" else requestPath
        if (path.contains("..") || path.contains('\\') || path.contains('\u0000') || path.endsWith('/')) return null
        // nur Dateien (mit Endung) – Ordner würden aus dem Klassenpfad sonst als Liste kommen
        if (!path.substringAfterLast('/').contains('.')) return null
        if (path in missing) return null
        cache[path]?.let { return it }
        val stream = StaticFiles::class.java.classLoader.getResourceAsStream(root + path)
        if (stream == null) {
            if (missing.size < 10_000) missing += path
            return null
        }
        val bytes = stream.use { it.readBytes() }
        val type = contentType(path)
        val file = File(type, bytes, if (StatsServer.isCompressible(type) && bytes.size >= 1024) StatsServer.gzip(bytes) else null)
        cache[path] = file
        return file
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "webmanifest" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
