/*
 * Zentrale Konfiguration: Tokens, Discord-IDs und Spielregeln.
 *
 * Tokens stehen NICHT im Code, sondern in der Datei `.env` im Arbeitsordner
 * (Vorlage: `.env.example`). Alles andere, was man am Server ändern möchte
 * (Kanäle, Rollen, XP-Werte), wird ausschließlich hier eingestellt.
 */
package de.amongusmanager.crewbot

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

// ==========================================
// PFADE
// ==========================================

object BotPaths {
    /** Arbeitsordner: hier liegen `.env` und `data/` (auf dem Panel: /home/container). */
    val projectDir: Path = Path.of("").toAbsolutePath()

    /** Spielerdaten: die Datenbank `crewbot.db` (und die alten JSON-Dateien als Backup). */
    val dataDir: Path = System.getenv("CREWBOT_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: projectDir.resolve("data")

    val database: Path get() = dataDir.resolve("crewbot.db")
}

// ==========================================
// TOKENS (.env)
// ==========================================

object Env {
    private val values = HashMap<String, String>()

    /** Minimaler .env-Loader (KEY=VALUE pro Zeile). Echte Umgebungsvariablen haben Vorrang. */
    fun load(file: Path) {
        if (!Files.exists(file)) return
        for (rawLine in Files.readAllLines(file, Charsets.UTF_8)) {
            val line = rawLine.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith("#") || "=" !in line) continue
            val key = line.substringBefore("=").trim()
            val value = line.substringAfter("=").trim().trim('"', '\'')
            values.putIfAbsent(key, value)
        }
    }

    fun get(key: String): String = System.getenv(key) ?: values[key] ?: ""

    val discordToken: String get() = get("DISCORD_TOKEN").trim()
    val workerTokens: List<String>
        get() = get("WORKER_TOKENS").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** MySQL/MariaDB statt SQLite, sobald DB_HOST gesetzt ist (Werte wie im Pelican-Panel unter „Datenbanken“). */
    val mysql: MysqlConfig?
        get() {
            val host = get("DB_HOST").trim()
            if (host.isEmpty()) return null
            val missing = listOf("DB_NAME", "DB_USER", "DB_PASSWORD").filter { get(it).isEmpty() }
            require(missing.isEmpty()) { "DB_HOST ist gesetzt, aber es fehlt: ${missing.joinToString(", ")}" }
            return MysqlConfig(host, get("DB_NAME").trim(), get("DB_USER").trim(), get("DB_PASSWORD"))
        }
}

data class MysqlConfig(val host: String, val name: String, val user: String, val password: String) {
    override fun toString(): String = "MysqlConfig(host=$host, name=$name, user=$user)" // nie das Passwort loggen
}

val TIMEZONE: ZoneId = ZoneId.of("Europe/Berlin")

// ==========================================
// DISCORD IDs
// ==========================================

object Channels {
    const val AMONG_US_VOICE = 1462514637680672861L
    const val AUTO_UNMUTE_VOICE = 1462514709612859706L // Wer hier beitritt, wird entmutet
    const val CODES = 1463921458249076959L // Lobby-Codes (/code) + automatische Lobby-Ankündigung
    const val LOG = 1463920288214548684L // Admin-/Host-Log (Admin-Panel, /mod-setup, ausgewertete Runden …)

    // Rohdaten des Minecraft-Plugins (Webhook, Bot löscht sie sofort). Der Bot verschiebt den
    // Webhook beim Start selbst hierher – die URL im Plugin bleibt dabei gleich.
    const val MINECRAFT_INBOX = LOG
    const val ROLE_LOG = 1463920288214548684L
    const val MINECRAFT = 1463920288214548684L // Standard für Minecraft-Ankündigungen (vorerst #test, änderbar mit /dc)
    const val ROLES = 1499809856603816136L // Rollen-Channel: hier postet /rollen-panel die Rollen-Auswahl
}

object Roles {
    const val AMONG_US_PING = 1515459584557322422L
    const val EVENT_MANAGER = 1515473925990256740L
}

/** Spiel -> Rolle, die beim Event gepingt wird (Reihenfolge = Auswahlmenü). */
val GAME_PING_ROLES: Map<String, Long> = linkedMapOf(
    "Among Us" to Roles.AMONG_US_PING,
    "Risiko" to 1515459654010802226L,
    "R.E.P.O." to 1515459719769227334L,
    "Stickfight" to 1515459966012756139L,
    "Ultimate Chicken Horse" to 1515460094559522896L,
    "GeoGuessr" to 1515462477847072828L,
    "PEAK" to 1515459802065797131L,
    "Browser Games" to 1515473357477515425L,
    "Lethal Company" to 1515462551150788798L,
    "Meccha Chameleon" to 1521928174298992680L,
    "ROUNDS" to 1515459848290959601L,
)

/** Eine Rolle zum Selbst-Abholen im Rollen-Panel (/rollen-panel). */
data class SelfRole(val emoji: String, val name: String, val roleId: Long)

/**
 * Eine Nachricht des Rollen-Panels: Überschrift + ein Button je Rolle (höchstens 25).
 * [key] wird gespeichert – nie umbenennen. [exclusive]: man kann nur eine Rolle der Gruppe haben.
 */
data class SelfRoleGroup(
    val key: String,
    val title: String,
    val description: String,
    val roles: List<SelfRole>,
    val exclusive: Boolean = false,
)

/** Rollen-Panel: jede Gruppe ist eine eigene Nachricht (Reihenfolge = Reihenfolge im Channel). */
val SELF_ROLE_GROUPS: List<SelfRoleGroup> = listOf(
    SelfRoleGroup(
        key = "clans",
        title = "🏰 Clans",
        description = "Such dir eine Rolle passend zu deinem Clan.",
        exclusive = true,
        roles = listOf(
            SelfRole("🏛️", "NOVA", 1490514611403755580L),
            SelfRole("🪵", "SPP", 1474591477119062016L),
            SelfRole("🐵", "MAC", 1499808447548358818L),
            SelfRole("🏝️", "Insel", 1474591918569689239L),
            SelfRole("🚢", "Handels Havn", 1515457948279640175L),
            SelfRole("💩", "Kake-Fresser", 1499814292185153687L),
            SelfRole("🧸", "GBB", 1522655413684801666L),
            SelfRole("🎱", "OST", 1538673319413285035L),
            SelfRole("🌅", "Ossis", 1478460962758594671L),
        ),
    ),
    SelfRoleGroup(
        key = "games",
        title = "🎮 Spiele",
        description = "Nehmt euch die Spiele raus, die ihr mit uns spielen wollt – " +
            "bei Events zu diesen Spielen werdet ihr gepingt.",
        roles = listOf(
            SelfRole("🔪", "Among Us", GAME_PING_ROLES.getValue("Among Us")),
            SelfRole("🎲", "Risiko", GAME_PING_ROLES.getValue("Risiko")),
            SelfRole("👾", "R.E.P.O.", GAME_PING_ROLES.getValue("R.E.P.O.")),
            SelfRole("🪵", "Stickfight", GAME_PING_ROLES.getValue("Stickfight")),
            SelfRole("🐔", "Ultimate Chicken Horse", GAME_PING_ROLES.getValue("Ultimate Chicken Horse")),
            SelfRole("🗺️", "GeoGuessr", GAME_PING_ROLES.getValue("GeoGuessr")),
            SelfRole("🏔️", "PEAK", GAME_PING_ROLES.getValue("PEAK")),
            SelfRole("🏭", "Lethal Company", GAME_PING_ROLES.getValue("Lethal Company")),
            SelfRole("🌐", "Browser Games", GAME_PING_ROLES.getValue("Browser Games")),
            SelfRole("🥊", "ROUNDS", GAME_PING_ROLES.getValue("ROUNDS")),
            SelfRole("🎨", "Meccha Chameleon", GAME_PING_ROLES.getValue("Meccha Chameleon")),
            SelfRole("🔫", "Lockdown Protocol", 1556707082386808883L),
        ),
    ),
    SelfRoleGroup(
        key = "minecraft",
        title = "⛏️ Minecraft-Events",
        description = "Bei welchen Minecraft-Events willst du dabei sein? Du wirst dazu gepingt.",
        roles = listOf(
            SelfRole("⚔️", "PVP", 1551601510989828137L),
            SelfRole("🏆", "MCC", 1559255061224235091L),
        ),
    ),
)

/** Rollen, die jedes Mitglied automatisch bekommt (beim Beitreten und beim Bot-Start für alle, Bots ausgenommen). */
val AUTO_ROLES: List<Long> = listOf(
    1544486785730478151L,
    1544485832860110868L,
)

/** Level -> Rolle, die ab diesem Level automatisch vergeben wird. */
val LEVEL_ROLE_REWARDS: Map<Int, Long> = linkedMapOf(
    5 to Roles.EVENT_MANAGER,
)

// ==========================================
// SPIELREGELN
// ==========================================

object Rules {
    const val XP_PER_LEVEL = 50

    const val EVENT_ATTENDANCE_XP = 10 // gibt es erst, wenn man beim Event wirklich im Voice war
    const val IMPOSTOR_WIN_XP = 25
    const val CREW_WIN_XP = 10
    const val MVP_XP = 50

    const val MAX_BET = 50
    const val BET_PAYOUT_FACTOR = 2
    const val BET_LOCK_MAX_MINUTES = 45L // Sicherheitsnetz: so lange bleibt das Wettbüro nach Rundenstart höchstens zu
    const val BET_UNLOCK_GRACE_SECONDS = 30L // nach Rundenende ohne Ergebnis (z.B. Spiel verlassen) wieder öffnen

    const val MVP_VOTING_SECONDS = 60L
    const val RECAP_HOURS = 6L // „heute Abend“ für MVP-Vorschläge
    const val EVENT_DURATION_HOURS = 2L
    const val EVENT_REMINDER_MINUTES = 15L // Erinnerung so viele Minuten vor Start
    const val EVENT_MIN_ATTENDANCE_MINUTES = 15 // so lange muss man im Voice sein, um als anwesend zu zählen

    const val FUN_TIMEOUT_CHANCE = 0.10
    const val FUN_TIMEOUT_MINUTES = 10L
}
