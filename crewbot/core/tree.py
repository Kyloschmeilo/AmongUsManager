"""Globaler Slash-Command-Tree: Server-Pflicht und zentrale Fehlerbehandlung."""

import logging

import discord
from discord import app_commands

from crewbot.ui.embeds import Tone, notify

log = logging.getLogger(__name__)


class CrewTree(app_commands.CommandTree):
    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.guild is None:
            await notify(interaction, "🚫 Dieser Bot funktioniert nur auf dem Server.", Tone.ERROR)
            return False
        return True

    async def on_error(
        self, interaction: discord.Interaction, error: app_commands.AppCommandError
    ) -> None:
        if isinstance(error, app_commands.MissingPermissions):
            text = "🚫 Dafür fehlen dir die Rechte."
        elif isinstance(error, app_commands.CommandOnCooldown):
            text = f"⏳ Langsam! Du kannst das in **{error.retry_after:.0f} s** wieder benutzen."
        elif isinstance(error, app_commands.CheckFailure):
            text = "🚫 Du darfst diesen Befehl hier nicht benutzen."
        else:
            original = getattr(error, "original", error)
            log.error(
                "Fehler in /%s",
                interaction.command.name if interaction.command else "?",
                exc_info=original,
            )
            text = "❌ Unerwarteter Fehler. Bitte versuch es nochmal."

        try:
            await notify(interaction, text, Tone.ERROR)
        except discord.HTTPException:
            pass
