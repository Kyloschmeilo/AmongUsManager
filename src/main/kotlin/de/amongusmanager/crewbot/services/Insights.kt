/*
 * Auswertungen aus den Mod-Runden: Highlights einer Runde, Rivalen eines Spielers und der
 * Spieleabend-Recap. Reine Rechenfunktionen ohne Discord – die Anzeige macht die UI.
 *
 * Spieler werden als `PlayerKey` gezählt: Discord-ID, wenn verknüpft, sonst der Ingame-Name –
 * so tauchen auch Unverknüpfte in Recap und Rivalen auf.
 */
package de.amongusmanager.crewbot.services

const val DOUBLE_KILL_SECONDS = 30
const val BLITZ_ROUND_SECONDS = 180
const val MIN_SUS_VOTES = 3
const val MIN_DETECTIVE_VOTES = 2

sealed interface PlayerKey {
    data class User(val id: Long) : PlayerKey
    data class Name(val name: String) : PlayerKey
}

fun formatTime(seconds: Double): String {
    val total = seconds.toInt()
    val minutes = Math.floorDiv(total, 60)
    val rest = Math.floorMod(total, 60)
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

fun keyOf(history: HistoryRound, player: ModPlayer): PlayerKey =
    history.discordIds[player.playerId]?.let { PlayerKey.User(it) } ?: PlayerKey.Name(player.name)

/** Zähler, der sich die Reihenfolge merkt (wie Pythons `Counter`). */
private class Counter<K> {
    val counts = LinkedHashMap<K, Int>()

    fun add(key: K, amount: Int = 1) {
        counts[key] = (counts[key] ?: 0) + amount
    }

    operator fun get(key: K): Int = counts[key] ?: 0

    /** Häufigster Eintrag; bei Gleichstand der zuerst gezählte. */
    fun mostCommon(): Pair<K, Int>? = counts.entries.maxByOrNull { it.value }?.toPair()
}

// ==========================================
// BAUSTEINE PRO RUNDE
// ==========================================

/** PlayerId -> Anzahl Stimmen gegen ihn (über alle Meetings). */
fun votesReceived(round: ModRound): Map<Int, Int> {
    val counter = Counter<Int>()
    for (meeting in round.meetings) for (vote in meeting.votes) vote.target?.let { counter.add(it) }
    return counter.counts
}

/** Crewmate-PlayerId -> (richtige, falsche) Stimmen. Übersprungen zählt nicht. */
fun voteAccuracy(round: ModRound): Map<Int, Pair<Int, Int>> {
    val result = LinkedHashMap<Int, IntArray>()
    for (meeting in round.meetings) {
        for (vote in meeting.votes) {
            val voter = round.player(vote.voter)
            val target = round.player(vote.target)
            if (voter == null || target == null || voter.isImpostor) continue
            result.getOrPut(vote.voter) { IntArray(2) }[if (target.isImpostor) 0 else 1]++
        }
    }
    return result.mapValuesTo(LinkedHashMap()) { (_, counts) -> counts[0] to counts[1] }
}

/** Killer-PlayerId -> kürzester Abstand zwischen zwei eigenen Kills (nur ≤ DOUBLE_KILL_SECONDS). */
fun fastestDoubleKill(round: ModRound): Map<Int, Double> {
    val times = LinkedHashMap<Int, MutableList<Double>>()
    for (kill in round.kills) times.getOrPut(kill.killer) { ArrayList() }.add(kill.atSeconds)
    val result = LinkedHashMap<Int, Double>()
    for ((killer, stamps) in times) {
        val gaps = stamps.zipWithNext { a, b -> b - a }.filter { it <= DOUBLE_KILL_SECONDS }
        if (gaps.isNotEmpty()) result[killer] = gaps.min()
    }
    return result
}

/** Die besonderen Momente einer Runde als fertige Textzeilen. */
fun roundHighlights(round: ModRound, name: (ModPlayer) -> String): List<String> {
    val lines = ArrayList<String>()

    round.kills.firstOrNull()?.let { first ->
        val killer = round.player(first.killer)
        val victim = round.player(first.victim)
        if (killer != null && victim != null) {
            lines.add("🩸 First Blood nach ${formatTime(first.atSeconds)}: ${name(killer)} → ${name(victim)}")
        }
    }

    for ((killerId, gap) in fastestDoubleKill(round)) {
        val killer = round.player(killerId) ?: continue
        lines.add("🔪 Doppelkill: ${name(killer)} (2 Kills in ${gap.toInt()} s)")
    }

    val detectives = voteAccuracy(round).entries
        .filter { (_, counts) -> counts.first >= MIN_DETECTIVE_VOTES && counts.second == 0 }
        .map { it.key to it.value.first }
    detectives.maxByOrNull { it.second }?.let { (playerId, right) ->
        lines.add("🕵️ Detektiv der Runde: ${name(round.player(playerId)!!)} ($right/$right richtige Stimmen)")
    }

    val received = votesReceived(round)
    val crewTargets = received.entries.filter { (playerId, _) -> round.player(playerId)?.isImpostor == false }
    crewTargets.maxByOrNull { it.value }?.let { (playerId, count) ->
        if (count >= MIN_SUS_VOTES) {
            lines.add("🤡 Sus-Magnet: ${name(round.player(playerId)!!)} bekam als Crewmate $count Stimmen")
        }
    }

    if (round.meetings.isNotEmpty()) {
        for (impostor in round.players) {
            if (impostor.isImpostor && !impostor.isDead && (received[impostor.playerId] ?: 0) == 0) {
                lines.add("🎭 Unter dem Radar: ${name(impostor)} bekam als Impostor keine einzige Stimme")
            }
        }
    }

    if (round.durationSeconds > 0 && round.durationSeconds < BLITZ_ROUND_SECONDS) {
        lines.add("⚡ Blitzrunde: vorbei nach ${formatTime(round.durationSeconds)}")
    }
    return lines
}

// ==========================================
// RIVALEN (für /stats)
// ==========================================

data class Rivals(
    val nemesis: Pair<PlayerKey, Int>? = null, // hat mich am häufigsten getötet
    val favoriteVictim: Pair<PlayerKey, Int>? = null, // habe ich am häufigsten getötet
    val biggestDoubter: Pair<PlayerKey, Int>? = null, // hat am häufigsten gegen mich gestimmt
    val mySuspect: Pair<PlayerKey, Int>? = null, // gegen den habe ich am häufigsten gestimmt
) {
    val empty: Boolean get() = nemesis == null && favoriteVictim == null && biggestDoubter == null && mySuspect == null
}

fun rivalsOf(userId: Long, rounds: Iterable<HistoryRound>): Rivals {
    val killedMe = Counter<PlayerKey>()
    val iKilled = Counter<PlayerKey>()
    val doubtedMe = Counter<PlayerKey>()
    val iDoubted = Counter<PlayerKey>()
    for (history in rounds) {
        val me = history.playerOf(userId) ?: continue
        val round = history.round
        for (kill in round.kills) {
            val killer = round.player(kill.killer)
            val victim = round.player(kill.victim)
            if (killer == null || victim == null) continue
            if (victim.playerId == me.playerId) {
                killedMe.add(keyOf(history, killer))
            } else if (killer.playerId == me.playerId) {
                iKilled.add(keyOf(history, victim))
            }
        }
        for (meeting in round.meetings) {
            for (vote in meeting.votes) {
                val voter = round.player(vote.voter)
                val target = round.player(vote.target)
                if (voter == null || target == null || vote.voter == vote.target) continue
                if (target.playerId == me.playerId) {
                    doubtedMe.add(keyOf(history, voter))
                } else if (voter.playerId == me.playerId) {
                    iDoubted.add(keyOf(history, target))
                }
            }
        }
    }
    return Rivals(killedMe.mostCommon(), iKilled.mostCommon(), doubtedMe.mostCommon(), iDoubted.mostCommon())
}

// ==========================================
// SPIELEABEND-RECAP
// ==========================================

class PlayerNight {
    var games = 0
    var wins = 0
    var kills = 0
    var survived = 0
    var correctVotes = 0
    var wrongVotes = 0
    var susVotes = 0 // Stimmen gegen ihn, während er Crewmate war
    var firstDeaths = 0

    val mvpScore: Int get() = wins * 3 + kills * 2 + correctVotes * 2 + survived
}

class Recap {
    var rounds = 0
    var crewWins = 0
    var impostorWins = 0
    var totalKills = 0
    val players = LinkedHashMap<PlayerKey, PlayerNight>()

    /** Bester Spieler in einer Kategorie (bei Gleichstand der zuerst gezählte). */
    fun best(minimum: Int = 1, value: (PlayerNight) -> Int): Pair<PlayerKey, Int>? =
        players.entries.map { it.key to value(it.value) }.filter { it.second >= minimum }.maxByOrNull { it.second }

    fun mvpCandidates(limit: Int = 3): List<Pair<PlayerKey, PlayerNight>> =
        players.entries.sortedByDescending { it.value.mvpScore }
            .filter { it.value.mvpScore > 0 }
            .take(limit)
            .map { it.key to it.value }
}

fun buildRecap(rounds: Iterable<HistoryRound>): Recap {
    val recap = Recap()
    for (history in rounds) {
        val round = history.round
        recap.rounds += 1
        if (round.winningTeam?.value == "CREW") recap.crewWins += 1
        if (round.winningTeam?.value == "IMPOSTOR") recap.impostorWins += 1
        recap.totalKills += round.kills.size

        val accuracy = voteAccuracy(round)
        val received = votesReceived(round)
        val firstVictim = round.kills.firstOrNull()?.victim
        for (player in round.players) {
            val night = recap.players.getOrPut(keyOf(history, player)) { PlayerNight() }
            night.games += 1
            if (player.won) night.wins += 1
            night.kills += player.kills
            if (!player.isDead && !player.disconnected) night.survived += 1
            val (right, wrong) = accuracy[player.playerId] ?: (0 to 0)
            night.correctVotes += right
            night.wrongVotes += wrong
            if (!player.isImpostor) night.susVotes += received[player.playerId] ?: 0
            if (player.playerId == firstVictim) night.firstDeaths += 1
        }
    }
    return recap
}
