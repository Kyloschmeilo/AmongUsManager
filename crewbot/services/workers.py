"""Worker-Bots: zusätzliche Bot-Accounts, die das Muten parallel übernehmen.

Discord begrenzt, wie schnell ein einzelner Bot Mitglieder bearbeiten darf.
Mit mehreren Bots werden die Mutes gleichmäßig verteilt und laufen parallel.
"""

import asyncio
import logging

import discord

from crewbot.services.voice import ActionReport

log = logging.getLogger(__name__)


class WorkerBot(discord.Client):
    def __init__(self, number: int) -> None:
        intents = discord.Intents.default()
        intents.guilds = True
        intents.voice_states = True
        super().__init__(intents=intents)
        self.number = number

    async def on_ready(self) -> None:
        log.info("🤖 Worker #%d online als %s", self.number, self.user)


class WorkerManager:
    def __init__(self, main_bot: discord.Client, tokens: list[str]) -> None:
        self._main_bot = main_bot
        self._tokens = tokens
        self._workers: list[WorkerBot] = []
        self._tasks: list[asyncio.Task] = []

    @property
    def ready_clients(self) -> list[discord.Client]:
        """Haupt-Bot + alle Worker, die gerade online sind."""
        return [self._main_bot, *(w for w in self._workers if w.is_ready())]

    async def start(self) -> None:
        log.info("🔍 Starte %d Worker-Bot(s)...", len(self._tokens))
        for number, token in enumerate(self._tokens, start=1):
            worker = WorkerBot(number)
            self._workers.append(worker)
            self._tasks.append(
                asyncio.create_task(self._run(worker, token), name=f"worker-{number}")
            )

    async def close(self) -> None:
        for worker in self._workers:
            await worker.close()

    async def set_mute(self, members: list[discord.Member], muted: bool) -> ActionReport:
        """Mutet/entmutet die Mitglieder, verteilt auf alle verfügbaren Bots."""
        return await self.edit_voice([(member, {"mute": muted}) for member in members])

    async def release(self, members: list[discord.Member]) -> ActionReport:
        """Hebt Server-Mute und -Taub auf (nur bei denen, die es gerade haben)."""
        changes = []
        for member in members:
            if member.voice is None:
                continue
            fields = {name: False for name, active in (("mute", member.voice.mute), ("deafen", member.voice.deaf)) if active}
            if fields:
                changes.append((member, fields))
        return await self.edit_voice(changes)

    async def edit_voice(self, changes: list[tuple[discord.Member, dict[str, bool]]]) -> ActionReport:
        """Setzt mute/deafen pro Mitglied – ein Aufruf pro Person, reihum auf alle Bots verteilt."""
        if not changes:
            return ActionReport(0, 0)
        clients = self.ready_clients
        results = await asyncio.gather(
            *(
                self._edit_one(clients[index % len(clients)], member, fields)
                for index, (member, fields) in enumerate(changes)
            ),
            return_exceptions=True,
        )
        return ActionReport.from_results(results)

    @staticmethod
    async def _edit_one(client: discord.Client, member: discord.Member, fields: dict[str, bool]) -> None:
        # Der Worker braucht sein *eigenes* Member-Objekt, damit er seine eigene
        # Verbindung (und sein eigenes Rate-Limit) benutzt.
        guild = client.get_guild(member.guild.id)
        target = guild.get_member(member.id) if guild else None
        await (target or member).edit(**fields)

    @staticmethod
    async def _run(worker: WorkerBot, token: str) -> None:
        try:
            await worker.start(token)
        except discord.LoginFailure:
            log.error("❌ Worker #%d: Ungültiger Token!", worker.number)
        except Exception:
            log.exception("❌ Worker #%d ist abgestürzt", worker.number)
