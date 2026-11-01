/*
 * Startpunkt der Stats-Website:  java -jar StatsWebsite.jar
 *
 * Läuft getrennt vom Bot (z.B. als eigener Server im Pelican-Panel) und liest dessen Datenbank nur.
 * Einstellungen in der .env im Arbeitsordner (echte Umgebungsvariablen haben Vorrang):
 *   DB_HOST, DB_NAME, DB_USER, DB_PASSWORD  – dieselbe MySQL/MariaDB-Datenbank wie der Bot
 *   (ohne DB_HOST: SQLite-Datei data/crewbot.db bzw. CREWBOT_DATA_DIR – nur, wenn die Website beim Bot liegt)
 *   WEB_PORT             – Port (Standard: SERVER_PORT des Panels, sonst 8080)
 *   WEB_ROUNDS_DIR       – statt der Datenbank einen Ordner mit Rundendateien der Mod lesen (zum Ausprobieren)
 *   WEB_REFRESH_SECONDS  – wie oft nach neuen Runden geschaut wird (Standard 60)
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.BotPaths
import de.amongusmanager.crewbot.Env
import de.amongusmanager.crewbot.db.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("StatsWebsite")

fun main() {
    Env.load(BotPaths.projectDir.resolve(".env"))
    val (source, db) = try {
        openSource()
    } catch (e: Exception) {
        log.error("❌ {}", e.message)
        exitProcess(1)
    }
    log.info("🗄️ Daten: {}", source.description)

    val store = StatsStore(source)
    try {
        store.refresh()
    } catch (e: Exception) {
        log.error("❌ Runden konnten nicht geladen werden (neuer Versuch in Kürze): {}", e.message, e)
    }

    val port = webPort()
    val server = try {
        StatsServer(store, port, Env.get("WEB_HOST").trim().ifEmpty { "0.0.0.0" })
    } catch (e: Exception) {
        log.error("❌ Port {} ist nicht frei: {}", port, e.message)
        exitProcess(1)
    }
    server.start()
    log.info("🌐 Stats-Website läuft auf Port {} – {} Runden", server.port, store.snapshot.roundCount)

    val refreshSeconds = Env.get("WEB_REFRESH_SECONDS").trim().toLongOrNull()?.coerceAtLeast(5) ?: 60
    val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "web-refresh").apply { isDaemon = true } }
    var lastFailure: String? = null
    scheduler.scheduleWithFixedDelay({
        try {
            store.refresh()
            if (lastFailure != null) log.info("Datenbank wieder erreichbar")
            lastFailure = null
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (message != lastFailure) log.warn("Aktualisieren fehlgeschlagen: {}", message)
            lastFailure = message
        }
    }, refreshSeconds, refreshSeconds, TimeUnit.SECONDS)

    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("Stats-Website wird beendet …")
        scheduler.shutdownNow()
        server.stop()
        db?.close()
    }, "web-shutdown"))
}

/** Port: WEB_PORT, sonst der Port des Panels (SERVER_PORT), sonst 8080. */
internal fun webPort(): Int =
    listOf("WEB_PORT", "SERVER_PORT").firstNotNullOfOrNull { Env.get(it).trim().toIntOrNull() } ?: 8080

private fun openSource(): Pair<StatsSource, Database?> {
    Env.get("WEB_ROUNDS_DIR").trim().takeIf { it.isNotEmpty() }?.let { dir ->
        val path = Path.of(dir)
        require(Files.isDirectory(path)) { "WEB_ROUNDS_DIR ist kein Ordner: $dir" }
        return DirectorySource(path) to null
    }
    val mysql = Env.mysql
    val db = if (mysql != null) {
        Database.mysql(mysql.host, mysql.name, mysql.user, mysql.password, readOnly = true)
    } else {
        require(Files.exists(BotPaths.database)) {
            "Keine Datenbank gefunden. Trage in die .env dieselben Zeilen DB_HOST, DB_NAME, DB_USER und DB_PASSWORD ein wie " +
                "beim Bot (MySQL des Panels) – oder starte die Website im Ordner des Bots (data/crewbot.db)."
        }
        Database.openReadOnly(BotPaths.database)
    }
    return DatabaseSource(db) to db
}
