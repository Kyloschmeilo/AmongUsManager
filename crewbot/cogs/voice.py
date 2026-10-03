"""Voice & Steuerung: Control Panel, Verschieben, Lobby-Code."""

import logging

import discord
from discord import app_commands
from discord.ext import commands

from crewbot.cogs.base import CrewCog
from crewbot.config import Channels
from crewbot.ui.embeds import Tone, notify
from crewbot.ui.views import ControlPanelView
from crewbot.ui.views.control_panel import move_everyone_to_among_us

log = logging.getLogger(__name__)


class VoiceCog(CrewCog, name="Voice & Steuerung"):
    emoji = "🎙️"

    @app_commands.command(name="panel", description="Postet das Among Us Control Panel")
    @app_commands.default_permissions(mute_members=True)
    @app_commands.checks.has_permissions(mute_members=True)
    async def panel(self, interaction: discord.Interaction) -> None:
        embed = ControlPanelView.build_embed(open_bets=len(self.bot.bets.active))
        await interaction.channel.send(embed=embed, view=ControlPanelView())
        await notify(interaction, "✅ Panel gesendet.", Tone.SUCCESS)

    @app_commands.command(name="moveall", description="Verschiebt alle aus deinem Voice in den Among-Us-Channel")
    async def move_all(self, interaction: discord.Interaction) -> None:
        await move_everyone_to_among_us(interaction)

    @app_commands.command(name="code", description="Teilt den Lobby-Code im Code-Channel")
    @app_commands.describe(code="Der Lobby-Code aus Among Us (z.B. ABCDEF)")
    async def share_code(
        self, interaction: discord.Interaction, code: app_commands.Range[str, 1, 20]
    ) -> None:
        channel = self.bot.get_channel(Channels.CODES)
        if not isinstance(channel, discord.TextChannel):
            return await notify(interaction, "❌ Code-Channel wurde nicht gefunden.", Tone.ERROR)
        await channel.send(f"🎮 **Code:** `{code.upper()}` - Host: {interaction.user.mention}")
        await notify(interaction, f"✅ Code in {channel.mention} geteilt.", Tone.SUCCESS)

    @commands.Cog.listener()
    async def on_voice_state_update(
        self, member: discord.Member, before: discord.VoiceState, after: discord.VoiceState
    ) -> None:
        # 1. Auto-Unmute: wer den Unmute-Channel neu betritt, wird entmutet (und ggf. nicht mehr taub)
        joined = after.channel is not None and before.channel != after.channel
        if joined and after.channel.id == Channels.AUTO_UNMUTE_VOICE and (after.mute or after.deaf):
            try:
                await member.edit(mute=False, deafen=False)
            except discord.HTTPException as exc:
                log.warning("Konnte %s nicht entmuten: %s", member.display_name, exc)

        # 2. Status im Namen des Among-Us-Channels aktualisieren
        touched = {c.id for c in (before.channel, after.channel) if c is not None}
        if Channels.AMONG_US_VOICE in touched:
            self.bot.voice_status.refresh()


async def setup(bot) -> None:
    await bot.add_cog(VoiceCog(bot))
