"""Auto-Mute: setzt Mute/Taub im Voice passend zur Spielphase, die die CrewStats-Mod meldet.

Ablauf: Mod -> Discord-Webhook (JSON im Nachrichtentext) -> Bot schaltet um.
  tasks   -> Lebende stumm + taub, Tote reden miteinander
  meeting -> Lebende reden, Tote stumm (hören aber zu)
  end     -> alle frei (Spielende, Lobby, Mod-Schalter aus)
Tot ist, wer in der `dead`-Liste steht und verknüpft ist – alle anderen zählen als lebend.
Die Mod schickt die Toten nur beim Phasenwechsel, ein Kill mitten in der Runde ändert also
nichts am Voice (sonst würden die Discord-Symbole den Tod verraten).
Gemutet wird im Voice-Channel des Spielers mit der Mod (über /verknuepfen gefunden),
sonst im zuletzt benutzten bzw. im Among-Us-Channel.

JSON-Format = mod/CrewStats/AutoMute.cs – beide Seiten gemeinsam ändern!
"""

import asyncio
import json
import logging
from dataclasses import dataclass

import discord

from crewbot.services.links import LinkService
from crewbot.services.voice import ActionReport, VoiceLike, VoiceStatusUpdater
from crewbot.services.workers import WorkerManager

log = logging.getLogger(__name__)

PHASES = ("tasks", "meeting", "end")


@dataclass(frozen=True)
class AutoMuteEvent:
    phase: str  # tasks | meeting | end
    sender: str  # Friendcode des Spielers mit der Mod
    dead: frozenset[str]  # Friendcodes der toten Spieler (Stand beim Phasenwechsel)

    @classmethod
    def parse(cls, text: str) -> "AutoMuteEvent | None":
        """Liest die Nachricht der Mod; None, wenn es keine Auto-Mute-Nachricht ist."""
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return None
        if not isinstance(data, dict) or data.get("crewstats") != "automute" or data.get("phase") not in PHASES:
            return None
        dead = data.get("dead") or []
        return cls(
            phase=data["phase"],
            sender=str(data.get("sender") or ""),
            dead=frozenset(str(code) for code in dead if isinstance(code, str)),
        )


@dataclass(frozen=True)
class AutoMuteResult:
    channel: VoiceLike
    report: ActionReport  # wie viele Mitglieder geändert wurden


def target_state(phase: str, *, dead: bool) -> tuple[bool, bool]:
    """(stumm, taub) für ein Mitglied. Wer nicht verknüpft ist, zählt als lebend."""
    if phase == "tasks":
        return (False, False) if dead else (True, True)  # Tote reden, Lebende hören nichts
    if phase == "meeting":
        return (True, False) if dead else (False, False)  # Tote hören nur zu
    return False, False


class AutoMuteService:
    def __init__(
        self,
        workers: WorkerManager,
        links: LinkService,
        voice_status: VoiceStatusUpdater,
        fallback_channel_id: int,
    ) -> None:
        self._workers = workers
        self._links = links
        self._voice_status = voice_status
        self._fallback_channel_id = fallback_channel_id
        self._last_channel_id: int | None = None
        self._lock = asyncio.Lock()
        self._sequence = 0

    async def apply(self, guild: discord.Guild, event: AutoMuteEvent) -> AutoMuteResult | None:
        """Setzt die Mutes. Kommen Phasen schnell hintereinander, gewinnt immer die neueste."""
        self._sequence += 1
        sequence = self._sequence
        async with self._lock:
            if sequence != self._sequence:
                return None  # inzwischen ist eine neuere Phase angekommen

            channel = self._channel_for(guild, event.sender)
            if channel is None:
                return None
            self._last_channel_id = channel.id

            dead_ids = {uid for code in event.dead if (uid := self._links.user_for(code))}
            changes: list[tuple[discord.Member, dict[str, bool]]] = []
            for member in channel.members:
                if member.bot or member.voice is None:
                    continue
                mute, deafen = target_state(event.phase, dead=member.id in dead_ids)
                fields = {}
                if member.voice.mute != mute:
                    fields["mute"] = mute
                if member.voice.deaf != deafen:
                    fields["deafen"] = deafen
                if fields:
                    changes.append((member, fields))

            report = await self._workers.edit_voice(changes)
            if channel.id == self._fallback_channel_id:
                self._voice_status.refresh(event.phase == "tasks")
            return AutoMuteResult(channel, report)

    def _channel_for(self, guild: discord.Guild, sender: str) -> VoiceLike | None:
        # 1. Voice-Channel des Spielers mit der Mod
        user_id = self._links.user_for(sender) if sender else None
        member = guild.get_member(user_id) if user_id else None
        if member is not None and member.voice is not None and member.voice.channel is not None:
            return member.voice.channel

        # 2. zuletzt benutzter Channel (z.B. Host hat den Voice am Spielende schon verlassen), 3. Among-Us-Channel
        for channel_id in (self._last_channel_id, self._fallback_channel_id):
            channel = guild.get_channel(channel_id) if channel_id else None
            if isinstance(channel, (discord.VoiceChannel, discord.StageChannel)):
                return channel
        return None
