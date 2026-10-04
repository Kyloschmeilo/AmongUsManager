"""Events: Gaming-Event mit Anmelde-Buttons und echtem Discord-Event erstellen."""

import logging
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import discord
from discord import app_commands
from discord.ext import tasks

from crewbot.cogs.base import CrewCog
from crewbot.config import GAME_PING_ROLES, TIMEZONE, Rules
from crewbot.services.events import OpenEvent
from crewbot.services.insights import build_recap
from crewbot.ui.embeds import Palette, Tone, notify
from crewbot.ui.insights import build_recap_embed
from crewbot.ui.views import EventSignupView
from crewbot.ui.views.event_signup import (
    EventRoster,
    build_event_embed,
    close_event_message,
    event_end,
)

log = logging.getLogger(__name__)

GAME_CHOICES = [app_commands.Choice(name=game, value=game) for game in GAME_PING_ROLES]
DATE_FORMAT = "%d.%m.%Y %H:%M"


class EventsCog(CrewCog, name="Events"):
    emoji = "📅"

    async def cog_load(self) -> None:
        self.event_lifecycle.start()

    async def cog_unload(self) -> None:
        self.event_lifecycle.cancel()

    # ==========================================
    # EVENT-ABLAUF (läuft jede Minute)
    # ==========================================

    @tasks.loop(minutes=1)
    async def event_lifecycle(self) -> None:
        """Erinnern -> Anwesenheit messen -> nach Ende abrechnen & sperren."""
        # Jeder Schritt ist einzeln abgesichert: ein Fehler darf nie die ganze Schleife stoppen.
        now = discord.utils.utcnow()
        for event in self.bot.events.needing_reminder(now):
            try:
                await self._send_reminder(event)
            except Exception:
                log.exception("Erinnerung für Event %s fehlgeschlagen", event.message_id)
                self.bot.events.mark_reminded(event.message_id)
        try:
            self._track_attendance(now)
        except Exception:
            log.exception("Anwesenheit konnte nicht erfasst werden")
        for event in self.bot.events.due(now):
            try:
                await self._finish_event(event)
            except Exception:
                log.exception("Event %s konnte nicht abgeschlossen werden", event.message_id)

    @event_lifecycle.before_loop
    async def _wait_until_ready(self) -> None:
        await self.bot.wait_until_ready()

    async def _send_reminder(self, event: OpenEvent) -> None:
        message = await self._fetch_message(event)
        if message is not None:
            player_ids = EventRoster.from_embed(message.embeds[0]).member_ids()
            if player_ids:
                mentions = " ".join(f"<@{uid}>" for uid in player_ids)
                await message.reply(
                    f"⏰ **Gleich geht's los!** Das Event startet"
                    f" {discord.utils.format_dt(event.starts_at, 'R')}.\n{mentions}",
                    allowed_mentions=discord.AllowedMentions(users=True),
                )
        self.bot.events.mark_reminded(event.message_id)

    def _track_attendance(self, now) -> None:
        """Schreibt allen, die gerade in einem Voice-Channel sind, eine Minute gut."""
        running = self.bot.events.running(now)
        for event in running:
            channel = self.bot.get_channel(event.channel_id)
            guild = getattr(channel, "guild", None)
            if guild is None:
                continue
            present = {
                member.id
                for voice in (*guild.voice_channels, *guild.stage_channels)
                if voice != guild.afk_channel
                for member in voice.members
                if not member.bot
            }
            self.bot.events.record_attendance(event.message_id, present)
        if running:
            self.bot.events.save()

    async def _finish_event(self, event: OpenEvent) -> None:
        try:
            message = await self._fetch_message(event)
        except discord.HTTPException as exc:
            log.warning("Event %s nicht abrufbar, neuer Versuch in 1 Min: %s", event.message_id, exc)
            return

        # Ab hier wird nicht wiederholt – so kann niemand doppelt XP bekommen.
        self.bot.events.remove(event.message_id)
        if message is None:
            return  # Nachricht wurde gelöscht
        try:
            await close_event_message(message)
        except discord.HTTPException as exc:
            log.warning("Event %s konnte nicht gesperrt werden: %s", event.message_id, exc)
        await self._pay_attendance(message, event)
        try:
            await self._post_recap(message, event)
        except Exception:
            log.exception("Recap für Event %s fehlgeschlagen", event.message_id)

    async def _pay_attendance(self, message: discord.Message, event: OpenEvent) -> None:
        player_ids = EventRoster.from_embed(message.embeds[0]).member_ids()
        if not player_ids:
            return

        present, absent = [], []
        for user_id in player_ids:
            if not event.attended(user_id):
                absent.append(f"<@{user_id}>")
                continue
            result = await self.levels.award(
                message.guild, user_id, Rules.EVENT_ATTENDANCE_XP, participation=True
            )
            line = f"<@{user_id}>"
            if result.leveled_up:
                line += f" 🎊 Level {result.stats.level}!"
            present.append(line)

        embed = discord.Embed(title="🏁 Event beendet – Danke fürs Mitspielen!", color=Palette.SUCCESS)
        embed.add_field(
            name=f"✅ Dabei gewesen (+{Rules.EVENT_ATTENDANCE_XP} XP)",
            value="\n".join(present) or "Niemand 😢",
            inline=True,
        )
        embed.add_field(name="👻 Nicht erschienen", value="\n".join(absent) or "Niemand 🎉", inline=True)
        embed.set_footer(text=f"Anwesend = mind. {Rules.EVENT_MIN_ATTENDANCE_MINUTES} Minuten im Voice")
        await message.reply(embed=embed, allowed_mentions=discord.AllowedMentions.none())

    async def _post_recap(self, message: discord.Message, event: OpenEvent) -> None:
        """Spieleabend-Recap aus den Mod-Runden während des Events (falls welche aufgezeichnet wurden)."""
        rounds = self.bot.round_history.rounds(since=event.starts_at, until=discord.utils.utcnow())
        if not rounds:
            return
        embed = build_recap_embed(build_recap(rounds), "🌙 Spieleabend-Recap")
        await message.reply(embed=embed, allowed_mentions=discord.AllowedMentions.none())

    async def _fetch_message(self, event: OpenEvent) -> discord.Message | None:
        """Holt die Event-Nachricht. None, wenn sie gelöscht wurde."""
        try:
            channel = self.bot.get_channel(event.channel_id) or await self.bot.fetch_channel(event.channel_id)
            return await channel.fetch_message(event.message_id)
        except discord.NotFound:
            return None

    # ==========================================
    # BEFEHLE
    # ==========================================

    @app_commands.command(name="create-event", description="Erstellt ein Gaming-Event mit Anmeldung & Discord-Event")
    @app_commands.describe(
        spiel="Welches Spiel?",
        datum_zeit="Datum und Startzeit: DD.MM.YYYY HH:MM (z.B. 25.08.2026 19:00)",
    )
    @app_commands.choices(spiel=GAME_CHOICES)
    async def create_event(self, interaction: discord.Interaction, spiel: str, datum_zeit: str) -> None:
        timezone = ZoneInfo(TIMEZONE)
        try:
            start = datetime.strptime(datum_zeit.strip(), DATE_FORMAT).replace(tzinfo=timezone)
        except ValueError:
            return await notify(
                interaction,
                "❌ **Ungültiges Format!** Bitte nutze exakt: `DD.MM.YYYY HH:MM` (z.B. `25.08.2026 19:00`)",
                Tone.ERROR,
            )
        if start <= datetime.now(timezone):
            return await notify(interaction, "❌ Der Zeitpunkt liegt in der Vergangenheit!", Tone.ERROR)

        await interaction.response.defer(ephemeral=True, thinking=True)

        event_url = await self._create_scheduled_event(interaction.guild, spiel, start)
        embed = build_event_embed(spiel, start, interaction.user.display_name, event_url)

        # Der Ping muss im Nachrichtentext stehen – in einem Embed pingt Discord nicht.
        role_id = GAME_PING_ROLES.get(spiel)
        message = await interaction.channel.send(
            content=f"🔔 <@&{role_id}>" if role_id else None,
            embed=embed,
            view=EventSignupView(),
            allowed_mentions=discord.AllowedMentions(roles=True),
        )
        self.bot.events.add(
            message.id, message.channel.id, start, event_end(start), discord.utils.utcnow()
        )

        note = "" if event_url else "\n⚠️ Das Discord-Event konnte nicht erstellt werden (Rechte?)."
        await notify(interaction, f"✅ Event erstellt: {message.jump_url}{note}", Tone.SUCCESS)

    @staticmethod
    async def _create_scheduled_event(guild: discord.Guild, game: str, start: datetime) -> str | None:
        try:
            event = await guild.create_scheduled_event(
                name=f"{game} Event",
                description=f"🎮 Gemeinsame Runde {game}! Melde dich im Channel an.",
                start_time=start,
                end_time=start + timedelta(hours=Rules.EVENT_DURATION_HOURS),
                entity_type=discord.EntityType.external,
                location="Voice Channels",
                privacy_level=discord.PrivacyLevel.guild_only,
            )
        except discord.HTTPException as exc:
            log.warning("Discord-Event konnte nicht erstellt werden: %s", exc)
            return None
        return event.url


async def setup(bot) -> None:
    await bot.add_cog(EventsCog(bot))
