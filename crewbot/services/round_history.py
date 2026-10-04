"""Rundenverlauf: jede ausgewertete Mod-Runde mit allen Details (für Highlights, Rivalen,
Erfolge und den Spieleabend-Recap). „Zurücksetzen“ entfernt eine Runde wieder.

Gespeichert werden die Originaldaten der Mod plus die Zuordnung Ingame-Spieler -> Discord-ID,
wie sie zum Zeitpunkt der Runde galt (spätere /verknuepfen ändern alte Runden nicht).
"""

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from crewbot.services.mod_rounds import ModPlayer, ModRound, ModRoundError
from crewbot.services.storage import JsonStore

MAX_ROUNDS = 1000  # ältere Runden fallen raus


@dataclass
class HistoryRound:
    round: ModRound
    discord_ids: dict[int, int]  # Ingame-PlayerId -> Discord-ID (nur verknüpfte)
    ended_at: datetime

    def user_of(self, player_id: int | None) -> int | None:
        return self.discord_ids.get(player_id) if player_id is not None else None

    def player_of(self, user_id: int) -> ModPlayer | None:
        return next((p for p in self.round.players if self.discord_ids.get(p.player_id) == user_id), None)


class RoundHistoryService:
    def __init__(self, store: JsonStore) -> None:
        self._store = store
        self._entries: list[dict[str, Any]] = store.load().get("rounds", [])
        self._cache: list[HistoryRound] | None = None

    def add(self, round_: ModRound, discord_ids: dict[int, int], ended_at: datetime | None = None) -> HistoryRound:
        ended_at = ended_at or datetime.now(timezone.utc)
        self._entries = [e for e in self._entries if e["game_id"] != round_.game_id]
        self._entries.append(
            {
                "game_id": round_.game_id,
                "ended_at": ended_at.isoformat(),
                "discord_ids": {str(pid): uid for pid, uid in discord_ids.items()},
                "round": round_.raw,
            }
        )
        self._entries = self._entries[-MAX_ROUNDS:]
        self._save()
        return HistoryRound(round_, dict(discord_ids), ended_at)

    def remove(self, game_id: str) -> None:
        before = len(self._entries)
        self._entries = [e for e in self._entries if e["game_id"] != game_id]
        if len(self._entries) != before:
            self._save()

    def rounds(self, since: datetime | None = None, until: datetime | None = None) -> list[HistoryRound]:
        """Alle Runden (älteste zuerst), optional nur in einem Zeitraum."""
        if self._cache is None:
            self._cache = []
            for entry in self._entries:
                try:
                    self._cache.append(
                        HistoryRound(
                            ModRound.from_dict(entry["round"]),
                            {int(pid): int(uid) for pid, uid in entry["discord_ids"].items()},
                            datetime.fromisoformat(entry["ended_at"]),
                        )
                    )
                except (ModRoundError, KeyError, TypeError, ValueError):
                    continue  # kaputter Eintrag – überspringen statt alles zu blockieren
        return [
            r for r in self._cache
            if (since is None or r.ended_at >= since) and (until is None or r.ended_at <= until)
        ]

    def rounds_of(self, user_id: int) -> list[HistoryRound]:
        return [r for r in self.rounds() if user_id in r.discord_ids.values()]

    def _save(self) -> None:
        self._cache = None
        self._store.save({"rounds": self._entries})
