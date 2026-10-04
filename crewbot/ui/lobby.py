"""Embed der automatischen Lobby-Ankündigung (siehe services/lobby.py)."""

import discord

from crewbot.services.lobby import LobbyEvent
from crewbot.ui.embeds import Palette, progress_bar


def build_lobby_embed(event: LobbyEvent, host: str) -> discord.Embed:
    if event.state == "closed":
        embed = discord.Embed(
            title="⬛ Lobby geschlossen",
            description=f"~~`{event.code}`~~ • Danke fürs Mitspielen!",
            color=discord.Color.dark_grey(),
        )
    else:
        started = event.state == "started"
        embed = discord.Embed(
            title="🚀 Runde läuft" if started else "🎮 Among-Us-Lobby offen",
            description=f"## `{event.code}`" + ("\n🔒 Wetten geschlossen bis zum Rundenende" if started else ""),
            color=Palette.BRAND if started else Palette.SUCCESS,
        )
        embed.add_field(name="🗺️ Map", value=event.map, inline=True)
        embed.add_field(name="👑 Host", value=host, inline=True)
        if event.max_players:
            embed.add_field(
                name=f"👥 Spieler {event.players}/{event.max_players}",
                value=progress_bar(event.players, event.max_players),
                inline=False,
            )
    embed.set_footer(text="Aktualisiert sich automatisch • CrewStats-Mod")
    embed.timestamp = discord.utils.utcnow()
    return embed
