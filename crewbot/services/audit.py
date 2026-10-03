"""Audit-Log: protokolliert Admin- und Host-Aktionen im Log-Channel."""

import logging

import discord

log = logging.getLogger(__name__)


class AuditLog:
    def __init__(self, client: discord.Client, channel_id: int) -> None:
        self._client = client
        self._channel_id = channel_id

    async def log(
        self,
        actor: discord.abc.User,
        title: str,
        text: str,
        color: discord.Color = discord.Color.blurple(),
    ) -> None:
        channel = self._client.get_channel(self._channel_id)
        if not isinstance(channel, discord.abc.Messageable):
            log.warning("Log-Channel %s nicht gefunden", self._channel_id)
            return

        embed = discord.Embed(title=f"📝 {title}", description=text, color=color)
        embed.set_author(name=actor.display_name, icon_url=actor.display_avatar.url)
        embed.set_footer(text=f"Ausgeführt von {actor} • ID {actor.id}")
        embed.timestamp = discord.utils.utcnow()
        try:
            # Erwähnungen im Log sollen niemanden anpingen
            await channel.send(embed=embed, allowed_mentions=discord.AllowedMentions.none())
        except discord.HTTPException as exc:
            log.warning("Audit-Log konnte nicht gesendet werden: %s", exc)
