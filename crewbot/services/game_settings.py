"""Die Lobbyeinstellungen der letzten Runde, die die CrewStats-Mod beim Rundenstart schickt.

JSON-Format = mod/CrewStats/SettingsReporter.cs – beide Seiten gemeinsam ändern!
Gespeichert wird immer nur der neueste Stand (überschreibt den alten).
"""

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from crewbot.services.storage import JsonStore

SETTINGS_FILE_NAME = "crewstats-settings.json"


class GameSettingsError(Exception):
    """Fehler mit einer Nachricht, die direkt angezeigt werden kann."""


@dataclass
class RoleRate:
    count: int
    chance: int

    @property
    def active(self) -> bool:
        return self.count > 0 and self.chance > 0


@dataclass
class GameSettings:
    captured_at: datetime
    lobby_code: str
    map: str
    players: int
    max_players: int
    num_impostors: int
    options: dict[str, dict[str, Any]]  # "ints" | "floats" | "bools" -> Name -> Wert
    roles: dict[str, RoleRate] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "GameSettings":
        if data.get("crewstats") != "settings":
            raise GameSettingsError("Das sind keine Rundeneinstellungen.")
        try:
            # .NET schickt bis zu 7 Nachkommastellen und "Z" – Python 3.10 versteht nur 6 und "+00:00"
            raw_time = re.sub(r"(\.\d{6})\d+", r"\1", str(data["capturedAt"])).replace("Z", "+00:00")
            captured = datetime.fromisoformat(raw_time)
            if captured.tzinfo is None:
                captured = captured.replace(tzinfo=timezone.utc)
            return cls(
                captured_at=captured,
                lobby_code=str(data.get("lobbyCode", "")),
                map=str(data.get("map", "?")),
                players=int(data.get("players", 0)),
                max_players=int(data.get("maxPlayers", 0)),
                num_impostors=int(data.get("numImpostors", 0)),
                options={kind: dict(data.get(kind) or {}) for kind in ("ints", "floats", "bools")},
                roles={
                    name: RoleRate(int(rate.get("count", 0)), int(rate.get("chance", 0)))
                    for name, rate in (data.get("roles") or {}).items()
                },
            )
        except (KeyError, TypeError, ValueError, AttributeError) as exc:
            raise GameSettingsError(f"Ungültige Rundeneinstellungen: {exc}") from exc

    def value(self, kind: str, key: str) -> Any | None:
        return self.options.get(kind, {}).get(key)


class GameSettingsService:
    def __init__(self, store: JsonStore) -> None:
        self._store = store
        raw = store.load()
        self._raw: dict[str, Any] | None = raw or None
        self._latest: GameSettings | None = None
        if self._raw:
            try:
                self._latest = GameSettings.from_dict(self._raw)
            except GameSettingsError:
                self._raw = None

    @property
    def latest(self) -> GameSettings | None:
        return self._latest

    def update(self, data: dict[str, Any]) -> GameSettings:
        """Prüft und speichert neue Einstellungen (ersetzt die alten)."""
        settings = GameSettings.from_dict(data)
        self._latest, self._raw = settings, data
        self._store.save(data)
        return settings
