package de.amongusmanager.crewbot

import com.sun.net.httpserver.HttpServer
import de.amongusmanager.crewbot.web.CoreHoldBridge
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Brücke zur CoreHold-Website: Durchreichen von Pfad, Methode, Body, Cookies und X-Forwarded-*, ohne Prozess. */
class CoreHoldBridgeTest {
    private val servers = mutableListOf<HttpServer>()
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(0) }
    }

    private fun server(configure: (HttpServer) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        configure(server)
        server.start()
        servers += server
        return server
    }

    /** Falscher „CoreHold“, der alles zurückmeldet, was bei ihm ankommt. */
    private fun upstream(): HttpServer = server { s ->
        s.createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val text = listOf(
                "${ex.requestMethod} ${ex.requestURI}",
                "body=$body",
                "for=${ex.requestHeaders.getFirst("X-Forwarded-For")}",
                "host=${ex.requestHeaders.getFirst("X-Forwarded-Host")}",
                "proto=${ex.requestHeaders.getFirst("X-Forwarded-Proto")}",
                "cookie=${ex.requestHeaders.getFirst("Cookie")}",
                "origin=${ex.requestHeaders.getFirst("Origin")}",
            ).joinToString("\n").toByteArray()
            ex.responseHeaders.add("Set-Cookie", "a=1; Path=/corehold/")
            ex.responseHeaders.add("Set-Cookie", "b=2; Path=/corehold/")
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            val head = ex.requestMethod == "HEAD"
            ex.sendResponseHeaders(if (ex.requestURI.path.endsWith("/missing")) 404 else 200, if (head) -1 else text.size.toLong())
            if (!head) ex.responseBody.use { it.write(text) }
        }
    }

    private fun front(upstreamPort: Int, trustProxy: Boolean): HttpServer {
        val bridge = CoreHoldBridge(
            jar = Files.createTempFile("x", ".jar"), port = upstreamPort, workDir = Files.createTempDirectory("ch"),
            javaOpts = emptyList(), trustProxy = trustProxy,
        )
        return server { s ->
            s.createContext("/") { ex ->
                try {
                    if (bridge.handles(ex.requestURI.path)) bridge.forward(ex)
                    else {
                        ex.sendResponseHeaders(404, -1)
                    }
                } finally {
                    ex.close()
                }
            }
        }
    }

    private fun send(front: HttpServer, method: String, path: String, body: String? = null, vararg headers: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${front.address.port}$path"))
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        headers.toList().chunked(2).forEach { builder.header(it[0], it[1]) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun onlyTheCoreHoldPathIsHandled() {
        val bridge = CoreHoldBridge(
            Files.createTempFile("x", ".jar"), 1, Files.createTempDirectory("ch"), emptyList(), false,
        )
        assertTrue(bridge.handles("/corehold"))
        assertTrue(bridge.handles("/corehold/"))
        assertTrue(bridge.handles("/corehold/api/data"))
        assertFalse(bridge.handles("/coreholdx"))
        assertFalse(bridge.handles("/api/corehold"))
        assertFalse(bridge.handles("/"))
    }

    @Test
    fun forwardsPathQueryMethodAndBody() {
        val front = front(upstream().address.port, trustProxy = false)
        val get = send(front, "GET", "/corehold/api/data?x=1&y=%C3%A4")
        assertEquals(200, get.statusCode())
        assertTrue(get.body().startsWith("GET /corehold/api/data?x=1&y=%C3%A4"), get.body())

        val post = send(front, "POST", "/corehold/api/team", "{\"name\":\"Rot\"}", "Content-Type", "application/json", "Origin", "http://example.org")
        assertTrue(post.body().contains("POST /corehold/api/team"))
        assertTrue(post.body().contains("body={\"name\":\"Rot\"}"))
        assertTrue(post.body().contains("origin=http://example.org"), "Origin muss unverändert ankommen: ${post.body()}")

        val missing = send(front, "GET", "/corehold/missing")
        assertEquals(404, missing.statusCode())
        val head = send(front, "HEAD", "/corehold/")
        assertEquals(200, head.statusCode())
        assertEquals("", head.body())
    }

    @Test
    fun keepsEverySetCookieAndSendsCookiesAlong() {
        val front = front(upstream().address.port, trustProxy = false)
        val response = send(front, "GET", "/corehold/", null, "Cookie", "corehold_admin=abc")
        assertEquals(listOf("a=1; Path=/corehold/", "b=2; Path=/corehold/"), response.headers().allValues("Set-Cookie"))
        assertTrue(response.body().contains("cookie=corehold_admin=abc"))
    }

    @Test
    fun withoutTrustedProxyForwardedHeadersCannotBeForged() {
        val front = front(upstream().address.port, trustProxy = false)
        val body = send(front, "GET", "/corehold/", null, "X-Forwarded-For", "6.6.6.6", "X-Forwarded-Proto", "https").body()
        assertTrue(body.contains("for=127.0.0.1"), body)
        assertTrue(body.contains("proto=http\n"), body)
        assertFalse(body.contains("6.6.6.6"), body)
        assertTrue(body.contains("host=127.0.0.1:${front.address.port}"), body)
    }

    @Test
    fun withTrustedProxyTheProxyHeadersArePassedOn() {
        val front = front(upstream().address.port, trustProxy = true)
        val body = send(
            front, "GET", "/corehold/", null,
            "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Host", "spieleabend.timonso.de", "X-Forwarded-Proto", "https",
        ).body()
        assertTrue(body.contains("for=203.0.113.7"), body)
        assertTrue(body.contains("host=spieleabend.timonso.de"), body)
        assertTrue(body.contains("proto=https"), body)
    }

    @Test
    fun answers502WhenCoreHoldIsDown() {
        val dead = server { }
        val port = dead.address.port
        dead.stop(0)
        servers.remove(dead)
        val front = front(port, trustProxy = false)
        val response = send(front, "GET", "/corehold/")
        assertEquals(502, response.statusCode())
        assertTrue(response.body().contains("nicht erreichbar"))
    }

    @Test
    fun bridgeStaysOffWithoutJar() {
        val dir = Files.createTempDirectory("ch")
        assertNull(CoreHoldBridge.fromEnv({ "" }, dir))
        Files.writeString(dir.resolve("CoreHoldWebsite.jar"), "x")
        val bridge = CoreHoldBridge.fromEnv({ if (it == "COREHOLD_PORT") "19000" else "" }, dir)
        assertNotNull(bridge)
    }
}
