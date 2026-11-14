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
 *   DISCORD_TOKEN        – Token des Bots: Discord-Profilbilder und Server-Namen (nur REST, kein zweiter Bot-Login)
 *   DISCORD_CLIENT_SECRET – „Mit Discord anmelden“ (Developer-Portal → OAuth2), Client-ID kommt aus dem Token
 *                          (oder DISCORD_CLIENT_ID)
 *   WEB_PUBLIC_URL       – Adresse der Website, z.B. http://45.81.233.92:25570 (für die Weiterleitung nach dem Login)
 *   WEB_DOWNLOADS_DIR    – Ordner mit eigenen Downloads (Standard: downloads/ neben dem JAR, z.B. TheOtherRoles-Installer.bat)
 *   CoreHoldWebsite.jar daneben legen = CoreHold-Website unter /corehold im selben Server (siehe CoreHoldBridge.kt)
 * Eigene Daten der Website (Anmeldungen, Profile, Profilbilder) liegen in data/website.db.
 * CrewStats-Spieleranfragen (Auto-Join, Stats, Verknüpfen): POST /api/mod/<Schlüssel>, siehe services/ModApi.kt.
 * Live-Lobbys mit „Beitreten“: /api/lobbies, siehe LiveLobbies.kt.
 * Proximity-Voice im Live-Tab: /api/voice (SSE + WebRTC im Browser), Positionen vom Host über die Mod-API, siehe VoiceChat.kt.
 * Crack-Voting nach jeder Runde (15 min, Stimmen in data/website.db): /api/crack, siehe CrackVoting.kt.
 * Bot steuern (Event-Tab, Admin-Panel): die Website schreibt Aufträge in die Tabelle web_requests der Bot-Datenbank,
 * der Bot führt sie aus und prüft dabei die Discord-Rollen selbst.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.BotPaths
import de.amongusmanager.crewbot.Env
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.ModApiSettings
import de.amongusmanager.crewbot.services.WebQueue
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

    // Eigene Daten der Website (Anmeldungen, Profile, Discord-Profilbilder) – die Bot-Datenbank bleibt nur gelesen
    val accountsDb = Database.openStandalone(BotPaths.dataDir.resolve("website.db"))
    val accounts = WebAccounts(accountsDb)
    val botToken = Env.discordToken.takeIf { it.isNotEmpty() }
    val discord = HttpDiscordApi(botToken, Env.get("WEB_GUILD_ID").trim().toLongOrNull())
    val sync = if (botToken != null) DiscordSync(discord, accounts) else null
    val authConfig = AuthConfig(
        clientId = Env.get("DISCORD_CLIENT_ID").trim().ifEmpty { null } ?: HttpDiscordApi.clientIdFromToken(botToken),
        clientSecret = Env.get("DISCORD_CLIENT_SECRET").trim().ifEmpty { null },
        publicUrl = Env.get("WEB_PUBLIC_URL").trim().ifEmpty { null },
    )
    if (sync == null) log.info("🖼️ Ohne DISCORD_TOKEN: keine Discord-Profilbilder (farbige Crewmates)")
    if (authConfig.enabled) {
        log.info("🔑 Discord-Anmeldung an (Weiterleitungs-Adresse im Developer-Portal: {}/auth/callback)", authConfig.publicUrl ?: "http://<Adresse>:<Port>")
    } else {
        log.info("🔑 Discord-Anmeldung aus (dafür DISCORD_CLIENT_SECRET in die .env)")
    }

    val crackVotes = CrackVotes(accountsDb)
    val store = StatsStore(source, accounts, crackVotes = crackVotes)
    try {
        store.refresh()
    } catch (e: Exception) {
        log.error("❌ Runden konnten nicht geladen werden (neuer Versuch in Kürze): {}", e.message, e)
    }

    // Hilfe & Mods: eigene Dateien aus downloads/, Versionen von CrewStats/The Other Roles von GitHub
    val downloadsDir = Env.get("WEB_DOWNLOADS_DIR").trim().ifEmpty { null }?.let { Path.of(it) } ?: BotPaths.projectDir.resolve("downloads")
    val downloads = Downloads(downloadsDir, GitHubReleases())
    log.info("📦 Downloads: {} ({} eigene Dateien)", downloadsDir, downloads.localFiles().size)

    val auth = WebAuth(accounts, store, discord, authConfig, sync)
    // Bot steuern (Events, Admin-Panel): eigene, beschreibbare Verbindung nur für die Auftrags-Tabelle des Bots
    val controlDb = if (db != null) openControlDatabase() else null
    val control = controlDb?.let { ControlApi(auth, BotLink(WebQueue(it)), store) }
    if (control == null) log.info("🎛️ Bot-Steuerung aus (keine Bot-Datenbank)")
    // CrewStats fragt (Auto-Join, Stats, Verknüpfen) hier statt über einen Discord-Webhook – /autojoin zeigt dann diese URL
    val picks = JoinPicks()
    // Proximity-Voice im Live-Tab: Positionen kommen von der Host-Mod über die Mod-API, Sprache läuft per WebRTC im Browser
    val voice = (controlDb ?: db)?.let { VoiceChat(it, auth, store, VoiceChat.iceFromEnv(Env.get("VOICE_ICE_SERVERS"))) }
    val modApi = controlDb?.let { ModApi(it, BotLink(WebQueue(it), wait = Duration.ofSeconds(6)), picks = picks, voice = voice) }
    // Live-Lobbys (wer ist gerade in welcher Lobby) + „Beitreten“ über Auto-Join der Mod
    val lobbies = (controlDb ?: db)?.let { LiveLobbies(it, auth, store, picks) }
    val beat = {
        try {
            modApi?.heartbeat(authConfig.publicUrl)
        } catch (e: Exception) {
            log.warn("Mod-API: Lebenszeichen nicht gespeichert: {}", e.message)
        }
    }
    when {
        modApi == null -> {}
        authConfig.publicUrl == null -> log.info("🎮 Mod-API aus Sicht von /autojoin unbekannt – dafür WEB_PUBLIC_URL in die .env")
        else -> log.info("🎮 Mod-API an: CrewStats fragt Auto-Join/Stats über {}{}…", authConfig.publicUrl, ModApiSettings.PATH)
    }

    // Geheimfunk (Wort-Ratespiel, /spiele/geheimfunk.html): Räume nur im Speicher, braucht keine Datenbank
    val wordGame = WordGame()
    val coreHold = CoreHoldBridge.fromEnv({ Env.get(it) }, BotPaths.projectDir)
    val port = webPort()
    val server = try {
        StatsServer(
            store, port, Env.get("WEB_HOST").trim().ifEmpty { "0.0.0.0" }, auth, accounts,
            guides = WebGuides(downloads),
            control = control,
            modApi = modApi,
            lobbies = lobbies,
            coreHold = coreHold,
            crack = CrackVoting(crackVotes, auth, store),
            voice = voice,
            wordGame = wordGame,
        )
    } catch (e: Exception) {
        log.error("❌ Port {} ist nicht frei: {}", port, e.message)
        exitProcess(1)
    }
    server.start()
    voice?.start()
    wordGame.start()
    if (coreHold != null) {
        coreHold.start()
        log.info("🧱 CoreHold-Website: {} wird mitgestartet, erreichbar unter /corehold", Env.get("COREHOLD_JAR").trim().ifEmpty { CoreHoldBridge.DEFAULT_JAR })
    }
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
        beat()
        syncDiscord(sync, store)
    }, refreshSeconds, refreshSeconds, TimeUnit.SECONDS)
    scheduler.execute { beat() } // /autojoin kennt die Website-URL sofort
    scheduler.execute { syncDiscord(sync, store) } // Profilbilder gleich nach dem Start holen
    scheduler.execute { downloads.refreshNow() } // Versionen für die Download-Seite

    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("Stats-Website wird beendet …")
        scheduler.shutdownNow()
        server.stop()
        db?.close()
        controlDb?.close()
        accountsDb.close()
    }, "web-shutdown"))
}

private var lastSyncFailure: String? = null

/** Namen und Profilbilder der verknüpften Spieler aus Discord holen (fällige, in kleinen Portionen). */
private fun syncDiscord(sync: DiscordSync?, store: StatsStore) {
    if (sync == null) return
    try {
        if (sync.sync(store.snapshot.linkedDiscordIds()) > 0) store.refreshQuietly()
        lastSyncFailure = null
    } catch (e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        if (message != lastSyncFailure) log.warn("Discord-Profile nicht geladen: {}", message)
        lastSyncFailure = message
    }
}

/** Zweite Verbindung zur Bot-Datenbank, beschreibbar, ohne Schema-Änderungen (nur für web_requests). */
private fun openControlDatabase(): Database? = try {
    val mysql = Env.mysql
    if (mysql != null) Database.mysql(mysql.host, mysql.name, mysql.user, mysql.password, readOnly = false, migrate = false)
    else Database.openShared(BotPaths.database)
} catch (e: Exception) {
    log.warn("🎛️ Bot-Steuerung aus – Datenbank nicht beschreibbar: {}", e.message)
    null
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
