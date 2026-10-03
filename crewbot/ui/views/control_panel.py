"""Among-Us-Steuerpult: Runde (Mute), Meeting (Unmute), verschieben, Runde beenden."""

import discord

from crewbot.config import Channels
from crewbot.services.voice import move_members, voice_channel_of
from crewbot.ui.embeds import Palette, Tone, notify
from crewbot.ui.views.base import PersistentView


class ControlPanelView(PersistentView):
    # custom_ids bleiben gleich, damit bereits gepostete Panels weiter funktionieren

    IDLE_STATUS = "⏸️ Bereit"

    @staticmethod
    def build_embed(status: str = IDLE_STATUS, open_bets: int = 0) -> discord.Embed:
        embed = discord.Embed(
            title="🎙️ Among Us Steuerung",
            description=(
                "Nutze die Buttons, um die Crew in deinem Voice-Channel zu verwalten.\n\n"
                "🤫 **RUNDE** – alle stummschalten\n"
                "🔊 **MEETING** – alle wieder entmuten\n"
                "🚀 **Verschieben** – alle in den Among-Us-Channel holen\n"
                "🏁 **Runde beenden** – Gewinner eintragen, Wetten auswerten, alle entmuten"
            ),
            color=Palette.BRAND,
        )
        embed.add_field(name="📊 Status", value=status, inline=True)
        embed.add_field(name="🎰 Offene Wetten", value=str(open_bets), inline=True)
        return embed

    @classmethod
    async def update_panel(cls, message: discord.Message, bot, status: str) -> None:
        """Aktualisiert Status und Wetten-Zähler im Panel."""
        try:
            await message.edit(embed=cls.build_embed(status, len(bot.bets.active)))
        except discord.HTTPException:
            pass  # Panel gelöscht o.ä. – nicht schlimm

    @discord.ui.button(
        label="RUNDE (Mute)",
        emoji="🤫",
        style=discord.ButtonStyle.danger,
        custom_id="persistent_control_panel:mute",
    )
    async def mute(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._set_mute(interaction, muted=True)

    @discord.ui.button(
        label="MEETING (Unmute)",
        emoji="🔊",
        style=discord.ButtonStyle.success,
        custom_id="persistent_control_panel:unmute",
    )
    async def unmute(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._set_mute(interaction, muted=False)

    @discord.ui.button(
        label="Alle verschieben",
        emoji="🚀",
        style=discord.ButtonStyle.primary,
        custom_id="bt_move",
    )
    async def move_all(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await move_everyone_to_among_us(interaction)

    @discord.ui.button(
        label="Runde beenden",
        emoji="🏁",
        style=discord.ButtonStyle.secondary,
        custom_id="persistent_control_panel:finish",
        row=1,
    )
    async def finish_round(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        from crewbot.ui.views.round_flow import RoundFlowView

        if not interaction.user.guild_permissions.mute_members:
            return await notify(interaction, "🚫 Nur Hosts dürfen eine Runde beenden.", Tone.ERROR)
        voice = voice_channel_of(interaction.user)
        if voice is None:
            return await notify(interaction, "⚠️ Du musst im Voice-Channel der Runde sein!", Tone.WARNING)

        view = RoundFlowView(interaction.user, voice, interaction.message)
        await interaction.response.send_message(embed=view.build_embed(), view=view, ephemeral=True)

    async def _set_mute(self, interaction: discord.Interaction, *, muted: bool) -> None:
        channel = voice_channel_of(interaction.user)
        if channel is None:
            return await notify(interaction, "⚠️ Du musst in einem Voice-Channel sein!", Tone.WARNING)

        # Entmuten hebt auch „taub“ auf (setzt der Auto-Mute während der Runde)
        if muted:
            targets = [m for m in channel.members if m.voice and not m.voice.mute]
        else:
            targets = [m for m in channel.members if m.voice and (m.voice.mute or m.voice.deaf)]
        if not targets:
            state = "gemutet" if muted else "entmutet"
            return await notify(interaction, f"ℹ️ Alle Spieler sind bereits {state}.")

        headline = "🤫 **Runde gestartet!**" if muted else "🔊 **Meeting gestartet!**"
        await notify(interaction, f"{headline} Multi-Bot-{'Mute' if muted else 'Unmute'} läuft...")

        bot = self.bot_of(interaction)
        report = await (bot.workers.set_mute(targets, True) if muted else bot.workers.release(targets))
        if channel.id == Channels.AMONG_US_VOICE:
            bot.voice_status.refresh(muted)
        await self.update_panel(interaction.message, bot, "🤫 Runde läuft" if muted else "🎙️ Meeting")

        await interaction.edit_original_response(
            embed=discord.Embed(
                description=f"{headline}\n{report.describe('gemutet' if muted else 'entmutet')}",
                color=Palette.ERROR if muted else Palette.SUCCESS,
            )
        )


async def move_everyone_to_among_us(interaction: discord.Interaction) -> None:
    """Verschiebt alle aus dem Voice des Nutzers in den Among-Us-Channel."""
    source = voice_channel_of(interaction.user)
    target = interaction.client.get_channel(Channels.AMONG_US_VOICE)
    if source is None:
        return await notify(interaction, "❌ Du bist in keinem Voice-Channel!", Tone.ERROR)
    if not isinstance(target, discord.VoiceChannel):
        return await notify(interaction, "❌ Der Among-Us-Channel wurde nicht gefunden.", Tone.ERROR)

    await interaction.response.defer(ephemeral=True, thinking=True)
    report = await move_members(source.members, target)
    await notify(interaction, report.describe(f"nach {target.mention} verschoben"), Tone.SUCCESS)
