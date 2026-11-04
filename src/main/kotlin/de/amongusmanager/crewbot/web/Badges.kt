/*
 * Stats-Website: Abzeichen im Spielerprofil (03.11.2026, Kilian: Erfolge/Abzeichen wie „5 Impostor-Siege in Folge“).
 *
 * Alles wird live aus den Runden eines Spielers berechnet (nichts gespeichert) – neue Abzeichen gelten also sofort
 * rückwirkend für alle alten Runden. Jedes Abzeichen hat drei Stufen (Bronze, Silber, Gold); gemerkt wird, in welcher
 * Runde eine Stufe erreicht wurde. Unabhängig von den Erfolgen des Bots (/erfolge, mit XP): die stehen in der
 * Bot-Datenbank und gelten nur für verknüpfte Spieler, Abzeichen gibt es für jeden, der mitgespielt hat.
 *
 * Neue Abzeichen: unten in [BADGES] ergänzen (id nie umbenennen – das Frontend kennt die Symbole über die id).
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.TIMEZONE
import kotlinx.serialization.json.JsonObject

/** Eine Runde aus Sicht eines Spielers. */
class BadgeRound(val round: WebRound, val me: WebPlayer)

enum class BadgeGroup(val label: String) {
    STREAK("Serien"),
    MOMENT("Momente"),
    CAREER("Laufbahn"),
    MISHAP("Pech gehabt"),
}

/** Zählt mit, Runde für Runde (älteste zuerst). */
abstract class BadgeCounter {
    /** Wert, der mit den Stufen verglichen wird (Bestwert bei Serien). */
    abstract val value: Int

    /** Laufende Serie (nur bei Serien, sonst null). */
    open val current: Int? get() = null

    abstract fun add(r: BadgeRound)
}

class BadgeDef(
    val id: String,
    val group: BadgeGroup,
    val name: String,
    /** Was zählt, als Satz ohne Zahl. */
    val description: String,
    /** Einheit hinter dem Wert („Siege in Folge“). */
    val unit: String,
    /** Bronze, Silber, Gold. */
    val tiers: IntArray,
    val counter: () -> BadgeCounter,
)

/** Zählt Runden, in denen [test] zutrifft. */
private fun count(test: (BadgeRound) -> Boolean): () -> BadgeCounter = {
    object : BadgeCounter() {
        override var value = 0
        override fun add(r: BadgeRound) { if (test(r)) value++ }
    }
}

/** Summe über alle Runden. */
private fun sum(amount: (BadgeRound) -> Number): () -> BadgeCounter = {
    object : BadgeCounter() {
        var total = 0.0
        override val value: Int get() = total.toInt()
        override fun add(r: BadgeRound) { total += amount(r).toDouble() }
    }
}

/**
 * Längste Serie: [counts] = Runde zählt für die Serie (sonst wird sie übersprungen), [hit] = Serie geht weiter
 * (sonst reißt sie).
 */
private fun streak(counts: (BadgeRound) -> Boolean, hit: (BadgeRound) -> Boolean): () -> BadgeCounter = {
    object : BadgeCounter() {
        var run = 0
        override var value = 0
        override val current: Int get() = run
        override fun add(r: BadgeRound) {
            if (!counts(r)) return
            run = if (hit(r)) run + 1 else 0
            if (run > value) value = run
        }
    }
}

/** Anzahl verschiedener Werte. */
private fun distinct(of: (BadgeRound) -> Any?): () -> BadgeCounter = {
    object : BadgeCounter() {
        val seen = HashSet<Any>()
        override val value: Int get() = seen.size
        override fun add(r: BadgeRound) { of(r)?.let { seen += it } }
    }
}

// ---------- Hilfen für einzelne Runden ----------

private val WebPlayer.alive: Boolean get() = !isDead && !disconnected
private val BadgeRound.decided: Boolean get() = round.winningTeam != UNKNOWN
private fun BadgeRound.votesBy(): List<WebVote> = round.meetings.flatMap { m -> m.votes.filter { it.voter == me.playerId } }

/** 2 eigene Kills innerhalb von 30 Sekunden. */
private fun BadgeRound.doubleKill(): Boolean {
    val times = round.kills.filter { it.killer == me.playerId && it.victim != me.playerId }.map { it.atSeconds }
    return times.zipWithNext().any { (a, b) -> b - a <= 30.0 }
}

/** Als Crew mindestens 2 Stimmen auf Impostor und keine auf einen Crewmate. */
private fun BadgeRound.sharpEye(): Boolean {
    if (!me.isCrew) return false
    var right = 0
    var wrong = 0
    for (vote in votesBy()) {
        val target = round.player(vote.target) ?: continue
        when {
            target.isImpostor -> right++
            target.isCrew -> wrong++
        }
    }
    return right >= 2 && wrong == 0
}

/** Als Impostor gewonnen, Meetings gab es, aber keine einzige Stimme gegen dich. */
private fun BadgeRound.invisible(): Boolean =
    me.isImpostor && me.won && round.meetings.any { it.votes.isNotEmpty() } &&
        round.meetings.none { m -> m.votes.any { it.target == me.playerId } }

/** Letzter Impostor: Partner ist raus, du gewinnst trotzdem und lebst. */
private fun BadgeRound.loneWolf(): Boolean =
    me.isImpostor && me.won && me.alive &&
        round.players.any { it.playerId != me.playerId && it.isImpostor && !it.alive }

/** Crew gewinnt, du bist der einzige Crewmate, der noch lebt (mindestens 3 andere Crewmates in der Runde). */
private fun BadgeRound.lastHope(): Boolean {
    if (!me.isCrew || !me.won || !me.alive || round.winningTeam != CREW) return false
    val others = round.players.filter { it.playerId != me.playerId && it.isCrew }
    return others.size >= 3 && others.none { it.alive }
}

/** Leiche gemeldet, und im selben Meeting flog ein Impostor raus. */
private fun BadgeRound.detective(): Boolean = me.isCrew && round.meetings.any { m ->
    m.caller == me.playerId && m.body != null && round.player(m.exiled)?.isImpostor == true
}

private fun BadgeRound.ejectedAsCrew(): Boolean =
    me.isCrew && (me.deathCause == "ejected" || me.deathCause == "exile" || round.meetings.any { it.exiled == me.playerId })

private fun BadgeRound.allTasks(): Boolean = me.isCrew && me.tasksTotal >= 3 && me.tasksCompleted >= me.tasksTotal

private fun BadgeRound.speedrun(): Boolean =
    me.isCrew && me.tasksTotal >= 3 && (me.summary?.allTasksSeconds?.let { it <= 120.0 } ?: false)

/** Runde zwischen 2 und 6 Uhr (Berlin) beendet. */
private fun BadgeRound.lateNight(): Boolean = round.endedAt.atZone(TIMEZONE).hour in 2..5

/** Meiste Runden an einem Spieleabend. */
private fun busiestNight(): () -> BadgeCounter = {
    object : BadgeCounter() {
        val nights = HashMap<String, Int>()
        override var value = 0
        override fun add(r: BadgeRound) {
            val n = nights.merge(gameNight(r.round.endedAt), 1, Int::plus)!!
            if (n > value) value = n
        }
    }
}

private fun tiers(vararg t: Int) = t

val BADGES: List<BadgeDef> = listOf(
    // Serien
    BadgeDef(
        "impostor_streak", BadgeGroup.STREAK, "Eiskalt", "Impostor-Runden in Folge gewonnen (Crew-Runden dazwischen zählen nicht)",
        "Siege in Folge", tiers(3, 5, 10), streak({ it.me.isImpostor && it.decided }, { it.me.won }),
    ),
    BadgeDef(
        "crew_streak", BadgeGroup.STREAK, "Bollwerk", "Crew-Runden in Folge gewonnen (Impostor-Runden dazwischen zählen nicht)",
        "Siege in Folge", tiers(3, 5, 10), streak({ it.me.isCrew && it.decided }, { it.me.won }),
    ),
    BadgeDef(
        "win_streak", BadgeGroup.STREAK, "Siegesserie", "Runden in Folge gewonnen, egal in welchem Team",
        "Siege in Folge", tiers(3, 5, 10), streak({ it.decided }, { it.me.won }),
    ),
    BadgeDef(
        "survivor_streak", BadgeGroup.STREAK, "Unkaputtbar", "Runden in Folge bis zum Ende überlebt",
        "Runden in Folge", tiers(3, 5, 10), streak({ true }, { it.me.alive }),
    ),
    // Momente
    BadgeDef(
        "double_kill", BadgeGroup.MOMENT, "Doppelschlag", "Zwei Kills innerhalb von 30 Sekunden",
        "Runden", tiers(1, 5, 15), count { it.doubleKill() },
    ),
    BadgeDef(
        "massacre", BadgeGroup.MOMENT, "Großreinemachen", "Drei oder mehr Kills in einer Runde",
        "Runden", tiers(1, 5, 15), count { it.me.kills >= 3 },
    ),
    BadgeDef(
        "first_blood", BadgeGroup.MOMENT, "Erstschlag", "Den ersten Kill der Runde gemacht",
        "Runden", tiers(1, 10, 25), count { r -> r.round.kills.firstOrNull()?.let { it.killer == r.me.playerId && it.victim != r.me.playerId } == true },
    ),
    BadgeDef(
        "invisible", BadgeGroup.MOMENT, "Unsichtbar", "Als Impostor gewonnen, ohne eine einzige Stimme abzubekommen",
        "Runden", tiers(1, 5, 15), count { it.invisible() },
    ),
    BadgeDef(
        "lone_wolf", BadgeGroup.MOMENT, "Einsamer Wolf", "Als letzter Impostor gewonnen, nachdem der Partner raus war",
        "Runden", tiers(1, 3, 10), count { it.loneWolf() },
    ),
    BadgeDef(
        "sharp_eye", BadgeGroup.MOMENT, "Scharfsinn", "Als Crew zwei richtige Stimmen in einer Runde und keine falsche",
        "Runden", tiers(1, 5, 15), count { it.sharpEye() },
    ),
    BadgeDef(
        "detective", BadgeGroup.MOMENT, "Spürnase", "Leiche gemeldet, und im selben Meeting flog ein Impostor raus",
        "Runden", tiers(1, 5, 15), count { it.detective() },
    ),
    BadgeDef(
        "last_hope", BadgeGroup.MOMENT, "Letzte Hoffnung", "Crew gewinnt, und du bist der einzige Crewmate, der noch lebt",
        "Runden", tiers(1, 3, 10), count { it.lastHope() },
    ),
    BadgeDef(
        "speedrun", BadgeGroup.MOMENT, "Schnelldurchlauf", "Alle Aufgaben in unter zwei Minuten erledigt",
        "Runden", tiers(1, 5, 15), count { it.speedrun() },
    ),
    BadgeDef(
        "vent_rat", BadgeGroup.MOMENT, "Lüftungsratte", "Zehn oder mehr Vent-Einstiege in einer Runde",
        "Runden", tiers(1, 5, 15), count { (it.me.summary?.ventUses ?: 0) >= 10 },
    ),
    BadgeDef(
        "neutral_win", BadgeGroup.MOMENT, "Eigene Wege", "Mit einer neutralen Rolle gewonnen",
        "Siege", tiers(1, 5, 15), count { it.me.isNeutral && it.me.won },
    ),
    // Laufbahn
    BadgeDef(
        "regular", BadgeGroup.CAREER, "Stammgast", "Runden gespielt",
        "Runden", tiers(10, 50, 200), sum { 1 },
    ),
    BadgeDef(
        "hunter", BadgeGroup.CAREER, "Jäger", "Kills insgesamt",
        "Kills", tiers(10, 50, 150), sum { it.me.kills },
    ),
    BadgeDef(
        "diligent", BadgeGroup.CAREER, "Pflichtbewusst", "Runden mit allen Aufgaben erledigt",
        "Runden", tiers(5, 25, 100), count { it.allTasks() },
    ),
    BadgeDef(
        "mechanic", BadgeGroup.CAREER, "Mechaniker", "Sabotagen repariert",
        "Reparaturen", tiers(10, 50, 150), sum { it.me.summary?.sabotagesFixed ?: 0 },
    ),
    BadgeDef(
        "marathon", BadgeGroup.CAREER, "Marathon", "Strecke über alle Runden",
        "m", tiers(5_000, 25_000, 100_000), sum { it.me.summary?.distance ?: 0.0 },
    ),
    BadgeDef(
        "roles", BadgeGroup.CAREER, "Rollenvielfalt", "Verschiedene Rollen gespielt",
        "Rollen", tiers(5, 15, 30), distinct { roleIdOf(it.me) },
    ),
    BadgeDef(
        "globetrotter", BadgeGroup.CAREER, "Weltenbummler", "Auf verschiedenen Maps gewonnen",
        "Maps", tiers(2, 3, 5), distinct { r -> r.round.mapId.takeIf { r.me.won && it >= 0 } },
    ),
    BadgeDef(
        "night_owl", BadgeGroup.CAREER, "Nachteule", "Runden nach 2 Uhr nachts beendet",
        "Runden", tiers(1, 10, 50), count { it.lateNight() },
    ),
    BadgeDef(
        "long_night", BadgeGroup.CAREER, "Dauerbrenner", "Runden an einem einzigen Spieleabend",
        "Runden", tiers(10, 15, 25), busiestNight(),
    ),
    // Pech gehabt (mit Augenzwinkern)
    BadgeDef(
        "first_victim", BadgeGroup.MISHAP, "Erstes Opfer", "Als Erster der Runde getötet",
        "Runden", tiers(1, 10, 25), count { r -> r.round.kills.firstOrNull()?.let { it.victim == r.me.playerId && it.killer != r.me.playerId } == true },
    ),
    BadgeDef(
        "miscarriage", BadgeGroup.MISHAP, "Justizirrtum", "Als Crewmate rausgewählt",
        "Runden", tiers(1, 5, 15), count { it.ejectedAsCrew() },
    ),
)

/** Ergebnis eines Abzeichens für einen Spieler. */
class BadgeResult(
    val def: BadgeDef,
    val value: Int,
    val current: Int?,
    /** 0 = noch nicht, 1 Bronze, 2 Silber, 3 Gold. */
    val tier: Int,
    /** Runde, in der die jeweilige Stufe erreicht wurde (Index = Stufe - 1). */
    val reached: List<WebRound>,
)

object Badges {
    /** Wertet alle Abzeichen über die Runden eines Spielers aus ([rounds]: älteste zuerst). */
    fun evaluate(rounds: List<BadgeRound>): List<BadgeResult> {
        val counters = BADGES.map { it.counter() }
        val reached = BADGES.map { ArrayList<WebRound>(3) }
        for (r in rounds) {
            BADGES.forEachIndexed { i, def ->
                val counter = counters[i]
                counter.add(r)
                val got = reached[i]
                while (got.size < def.tiers.size && counter.value >= def.tiers[got.size]) got += r.round
            }
        }
        return BADGES.mapIndexed { i, def ->
            BadgeResult(def, counters[i].value, counters[i].current, reached[i].size, reached[i])
        }
    }

    /**
     * JSON fürs Profil. [rarity]: Anteil der Spieler (0..1), die mindestens Bronze haben, je Abzeichen-id.
     * Reihenfolge: wie in [BADGES] (Frontend sortiert/gruppiert selbst).
     */
    fun json(results: List<BadgeResult>, rarity: Map<String, Double>): List<JsonObject> = results.map { r ->
        obj(
            "id" to r.def.id,
            "group" to r.def.group.label,
            "name" to r.def.name,
            "description" to r.def.description,
            "unit" to r.def.unit,
            "tiers" to r.def.tiers.toList(),
            "value" to r.value,
            "current" to r.current,
            "tier" to r.tier,
            "reached" to r.reached.map { round -> obj("gameId" to round.gameId, "endedAt" to round.endedAt) },
            "rarity" to rarity[r.def.id],
        )
    }
}
