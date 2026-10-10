/*
 * Erfolge (Achievements) aus den Mod-Runden – Texte, Ziele und XP-Belohnung.
 *
 * Drei Arten:
 *   stat   -> Gesamtwert aus `GameStats` erreicht (z.B. 25 Kills), Fortschritt in /erfolge sichtbar
 *   round  -> besonderer Moment in einer einzelnen Runde
 *   streak -> mehrere Runden in Folge
 * Die Prüf-Logik für `round`/`streak` steht in `services/AchievementService.kt` (gleiche id).
 * Neue Erfolge einfach hier ergänzen; ids nie umbenennen (sind gespeichert).
 */
package de.amongusmanager.crewbot.content

enum class AchievementKind { STAT, ROUND, STREAK }

data class Achievement(
    val id: String,
    val emoji: String,
    val name: String,
    val description: String,
    val xp: Int,
    val kind: AchievementKind,
    val stat: String? = null, // Feld in GameStats (nur kind=STAT)
    val target: Int = 1,
) {
    val label: String get() = "$emoji $name"
}

const val STREAK_LENGTH = 5

val ACHIEVEMENTS: List<Achievement> = listOf(
    // Gesamtziele
    Achievement("first_game", "🎮", "Frischling", "Deine erste aufgezeichnete Runde", 5, AchievementKind.STAT, "games", 1),
    Achievement("regular", "🎖️", "Stammgast", "50 Runden gespielt", 25, AchievementKind.STAT, "games", 50),
    Achievement("first_kill", "🔪", "Erster Kill", "Deinen ersten Crewmate erwischt", 5, AchievementKind.STAT, "kills", 1),
    Achievement("serial_killer", "🩸", "Serienkiller", "25 Kills insgesamt", 25, AchievementKind.STAT, "kills", 25),
    Achievement(
        "deceiver", "👑", "Meister der Täuschung", "10 Siege als Impostor", 30, AchievementKind.STAT, "impostor_wins", 10,
    ),
    Achievement("crew_veteran", "🛡️", "Crew-Veteran", "25 Siege als Crewmate", 25, AchievementKind.STAT, "crew_wins", 25),
    Achievement(
        "task_machine", "🧰", "Task-Maschine", "200 Tasks erledigt", 20, AchievementKind.STAT, "tasks_completed", 200,
    ),
    Achievement(
        "bloodhound", "🕵️", "Spürnase", "25 richtige Stimmen als Crewmate", 20, AchievementKind.STAT, "votes_correct", 25,
    ),
    // Momente in einer Runde
    Achievement("double_kill", "⚡", "Blitzkrieg", "2 Kills innerhalb von 30 Sekunden", 15, AchievementKind.ROUND),
    Achievement(
        "invisible", "🎭", "Unsichtbar", "Als Impostor gewonnen, ohne eine einzige Stimme zu bekommen", 20,
        AchievementKind.ROUND,
    ),
    Achievement(
        "lone_wolf", "🦊", "Einsamer Wolf", "Als letzter Impostor gewonnen, nachdem dein Partner raus war", 15,
        AchievementKind.ROUND,
    ),
    Achievement(
        "sharp_eye", "🔎", "Scharfsinn", "In einer Runde 2+ richtige Stimmen und keine falsche", 10, AchievementKind.ROUND,
    ),
    // Serien
    Achievement(
        "survivor_streak", "🩹", "Unkaputtbar", "$STREAK_LENGTH Runden in Folge überlebt", 20, AchievementKind.STREAK,
    ),
    Achievement("win_streak", "🔥", "Siegesserie", "$STREAK_LENGTH Siege in Folge", 20, AchievementKind.STREAK),
)

val ACHIEVEMENTS_BY_ID: Map<String, Achievement> = ACHIEVEMENTS.associateBy { it.id }
