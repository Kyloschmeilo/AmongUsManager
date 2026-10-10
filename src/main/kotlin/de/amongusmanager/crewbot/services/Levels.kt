/* Level- und XP-System: Spielerdaten, Ränge und automatische Level-Rollen. */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.LEVEL_ROLE_REWARDS
import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.db.Row
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import org.slf4j.LoggerFactory

fun levelForXp(xp: Int): Int = Math.floorDiv(xp, Rules.XP_PER_LEVEL) + 1

fun xpForLevel(level: Int): Int = (level - 1) * Rules.XP_PER_LEVEL

// ==========================================
// RÄNGE
// ==========================================

data class Rank(val name: String, val minLevel: Int)

val RANKS: List<Rank> = listOf(
    Rank("🌱 Crew-Anfänger", 1),
    Rank("🛡️ Erfahrener Astronaut", 5),
    Rank("🚀 Skeld-Veteran", 10),
    Rank("👑 Intergalaktische Legende", 20),
)

/** Höchster erreichter Rang (Level unter 1 gibt es nicht – zur Sicherheit dann der erste Rang). */
fun rankFor(level: Int): Rank = RANKS.lastOrNull { level >= it.minLevel } ?: RANKS.first()

fun nextRank(level: Int): Rank? = RANKS.firstOrNull { it.minLevel > level }

// ==========================================
// SPIELERDATEN
// ==========================================

data class PlayerStats(
    val xp: Int = 0,
    val level: Int = 1,
    val participations: Int = 0,
    val wins: Int = 0,
) {
    val rank: Rank get() = rankFor(level)
    val xpInLevel: Int get() = Math.floorMod(xp, Rules.XP_PER_LEVEL)
}

/** Ergebnis einer XP-Änderung – weiß, ob es ein Level-Up gab. */
data class XpResult(val stats: PlayerStats, val oldLevel: Int) {
    val leveledUp: Boolean get() = stats.level > oldLevel

    /**
     * Gleiches Ergebnis, aber mit dem aktuellen Stand des Spielers. Bekommt jemand in einer Runde mehrmals XP
     * (Sieg, Wette, Erfolge), zeigen alle Zeilen den End-Level – und das Protokoll fürs „Zurücksetzen“ speichert
     * ihn als neuen Level. Genau so verhielt sich der alte Bot; das Zurücksetzen (in Vergabe-Reihenfolge)
     * landet dadurch wieder beim Level vor der Runde.
     */
    fun withFinalStats(levels: LevelService, userId: Long): XpResult = copy(stats = levels.get(userId))
}

// ==========================================
// SERVICE
// ==========================================

/** Verwaltet XP, Level, Siege und Teilnahmen aller Spieler (Tabelle `players`). */
class LevelService(private val db: Database) {
    private val log = LoggerFactory.getLogger(LevelService::class.java)

    // ---------- Lesen ----------

    /** Liefert die Daten eines Spielers (oder Standardwerte, ohne zu speichern). */
    fun get(userId: Long): PlayerStats =
        db.queryOne("SELECT xp, level, participations, wins FROM players WHERE user_id = ?", userId, map = ::toStats)
            ?: PlayerStats()

    /** Alle Spieler, sortiert nach XP (höchste zuerst; bei Gleichstand wer zuerst gespeichert wurde). */
    fun ranking(): List<Pair<Long, PlayerStats>> =
        db.query("SELECT user_id, xp, level, participations, wins FROM players ORDER BY xp DESC, id ASC") {
            it.long("user_id") to toStats(it)
        }

    // ---------- Schreiben ----------

    fun addXp(userId: Long, amount: Int, participation: Boolean = false, win: Boolean = false): XpResult =
        db.transaction {
            val stats = ensure(userId)
            val xp = stats.xp + amount
            // Level sinkt nie durch Wetteinsätze, steigt aber, sobald genug XP da sind
            val updated = stats.copy(
                xp = xp,
                level = maxOf(stats.level, levelForXp(xp)),
                participations = stats.participations + if (participation) 1 else 0,
                wins = stats.wins + if (win) 1 else 0,
            )
            save(userId, updated)
            XpResult(updated, stats.level)
        }

    /** Zieht XP ab. `participation = true` macht zusätzlich eine Teilnahme rückgängig. */
    fun removeXp(userId: Long, amount: Int, participation: Boolean = false): PlayerStats =
        db.transaction {
            val stats = ensure(userId)
            val updated = stats.copy(
                xp = maxOf(0, stats.xp - amount),
                participations = if (participation) maxOf(0, stats.participations - 1) else stats.participations,
            )
            save(userId, updated)
            updated
        }

    /** Macht eine frühere XP-Vergabe rückgängig (inkl. Level-Up und Sieg). */
    fun revertXp(userId: Long, amount: Int, oldLevel: Int, newLevel: Int, win: Boolean = false): PlayerStats =
        db.transaction {
            val stats = ensure(userId)
            var updated = stats.copy(
                xp = maxOf(0, stats.xp - amount),
                wins = if (win) maxOf(0, stats.wins - 1) else stats.wins,
            )
            if (newLevel > oldLevel && updated.level == newLevel) updated = updated.copy(level = oldLevel)
            save(userId, updated)
            updated
        }

    /** Admin: XP direkt setzen, Level wird daraus neu berechnet. */
    fun setXp(userId: Long, xp: Int): XpResult =
        db.transaction {
            val stats = ensure(userId)
            val newXp = maxOf(0, xp)
            val updated = stats.copy(xp = newXp, level = levelForXp(newXp))
            save(userId, updated)
            XpResult(updated, stats.level)
        }

    /** Admin: Level direkt setzen, XP werden auf den Level-Anfang gesetzt. */
    fun setLevel(userId: Long, level: Int): XpResult =
        db.transaction {
            val stats = ensure(userId)
            val newLevel = maxOf(1, level)
            val updated = stats.copy(level = newLevel, xp = xpForLevel(newLevel))
            save(userId, updated)
            XpResult(updated, stats.level)
        }

    /** Setzt alle Werte zurück (der Spieler bleibt mit 0 XP in der Rangliste, wie im alten Bot). */
    fun reset(userId: Long) {
        db.transaction {
            ensure(userId)
            db.update(
                "UPDATE players SET xp = 0, level = 1, participations = 0, wins = 0, extra = NULL WHERE user_id = ?",
                userId,
            )
        }
    }

    // ---------- Discord-Belohnungen ----------

    /** XP gutschreiben und bei Level-Up automatisch Level-Rollen vergeben. */
    suspend fun award(
        guild: Guild?,
        userId: Long,
        amount: Int,
        participation: Boolean = false,
        win: Boolean = false,
    ): XpResult {
        val result = addXp(userId, amount, participation, win)
        grantRolesIfLeveledUp(guild, userId, result)
        return result
    }

    /** Vergibt bei einem Level-Up die neu erreichten Level-Rollen (für XP, die schon gespeichert sind). */
    suspend fun grantRolesIfLeveledUp(guild: Guild?, userId: Long, result: XpResult) {
        if (!result.leveledUp || guild == null) return
        val member = guild.getMemberById(userId) ?: return
        grantLevelRoles(member, result.stats.level)
    }

    /** Vergibt alle Level-Rollen, die der Spieler erreicht, aber noch nicht hat. */
    suspend fun grantLevelRoles(member: Member, level: Int): List<Role> {
        val granted = ArrayList<Role>()
        for ((requiredLevel, roleId) in LEVEL_ROLE_REWARDS) {
            val role = member.guild.getRoleById(roleId)
            if (level < requiredLevel || role == null || role in member.roles) continue
            try {
                member.guild.addRoleToMember(member, role).reason("Level $requiredLevel erreicht").await()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.warn("Konnte Rolle {} nicht an {} vergeben: {}", role.name, member.effectiveName, e.describe())
                continue
            }
            granted.add(role)
        }
        return granted
    }

    // ---------- intern ----------

    private fun ensure(userId: Long): PlayerStats {
        db.update("INSERT OR IGNORE INTO players (user_id) VALUES (?)", userId)
        return get(userId)
    }

    private fun save(userId: Long, stats: PlayerStats) {
        db.update(
            "UPDATE players SET xp = ?, level = ?, participations = ?, wins = ? WHERE user_id = ?",
            stats.xp, stats.level, stats.participations, stats.wins, userId,
        )
    }

    private fun toStats(row: Row) = PlayerStats(
        xp = row.int("xp"),
        level = row.int("level"),
        participations = row.int("participations"),
        wins = row.int("wins"),
    )
}
