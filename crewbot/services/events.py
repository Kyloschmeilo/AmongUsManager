"""Offene Events: Erinnerung, Anwesenheit im Voice und Abschluss nach Event-Ende."""

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from crewbot.config import Rules
from crewbot.services.storage import JsonStore


@dataclass
class OpenEvent:
    message_id: int
    channel_id: int
    starts_at: datetime
    ends_at: datetime
    reminded: bool = False
    # user_id -> Minuten, die der Spieler während des Events im Voice war
    attendance: dict[int, int] = field(default_factory=dict)

    @property
    def remind_at(self) -> datetime:
        return self.starts_at - timedelta(minutes=Rules.EVENT_REMINDER_MINUTES)

    def is_running(self, now: datetime) -> bool:
        return self.starts_at <= now < self.ends_at

    def attended(self, user_id: int) -> bool:
        return self.attendance.get(user_id, 0) >= Rules.EVENT_MIN_ATTENDANCE_MINUTES

    @classmethod
    def from_dict(cls, message_id: int, data: dict[str, Any]) -> "OpenEvent":
        ends_at = datetime.fromisoformat(data["ends_at"])
        starts_at = (
            datetime.fromisoformat(data["starts_at"])
            if "starts_at" in data
            else ends_at - timedelta(hours=Rules.EVENT_DURATION_HOURS)
        )
        return cls(
            message_id=message_id,
            channel_id=int(data["channel_id"]),
            starts_at=starts_at,
            ends_at=ends_at,
            reminded=bool(data.get("reminded", False)),
            attendance={int(uid): int(m) for uid, m in data.get("attendance", {}).items()},
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "channel_id": self.channel_id,
            "starts_at": self.starts_at.isoformat(),
            "ends_at": self.ends_at.isoformat(),
            "reminded": self.reminded,
            "attendance": {str(uid): m for uid, m in self.attendance.items()},
        }


class EventService:
    def __init__(self, store: JsonStore) -> None:
        self._store = store
        self._events: dict[int, OpenEvent] = {
            int(mid): OpenEvent.from_dict(int(mid), data) for mid, data in store.load().items()
        }

    def get(self, message_id: int) -> OpenEvent | None:
        return self._events.get(message_id)

    def add(self, message_id: int, channel_id: int, starts_at: datetime, ends_at: datetime, now: datetime) -> OpenEvent:
        event = OpenEvent(message_id, channel_id, starts_at, ends_at)
        # Wird das Event kurzfristig erstellt, ist eine Erinnerung überflüssig
        event.reminded = now >= event.remind_at
        self._events[message_id] = event
        self.save()
        return event

    def needing_reminder(self, now: datetime) -> list[OpenEvent]:
        return [e for e in self._events.values() if not e.reminded and e.remind_at <= now < e.starts_at]

    def running(self, now: datetime) -> list[OpenEvent]:
        return [e for e in self._events.values() if e.is_running(now)]

    def due(self, now: datetime) -> list[OpenEvent]:
        """Alle Events, deren Ende erreicht ist."""
        return [e for e in self._events.values() if e.ends_at <= now]

    def mark_reminded(self, message_id: int) -> None:
        if event := self._events.get(message_id):
            event.reminded = True
            self.save()

    def record_attendance(self, message_id: int, user_ids: Iterable[int], minutes: int = 1) -> None:
        """Schreibt allen übergebenen Spielern Anwesenheits-Minuten gut (ohne zu speichern)."""
        if event := self._events.get(message_id):
            for user_id in user_ids:
                event.attendance[user_id] = event.attendance.get(user_id, 0) + minutes

    def remove(self, message_id: int) -> None:
        if self._events.pop(message_id, None):
            self.save()

    def save(self) -> None:
        self._store.save({str(e.message_id): e.to_dict() for e in self._events.values()})
