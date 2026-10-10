/* Verknüpfung Among-Us-Account (Friend Code) <-> Discord-Account. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.db.Database

private val FRIEND_CODE_PATTERN = Regex("^[a-z0-9]{2,20}#[0-9]{4}$")

fun normalizeFriendCode(code: String): String = code.trim().lowercase()

fun isValidFriendCode(code: String): Boolean = FRIEND_CODE_PATTERN.matches(normalizeFriendCode(code))

/** Ein Friend Code gehört genau einem Discord-Account; ein Discord-Account darf mehrere haben. */
class LinkService(private val db: Database) {
    fun userFor(friendCode: String): Long? =
        db.queryOne("SELECT user_id FROM friend_links WHERE friend_code = ?", normalizeFriendCode(friendCode)) {
            it.long("user_id")
        }

    fun codesOf(userId: Long): List<String> =
        db.query("SELECT friend_code FROM friend_links WHERE user_id = ? ORDER BY rowid", userId) {
            it.string("friend_code")
        }

    /** Alle Verknüpfungen, gruppiert nach Discord-ID. */
    fun byUser(): Map<Long, List<String>> {
        val grouped = LinkedHashMap<Long, MutableList<String>>()
        db.query("SELECT friend_code, user_id FROM friend_links ORDER BY rowid") {
            grouped.getOrPut(it.long("user_id")) { ArrayList() }.add(it.string("friend_code"))
        }
        return grouped
    }

    fun link(friendCode: String, userId: Long) {
        db.update(
            "INSERT INTO friend_links (friend_code, user_id) VALUES (?, ?)" +
                " ON CONFLICT(friend_code) DO UPDATE SET user_id = excluded.user_id",
            normalizeFriendCode(friendCode), userId,
        )
    }

    fun unlink(userId: Long): List<String> = db.transaction {
        val removed = codesOf(userId)
        db.update("DELETE FROM friend_links WHERE user_id = ?", userId)
        removed
    }
}
