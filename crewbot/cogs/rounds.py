"""Runden: Wetten, Sieg-Auswertung und MVP-Voting."""

from datetime import timedelta

import discord
from discord import app_commands

from crewbot.cogs.base import CrewCog
from crewbot.config import Rules
from crewbot.content.among_us import Team
from crewbot.services.betting import BetError
from crewbot.services.rounds import RoundService
from crewbot.services.voice import voice_channel_of
from crewbot.ui.embeds import Palette, Tone, notify
from crewbot.ui.views import MvpVotingView
from crewbot.ui.views.round_flow import evaluate_round

TEAM_CHOICES = [app_commands.Choice(name=team.label, value=team.value) for team in Team]


class RoundsCog(CrewCog, name="Runden & Wetten"):
    emoji = "🎰"

    # ==========================================
    # WETTEN
    # ==========================================

    @app_commands.command(name="bet", description="Wette XP auf den Ausgang der aktuellen Runde")
    @app_commands.describe(team="Wer gewinnt?", einsatz=f"Wie viele XP? (max. {Rules.MAX_BET})")
    @app_commands.choices(team=TEAM_CHOICES)
    async def bet(
        self,
        interaction: discord.Interaction,
        team: str,
        einsatz: app_commands.Range[int, 1, Rules.MAX_BET],
    ) -> None:
        try:
            bet = self.bot.bets.place(interaction.user.id, Team(team), einsatz)
        except BetError as exc:
            return await notify(interaction, f"❌ {exc}", Tone.ERROR)
        await notify(
            interaction,
            f"🎰 Wette platziert! Du setzt **{bet.amount} XP** auf einen Sieg der"
            f" **{bet.team.label}**.\nBei einem Sieg bekommst du **{bet.payout} XP** zurück.",
            Tone.SUCCESS,
        )

    @app_commands.command(name="runde-abbrechen", description="Bricht die Runde ab und erstattet alle Wetteinsätze")
    @app_commands.default_permissions(mute_members=True)
    @app_commands.checks.has_permissions(mute_members=True)
    async def cancel_round(self, interaction: discord.Interaction) -> None:
        refunded = self.bot.bets.refund_all()
        if not refunded:
            return await notify(interaction, "ℹ️ Es sind keine Wetten aktiv.")
        lines = [f"↩️ <@{bet.user_id}> bekommt **{bet.amount} XP** zurück." for bet in refunded]
        embed = discord.Embed(
            title="🛑 Runde abgebrochen",
            description="\n".join(lines),
            color=Palette.WARNING,
        )
        await interaction.response.send_message(embed=embed)
        await self.bot.audit.log(
            interaction.user, "Runde abgebrochen", f"{len(refunded)} Wetteinsätze erstattet.", Palette.WARNING
        )

    # ==========================================
    # RUNDENENDE
    # ==========================================

    @app_commands.command(name="impostor-win", description="Runde endet mit Impostor-Sieg (XP, Siege & Wetten)")
    @app_commands.describe(impostor1="Impostor dieser Runde", impostor2="Zweiter Impostor (optional)")
    @app_commands.default_permissions(mute_members=True)
    @app_commands.checks.has_permissions(mute_members=True)
    async def impostor_win(
        self,
        interaction: discord.Interaction,
        impostor1: discord.Member,
        impostor2: discord.Member | None = None,
    ) -> None:
        winners = [m for m in (impostor1, impostor2) if m is not None]
        await self._finish_round(interaction, Team.IMPOSTOR, winners)

    @app_commands.command(name="crew-win", description="Runde endet mit Crew-Sieg (XP, Siege & Wetten)")
    @app_commands.describe(
        impostor1="Impostor dieser Runde (bekommt keinen Sieg)",
        impostor2="Zweiter Impostor (optional)",
    )
    @app_commands.default_permissions(mute_members=True)
    @app_commands.checks.has_permissions(mute_members=True)
    async def crew_win(
        self,
        interaction: discord.Interaction,
        impostor1: discord.Member | None = None,
        impostor2: discord.Member | None = None,
    ) -> None:
        # Gewinner = alle im Voice des Hosts außer den Impostorn
        voice = voice_channel_of(interaction.user)
        impostors = [m for m in (impostor1, impostor2) if m is not None]
        winners = RoundService.winners_for(Team.CREW, impostors, voice.members if voice else [])
        await self._finish_round(interaction, Team.CREW, winners)

    async def _finish_round(
        self, interaction: discord.Interaction, winning_team: Team, winners: list[discord.Member]
    ) -> None:
        await interaction.response.defer()
        embed = await evaluate_round(self.bot, interaction.user, winning_team, winners)
        await interaction.followup.send(embed=embed)

    # ==========================================
    # MVP
    # ==========================================

    @app_commands.command(name="mvp-voting", description="Abstimmung für den MVP des Abends im Voice-Channel")
    @app_commands.default_permissions(mute_members=True)
    @app_commands.checks.has_permissions(mute_members=True)
    async def mvp_voting(self, interaction: discord.Interaction) -> None:
        voice = voice_channel_of(interaction.user)
        if voice is None:
            return await notify(interaction, "❌ Du musst im Voice-Channel sein, um das Voting zu starten!", Tone.ERROR)

        players = [m for m in voice.members if not m.bot]
        if len(players) < 2:
            return await notify(interaction, "❌ Es müssen mindestens 2 Spieler im Voice sein!", Tone.ERROR)

        view = MvpVotingView(players, timeout=Rules.MVP_VOTING_SECONDS)
        ends_at = discord.utils.utcnow() + timedelta(seconds=Rules.MVP_VOTING_SECONDS)
        embed = discord.Embed(
            title="👑 MVP OF THE NIGHT VOTING 👑",
            description=(
                "Wer hat heute die krassesten Plays gemacht, die härtesten Lügen"
                " entlarvt oder als Impostor alle rasiert?\n\n"
                f"⏳ Voting endet {discord.utils.format_dt(ends_at, 'R')}"
            ),
            color=Palette.PURPLE,
        )
        embed.set_footer(text=view.progress_text)
        await interaction.response.send_message(embed=embed, view=view)

        await view.wait()
        await interaction.edit_original_response(embed=await self._mvp_result(interaction.guild, view), view=None)

    async def _mvp_result(self, guild: discord.Guild, view: MvpVotingView) -> discord.Embed:
        embed = discord.Embed(title="👑 MVP OF THE NIGHT 👑", color=Palette.GOLD)
        winner_ids, votes = view.results()
        if not winner_ids:
            embed.description = "⏱️ **Das Voting ist beendet.**\nEs wurden leider keine Stimmen abgegeben."
            embed.color = Palette.PURPLE
            return embed

        lines = []
        for user_id in winner_ids:
            result = await self.levels.award(guild, user_id, Rules.MVP_XP)
            line = f"🏆 <@{user_id}> mit **{votes} Stimme(n)** → **+{Rules.MVP_XP} XP**"
            if result.leveled_up:
                line += f" 🎊 Level {result.stats.level}!"
            lines.append(line)

        headline = "Gleichstand! Die MVPs des Abends sind:" if len(winner_ids) > 1 else "Der **MVP des Abends** ist:"
        embed.description = f"⏱️ **Das Voting ist beendet!**\n\n{headline}\n" + "\n".join(lines)
        if len(winner_ids) == 1 and (member := guild.get_member(winner_ids[0])):
            embed.set_thumbnail(url=member.display_avatar.url)
        embed.set_footer(text=view.progress_text)
        return embed


async def setup(bot) -> None:
    await bot.add_cog(RoundsCog(bot))
