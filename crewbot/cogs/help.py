"""/hilfe: automatisch generierte Übersicht aller Befehle."""

import discord
from discord import app_commands

from crewbot.cogs.base import CrewCog
from crewbot.ui.embeds import Palette


class HelpCog(CrewCog, name="Hilfe"):
    emoji = "📖"

    @app_commands.command(name="hilfe", description="Zeigt alle Befehle des Bots")
    async def help(self, interaction: discord.Interaction) -> None:
        embed = discord.Embed(
            title="📖 Crew-Handbuch",
            description="Alle Befehle auf einen Blick. Einfach anklicken zum Benutzen!",
            color=Palette.BRAND,
        )
        for cog in self.bot.cogs.values():
            if not isinstance(cog, CrewCog) or cog is self:
                continue
            lines = [
                f"{self.bot.mention_command(cmd.name)}{' 🔒' if cmd.default_permissions else ''}"
                f" – {cmd.description}"
                for cmd in cog.get_app_commands()
            ]
            if lines:
                embed.add_field(name=f"{cog.emoji} {cog.qualified_name}", value="\n".join(lines), inline=False)
        embed.set_footer(text="🔒 = nur für Hosts/Admins")
        await interaction.response.send_message(embed=embed, ephemeral=True)


async def setup(bot) -> None:
    await bot.add_cog(HelpCog(bot))
