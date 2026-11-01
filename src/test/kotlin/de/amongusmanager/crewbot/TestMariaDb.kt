package de.amongusmanager.crewbot

import ch.vorburger.mariadb4j.DB
import ch.vorburger.mariadb4j.DBConfigurationBuilder
import de.amongusmanager.crewbot.db.Database
import java.util.concurrent.atomic.AtomicInteger

/** Ein echter MariaDB-Server nur für die Tests (startet einmal, jede Test-Datenbank ist frisch). */
object TestMariaDb {
    private val counter = AtomicInteger()

    private val server: DB by lazy {
        val config = DBConfigurationBuilder.newBuilder().setPort(0).build()
        DB.newEmbeddedDB(config).also { db ->
            db.start()
            Runtime.getRuntime().addShutdownHook(Thread { db.stop() })
        }
    }

    val port: Int get() = server.configuration.port

    fun newDatabase(): Database {
        val name = "crewbot_test_${counter.incrementAndGet()}"
        server.createDB(name)
        return Database.mysql("localhost:$port", name, "root", "")
    }

    /** Neue Test-Datenbank plus Nur-Lese-Verbindungen darauf (Stats-Website). */
    fun newDatabaseWithReader(): Pair<Database, () -> Database> {
        val name = "crewbot_test_${counter.incrementAndGet()}"
        server.createDB(name)
        return Database.mysql("localhost:$port", name, "root", "") to { Database.mysql("localhost:$port", name, "root", "", readOnly = true) }
    }

    /** Leere Datenbank (ohne Tabellen), nur lesend verbunden. */
    fun emptyReadOnly(): Database {
        val name = "crewbot_test_${counter.incrementAndGet()}"
        server.createDB(name)
        return Database.mysql("localhost:$port", name, "root", "", readOnly = true)
    }
}
