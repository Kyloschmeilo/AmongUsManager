"""Event-Anmeldung: Teilnehmerliste im Embed, An-/Abmelden und der Fun-Button."""

import asyncio
import random
import re
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import discord

from crewbot.config import TIMEZONE, Rules
from crewbot.content.among_us import FUN_TEXTS
from crewbot.ui.embeds import Tone, Palette, notify
from crewbot.ui.views.base import PersistentView

WEEKDAYS = ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
MENTION_PATTERN = re.compile(r"<@!?(\d+)>")
TIMESTAMP_PATTERN = re.compile(r"<t:(\d+):")
LEGACY_DATE_PATTERN = re.compile(r"\((\d{2}\.\d{2}\.\d{4})\)")
LEGACY_TIME_PATTERN = re.compile(r"UM (\d{2}:\d{2}) UHR")
CLOSED_PREFIX = "🔒 "


def event_end(start: datetime) -> datetime:
    return start + timedelta(hours=Rules.EVENT_DURATION_HOURS)


def event_start_from_embed(embed: discord.Embed) -> datetime | None:
    """Liest die Startzeit aus dem Event-Embed (neues und altes Format)."""
    text = embed.description or ""
    if match := TIMESTAMP_PATTERN.search(text):
        return datetime.fromtimestamp(int(match[1]), tz=timezone.utc)
    date, time = LEGACY_DATE_PATTERN.search(text), LEGACY_TIME_PATTERN.search(text)
    if date and time:
        return datetime.strptime(f"{date[1]} {time[1]}", "%d.%m.%Y %H:%M").replace(
            tzinfo=ZoneInfo(TIMEZONE)
        )
    return None


def is_event_over(embed: discord.Embed) -> bool:
    if (embed.title or "").startswith(CLOSED_PREFIX):
        return True
    start = event_start_from_embed(embed)
    return start is not None and discord.utils.utcnow() >= event_end(start)


async def close_event_message(message: discord.Message) -> None:
    """Markiert das Event als beendet und sperrt An- und Abmeldung."""
    embed = message.embeds[0]
    if not (embed.title or "").startswith(CLOSED_PREFIX):
        embed.title = f"{CLOSED_PREFIX}{embed.title or 'EVENT'} (BEENDET)"
    embed.color = discord.Color.dark_grey()
    embed.set_footer(text=f"{embed.footer.text or ''} • Anmeldung geschlossen".lstrip(" •"))

    view = EventSignupView()
    view.join.disabled = True
    view.leave.disabled = True
    await message.edit(embed=embed, view=view)


def build_event_embed(
    game: str, start: datetime, creator: str, event_url: str | None = None
) -> discord.Embed:
    lines = [
        f"# 📅 {WEEKDAYS[start.weekday()]} ({start:%d.%m.%Y})",
        f"# ⏰ UM {start:%H:%M} UHR",
        f"⏳ Start {discord.utils.format_dt(start, 'R')}",
        "",
        "**Wir spielen zusammen! Sei dabei!**",
    ]
    if event_url:
        lines += ["", f"📌 **[Zum Discord Event]({event_url})**"]

    embed = discord.Embed(
        title=f"🎮 {game.upper()} EVENT",
        description="\n".join(lines),
        color=Palette.EVENT,
    )
    EventRoster([]).write_to(embed)
    embed.set_footer(text=f"Erstellt von {creator}")
    return embed


class EventRoster:
    """Die Teilnehmerliste, die direkt im Event-Embed gespeichert ist.

    Neue Einträge sind Erwähnungen (<@id>), damit Namensänderungen nichts
    kaputt machen. Alte Einträge (nur Anzeigename) werden weiter erkannt.
    """

    COUNT_FIELD = "✅ Dabei"
    LIST_FIELD = "📝 Crewliste"
    EMPTY_TEXT = "Noch niemand..."

    def __init__(self, entries: list[str]) -> None:
        self.entries = entries

    @classmethod
    def from_embed(cls, embed: discord.Embed) -> "EventRoster":
        value = embed.fields[1].value if len(embed.fields) > 1 else ""
        if not value or cls.EMPTY_TEXT in value:
            return cls([])
        return cls([line.strip() for line in value.splitlines() if line.strip()])

    def member_ids(self) -> list[int]:
        """IDs aller Einträge, die als Erwähnung gespeichert sind."""
        return [int(m[1]) for entry in self.entries if (m := MENTION_PATTERN.fullmatch(entry))]

    def contains(self, member: discord.abc.User) -> bool:
        return self._index_of(member) is not None

    def add(self, member: discord.abc.User) -> None:
        self.entries.append(member.mention)

    def remove(self, member: discord.abc.User) -> bool:
        index = self._index_of(member)
        if index is None:
            return False
        del self.entries[index]
        return True

    def write_to(self, embed: discord.Embed) -> discord.Embed:
        count = f"## {len(self.entries)}"
        names = "\n".join(self.entries) or self.EMPTY_TEXT
        if len(embed.fields) >= 2:
            embed.set_field_at(0, name=self.COUNT_FIELD, value=count, inline=True)
            embed.set_field_at(1, name=self.LIST_FIELD, value=names, inline=True)
        else:
            embed.clear_fields()
            embed.add_field(name=self.COUNT_FIELD, value=count, inline=True)
            embed.add_field(name=self.LIST_FIELD, value=names, inline=True)
        return embed

    def _index_of(self, member: discord.abc.User) -> int | None:
        for index, entry in enumerate(self.entries):
            match = MENTION_PATTERN.fullmatch(entry)
            if (match and int(match[1]) == member.id) or entry == member.display_name:
                return index
        return None


class EventSignupView(PersistentView):
    # Ein Lock pro Event-Nachricht verhindert, dass sich gleichzeitige Klicks
    # gegenseitig überschreiben.
    _locks: defaultdict[int, asyncio.Lock] = defaultdict(asyncio.Lock)

    @discord.ui.button(
        label="Ich bin dabei", emoji="✅", style=discord.ButtonStyle.success, custom_id="evt_yes_p"
    )
    async def join(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.defer()
        async with self._locks[interaction.message.id]:
            message = await self._fresh_message(interaction)
            embed = message.embeds[0]
            if await self._reject_if_over(interaction, message):
                return
            roster = EventRoster.from_embed(embed)
            if roster.contains(interaction.user):
                return await notify(interaction, "Du bist schon dabei! 😉")
            roster.add(interaction.user)
            await message.edit(embed=roster.write_to(embed))

        # XP gibt es erst nach dem Event – und nur, wenn man wirklich im Voice war.
        await notify(
            interaction,
            f"🚀 Du bist auf der Liste!\n🎁 Sei beim Event mindestens"
            f" {Rules.EVENT_MIN_ATTENDANCE_MINUTES} Minuten im Voice, dann bekommst du"
            f" **+{Rules.EVENT_ATTENDANCE_XP} XP**. Eine Erinnerung kommt"
            f" {Rules.EVENT_REMINDER_MINUTES} Minuten vor Start.",
            Tone.SUCCESS,
        )

    @discord.ui.button(
        label="Diesmal nicht", emoji="❌", style=discord.ButtonStyle.danger, custom_id="evt_no_p"
    )
    async def leave(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.defer()
        async with self._locks[interaction.message.id]:
            message = await self._fresh_message(interaction)
            embed = message.embeds[0]
            if await self._reject_if_over(interaction, message):
                return
            roster = EventRoster.from_embed(embed)
            if not roster.remove(interaction.user):
                return await notify(interaction, "Du stehst sowieso nicht auf der Liste! 😉")
            await message.edit(embed=roster.write_to(embed))

        await notify(interaction, "📋 Du wurdest von der Liste gestrichen.")

    @discord.ui.button(
        label="Fun", emoji="🎲", style=discord.ButtonStyle.primary, custom_id="evt_fun_p"
    )
    async def fun(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.defer()
        if random.random() >= Rules.FUN_TIMEOUT_CHANCE:
            return await notify(interaction, random.choice(FUN_TEXTS))

        try:
            await interaction.user.timeout(
                timedelta(minutes=Rules.FUN_TIMEOUT_MINUTES), reason="Pech beim Fun-Button!"
            )
        except discord.Forbidden:
            return await notify(
                interaction,
                "🛡️ Du hättest eigentlich einen Timeout bekommen, aber mir fehlen die Rechte dafür!",
                Tone.WARNING,
            )
        await interaction.channel.send(
            f"💀 **💥 KABOOM!** {interaction.user.mention} hatte Pech und wurde für"
            f" {Rules.FUN_TIMEOUT_MINUTES} Minuten getimeouted!"
        )

    @staticmethod
    async def _reject_if_over(interaction: discord.Interaction, message: discord.Message) -> bool:
        """Ist das Event vorbei, wird die Nachricht gesperrt und der Klick abgelehnt."""
        if not is_event_over(message.embeds[0]):
            return False
        await close_event_message(message)
        await notify(interaction, "🔒 Dieses Event ist bereits vorbei – keine An- oder Abmeldung mehr möglich.", Tone.WARNING)
        return True

    @staticmethod
    async def _fresh_message(interaction: discord.Interaction) -> discord.Message:
        """Holt die aktuelle Version der Nachricht (nicht den Stand beim Klick)."""
        try:
            return await interaction.message.fetch()
        except discord.HTTPException:
            return interaction.message
