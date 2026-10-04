"""Erfolge (Achievements) aus den Mod-Runden – Texte, Ziele und XP-Belohnung.

Drei Arten:
  stat   -> Gesamtwert aus `GameStats` erreicht (z.B. 25 Kills), Fortschritt in /erfolge sichtbar
  round  -> besonderer Moment in einer einzelnen Runde
  streak -> mehrere Runden in Folge
Die Prüf-Logik für `round`/`streak` steht in `services/achievements.py` (gleiche id).
Neue Erfolge einfach hier ergänzen; ids nie umbenennen (sind gespeichert).
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Achievement:
    id: str
    emoji: str
    name: str
    description: str
    xp: int
    kind: str  # stat | round | streak
    stat: str | None = None  # Feld in GameStats (nur kind="stat")
    target: int = 1

    @property
    def label(self) -> str:
        return f"{self.emoji} {self.name}"


STREAK_LENGTH = 5

ACHIEVEMENTS: tuple[Achievement, ...] = (
    # Gesamtziele
    Achievement("first_game", "🎮", "Frischling", "Deine erste aufgezeichnete Runde", 5, "stat", "games", 1),
    Achievement("regular", "🎖️", "Stammgast", "50 Runden gespielt", 25, "stat", "games", 50),
    Achievement("first_kill", "🔪", "Erster Kill", "Deinen ersten Crewmate erwischt", 5, "stat", "kills", 1),
    Achievement("serial_killer", "🩸", "Serienkiller", "25 Kills insgesamt", 25, "stat", "kills", 25),
    Achievement("deceiver", "👑", "Meister der Täuschung", "10 Siege als Impostor", 30, "stat", "impostor_wins", 10),
    Achievement("crew_veteran", "🛡️", "Crew-Veteran", "25 Siege als Crewmate", 25, "stat", "crew_wins", 25),
    Achievement("task_machine", "🧰", "Task-Maschine", "200 Tasks erledigt", 20, "stat", "tasks_completed", 200),
    Achievement("bloodhound", "🕵️", "Spürnase", "25 richtige Stimmen als Crewmate", 20, "stat", "votes_correct", 25),
    # Momente in einer Runde
    Achievement("double_kill", "⚡", "Blitzkrieg", "2 Kills innerhalb von 30 Sekunden", 15, "round"),
    Achievement("invisible", "🎭", "Unsichtbar", "Als Impostor gewonnen, ohne eine einzige Stimme zu bekommen", 20, "round"),
    Achievement("lone_wolf", "🦊", "Einsamer Wolf", "Als letzter Impostor gewonnen, nachdem dein Partner raus war", 15, "round"),
    Achievement("sharp_eye", "🔎", "Scharfsinn", "In einer Runde 2+ richtige Stimmen und keine falsche", 10, "round"),
    # Serien
    Achievement("survivor_streak", "🩹", "Unkaputtbar", f"{STREAK_LENGTH} Runden in Folge überlebt", 20, "streak"),
    Achievement("win_streak", "🔥", "Siegesserie", f"{STREAK_LENGTH} Siege in Folge", 20, "streak"),
)

ACHIEVEMENTS_BY_ID: dict[str, Achievement] = {a.id: a for a in ACHIEVEMENTS}
