package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Discord-Login in den Browserspielen (21.11.2026, Kilian: „wenn man mit dc angemeldet ist, dann automatisch der Name
 * genommen wird und hosten kann man auch nur mit dc link“).
 *
 * Läuft zentral in [StatsServer] vor den Spielen, die Spiele selbst wissen nichts davon:
 * - **Hosten** (Raum eröffnen) nur mit Discord-Anmeldung → sonst 401 `{error, login:true}`. Beitreten, Zuschauen und
 *   die Arenen bleiben offen (auch der Direkt-Beitritt aus der Live-Leiste).
 * - Wer angemeldet ist, spielt immer unter seinem Discord-Namen: das Feld `name` beim Eröffnen/Beitreten (und beim
 *   Umbenennen in Geheimfunk) wird durch ihn ersetzt, egal was der Browser schickt.
 * - Ist der Discord-Login auf der Website aus (kein Client Secret), gibt es keine Anmeldung – dann darf jeder hosten.
 */
object GameLogin {
    /** Was eine Anfrage tut: HOST = Raum eröffnen (Name zählt auch), NAME = nur der Name zählt. */
    enum class Kind { HOST, NAME }

    /** Längster Name, den die Spiele annehmen (alle schneiden bei 16 ab). */
    const val MAX_NAME = 16

    const val LOGIN_NEEDED = "Raum eröffnen geht nur mit Discord-Anmeldung. Beitreten kannst du auch so."

    /** Eigene Antwort für die Spiel-Seiten: `GET /api/spielname` → {loginEnabled, loggedIn, name}. */
    const val STATUS_PATH = "/api/spielname"

    /** Spiele mit Raum-Code: Aktion steht im Feld „a“ des JSON. */
    private val ACTION_GAMES = setOf(DrawGame.PATH, MemeGame.PATH, ShipGame.PATH, ChessGame.PATH)

    fun kindOf(path: String, body: JsonObject?): Kind? {
        if (path in ACTION_GAMES) {
            return when (body?.str("a")) {
                "create" -> Kind.HOST
                "join" -> Kind.NAME
                else -> null
            }
        }
        return when (path) {
            "${Kritzelpost.PATH}/create", "${WordGame.PATH}/create" -> Kind.HOST
            "${Kritzelpost.PATH}/join", "${WordGame.PATH}/join" -> Kind.NAME
            "${WordGame.PATH}/act" -> if (body?.str("do") == "profile") Kind.NAME else null
            "${Ballerbude.PATH}/join", "${NoodleGame.PATH}/join", "${MampfGame.PATH}/join", "${MampfGame.PATH}/spawn" -> Kind.NAME
            else -> null
        }
    }

    /** Schneller Vorfilter (ohne JSON zu lesen): Eingaben der Arenen 25×/s sollen nichts kosten. */
    fun candidate(path: String): Boolean = path in ACTION_GAMES || path.endsWith("/create") || path.endsWith("/join") ||
        path == "${WordGame.PATH}/act" || path == "${MampfGame.PATH}/spawn"

    /** Ergebnis: neuer Anfrage-Inhalt (ggf. unverändert) oder eine Fehler-Antwort. */
    class Outcome(val body: ByteArray, val error: WebResponse? = null)

    /**
     * [name] liefert den Discord-Name des Angemeldeten (null = nicht angemeldet), [loginEnabled] = gibt es auf der Website den
     * Discord-Login überhaupt. Anfragen, die kein Spiel-Eröffnen/-Beitreten sind, bleiben byte-genau gleich.
     */
    fun apply(path: String, body: ByteArray, loginEnabled: Boolean, name: () -> String?): Outcome {
        if (body.isEmpty() || !candidate(path)) return Outcome(body)
        val json = runCatching { parseJson(String(body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return Outcome(body)
        val kind = kindOf(path, json) ?: return Outcome(body)
        val shown = name()?.let { cleanName(it) }
        if (kind == Kind.HOST && shown == null && loginEnabled) {
            return Outcome(body, WebResponse(401, body = bytes(obj("error" to LOGIN_NEEDED, "login" to true)), headers = listOf("Cache-Control" to "no-store")))
        }
        if (shown == null) return Outcome(body)
        val rewritten = JsonObject(json + ("name" to JsonPrimitive(shown)))
        return Outcome(rewritten.toString().toByteArray(Charsets.UTF_8))
    }

    /** Discord-Namen können Zeichen enthalten, die die Spiele nicht mögen – grob säubern, Rest machen die Spiele. */
    fun cleanName(raw: String): String? =
        raw.filter { !it.isISOControl() && it != '<' && it != '>' }.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim().ifEmpty { null }

    /** Antwort für [STATUS_PATH]. */
    fun status(loginEnabled: Boolean, name: String?): JsonObject {
        val shown = name?.let { cleanName(it) }
        return obj("loginEnabled" to loginEnabled, "loggedIn" to (shown != null), "name" to shown)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
