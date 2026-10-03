"""Among-Us-Wissen: Vent-Karten, Map-Infos und Rollen-Erklärungen."""

import discord
from discord import app_commands

from crewbot.cogs.base import CrewCog
from crewbot.content.among_us import MAPS
from crewbot.ui.embeds import Palette
from crewbot.ui.views import RoleSelectView

MAP_CHOICES = [app_commands.Choice(name=name, value=name) for name in MAPS]


class KnowledgeCog(CrewCog, name="Among Us Wissen"):
    emoji = "📚"

    @app_commands.command(name="vents", description="Zeigt das Vent-Netzwerk einer Map")
    @app_commands.describe(map_name="Welche Map?")
    @app_commands.choices(map_name=MAP_CHOICES)
    async def vents(self, interaction: discord.Interaction, map_name: str) -> None:
        embed = discord.Embed(title=f"🕳️ Vents: {map_name}", color=Palette.INFO)
        embed.set_image(url=MAPS[map_name].vent_image)
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="map-info", description="Tipps, Kameras und Todesfallen einer Map")
    @app_commands.describe(map_name="Welche Map?")
    @app_commands.choices(map_name=MAP_CHOICES)
    async def map_info(self, interaction: discord.Interaction, map_name: str) -> None:
        game_map = MAPS[map_name]
        embed = discord.Embed(
            title=f"🗺️ MAP INFO: {game_map.name} 🗺️",
            description=game_map.description,
            color=Palette.INFO,
        )
        embed.add_field(name="👁️ Überwachung / Cams", value=game_map.cameras, inline=False)
        embed.add_field(name="☠️ Gefahrenzonen", value=game_map.danger_zones, inline=False)
        embed.add_field(name="📝 Tasks / Besonderheiten", value=game_map.tasks, inline=False)
        embed.set_image(url=game_map.vent_image)
        embed.set_footer(text="Inklusive Vent-Netzwerk auf der Karte")
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="rolle", description="Erklärt dir eine Among Us Rolle")
    async def role(self, interaction: discord.Interaction) -> None:
        await interaction.response.send_message(
            "Wähle eine Rolle aus dem Menü, um ihre Erklärung zu lesen:",
            view=RoleSelectView(),
            ephemeral=True,
        )


async def setup(bot) -> None:
    await bot.add_cog(KnowledgeCog(bot))
