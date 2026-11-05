/*
 * CoreHold-Website im selben Pelican-Server wie die Stats-Website (03.11.2026 → 05.11.2026, Kilian: „ich kann keinen neuen
 * [Server] machen“). Ein Java-Egg startet nur EINE JAR und der Server hat nur EINEN Port. Deshalb startet die Stats-Website
 * die CoreHoldWebsite.jar selbst als Kindprozess auf einem internen Port (127.0.0.1) und reicht alles unter /corehold dorthin
 * durch. Ohne CoreHoldWebsite.jar bzw. COREHOLD_JAR passiert nichts (alles bleibt wie vorher).
 *
 * .env (alle optional):
 *   COREHOLD_JAR        – Pfad zur JAR (Standard: CoreHoldWebsite.jar im Arbeitsordner; fehlt sie, bleibt die Brücke aus)
 *   COREHOLD_PORT       – interner Port (Standard 18091, nur 127.0.0.1)
 *   COREHOLD_JAVA_OPTS  – JVM-Optionen des Kindprozesses (Standard: -Xmx128m -Xms32m -XX:+UseSerialGC)
 *   WEB_TRUST_PROXY     – true = X-Forwarded-For/-Host/-Proto eines Reverse-Proxys vor der Website übernehmen
 * Die Einstellungen der CoreHold-Website selbst (ADMIN_PASSWORD …) stehen in derselben .env.
 */
package de.amongusmanager.crewbot.web

import com.sun.net.httpserver.HttpExchange
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class CoreHoldBridge(
    private val jar: Path,
    private val port: Int,
    private val workDir: Path,
    private val javaOpts: List<String>,
    private val trustProxy: Boolean,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val javaCommand: String = defaultJava(),
) {
    private val log = LoggerFactory.getLogger(CoreHoldBridge::class.java)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Volatile
    private var stopped = false

    @Volatile
    private var process: Process? = null

    /** Startet die CoreHold-JAR (und startet sie neu, wenn sie sich beendet). */
    fun start() {
        val thread = Thread({ supervise() }, "corehold-supervisor")
        thread.isDaemon = true
        thread.start()
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    fun stop() {
        stopped = true
        process?.let {
            it.destroy()
            if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    private fun supervise() {
        var quickFailures = 0
        while (!stopped) {
            val started = System.currentTimeMillis()
            try {
                val command = ArrayList<String>()
                command += javaCommand
                command += javaOpts
                command += listOf("-jar", jar.toAbsolutePath().toString())
                val builder = ProcessBuilder(command).directory(workDir.toFile()).inheritIO()
                val env = builder.environment()
                env["WEB_PORT"] = port.toString()
                env["WEB_HOST"] = "127.0.0.1"
                env["BASE_PATH"] = PATH
                env["TRUST_PROXY"] = "true" // wir sind der Proxy und setzen X-Forwarded-* selbst
                env.putIfAbsent("COREHOLD_DATA_DIR", workDir.resolve("data").resolve("corehold").toString())
                env.putAll(extraEnv)
                val child = builder.start()
                process = child
                log.info("🧱 CoreHold-Website gestartet (intern Port {}, erreichbar unter {}/)", port, PATH)
                child.waitFor()
                if (!stopped) log.warn("CoreHold-Website hat sich beendet (Code {})", child.exitValue())
            } catch (e: Exception) {
                log.warn("CoreHold-Website konnte nicht gestartet werden: {}", e.toString())
            }
            if (stopped) return
            quickFailures = if (System.currentTimeMillis() - started < 10_000) quickFailures + 1 else 0
            Thread.sleep(if (quickFailures >= 3) 60_000 else 3_000)
        }
    }

    fun handles(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    /** Reicht die Anfrage unverändert an die CoreHold-Website durch und schreibt deren Antwort zurück. */
    fun forward(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val body = readBody(exchange) ?: return fail(exchange, 413, "Anfrage zu groß")
        val uri = URI.create("http://127.0.0.1:$port" + exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""))
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
        request.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        for ((name, values) in exchange.requestHeaders) {
            if (name.lowercase() in SKIP_REQUEST_HEADERS) continue
            for (value in values) request.header(name, value)
        }
        val remote = exchange.remoteAddress?.address?.hostAddress ?: "?"
        val host = exchange.requestHeaders.getFirst("Host")
        val incomingFor = exchange.requestHeaders.getFirst("X-Forwarded-For")
        val incomingHost = exchange.requestHeaders.getFirst("X-Forwarded-Host")
        val incomingProto = exchange.requestHeaders.getFirst("X-Forwarded-Proto")
        // Ohne vertrauenswürdigen Proxy zählt nur, was wir selbst sehen (sonst könnte jeder seine Adresse fälschen).
        request.header("X-Forwarded-For", if (trustProxy && !incomingFor.isNullOrBlank()) incomingFor else remote)
        if (trustProxy && !incomingHost.isNullOrBlank()) request.header("X-Forwarded-Host", incomingHost)
        else if (host != null) request.header("X-Forwarded-Host", host)
        request.header("X-Forwarded-Proto", if (trustProxy && !incomingProto.isNullOrBlank()) incomingProto else "http")

        val response = try {
            client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: java.io.IOException) {
            return fail(exchange, 502, "CoreHold-Website ist gerade nicht erreichbar.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return fail(exchange, 502, "CoreHold-Website ist gerade nicht erreichbar.")
        }
        for ((name, values) in response.headers().map()) {
            if (name.lowercase() in SKIP_RESPONSE_HEADERS) continue
            for (value in values) exchange.responseHeaders.add(name, value)
        }
        val head = method == "HEAD"
        val payload = response.body()
        exchange.sendResponseHeaders(response.statusCode(), if (head || payload.isEmpty()) -1 else payload.size.toLong())
        if (!head && payload.isNotEmpty()) exchange.responseBody.use { it.write(payload) }
    }

    private fun fail(exchange: HttpExchange, status: Int, message: String) {
        val bytes = "{\"error\":\"$message\"}".toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Höchstens 128 KB (die CoreHold-Website selbst nimmt 64 KB), sonst null. */
    private fun readBody(exchange: HttpExchange): ByteArray? {
        val limit = 128 * 1024
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) return null
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val PATH = "/corehold"
        const val DEFAULT_PORT = 18091
        const val DEFAULT_JAR = "CoreHoldWebsite.jar"
        const val DEFAULT_OPTS = "-Xmx128m -Xms32m -XX:+UseSerialGC"

        // Vom JDK-HttpClient gesperrt bzw. von uns selbst gesetzt.
        private val SKIP_REQUEST_HEADERS = setOf(
            "host", "connection", "content-length", "expect", "upgrade", "transfer-encoding", "keep-alive",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
        )
        private val SKIP_RESPONSE_HEADERS = setOf("connection", "content-length", "transfer-encoding", "keep-alive", ":status")

        private fun defaultJava(): String =
            Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()

        /** Aus der .env: null = Brücke aus (keine JAR gefunden). */
        fun fromEnv(get: (String) -> String, workDir: Path): CoreHoldBridge? {
            val jar = get("COREHOLD_JAR").trim().ifEmpty { null }?.let { Path.of(it) } ?: workDir.resolve(DEFAULT_JAR)
            if (!Files.isRegularFile(jar)) return null
            val port = get("COREHOLD_PORT").trim().toIntOrNull() ?: DEFAULT_PORT
            val opts = get("COREHOLD_JAVA_OPTS").trim().ifEmpty { DEFAULT_OPTS }.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val trust = get("WEB_TRUST_PROXY").trim().equals("true", ignoreCase = true)
            return CoreHoldBridge(jar, port, workDir, opts, trust)
        }
    }
}
