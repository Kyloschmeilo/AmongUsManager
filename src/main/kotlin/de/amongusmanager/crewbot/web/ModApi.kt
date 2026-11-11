/*
 * Stats-Website: Spieler-Anfragen von CrewStats direkt beantworten (Auto-Join, eigene Stats, Verknüpfungs-Code).
 *
 *   POST /api/mod/<Schlüssel>   Body = JSON wie bisher im Webhook-Text, Antwort = die passende *-reply-JSON
 *
 * Statt Nachricht posten → warten, bis der Bot sie bearbeitet → lesen → löschen (Discord-Webhook) ist es hier eine
 * einzige Anfrage. Lobby und Stats liest die Website selbst aus der Bot-Datenbank, nur den Verknüpfungs-Code erzeugt
 * der Bot (er löst ihn bei /verknuepfen-code ein) – dafür ein Auftrag über web_requests. Ablauf + Format: services/ModApi.kt.
 * Wie bei den Webhook-Antworten: keine Discord-Namen oder -IDs, nur „verknüpft ja/nein“ und Zahlen.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.AutoJoinProtocol
import de.amongusmanager.crewbot.services.GameStatsService
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.LobbyStore
import de.amongusmanager.crewbot.services.ModApiSettings
import de.amongusmanager.crewbot.services.PlayerInfoProtocol
import de.amongusmanager.crewbot.services.PlayerInfoRequest
import de.amongusmanager.crewbot.services.PlayerInfoService
import de.amongusmanager.crewbot.services.RoundHistoryService
import de.amongusmanager.crewbot.ui.views.playerRoleLabel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ModApi(
    db: Database,
    private val link: BotLink,
    /** „Beitreten“ auf der Website (Live-Lobbys) – die nächste Auto-Join-Frage dieser Person bekommt genau diese Lobby. */
    private val picks: JoinPicks? = null,
    /** Proximity-Voice: Positionen vom Host (CrewStats ≥ 1.10.0), eigenes Mengenlimit (~5 Meldungen/s). */
    private val voice: VoiceChat? = null,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(ModApi::class.java)
    private val settings = ModApiSettings(db)
    private val lobby = LobbyStore(db)
    private val links = LinkService(db)
    private val playerInfo = PlayerInfoService(
        db, links, LevelService(db), GameStatsService(db), RoundHistoryService(db, clock), ::playerRoleLabel, clock,
    )
    private val calls = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    @Volatile
    private var cachedKey: Pair<String?, Instant>? = null

    @Volatile
    private var lastBeat: Instant = Instant.EPOCH

    fun handles(path: String): Boolean = path.startsWith(ModApiSettings.PATH)

    /** Lebenszeichen + Adresse für /autojoin (aus dem Aktualisierungs-Takt der Website). */
    fun heartbeat(publicUrl: String?) {
        if (publicUrl == null) return
        val now = clock()
        if (Duration.between(lastBeat, now) < ModApiSettings.WEBSITE_HEARTBEAT.minusSeconds(5)) return
        settings.websiteAlive(publicUrl, now)
        settings.key() // gleich anlegen, damit /autojoin sofort die Website-URL zeigen kann
        lastBeat = now
    }

    fun handle(request: WebRequest, client: String): WebResponse {
        val key = request.path.removePrefix(ModApiSettings.PATH).trim('/')
        if (key.isEmpty() || !ModApiSettings.sameKey(currentKey(), key)) return json(404, """{"error":"unknown"}""")
        if (request.method != "POST") return WebResponse(405, body = """{"error":"post"}""".toByteArray(), headers = listOf("Allow" to "POST", NO_STORE))
        if (request.body.size > MAX_BODY) return json(413, """{"error":"size"}""")
        val text = String(request.body, Charsets.UTF_8)
        if (voice != null && VoiceReport.isReport(text)) return json(200, voice.report(text, client))
        if (!allow(client)) return json(429, """{"error":"busy"}""")

        if (AutoJoinProtocol.isRequest(text)) return json(200, joinReply(text))
        return when (val info = PlayerInfoProtocol.parse(text)) {
            is PlayerInfoRequest.Stats -> json(200, playerInfo.statsReply(info))
            is PlayerInfoRequest.Link ->
                if (info.friendCode.isEmpty()) json(200, PlayerInfoProtocol.linkReply(null, linked = false, error = "friendcode"))
                else linkCode(info.friendCode)
            null -> json(400, """{"error":"request"}""")
        }
    }

    /** Neueste Lobby – oder die, bei der die Person auf der Website „Beitreten“ gedrückt hat. */
    private fun joinReply(text: String): String {
        val now = clock()
        val pick = picks?.take(AutoJoinProtocol.friendCodeOf(text))
        if (pick != null) {
            val chosen = lobby.find(pick.code, now)
            if (chosen != null) {
                log.info("CrewStats: Beitreten zu {} von der Website an die Mod gegeben", pick.code)
                return AutoJoinProtocol.reply(chosen, now, pick = pick.id)
            }
        }
        return AutoJoinProtocol.reply(lobby.current(now), now)
    }

    private fun linkCode(friendCode: String): WebResponse {
        val reply = link.call(GUEST, MOD_LINK, obj("fc" to friendCode))
        val kind = (reply.body["crewstats"] as? JsonPrimitive)?.contentOrNull
        if (kind == PlayerInfoProtocol.LINK_REPLY) return json(200, reply.body.toString())
        log.info("CrewStats: Verknüpfungs-Code nicht erzeugt ({})", (reply.body["error"] as? JsonPrimitive)?.contentOrNull)
        return json(if (reply.status >= 500) reply.status else 503, """{"error":"bot-offline"}""")
    }

    /** Schlüssel höchstens alle 30 s neu lesen (/autojoin neu:True wirkt also nach spätestens 30 s). */
    private fun currentKey(): String? {
        val now = clock()
        cachedKey?.let { (key, at) -> if (at.plus(KEY_CACHE).isAfter(now)) return key }
        val key = settings.currentKey()
        cachedKey = key to now
        return key
    }

    /** Höchstens [RATE] Anfragen pro Minute und Adresse (Auto-Join fragt alle ~15 s). */
    private fun allow(client: String): Boolean {
        val now = clock()
        if (calls.size > 5000) calls.clear()
        val window = calls.getOrPut(client) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first().plus(Duration.ofMinutes(1)).isBefore(now)) window.removeFirst()
            if (window.size >= RATE) return false
            window.addLast(now)
            return true
        }
    }

    private fun json(status: Int, body: String) = WebResponse(status, body = body.toByteArray(Charsets.UTF_8), headers = listOf(NO_STORE))

    companion object {
        /** Wie WebControlModule.GUEST / MOD_LINK (ohne Abhängigkeit vom Bot-Modul). */
        private const val GUEST = 0L
        private const val MOD_LINK = "mod.link"
        private const val MAX_BODY = 8 * 1024 // Voice-Meldung mit 15 Spielern ~2 KB
        private const val RATE = 40
        private val KEY_CACHE: Duration = Duration.ofSeconds(30)
        private val NO_STORE = "Cache-Control" to "no-store"
    }
}
