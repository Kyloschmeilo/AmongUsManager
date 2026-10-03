"""Fortschritt: Profil, Crew-Card und Leaderboard (inkl. Medaillen im Nickname)."""

import asyncio
import logging

import discord
from discord import app_commands

from crewbot.cogs.base import CrewCog
from crewbot.config import Rules
from crewbot.services.levels import PlayerStats, next_rank
from crewbot.ui.embeds import Palette, Tone, notify, progress_bar

log = logging.getLogger(__name__)

MEDALS = ("🥇", "🥈", "🥉")
MAX_NICK_LENGTH = 32
_medal_lock = asyncio.Lock()  # zwei /leaderboard gleichzeitig dürfen sich nicht in die Quere kommen


def strip_medal(name: str) -> str:
    """Entfernt alle Medaillen am Namensanfang (auch mehrere, z.B. „🥇 🥈 Name“)."""
    stripped = True
    while stripped:
        stripped = False
        for medal in MEDALS:
            if name.startswith(medal):
                name = name[len(medal):].lstrip()
                stripped = True
    return name


class ProgressionCog(CrewCog, name="Level & Fortschritt"):
    emoji = "📈"

    @app_commands.command(name="profile", description="Zeigt Crew-Level und Fortschritt")
    @app_commands.describe(user="Wessen Profil? (leer = dein eigenes)")
    async def profile(self, interaction: discord.Interaction, user: discord.Member | None = None) -> None:
        target = user or interaction.user
        stats = self.levels.get(target.id)

        embed = discord.Embed(title=f"📊 Profil: {target.display_name}", color=Palette.PURPLE)
        embed.set_thumbnail(url=target.display_avatar.url)
        embed.add_field(name="Rang", value=stats.rank.name, inline=False)
        embed.add_field(name="Level", value=f"**{stats.level}**")
        embed.add_field(name="Gesamt XP", value=f"**{stats.xp} XP**")
        embed.add_field(name="Siege", value=f"🔪 {stats.wins}")
        embed.add_field(
            name=f"Fortschritt zum nächsten Level ({stats.xp_in_level}/{Rules.XP_PER_LEVEL} XP)",
            value=progress_bar(stats.xp_in_level, Rules.XP_PER_LEVEL),
            inline=False,
        )
        if upcoming := next_rank(stats.level):
            embed.set_footer(
                text=f"Nächster Rang: {upcoming.name} ab Level {upcoming.min_level}"
                f" • {stats.participations} Event-Teilnahmen"
            )
        else:
            embed.set_footer(text=f"Höchster Rang erreicht! • {stats.participations} Event-Teilnahmen")
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="crew-card", description="Deine persönliche Astronauten-Visitenkarte")
    @app_commands.describe(spieler="Wessen Crew-Card? (leer = deine eigene)")
    async def crew_card(self, interaction: discord.Interaction, spieler: discord.Member | None = None) -> None:
        target = spieler or interaction.user
        stats = self.levels.get(target.id)
        status = "INAKTIV" if target.status == discord.Status.offline else "AKTIV"
        line = "─" * 34

        card = "\n".join(
            [
                f"NAME:     {target.display_name.upper()}",
                f"STATUS:   {status}",
                f"RANG:     {stats.rank.name.upper()}",
                line,
                f"LEVEL:    {stats.level:<5} |  XP GESAMT: {stats.xp}",
                line,
                f"SIEGE:    🔪 {stats.wins}",
                f"EVENTS:   📅 {stats.participations}",
            ]
        )
        embed = discord.Embed(
            title="💳 MIRA HQ • IDENTIFICATION CARD",
            description=f"```\n{card}\n```",  # Code-Block = saubere Spalten
            color=Palette.CARD,
        )
        embed.set_thumbnail(url=target.display_avatar.url)
        embed.set_footer(text=f"ID: {target.id}  •  AUSGESTELLT DURCH AMONG US SEKTOR 9")
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="leaderboard", description="Top 10 der Crew – die Top 3 bekommen Medaillen im Nickname")
    async def leaderboard(self, interaction: discord.Interaction) -> None:
        ranking = self.levels.ranking()
        if not ranking:
            return await notify(interaction, "📊 Das Leaderboard ist aktuell leer!", Tone.INFO)

        await interaction.response.defer()
        async with _medal_lock:
            await self._sync_medals(interaction.guild, ranking)

        entries = []
        for position, (user_id, stats) in enumerate(ranking[:10], start=1):
            member = interaction.guild.get_member(user_id)
            name = strip_medal(member.display_name) if member else f"Unbekannter Astronaut ({user_id})"
            badge = MEDALS[position - 1] if position <= 3 else f"**#{position}**"
            entries.append(
                f"{badge} **{name}**\n↳ 📊 *Lvl {stats.level}* | ✨ *{stats.xp} XP*"
                f" | 🔪 *{stats.wins} Siege* | {stats.rank.name}"
            )

        embed = discord.Embed(
            title="🏆 AMONG US LEADERBOARD 🏆",
            description=(
                "Die aktuellen Top 10. Die Server-Nicknames der Top 3 wurden synchronisiert!\n\n"
                + "\n\n".join(entries)
            ),
            color=Palette.GOLD,
        )
        own_position = next(
            (pos for pos, (uid, _) in enumerate(ranking, start=1) if uid == interaction.user.id),
            None,
        )
        own_text = f"Dein Platz: #{own_position}" if own_position else "Du bist noch nicht im Ranking"
        embed.set_footer(text=own_text)
        embed.timestamp = discord.utils.utcnow()  # Discord zeigt es in der Ortszeit des Lesers
        await interaction.followup.send(embed=embed)

    @staticmethod
    async def _sync_medals(guild: discord.Guild, ranking: list[tuple[int, PlayerStats]]) -> None:
        """Top 3 bekommen 🥇🥈🥉 vor den Nickname, alle anderen verlieren ihre Medaille.

        Geprüft wird jedes Server-Mitglied mit Medaille – auch wer nicht (mehr) im Ranking
        steht, z.B. nach einem Reset im Admin-Panel oder bei selbst eingetragenen Medaillen.
        """
        top3 = {user_id: MEDALS[i] for i, (user_id, _) in enumerate(ranking[:3])}
        members = {m.id: m for m in guild.members if strip_medal(m.display_name) != m.display_name}
        members.update({uid: m for uid in top3 if (m := guild.get_member(uid)) is not None})

        for user_id, member in members.items():
            base = strip_medal(member.display_name)
            wanted = f"{top3[user_id]} {base}"[:MAX_NICK_LENGTH] if user_id in top3 else base
            if member.display_name == wanted:
                continue
            # Ohne Medaille wieder den normalen Namen zeigen statt eines Nicknames, der ihm nur gleicht
            nick = None if user_id not in top3 and base == (member.global_name or member.name) else wanted
            try:
                await member.edit(nick=nick)
            except discord.HTTPException as exc:
                log.warning("Nickname von %s nicht änderbar (Rolle über dem Bot / Server-Owner?): %s", member, exc)


async def setup(bot) -> None:
    await bot.add_cog(ProgressionCog(bot))
