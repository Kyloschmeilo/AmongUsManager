/*
 * Stats-Website: Monatsrückblick mit „Runde des Monats“ (Kilian, 21.11.2026). Alles live aus den Runden berechnet, nichts
 * gespeichert: Zahlen des Monats, die spektakulärste Runde (Punkte nach Kills, Rauswürfen, knappen Enden, Sondersiegen …
 * mit den Gründen), Auszeichnungen für Spieler, Momente (schnellster Kill, längste Runde …), Rollen und Maps.
 * Monat = Monat des Spieleabends (ein Abend geht bis 6 Uhr morgens, wie beim Bot). Von Admins aus Bestenlisten
 * ausgenommene Spieler bekommen keine Auszeichnungen.
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.YearMonth

/** Warum eine Runde spektakulär war: Punkte + Gründe zum Anzeigen. */
data class Spectacle(val score: Double, val reasons: List<String>)

object MonthlyRecap {
    private val MONTH = Regex("""\d{4}-\d{2}""")
    private val MONTH_NAMES = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    /** Ab so vielen Runden zählen Quoten (Siegquote, Überleben) für eine Auszeichnung. */
    const val MIN_GAMES = 3

    fun monthOf(view: RoundView): YearMonth = YearMonth.from(LocalDate.parse(gameNight(view.round.endedAt)))

    fun label(month: YearMonth): String = "${MONTH_NAMES[month.monthValue - 1]} ${month.year}"

    /**
     * Wie sehenswert eine Runde ist. Bewusst einfache Regeln, damit die Gründe stimmen: viele Kills, Doppel-Kills,
     * erwischte Impostor, zu Unrecht rausgewählte Crewmates, Sondersiege, knappe Enden, lange Runden.
     */
    fun spectacle(round: WebRound): Spectacle {
        var score = 0.0
        val reasons = ArrayList<String>()
        val byId = round.players.associateBy { it.playerId }
        fun name(id: Int?) = id?.let { byId[it]?.name } ?: "?"

        val kills = round.kills.size
        score += kills * 1.5
        if (kills >= 4) reasons += "$kills Kills"

        // Doppel-Kill: zwei Kills innerhalb von 10 Sekunden
        val sorted = round.kills.sortedBy { it.atSeconds }
        val doubles = sorted.zipWithNext().count { (a, b) -> b.atSeconds - a.atSeconds <= 10.0 }
        if (doubles > 0) {
            score += 4.0 * doubles
            reasons += if (doubles == 1) "Doppel-Kill" else "$doubles Doppel-Kills"
        }

        // Ein Spieler mit vielen Kills
        val topKiller = round.players.maxByOrNull { it.kills }
        if (topKiller != null && topKiller.kills >= 4) {
            score += 3.0
            reasons += "${topKiller.name} mit ${topKiller.kills} Kills"
        }

        val exiled = round.meetings.mapNotNull { m -> m.exiled?.let { byId[it] } }
        val caught = exiled.count { it.isImpostor }
        val wrong = exiled.count { it.isCrew }
        if (caught > 0) {
            score += 3.0 * caught
            reasons += if (caught == 1) "Impostor rausgewählt" else "$caught Impostor rausgewählt"
        }
        if (wrong > 0) {
            score += 2.0 * wrong
            reasons += if (wrong == 1) "Crewmate zu Unrecht rausgewählt" else "$wrong Crewmates zu Unrecht rausgewählt"
        }
        val ties = round.meetings.count { it.tie }
        if (ties > 0) {
            score += ties.toDouble()
            reasons += if (ties == 1) "Gleichstand im Meeting" else "$ties Gleichstände im Meeting"
        }

        when (round.winningTeam) {
            NEUTRAL -> {
                score += 6.0
                val winners = round.players.filter { it.won }.joinToString(", ") { it.name }
                reasons += if (winners.isNotEmpty()) "Sondersieg für $winners" else "Sondersieg"
            }
            CREW -> {
                val alive = round.players.count { it.isCrew && !it.isDead && !it.disconnected }
                if (alive in 1..2 && round.players.count { it.isCrew } >= 4) {
                    score += 5.0
                    reasons += if (alive == 1) "Crew gewinnt mit nur einem Überlebenden" else "Crew gewinnt mit nur zwei Überlebenden"
                }
            }
            IMPOSTOR -> {
                val impostors = round.players.filter { it.isImpostor }
                val left = impostors.count { !it.isDead && !it.disconnected }
                if (impostors.size >= 2 && left == 1) {
                    score += 4.0
                    reasons += "${impostors.first { !it.isDead && !it.disconnected }.name} holt den Sieg allein"
                }
            }
        }

        val minutes = round.durationSeconds / 60.0
        score += minutes.coerceAtMost(15.0) * 0.3
        if (minutes >= 12) reasons += "${minutes.toInt()} Minuten lang"
        score += (round.players.size - 6).coerceAtLeast(0) * 0.2
        if (round.hasTracks) score += 1.0
        // Name für den ersten Kill, wenn er sehr früh kam
        sorted.firstOrNull()?.takeIf { it.atSeconds <= 20.0 }?.let {
            score += 1.0
            reasons += "${name(it.killer)} schlägt nach ${it.atSeconds.toInt()} Sekunden zu"
        }
        if (reasons.isEmpty()) reasons += if (kills > 0) "$kills ${if (kills == 1) "Kill" else "Kills"}, ${round.meetings.size} Meetings" else "Ruhige Runde"
        return Spectacle(score, reasons)
    }

    /** Monatsrückblick, null = [month] ist kein gültiger Monat. Ohne Runden: nur die Liste der Monate (leer). */
    fun build(snapshot: StatsSnapshot, month: String?): JsonObject? {
        if (month != null && !MONTH.matches(month)) return null
        val byMonth = snapshot.views.groupBy { monthOf(it) }
        val months = byMonth.keys.sortedDescending()
        val chosen = month?.let { runCatching { YearMonth.parse(it) }.getOrNull() ?: return null } ?: months.firstOrNull()
        val monthList = months.map { obj("month" to it.toString(), "label" to label(it), "rounds" to byMonth[it]!!.size) }
        if (chosen == null) return obj("months" to monthList, "month" to null)
        val views = byMonth[chosen].orEmpty() // neueste zuerst
        val previous = byMonth[chosen.minusMonths(1)].orEmpty()

        // ---------- Zahlen ----------
        val rounds = views.map { it.round }
        val teams = linkedMapOf(CREW to 0, IMPOSTOR to 0, NEUTRAL to 0, UNKNOWN to 0)
        rounds.forEach { teams.merge(it.winningTeam, 1, Int::plus) }
        val nights = views.groupingBy { gameNight(it.round.endedAt) }.eachCount()
        val firstSeen = HashMap<String, java.time.Instant>()
        for (view in snapshot.views) for (key in view.keys) firstSeen[key] = view.round.endedAt // neueste zuerst → am Ende die erste Runde
        val playerKeys = views.flatMap { it.keys }.toSet()
        val newcomers = playerKeys.filter { key -> firstSeen[key]?.let { YearMonth.from(LocalDate.parse(gameNight(it))) } == chosen }

        // ---------- Spieler ----------
        class Acc { var games = 0; var wins = 0; var kills = 0; var survived = 0; var impGames = 0; var impWins = 0
            var rightVotes = 0; var firstDeaths = 0; var tasks = 0 }
        val acc = HashMap<String, Acc>()
        for (view in views) {
            val r = view.round
            val byId = r.players.withIndex().associate { (i, p) -> p.playerId to i }
            val firstVictim = r.kills.minByOrNull { it.atSeconds }?.victim
            r.players.forEachIndexed { i, p ->
                val a = acc.getOrPut(view.keys[i]) { Acc() }
                a.games++
                if (p.won) a.wins++
                a.kills += p.kills
                if (!p.isDead && !p.disconnected) a.survived++
                if (p.isImpostor) { a.impGames++; if (p.won) a.impWins++ }
                if (p.playerId == firstVictim) a.firstDeaths++
                if (p.isCrew) a.tasks += p.tasksCompleted
            }
            for (m in r.meetings) for (v in m.votes) {
                val voter = byId[v.voter]?.let { r.players[it] } ?: continue
                val target = v.target?.let { byId[it] }?.let { r.players[it] } ?: continue
                if (voter.isCrew && target.isImpostor) acc.getOrPut(view.keys[byId[v.voter]!!]) { Acc() }.rightVotes++
            }
        }
        val ranked = acc.filterKeys { !snapshot.isHidden(it) }
        fun award(id: String, title: String, text: String, unit: String, pick: (Acc) -> Double?, tie: (Acc) -> Double = { it.games.toDouble() }, format: (Double) -> String = { it.toInt().toString() }): JsonObject? {
            val best = ranked.entries.mapNotNull { (key, a) -> pick(a)?.takeIf { it > 0 }?.let { Triple(key, it, a) } }
                .sortedWith(compareByDescending<Triple<String, Double, Acc>> { it.second }.thenByDescending { tie(it.third) })
            val top = best.firstOrNull() ?: return null
            val player = snapshot.refOf(top.first) ?: return null
            return obj(
                "id" to id, "title" to title, "text" to text, "player" to player, "value" to format(top.second), "unit" to unit,
                "runnersUp" to best.drop(1).take(2).mapNotNull { (key, v, _) -> snapshot.refOf(key)?.let { JsonObject(it + ("value" to format(v).toJson())) } },
            )
        }
        val pct = { v: Double -> "${(v * 100).toInt()} %" }
        val awards = listOfNotNull(
            award("mvp", "Spieler des Monats", "Die meisten Siege", "Siege", { it.wins.toDouble() }, tie = { if (it.games == 0) 0.0 else it.wins.toDouble() / it.games }),
            award("killer", "Killer des Monats", "Die meisten Kills", "Kills", { it.kills.toDouble() }),
            award("impostor", "Bester Impostor", "Höchste Siegquote als Impostor (ab $MIN_GAMES Runden)", "Siegquote",
                { a -> if (a.impGames >= MIN_GAMES) a.impWins.toDouble() / a.impGames else null }, tie = { it.impGames.toDouble() }, format = pct),
            award("detective", "Spürnase", "Am häufigsten als Crew für einen Impostor gestimmt", "Treffer", { it.rightVotes.toDouble() }),
            award("survivor", "Überlebenskünstler", "Am häufigsten bis zum Ende überlebt (ab $MIN_GAMES Runden)", "überlebt",
                { a -> if (a.games >= MIN_GAMES) a.survived.toDouble() / a.games else null }, format = pct),
            award("tasks", "Task-Held", "Die meisten erledigten Aufgaben", "Aufgaben", { it.tasks.toDouble() }),
            award("regular", "Dauerbrenner", "Die meisten Runden gespielt", "Runden", { it.games.toDouble() }, tie = { it.wins.toDouble() }),
            award("unlucky", "Pechvogel", "Am häufigsten als Erstes gestorben", "mal zuerst", { it.firstDeaths.toDouble() }),
        )

        // ---------- Runde des Monats ----------
        val scored = views.map { it to spectacle(it.round) }.sortedByDescending { it.second.score }
        fun scoredJson(entry: Pair<RoundView, Spectacle>) = JsonObject(
            snapshot.roundItemOf(entry.first) + mapOf("score" to entry.second.score.toJson(), "reasons" to entry.second.reasons.toJson()),
        )

        // ---------- Momente ----------
        fun playerOf(view: RoundView, playerId: Int?): JsonObject? {
            val i = view.round.players.indexOfFirst { it.playerId == playerId }
            if (i < 0) return null
            val key = view.keys[i]
            if (snapshot.isHidden(key)) return null
            return obj("key" to key, "name" to view.round.players[i].name, "colorId" to (view.round.players[i].colorId ?: snapshot.colorOf(key)))
        }
        val fastestKill = views.flatMap { v -> v.round.kills.map { v to it } }.minByOrNull { it.second.atSeconds }
        val longest = views.maxByOrNull { it.round.durationSeconds }
        val shortest = views.filter { it.round.durationSeconds > 0 }.minByOrNull { it.round.durationSeconds }
        val multi = views.flatMap { v -> v.round.players.map { v to it } }.filter { it.second.kills > 0 }.maxByOrNull { it.second.kills }
        val bestNight = nights.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val moments = listOfNotNull(
            fastestKill?.let { (v, k) -> obj("id" to "fastestKill", "title" to "Schnellster Kill", "value" to k.atSeconds, "unit" to "s", "player" to playerOf(v, k.killer), "round" to snapshot.roundItemOf(v)) },
            multi?.let { (v, p) -> obj("id" to "mostKills", "title" to "Meiste Kills in einer Runde", "value" to p.kills, "unit" to "Kills", "player" to playerOf(v, p.playerId), "round" to snapshot.roundItemOf(v)) },
            longest?.let { v -> obj("id" to "longest", "title" to "Längste Runde", "value" to v.round.durationSeconds, "unit" to "s", "player" to null, "round" to snapshot.roundItemOf(v)) },
            shortest?.let { v -> obj("id" to "shortest", "title" to "Kürzeste Runde", "value" to v.round.durationSeconds, "unit" to "s", "player" to null, "round" to snapshot.roundItemOf(v)) },
        )

        // ---------- Rollen & Maps ----------
        val roleCounts = HashMap<String, IntArray>()
        for (view in views) for (p in view.round.players) {
            val id = roleIdOf(p)
            if (id == "game:Crewmate" || id == "game:Impostor") continue
            roleCounts.getOrPut(id) { IntArray(2) }.let { it[0]++; if (p.won) it[1]++ }
        }
        val mapCounts = rounds.groupingBy { it.mapId }.eachCount()

        return obj(
            "months" to monthList,
            "month" to chosen.toString(),
            "label" to label(chosen),
            "totals" to obj(
                "rounds" to rounds.size,
                "previousRounds" to previous.size,
                "nights" to nights.size,
                "players" to playerKeys.size,
                "newcomers" to newcomers.size,
                "kills" to rounds.sumOf { it.kills.size },
                "meetings" to rounds.sumOf { it.meetings.size },
                "ejections" to rounds.sumOf { r -> r.meetings.count { it.exiled != null } },
                "playSeconds" to rounds.sumOf { it.durationSeconds },
            ),
            "teams" to teams,
            "bestNight" to bestNight?.let { obj("date" to it.key, "rounds" to it.value) },
            "roundOfMonth" to scored.firstOrNull()?.let { scoredJson(it) },
            "candidates" to scored.drop(1).take(4).map { scoredJson(it) },
            "awards" to awards,
            "moments" to moments,
            "newcomers" to newcomers.filter { !snapshot.isHidden(it) }.mapNotNull { snapshot.refOf(it) }.take(24),
            "roles" to roleCounts.entries.sortedByDescending { it.value[0] }.take(8).map { (id, v) -> obj("id" to id, "name" to roleName(id), "games" to v[0], "wins" to v[1]) },
            "maps" to mapCounts.entries.sortedByDescending { it.value }.map { (id, n) -> obj("mapId" to id, "name" to (MAP_NAMES[id] ?: "?"), "rounds" to n) },
        )
    }
}
