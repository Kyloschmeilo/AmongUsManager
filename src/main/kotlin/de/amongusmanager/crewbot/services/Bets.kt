/*
 * Wettbüro: XP-Wetten auf den Ausgang einer Runde.
 *
 * Offene Wetten stehen in der Datenbank, damit bei einem Neustart des Bots keine
 * bereits abgezogenen Einsätze verloren gehen.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import java.time.Duration
import java.time.Instant

data class Bet(val userId: Long, val team: Team, val amount: Int) {
    val payout: Int get() = amount * Rules.BET_PAYOUT_FACTOR
}

/** Fehler, dessen Nachricht direkt dem Spieler angezeigt werden kann. */
class BetError(message: String) : Exception(message)

class BetService(
    private val db: Database,
    private val levels: LevelService,
    private val clock: () -> Instant = Instant::now,
) {
    // Gesperrt, solange eine Runde läuft (die Mod meldet den Start). Nur im Speicher:
    // nach einem Bot-Neustart ist das Wettbüro wieder offen.
    private var lockedUntil: Instant? = null

    val active: List<Bet>
        get() = db.query("SELECT user_id, team, amount FROM bets ORDER BY id") {
            Bet(it.long("user_id"), Team.fromValue(it.string("team")) ?: Team.CREW, it.int("amount"))
        }

    val locked: Boolean
        get() = lockedUntil?.let { clock() < it } ?: false

    /** Runde hat begonnen: keine neuen Wetten. Öffnet spätestens nach BET_LOCK_MAX_MINUTES. */
    fun lock() {
        lockedUntil = clock() + Duration.ofMinutes(Rules.BET_LOCK_MAX_MINUTES)
    }

    /** Wettbüro wieder öffnen – sofort oder nach einer kurzen Wartezeit. */
    fun unlock(afterSeconds: Long = 0) {
        val current = lockedUntil ?: return
        lockedUntil = minOf(current, clock() + Duration.ofSeconds(afterSeconds))
    }

    /** Platziert eine Wette und zieht den Einsatz sofort ab. */
    fun place(userId: Long, team: Team, amount: Int): Bet = db.transaction {
        if (locked) {
            throw BetError(
                "🔒 Die Runde läuft schon – das Wettbüro ist geschlossen. Nach dem Rundenende kannst du wieder wetten!"
            )
        }
        val existing = active.firstOrNull { it.userId == userId }
        if (existing != null) {
            throw BetError(
                "Du hast bereits **${existing.amount} XP** auf **${existing.team.label}** gesetzt." +
                    " Warte bis die Runde ausgewertet wird!"
            )
        }
        if (amount !in 1..Rules.MAX_BET) throw BetError("Dein Einsatz muss zwischen 1 und ${Rules.MAX_BET} XP liegen!")

        val balance = levels.get(userId).xp
        if (balance < amount) throw BetError("Du hast nicht genug XP! (Dein Guthaben: $balance XP)")

        levels.removeXp(userId, amount)
        val bet = Bet(userId, team, amount)
        insert(bet)
        bet
    }

    /** Beendet die Wettrunde und gibt alle Wetten zur Auszahlung zurück. */
    fun settle(): List<Bet> = db.transaction {
        val bets = active
        db.update("DELETE FROM bets")
        lockedUntil = null // Runde ist vorbei -> wieder wetten
        bets
    }

    /** Bricht die Wettrunde ab und erstattet alle Einsätze. */
    fun refundAll(): List<Bet> = db.transaction {
        val bets = settle()
        for (bet in bets) levels.addXp(bet.userId, bet.amount)
        bets
    }

    /**
     * Stellt ausgewertete Wetten wieder her (für „Runde zurücksetzen“).
     *
     * Hat ein Spieler inzwischen neu gewettet, bekommt er den alten Einsatz
     * stattdessen erstattet – die neue Wette bleibt bestehen.
     */
    fun restore(bets: List<Bet>) {
        db.transaction {
            for (bet in bets) {
                if (active.any { it.userId == bet.userId }) levels.addXp(bet.userId, bet.amount) else insert(bet)
            }
        }
    }

    private fun insert(bet: Bet) {
        db.update("INSERT INTO bets (user_id, team, amount) VALUES (?, ?, ?)", bet.userId, bet.team.value, bet.amount)
    }
}
