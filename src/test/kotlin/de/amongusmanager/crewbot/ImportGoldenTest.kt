package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.CATEGORIES
import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.db.JsonImport
import de.amongusmanager.crewbot.services.GameSettings
import de.amongusmanager.crewbot.services.buildRecap
import de.amongusmanager.crewbot.ui.buildRecapEmbed
import de.amongusmanager.crewbot.ui.views.buildSettingsEmbed
import java.nio.file.Files
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Die echten JSON-Dateien des alten Bots werden vollständig und korrekt in die Datenbank übernommen. */
class ImportGoldenTest {
    private val pipeline = Golden.section("pipeline")
    private val extra = pipeline["import_extra"]!!.obj
    private val users = pipeline["users"]!!.arr.map { it.long }
    private val importDir = Golden.dir.resolve("import")

    private fun imported(): TestServices {
        val db = Database.inMemory()
        assertNotNull(JsonImport.runIfNeeded(db, importDir), "Import muss laufen")
        return TestServices(db)
    }

    @Test
    fun importsEverything() {
        val s = imported()
        s.assertState(extra["state"]!!, "Nach dem Import")

        // Unbekannte Felder alter Versionen bleiben erhalten
        val extraJson = s.db.queryOne("SELECT extra FROM players WHERE user_id = ?", extra["levels_extra_user"]!!.long) {
            it.stringOrNull("extra")
        }
        assertEquals(extra["levels_extra"], parseJson(extraJson!!))

        // Rundenverlauf vollständig (Recap über alle Runden wie vor dem Import)
        assertEmbed(pipeline["recap_all"]!!, buildRecapEmbed(buildRecap(s.history.rounds()), "🌙 Alle Runden"), "Recap nach Import")

        // Webhooks und Einstellungen
        assertEquals(1499999999999999999L, s.modSettings.webhookId)
        assertEquals(1463920288214548684L, s.modSettings.channelId)
        assertEquals(1488888888888888888L, s.minecraft.webhookId)
        assertEquals(77L, s.minecraft.channelId)
        assertEquals(setOf(McEventType.JOIN, McEventType.DEATH), s.minecraft.enabled)
        assertEquals("https://discord.com/api/webhooks/1488888888888888888/abc", s.db.getSetting("minecraft.webhook_url"))

        val expectedSettings = GameSettings.parse(Golden.section("embeds")["settings"]!!.arr[0].obj["raw"]!!.str)
        val importedSettings = assertNotNull(s.gameSettings.latest)
        // Zeitpunkt wird auf Millisekunden genau gespeichert (assertEmbed erlaubt 1 ms Abweichung)
        for (category in CATEGORIES) {
            assertEmbed(
                normEmbed(buildSettingsEmbed(expectedSettings, category)),
                buildSettingsEmbed(importedSettings, category),
                "Rundeneinstellungen ${category.key}",
            )
        }
        // auch nach einem Neustart (aus der Datenbank gelesen statt aus dem Zwischenspeicher)
        val reloaded = assertNotNull(TestServices(s.db).gameSettings.latest)
        for (category in CATEGORIES) {
            assertEmbed(
                normEmbed(buildSettingsEmbed(expectedSettings, category)),
                buildSettingsEmbed(reloaded, category),
                "Rundeneinstellungen nach Neustart ${category.key}",
            )
        }
    }

    @Test
    fun importsEvents() {
        val s = imported()
        val events = s.events.all().associateBy { it.messageId }
        assertEquals(3, events.size)

        val first = events.getValue(1500000000000000001)
        assertEquals(OffsetDateTime.parse("2026-10-20T19:00+02:00").toInstant(), first.startsAt)
        assertEquals(OffsetDateTime.parse("2026-10-20T21:00+02:00").toInstant(), first.endsAt)
        assertEquals(1463920288214548684L, first.channelId)
        assertEquals(false, first.reminded)
        assertEquals(mapOf(users[0] to 20, users[1] to 23), first.attendance)
        assertEquals(true, first.attended(users[0]))

        val second = events.getValue(1500000000000000002)
        assertEquals(true, second.reminded)
        assertEquals(emptyMap(), second.attendance)

        // Altes Format ohne starts_at: Start = Ende - Eventdauer
        val old = events.getValue(1500000000000000003)
        assertEquals(OffsetDateTime.parse("2026-09-01T19:00+02:00").toInstant(), old.startsAt)
        assertEquals(1L, old.channelId)
        assertEquals(mapOf(users[2] to 16), old.attendance)
        assertEquals(listOf(old), s.events.due(Instant.parse("2026-10-01T00:00:00Z")))
    }

    @Test
    fun undoAfterImport() {
        val s = imported()
        s.modRounds.undo(extra["undo_game_id"]!!.str)
        s.assertState(extra["after_undo"]!!, "Zurücksetzen nach dem Import")
    }

    @Test
    fun importRunsOnlyOnce() {
        val db = Database.inMemory()
        assertNotNull(JsonImport.runIfNeeded(db, importDir))
        val players = db.queryOne("SELECT COUNT(*) AS n FROM players") { it.int("n") }
        assertNull(JsonImport.runIfNeeded(db, importDir), "Zweiter Start darf nicht erneut importieren")
        assertEquals(players, db.queryOne("SELECT COUNT(*) AS n FROM players") { it.int("n") })
    }

    @Test
    fun emptyDataFolderGivesEmptyDatabase() {
        val empty = Files.createTempDirectory("crewbot-empty")
        val db = Database.inMemory()
        assertEquals(emptyMap(), JsonImport.runIfNeeded(db, empty))
        assertNull(JsonImport.runIfNeeded(db, importDir), "Nach dem ersten Start wird nie mehr importiert")
        assertEquals(0, db.queryOne("SELECT COUNT(*) AS n FROM players") { it.int("n") })
    }
}
