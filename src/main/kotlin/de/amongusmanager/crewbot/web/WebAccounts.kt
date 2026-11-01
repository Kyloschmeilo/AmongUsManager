/*
 * Stats-Website: eigene Daten der Website – Anmeldungen, Profil-Einstellungen und zwischengespeicherte
 * Discord-Namen/-Profilbilder. Liegt in einer eigenen SQLite-Datei (`data/website.db` im Ordner der Website),
 * die Datenbank des Bots wird weiterhin nur gelesen.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** Was ein Spieler auf der Website über sich einstellen kann (gespeichert als JSON je Discord-ID). */
data class ProfileSettings(
    /** Anzeigename – nur einer der eigenen Ingame- oder Discord-Namen (niemand kann sich als jemand anderes ausgeben). */
    val displayName: String? = null,
    val bio: String = "",
    /** Profilfarbe = eine der Among-Us-Farben (0–17). */
    val colorId: Int? = null,
    val favoriteRole: String? = null,
    val favoriteMap: Int? = null,
    /** Bis zu drei Kennzahlen, die oben im Profil groß stehen (ids wie in web/src/lib/metrics.ts). */
    val showcase: List<String> = emptyList(),
    /** Discord-Profilbild auf der Website zeigen (sonst der farbige Crewmate). */
    val showAvatar: Boolean = true,
) {
    fun toJson(): JsonObject = obj(
        "displayName" to displayName,
        "bio" to bio,
        "colorId" to colorId,
        "favoriteRole" to favoriteRole,
        "favoriteMap" to favoriteMap,
        "showcase" to showcase,
        "showAvatar" to showAvatar,
    )

    companion object {
        fun fromJson(data: JsonObject): ProfileSettings = ProfileSettings(
            displayName = data.str("displayName")?.takeIf { it.isNotBlank() },
            bio = data.str("bio") ?: "",
            colorId = data.int("colorId"),
            favoriteRole = data.str("favoriteRole")?.takeIf { it.isNotBlank() },
            favoriteMap = data.int("favoriteMap"),
            showcase = (data["showcase"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(),
            showAvatar = data.bool("showAvatar") ?: true,
        )
    }
}

/** Name und Profilbild eines Discord-Nutzers (Server-Spitzname und Server-Profilbild haben Vorrang). */
data class DiscordUser(
    val id: Long,
    val username: String,
    val globalName: String? = null,
    val nick: String? = null,
    /** Kennung des Profilbilds (ändert sich mit dem Bild), null = kein eigenes Bild. */
    val avatarKey: String? = null,
    /** Adresse des Bilds im Discord-CDN (nur zum Herunterladen, geht nie an den Browser). */
    val avatarUrl: String? = null,
) {
    /** Name wie in Discord angezeigt. */
    val shownName: String get() = listOfNotNull(nick, globalName, username).first { it.isNotBlank() }

    /** Alle Namen, die der Nutzer als Anzeigenamen wählen darf. */
    val names: List<String> get() = listOfNotNull(nick, globalName, username).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** Was die Website zusätzlich zu den Runden über einen verknüpften Spieler weiß. */
data class PlayerDecoration(
    val displayName: String? = null,
    /** Version des Profilbilds, null = keins zeigen. */
    val avatarVersion: String? = null,
    val profile: ProfileSettings? = null,
)

class StoredAvatar(val version: String, val contentType: String, val bytes: ByteArray)

class WebAccounts(private val db: Database, private val clock: () -> Instant = Instant::now) {
    private val random = SecureRandom()
    private val changes = AtomicLong(1)

    /** Steigt bei jeder Änderung an Profilen/Discord-Daten – dann braucht die Website einen neuen Snapshot. */
    val version: Long get() = changes.get()

    init {
        db.update(
            "CREATE TABLE IF NOT EXISTS web_sessions (token_hash TEXT PRIMARY KEY, discord_id INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
        )
        db.update("CREATE TABLE IF NOT EXISTS web_profiles (discord_id INTEGER PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.update(
            "CREATE TABLE IF NOT EXISTS web_discord_users (discord_id INTEGER PRIMARY KEY, username TEXT NOT NULL, " +
                "global_name TEXT, nick TEXT, avatar_key TEXT, avatar BLOB, avatar_type TEXT, fetched_at INTEGER NOT NULL, " +
                "missing INTEGER NOT NULL DEFAULT 0)",
        )
    }

    // ---------- Anmeldung ----------

    /** Neue Sitzung, liefert das Cookie-Token (gespeichert wird nur sein Hash). */
    fun createSession(discordId: Long, lifetime: Duration = SESSION_LIFETIME): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock()
        db.update("DELETE FROM web_sessions WHERE expires_at < ?", now)
        db.update(
            "INSERT INTO web_sessions (token_hash, discord_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
            hash(token), discordId, now, now.plus(lifetime),
        )
        return token
    }

    /** Discord-ID der angemeldeten Person, null = nicht (mehr) angemeldet. */
    fun sessionUser(token: String?): Long? {
        if (token.isNullOrBlank() || token.length > 100) return null
        return db.queryOne("SELECT discord_id, expires_at FROM web_sessions WHERE token_hash = ?", hash(token)) { row ->
            row.long("discord_id").takeIf { row.instant("expires_at").isAfter(clock()) }
        }
    }

    fun deleteSession(token: String?) {
        if (!token.isNullOrBlank()) db.update("DELETE FROM web_sessions WHERE token_hash = ?", hash(token))
    }

    // ---------- Profile ----------

    fun profile(discordId: Long): ProfileSettings? =
        db.queryOne("SELECT data FROM web_profiles WHERE discord_id = ?", discordId) { row ->
            (runCatching { parseJson(row.string("data")) }.getOrNull() as? JsonObject)?.let { ProfileSettings.fromJson(it) }
        }

    fun saveProfile(discordId: Long, settings: ProfileSettings) {
        db.transaction {
            db.update("DELETE FROM web_profiles WHERE discord_id = ?", discordId)
            db.update("INSERT INTO web_profiles (discord_id, data, updated_at) VALUES (?, ?, ?)", discordId, settings.toJson().toString(), clock())
        }
        changes.incrementAndGet()
    }

    // ---------- Discord ----------

    fun discordUser(discordId: Long): DiscordUser? =
        db.queryOne("SELECT * FROM web_discord_users WHERE discord_id = ? AND missing = 0", discordId) { row -> userOf(row) }

    /** Wann jeder bekannte Nutzer zuletzt bei Discord nachgeschlagen wurde. */
    fun fetchedAt(): Map<Long, Instant> =
        db.query("SELECT discord_id, fetched_at FROM web_discord_users") { it.long("discord_id") to it.instant("fetched_at") }.toMap()

    /** Nutzer speichern; [image] nur, wenn sich das Bild geändert hat (sonst bleibt das gespeicherte). */
    fun saveDiscordUser(user: DiscordUser, image: ByteArray?, contentType: String?) {
        val old = db.queryOne("SELECT avatar_key, avatar, avatar_type FROM web_discord_users WHERE discord_id = ?", user.id) { row ->
            Triple(row.stringOrNull("avatar_key"), row.bytesOrNull("avatar"), row.stringOrNull("avatar_type"))
        }
        val keepImage = image == null && old != null && old.first == user.avatarKey
        val bytes = if (keepImage) old!!.second else image
        val type = if (keepImage) old!!.third else contentType
        val key = if (bytes == null) null else user.avatarKey
        val oldUser = discordUser(user.id)
        db.transaction {
            db.update("DELETE FROM web_discord_users WHERE discord_id = ?", user.id)
            db.update(
                "INSERT INTO web_discord_users (discord_id, username, global_name, nick, avatar_key, avatar, avatar_type, fetched_at, missing) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                user.id, user.username, user.globalName, user.nick, key, bytes, type, clock(),
            )
        }
        val newUser = user.copy(avatarKey = key, avatarUrl = null)
        if (oldUser?.copy(avatarUrl = null) != newUser) changes.incrementAndGet()
    }

    /** Bei Discord nicht (mehr) gefunden – nicht bei jedem Durchlauf neu fragen. */
    fun markMissing(discordId: Long) {
        val existed = discordUser(discordId) != null
        db.transaction {
            db.update("DELETE FROM web_discord_users WHERE discord_id = ?", discordId)
            db.update(
                "INSERT INTO web_discord_users (discord_id, username, fetched_at, missing) VALUES (?, '', ?, 1)",
                discordId, clock(),
            )
        }
        if (existed) changes.incrementAndGet()
    }

    fun avatar(discordId: Long): StoredAvatar? =
        db.queryOne("SELECT avatar_key, avatar, avatar_type FROM web_discord_users WHERE discord_id = ? AND missing = 0", discordId) { row ->
            val bytes = row.bytesOrNull("avatar")
            val key = row.stringOrNull("avatar_key")
            if (bytes == null || key == null) null else StoredAvatar(key, row.stringOrNull("avatar_type") ?: "image/webp", bytes)
        }

    /** Anzeigenamen, Profilbilder und Einstellungen aller verknüpften Spieler (für den Snapshot). */
    fun decorations(): Map<Long, PlayerDecoration> {
        val profiles = db.query("SELECT discord_id, data FROM web_profiles") { row ->
            row.long("discord_id") to (runCatching { parseJson(row.string("data")) }.getOrNull() as? JsonObject)?.let { ProfileSettings.fromJson(it) }
        }.toMap()
        val avatars = db.query("SELECT discord_id, avatar_key FROM web_discord_users WHERE missing = 0 AND avatar IS NOT NULL") { row ->
            row.long("discord_id") to row.stringOrNull("avatar_key")
        }.toMap()
        return (profiles.keys + avatars.keys).associateWith { id ->
            val profile = profiles[id]
            PlayerDecoration(
                displayName = profile?.displayName,
                avatarVersion = avatars[id]?.takeIf { profile?.showAvatar != false }?.let { shortHash(it) },
                profile = profile,
            )
        }
    }

    private fun userOf(row: de.amongusmanager.crewbot.db.Row): DiscordUser = DiscordUser(
        id = row.long("discord_id"),
        username = row.string("username"),
        globalName = row.stringOrNull("global_name"),
        nick = row.stringOrNull("nick"),
        avatarKey = row.stringOrNull("avatar_key"),
    )

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        val SESSION_LIFETIME: Duration = Duration.ofDays(30)
    }
}
