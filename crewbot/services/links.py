"""Verknüpfung Among-Us-Account (Friend Code) <-> Discord-Account."""

import re

from crewbot.services.storage import JsonStore

FRIEND_CODE_PATTERN = re.compile(r"^[a-z0-9]{2,20}#\d{4}$")


def normalize_friend_code(code: str) -> str:
    return code.strip().lower()


def is_valid_friend_code(code: str) -> bool:
    return bool(FRIEND_CODE_PATTERN.fullmatch(normalize_friend_code(code)))


class LinkService:
    """Ein Friend Code gehört genau einem Discord-Account; ein Discord-Account darf mehrere haben."""

    def __init__(self, store: JsonStore) -> None:
        self._store = store
        self._links: dict[str, int] = {code: int(uid) for code, uid in store.load().items()}

    def user_for(self, friend_code: str) -> int | None:
        return self._links.get(normalize_friend_code(friend_code))

    def codes_of(self, user_id: int) -> list[str]:
        return [code for code, uid in self._links.items() if uid == user_id]

    def by_user(self) -> dict[int, list[str]]:
        """Alle Verknüpfungen, gruppiert nach Discord-ID."""
        grouped: dict[int, list[str]] = {}
        for code, uid in self._links.items():
            grouped.setdefault(uid, []).append(code)
        return grouped

    def link(self, friend_code: str, user_id: int) -> None:
        self._links[normalize_friend_code(friend_code)] = user_id
        self._save()

    def unlink(self, user_id: int) -> list[str]:
        removed = self.codes_of(user_id)
        for code in removed:
            del self._links[code]
        self._save()
        return removed

    def _save(self) -> None:
        self._store.save({code: uid for code, uid in self._links.items()})
