/*
 * Inhalt von /changelog: ein Admin postet ihn nach jedem Update einmal in den Channel, in dem er den Befehl ausführt.
 * Bei jedem Update ERSETZEN (nicht anhängen): nur die Neuerungen seit dem letzten Changelog, kurz und für Spieler.
 * VERSION = `version` in build.gradle.kts (ein Test prüft das). „/befehl“ im Text wird automatisch anklickbar.
 */
package de.amongusmanager.crewbot.content


data class ChangelogSection(val title: String, val lines: List<String>)

object Changelog {
    const val VERSION = "2.5.0"

    val sections: List<ChangelogSection> = listOf(
        ChangelogSection(
            "🎭 The Other Roles zählt mit",
            listOf(
                "Mod-Runden mit The Other Roles werden jetzt richtig ausgewertet – im Ergebnis steht die Rolle jedes" +
                    " Spielers, was eine Rolle kann, zeigt /special-rolle",
                "Neutrale Rollen gewinnen für sich: Ein Sieg als Jester, Jackal & Co. bringt **30 XP**" +
                    " (Crew weiter 10, Impostor 25). Gewinnt eine neutrale Rolle, verlieren alle Wetten",
                "Schaltet eine Crew-Rolle einen Impostor oder Neutralen aus – Sheriff-Schuss, Guesser-Treffer," +
                    " Jailor-Hinrichtung –, gibt es **+5 XP**, auch wenn die Crew verliert",
                "In /stats zählen Runden mit neutraler Rolle weder als Crew- noch als Impostor-Runde",
            ),
        ),
        ChangelogSection(
            "🔇 Auto-Mute",
            listOf(
                "Wer im Meeting vom **Blackmailer** erpresst oder vom **Jailor** eingesperrt ist, wird jetzt auch im" +
                    " Discord stumm geschaltet – bis das Meeting vorbei ist",
                "Dafür braucht ihr CrewStats 1.4.0, die Mod aktualisiert sich beim Spielstart selbst",
            ),
        ),
    )
}
