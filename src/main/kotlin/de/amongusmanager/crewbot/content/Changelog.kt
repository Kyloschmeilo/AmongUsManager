/*
 * Inhalt von /changelog: ein Admin postet ihn nach jedem Update einmal in den Channel, in dem er den Befehl ausführt.
 * Bei jedem Update ERSETZEN (nicht anhängen): nur die Neuerungen seit dem letzten Changelog, kurz und für Spieler.
 * VERSION = `version` in build.gradle.kts (ein Test prüft das). „/befehl“ im Text wird automatisch anklickbar.
 */
package de.amongusmanager.crewbot.content

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.Rules

data class ChangelogSection(val title: String, val lines: List<String>)

object Changelog {
    const val VERSION = "2.1.0"

    val sections: List<ChangelogSection> = listOf(
        ChangelogSection(
            "🎭 Rollen-Panel",
            listOf(
                "Im <#${Channels.ROLES}> holst du dir deine Rollen per Knopf: Clans (nur einer), Spiele und Minecraft-Events",
                "Jeder Knopf zeigt, wie viele die Rolle schon haben",
                "Neue Mitglieder bekommen die Standard-Rollen automatisch",
            ),
        ),
        ChangelogSection(
            "🎟️ Rollenwünsche",
            listOf(
                "/rollenwunsch: Wünsch dir eine Rolle für deine nächste Among-Us-Runde" +
                    " (${Rules.ROLE_WISH_COST} XP, ${Rules.ROLE_WISHES_PER_EVENING}× pro Spieleabend, vorher /verknuepfen)",
                "Ist die Rolle schon vergeben, kommt eine Fehlermeldung – klappt der Wunsch nicht, gibt's die XP zurück",
                "Admins legen mit /rolle-zuweisen Rollen fest",
                "Im Spiel-Chat steht offen, wie viele Rollen per Discord kommen",
            ),
        ),
    )
}
