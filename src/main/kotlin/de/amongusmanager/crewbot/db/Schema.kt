/*
 * Tabellen der Datenbank. Zeitpunkte werden als Millisekunden seit 1970 (UTC) gespeichert.
 *
 * Neue Spalten/Tabellen: in BEIDEN Listen (SQLite und MySQL) eine weitere Version anhängen (nie alte
 * Versionen ändern – die Datenbank auf dem Server ist schon auf dem alten Stand).
 */
package de.amongusmanager.crewbot.db

object Schema {
    private val SQLITE_MIGRATIONS: List<String> = listOf(
        // Version 1: alles, was früher in den JSON-Dateien stand
        """
        CREATE TABLE settings (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE players (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id        INTEGER NOT NULL UNIQUE,
            xp             INTEGER NOT NULL DEFAULT 0,
            level          INTEGER NOT NULL DEFAULT 1,
            participations INTEGER NOT NULL DEFAULT 0,
            wins           INTEGER NOT NULL DEFAULT 0,
            extra          TEXT
        );

        CREATE TABLE bets (
            id      INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL UNIQUE,
            team    TEXT NOT NULL CHECK (team IN ('CREW', 'IMPOSTOR')),
            amount  INTEGER NOT NULL
        );

        CREATE TABLE events (
            message_id INTEGER PRIMARY KEY,
            channel_id INTEGER NOT NULL,
            starts_at  INTEGER NOT NULL,
            ends_at    INTEGER NOT NULL,
            reminded   INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE event_attendance (
            message_id INTEGER NOT NULL REFERENCES events (message_id) ON DELETE CASCADE,
            user_id    INTEGER NOT NULL,
            minutes    INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (message_id, user_id)
        );

        CREATE TABLE friend_links (
            friend_code TEXT PRIMARY KEY,
            user_id     INTEGER NOT NULL
        );
        CREATE INDEX friend_links_user ON friend_links (user_id);

        CREATE TABLE game_stats (
            user_id         INTEGER PRIMARY KEY,
            games           INTEGER NOT NULL DEFAULT 0,
            crew_games      INTEGER NOT NULL DEFAULT 0,
            crew_wins       INTEGER NOT NULL DEFAULT 0,
            impostor_games  INTEGER NOT NULL DEFAULT 0,
            impostor_wins   INTEGER NOT NULL DEFAULT 0,
            kills           INTEGER NOT NULL DEFAULT 0,
            deaths          INTEGER NOT NULL DEFAULT 0,
            ejected         INTEGER NOT NULL DEFAULT 0,
            survived        INTEGER NOT NULL DEFAULT 0,
            tasks_completed INTEGER NOT NULL DEFAULT 0,
            meetings_called INTEGER NOT NULL DEFAULT 0,
            bodies_reported INTEGER NOT NULL DEFAULT 0,
            votes_correct   INTEGER NOT NULL DEFAULT 0,
            votes_wrong     INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE mod_rounds (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            game_id    TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL,
            undone     INTEGER NOT NULL DEFAULT 0,
            channel_id INTEGER,
            message_id INTEGER
        );

        CREATE TABLE mod_round_awards (
            game_id   TEXT NOT NULL REFERENCES mod_rounds (game_id) ON DELETE CASCADE,
            seq       INTEGER NOT NULL,
            user_id   INTEGER NOT NULL,
            xp        INTEGER NOT NULL,
            win       INTEGER NOT NULL,
            old_level INTEGER NOT NULL,
            new_level INTEGER NOT NULL,
            PRIMARY KEY (game_id, seq)
        );

        CREATE TABLE mod_round_bets (
            game_id TEXT NOT NULL REFERENCES mod_rounds (game_id) ON DELETE CASCADE,
            seq     INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            team    TEXT NOT NULL,
            amount  INTEGER NOT NULL,
            PRIMARY KEY (game_id, seq)
        );

        CREATE TABLE mod_round_stats (
            game_id TEXT NOT NULL REFERENCES mod_rounds (game_id) ON DELETE CASCADE,
            user_id INTEGER NOT NULL,
            stat    TEXT NOT NULL,
            delta   INTEGER NOT NULL,
            PRIMARY KEY (game_id, user_id, stat)
        );

        CREATE TABLE mod_round_achievements (
            game_id        TEXT NOT NULL REFERENCES mod_rounds (game_id) ON DELETE CASCADE,
            seq            INTEGER NOT NULL,
            user_id        INTEGER NOT NULL,
            achievement_id TEXT NOT NULL,
            xp             INTEGER NOT NULL,
            old_level      INTEGER NOT NULL,
            new_level      INTEGER NOT NULL,
            PRIMARY KEY (game_id, seq)
        );

        CREATE TABLE round_history (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            game_id          TEXT NOT NULL UNIQUE,
            ended_at         INTEGER NOT NULL,
            lobby_code       TEXT NOT NULL,
            map              TEXT NOT NULL,
            duration_seconds REAL NOT NULL,
            end_reason       TEXT NOT NULL,
            winning_team     TEXT
        );
        CREATE INDEX round_history_ended ON round_history (ended_at);

        CREATE TABLE round_history_players (
            round_id        INTEGER NOT NULL REFERENCES round_history (id) ON DELETE CASCADE,
            seq             INTEGER NOT NULL,
            player_id       INTEGER NOT NULL,
            discord_id      INTEGER,
            name            TEXT NOT NULL,
            friend_code     TEXT NOT NULL,
            role            TEXT NOT NULL,
            team            TEXT NOT NULL,
            won             INTEGER NOT NULL,
            is_dead         INTEGER NOT NULL,
            disconnected    INTEGER NOT NULL,
            death_cause     TEXT,
            killed_by       INTEGER,
            died_at         REAL,
            kills           INTEGER NOT NULL,
            tasks_total     INTEGER NOT NULL,
            tasks_completed INTEGER NOT NULL,
            PRIMARY KEY (round_id, seq)
        );
        CREATE INDEX round_history_players_discord ON round_history_players (discord_id);

        CREATE TABLE round_history_kills (
            round_id   INTEGER NOT NULL REFERENCES round_history (id) ON DELETE CASCADE,
            seq        INTEGER NOT NULL,
            at_seconds REAL NOT NULL,
            killer     INTEGER NOT NULL,
            victim     INTEGER NOT NULL,
            PRIMARY KEY (round_id, seq)
        );

        CREATE TABLE round_history_meetings (
            round_id   INTEGER NOT NULL REFERENCES round_history (id) ON DELETE CASCADE,
            seq        INTEGER NOT NULL,
            at_seconds REAL NOT NULL,
            caller     INTEGER NOT NULL,
            body       INTEGER,
            exiled     INTEGER,
            tie        INTEGER NOT NULL,
            PRIMARY KEY (round_id, seq)
        );

        CREATE TABLE round_history_votes (
            round_id    INTEGER NOT NULL REFERENCES round_history (id) ON DELETE CASCADE,
            meeting_seq INTEGER NOT NULL,
            seq         INTEGER NOT NULL,
            voter       INTEGER NOT NULL,
            target      INTEGER,
            PRIMARY KEY (round_id, meeting_seq, seq)
        );

        CREATE TABLE achievements (
            user_id        INTEGER NOT NULL,
            achievement_id TEXT NOT NULL,
            unlocked_at    INTEGER NOT NULL,
            PRIMARY KEY (user_id, achievement_id)
        );

        CREATE TABLE game_settings (
            id            INTEGER PRIMARY KEY CHECK (id = 1),
            captured_at   INTEGER NOT NULL,
            lobby_code    TEXT NOT NULL,
            map           TEXT NOT NULL,
            players       INTEGER NOT NULL,
            max_players   INTEGER NOT NULL,
            num_impostors INTEGER NOT NULL
        );

        CREATE TABLE game_setting_options (
            seq   INTEGER PRIMARY KEY,
            kind  TEXT NOT NULL,
            key   TEXT NOT NULL,
            value TEXT NOT NULL,
            UNIQUE (kind, key)
        );

        CREATE TABLE game_setting_roles (
            seq    INTEGER PRIMARY KEY,
            name   TEXT NOT NULL UNIQUE,
            count  INTEGER NOT NULL,
            chance INTEGER NOT NULL
        )
        """,
    )

    /**
     * Gleiche Tabellen für MySQL/MariaDB: IDs als BIGINT (Discord-IDs sind 64 Bit), Texte mit Schlüssel als
     * VARCHAR, utf8mb4 (Emojis) mit binärem Vergleich wie in SQLite. Wo die Reihenfolge zählt, ersetzt eine
     * Spalte `rowid` die eingebaute rowid von SQLite. `IF NOT EXISTS`, weil MySQL Tabellen nicht in einer
     * Transaktion anlegt – ein abgebrochener Start kann so einfach wiederholt werden.
     */
    private const val MYSQL_TABLE = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"

    private val MYSQL_MIGRATIONS: List<String> = listOf(
        // Version 1
        """
        CREATE TABLE IF NOT EXISTS settings (
            `key`   VARCHAR(191) NOT NULL PRIMARY KEY,
            `value` TEXT NOT NULL
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS players (
            id             BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            user_id        BIGINT NOT NULL UNIQUE,
            xp             INT NOT NULL DEFAULT 0,
            level          INT NOT NULL DEFAULT 1,
            participations INT NOT NULL DEFAULT 0,
            wins           INT NOT NULL DEFAULT 0,
            extra          TEXT NULL
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS bets (
            id      BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            user_id BIGINT NOT NULL UNIQUE,
            team    VARCHAR(16) NOT NULL,
            amount  INT NOT NULL
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS events (
            message_id BIGINT NOT NULL PRIMARY KEY,
            channel_id BIGINT NOT NULL,
            starts_at  BIGINT NOT NULL,
            ends_at    BIGINT NOT NULL,
            reminded   INT NOT NULL DEFAULT 0
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS event_attendance (
            rowid      BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
            message_id BIGINT NOT NULL,
            user_id    BIGINT NOT NULL,
            minutes    INT NOT NULL DEFAULT 0,
            PRIMARY KEY (message_id, user_id),
            FOREIGN KEY (message_id) REFERENCES events (message_id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS friend_links (
            rowid       BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
            friend_code VARCHAR(64) NOT NULL PRIMARY KEY,
            user_id     BIGINT NOT NULL,
            INDEX friend_links_user (user_id)
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS game_stats (
            user_id         BIGINT NOT NULL PRIMARY KEY,
            games           INT NOT NULL DEFAULT 0,
            crew_games      INT NOT NULL DEFAULT 0,
            crew_wins       INT NOT NULL DEFAULT 0,
            impostor_games  INT NOT NULL DEFAULT 0,
            impostor_wins   INT NOT NULL DEFAULT 0,
            kills           INT NOT NULL DEFAULT 0,
            deaths          INT NOT NULL DEFAULT 0,
            ejected         INT NOT NULL DEFAULT 0,
            survived        INT NOT NULL DEFAULT 0,
            tasks_completed INT NOT NULL DEFAULT 0,
            meetings_called INT NOT NULL DEFAULT 0,
            bodies_reported INT NOT NULL DEFAULT 0,
            votes_correct   INT NOT NULL DEFAULT 0,
            votes_wrong     INT NOT NULL DEFAULT 0
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS mod_rounds (
            id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            game_id    VARCHAR(64) NOT NULL UNIQUE,
            created_at BIGINT NOT NULL,
            undone     INT NOT NULL DEFAULT 0,
            channel_id BIGINT NULL,
            message_id BIGINT NULL
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS mod_round_awards (
            game_id   VARCHAR(64) NOT NULL,
            seq       INT NOT NULL,
            user_id   BIGINT NOT NULL,
            xp        INT NOT NULL,
            win       INT NOT NULL,
            old_level INT NOT NULL,
            new_level INT NOT NULL,
            PRIMARY KEY (game_id, seq),
            FOREIGN KEY (game_id) REFERENCES mod_rounds (game_id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS mod_round_bets (
            game_id VARCHAR(64) NOT NULL,
            seq     INT NOT NULL,
            user_id BIGINT NOT NULL,
            team    VARCHAR(16) NOT NULL,
            amount  INT NOT NULL,
            PRIMARY KEY (game_id, seq),
            FOREIGN KEY (game_id) REFERENCES mod_rounds (game_id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS mod_round_stats (
            rowid   BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
            game_id VARCHAR(64) NOT NULL,
            user_id BIGINT NOT NULL,
            stat    VARCHAR(64) NOT NULL,
            delta   INT NOT NULL,
            PRIMARY KEY (game_id, user_id, stat),
            FOREIGN KEY (game_id) REFERENCES mod_rounds (game_id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS mod_round_achievements (
            game_id        VARCHAR(64) NOT NULL,
            seq            INT NOT NULL,
            user_id        BIGINT NOT NULL,
            achievement_id VARCHAR(64) NOT NULL,
            xp             INT NOT NULL,
            old_level      INT NOT NULL,
            new_level      INT NOT NULL,
            PRIMARY KEY (game_id, seq),
            FOREIGN KEY (game_id) REFERENCES mod_rounds (game_id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS round_history (
            id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            game_id          VARCHAR(64) NOT NULL UNIQUE,
            ended_at         BIGINT NOT NULL,
            lobby_code       TEXT NOT NULL,
            map              TEXT NOT NULL,
            duration_seconds DOUBLE NOT NULL,
            end_reason       TEXT NOT NULL,
            winning_team     VARCHAR(16) NULL,
            INDEX round_history_ended (ended_at)
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS round_history_players (
            round_id        BIGINT NOT NULL,
            seq             INT NOT NULL,
            player_id       INT NOT NULL,
            discord_id      BIGINT NULL,
            name            TEXT NOT NULL,
            friend_code     TEXT NOT NULL,
            role            TEXT NOT NULL,
            team            TEXT NOT NULL,
            won             INT NOT NULL,
            is_dead         INT NOT NULL,
            disconnected    INT NOT NULL,
            death_cause     TEXT NULL,
            killed_by       INT NULL,
            died_at         DOUBLE NULL,
            kills           INT NOT NULL,
            tasks_total     INT NOT NULL,
            tasks_completed INT NOT NULL,
            PRIMARY KEY (round_id, seq),
            INDEX round_history_players_discord (discord_id),
            FOREIGN KEY (round_id) REFERENCES round_history (id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS round_history_kills (
            round_id   BIGINT NOT NULL,
            seq        INT NOT NULL,
            at_seconds DOUBLE NOT NULL,
            killer     INT NOT NULL,
            victim     INT NOT NULL,
            PRIMARY KEY (round_id, seq),
            FOREIGN KEY (round_id) REFERENCES round_history (id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS round_history_meetings (
            round_id   BIGINT NOT NULL,
            seq        INT NOT NULL,
            at_seconds DOUBLE NOT NULL,
            caller     INT NOT NULL,
            body       INT NULL,
            exiled     INT NULL,
            tie        INT NOT NULL,
            PRIMARY KEY (round_id, seq),
            FOREIGN KEY (round_id) REFERENCES round_history (id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS round_history_votes (
            round_id    BIGINT NOT NULL,
            meeting_seq INT NOT NULL,
            seq         INT NOT NULL,
            voter       INT NOT NULL,
            target      INT NULL,
            PRIMARY KEY (round_id, meeting_seq, seq),
            FOREIGN KEY (round_id) REFERENCES round_history (id) ON DELETE CASCADE
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS achievements (
            rowid          BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
            user_id        BIGINT NOT NULL,
            achievement_id VARCHAR(64) NOT NULL,
            unlocked_at    BIGINT NOT NULL,
            PRIMARY KEY (user_id, achievement_id)
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS game_settings (
            id            INT NOT NULL PRIMARY KEY,
            captured_at   BIGINT NOT NULL,
            lobby_code    TEXT NOT NULL,
            map           TEXT NOT NULL,
            players       INT NOT NULL,
            max_players   INT NOT NULL,
            num_impostors INT NOT NULL
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS game_setting_options (
            seq     INT NOT NULL PRIMARY KEY,
            kind    VARCHAR(16) NOT NULL,
            `key`   VARCHAR(191) NOT NULL,
            `value` TEXT NOT NULL,
            UNIQUE KEY game_setting_options_key (kind, `key`)
        ) $MYSQL_TABLE;

        CREATE TABLE IF NOT EXISTS game_setting_roles (
            seq    INT NOT NULL PRIMARY KEY,
            name   VARCHAR(191) NOT NULL UNIQUE,
            count  INT NOT NULL,
            chance INT NOT NULL
        ) $MYSQL_TABLE
        """,
    )

    /** Beide Dialekte haben immer gleich viele Versionen. */
    val VERSION: Int get() = SQLITE_MIGRATIONS.size

    fun migrate(db: Database) {
        check(SQLITE_MIGRATIONS.size == MYSQL_MIGRATIONS.size) { "SQLite- und MySQL-Schema haben unterschiedlich viele Versionen" }
        val migrations = if (db.dialect == Dialect.MYSQL) MYSQL_MIGRATIONS else SQLITE_MIGRATIONS
        val current = db.userVersion()
        check(current <= migrations.size) {
            "Die Datenbank ist neuer als dieser Bot (Version $current > ${migrations.size}). Bitte den Bot aktualisieren."
        }
        for (version in current + 1..migrations.size) {
            db.transaction {
                db.executeScript(migrations[version - 1])
                db.setUserVersion(version)
            }
        }
    }
}
