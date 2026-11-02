package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.AutoJoinProtocol
import de.amongusmanager.crewbot.services.AutoJoinSettings
import de.amongusmanager.crewbot.services.BetService
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.LobbyAnnouncer
import de.amongusmanager.crewbot.services.LobbyEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dv8tion.jda.api.EmbedBuilder
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Auto-Join: neueste Lobby für die Mod (/autojoin), Protokoll ↔ CrewStats AutoJoin.cs. */
class AutoJoinTest {
    private object NeverDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    }

    private fun announcer(db: Database = Database.inMemory()): LobbyAnnouncer {
        val levels = LevelService(db)
        return LobbyAnnouncer(
            { error("kein Discord im Test") }, 1, LinkService(db), BetService(db, levels),
            { _, _ -> EmbedBuilder().setDescription("x").build() }, CoroutineScope(Job() + NeverDispatcher),
        )
    }

    private fun lobby(state: String, code: String = "ABCDEF") = LobbyEvent(state, code, "Polus", 4, 10, "Kilian", "kilian#1234")

    @Test
    fun neuesteLobbyFolgtDerMod() {
        val announcer = announcer()
        assertNull(announcer.current())
        announcer.apply(lobby("open"))
        assertEquals("ABCDEF", announcer.current()!!.first.code)
        announcer.apply(lobby("started"))
        assertEquals("started", announcer.current()!!.first.state)
        announcer.apply(lobby("open", "GHIJKL")) // neue Lobby ersetzt die alte
        assertEquals("GHIJKL", announcer.current()!!.first.code)
        announcer.apply(lobby("closed", "GHIJKL"))
        assertNull(announcer.current())
    }

    @Test
    fun alteLobbyVerfaellt() {
        val announcer = announcer()
        announcer.apply(lobby("open"))
        assertTrue(announcer.current(Instant.now() + Duration.ofMinutes(59)) != null)
        assertNull(announcer.current(Instant.now() + Duration.ofMinutes(61)))
    }

    @Test
    fun protokoll() {
        assertTrue(AutoJoinProtocol.isRequest("""{"crewstats":"join","v":1}"""))
        assertFalse(AutoJoinProtocol.isRequest("""{"crewstats":"lobby","state":"open"}"""))
        assertFalse(AutoJoinProtocol.isRequest("kein json"))

        val now = Instant.parse("2026-11-02T20:00:00Z")
        val reply = Json.parseToJsonElement(AutoJoinProtocol.reply(lobby("open") to now.minusSeconds(42), now)).jsonObject
        assertEquals("join-reply", reply["crewstats"]!!.jsonPrimitive.content)
        assertEquals("open", reply["state"]!!.jsonPrimitive.content)
        assertEquals("ABCDEF", reply["code"]!!.jsonPrimitive.content)
        assertEquals("Polus", reply["map"]!!.jsonPrimitive.content)
        assertEquals(4, reply["players"]!!.jsonPrimitive.content.toInt())
        assertEquals(10, reply["max"]!!.jsonPrimitive.content.toInt())
        assertEquals(42, reply["age"]!!.jsonPrimitive.content.toInt())
        assertFalse("sender" in reply) // Friendcode des Hosts geht nicht an alle

        assertEquals("""{"crewstats":"join-reply","v":1,"state":"none"}""", AutoJoinProtocol.reply(null))
    }

    @Test
    fun webhookWirdGespeichert() {
        val settings = AutoJoinSettings(Database.inMemory())
        assertNull(settings.webhookId)
        settings.set(123456789012345678)
        assertEquals(123456789012345678, settings.webhookId)
    }
}
