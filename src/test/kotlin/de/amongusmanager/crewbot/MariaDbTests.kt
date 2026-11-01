package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.db.Dialect
import de.amongusmanager.crewbot.db.JsonImport
import de.amongusmanager.crewbot.db.SqliteToMysql
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Dieselben Tests wie mit SQLite – diesmal gegen einen echten MariaDB-Server (MySQL-Dialekt).

class MariaDbPipelineGoldenTest : PipelineGoldenTest() {
    override fun newDb(): Database = TestMariaDb.newDatabase()
}

class MariaDbImportGoldenTest : ImportGoldenTest() {
    override fun newDb(): Database = TestMariaDb.newDatabase()
}

class MariaDbServiceBehaviorTest : ServiceBehaviorTest() {
    override fun newDb(): Database = TestMariaDb.newDatabase()
}

class MariaDbRoleWishTest : RoleWishTest() {
    override fun newDb(): Database = TestMariaDb.newDatabase()
}

class MariaDbTheOtherRolesTest : TheOtherRolesTest() {
    override fun newDb(): Database = TestMariaDb.newDatabase()
}

class MariaDbRoundFormatV2Test : RoundFormatV2Test() {
    override fun newDb(): Database = TestMariaDb.newDatabase()
}

class MariaDbStatsWebsiteTest : StatsWebsiteTest() {
    override fun newDatabases(): Pair<Database, () -> Database> = TestMariaDb.newDatabaseWithReader()
    override fun emptyReadOnly(): Database = TestMariaDb.emptyReadOnly()
}

/** Umzug einer bestehenden SQLite-Datenbank nach MySQL. */
class SqliteToMysqlTest {
    private val pipeline = Golden.section("pipeline")
    private val extra = pipeline["import_extra"]!!.obj

    @Test
    fun copiesEverything() {
        val file = Files.createTempDirectory("crewbot-copy").resolve("crewbot.db")
        Database.open(file).use { sqlite -> JsonImport.runIfNeeded(sqlite, Golden.dir.resolve("import")) }

        val mysql = TestMariaDb.newDatabase()
        assertEquals(Dialect.MYSQL, mysql.dialect)
        assertNotNull(SqliteToMysql.copyIfNeeded(mysql, file), "Kopie muss laufen")
        assertNull(SqliteToMysql.copyIfNeeded(mysql, file), "Zweiter Start kopiert nicht erneut")
        assertNull(JsonImport.runIfNeeded(mysql, Golden.dir.resolve("import")), "JSON-Import nach der Kopie nicht mehr nötig")

        val s = TestServices(mysql)
        s.assertState(extra["state"]!!, "Nach der Kopie nach MySQL")
        assertEquals(1499999999999999999L, s.modSettings.webhookId)
        assertEquals(3, s.events.all().size)
        assertNotNull(s.gameSettings.latest)

        // Neue Zeilen bekommen danach weiter passende IDs (AUTO_INCREMENT hinter den kopierten)
        s.modRounds.undo(extra["undo_game_id"]!!.str)
        s.assertState(extra["after_undo"]!!, "Zurücksetzen nach der Kopie")
        s.levels.addXp(424242, 5)
        s.links.link("neu#1234", 424242)
        assertEquals("neu#1234", s.links().last().first)
    }

    @Test
    fun emptySqliteIsNotCopied() {
        val file = Files.createTempDirectory("crewbot-empty").resolve("crewbot.db")
        Database.open(file).close()
        val mysql = TestMariaDb.newDatabase()
        assertNull(SqliteToMysql.copyIfNeeded(mysql, file))
    }
}
