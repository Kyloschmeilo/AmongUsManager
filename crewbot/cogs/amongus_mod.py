"""Among-Us-Mod: Webhook einrichten, Accounts verknüpfen, Statistiken und automatische Auswertung."""

import json
import logging
from datetime import timedelta

import discord
from discord import app_commands
from discord.ext import commands

from crewbot.cogs.base import CrewCog
from crewbot.content.achievements import ACHIEVEMENTS
from crewbot.services.automute import AutoMuteEvent
from crewbot.services.insights import build_recap, rivals_of
from crewbot.services.lobby import LobbyEvent
from crewbot.services.game_settings import SETTINGS_FILE_NAME, GameSettingsError
from crewbot.services.game_stats import GameStats
from crewbot.services.links import is_valid_friend_code, normalize_friend_code
from crewbot.services.mod_rounds import ModRound, ModRoundError
from crewbot.services.voice import voice_channel_of
from crewbot.ui.embeds import Palette, Tone, notify
from crewbot.ui.insights import build_achievements_embed, build_recap_embed, rivals_text
from crewbot.ui.views.game_settings import GameSettingsView, build_settings_embed
from crewbot.ui.views.mod_round import UndoRoundButton, build_mod_round_embed

log = logging.getLogger(__name__)

MOD_CONFIG_PATH = r"Among Us\BepInEx\config\de.amongusmanager.crewstats.cfg"
AUTOMUTE_MAX_AGE = timedelta(seconds=60)


class AmongUsModCog(CrewCog, name="Among Us Mod"):
    emoji = "🛰️"

    # ==========================================
    # EINRICHTUNG
    # ==========================================

    @app_commands.command(name="mod-setup", description="Richtet den Empfang der CrewStats-Mod in einem Channel ein")
    @app_commands.describe(kanal="Hier landen die automatischen Rundenergebnisse")
    @app_commands.default_permissions(administrator=True)
    @app_commands.checks.has_permissions(administrator=True)
    async def mod_setup(self, interaction: discord.Interaction, kanal: discord.TextChannel) -> None:
        await interaction.response.defer(ephemeral=True, thinking=True)
        settings = self.bot.mod_settings

        # Alten Webhook entfernen, damit die alte URL ungültig wird
        if settings.webhook_id:
            try:
                old = await self.bot.fetch_webhook(settings.webhook_id)
                await old.delete(reason="CrewStats-Webhook neu eingerichtet")
            except discord.HTTPException:
                pass

        try:
            webhook = await kanal.create_webhook(name="CrewStats Mod", reason=f"/mod-setup von {interaction.user}")
        except discord.Forbidden:
            return await notify(interaction, "❌ Mir fehlt das Recht **Webhooks verwalten** in diesem Channel.", Tone.ERROR)

        settings.set(webhook.id, kanal.id)
        await self.bot.audit.log(interaction.user, "CrewStats-Mod eingerichtet", f"Ergebnis-Channel: {kanal.mention}")

        embed = discord.Embed(
            title="🛰️ CrewStats-Mod eingerichtet",
            description=(
                f"Rundenergebnisse erscheinen ab jetzt in {kanal.mention}.\n\n"
                "**So verbindest du die Mod:**\n"
                "1. Among Us einmal mit der Mod starten und wieder schließen\n"
                f"2. Datei öffnen: `{MOD_CONFIG_PATH}`\n"
                "3. Bei `WebhookUrl =` diese URL eintragen:\n"
                f"```{webhook.url}```\n"
                "⚠️ **Die URL ist geheim!** Wer sie hat, kann Runden einschicken."
                " Bei Bedarf einfach `/mod-setup` erneut ausführen – die alte URL wird dann ungültig.\n\n"
                "🔇 **Auto-Mute:** im Spiel unter **ESC → Einstellungen → Auto-Mute** an-/ausschalten."
                " Runde: Lebende stumm + taub, Tote reden. Meeting: Lebende reden, Tote stumm."
                " Gilt im Voice des Spielers mit der Mod (muss mit `/verknuepfen` verknüpft sein),"
                " sonst im Among-Us-Channel. Als tot erkannt werden nur verknüpfte Spieler."
            ),
            color=Palette.SUCCESS,
        )
        await interaction.followup.send(embed=embed, ephemeral=True)

    # ==========================================
    # VERKNÜPFEN & STATISTIK
    # ==========================================

    @app_commands.command(name="verknuepfen", description="Verknüpft deinen Among-Us-Account (Friendcode) mit Discord")
    @app_commands.describe(
        friendcode="Dein Friendcode, z.B. happyfox#1234 (Among Us → Freunde-Menü)",
        spieler="Nur Admins: jemand anderen verknüpfen",
    )
    async def link(
        self, interaction: discord.Interaction, friendcode: str, spieler: discord.Member | None = None
    ) -> None:
        is_admin = interaction.user.guild_permissions.administrator
        target = spieler or interaction.user
        if target != interaction.user and not is_admin:
            return await notify(interaction, "🚫 Nur Admins dürfen andere Spieler verknüpfen.", Tone.ERROR)
        if not is_valid_friend_code(friendcode):
            return await notify(
                interaction,
                "❌ Das sieht nicht nach einem Friendcode aus. Format: `name#1234`\n"
                "Du findest ihn in Among Us im **Freunde-Menü** oben.",
                Tone.ERROR,
            )

        code = normalize_friend_code(friendcode)
        owner = self.bot.links.user_for(code)
        if owner and owner != target.id and not is_admin:
            return await notify(interaction, f"❌ Dieser Friendcode ist bereits mit <@{owner}> verknüpft.", Tone.ERROR)

        self.bot.links.link(code, target.id)
        if target != interaction.user:
            await self.bot.audit.log(interaction.user, "Account verknüpft", f"`{code}` → {target.mention}")
        await notify(
            interaction,
            f"✅ `{code}` ist jetzt mit {target.mention} verknüpft. Ab der nächsten Runde gibt es XP & Statistiken!",
            Tone.SUCCESS,
        )

    @app_commands.command(name="verknuepfungen", description="Zeigt, wer seinen Among-Us-Account verknüpft hat")
    @app_commands.describe(
        spieler="Nur diesen Spieler prüfen",
        friendcode="Herausfinden, wem ein Friendcode gehört",
    )
    @app_commands.default_permissions(mute_members=True)
    @app_commands.checks.has_permissions(mute_members=True)
    async def links_overview(
        self,
        interaction: discord.Interaction,
        spieler: discord.Member | None = None,
        friendcode: str | None = None,
    ) -> None:
        links = self.bot.links

        # Einzelabfrage: Friendcode -> Discord
        if friendcode:
            code = normalize_friend_code(friendcode)
            owner = links.user_for(code)
            text = f"✅ `{code}` gehört zu <@{owner}>." if owner else f"❌ `{code}` ist mit niemandem verknüpft."
            return await notify(interaction, text, Tone.SUCCESS if owner else Tone.WARNING)

        # Einzelabfrage: Discord -> Friendcode
        if spieler:
            codes = links.codes_of(spieler.id)
            text = (
                f"✅ {spieler.mention} ist verknüpft mit {', '.join(f'`{c}`' for c in codes)}."
                if codes
                else f"❌ {spieler.mention} ist **nicht** verknüpft und bekommt keine XP aus Mod-Runden."
            )
            return await notify(interaction, text, Tone.SUCCESS if codes else Tone.WARNING)

        # Übersicht
        grouped = links.by_user()
        embed = discord.Embed(title="🔗 Verknüpfte Among-Us-Accounts", color=Palette.INFO)
        lines = []
        for user_id, codes in sorted(grouped.items(), key=lambda item: self._sort_name(interaction.guild, item[0])):
            member = interaction.guild.get_member(user_id)
            gone = "" if member else " *(nicht mehr auf dem Server)*"
            lines.append(f"<@{user_id}> – {', '.join(f'`{c}`' for c in codes)}{gone}")
        embed.description = self._fit_description(lines) or "Noch niemand hat sich verknüpft."

        # Wer sitzt gerade im Voice und ist noch nicht verknüpft?
        voice = voice_channel_of(interaction.user)
        if voice is not None:
            players = [m for m in voice.members if not m.bot]
            missing = [m for m in players if m.id not in grouped]
            embed.add_field(
                name=f"🎙️ {voice.name}: {len(players) - len(missing)}/{len(players)} verknüpft",
                value="\n".join(f"❌ {m.mention}" for m in missing) or "✅ Alle im Voice sind verknüpft!",
                inline=False,
            )
        embed.set_footer(text=f"{len(grouped)} Spieler verknüpft • Verknüpfen: /verknuepfen")
        await interaction.response.send_message(embed=embed, ephemeral=True)

    @staticmethod
    def _sort_name(guild: discord.Guild, user_id: int) -> str:
        member = guild.get_member(user_id)
        return member.display_name.lower() if member else "~"  # Ehemalige ans Ende

    @staticmethod
    def _fit_description(lines: list[str], limit: int = 4000) -> str:
        text = ""
        for index, line in enumerate(lines):
            if len(text) + len(line) + 40 > limit:
                return text + f"… und {len(lines) - index} weitere"
            text += line + "\n"
        return text.strip()

    @app_commands.command(name="stats", description="Among-Us-Statistiken aus den aufgezeichneten Runden")
    @app_commands.describe(spieler="Wessen Statistik? (leer = deine eigene)")
    async def stats(self, interaction: discord.Interaction, spieler: discord.Member | None = None) -> None:
        target = spieler or interaction.user
        s = self.bot.game_stats.get(target.id)
        codes = self.bot.links.codes_of(target.id)

        embed = discord.Embed(title=f"📈 Among-Us-Statistik: {target.display_name}", color=Palette.PURPLE)
        embed.set_thumbnail(url=target.display_avatar.url)
        if s.games == 0:
            embed.description = (
                "Noch keine aufgezeichneten Runden."
                + ("" if codes else "\nVerknüpfe zuerst deinen Account mit `/verknuepfen`!")
            )
        else:
            rate = GameStats.rate
            embed.add_field(name="🎮 Spiele", value=f"**{s.games}**\n🚀 {s.crew_games} • 🔪 {s.impostor_games}")
            embed.add_field(name="🏆 Siege", value=f"**{s.wins}** ({rate(s.wins, s.games)})")
            embed.add_field(name="🛡️ Überlebt", value=f"{s.survived} ({rate(s.survived, s.games)})")
            embed.add_field(name="🚀 Crew-Winrate", value=rate(s.crew_wins, s.crew_games))
            embed.add_field(name="🔪 Impostor-Winrate", value=rate(s.impostor_wins, s.impostor_games))
            kills_per_game = f"{s.kills / s.impostor_games:.1f}" if s.impostor_games else "–"
            embed.add_field(name="🩸 Kills", value=f"{s.kills} (Ø {kills_per_game} pro Impostor-Runde)")
            embed.add_field(name="💀 Getötet", value=str(s.deaths))
            embed.add_field(name="🚪 Rausgewählt", value=str(s.ejected))
            embed.add_field(name="🧰 Tasks erledigt", value=str(s.tasks_completed))
            embed.add_field(name="📢 Notfall-Meetings", value=str(s.meetings_called))
            embed.add_field(name="🩸 Leichen gemeldet", value=str(s.bodies_reported))
            embed.add_field(
                name="🎯 Vote-Trefferquote",
                value=f"{rate(s.votes_correct, s.votes_correct + s.votes_wrong)} ({s.votes_correct}/{s.votes_correct + s.votes_wrong})",
            )
            if rivals := rivals_text(rivals_of(target.id, self.bot.round_history.rounds_of(target.id))):
                embed.add_field(name="⚔️ Rivalen", value=rivals, inline=False)
            unlocked = len(self.bot.achievements.unlocked(target.id))
            embed.add_field(name="🏅 Erfolge", value=f"{unlocked}/{len(ACHIEVEMENTS)} – Details: `/erfolge`", inline=False)
        embed.set_footer(text=f"Verknüpft: {', '.join(codes)}" if codes else "Nicht verknüpft – /verknuepfen")
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="erfolge", description="Zeigt freigeschaltete und offene Among-Us-Erfolge")
    @app_commands.describe(spieler="Wessen Erfolge? (leer = deine eigenen)")
    async def achievements(self, interaction: discord.Interaction, spieler: discord.Member | None = None) -> None:
        target = spieler or interaction.user
        embed = build_achievements_embed(target, self.bot.achievements, self.bot.game_stats.get(target.id))
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="recap", description="Zusammenfassung der letzten Runden: Auszeichnungen & MVP-Kandidaten")
    @app_commands.describe(stunden="Wie viele Stunden zurück? (Standard: 6)")
    async def recap(self, interaction: discord.Interaction, stunden: app_commands.Range[int, 1, 168] = 6) -> None:
        since = discord.utils.utcnow() - timedelta(hours=stunden)
        recap = build_recap(self.bot.round_history.rounds(since=since))
        embed = build_recap_embed(recap, f"🌙 Recap der letzten {stunden} Stunden", since)
        await interaction.response.send_message(embed=embed)

    @app_commands.command(name="rundensettings", description="Zeigt die Lobbyeinstellungen der letzten Among-Us-Runde")
    async def round_settings(self, interaction: discord.Interaction) -> None:
        settings = self.bot.game_settings.latest
        if settings is None:
            return await notify(
                interaction,
                "ℹ️ Noch keine Einstellungen gespeichert. Sie kommen automatisch beim nächsten"
                " Rundenstart, wenn der Host die CrewStats-Mod hat.",
                Tone.WARNING,
            )
        view = GameSettingsView(settings)
        await interaction.response.send_message(embed=build_settings_embed(settings), view=view)
        view.message = await interaction.original_response()

    # ==========================================
    # EMPFANG DER RUNDENDATEN
    # ==========================================

    @commands.Cog.listener()
    async def on_message(self, message: discord.Message) -> None:
        settings = self.bot.mod_settings
        if message.guild is None or message.webhook_id is None or message.webhook_id != settings.webhook_id:
            return
        if (event := AutoMuteEvent.parse(message.content)) is not None:
            return await self._handle_automute(message, event)
        if (lobby := LobbyEvent.parse(message.content)) is not None:
            if discord.utils.utcnow() - message.created_at <= AUTOMUTE_MAX_AGE:
                self.bot.lobby.apply(lobby)
            return await self._delete(message)
        settings_file = next((a for a in message.attachments if a.filename == SETTINGS_FILE_NAME), None)
        if settings_file is not None:
            return await self._handle_settings(message, settings_file)
        attachment = next((a for a in message.attachments if a.filename.endswith(".json")), None)
        if attachment is None:
            return

        try:
            round_ = ModRound.from_dict(json.loads(await attachment.read()))
            if self.bot.mod_rounds.is_known(round_.game_id):
                return await self._delete(message)  # doppelt gesendet
            result = await self.bot.mod_rounds.apply(message.guild, round_)
        except (ModRoundError, json.JSONDecodeError, discord.HTTPException) as exc:
            log.warning("Mod-Runde konnte nicht ausgewertet werden: %s", exc)
            await message.reply(f"⚠️ Rundendaten konnten nicht ausgewertet werden: {exc}")
            return

        summary = await message.channel.send(
            embed=build_mod_round_embed(result), view=UndoRoundButton.view_for(round_.game_id)
        )
        self.bot.mod_rounds.attach_message(round_.game_id, summary.channel.id, summary.id)
        await self._delete(message)

        winner = round_.winning_team.label if round_.winning_team else "unbekannt"
        await self.bot.audit.log(
            self.bot.user, "Runde automatisch ausgewertet", f"[Zur Runde]({summary.jump_url}) • Sieger: {winner}"
        )

    async def _handle_settings(self, message: discord.Message, attachment: discord.Attachment) -> None:
        try:
            settings = self.bot.game_settings.update(json.loads(await attachment.read()))
        except (GameSettingsError, json.JSONDecodeError, discord.HTTPException) as exc:
            log.warning("Rundeneinstellungen konnten nicht gelesen werden: %s", exc)
            return  # Nachricht stehen lassen, damit man den Fehler nachvollziehen kann
        log.info("Rundeneinstellungen gespeichert (%s, Lobby %s)", settings.map, settings.lobby_code)
        await self._delete(message)

    async def _handle_automute(self, message: discord.Message, event: AutoMuteEvent) -> None:
        # Nachrichten, die z.B. während eines Bot-Neustarts liegen geblieben sind, nicht mehr ausführen
        if discord.utils.utcnow() - message.created_at <= AUTOMUTE_MAX_AGE:
            result = await self.bot.automute.apply(message.guild, event)
            if result is not None:
                log.info(
                    "Auto-Mute %s in %s: %d geändert, %d Fehler",
                    event.phase, result.channel.name, result.report.succeeded, result.report.failed,
                )
        await self._delete(message)

    @staticmethod
    async def _delete(message: discord.Message) -> None:
        try:
            await message.delete()
        except discord.HTTPException:
            pass  # fehlendes Recht „Nachrichten verwalten“ – nicht schlimm


async def setup(bot) -> None:
    await bot.add_cog(AmongUsModCog(bot))
