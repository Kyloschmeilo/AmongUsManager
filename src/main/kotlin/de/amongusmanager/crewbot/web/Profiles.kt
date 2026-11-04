/*
 * Stats-Website: Regeln fürs eigene Profil – was man wählen darf und wie Eingaben geprüft werden.
 * Alles, was andere sehen, ist entweder aus einer festen Auswahl (Namen, Rollen, Maps, Farben, Kennzahlen)
 * oder ein kurzer Text ohne Steuerzeichen (die Seite zeigt ihn nur als Text an, nie als HTML).
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Auswahl fürs Profil eines Spielers: Namen (Ingame + Discord), gespielte Rollen (id → Name), Maps. */
data class ProfileOptions(
    val names: List<String>,
    val roles: Map<String, String>,
    val maps: Map<Int, String>,
) {
    fun toJson(): JsonObject = obj(
        "names" to names,
        "roles" to roles.map { (id, name) -> obj("id" to id, "name" to name) },
        "maps" to maps.map { (id, name) -> obj("mapId" to id, "map" to name) },
        "colors" to COLOR_COUNT,
        "metrics" to ProfileRules.METRICS,
        "bioMax" to ProfileRules.BIO_MAX,
        "showcaseMax" to ProfileRules.SHOWCASE_MAX,
    )
}

class ProfileException(message: String) : Exception(message)

object ProfileRules {
    const val BIO_MAX = 160
    const val SHOWCASE_MAX = 3

    /** Kennzahlen der Bestenliste (ids wie in web/src/lib/metrics.ts – StatsWebsiteTest prüft das). */
    val METRICS = listOf(
        "wins", "winrate", "games", "survival", "crewrate", "votes", "ejections", "tasks", "reports",
        "kills", "kpg", "imprate", "deceptions", "sabotages", "distance", "fixes",
    )

    /** Prüft eine Eingabe aus dem Browser; wirft [ProfileException] mit einer Meldung für die Seite. */
    fun parse(input: JsonObject, options: ProfileOptions): ProfileSettings {
        val displayName = text(input, "displayName")?.trim()?.takeIf { it.isNotEmpty() }
        if (displayName != null && displayName !in options.names) {
            throw ProfileException("Als Anzeigename geht nur einer deiner Ingame- oder Discord-Namen.")
        }

        val bio = cleanBio(text(input, "bio") ?: "")
        if (bio.codePointCount(0, bio.length) > BIO_MAX) throw ProfileException("„Über mich“ darf höchstens $BIO_MAX Zeichen lang sein.")

        val colorId = number(input, "colorId")
        if (colorId != null && colorId !in 0 until COLOR_COUNT) throw ProfileException("Unbekannte Farbe.")

        val favoriteRole = text(input, "favoriteRole")?.takeIf { it.isNotEmpty() }
        if (favoriteRole != null && favoriteRole !in options.roles) throw ProfileException("Diese Rolle gibt es hier nicht.")

        val favoriteMap = number(input, "favoriteMap")
        if (favoriteMap != null && favoriteMap !in options.maps) throw ProfileException("Diese Map gibt es nicht.")

        val showcaseRaw = input["showcase"]
        val showcase = when (showcaseRaw) {
            null, JsonNull -> emptyList()
            is JsonArray -> showcaseRaw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ProfileException("Ungültige Kennzahl.") }
            else -> throw ProfileException("Ungültige Kennzahlen.")
        }.distinct()
        if (showcase.size > SHOWCASE_MAX) throw ProfileException("Höchstens $SHOWCASE_MAX Kennzahlen.")
        if (showcase.any { it !in METRICS }) throw ProfileException("Unbekannte Kennzahl.")

        val showAvatar = when (val raw = input["showAvatar"]) {
            null, JsonNull -> true
            is JsonPrimitive -> if (raw.isString) throw ProfileException("Ungültige Angabe beim Profilbild.") else raw.content == "true"
            else -> throw ProfileException("Ungültige Angabe beim Profilbild.")
        }

        return ProfileSettings(displayName, bio, colorId, favoriteRole, favoriteMap, showcase, showAvatar)
    }

    /** Steuerzeichen raus, höchstens 3 Zeilen, Leerraum an den Enden weg. */
    fun cleanBio(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || !Character.isISOControl(it) }
            .filter { Character.getType(it) != Character.FORMAT.toInt() || it == '‍' } // keine unsichtbaren Richtungszeichen (Emoji-Verbinder bleiben)
            .split('\n')
            .map { it.trim() }
        return lines.filter { it.isNotEmpty() }.take(3).joinToString("\n").trim()
    }

    private fun text(input: JsonObject, name: String): String? = when (val value = input[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw ProfileException("Ungültige Eingabe bei $name.")
        else -> throw ProfileException("Ungültige Eingabe bei $name.")
    }

    private fun number(input: JsonObject, name: String): Int? = when (val value = input[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) throw ProfileException("Ungültige Eingabe bei $name.") else value.content.toIntOrNull()
            ?: throw ProfileException("Ungültige Eingabe bei $name.")
        else -> throw ProfileException("Ungültige Eingabe bei $name.")
    }
}

/** Among-Us-Farben 0–17 (Namen und Farbwerte stehen in web/src/lib/colors.ts). */
const val COLOR_COUNT = 18
