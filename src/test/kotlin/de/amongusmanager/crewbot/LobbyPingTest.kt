package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.LobbyNotifier
import de.amongusmanager.crewbot.services.LobbyPing
import de.amongusmanager.crewbot.services.LobbyPingMode
import de.amongusmanager.crewbot.services.LobbyPingStore
import de.amongusmanager.crewbot.services.LobbyPingSub
import de.amongusmanager.crewbot.services.LobbyPlayer
import de.amongusmanager.crewbot.ui.buildLobbyPingEmbed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /lobby-benachrichtigung: wer bekommt bei welcher Lobby-Meldung eine DM? */
class LobbyPingTest {
    private val db = Database.inMemory()
    private val links = LinkService(db)
    private val store = LobbyPingStore(db)
    private val notifier = LobbyNotifier({ error("kein Discord im Test") }, store, links, { _, _ -> error("nicht senden") }, CoroutineScope(Dispatchers.Unconfined))

    private fun lobby(code: String = "ABCDEF", state: String = "open", players: Int = 3, max: Int = 15, list: List<String> = emptyList()) =
        LobbyEvent(state, code, "The Skeld", players, max, "Host", "host#1111", list = list.map { LobbyPlayer(it, it, 0) })

    private fun pings(event: LobbyEvent, now: Instant = Instant.parse("2026-11-21T20:00:00Z")) =
        notifier.onLobby(event, now, send = false).map { it.userId to it.friends }

    init {
        links.link("host#1111", 1)
        links.link("anna#2222", 2)
        links.link("ben#3333", 3)
    }

    @Test
    fun storesSettingsAndKeepsFriendsWhenOff() {
        store.update(10) { LobbyPingSub(LobbyPingMode.FRIENDS, listOf(1, 2)) }
        store.update(11) { LobbyPingSub(LobbyPingMode.ALL) }
        assertEquals(LobbyPingSub(LobbyPingMode.FRIENDS, listOf(1L, 2L)), store.of(10))
        store.update(10) { it.copy(mode = LobbyPingMode.OFF) }
        assertEquals(listOf(1L, 2L), store.of(10).friends)
        store.update(11) { LobbyPingSub() } // aus ohne Freunde → Eintrag weg
        assertEquals(setOf(10L), store.all().keys)
        assertEquals(store.all(), LobbyPingStore.parse(LobbyPingStore.serialize(store.all())))
        assertEquals(emptyMap(), LobbyPingStore.parse("kaputt"))
    }

    @Test
    fun allModeGetsEveryNewLobbyOnce() {
        store.update(10) { LobbyPingSub(LobbyPingMode.ALL) }
        val start = Instant.parse("2026-11-21T20:00:00Z")
        assertEquals(listOf(10L to emptyList<Long>()), pings(lobby(), start))
        assertEquals(emptyList(), pings(lobby(players = 5), start + Duration.ofMinutes(1)), "gleiche Lobby nur einmal")
        assertEquals(emptyList(), pings(lobby("GHIJKL"), start + Duration.ofMinutes(2)), "kurz danach keine zweite DM")
        assertEquals(listOf(10L to emptyList<Long>()), pings(lobby("MNOPQR"), start + Duration.ofMinutes(10)))
    }

    @Test
    fun friendsModeOnlyWhenAFriendHostsOrIsInside() {
        store.update(10) { LobbyPingSub(LobbyPingMode.FRIENDS, listOf(3, 1)) }
        store.update(20) { LobbyPingSub(LobbyPingMode.FRIENDS, listOf(3)) }
        assertEquals(listOf(10L to listOf(1L)), pings(lobby()), "Host ist Freund von 10")
        assertEquals(listOf(10L to listOf(1L, 3L), 20L to listOf(3L)), pings(lobby("ZZZZZZ", list = listOf("ben#3333")), Instant.parse("2026-11-21T21:00:00Z")))
    }

    @Test
    fun noPingForOwnLobbyFullStartedOrOff() {
        store.update(1) { LobbyPingSub(LobbyPingMode.ALL) } // Host selbst
        store.update(2) { LobbyPingSub(LobbyPingMode.ALL) } // schon in der Lobby
        store.update(3) { LobbyPingSub(LobbyPingMode.OFF, listOf(1)) }
        assertEquals(emptyList(), pings(lobby(list = listOf("anna#2222"))))
        store.update(30) { LobbyPingSub(LobbyPingMode.ALL) }
        assertEquals(emptyList(), pings(lobby("FULLLL", players = 15)))
        assertEquals(emptyList(), pings(lobby("STARTD", state = "started")))
        assertEquals(emptyList(), pings(lobby("CLOSED", state = "closed")))
    }

    @Test
    fun embedNamesFriendsAndWebsite() {
        val embed = buildLobbyPingEmbed(lobby(), LobbyPing(10, listOf(1, 2)), "<@1>", "https://spieleabend.example/")
        assertTrue(embed.title!!.contains("2 deiner Freunde"))
        assertTrue(embed.description!!.contains("`ABCDEF`"))
        assertTrue(embed.description!!.contains("https://spieleabend.example/amongus/lobbys"))
        assertTrue(embed.description!!.contains("<@1>, <@2>"))
    }
}
