"""Basisklassen für alle Views."""

import logging
from typing import TYPE_CHECKING, Any

import discord

from crewbot.ui.embeds import Tone, notify

if TYPE_CHECKING:
    from crewbot.core.client import AmongUsBot

log = logging.getLogger(__name__)


class CrewView(discord.ui.View):
    """Basis-View: zeigt bei Fehlern eine saubere Meldung statt 'Interaktion fehlgeschlagen'."""

    @staticmethod
    def bot_of(interaction: discord.Interaction) -> "AmongUsBot":
        return interaction.client  # type: ignore[return-value]

    async def on_error(
        self,
        interaction: discord.Interaction,
        error: Exception,
        item: discord.ui.Item[Any],
    ) -> None:
        log.exception("Fehler in %s (%s)", type(self).__name__, item, exc_info=error)
        await notify(interaction, "❌ Da ist etwas schiefgelaufen. Versuch es nochmal!", Tone.ERROR)


class PersistentView(CrewView):
    """View ohne Timeout. Muss in `setup_hook` per `bot.add_view` registriert werden."""

    def __init__(self) -> None:
        super().__init__(timeout=None)


class PrivateView(CrewView):
    """View, die nur von ihrem Besitzer bedient werden darf."""

    def __init__(self, owner_id: int, *, timeout: float = 180) -> None:
        super().__init__(timeout=timeout)
        self.owner_id = owner_id

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.owner_id:
            return True
        await notify(interaction, "🚫 Das ist nicht dein Menü.", Tone.ERROR)
        return False
