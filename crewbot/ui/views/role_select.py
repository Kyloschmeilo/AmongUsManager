"""Rollen-Erklärungen per Dropdown, inklusive Log im Rollen-Log-Channel."""

import logging

import discord

from crewbot.config import Channels
from crewbot.content.among_us import ROLES, AmongUsRole
from crewbot.ui.embeds import Palette
from crewbot.ui.views.base import PersistentView

log = logging.getLogger(__name__)


class RoleSelect(discord.ui.Select):
    def __init__(self) -> None:
        super().__init__(
            placeholder="Wähle eine Rolle aus...",
            options=[
                discord.SelectOption(
                    label=role.name,
                    value=role.name,
                    emoji=role.emoji,
                    description=f"Team {role.team.label}",
                )
                for role in ROLES.values()
            ],
            custom_id="persistent_role_dropdown",
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        role = ROLES[self.values[0]]
        embed = discord.Embed(
            title=f"{role.emoji} {role.name}",
            description=role.description,
            color=Palette.for_team(role.team),
        )
        embed.add_field(name="Team", value=role.team.label)
        await interaction.response.send_message(embed=embed, ephemeral=True)
        await self._log_selection(interaction, role)

    @staticmethod
    async def _log_selection(interaction: discord.Interaction, role: AmongUsRole) -> None:
        channel = interaction.client.get_channel(Channels.ROLE_LOG)
        if not isinstance(channel, discord.abc.Messageable):
            return
        embed = discord.Embed(
            title="🔍 Rollen-Auswahl",
            description=(
                f"{interaction.user.mention} ({interaction.user.display_name})"
                f" hat sich die Rolle **{role.name}** anzeigen lassen."
            ),
            color=Palette.INFO,
        )
        embed.set_footer(text=f"User-ID: {interaction.user.id}")
        try:
            await channel.send(embed=embed)
        except discord.HTTPException as exc:
            log.warning("Rollen-Log konnte nicht gesendet werden: %s", exc)


class RoleSelectView(PersistentView):
    def __init__(self) -> None:
        super().__init__()
        self.add_item(RoleSelect())
