package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Startet das fertige JAR (build/libs/AmongUsManager.jar) mit MySQL-Zugangsdaten gegen einen echten
 * MariaDB-Server – mit Fake-Discord-Token, damit es nach dem Datenbank-Teil sauber abbricht.
 * Läuft nur mit `gradlew shadowJar test -DjarSmoke=true` (braucht das gebaute JAR).
 */
class JarSmokeTest {
    @Test
    fun jarStartsWithMysql() {
        assumeTrue(System.getProperty("jarSmoke") == "true", "nur mit -DjarSmoke=true")
        val jar = Path.of("build/libs/AmongUsManager.jar").toAbsolutePath()
        assumeTrue(Files.exists(jar), "JAR fehlt – vorher gradlew shadowJar")

        val db = TestMariaDb.newDatabase()
        val name = db.description.substringAfter("MySQL ").substringBefore(" auf")
        db.close()

        val workDir = Files.createTempDirectory("crewbot-jar")
        Files.createDirectories(workDir.resolve("data"))
        Files.copy(Golden.dir.resolve("import/levels.json"), workDir.resolve("data/levels.json"))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-jar", jar.toString())
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .also { builder ->
                val env = builder.environment()
                env["DISCORD_TOKEN"] = "MTAwMDAwMDAwMDAwMDAwMDAwMA.Gabcde.fakefakefakefakefakefakefakefakefake"
                env["DB_HOST"] = "localhost:${TestMariaDb.port}"
                env["DB_NAME"] = name
                env["DB_USER"] = "root"
                env["DB_PASSWORD"] = "egal" // der Test-Server prüft keine Passwörter
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS))
        println(output)
        assertTrue("Datenbank: MySQL $name" in output, "MySQL wird benutzt")
        assertTrue("Alte JSON-Daten übernommen: Spieler=" in output, "JSON-Import nach MySQL")
        assertTrue("DISCORD_TOKEN ist ungültig" in output, "bis zum Discord-Login gekommen")

        val check = Database.mysql("localhost:${TestMariaDb.port}", name, "root", "")
        val expected = Golden.section("pipeline")["import_extra"]!!.obj["state"]!!.obj["players"]!!.arr.size
        assertEquals(expected, check.queryOne("SELECT COUNT(*) AS n FROM players") { it.int("n") })
        check.close()
    }
}
