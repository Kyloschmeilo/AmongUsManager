"""Minecraft-Kopplung: /dc und Empfang der Ankündigungen vom CoreSMP-Plugin (CoreHold)."""

import asyncio
import logging

import discord
from discord import app_commands
from discord.ext import commands

from crewbot.cogs.base import CrewCog
from crewbot.services.minecraft import McPayloadError, parse_payload
from crewbot.ui.minecraft import build_event_embed
from crewbot.ui.views.minecraft_panel import MinecraftPanelView

log = logging.getLogger(__name__)


class MinecraftCog(CrewCog, name="Minecraft"):
    emoji = "⛏️"

    def __init__(self, bot) -> None:
        super().__init__(bot)
        self._send_lock = asyncio.Lock()  # Reihenfolge der Ankündigungen beibehalten

    @app_commands.command(name="dc", description="Minecraft-Ankündigungen: Kanal und Nachrichten einstellen")
    @app_commands.default_permissions(administrator=True)
    @app_commands.checks.has_permissions(administrator=True)
    async def dc(self, interaction: discord.Interaction) -> None:
        view = MinecraftPanelView(self.bot.minecraft, self.bot.audit, interaction.user)
        await interaction.response.send_message(embed=view.build_embed(), view=view, ephemeral=True)

    # ==========================================
    # EMPFANG DER PLUGIN-NACHRICHTEN
    # ==========================================

    @commands.Cog.listener()
    async def on_ready(self) -> None:
        # Liegt der Webhook noch im alten Channel, zieht er um – die URL im Plugin bleibt gleich
        try:
            await self.bot.minecraft.move_webhook_to_inbox(self.bot)
        except discord.HTTPException as exc:
            log.warning("Minecraft-Webhook konnte nicht verschoben werden (Recht „Webhooks verwalten“?): %s", exc)

    @commands.Cog.listener()
    async def on_message(self, message: discord.Message) -> None:
        service = self.bot.minecraft
        if message.guild is None or message.webhook_id is None or message.webhook_id != service.webhook_id:
            return
        if not message.content:
            log.warning("Minecraft-Nachricht ohne Text – ist der Message Content Intent aktiv?")
            return
        try:
            payload = parse_payload(message.content)
        except McPayloadError as exc:
            log.warning("Minecraft-Nachricht unlesbar: %s", exc)
            return
        service.mark_received()

        embeds = [
            build_event_embed(event, payload.server, message.created_at)
            for event in payload.events
            if service.is_enabled(event.type)
        ]
        if embeds:
            channel = self.bot.get_channel(service.channel_id)
            if not isinstance(channel, discord.abc.Messageable):
                log.warning("Minecraft-Channel %s nicht gefunden – /dc prüfen", service.channel_id)
                return  # Rohdaten bleiben im Log-Channel liegen
            try:
                async with self._send_lock:
                    for start in range(0, len(embeds), 10):  # max. 10 Embeds pro Nachricht
                        await channel.send(
                            embeds=embeds[start : start + 10], allowed_mentions=discord.AllowedMentions.none()
                        )
            except discord.HTTPException as exc:
                log.warning("Minecraft-Ankündigung konnte nicht gesendet werden: %s", exc)
                return

        try:
            await message.delete()
        except discord.HTTPException:
            pass  # fehlendes Recht „Nachrichten verwalten“ – nicht schlimm


async def setup(bot) -> None:
    await bot.add_cog(MinecraftCog(bot))
