"""Embeds für Minecraft-Ereignisse vom CoreSMP-Plugin (plus Beispiele für die /dc-Vorschau)."""

from collections.abc import Callable
from datetime import datetime
from urllib.parse import quote

import discord
from discord.utils import escape_markdown

from crewbot.content.minecraft import (
    DEATH_CAUSES,
    DEFAULT_DEATH,
    DIMENSIONS,
    MOBS,
    McEventType,
    ability_name,
)
from crewbot.services.minecraft import McEvent
from crewbot.ui.embeds import Palette

LEAVE_COLOR = discord.Color.dark_grey()
DEATH_COLOR = discord.Color.dark_red()
FIRST_JOIN_COLOR = discord.Color.teal()

LEAVE_TEXTS = {
    "kicked": "🥾 {name} wurde vom Server geworfen",
    "timed_out": "📡 {name} hat die Verbindung verloren",
    "stop": "🛑 {name} ist raus – der Server wurde gestoppt",
}


# Köpfe per Name statt UUID: klappt auch auf Offline-Mode-Servern
def head_url(player: str) -> str:
    return f"https://mc-heads.net/avatar/{quote(player)}/64"


def body_url(player: str) -> str:
    return f"https://mc-heads.net/body/{quote(player)}/128"


def _bold(text: str) -> str:
    return f"**{escape_markdown(text)}**"


def _duration(seconds: int) -> str:
    hours, rest = divmod(int(seconds), 3600)
    minutes = rest // 60
    if hours:
        return f"{hours} Std. {minutes} Min."
    return f"{minutes} Min." if minutes else "unter 1 Min."


def _online(event: McEvent) -> str | None:
    online, max_players = event.get("online"), event.get("max")
    if online is None:
        return None
    return f"🟢 {online}/{max_players} online" if max_players else f"🟢 {online} online"


def killer_text(event: McEvent) -> str | None:
    """Der Schuldige im Dativ: Spieler/benannte Mobs mit Namen, sonst "einem **Zombie**"."""
    if event.get("killer"):
        return _bold(event.get("killer"))
    killer_type = event.get("killerType")
    if not killer_type:
        return None
    article, name = MOBS.get(killer_type, ("", killer_type.replace("_", " ").title()))
    return f"{article} {_bold(name)}".strip()


# ==========================================
# EINZELNE ARTEN
# ==========================================


def _join(event: McEvent) -> tuple[discord.Embed, list[str | None]]:
    name = _bold(event.player)
    if event.get("first"):
        embed = discord.Embed(
            title="🎉 Neu auf dem Server!",
            description=f"{name} ist zum **ersten Mal** da – herzlich willkommen!",
            color=FIRST_JOIN_COLOR,
        )
        embed.set_thumbnail(url=body_url(event.player))
    else:
        embed = discord.Embed(description=f"📥 {name} hat den Server betreten", color=Palette.SUCCESS)
    return embed, [_online(event)]


def _leave(event: McEvent) -> tuple[discord.Embed, list[str | None]]:
    reason = event.get("reason")
    text = LEAVE_TEXTS.get(reason, "📤 {name} hat den Server verlassen")
    embed = discord.Embed(description=text.format(name=_bold(event.player)), color=LEAVE_COLOR)
    playtime = event.get("playtime")
    footer = [f"⏱️ {_duration(playtime)} online" if playtime is not None else None]
    if reason != "stop":
        footer.append(_online(event))
    return embed, footer


def _death(event: McEvent) -> tuple[discord.Embed, list[str | None]]:
    cause = DEATH_CAUSES.get(event.get("cause"))
    known = cause is not None
    cause = cause or DEFAULT_DEATH
    killer = killer_text(event)
    if killer is None:
        text = cause.text
    elif cause.by_killer:
        text = cause.by_killer.format(killer=killer)
    else:
        text = f"{cause.text} – dank {killer}"

    description = f"{cause.emoji} {_bold(event.player)} {text}"
    if not known and killer is None and event.get("message"):
        description += f"\n> *{escape_markdown(event.get('message'))}*"  # Originaltext von Minecraft
    embed = discord.Embed(description=description, color=DEATH_COLOR)
    if event.get("weapon"):
        embed.add_field(name="✨ Special Item", value=_bold(event.get("weapon")))
    return embed, [DIMENSIONS.get(event.get("dimension"))]


def _craft(event: McEvent) -> tuple[discord.Embed, list[str | None]]:
    item = event.get("item") or event.get("itemKey") or "?"
    embed = discord.Embed(
        title="✨ Special Item gecraftet!",
        description=(
            f"{_bold(event.player)} hat {_bold(item)} gecraftet!\n"
            "Davon gibt es nur **ein einziges Exemplar** auf dem Server."
        ),
        color=Palette.GOLD,
    )
    if event.get("ability"):
        embed.add_field(name="🔮 Fähigkeit", value=ability_name(event.get("ability")))
    embed.set_thumbnail(url=body_url(event.player))
    return embed, []


_BUILDERS: dict[McEventType, Callable[[McEvent], tuple[discord.Embed, list[str | None]]]] = {
    McEventType.JOIN: _join,
    McEventType.LEAVE: _leave,
    McEventType.DEATH: _death,
    McEventType.CRAFT: _craft,
}


def build_event_embed(event: McEvent, server: str, when: datetime | None = None) -> discord.Embed:
    embed, footer = _BUILDERS[event.type](event)
    embed.set_author(name=event.player, icon_url=head_url(event.player))
    embed.set_footer(text=" • ".join(part for part in [*footer, f"⛏️ {server}"] if part))
    embed.timestamp = when or discord.utils.utcnow()
    return embed


def sample_events() -> list[McEvent]:
    """Beispiele für die Vorschau in /dc."""
    return [
        McEvent(McEventType.JOIN, "Steve", {"online": 3, "max": 20}),
        McEvent(McEventType.JOIN, "Alex", {"first": True, "online": 4, "max": 20}),
        McEvent(McEventType.LEAVE, "Steve", {"reason": "disconnected", "playtime": 4380, "online": 3, "max": 20}),
        McEvent(
            McEventType.DEATH, "Alex",
            {"cause": "player_attack", "killerType": "player", "killer": "Steve", "weapon": "Lichtschwert", "dimension": "normal"},
        ),
        McEvent(McEventType.DEATH, "Steve", {"cause": "explosion", "killerType": "creeper", "dimension": "normal"}),
        McEvent(McEventType.CRAFT, "Steve", {"item": "Lichtschwert", "itemKey": "lichtschwert", "ability": "lichtschwert"}),
    ]
