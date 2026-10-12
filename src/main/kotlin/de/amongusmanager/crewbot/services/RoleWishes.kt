/*
 * Rollenwünsche: Spieler wünschen sich mit /rollenwunsch eine Rolle für ihre nächste Among-Us-Runde
 * (ROLE_WISH_COST XP, höchstens ROLE_WISHES_PER_EVENING-mal pro Spieleabend), Admins legen mit /rolle-zuweisen
 * kostenlos Rollen fest. Mehr Wünsche/Zuweisungen, als es eine Rolle laut Lobby-Einstellungen gibt, werden
 * abgelehnt. Die XP werden beim Wünschen abgezogen und kommen zurück, wenn der Wunsch zurückgezogen wird, in der
 * Runde nicht erfüllbar ist oder der Spieleabend (bis 6 Uhr morgens) ohne passende Runde endet.
 *
 * Die CrewStats-Mod des Hosts holt beim Rundenstart die Wünsche der Spieler in ihrer Lobby ab, wendet sie bei der
 * Rollenvergabe an und meldet zurück, was geklappt hat. Protokoll über den Mod-Webhook (kein offener Port nötig)
 * = mod/CrewStats/RoleWishes.cs – beide Seiten gemeinsam ändern!
 *   1. Mod schickt   {"crewstats":"wishes","v":1,"data":…}         data = {"players":["friendcode", …]}
 *   2. Bot bearbeitet genau diese Webhook-Nachricht zu
 *                    {"crewstats":"wishes-reply","v":1,"data":…}   data = {"wishes":[{"id","fc","role","admin"}]}
 *   3. Mod liest die Nachricht über den Webhook, löscht sie und wendet die Wünsche an.
 *   4. Mod schickt   {"crewstats":"wishes-result","v":1,"data":…}  data = {"results":[{"id","status","reason"}]}
 *      status = applied | failed (reason: noSlot, disabled, unknownRole, notAllowed) | absent (nicht in der Runde)
 * `data` ist AES-256-GCM-verschlüsselt (Schlüssel = SHA-256 von "crewstats-wishes:" + Webhook-Token, die Art
 * als Zusatzdaten), damit im Channel niemand mitlesen kann, wer sich welche Rolle wünscht.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.TIMEZONE
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.content.WishRole
import de.amongusmanager.crewbot.content.wishRole
import de.amongusmanager.crewbot.core.parseJsonOrNull
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.db.Row
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class WishKind(val value: String) { WISH("wish"), ADMIN("admin") }

enum class WishStatus(val value: String) {
    PENDING("pending"), // wartet auf die nächste Runde des Spielers
    USED("used"), // in einer Runde erfüllt
    REFUNDED("refunded"), // zurückgezogen / nicht erfüllbar / verfallen – XP erstattet
    CANCELLED("cancelled"), // Admin-Zuweisung entfernt, ersetzt oder verfallen (kostet nichts)
}

/** Ein Rollenwunsch (`kind` = WISH) oder eine Admin-Zuweisung (`kind` = ADMIN). */
data class RoleWish(
    val id: Long,
    val userId: Long,
    val roleName: String,
    val kind: WishKind,
    val cost: Int,
    val status: WishStatus,
    val reason: String?,
    val evening: String,
    val createdBy: Long,
    val createdAt: Instant,
    val reservedAt: Instant?,
) {
    val role: WishRole? get() = wishRole(roleName)
    val label: String get() = role?.label ?: roleName
    val isAdmin: Boolean get() = kind == WishKind.ADMIN
}

/** Fehler, dessen Nachricht direkt angezeigt werden kann. */
class RoleWishError(message: String) : Exception(message)

/** Ergebnis von /rollenwunsch. `previous` = alte Rolle, wenn ein offener Wunsch (kostenlos) geändert wurde. */
data class WishChange(val wish: RoleWish, val previous: String?, val usedTonight: Int, val balance: Int)

/** Ergebnis von /rolle-zuweisen. `replaced` = vorheriger Wunsch (erstattet) bzw. vorherige Zuweisung. */
data class Assignment(val entry: RoleWish, val replaced: RoleWish?)

/** Ein Wunsch, wie er an die Mod geht. */
data class ReservedWish(val id: Long, val friendCode: String, val roleName: String, val admin: Boolean)

/** Rückmeldung der Mod zu einem Wunsch. */
data class WishOutcome(val id: Long, val status: String, val reason: String?)

data class WishResults(val applied: List<RoleWish>, val refunded: List<RoleWish>)

class RoleWishService(
    private val db: Database,
    private val levels: LevelService,
    private val links: LinkService,
    /** Die zuletzt gemeldeten Lobby-Einstellungen (Anzahl Impostor, Rollen-Anzahl/-Chance). */
    private val settings: () -> GameSettings?,
    private val clock: () -> Instant = Instant::now,
) {
    // ---------- Lesen ----------

    /** Spieleabend eines Zeitpunkts als Datum – bis 6 Uhr morgens zählt noch der Vortag. */
    fun eveningOf(time: Instant): String =
        time.atZone(TIMEZONE).minusHours(Rules.GAME_NIGHT_END_HOUR).toLocalDate().toString()

    val currentEvening: String get() = eveningOf(clock())

    /** Alle offenen Wünsche und Zuweisungen (älteste zuerst). */
    fun pending(): List<RoleWish> =
        db.query("SELECT * FROM role_wishes WHERE status = ? ORDER BY id", WishStatus.PENDING.value, map = ::toWish)

    fun pendingOf(userId: Long): RoleWish? =
        db.queryOne(
            "SELECT * FROM role_wishes WHERE status = ? AND user_id = ? ORDER BY id",
            WishStatus.PENDING.value, userId, map = ::toWish,
        )

    fun byId(id: Long): RoleWish? = db.queryOne("SELECT * FROM role_wishes WHERE id = ?", id, map = ::toWish)

    /** Wie viele Wünsche der Spieler heute schon benutzt hat (offene + erfüllte, Erstattete zählen nicht). */
    fun usedTonight(userId: Long): Int =
        db.queryOne(
            "SELECT COUNT(*) AS n FROM role_wishes WHERE user_id = ? AND kind = ? AND evening = ? AND status IN (?, ?)",
            userId, WishKind.WISH.value, currentEvening, WishStatus.PENDING.value, WishStatus.USED.value,
        ) { it.int("n") } ?: 0

    /** Die Mod hat den Wunsch gerade für einen Rundenstart abgeholt – solange nicht ändern. */
    fun isReserved(wish: RoleWish): Boolean =
        wish.reservedAt?.let { clock() < it + Duration.ofMinutes(Rules.ROLE_WISH_RESERVE_MINUTES) } ?: false

    // ---------- Spieler ----------

    /** Neuer Wunsch (kostet XP) oder Änderung des offenen Wunsches (kostenlos). */
    fun wish(userId: Long, role: WishRole): WishChange = db.transaction {
        expireOld()
        requireLinked(userId, self = true)
        val current = pendingOf(userId)
        if (current != null) {
            if (current.isAdmin) {
                throw RoleWishError(
                    "🛡️ Ein Admin hat dir für die nächste Runde schon **${current.label}** zugewiesen –" +
                        " ein eigener Wunsch geht gerade nicht."
                )
            }
            requireNotReserved(current)
            if (current.roleName == role.gameName) throw RoleWishError("ℹ️ Du hast dir **${role.label}** schon gewünscht.")
            checkSlot(role, userId, forAdmin = false)
            db.update(
                "UPDATE role_wishes SET role_name = ?, updated_at = ? WHERE id = ?",
                role.gameName, clock(), current.id,
            )
            return@transaction WishChange(byId(current.id)!!, current.roleName, usedTonight(userId), levels.get(userId).xp)
        }

        val used = usedTonight(userId)
        if (used >= Rules.ROLE_WISHES_PER_EVENING) {
            throw RoleWishError(
                "🚫 Du hast heute schon $used Rollenwünsche benutzt – mehr gehen pro Spieleabend nicht." +
                    " Ab ${Rules.GAME_NIGHT_END_HOUR} Uhr morgens geht es wieder."
            )
        }
        checkSlot(role, userId, forAdmin = false)
        val balance = levels.get(userId).xp
        if (balance < Rules.ROLE_WISH_COST) {
            throw RoleWishError("💸 Ein Rollenwunsch kostet **${Rules.ROLE_WISH_COST} XP** – du hast nur $balance XP.")
        }
        levels.removeXp(userId, Rules.ROLE_WISH_COST) // wie beim Wetten: das Level sinkt dadurch nicht
        val id = insert(userId, role, WishKind.WISH, Rules.ROLE_WISH_COST, createdBy = userId)
        WishChange(byId(id)!!, null, used + 1, levels.get(userId).xp)
    }

    /** Offenen Wunsch zurückziehen – die XP kommen zurück. */
    fun withdraw(userId: Long): RoleWish = db.transaction {
        val current = pendingOf(userId) ?: throw RoleWishError("ℹ️ Du hast gerade keinen offenen Rollenwunsch.")
        if (current.isAdmin) {
            throw RoleWishError("🛡️ Diese Rolle hat dir ein Admin zugewiesen – nur ein Admin kann sie entfernen.")
        }
        requireNotReserved(current)
        close(current, "withdrawn")
    }

    // ---------- Admins ----------

    /** Rolle für die nächste Runde festlegen (kostenlos). Ein offener Wunsch des Spielers wird erstattet. */
    fun assign(adminId: Long, userId: Long, role: WishRole): Assignment = db.transaction {
        expireOld()
        requireLinked(userId, self = false)
        val current = pendingOf(userId)
        if (current != null) {
            requireNotReserved(current)
            if (current.isAdmin && current.roleName == role.gameName) {
                throw RoleWishError("ℹ️ <@$userId> hat für die nächste Runde schon **${role.label}**.")
            }
        }
        checkSlot(role, userId, forAdmin = true)
        val replaced = current?.let { close(it, if (it.isAdmin) "replaced" else "admin") }
        val id = insert(userId, role, WishKind.ADMIN, 0, createdBy = adminId)
        Assignment(byId(id)!!, replaced)
    }

    /** Offenen Wunsch (erstattet) oder Zuweisung eines Spielers entfernen. */
    fun unassign(userId: Long): RoleWish = db.transaction {
        val current = pendingOf(userId)
            ?: throw RoleWishError("ℹ️ <@$userId> hat keinen offenen Rollenwunsch und keine zugewiesene Rolle.")
        requireNotReserved(current)
        close(current, if (current.isAdmin) "removed" else "admin")
    }

    // ---------- Spieleabend vorbei ----------

    /** Offene Wünsche früherer Spieleabende verfallen (XP zurück). Liefert die geschlossenen Einträge. */
    fun expireOld(): List<RoleWish> = db.transaction {
        val evening = currentEvening
        pending().filter { it.evening != evening && !isReserved(it) }.map { close(it, "expired") }
    }

    // ---------- Mod ----------

    /** Die offenen Wünsche der Spieler in einer Lobby (Admin-Zuweisungen zuerst, dann wer zuerst gewünscht hat). */
    fun reserve(friendCodes: Collection<String>): List<ReservedWish> = db.transaction {
        expireOld()
        val now = clock()
        val seen = HashSet<Long>()
        val result = ArrayList<ReservedWish>()
        for (code in friendCodes.map(::normalizeFriendCode).distinct()) {
            val userId = links.userFor(code) ?: continue
            if (!seen.add(userId)) continue
            val wish = pendingOf(userId) ?: continue
            db.update("UPDATE role_wishes SET reserved_at = ? WHERE id = ?", now, wish.id)
            result.add(ReservedWish(wish.id, code, wish.roleName, wish.isAdmin))
        }
        result.sortedWith(compareBy<ReservedWish> { !it.admin }.thenBy { it.id })
    }

    /** Abgeholte Wünsche wieder freigeben (z.B. wenn die Antwort an die Mod nicht rausging). */
    fun release(ids: Collection<Long>) {
        db.transaction {
            for (id in ids) db.update("UPDATE role_wishes SET reserved_at = NULL WHERE id = ? AND status = ?", id, WishStatus.PENDING.value)
        }
    }

    /** Rückmeldung der Mod nach der Rollenvergabe: erfüllt → verbraucht, nicht möglich → erstattet. */
    fun applyResults(outcomes: List<WishOutcome>): WishResults = db.transaction {
        val applied = ArrayList<RoleWish>()
        val refunded = ArrayList<RoleWish>()
        for (outcome in outcomes) {
            val wish = byId(outcome.id) ?: continue
            if (wish.status != WishStatus.PENDING) continue // doppelt gemeldet oder inzwischen verfallen
            when (outcome.status) {
                "applied" -> {
                    db.update(
                        "UPDATE role_wishes SET status = ?, reserved_at = NULL, updated_at = ? WHERE id = ?",
                        WishStatus.USED.value, clock(), wish.id,
                    )
                    applied.add(byId(wish.id)!!)
                }
                "failed" -> refunded.add(close(wish, outcome.reason?.takeIf { it in FAILURE_REASONS } ?: "failed"))
                else -> db.update("UPDATE role_wishes SET reserved_at = NULL WHERE id = ?", wish.id) // nicht dabei
            }
        }
        WishResults(applied, refunded)
    }

    // ---------- intern ----------

    /**
     * Gibt es die Rolle noch? Grundlage sind die zuletzt gemeldeten Lobby-Einstellungen: Spezialrollen höchstens so
     * oft wie eingestellt (und nur mit Chance > 0), das Impostor-Team höchstens so groß wie die Impostor-Anzahl.
     */
    private fun checkSlot(role: WishRole, userId: Long, forAdmin: Boolean) {
        val others = pending().filter { it.userId != userId }
        val settings = settings()
        val impostorSlots = settings?.numImpostors?.takeIf { it > 0 } ?: 1
        val crewSlots = maxOf(1, (settings?.maxPlayers?.takeIf { it > 0 } ?: 15) - impostorSlots)
        // Admins sehen, wer die Plätze hat (und können Einträge mit „entfernen“ freimachen)
        fun holders(entries: List<RoleWish>) =
            if (forAdmin && entries.isNotEmpty()) " (vergeben an ${entries.joinToString(", ") { "<@${it.userId}>" }})" else ""

        if (!role.isTeamRole) {
            if (settings == null) {
                throw RoleWishError(
                    "❌ Die Rollen-Einstellungen der Lobby sind noch unbekannt – sie kommen beim nächsten Rundenstart" +
                        " mit der CrewStats-Mod. Bis dahin gehen nur Crewmate und Impostor."
                )
            }
            val rate = settings.roles[role.gameName]
            if (rate == null || !rate.active) {
                throw RoleWishError("❌ **${role.label}** ist in den aktuellen Lobby-Einstellungen aus – die Rolle gibt es gerade nicht.")
            }
            val same = others.filter { it.roleName == role.gameName }
            if (same.size >= rate.count) {
                throw RoleWishError(
                    "❌ Es gibt nur **${rate.count}× ${role.label}** – die Rolle ist für die nächste Runde schon" +
                        " vergeben${holders(same)}."
                )
            }
        }
        val team = others.filter { it.role?.team == role.team }
        val slots = if (role.team == Team.IMPOSTOR) impostorSlots else crewSlots
        if (team.size >= slots) {
            val what = if (role.team == Team.IMPOSTOR) "$slots Impostor" else "$slots Crewmates"
            throw RoleWishError("❌ Es gibt nur **$what** – alle Plätze sind für die nächste Runde schon vergeben${holders(team)}.")
        }
    }

    private fun requireLinked(userId: Long, self: Boolean) {
        if (links.codesOf(userId).isNotEmpty()) return
        throw RoleWishError(
            if (self) {
                "🔗 Verknüpfe zuerst deinen Among-Us-Account mit `/verknuepfen` – sonst erkennt die Mod dich nicht."
            } else {
                "🔗 <@$userId> ist noch nicht mit Among Us verknüpft (`/verknuepfen`) – die Mod würde die Person nicht erkennen."
            }
        )
    }

    private fun requireNotReserved(wish: RoleWish) {
        if (isReserved(wish)) {
            throw RoleWishError("⏳ Der Rollenwunsch wird gerade für eine startende Runde verwendet – versuch es in ein paar Minuten nochmal.")
        }
    }

    /** Schließt einen offenen Eintrag: Wünsche werden erstattet, Zuweisungen einfach beendet. */
    private fun close(wish: RoleWish, reason: String): RoleWish {
        val status = if (wish.cost > 0) WishStatus.REFUNDED else WishStatus.CANCELLED
        val changed = db.update(
            "UPDATE role_wishes SET status = ?, reason = ?, reserved_at = NULL, updated_at = ? WHERE id = ? AND status = ?",
            status.value, reason, clock(), wish.id, WishStatus.PENDING.value,
        )
        if (changed == 1 && wish.cost > 0) levels.addXp(wish.userId, wish.cost)
        return byId(wish.id)!!
    }

    private fun insert(userId: Long, role: WishRole, kind: WishKind, cost: Int, createdBy: Long): Long {
        val now = clock()
        return db.insert(
            "INSERT INTO role_wishes (user_id, role_name, kind, cost, status, evening, created_by, created_at, updated_at)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            userId, role.gameName, kind.value, cost, WishStatus.PENDING.value, eveningOf(now), createdBy, now, now,
        )
    }

    private fun toWish(row: Row) = RoleWish(
        id = row.long("id"),
        userId = row.long("user_id"),
        roleName = row.string("role_name"),
        kind = if (row.string("kind") == WishKind.ADMIN.value) WishKind.ADMIN else WishKind.WISH,
        cost = row.int("cost"),
        status = WishStatus.entries.firstOrNull { it.value == row.string("status") } ?: WishStatus.CANCELLED,
        reason = row.stringOrNull("reason"),
        evening = row.string("evening"),
        createdBy = row.long("created_by"),
        createdAt = row.instant("created_at"),
        reservedAt = row.longOrNull("reserved_at")?.let(Instant::ofEpochMilli),
    )

    companion object {
        val FAILURE_REASONS = setOf("noSlot", "disabled", "unknownRole", "notAllowed")
    }
}

// ==========================================
// PROTOKOLL MIT DER MOD
// ==========================================

class WishProtocolError(message: String) : Exception(message)

/** Eine (noch verschlüsselte) Rollenwunsch-Nachricht der Mod. */
data class WishMessage(val kind: String, val data: String)

object WishProtocol {
    const val REQUEST = "wishes"
    const val REPLY = "wishes-reply"
    const val RESULT = "wishes-result"

    /** Discord erlaubt höchstens 2000 Zeichen pro Nachricht. */
    const val MAX_CONTENT = 2000

    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** Erkennt Anfrage bzw. Rückmeldung der Mod (sonst null). */
    fun parse(text: String): WishMessage? {
        val data = parseJsonOrNull(text) as? JsonObject ?: return null
        val kind = data.string("crewstats")
        if (kind != REQUEST && kind != RESULT) return null
        return WishMessage(kind, data.string("data") ?: return null)
    }

    fun seal(kind: String, plaintext: String, webhookToken: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(webhookToken), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(kind.toByteArray(Charsets.UTF_8))
        val sealed = nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // Chiffretext + Tag
        return buildJsonObject {
            put("crewstats", kind)
            put("v", VERSION)
            put("data", Base64.getEncoder().encodeToString(sealed))
        }.toString()
    }

    fun open(message: WishMessage, webhookToken: String): String {
        val sealed = try {
            Base64.getDecoder().decode(message.data)
        } catch (e: IllegalArgumentException) {
            throw WishProtocolError("kein Base64")
        }
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw WishProtocolError("zu kurz")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(webhookToken), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
            cipher.updateAAD(message.kind.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw WishProtocolError("Entschlüsseln fehlgeschlagen (falscher Webhook?)")
        }
    }

    /** Friendcodes der Spieler in der Lobby aus der Anfrage. */
    fun requestPlayers(plaintext: String): List<String> {
        val data = parseJsonOrNull(plaintext) as? JsonObject ?: throw WishProtocolError("Anfrage ist kein JSON-Objekt")
        val players = data["players"] as? JsonArray ?: throw WishProtocolError("'players' fehlt")
        return players.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it.isNotBlank() }
    }

    /** Antwort an die Mod (verschlüsselt). Passt sie nicht in eine Nachricht, fallen die letzten Wünsche weg. */
    fun reply(wishes: List<ReservedWish>, webhookToken: String): Pair<String, List<ReservedWish>> {
        var included = wishes
        while (true) {
            val plaintext = buildJsonObject {
                putJsonArray("wishes") {
                    for (wish in included) {
                        addJsonObject {
                            put("id", wish.id)
                            put("fc", wish.friendCode)
                            put("role", wish.roleName)
                            put("admin", wish.admin)
                        }
                    }
                }
            }.toString()
            val content = seal(REPLY, plaintext, webhookToken)
            if (content.length <= MAX_CONTENT || included.isEmpty()) return content to included
            included = included.dropLast(1)
        }
    }

    /** Rückmeldung der Mod nach der Rollenvergabe. */
    fun outcomes(plaintext: String): List<WishOutcome> {
        val data = parseJsonOrNull(plaintext) as? JsonObject ?: throw WishProtocolError("Rückmeldung ist kein JSON-Objekt")
        val results = data["results"] as? JsonArray ?: throw WishProtocolError("'results' fehlt")
        return results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            WishOutcome(id, obj.string("status") ?: return@mapNotNull null, obj.string("reason"))
        }
    }

    /** Schlüssel aus dem Webhook-Token – den kennen nur der Bot und die Mods mit der Webhook-URL. */
    private fun key(webhookToken: String): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("crewstats-wishes:$webhookToken".toByteArray(Charsets.UTF_8)), "AES")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
