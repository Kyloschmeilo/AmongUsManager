"""Spaß-Befehle."""

import random

import discord
from discord import app_commands

from crewbot.cogs.base import CrewCog
from crewbot.content.among_us import verdict_for
from crewbot.ui.embeds import Palette


class FunCog(CrewCog, name="Spaß"):
    emoji = "🎲"

    @app_commands.command(name="impostor-test", description="Berechnet deine heutige Impostor-Wahrscheinlichkeit")
    async def impostor_test(self, interaction: discord.Interaction) -> None:
        percent = random.randint(1, 100)
        embed = discord.Embed(
            title="🔬 LÜGENDETEKTOR-TEST 🔬",
            description=(
                f"Untersuchung von {interaction.user.mention} abgeschlossen.\n\n"
                f"Das System sagt...\n# 📊 Verdachtsstufe: {percent}%\n\n"
                f"{verdict_for(percent).text}"
            ),
            color=Palette.PURPLE if percent < 75 else Palette.ERROR,
        )
        embed.set_thumbnail(url=interaction.user.display_avatar.url)
        await interaction.response.send_message(embed=embed)


async def setup(bot) -> None:
    await bot.add_cog(FunCog(bot))
