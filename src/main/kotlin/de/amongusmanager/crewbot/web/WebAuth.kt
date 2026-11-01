/*
 * Stats-Website: „Mit Discord anmelden“ (OAuth2-Code-Flow, Scope nur `identify`) und das eigene Profil.
 *
 *   GET  /auth/login?back=/…   → Weiterleitung zu Discord (state als Cookie + im Speicher)
 *   GET  /auth/callback        → Code tauschen, Discord-Konto lesen, Sitzung anlegen, zurück zur Seite
 *   POST /auth/logout          → Sitzung löschen
 *   GET  /api/me               → wer bin ich, mein Spieler, meine Einstellungen + Auswahl
 *   POST /api/me/profile       → Einstellungen speichern (JSON)
 *
 * Sitzungen liegen in der Website-Datenbank (nur der Hash des Cookies). POSTs brauchen JSON und dieselbe Herkunft
 * (Origin), das Cookie ist HttpOnly und SameSite=Lax – fremde Seiten können also nichts im Namen anderer ändern.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Einstellungen für die Anmeldung (aus der .env der Website). */
data class AuthConfig(
    val clientId: String?,
    val clientSecret: String?,
    /** Öffentliche Adresse der Website, z.B. http://45.81.233.92:25570 (sonst aus dem Host-Header). */
    val publicUrl: String?,
) {
    val enabled: Boolean get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
}

/** Eine Anfrage, unabhängig vom HTTP-Server (für Tests). */
class WebRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val cookies: Map<String, String> by lazy {
        (header("cookie") ?: "").split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            if (name.isEmpty() || '=' !in part) null else name to part.substringAfter('=').trim()
        }.toMap()
    }
}

class WebResponse(
    val status: Int,
    val type: String = StatsServer.TYPE_JSON,
    val body: ByteArray = ByteArray(0),
    val headers: List<Pair<String, String>> = emptyList(),
)

class WebAuth(
    private val accounts: WebAccounts,
    private val store: StatsStore,
    private val discord: DiscordApi,
    private val config: AuthConfig,
    private val sync: DiscordSync? = null,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(WebAuth::class.java)
    private val random = SecureRandom()
    private class Pending(val back: String, val created: Instant)
    private val pending = ConcurrentHashMap<String, Pending>()

    fun handles(path: String): Boolean = path == "/auth/login" || path == "/auth/callback" || path == "/auth/logout" ||
        path == "/api/me" || path == "/api/me/profile"

    fun handle(request: WebRequest): WebResponse = when (request.path) {
        "/auth/login" -> if (request.method == "GET") login(request) else notAllowed("GET")
        "/auth/callback" -> if (request.method == "GET") callback(request) else notAllowed("GET")
        "/auth/logout" -> if (request.method == "POST") logout(request) else notAllowed("POST")
        "/api/me" -> if (request.method == "GET" || request.method == "HEAD") json(200, me(userOf(request))) else notAllowed("GET")
        "/api/me/profile" -> if (request.method == "POST") saveProfile(request) else notAllowed("POST")
        else -> json(404, obj("error" to "Nicht gefunden"))
    }

    // ---------- Anmelden ----------

    private fun login(request: WebRequest): WebResponse {
        val back = safeBack(request.query["back"])
        if (!config.enabled) return redirect(withParam(back, "login", "aus"))
        val now = clock()
        pending.entries.removeIf { it.value.created.plus(STATE_LIFETIME).isBefore(now) }
        if (pending.size > 5000) pending.clear() // niemand soll den Speicher füllen können
        val state = token(24)
        pending[state] = Pending(back, now)
        val url = "https://discord.com/oauth2/authorize?" + listOf(
            "client_id" to config.clientId!!,
            "response_type" to "code",
            "redirect_uri" to redirectUri(request),
            "scope" to "identify",
            "state" to state,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        return redirect(url, cookie(STATE_COOKIE, state, path = "/auth", maxAge = STATE_LIFETIME.seconds, secure = isSecure(request)))
    }

    private fun callback(request: WebRequest): WebResponse {
        val state = request.query["state"] ?: ""
        val entry = pending.remove(state)
        val clearState = cookie(STATE_COOKIE, "", path = "/auth", maxAge = 0, secure = isSecure(request))
        if (entry == null || state.isEmpty() || request.cookies[STATE_COOKIE] != state || entry.created.plus(STATE_LIFETIME).isBefore(clock())) {
            return redirect(withParam("/", "login", "fehler"), clearState)
        }
        val code = request.query["code"]
        if (code.isNullOrEmpty()) {
            // z.B. „Abbrechen“ bei Discord (error=access_denied)
            return redirect(withParam(entry.back, "login", "abgebrochen"), clearState)
        }
        return try {
            val accessToken = discord.exchangeCode(code, redirectUri(request), config.clientId!!, config.clientSecret!!)
            val user = discord.me(accessToken)
            // Server-Spitzname/-Profilbild bevorzugen (Bot-Token), sonst das Konto aus der Anmeldung
            val fresh = runCatching { discord.lookup(user.id) }.getOrNull() ?: user
            sync?.store(fresh)
            val session = accounts.createSession(user.id)
            store.refreshQuietly()
            log.info("Angemeldet: Discord-Konto {}", shortHash("log:${user.id}"))
            redirect(entry.back, clearState, cookie(SESSION_COOKIE, session, path = "/", maxAge = WebAccounts.SESSION_LIFETIME.seconds, secure = isSecure(request)))
        } catch (e: Exception) {
            log.warn("Anmeldung fehlgeschlagen: {}", e.message)
            redirect(withParam(entry.back, "login", "fehler"), clearState)
        }
    }

    private fun logout(request: WebRequest): WebResponse {
        sameOrigin(request)?.let { return it }
        accounts.deleteSession(request.cookies[SESSION_COOKIE])
        return WebResponse(200, body = bytes(me(null)), headers = listOf(noStore(), cookie(SESSION_COOKIE, "", path = "/", maxAge = 0, secure = isSecure(request))))
    }

    // ---------- Profil ----------

    private fun saveProfile(request: WebRequest): WebResponse {
        sameOrigin(request)?.let { return it }
        if (request.header("content-type")?.startsWith("application/json") != true) return json(415, obj("error" to "Nur JSON"))
        val discordId = userOf(request) ?: return json(401, obj("error" to "Bitte zuerst mit Discord anmelden."))
        val key = playerKeyOf(discordId)
        if (!store.snapshot.hasPlayer(key)) {
            return json(404, obj("error" to "Zu deinem Discord-Konto gibt es noch keine Runden. Verknüpfe dich im Discord mit /verknuepfen."))
        }
        if (request.body.size > MAX_BODY) return json(413, obj("error" to "Zu groß"))
        val input = runCatching { parseJson(String(request.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return json(400, obj("error" to "Ungültige Eingabe"))
        val settings = try {
            ProfileRules.parse(input, store.snapshot.profileOptions(key, accounts.discordUser(discordId)))
        } catch (e: ProfileException) {
            return json(400, obj("error" to e.message))
        }
        accounts.saveProfile(discordId, settings)
        store.refreshQuietly()
        return json(200, me(discordId))
    }

    private fun me(discordId: Long?): JsonObject {
        if (discordId == null) return obj("loginEnabled" to config.enabled, "loggedIn" to false)
        val snapshot = store.snapshot
        val key = playerKeyOf(discordId)
        val discordUser = accounts.discordUser(discordId)
        val player = snapshot.summaryOf(key)
        return obj(
            "loginEnabled" to config.enabled,
            "loggedIn" to true,
            "user" to obj(
                "name" to (discordUser?.shownName ?: "Discord-Konto"),
                "avatar" to player?.let { snapshot.avatarVersionOf(key)?.let { v -> "/api/avatar/$key?v=$v" } },
                "hasDiscordAvatar" to (accounts.avatar(discordId) != null),
            ),
            "player" to player,
            "profile" to (accounts.profile(discordId) ?: ProfileSettings()).toJson(),
            "options" to if (player != null) snapshot.profileOptions(key, discordUser).toJson() else null,
        )
    }

    // ---------- Helfer ----------

    private fun userOf(request: WebRequest): Long? = accounts.sessionUser(request.cookies[SESSION_COOKIE])

    private fun redirectUri(request: WebRequest): String = baseUrl(request) + "/auth/callback"

    private fun baseUrl(request: WebRequest): String {
        config.publicUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { return it }
        val proto = request.header("x-forwarded-proto")?.takeIf { it == "https" } ?: "http"
        return "$proto://${request.header("host") ?: "localhost"}"
    }

    private fun isSecure(request: WebRequest): Boolean = baseUrl(request).startsWith("https://")

    /** POST nur von der eigenen Seite (Origin = eigene Adresse, falls der Browser ihn schickt). */
    private fun sameOrigin(request: WebRequest): WebResponse? {
        val origin = request.header("origin") ?: return null
        val own = runCatching { URI(baseUrl(request)) }.getOrNull()
        val from = runCatching { URI(origin) }.getOrNull()
        val hostHeader = request.header("host")
        val ok = from != null && (
            (own != null && from.scheme == own.scheme && from.host == own.host && port(from) == port(own)) ||
                (hostHeader != null && from.authority == hostHeader)
            )
        return if (ok) null else json(403, obj("error" to "Fremde Herkunft"))
    }

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun json(status: Int, body: JsonObject) = WebResponse(status, body = bytes(body), headers = listOf(noStore()))

    private fun notAllowed(allowed: String) = WebResponse(405, body = bytes(obj("error" to "Nur $allowed")), headers = listOf("Allow" to allowed, noStore()))

    private fun redirect(location: String, vararg extra: Pair<String, String>) =
        WebResponse(302, type = "text/plain; charset=utf-8", headers = listOf("Location" to location, noStore()) + extra)

    private fun noStore() = "Cache-Control" to "no-store"

    private fun cookie(name: String, value: String, path: String, maxAge: Long, secure: Boolean): Pair<String, String> =
        "Set-Cookie" to "$name=$value; Path=$path; Max-Age=$maxAge; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else ""

    companion object {
        const val SESSION_COOKIE = "cs_session"
        const val STATE_COOKIE = "cs_state"
        private val STATE_LIFETIME: Duration = Duration.ofMinutes(10)
        private const val MAX_BODY = 8 * 1024

        /** Schlüssel des Spielers zu einem Discord-Konto (wie im Snapshot für verknüpfte Spieler). */
        fun playerKeyOf(discordId: Long): String = shortHash("crewstats-web:d:$discordId")

        /** Nur Pfade dieser Seite als Rücksprung (keine fremden Adressen). */
        fun safeBack(back: String?): String {
            val value = back?.trim() ?: return "/"
            if (!value.startsWith("/") || value.startsWith("//") || value.contains('\\') || value.any { it.isISOControl() } || value.length > 300) return "/"
            if (value.startsWith("/auth/")) return "/"
            return value
        }

        fun withParam(path: String, name: String, value: String): String = path + (if ('?' in path) "&" else "?") + "$name=$value"
    }
}
