"""Among-Us-Statistiken pro Spieler (aus der Mod)."""

from dataclasses import asdict, dataclass, fields

from crewbot.services.storage import JsonStore


@dataclass
class GameStats:
    games: int = 0
    crew_games: int = 0
    crew_wins: int = 0
    impostor_games: int = 0
    impostor_wins: int = 0
    kills: int = 0
    deaths: int = 0  # getötet
    ejected: int = 0
    survived: int = 0
    tasks_completed: int = 0
    meetings_called: int = 0  # Notfall-Knopf
    bodies_reported: int = 0
    votes_correct: int = 0  # als Crewmate einen Impostor gewählt
    votes_wrong: int = 0  # als Crewmate einen Crewmate gewählt

    @classmethod
    def from_dict(cls, data: dict[str, int]) -> "GameStats":
        known = {f.name for f in fields(cls)}
        return cls(**{k: int(v) for k, v in data.items() if k in known})

    def to_dict(self) -> dict[str, int]:
        return asdict(self)

    def apply(self, delta: dict[str, int], sign: int = 1) -> None:
        for key, value in delta.items():
            if hasattr(self, key):
                setattr(self, key, max(0, getattr(self, key) + sign * value))

    @property
    def wins(self) -> int:
        return self.crew_wins + self.impostor_wins

    @staticmethod
    def rate(part: int, total: int) -> str:
        return f"{part / total:.0%}" if total else "–"


class GameStatsService:
    def __init__(self, store: JsonStore) -> None:
        self._store = store
        self._stats: dict[int, GameStats] = {
            int(uid): GameStats.from_dict(data) for uid, data in store.load().items()
        }

    def get(self, user_id: int) -> GameStats:
        return self._stats.get(user_id) or GameStats()

    def apply(self, deltas: dict[int, dict[str, int]], sign: int = 1) -> None:
        """Addiert (sign=1) oder entfernt (sign=-1) die Werte einer Runde."""
        for user_id, delta in deltas.items():
            self._stats.setdefault(user_id, GameStats()).apply(delta, sign)
        self._store.save({str(uid): stats.to_dict() for uid, stats in self._stats.items()})
