/*
 * Inhalt von /tutorial: Anleitungen für Spieler, die ein Admin in einen Channel oder Thread postet.
 * „/befehl“ im Text wird automatisch anklickbar (ein Test prüft, dass es die Befehle gibt).
 * Die Special-Rollen-Tutorials beschreiben die Mod The Other Roles (GMIA) und unseren TheOtherRoles-Installer
 * (liegt nicht auf GitHub, Kilian postet die .bat im Discord) – bei Änderungen am Installer mitziehen.
 */
package de.amongusmanager.crewbot.content

import de.amongusmanager.crewbot.Channels
import de.amongusmanager.crewbot.Rules

data class TutorialSection(val title: String, val lines: List<String>)

/** [key] steht in der Auswahl des Befehls – nie umbenennen, nur neue anhängen. */
data class Tutorial(
    val key: String,
    val label: String,
    val title: String,
    val intro: String,
    val sections: List<TutorialSection>,
    val footer: String,
)

const val TOR_GITHUB = "https://github.com/GMIA-Nexus/TheOtherRolesGMIA"

private fun teamCount(team: SpecialTeam) = SPECIAL_ROLES.values.count { it.team == team }

val TUTORIALS: List<Tutorial> = listOf(
    Tutorial(
        key = "befehle",
        label = "Wichtigste Befehle als Spieler",
        title = "Die wichtigsten Befehle",
        intro = "Tipp `/` in den Chat und wähl den Befehl aus der Liste – oder klick einfach einen Befehl hier an." +
            " Die komplette Liste zeigt dir /hilfe.",
        sections = listOf(
            TutorialSection(
                "LOS GEHT'S",
                listOf(
                    "/verknuepfen – verbindet deinen Among-Us-Account mit Discord. Einmal machen, dann zählen" +
                        " deine Runden automatisch",
                    "/hilfe – alle Befehle auf einen Blick",
                ),
            ),
            TutorialSection(
                "SPIELEABEND",
                listOf(
                    "/code – teilt deinen Lobby-Code in <#${Channels.CODES}>",
                    "/moveall – holt alle aus deinem Voice in den Among-Us-Channel",
                    "/autojoin – Among Us tritt mit CrewStats von selbst der neuesten Lobby bei",
                    "/create-event – plant einen Spieleabend mit Anmeldung",
                    "/bet – setz bis zu ${Rules.MAX_BET} XP auf Crew oder Impostor. Gewinnt dein Team, bekommst du" +
                        " das ${Rules.BET_PAYOUT_FACTOR}-Fache. Sobald die Runde läuft, ist das Wettbüro zu",
                    "/rollenwunsch – wünsch dir eine Rolle für deine nächste Runde" +
                        " (${Rules.ROLE_WISH_COST} XP, ${Rules.ROLE_WISHES_PER_EVENING}× pro Spieleabend)," +
                        " Special-Rollen mit /special-rollenwunsch",
                ),
            ),
            TutorialSection(
                "FORTSCHRITT",
                listOf(
                    "/profile – dein Level und wie viel XP bis zum nächsten fehlen",
                    "/crew-card – deine Astronauten-Visitenkarte",
                    "/leaderboard – die Top 10, die Top 3 tragen eine Medaille im Namen",
                    "/stats – deine Statistiken und Rivalen aus den aufgezeichneten Runden",
                    "/erfolge – freigeschaltete und offene Erfolge",
                    "/recap – Auszeichnungen und MVP-Kandidaten der letzten Runden",
                ),
            ),
            TutorialSection(
                "WISSEN",
                listOf(
                    "/rolle und /special-rolle – erklären jede Rolle aus dem Spiel und aus der Mod The Other Roles",
                    "/map-info und /vents – Kameras, Gefahrenzonen und das Vent-Netz jeder Map",
                    "/rundensettings – die Lobby-Einstellungen der letzten Runde",
                ),
            ),
            TutorialSection(
                "SO SAMMELST DU XP",
                listOf(
                    "Impostor-Sieg `${Rules.IMPOSTOR_WIN_XP} XP` · Crew-Sieg `${Rules.CREW_WIN_XP} XP`" +
                        " · MVP des Abends `${Rules.MVP_XP} XP`",
                    "Beim Event dabei (im Voice) `${Rules.EVENT_ATTENDANCE_XP} XP`",
                    "Alle `${Rules.XP_PER_LEVEL} XP` steigst du ein Level auf",
                ),
            ),
        ),
        footer = "Fragen? Schreib einfach einen Admin an.",
    ),
    Tutorial(
        key = "verknuepfen",
        label = "Account verknüpfen",
        title = "Among-Us-Account verknüpfen",
        intro = "Der Host zeichnet jede Runde mit der CrewStats-Mod auf. Damit der Bot weiß, welcher Spieler im" +
            " Spiel du bist, verknüpfst du einmal deinen Friendcode mit Discord. Das dauert eine Minute.",
        sections = listOf(
            TutorialSection(
                "1 · FRIENDCODE FINDEN",
                listOf(
                    "Starte Among Us und öffne oben das **Freunde-Menü**",
                    "Dort steht dein Friendcode, zum Beispiel `happyfox#1234`",
                ),
            ),
            TutorialSection(
                "2 · VERKNÜPFEN",
                listOf(
                    "Gib hier im Discord /verknuepfen ein und trag deinen Friendcode ein",
                    "Der Bot bestätigt dir die Verknüpfung – nur du siehst die Antwort",
                ),
            ),
            TutorialSection(
                "3 · FERTIG",
                listOf(
                    "Ab der nächsten Runde bekommst du XP und Siege automatisch",
                    "/stats, /erfolge und /recap zeigen deine Runden",
                    "Du kannst dir mit /rollenwunsch Rollen wünschen",
                    "Bei Auto-Mute erkennt der Bot, wenn du tot bist, und schaltet dich passend stumm",
                ),
            ),
            TutorialSection(
                "GUT ZU WISSEN",
                listOf(
                    "Du selbst brauchst keine Mod – es reicht, wenn der Host CrewStats hat",
                    "Zweitaccount? Einfach nochmal /verknuepfen mit dem anderen Friendcode",
                    "Ist dein Friendcode schon mit jemand anderem verknüpft, melde dich bei einem Admin",
                ),
            ),
        ),
        footer = "Ohne Verknüpfung zählen deine Runden nur unter deinem Ingame-Namen.",
    ),
    Tutorial(
        key = "special-rollen",
        label = "Special-Rollen (The Other Roles)",
        title = "The Other Roles: über 90 neue Rollen",
        intro = "Wir spielen mit der Mod **The Other Roles** (Version GMIA). Sie bringt" +
            " ${teamCount(SpecialTeam.CREW)} Crew-, ${teamCount(SpecialTeam.IMPOSTOR)} Impostor- und" +
            " ${teamCount(SpecialTeam.NEUTRAL)} neutrale Rollen, ${teamCount(SpecialTeam.BOTH)} Rollen für beide Teams und" +
            " ${teamCount(SpecialTeam.MODIFIER)} Modifikatoren ins Spiel.",
        sections = listOf(
            TutorialSection(
                "INSTALLIEREN",
                listOf(
                    "1. Lade die Datei `TheOtherRoles-Installer.bat` herunter – sie ist im Discord gepostet und liegt" +
                        " auf der Stats-Website unter Downloads",
                    "2. Doppelklicken. Warnt Windows, klick **Weitere Informationen → Trotzdem ausführen**",
                    "3. Enter drücken – der Installer findet Among Us, legt daneben eine eigene Kopie für die Mod an" +
                        " und installiert dort alles. Dein normales Among Us bleibt unverändert",
                    "4. Starte die Mod über die neue Desktop-Verknüpfung **Among Us - The Other Roles** (Steam muss" +
                        " laufen). Über Steam startest du weiter das normale Among Us",
                    "Der erste Start dauert 1–2 Minuten. Neue Version oder Among-Us-Update? Installer nochmal" +
                        " starten. Deinstallieren: Installer starten und `2` wählen",
                ),
            ),
            TutorialSection(
                "WICHTIG",
                listOf(
                    "**Alle Spieler der Lobby** brauchen die Mod in derselben Version",
                    "Gespielt wird auf dem Server **Modded NA** – der Installer stellt ihn schon ein." +
                        " Auf den normalen Among-Us-Servern geht die Mod nicht",
                    "Hosts: Lass CrewStats an (der Installer übernimmt es, wenn du es hast) – es verhindert Kicks," +
                        " wenn jemand deiner Lobby beitritt",
                    "Nur für Among Us auf Steam (Windows, 64-Bit). Die Mod selbst: [GitHub]($TOR_GITHUB)",
                ),
            ),
            TutorialSection(
                "IM SPIEL",
                listOf(
                    "Deine Rolle siehst du beim Rundenstart und unter deinem Namen – nur du siehst sie",
                    "Fähigkeiten liegen auf eigenen Knöpfen unten rechts neben dem Kill-Knopf",
                    "Die Mod ist auf Englisch – die Rollen heißen im Spiel genauso wie bei /special-rolle",
                    "Welche Rollen wie oft vorkommen, stellt der Host in der Lobby in den Spiel-Einstellungen ein",
                ),
            ),
            TutorialSection(
                "DIE ROLLEN",
                listOf(
                    "Was jede Rolle kann, erklärt dir /special-rolle",
                    "Neutrale spielen für sich: Der **Jester** gewinnt, wenn er rausgewählt wird," +
                        " der **Jackal** will alle anderen ausschalten",
                    "Modifikatoren wie **Lovers** oder **Mini** bekommst du zusätzlich zu deiner Rolle",
                ),
            ),
        ),
        footer = "Fehler gefunden? Sag dem Host Bescheid, was passiert ist.",
    ),
    Tutorial(
        key = "special-rollen-liste",
        label = "Alle Special-Rollen erklärt",
        title = "Alle Rollen aus The Other Roles",
        intro = "Das kann jede Rolle der Mod The Other Roles. Eine einzelne Rolle zeigt dir /special-rolle.",
        sections = SpecialTeam.entries.mapNotNull { team ->
            val roles = SPECIAL_ROLES.values.filter { it.team == team }.sortedBy { it.name }
            if (roles.isEmpty()) return@mapNotNull null
            val title = when (team) {
                SpecialTeam.CREW -> "CREWMATES · ${roles.size}"
                SpecialTeam.IMPOSTOR -> "IMPOSTOR · ${roles.size}"
                SpecialTeam.NEUTRAL -> "NEUTRAL · ${roles.size} · SPIELEN FÜR SICH"
                SpecialTeam.BOTH -> "CREW ODER IMPOSTOR · ${roles.size}"
                SpecialTeam.MODIFIER -> "MODIFIKATOREN · ${roles.size} · ZUSÄTZLICH ZUR ROLLE"
            }
            TutorialSection(title, roles.map { "**${it.name}** – ${it.description}" })
        },
        footer = "Jeder sieht nur seine eigene Rolle.",
    ),
)

fun tutorial(key: String?): Tutorial? = TUTORIALS.firstOrNull { it.key == key }
