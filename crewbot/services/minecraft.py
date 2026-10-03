"""Minecraft-Kopplung: Ereignisse des CoreSMP-Plugins (CoreHold) lesen und die /dc-Einstellungen speichern.

Das Plugin schickt seine Ereignisse als JSON über einen Webhook im Log-Channel. Der Bot löscht diese
Rohdaten wieder und postet daraus Embeds im eingestellten Channel – aber nur die eingeschalteten Arten.
JSON-Format = `DiscordBridge.java` / `DiscordListener.java` im CoreSMP-Projekt. Beide Seiten gemeinsam ändern!
"""

import json
import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import discord

from crewbot.content.minecraft import McEventType
from crewbot.services.storage import JsonStore

log = logging.getLogger(__name__)


class McPayloadError(Exception):
    """Die Webhook-Nachricht ist kein gültiges Ereignis-Paket."""


class McSetupError(Exception):
    """Der Webhook für das Plugin konnte nicht eingerichtet werden."""


@dataclass(frozen=True)
class McEvent:
    type: McEventType
    player: str
    data: dict[str, Any] = field(default_factory=dict)  # alle Felder aus dem JSON

    def get(self, key: str, default: Any = None) -> Any:
        return self.data.get(key, default)


@dataclass(frozen=True)
class McPayload:
    server: str
    events: list[McEvent]


def parse_payload(text: str) -> McPayload:
    """Liest den Nachrichtentext des Plugin-Webhooks: {"v": 1, "server": "...", "events": [...]}."""
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        raise McPayloadError(f"kein gültiges JSON ({exc})") from exc
    if not isinstance(raw, dict) or not isinstance(raw.get("events"), list):
        raise McPayloadError("Feld 'events' fehlt")

    events = []
    for item in raw["events"]:
        if not isinstance(item, dict):
            continue
        try:
            type_ = McEventType(item.get("type"))
        except ValueError:
            continue  # Art aus einer neueren Plugin-Version – überspringen
        events.append(McEvent(type_, str(item.get("player") or "?"), item))
    return McPayload(str(raw.get("server") or "Minecraft"), events)


class MinecraftService:
    """Webhook, Ziel-Channel und eingeschaltete Nachrichtenarten (data/minecraft.json)."""

    WEBHOOK_NAME = "CoreSMP Plugin"

    def __init__(self, store: JsonStore, default_channel_id: int, inbox_channel_id: int) -> None:
        self._store = store
        self._inbox_channel_id = inbox_channel_id  # hier landen die Rohdaten des Plugins
        data = store.load()
        self.webhook_id: int | None = data.get("webhook_id")
        self._webhook_url: str | None = data.get("webhook_url")
        self.channel_id: int = data.get("channel_id") or default_channel_id
        enabled = data.get("enabled", [t.value for t in McEventType])
        self.enabled: set[McEventType] = {t for t in McEventType if t.value in enabled}
        self.last_received: datetime | None = None  # nur zur Anzeige in /dc, wird nicht gespeichert

    # ---------- Einstellungen ----------

    def is_enabled(self, type_: McEventType) -> bool:
        return type_ in self.enabled

    def set_channel(self, channel_id: int) -> None:
        self.channel_id = channel_id
        self._save()

    def set_enabled(self, types: set[McEventType]) -> None:
        self.enabled = set(types)
        self._save()

    def mark_received(self) -> None:
        self.last_received = discord.utils.utcnow()

    # ---------- Webhook fürs Plugin ----------

    async def webhook_url(self, client: discord.Client, *, reason: str, renew: bool = False) -> str:
        """URL für die config.yml des Plugins. Legt den Webhook bei Bedarf (neu) an – eine alte URL wird dann ungültig."""
        if self.webhook_id and not renew:
            try:
                webhook = await client.fetch_webhook(self.webhook_id)
                url = webhook.url if webhook.token else self._webhook_url
                if url:
                    return url
            except discord.NotFound:
                pass  # im Discord gelöscht -> neu anlegen

        channel = client.get_channel(self._inbox_channel_id)
        if not isinstance(channel, discord.TextChannel):
            raise McSetupError(f"Log-Channel {self._inbox_channel_id} nicht gefunden.")
        if self.webhook_id:
            try:
                old = await client.fetch_webhook(self.webhook_id)
                await old.delete(reason=reason)
            except discord.HTTPException:
                pass
        webhook = await channel.create_webhook(name=self.WEBHOOK_NAME, reason=reason)  # Forbidden -> Aufrufer
        self.webhook_id, self._webhook_url = webhook.id, webhook.url
        self._save()
        return webhook.url

    async def move_webhook_to_inbox(self, client: discord.Client) -> None:
        """Verschiebt einen bestehenden Webhook in den Inbox-Channel. Die URL bleibt dabei gleich."""
        if not self.webhook_id:
            return
        channel = client.get_channel(self._inbox_channel_id)
        if not isinstance(channel, discord.TextChannel):
            return
        webhook = await client.fetch_webhook(self.webhook_id)
        if webhook.channel_id != channel.id:
            await webhook.edit(channel=channel, reason="Minecraft-Rohdaten in den Log-Channel verschoben")
            log.info("Minecraft-Webhook nach #%s verschoben", channel.name)

    def _save(self) -> None:
        self._store.save(
            {
                "webhook_id": self.webhook_id,
                "webhook_url": self._webhook_url,
                "channel_id": self.channel_id,
                "enabled": [t.value for t in McEventType if t in self.enabled],
            }
        )
