"""Einheitliche Farben, Rückmeldungen und kleine Anzeige-Helfer."""

from enum import Enum

import discord

from crewbot.content.among_us import Team


class Palette:
    BRAND = discord.Color.from_rgb(197, 17, 17)  # Among-Us-Rot
    SUCCESS = discord.Color.from_rgb(46, 204, 113)
    ERROR = discord.Color.from_rgb(231, 76, 60)
    WARNING = discord.Color.from_rgb(241, 196, 15)
    INFO = discord.Color.from_rgb(52, 152, 219)
    GOLD = discord.Color.gold()
    PURPLE = discord.Color.purple()
    EVENT = discord.Color.from_rgb(255, 0, 255)
    CARD = discord.Color.from_rgb(245, 245, 247)

    @classmethod
    def for_team(cls, team: Team) -> discord.Color:
        return cls.SUCCESS if team is Team.CREW else cls.ERROR


class Tone(Enum):
    INFO = "info"
    SUCCESS = "success"
    WARNING = "warning"
    ERROR = "error"

    @property
    def color(self) -> discord.Color:
        return {
            Tone.INFO: Palette.INFO,
            Tone.SUCCESS: Palette.SUCCESS,
            Tone.WARNING: Palette.WARNING,
            Tone.ERROR: Palette.ERROR,
        }[self]


async def notify(
    interaction: discord.Interaction,
    text: str,
    tone: Tone = Tone.INFO,
    *,
    ephemeral: bool = True,
) -> None:
    """Kurze farbige Rückmeldung – funktioniert egal ob schon geantwortet wurde."""
    embed = discord.Embed(description=text, color=tone.color)
    if interaction.response.is_done():
        await interaction.followup.send(embed=embed, ephemeral=ephemeral)
    else:
        await interaction.response.send_message(embed=embed, ephemeral=ephemeral)


def progress_bar(value: int, total: int, length: int = 10) -> str:
    filled = min(length, int(value / total * length)) if total else 0
    return "🟦" * filled + "⬜" * (length - filled)
