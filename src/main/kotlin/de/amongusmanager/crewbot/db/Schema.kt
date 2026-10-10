/*
 * Tabellen der Datenbank. Zeitpunkte werden als Millisekunden seit 1970 (UTC) gespeichert.
 *
 * Neue Spalten/Tabellen: eine weitere Version an MIGRATIONS anhängen (nie alte Versionen ändern –
 * die Datenbank auf dem Server ist schon auf dem alten Stand).
 */
package de.amongusmanager.crewbot.db

object Schema {
    private val MIGRATIONS: List<String> = listOf(
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

    val VERSION: Int get() = MIGRATIONS.size

    fun migrate(db: Database) {
        val current = db.userVersion()
        check(current <= MIGRATIONS.size) {
            "Die Datenbank ist neuer als dieser Bot (Version $current > ${MIGRATIONS.size}). Bitte den Bot aktualisieren."
        }
        for (version in current + 1..MIGRATIONS.size) {
            db.transaction {
                db.executeScript(MIGRATIONS[version - 1])
                db.setUserVersion(version)
            }
        }
    }
}
