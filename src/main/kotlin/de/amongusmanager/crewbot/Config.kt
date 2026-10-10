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
