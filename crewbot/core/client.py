"""Die Haupt-Bot-Klasse: verbindet Services, Views und Cogs."""

import logging

import discord
from discord.ext import commands

from crewbot import config
from crewbot.core.tree import CrewTree
from crewbot.services.audit import AuditLog
from crewbot.services.automute import AutoMuteService
from crewbot.services.betting import BetService
from crewbot.services.events import EventService
from crewbot.services.game_settings import GameSettingsService
from crewbot.services.game_stats import GameStatsService
from crewbot.services.levels import LevelService
from crewbot.services.links import LinkService
from crewbot.services.minecraft import MinecraftService
from crewbot.services.mod_rounds import ModRoundService, ModSettings
from crewbot.services.rounds import RoundService
from crewbot.services.storage import JsonStore
from crewbot.services.voice import VoiceStatusUpdater
from crewbot.services.workers import WorkerManager
from crewbot.ui.views import ControlPanelView, EventSignupView, RoleSelectView
from crewbot.ui.views.mod_round import UndoRoundButton

log = logging.getLogger(__name__)

EXTENSIONS = (
    "crewbot.cogs.voice",
    "crewbot.cogs.knowledge",
    "crewbot.cogs.progression",
    "crewbot.cogs.rounds",
    "crewbot.cogs.events",
    "crewbot.cogs.fun",
    "crewbot.cogs.amongus_mod",
    "crewbot.cogs.minecraft",
    "crewbot.cogs.admin",
    "crewbot.cogs.help",
)


class AmongUsBot(commands.Bot):
    def __init__(self) -> None:
        super().__init__(
            command_prefix=commands.when_mentioned,
            intents=discord.Intents.all(),
            tree_cls=CrewTree,
        )
        # Services – überall erreichbar über bot.<name> bzw. interaction.client.<name>
        self.levels = LevelService(JsonStore(config.LEVELS_FILE))
        self.bets = BetService(JsonStore(config.BETS_FILE), self.levels)
        self.events = EventService(JsonStore(config.EVENTS_FILE))
        self.rounds = RoundService(self.levels, self.bets)
        self.audit = AuditLog(self, config.Channels.LOG)
        # Among-Us-Mod
        self.links = LinkService(JsonStore(config.LINKS_FILE))
        self.game_stats = GameStatsService(JsonStore(config.GAME_STATS_FILE))
        self.mod_settings = ModSettings(JsonStore(config.MOD_CONFIG_FILE))
        self.game_settings = GameSettingsService(JsonStore(config.GAME_SETTINGS_FILE))
        self.mod_rounds = ModRoundService(
            JsonStore(config.MOD_ROUNDS_FILE), self.levels, self.bets, self.rounds, self.links, self.game_stats
        )
        # Minecraft-Plugin (CoreSMP): Rohdaten kommen im Inbox-Channel an, Ankündigungen gehen in den /dc-Channel
        self.minecraft = MinecraftService(
            JsonStore(config.MINECRAFT_FILE), config.Channels.MINECRAFT, config.Channels.MINECRAFT_INBOX
        )
        self.workers = WorkerManager(self, config.WORKER_TOKENS)
        self.voice_status = VoiceStatusUpdater(self, config.Channels.AMONG_US_VOICE)
        self.automute = AutoMuteService(self.workers, self.links, self.voice_status, config.Channels.AMONG_US_VOICE)
        self.command_ids: dict[str, int] = {}

    async def setup_hook(self) -> None:
        # Persistente Views: Buttons alter Nachrichten funktionieren auch nach Neustarts
        for view in (ControlPanelView(), EventSignupView(), RoleSelectView()):
            self.add_view(view)
        self.add_dynamic_items(UndoRoundButton)  # "Zurücksetzen"-Buttons unter Mod-Runden

        for extension in EXTENSIONS:
            await self.load_extension(extension)

        await self.workers.start()

        synced = await self.tree.sync()
        self.command_ids = {command.name: command.id for command in synced}
        log.info("⚙️ %d Slash-Commands synchronisiert.", len(synced))

    async def on_ready(self) -> None:
        log.info("✅ Eingeloggt als %s", self.user)
        self.voice_status.refresh()

    async def close(self) -> None:
        await self.workers.close()
        await super().close()

    def mention_command(self, name: str) -> str:
        """Klickbare Befehls-Erwähnung (</name:id>), sonst einfach /name."""
        command_id = self.command_ids.get(name)
        return f"</{name}:{command_id}>" if command_id else f"/{name}"
