"""Basisklasse für alle Feature-Module."""

from typing import TYPE_CHECKING, ClassVar

from discord.ext import commands

from crewbot.services.levels import LevelService

if TYPE_CHECKING:
    from crewbot.core.client import AmongUsBot


class CrewCog(commands.Cog):
    """Gemeinsame Basis: typisierter Zugriff auf Bot und Services.

    `emoji` und der Cog-Name (`class X(CrewCog, name="...")`) erscheinen in /hilfe.
    """

    emoji: ClassVar[str] = "📦"

    def __init__(self, bot: "AmongUsBot") -> None:
        self.bot = bot

    @property
    def levels(self) -> LevelService:
        return self.bot.levels
