/*
 * Inhalt von /tutorial: Anleitungen für Spieler, die ein Admin in einen Channel oder Thread postet.
 * „/befehl“ im Text wird automatisch anklickbar (ein Test prüft, dass es die Befehle gibt).
 * Das Special-Rollen-Tutorial beschreibt die Rollen-Mod SpecialRoles – mit deren README gemeinsam ändern.
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

private const val SPECIAL_ROLES_RELEASES = "https://github.com/Kyloschmeilo/SpecialRoles/releases"

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
                    "/create-event – plant einen Spieleabend mit Anmeldung",
                    "/bet – setz bis zu ${Rules.MAX_BET} XP auf Crew oder Impostor. Gewinnt dein Team, bekommst du" +
                        " das ${Rules.BET_PAYOUT_FACTOR}-Fache. Sobald die Runde läuft, ist das Wettbüro zu",
                    "/rollenwunsch – wünsch dir eine Rolle für deine nächste Runde" +
                        " (${Rules.ROLE_WISH_COST} XP, ${Rules.ROLE_WISHES_PER_EVENING}× pro Spieleabend)",
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
                    "/rolle und /special-rolle – erklären jede Rolle aus dem Spiel und aus unserer Rollen-Mod",
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
        label = "Special-Rollen",
        title = "Special-Rollen: 30 neue Rollen",
        intro = "Unsere eigene Mod **SpecialRoles** bringt 30 neue Rollen ins Spiel: 17 für die Crew, 11 für" +
            " Impostor und 2 neutrale, dazu den Modifikator Flash.",
        sections = listOf(
            TutorialSection(
                "INSTALLIEREN",
                listOf(
                    "1. Auf [GitHub]($SPECIAL_ROLES_RELEASES) beim neuesten Release `SpecialRoles-Installer.bat`" +
                        " herunterladen",
                    "2. Doppelklicken. Warnt Windows, klick **Weitere Informationen → Trotzdem ausführen**",
                    "3. Enter drücken – der Installer findet Among Us und installiert BepInEx und die Mod",
                    "Der erste Spielstart dauert 1–2 Minuten. Updates installiert die Mod beim Spielstart selbst," +
                        " danach Among Us neu starten. Deinstallieren: Installer starten und `2` wählen",
                ),
            ),
            TutorialSection(
                "WICHTIG",
                listOf(
                    "**Alle Spieler der Lobby** brauchen die Mod in derselben Version",
                    "Nur für Among Us auf Steam (64-Bit)",
                ),
            ),
            TutorialSection(
                "IM SPIEL",
                listOf(
                    "Der Host verteilt die Rollen beim Rundenstart fair per Zufall – zusätzlich zu deiner Spielrolle." +
                        " Crewmates bekommen Crew- oder Neutral-Rollen, Impostor nur Impostor-Rollen",
                    "Deine Rolle steht unter deinem Namen und oben in der Aufgabenliste. Nur du siehst sie",
                    "Fähigkeiten liegen auf einem eigenen Knopf neben dem Kill-Knopf, Taste `F`" +
                        " (Sheriff `Q`, Kameramann zusätzlich `G`)",
                    "Hinweise wie „Dein Schild hat dich gerettet“ erscheinen oben in der Bildschirmmitte – nie im Chat",
                ),
            ),
            TutorialSection(
                "DIE ROLLEN",
                listOf(
                    "Was jede Rolle kann, erklärt dir /special-rolle",
                    "Neutral: Der **Narr** gewinnt allein, wenn er rausgewählt wird." +
                        " Als **Vergesslicher** übernimmst du an einer Leiche deren Rolle",
                ),
            ),
        ),
        footer = "Fehler gefunden? Sag dem Host Bescheid, was passiert ist.",
    ),
    Tutorial(
        key = "special-rollen-liste",
        label = "Alle Special-Rollen erklärt",
        title = "Alle Special-Rollen auf einen Blick",
        intro = "Das kann jede Rolle der Rollen-Mod. Fähigkeiten liegen auf Taste `F` (Sheriff `Q`," +
            " Kameramann zusätzlich `G`). Eine einzelne Rolle mit allen Details zeigt dir /special-rolle.",
        sections = SpecialTeam.entries.mapNotNull { team ->
            val roles = SPECIAL_ROLES.values.filter { it.team == team }
            if (roles.isEmpty()) return@mapNotNull null
            val title = when (team) {
                SpecialTeam.CREW -> "CREWMATES · ${roles.size}"
                SpecialTeam.IMPOSTOR -> "IMPOSTOR · ${roles.size}"
                SpecialTeam.NEUTRAL -> "NEUTRAL · ${roles.size} · GEWINNEN ALLEIN"
                SpecialTeam.MODIFIER -> "MODIFIKATOR · ZUSÄTZLICH ZUR ROLLE"
            }
            TutorialSection(title, roles.map { "**${it.name}** – ${it.description}" })
        },
        footer = "Jeder sieht nur seine eigene Rolle.",
    ),
)

fun tutorial(key: String?): Tutorial? = TUTORIALS.firstOrNull { it.key == key }
