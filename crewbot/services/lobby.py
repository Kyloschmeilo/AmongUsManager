"""Lobby-Ankündigung: Die Mod meldet als Host den Zustand der Lobby, der Bot hält dazu eine
Nachricht im Codes-Channel aktuell (offen → Runde läuft → wieder offen … → geschlossen).

Nebenbei steuert der Rundenstart das Wettbüro: „started“ sperrt neue Wetten, sobald die Lobby
danach wieder offen/geschlossen ist, öffnet es nach kurzer Wartezeit (falls kein Ergebnis kam –
normalerweise öffnet schon die Rundenauswertung).

JSON-Format = mod/CrewStats/LobbyReporter.cs – beide Seiten gemeinsam ändern!
"""

import asyncio
import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass

import discord

from crewbot.config import Rules
from crewbot.services.betting import BetService
from crewbot.services.links import LinkService

log = logging.getLogger(__name__)

STATES = ("open", "started", "closed")


@dataclass(frozen=True)
class LobbyEvent:
    state: str  # open | started | closed
    code: str
    map: str
    players: int
    max_players: int
    host: str  # Ingame-Name des Hosts
    sender: str  # Friendcode des Hosts

    @classmethod
    def parse(cls, text: str) -> "LobbyEvent | None":
        """Liest die Nachricht der Mod; None, wenn es keine Lobby-Nachricht ist."""
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return None
        if not isinstance(data, dict) or data.get("crewstats") != "lobby" or data.get("state") not in STATES:
            return None
        try:
            return cls(
                state=data["state"],
                code=str(data.get("code") or ""),
                map=str(data.get("map") or "?"),
                players=int(data.get("players") or 0),
                max_players=int(data.get("max") or 0),
                host=str(data.get("host") or ""),
                sender=str(data.get("sender") or ""),
            )
        except (TypeError, ValueError):
            return None


EmbedBuilder = Callable[[LobbyEvent, str], discord.Embed]


class LobbyAnnouncer:
    MIN_EDIT_SECONDS = 2.0  # Discord mag keine Dauer-Edits

    def __init__(
        self,
        client: discord.Client,
        channel_id: int,
        links: LinkService,
        bets: BetService,
        build_embed: EmbedBuilder,
    ) -> None:
        self._client = client
        self._channel_id = channel_id
        self._links = links
        self._bets = bets
        self._build_embed = build_embed
        self._state: str | None = None
        self._message: discord.Message | None = None
        self._shown: LobbyEvent | None = None
        self._pending: LobbyEvent | None = None
        self._task: asyncio.Task | None = None
        self._last_edit = 0.0

    def apply(self, event: LobbyEvent) -> None:
        """Neuer Zustand von der Mod. Das Wettbüro reagiert sofort, die Nachricht gedrosselt."""
        if event.state == "started":
            self._bets.lock()
        elif self._state == "started":
            self._bets.unlock(after_seconds=Rules.BET_UNLOCK_GRACE_SECONDS)
        self._state = event.state

        self._pending = event
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run())

    async def _run(self) -> None:
        while self._pending is not None:
            wait = self._last_edit + self.MIN_EDIT_SECONDS - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            event, self._pending = self._pending, None
            try:
                await self._show(event)
            except discord.HTTPException as exc:
                log.warning("Lobby-Nachricht konnte nicht aktualisiert werden: %s", exc)
            self._last_edit = time.monotonic()

    async def _show(self, event: LobbyEvent) -> None:
        # Neue Lobby (anderer Code): die alte Nachricht als geschlossen markieren
        if self._message is not None and self._shown is not None and event.code != self._shown.code:
            await self._edit(self._closed(self._shown))
            self._message = None

        if event.state == "closed":
            if self._message is not None:
                await self._edit(event)
            self._message, self._shown = None, None
            return

        if self._message is None or not await self._edit(event):
            channel = self._client.get_channel(self._channel_id)
            if not isinstance(channel, discord.abc.Messageable):
                log.warning("Codes-Channel %s nicht gefunden – keine Lobby-Ankündigung", self._channel_id)
                return
            self._message = await channel.send(
                embed=self._build_embed(event, self._host_text(event)),
                allowed_mentions=discord.AllowedMentions.none(),
            )
        self._shown = event

    async def _edit(self, event: LobbyEvent) -> bool:
        """Bearbeitet die aktuelle Nachricht. False, wenn sie inzwischen gelöscht wurde."""
        try:
            await self._message.edit(embed=self._build_embed(event, self._host_text(event)))
            return True
        except discord.NotFound:
            self._message = None
            return False

    def _host_text(self, event: LobbyEvent) -> str:
        user_id = self._links.user_for(event.sender) if event.sender else None
        return f"<@{user_id}>" if user_id else (event.host or "?")

    @staticmethod
    def _closed(event: LobbyEvent) -> LobbyEvent:
        return LobbyEvent("closed", event.code, event.map, 0, event.max_players, event.host, event.sender)
