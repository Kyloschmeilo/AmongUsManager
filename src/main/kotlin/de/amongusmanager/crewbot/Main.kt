/* Startpunkt des Among Us Managers.  Starten mit:  java -jar AmongUsManager.jar */
package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.BotThread
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.db.JsonImport
import de.amongusmanager.crewbot.db.SqliteToMysql
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("Main")

fun main() {
    Env.load(BotPaths.projectDir.resolve(".env"))
    val token = Env.discordToken
    if (token.isEmpty()) {
        log.error("❌ Kein DISCORD_TOKEN gefunden. Lege eine .env-Datei an (Vorlage: .env.example).")
        exitProcess(1)
    }

    val db = try {
        openDatabase()
    } catch (e: Exception) {
        log.error("❌ Datenbank konnte nicht geöffnet bzw. befüllt werden: {}", e.message, e)
        exitProcess(1)
    }

    val bot = CrewBot(db)
    Runtime.getRuntime().addShutdownHook(Thread({ shutdown(bot, db) }, "crewbot-shutdown"))
    try {
        bot.start(token)
    } catch (e: InvalidTokenException) {
        log.error("❌ Der DISCORD_TOKEN ist ungültig.")
        exitProcess(1)
    } catch (e: Exception) {
        log.error("❌ Bot konnte nicht starten", e)
        exitProcess(1)
    }
}

/**
 * MySQL, wenn in der .env DB_HOST usw. stehen, sonst die SQLite-Datei data/crewbot.db.
 * Beim ersten Start werden vorhandene Daten übernommen (SQLite-Datei bzw. alte JSON-Dateien).
 */
private fun openDatabase(): Database {
    val mysql = Env.mysql
    val db = if (mysql != null) {
        Database.mysql(mysql.host, mysql.name, mysql.user, mysql.password)
    } else {
        Database.open(BotPaths.database)
    }
    log.info("🗄️ Datenbank: {}", db.description)
    try {
        SqliteToMysql.copyIfNeeded(db, BotPaths.database)
        JsonImport.runIfNeeded(db, BotPaths.dataDir)
    } catch (e: Exception) {
        db.close()
        throw e
    }
    return db
}

private fun shutdown(bot: CrewBot, db: Database) {
    log.info("Bot wird beendet …")
    try {
        bot.shutdown()
    } catch (e: Exception) {
        log.warn("Fehler beim Trennen von Discord", e)
    }
    BotThread.shutdown()
    try {
        BotThread.awaitIdle(5, TimeUnit.SECONDS)
    } finally {
        db.close()
    }
}
