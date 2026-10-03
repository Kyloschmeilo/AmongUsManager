"""Admin-Werkzeuge."""

import discord
from discord import app_commands

from crewbot.cogs.base import CrewCog
from crewbot.ui.views import AdminPanelView


class AdminCog(CrewCog, name="Admin"):
    emoji = "🛠️"

    @app_commands.command(name="admin-panel", description="XP/Level eines Spielers ändern oder zurücksetzen")
    @app_commands.describe(user="Welcher Spieler?")
    @app_commands.default_permissions(administrator=True)
    @app_commands.checks.has_permissions(administrator=True)
    async def admin_panel(self, interaction: discord.Interaction, user: discord.Member) -> None:
        view = AdminPanelView(self.levels, self.bot.audit, user, interaction.user)
        await interaction.response.send_message(embed=view.build_embed(), view=view, ephemeral=True)


async def setup(bot) -> None:
    await bot.add_cog(AdminCog(bot))
