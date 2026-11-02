/*
 * Stats-Website, Bereich „Hilfe & Mods“: Tutorials (dieselben Texte wie /tutorial im Bot, content/Tutorials.kt, plus
 * eine Host-Anleitung nur für die Website), das Rollen-Lexikon (alle Rollen aus Among Us und The Other Roles, auch nie
 * gespielte) und die Downloads (Downloads.kt). Nichts davon kommt aus der Datenbank.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.SpecialTeam
import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.content.Tutorial
import de.amongusmanager.crewbot.content.TutorialSection
import de.amongusmanager.crewbot.content.torNameKeys

class WebGuides(val downloads: Downloads?) {
    val tutorialsJson: ByteArray by lazy { bytes(obj("tutorials" to WEB_TUTORIALS.map(::tutorialJson))) }
    val lexiconJson: ByteArray by lazy { bytes(lexicon()) }

    /** JSON für /api/tutorials, /api/lexicon, /api/downloads – null = keine dieser Routen. */
    fun route(parts: List<String>): ByteArray? = when (parts) {
        listOf("tutorials") -> tutorialsJson
        listOf("lexicon") -> lexiconJson
        listOf("downloads") -> downloads?.json() ?: bytes(obj("items" to emptyList<Any>()))
        else -> null
    }

    companion object {
        val ROUTES = setOf("tutorials", "lexicon", "downloads")

        /**
         * Tutorials auf der Website: die des Bots (ohne „Alle Special-Rollen erklärt“ – dafür gibt es die Rollen-Seite)
         * und die Host-Anleitung. Reihenfolge = Reiter auf der Seite.
         */
        val WEB_TUTORIALS: List<Tutorial> by lazy {
            TUTORIALS.filter { it.key != "special-rollen-liste" } + HOST_TUTORIAL
        }

        /** Nur auf der Website: CrewStats für Hosts (passt zur Download-Karte). */
        val HOST_TUTORIAL = Tutorial(
            key = "crewstats-host",
            label = "Hosten mit CrewStats",
            title = "Als Host Runden aufzeichnen",
            intro = "Damit Runden zählen – XP, Wetten, /stats und diese Website –, braucht der **Host** der Lobby die Mod" +
                " CrewStats. Alle anderen brauchen sie nicht, sie verknüpfen nur einmal ihren Account.",
            sections = listOf(
                TutorialSection(
                    "1 · INSTALLIEREN",
                    listOf(
                        "1. Lade unter Downloads den CrewStats-Installer herunter und starte ihn. Warnt Windows, klick" +
                            " **Weitere Informationen → Trotzdem ausführen**",
                        "2. Enter drücken – der Installer findet Among Us und installiert BepInEx (64-Bit) und die Mod",
                        "3. Er fragt nach der **Webhook-URL**. Die bekommst du von einem Admin – sie ist geheim, gib sie" +
                            " nicht weiter",
                    ),
                ),
                TutorialSection(
                    "2 · MIT THE OTHER ROLES",
                    listOf(
                        "Erst CrewStats installieren, dann den TheOtherRoles-Installer starten – er übernimmt CrewStats" +
                            " samt Webhook-URL in die Mod-Kopie",
                        "Hast du The Other Roles schon? Starte den TheOtherRoles-Installer einfach nochmal und antworte" +
                            " bei CrewStats mit `j`",
                        "Lass CrewStats dort an – es verhindert Kicks, wenn jemand deiner Lobby beitritt",
                    ),
                ),
                TutorialSection(
                    "3 · SPIELEN",
                    listOf(
                        "Verknüpfe einmal deinen Friendcode im Discord: /verknuepfen",
                        "Als Host zeichnet CrewStats jede Runde auf und schickt sie erst nach Rundenende an den Bot." +
                            " Im Spiel wird nichts angezeigt",
                        "Auto-Mute an/aus: im Spiel **ESC → Allgemein → Auto-Mute**",
                        "Die Mod aktualisiert sich beim Spielstart selbst",
                    ),
                ),
            ),
            footer = "Probleme? Schick die Datei BepInEx\\LogOutput.log aus deinem Among-Us-Ordner an einen Admin.",
        )

        /** Channel-Erwähnungen (<#id>) gibt es auf der Website nicht – dort steht, welcher Channel gemeint ist. */
        private val CHANNEL_NAMES = mapOf(Channels.CODES to "den Lobby-Code-Channel")
        private val CHANNEL_MENTION = Regex("""<#(\d+)>""")

        /** Sätze, die nur im Discord stimmen (dort sind Befehle anklickbar) – ein Test prüft, dass es sie noch gibt. */
        internal val DISCORD_ONLY = listOf(" – oder klick einfach einen Befehl hier an")

        internal fun webText(text: String): String =
            DISCORD_ONLY.fold(text) { acc, sentence -> acc.replace(sentence, "") }
                .let { CHANNEL_MENTION.replace(it) { m -> CHANNEL_NAMES[m.groupValues[1].toLongOrNull()] ?: "den Discord-Channel" } }

        private fun tutorialJson(t: Tutorial) = obj(
            "key" to t.key,
            "label" to t.label,
            "title" to t.title,
            "intro" to webText(t.intro),
            "footer" to webText(t.footer),
            "sections" to t.sections.map { s -> obj("title" to s.title, "lines" to s.lines.map(::webText)) },
        )

        /** Geister-Rollen des Spiels: bekommt man erst nach dem Tod. */
        private val GHOST_ROLES = setOf("Guardian Angel", "Influencer")

        /** Anzeigename im Bot → Rollenname in den Rundendaten (RoleTypes). */
        private val GAME_ROLE_IDS = mapOf("Guardian Angel" to "GuardianAngel", "Influencer" to "SpiritGuide")

        private fun lexicon() = obj(
            "roles" to ROLES.values.map { role ->
                obj(
                    "id" to "game:${GAME_ROLE_IDS[role.name] ?: role.name}",
                    "name" to role.name,
                    "team" to if (role.team == Team.IMPOSTOR) "IMPOSTOR" else "CREW",
                    "source" to "game",
                    "ghost" to (role.name in GHOST_ROLES),
                    "description" to role.description,
                    "variants" to emptyList<String>(),
                    "statIds" to listOf("game:${GAME_ROLE_IDS[role.name] ?: role.name}"),
                )
            } + SPECIAL_ROLES.values.sortedBy { it.name.lowercase() }.map { role ->
                val keys = torNameKeys(role.id)
                obj(
                    "id" to "tor:${role.id}",
                    "name" to role.name,
                    "team" to role.team.name,
                    "source" to "tor",
                    "ghost" to false,
                    "description" to role.description,
                    "variants" to keys.map { it.second }.filter { it != role.name },
                    "statIds" to keys.map { "tor:${it.first}" },
                )
            },
            "teams" to SpecialTeam.entries.map { it.name },
        )
    }
}
