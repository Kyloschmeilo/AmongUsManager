/*
 * Stats-Website ↔ Discord: Namen und Profilbilder der verknüpften Spieler (mit dem Bot-Token, nur REST – die Website
 * verbindet sich nicht als zweiter Bot) und die Anmeldung „Mit Discord anmelden“ (OAuth2, nur Scope `identify`).
 * Profilbilder werden heruntergeladen und von der Website selbst ausgeliefert – der Browser fragt nie bei Discord an.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64

interface DiscordApi {
    /** Name + Profilbild eines Nutzers (auf dem Server: Spitzname und Server-Profilbild), null = gibt es nicht. */
    fun lookup(userId: Long): DiscordUser?

    /** Bild aus dem Discord-CDN: Bytes + Content-Type, null = nicht da. */
    fun download(url: String): Pair<ByteArray, String>?

    /** OAuth2: Code aus der Weiterleitung gegen ein Zugriffstoken tauschen. */
    fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String): String

    /** OAuth2: wer ist angemeldet? */
    fun me(accessToken: String): DiscordUser
}

class DiscordException(message: String) : Exception(message)

class HttpDiscordApi(private val botToken: String?, private val guildIdSetting: Long? = null) : DiscordApi {
    private val log = LoggerFactory.getLogger(HttpDiscordApi::class.java)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    /** Server des Bots (für Spitznamen/Server-Profilbilder): WEB_GUILD_ID oder über einen bekannten Channel. */
    private val guildId: Long? by lazy {
        guildIdSetting ?: botToken?.let {
            runCatching { (get("/channels/${Channels.CODES}") as? JsonObject)?.str("guild_id")?.toLong() }
                .onFailure { log.warn("Server des Bots nicht gefunden (nur globale Discord-Namen): {}", it.message) }
                .getOrNull()
        }
    }

    override fun lookup(userId: Long): DiscordUser? {
        if (botToken.isNullOrBlank()) return null
        val guild = guildId
        if (guild != null) {
            val member = get("/guilds/$guild/members/$userId") as? JsonObject
            if (member != null) {
                val user = member["user"] as? JsonObject ?: return null
                val guildAvatar = member.str("avatar")
                return userOf(user, nick = member.str("nick"), guildAvatar = guildAvatar?.let { guild to it })
            }
        }
        val user = get("/users/$userId") as? JsonObject ?: return null
        return userOf(user)
    }

    override fun download(url: String): Pair<ByteArray, String>? {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "cdn.discordapp.com") { "Nur Bilder aus dem Discord-CDN" }
        val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) throw DiscordException("Profilbild: HTTP ${response.statusCode()}")
        val bytes = response.body()
        if (bytes.size > MAX_IMAGE_BYTES) throw DiscordException("Profilbild zu groß (${bytes.size} Bytes)")
        val type = response.headers().firstValue("Content-Type").orElse("image/webp").substringBefore(';').trim()
        if (!type.startsWith("image/")) throw DiscordException("Profilbild hat den Typ $type")
        return bytes to type
    }

    override fun exchangeCode(code: String, redirectUri: String, clientId: String, clientSecret: String): String {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "client_secret" to clientSecret,
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("$API/oauth2/token"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = runCatching { parseJson(response.body()) as? JsonObject }.getOrNull()
        if (response.statusCode() != 200) {
            throw DiscordException("Anmeldung abgelehnt (HTTP ${response.statusCode()}: ${body?.str("error_description") ?: body?.str("error") ?: "?"})")
        }
        return body?.str("access_token") ?: throw DiscordException("Discord hat kein Zugriffstoken geschickt")
    }

    override fun me(accessToken: String): DiscordUser {
        val request = HttpRequest.newBuilder(URI("$API/users/@me"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw DiscordException("Discord-Konto nicht lesbar (HTTP ${response.statusCode()})")
        return userOf(parseJson(response.body()) as JsonObject)
    }

    /** GET mit dem Bot-Token; 404 = null; bei Rate-Limit einmal warten und wiederholen. */
    private fun get(path: String): Any? {
        repeat(2) { attempt ->
            val request = HttpRequest.newBuilder(URI("$API$path"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot $botToken")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> return parseJson(response.body())
                404 -> return null
                429 -> {
                    val wait = (runCatching { (parseJson(response.body()) as JsonObject).num("retry_after") }.getOrNull() ?: 1.0).coerceIn(0.1, 10.0)
                    if (attempt == 0) Thread.sleep((wait * 1000).toLong()) else throw DiscordException("Discord bremst (Rate-Limit)")
                }
                else -> throw DiscordException("Discord $path: HTTP ${response.statusCode()}")
            }
        }
        return null
    }

    companion object {
        private const val API = "https://discord.com/api/v10"
        private const val USER_AGENT = "DiscordBot (https://github.com/Kyloschmeilo/AmongUsManager, 1) CrewStats-Website"
        private const val MAX_IMAGE_BYTES = 1_000_000

        /** Nutzer-Objekt der Discord-API → [DiscordUser]; Server-Profilbild vor globalem Profilbild. */
        internal fun userOf(user: JsonObject, nick: String? = null, guildAvatar: Pair<Long, String>? = null): DiscordUser {
            val id = user.str("id")?.toLong() ?: throw DiscordException("Discord-Nutzer ohne id")
            val userAvatar = user.str("avatar")
            val (key, url) = when {
                guildAvatar != null -> "g:${guildAvatar.second}" to
                    "https://cdn.discordapp.com/guilds/${guildAvatar.first}/users/$id/avatars/${guildAvatar.second}.webp?size=128"
                userAvatar != null -> "u:$userAvatar" to "https://cdn.discordapp.com/avatars/$id/$userAvatar.webp?size=128"
                else -> null to null
            }
            return DiscordUser(
                id = id,
                username = user.str("username") ?: "?",
                globalName = user.str("global_name"),
                nick = nick,
                avatarKey = key,
                avatarUrl = url,
            )
        }

        /** Die Client-ID der Discord-App steckt im ersten Teil des Bot-Tokens (Base64 der Bot-ID). */
        fun clientIdFromToken(token: String?): String? {
            val first = token?.trim()?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val padded = first + "=".repeat((4 - first.length % 4) % 4)
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8).takeIf { text -> text.all { it.isDigit() } && text.length >= 15 }
            }.getOrNull()
        }
    }
}

/**
 * Hält Namen und Profilbilder der verknüpften Spieler aktuell: jeder wird höchstens alle [maxAge] bei Discord
 * nachgeschlagen, pro Durchlauf höchstens [batch] Spieler (schont das Rate-Limit des Bots).
 */
class DiscordSync(
    private val api: DiscordApi,
    private val accounts: WebAccounts,
    private val clock: () -> Instant = Instant::now,
    private val maxAge: Duration = Duration.ofHours(12),
    private val batch: Int = 10,
) {
    private val log = LoggerFactory.getLogger(DiscordSync::class.java)

    /** Schlägt fällige Spieler nach; liefert, wie viele nachgeschlagen wurden. */
    fun sync(discordIds: Collection<Long>): Int {
        val fetched = accounts.fetchedAt()
        val now = clock()
        val due = discordIds.distinct()
            .filter { id -> fetched[id]?.let { it.plus(maxAge).isBefore(now) } ?: true }
            .sortedBy { fetched[it] ?: Instant.EPOCH }
            .take(batch)
        for (id in due) {
            val user = api.lookup(id)
            if (user == null) {
                accounts.markMissing(id)
                continue
            }
            store(user)
        }
        return due.size
    }

    /** Speichert einen Nutzer und lädt sein Profilbild, wenn es neu ist. */
    fun store(user: DiscordUser) {
        val known = accounts.avatar(user.id)
        val image = if (user.avatarUrl != null && (known == null || known.version != user.avatarKey)) {
            runCatching { api.download(user.avatarUrl) }
                .onFailure { log.warn("Profilbild von {} nicht geladen: {}", user.id, it.message) }
                .getOrNull()
        } else {
            null
        }
        val avatarKey = when {
            user.avatarUrl == null -> null
            image != null -> user.avatarKey
            // Herunterladen fehlgeschlagen: altes Bild behalten, beim nächsten Durchlauf neu versuchen
            else -> known?.version
        }
        accounts.saveDiscordUser(user.copy(avatarKey = avatarKey), image?.first, image?.second)
    }
}
