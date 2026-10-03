"""Voice-Hilfen: Spieler verschieben und den Status im Channel-Namen anzeigen."""

import asyncio
import logging
from collections.abc import Iterable
from dataclasses import dataclass

import discord

log = logging.getLogger(__name__)

VoiceLike = discord.VoiceChannel | discord.StageChannel


@dataclass(frozen=True)
class ActionReport:
    """Wie viele Aktionen (Mute, Move, ...) geklappt haben und wie viele nicht."""

    succeeded: int
    failed: int

    @classmethod
    def from_results(cls, results: Iterable[object]) -> "ActionReport":
        results = list(results)
        failed = sum(isinstance(r, BaseException) for r in results)
        return cls(len(results) - failed, failed)

    def describe(self, verb: str) -> str:
        text = f"✅ **{self.succeeded}** Spieler {verb}."
        if self.failed:
            text += f"\n⚠️ Bei **{self.failed}** Spieler(n) hat es nicht geklappt (fehlende Rechte?)."
        return text


def voice_channel_of(user: discord.abc.User) -> VoiceLike | None:
    if isinstance(user, discord.Member) and user.voice:
        return user.voice.channel
    return None


async def move_members(members: Iterable[discord.Member], target: VoiceLike) -> ActionReport:
    movers = [m for m in members if m.voice and m.voice.channel != target]
    results = await asyncio.gather(
        *(m.move_to(target) for m in movers), return_exceptions=True
    )
    return ActionReport.from_results(results)


class VoiceStatusUpdater:
    """Zeigt im Namen des Among-Us-Channels an, ob gerade Runde oder Meeting ist.

    Discord erlaubt nur ~2 Umbenennungen pro 10 Minuten. Deshalb läuft immer
    höchstens ein Umbenennungs-Task, der am Ende den *neuesten* Wunschnamen
    setzt, statt dass sich viele Anfragen im Rate-Limit stauen.
    """

    EMPTY_NAME = "⬜ Amongus⬜"
    ROUND_NAME = "🤫RUNDE🤫"
    MEETING_NAME = "🎙️MEETING🎙️"

    def __init__(self, client: discord.Client, channel_id: int) -> None:
        self._client = client
        self._channel_id = channel_id
        self._desired: str | None = None
        self._task: asyncio.Task | None = None

    def refresh(self, muted: bool | None = None) -> None:
        """Namen neu berechnen. `muted` überschreibt den Zustand aus dem Cache."""
        channel = self._client.get_channel(self._channel_id)
        if not isinstance(channel, discord.VoiceChannel):
            return
        self._desired = self._name_for(channel, muted)
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._apply(channel))

    def _name_for(self, channel: discord.VoiceChannel, muted: bool | None) -> str:
        players = [m for m in channel.members if not m.bot]
        if not players:
            return self.EMPTY_NAME
        if muted is None:
            muted = any(m.voice and m.voice.mute for m in players)
        return self.ROUND_NAME if muted else self.MEETING_NAME

    async def _apply(self, channel: discord.VoiceChannel) -> None:
        current = channel.name
        while self._desired and self._desired != current:
            target = self._desired
            try:
                await channel.edit(name=target)
            except discord.HTTPException as exc:
                log.warning("Channel konnte nicht umbenannt werden: %s", exc)
                return
            current = target
