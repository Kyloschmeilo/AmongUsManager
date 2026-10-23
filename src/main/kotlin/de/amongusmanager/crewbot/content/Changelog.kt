/*
 * Inhalt von /changelog: ein Admin postet ihn nach jedem Update einmal in den Channel, in dem er den Befehl ausführt.
 * Bei jedem Update ERSETZEN (nicht anhängen): nur die Neuerungen seit dem letzten Changelog, kurz und für Spieler.
 * VERSION = `version` in build.gradle.kts (ein Test prüft das). „/befehl“ im Text wird automatisch anklickbar.
 */
package de.amongusmanager.crewbot.content


data class ChangelogSection(val title: String, val lines: List<String>)

object Changelog {
    const val VERSION = "2.4.0"

    val sections: List<ChangelogSection> = listOf(
        ChangelogSection(
            "🎭 The Other Roles",
            listOf(
                "Wir spielen ab jetzt mit der Mod **The Other Roles** statt mit SpecialRoles – über 90 Rollen," +
                    " von Sheriff und Medic bis Jackal und Jester",
                "Installieren geht mit dem neuen TheOtherRoles-Installer: Doppelklick, Enter, fertig." +
                    " Dein normales Among Us bleibt dabei unverändert",
            ),
        ),
        ChangelogSection(
            "📘 Rollen & Tutorials",
            listOf(
                "/special-rolle erklärt dir jetzt jede Rolle aus The Other Roles auf Deutsch",
                "Die Special-Rollen-Tutorials zeigen, wie du die Mod installierst und was jede Rolle kann",
            ),
        ),
    )
}
