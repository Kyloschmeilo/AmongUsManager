/*
 * Inhalt von /changelog: ein Admin postet ihn nach jedem Update einmal in den Channel, in dem er den Befehl ausführt.
 * Bei jedem Update ERSETZEN (nicht anhängen): nur die Neuerungen seit dem letzten Changelog, kurz und für Spieler.
 * VERSION = `version` in build.gradle.kts (ein Test prüft das). „/befehl“ im Text wird automatisch anklickbar.
 */
package de.amongusmanager.crewbot.content

import de.amongusmanager.crewbot.Rules

data class ChangelogSection(val title: String, val lines: List<String>)

object Changelog {
    const val VERSION = "2.3.0"

    val sections: List<ChangelogSection> = listOf(
        ChangelogSection(
            "🎭 Special-Rollen",
            listOf(
                "/special-rolle erklärt dir jede der 30 Rollen unserer neuen Rollen-Mod SpecialRoles",
                "Wie du die Mod installierst und was jede Rolle kann, steht in den neuen Tutorials",
            ),
        ),
        ChangelogSection(
            "📘 Tutorials",
            listOf(
                "Neue Anleitungen zu den wichtigsten Befehlen, zum Verknüpfen deines Accounts mit /verknuepfen" +
                    " und zu den Special-Rollen",
            ),
        ),
        ChangelogSection(
            "🌙 Spieleabend-Recap",
            listOf("Der Recap kommt jetzt erst ${Rules.RECAP_AFTER_HOURS} Stunden nach Event-Start statt mitten im Spieleabend"),
        ),
    )
}
