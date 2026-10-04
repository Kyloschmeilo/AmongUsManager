"""Anzeige der Auswertungen: Rivalen in /stats, Spieleabend-Recap und /erfolge."""

from datetime import datetime

import discord

from crewbot.content.achievements import ACHIEVEMENTS
from crewbot.services.achievements import AchievementService
from crewbot.services.game_stats import GameStats
from crewbot.services.insights import PlayerKey, Recap, Rivals
from crewbot.ui.embeds import Palette, progress_bar


def who(key: PlayerKey) -> str:
    """Discord-ID -> Erwähnung, Ingame-Name (unverknüpft) -> kursiv."""
    return f"<@{key}>" if isinstance(key, int) else f"*{key}*"


# ==========================================
# RIVALEN
# ==========================================


def rivals_text(rivals: Rivals) -> str:
    lines = []
    if rivals.nemesis:
        lines.append(f"😈 **Nemesis:** {who(rivals.nemesis[0])} hat dich {rivals.nemesis[1]}× getötet")
    if rivals.favorite_victim:
        lines.append(f"🎯 **Lieblingsopfer:** {who(rivals.favorite_victim[0])} ({rivals.favorite_victim[1]}× getötet)")
    if rivals.biggest_doubter:
        lines.append(f"🤨 **Misstraut dir am meisten:** {who(rivals.biggest_doubter[0])} ({rivals.biggest_doubter[1]} Stimmen)")
    if rivals.my_suspect:
        lines.append(f"🔍 **Dein Hauptverdächtiger:** {who(rivals.my_suspect[0])} ({rivals.my_suspect[1]} Stimmen)")
    return "\n".join(lines)


# ==========================================
# SPIELEABEND-RECAP
# ==========================================


def build_recap_embed(recap: Recap, title: str, since: datetime | None = None) -> discord.Embed:
    embed = discord.Embed(title=title, color=Palette.PURPLE)
    if recap.rounds == 0:
        embed.description = "Keine aufgezeichneten Runden in diesem Zeitraum."
        return embed

    embed.description = (
        f"🎮 **{recap.rounds}** Runden • 🚀 Crew **{recap.crew_wins}** : **{recap.impostor_wins}** Impostor 🔪"
        f" • 🩸 {recap.total_kills} Kills"
    )

    awards = []
    for attribute, emoji, label, unit, minimum in (
        ("wins", "🏆", "Meiste Siege", "Siege", 1),
        ("kills", "🔪", "Gefährlichster Impostor", "Kills", 1),
        ("correct_votes", "🕵️", "Bester Detektiv", "richtige Stimmen", 1),
        ("survived", "🛡️", "Überlebenskünstler", "× überlebt", 1),
        ("sus_votes", "🤡", "Sus-Magnet", "Stimmen als Crewmate", 2),
        ("first_deaths", "🥀", "Pechvogel", "× als Erste(r) gestorben", 2),
    ):
        if best := recap.best(attribute, minimum):
            awards.append(f"{emoji} **{label}:** {who(best[0])} ({best[1]} {unit})")
    embed.add_field(name="🏅 Auszeichnungen", value="\n".join(awards) or "–", inline=False)

    candidates = recap.mvp_candidates()
    if candidates:
        medals = ("🥇", "🥈", "🥉")
        embed.add_field(
            name="👑 MVP-Kandidaten (aus den Stats)",
            value="\n".join(
                f"{medals[i]} {who(key)} – {night.wins} Siege, {night.kills} Kills, {night.correct_votes} richtige Stimmen"
                for i, (key, night) in enumerate(candidates)
            ),
            inline=False,
        )
    if since is not None:
        embed.set_footer(text="Runden seit")
        embed.timestamp = since
    return embed


def mvp_suggestion_text(recap: Recap) -> str:
    """Kurzer Hinweis für das MVP-Voting, wer laut Stats vorne liegt."""
    candidates = recap.mvp_candidates()
    if not candidates:
        return ""
    names = ", ".join(who(key) for key, _ in candidates)
    return f"\n\n💡 **Laut Stats heute stark:** {names}"


# ==========================================
# ERFOLGE
# ==========================================


def build_achievements_embed(member: discord.Member, service: AchievementService, stats: GameStats) -> discord.Embed:
    unlocked = service.unlocked(member.id)
    embed = discord.Embed(
        title=f"🏅 Erfolge: {member.display_name}",
        description=f"**{len(unlocked)}/{len(ACHIEVEMENTS)}** freigeschaltet",
        color=Palette.GOLD,
    )
    embed.set_thumbnail(url=member.display_avatar.url)

    done, open_ = [], []
    for achievement in ACHIEVEMENTS:
        if achievement.id in unlocked:
            done.append(
                f"{achievement.label} – {achievement.description}"
                f" ({discord.utils.format_dt(unlocked[achievement.id], 'd')})"
            )
            continue
        line = f"🔒 **{achievement.name}** – {achievement.description} • +{achievement.xp} XP"
        if progress := service.progress(achievement, stats):
            line += f"\n{progress_bar(*progress)} {progress[0]}/{progress[1]}"
        open_.append(line)

    _add_chunked(embed, "✅ Geschafft", done or ["Noch keine – spiel eine Runde mit der Mod!"])
    if open_:
        _add_chunked(embed, "🎯 Noch offen", open_)
    return embed


def _add_chunked(embed: discord.Embed, name: str, lines: list[str], limit: int = 1024) -> None:
    """Verteilt lange Listen auf mehrere Felder statt abzuschneiden."""
    chunk = ""
    for line in lines:
        if chunk and len(chunk) + len(line) + 1 > limit:
            embed.add_field(name=name, value=chunk, inline=False)
            name, chunk = "​", ""  # Folgefelder ohne Überschrift
        chunk = f"{chunk}\n{line}" if chunk else line[:limit]
    if chunk:
        embed.add_field(name=name, value=chunk, inline=False)


def _fit(lines: list[str], limit: int = 1024) -> str:
    text = ""
    for index, line in enumerate(lines):
        if len(text) + len(line) + 30 > limit:
            return text + f"… und {len(lines) - index} weitere"
        text += line + "\n"
    return text.strip()
