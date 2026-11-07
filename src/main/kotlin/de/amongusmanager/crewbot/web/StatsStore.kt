/*
 * Stats-Website: hält die ausgewerteten Runden im Speicher und schaut regelmäßig nach neuen.
 * Neue Runden werden einzeln geladen und ausgewertet; ändert sich etwas (neue Runde, Zurücksetzen,
 * neue Verknüpfung, Level), entsteht ein neuer [StatsSnapshot].
 */
package de.amongusmanager.crewbot.web

import org.slf4j.LoggerFactory
import java.time.Instant

class StatsStore(
    private val source: StatsSource,
    /** Eigene Daten der Website (Anzeigenamen, Profilbilder, Profile), null = ohne. */
    private val accounts: WebAccounts? = null,
    private val clock: () -> Instant = Instant::now,
    /** Stimmen des Crack-Votings (fertige Votings zählen in Bestenliste/Profil), null = ohne. */
    private val crackVotes: CrackVotes? = null,
) {
    private val log = LoggerFactory.getLogger(StatsStore::class.java)

    private val archived = HashMap<String, WebRound>()
    /** Runden, die nicht zählen (Freeplay) oder kaputt sind – werden nicht jedes Mal neu geladen. */
    private val ignored = HashSet<String>()
    private var archiveIds: Set<String> = emptySet()
    private var history: List<WebRound> = emptyList()
    private var historySignature: String? = null
    private var links: Map<String, Long>? = null
    private var levels: Map<Long, BotLevel>? = null
    private var hidden: Set<String>? = null
    private var accountsVersion: Long = -1
    private var decorations: Map<Long, PlayerDecoration> = emptyMap()
    private var crack: Map<String, Map<Int, Int>> = emptyMap()

    @Volatile
    var snapshot: StatsSnapshot = StatsSnapshot.empty(clock())
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Wie [refresh], aber Fehler landen nur im Log (z.B. direkt nach dem Speichern eines Profils). */
    fun refreshQuietly() {
        try {
            refresh()
        } catch (e: Exception) {
            log.warn("Aktualisieren fehlgeschlagen: {}", e.message)
        }
    }

    /** Lädt neue/geänderte Daten. `true`, wenn es einen neuen Snapshot gibt. */
    @Synchronized
    fun refresh(): Boolean {
        try {
            val changed = load()
            lastError = null
            return changed
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    private fun load(): Boolean {
        var changed = false
        val entries = source.archived()
        archiveIds = entries.mapTo(HashSet()) { it.gameId }
        val active = entries.filter { !it.undone }.associateBy { it.gameId }
        if (archived.keys.retainAll(active.keys)) changed = true

        val missing = active.keys.filter { it !in archived && it !in ignored }
        for (chunk in missing.chunked(20)) {
            val json = source.archivedJson(chunk)
            for (gameId in chunk) {
                val text = json[gameId]
                if (text == null) {
                    ignored += gameId
                    continue
                }
                try {
                    val round = WebRounds.fromJson(text, active.getValue(gameId).receivedAt)
                    if (round == null) ignored += gameId else archived[gameId] = round
                } catch (e: Exception) {
                    ignored += gameId
                    log.warn("Runde {} nicht lesbar: {}", gameId, e.message)
                }
            }
            changed = true
        }

        val signature = source.historySignature()
        if (signature != historySignature) {
            history = source.history().map { WebRounds.fromHistory(it) }
            historySignature = signature
            changed = true
        }

        val newLinks = source.friendLinks()
        val newLevels = source.levels()
        if (newLinks != links || newLevels != levels) {
            links = newLinks
            levels = newLevels
            changed = true
        }

        val newHidden = source.hiddenPlayers()
        if (newHidden != hidden) {
            hidden = newHidden
            changed = true
        }

        if (accounts != null && accounts.version != accountsVersion) {
            accountsVersion = accounts.version
            decorations = accounts.decorations()
            changed = true
        }

        val rounds = archived.values + history.filter { it.gameId !in archiveIds }
        if (crackVotes != null) {
            // nur beendete Votings – laufende ändern sich mit jeder Stimme und stehen nur auf der Runden-Seite
            val now = clock()
            val byId = rounds.associateBy { it.gameId }
            val closed = crackVotes.tallies().filterKeys { id -> byId[id]?.let { CrackPoll.isClosed(it, now) } == true }
            if (closed != crack) {
                crack = closed
                changed = true
            }
        }

        if (changed) {
            snapshot = StatsSnapshot.build(rounds, links ?: emptyMap(), levels ?: emptyMap(), clock(), decorations, hidden ?: emptySet(), crack)
            log.info("Stats aktualisiert: {} Runden ({} mit Rohdaten), {} Spieler", rounds.size, archived.size, snapshotPlayers())
        }
        return changed
    }

    private fun snapshotPlayers(): Int = snapshot.views.flatMap { it.keys }.toSet().size

    // ---------- Detailansicht ----------

    private val detailCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 12
    }

    /** Eine Runde für die Detailansicht (Rohdaten ohne Friendcodes), `null` wenn es sie nicht gibt. */
    fun roundDetail(gameId: String): ByteArray? {
        val current = snapshot
        val view = current.view(gameId) ?: return null
        val cacheKey = "${current.version}:$gameId"
        synchronized(detailCache) { detailCache[cacheKey] }?.let { return it }
        val result = if (view.round.fromHistory) {
            RoundDetail.fromHistory(view, current)
        } else {
            val json = synchronized(this) { source.archivedJson(listOf(gameId))[gameId] } ?: return null
            RoundDetail.fromRaw(json, view, current)
        }
        synchronized(detailCache) { detailCache[cacheKey] = result }
        return result
    }
}
