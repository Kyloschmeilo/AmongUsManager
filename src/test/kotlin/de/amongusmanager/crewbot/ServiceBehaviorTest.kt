package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.BetError
import de.amongusmanager.crewbot.services.BetService
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LobbyAnnouncer
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundError
import de.amongusmanager.crewbot.services.PendingRecap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.CoroutineDispatcher
import net.dv8tion.jda.api.EmbedBuilder
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verhalten, das nicht aus dem Python-Vergleich kommt: Transaktionen, Wettsperre, Events, Datenbankdatei. */
open class ServiceBehaviorTest {
    /** Datenbank für den Test (Standard SQLite; Unterklasse für MariaDB). */
    open fun newDb(): Database = Database.inMemory()

    private val roundJson = Golden.section("pipeline")["ops"]!!.arr.first { it.obj["op"]!!.str == "round" }.obj["json"]!!.str

    @Test
    fun failedRoundLeavesNoTrace() {
        val s = TestServices(newDb())
        for ((code, user) in listOf("spieler0#1000" to 1L, "spieler1#1001" to 2L, "spieler2#1002" to 3L)) s.links.link(code, user)
        s.levels.addXp(1, 40)
        val before = s.players()
        val round = ModRound.parse(roundJson)

        // Mitten in der Auswertung geht etwas schief -> alles zurück (auch der Zwischenspeicher des Verlaufs)
        assertFailsWith<IllegalStateException> {
            s.db.transaction {
                s.modRounds.applyWithoutRoles(round)
                assertEquals(1, s.history.rounds().size)
                error("Absturz")
            }
        }
        assertEquals(before, s.players())
        assertEquals(0, s.history.rounds().size)
        assertFalse(s.modRounds.isKnown(round.gameId))
        assertEquals(emptyMap(), s.achievementIds())

        // danach klappt die Auswertung normal – und nur einmal
        s.modRounds.applyWithoutRoles(round)
        assertEquals(1, s.history.rounds().size)
        assertFailsWith<ModRoundError> { s.modRounds.applyWithoutRoles(round) }
    }

    @Test
    fun undoRestoresEverything() {
        val s = TestServices(newDb())
        val round = ModRound.parse(roundJson)
        for (player in round.players) s.links.link(player.friendCode, 1000L + player.playerId)
        s.levels.addXp(1000, 45) // knapp vor Level 2 – der Sieg + Erfolge heben das Level
        s.bets.place(1000, Team.CREW, 5)
        val before = s.players()
        val betsBefore = s.bets.active

        s.modRounds.applyWithoutRoles(round)
        s.modRounds.undo(round.gameId)

        assertEquals(levelBefore(before, 1000), s.levels.get(1000).level)
        assertEquals(before, s.players().filter { p -> before.any { it[0] == p[0] } })
        assertTrue(s.players().all { p -> before.any { it[0] == p[0] } || p[1] == 0L }, "Neue Spieler haben wieder 0 XP")
        assertEquals(betsBefore.map { it.userId to it.amount }, s.bets.active.map { it.userId to it.amount })
        assertEquals(emptyMap(), s.achievementIds())
        assertEquals(0, s.history.rounds().size)
        assertTrue(s.allStatUsers().all { s.statsOf(it).games == 0 })
    }

    private fun levelBefore(players: List<List<Long>>, userId: Long): Int = players.first { it[0] == userId }[2].toInt()

    @Test
    fun betLockFollowsLobby() {
        var now = Instant.parse("2026-10-10T20:00:00Z")
        val db = newDb()
        val levels = LevelService(db)
        val bets = BetService(db, levels) { now }
        levels.addXp(7, 100)
        val announcer = LobbyAnnouncer(
            { error("kein Discord im Test") }, 1, de.amongusmanager.crewbot.services.LinkService(db), bets,
            { _, _ -> EmbedBuilder().setDescription("x").build() }, CoroutineScope(Job() + NeverDispatcher),
        )

        announcer.apply(LobbyEvent("open", "ABC", "Polus", 3, 10, "Host", ""))
        assertFalse(bets.locked)
        announcer.apply(LobbyEvent("started", "ABC", "Polus", 3, 10, "Host", ""))
        assertTrue(bets.locked)
        assertFailsWith<BetError> { bets.place(7, Team.CREW, 5) }

        // Rundenende ohne Ergebnis: nach 30 s wieder offen
        announcer.apply(LobbyEvent("open", "ABC", "Polus", 3, 10, "Host", ""))
        assertTrue(bets.locked)
        now += Duration.ofSeconds(31)
        assertFalse(bets.locked)

        // Sicherheitsnetz: spätestens nach 45 Minuten offen
        announcer.apply(LobbyEvent("started", "ABC", "Polus", 3, 10, "Host", ""))
        now += Duration.ofMinutes(44)
        assertTrue(bets.locked)
        now += Duration.ofMinutes(2)
        assertFalse(bets.locked)

        // Auswertung öffnet sofort
        announcer.apply(LobbyEvent("started", "ABC", "Polus", 3, 10, "Host", ""))
        assertTrue(bets.locked)
        bets.settle()
        assertFalse(bets.locked)
        bets.place(7, Team.CREW, 5)
        assertEquals(95, levels.get(7).xp)
    }

    @Test
    fun eventLifecycle() {
        val s = TestServices(newDb())
        val start = Instant.parse("2026-10-20T17:00:00Z")
        val end = start + Duration.ofHours(2)
        s.events.add(1, 10, start, end, start - Duration.ofHours(1))
        assertEquals(emptyList(), s.events.needingReminder(start - Duration.ofMinutes(16)).map { it.messageId })
        assertEquals(listOf(1L), s.events.needingReminder(start - Duration.ofMinutes(15)).map { it.messageId })
        s.events.markReminded(1)
        assertEquals(emptyList(), s.events.needingReminder(start - Duration.ofMinutes(10)).map { it.messageId })

        // kurzfristig erstellt: keine Erinnerung nötig
        assertTrue(s.events.add(2, 10, start, end, start - Duration.ofMinutes(5)).reminded)

        assertEquals(emptyList(), s.events.running(start - Duration.ofSeconds(1)).map { it.messageId })
        assertEquals(listOf(1L, 2L), s.events.running(start).map { it.messageId })
        repeat(15) { s.events.recordAttendance(1, listOf(100L, 200L)) }
        s.events.recordAttendance(1, listOf(100L))
        s.events.recordAttendance(99, listOf(100L)) // unbekanntes Event: nichts passiert
        val event = s.events.get(1)!!
        assertEquals(mapOf(100L to 16, 200L to 15), event.attendance)
        assertTrue(event.attended(200))
        assertFalse(event.attended(300))

        assertEquals(emptyList(), s.events.due(end - Duration.ofSeconds(1)).map { it.messageId })
        assertEquals(listOf(1L, 2L), s.events.due(end).map { it.messageId })
        s.events.remove(1)
        assertEquals(listOf(2L), s.events.all().map { it.messageId })
        assertEquals(0, s.db.queryOne("SELECT COUNT(*) AS n FROM event_attendance") { it.int("n") })
    }

    @Test
    fun pendingRecapsOutliveTheEvent() {
        val s = TestServices(newDb())
        val start = Instant.parse("2026-10-20T17:00:00Z")
        val end = start + Duration.ofHours(2)

        // Event von vor den Recaps (noch nichts gespeichert): bekommt seinen Recap trotzdem
        s.db.update(
            "INSERT INTO events (message_id, channel_id, starts_at, ends_at, reminded) VALUES (?, ?, ?, ?, ?)",
            1L, 10L, start, end, true,
        )
        assertEquals(listOf(PendingRecap(1, 10, start)), s.events.pendingRecaps())
        assertEquals(start + Duration.ofHours(Rules.RECAP_AFTER_HOURS), s.events.pendingRecaps()[0].dueAt)

        // Abschluss des Events (Anwesenheits-XP) lässt den Recap offen
        s.events.remove(1)
        assertEquals(listOf(PendingRecap(1, 10, start)), s.events.pendingRecaps())

        val later = start + Duration.ofHours(1)
        s.events.add(2, 20, later, end, start)
        s.events.add(2, 20, later, end, start) // nochmal: kein doppelter Recap
        assertEquals(listOf(1L, 2L), s.events.pendingRecaps().map { it.messageId })

        s.events.removePendingRecap(1)
        assertEquals(listOf(PendingRecap(2, 20, later)), s.events.pendingRecaps())
        s.events.removePendingRecap(2)
        assertEquals(emptyList(), s.events.pendingRecaps()) // Event 2 ist noch offen, sein Recap aber erledigt
    }

    @Test
    fun databaseFileSurvivesRestart() {
        val folder = Files.createTempDirectory("crewbot-db")
        val file = folder.resolve("data").resolve("crewbot.db")
        Database.open(file).use { db ->
            TestServices(db).levels.addXp(42, 77, participation = true)
            db.setSetting("mod.webhook_id", "123")
        }
        Database.open(file).use { db ->
            val s = TestServices(db)
            assertEquals(77, s.levels.get(42).xp)
            assertEquals(2, s.levels.get(42).level)
            assertEquals(1, s.levels.get(42).participations)
            assertEquals(123L, s.modSettings.webhookId)
        }
    }

    /** Führt nie etwas aus – für Jobs, die im Test keine Rolle spielen (Discord-Nachrichten). */
    private object NeverDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    }
}
