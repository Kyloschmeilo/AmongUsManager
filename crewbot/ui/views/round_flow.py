"""Runden-Assistent: Runde in 2 Schritten beenden (Sieger-Team -> Impostor wählen)."""

from typing import TYPE_CHECKING

import discord

from crewbot.config import Channels
from crewbot.content.among_us import Team
from crewbot.services.rounds import RoundOutcome, RoundService
from crewbot.services.voice import VoiceLike
from crewbot.ui.embeds import Palette
from crewbot.ui.views.base import PrivateView

if TYPE_CHECKING:
    from crewbot.core.client import AmongUsBot


# ==========================================
# AUSWERTUNG (auch von /crew-win und /impostor-win genutzt)
# ==========================================


def round_result_embed(outcome: RoundOutcome) -> discord.Embed:
    team = outcome.winning_team
    title = "🔪 IMPOSTOR SIEG" if team is Team.IMPOSTOR else "🚀 CREWMATE SIEG"
    embed = discord.Embed(title=title, color=Palette.for_team(team))

    winner_lines = []
    for user_id, result in outcome.winners:
        line = f"• <@{user_id}> (+{outcome.xp_per_winner} XP)"
        if result.leveled_up:
            line += f" 🎊 **Level {result.stats.level}!**"
        winner_lines.append(line)
    embed.add_field(name="🏆 Gewinner", value="\n".join(winner_lines) or "Keine Gewinner erfasst.", inline=False)

    bet_lines = []
    for bet, result in outcome.bets:
        if result is None:
            bet_lines.append(f"❌ <@{bet.user_id}> verliert den Einsatz von *{bet.amount} XP*.")
            continue
        line = f"✅ <@{bet.user_id}> gewinnt **{bet.payout} XP**!"
        if result.leveled_up:
            line += f" 🎊 **Level {result.stats.level}!**"
        bet_lines.append(line)
    embed.add_field(
        name="🎰 Wettbüro",
        value="\n".join(bet_lines) or "ℹ️ In dieser Runde waren keine Wetten aktiv.",
        inline=False,
    )
    return embed


async def evaluate_round(
    bot: "AmongUsBot", host: discord.Member, team: Team, winners: list[discord.Member]
) -> discord.Embed:
    """Wertet die Runde aus, schreibt ins Audit-Log und liefert das Ergebnis-Embed."""
    outcome = await bot.rounds.finish(host.guild, team, [m.id for m in winners])
    names = ", ".join(f"<@{uid}>" for uid, _ in outcome.winners) or "–"
    await bot.audit.log(
        host,
        "Runde ausgewertet",
        f"**Sieger:** {team.label}\n**Gewinner:** {names}\n**Ausgewertete Wetten:** {len(outcome.bets)}",
        Palette.for_team(team),
    )
    return round_result_embed(outcome)


# ==========================================
# ASSISTENT
# ==========================================


class ImpostorSelect(discord.ui.UserSelect["RoundFlowView"]):
    def __init__(self, selected: list[discord.Member]) -> None:
        super().__init__(
            placeholder="Wer war Impostor? (1–3 Spieler)",
            min_values=1,
            max_values=3,
            default_values=[discord.Object(m.id) for m in selected],  # bleibt nach Neu-Zeichnen ausgewählt
            row=0,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        assert self.view is not None
        self.view.impostors = [u for u in self.values if isinstance(u, discord.Member)]
        self.view.show_impostor_step()
        await self.view.refresh(interaction)


class RoundFlowView(PrivateView):
    def __init__(
        self, host: discord.Member, voice: VoiceLike, panel_message: discord.Message | None
    ) -> None:
        super().__init__(host.id, timeout=300)
        self.host = host
        self.voice = voice
        self.panel_message = panel_message
        self.team: Team | None = None
        self.impostors: list[discord.Member] = []
        self.show_team_step()

    @property
    def winners(self) -> list[discord.Member]:
        return RoundService.winners_for(self.team, self.impostors, self.voice.members) if self.team else []

    def build_embed(self) -> discord.Embed:
        embed = discord.Embed(title="🏁 Runde beenden", color=Palette.BRAND)
        if self.team is None:
            embed.description = "**Schritt 1/2:** Welches Team hat gewonnen?"
            return embed

        embed.description = f"**Sieger:** {self.team.label}\n**Schritt 2/2:** Wer war Impostor?"
        embed.add_field(
            name="🔪 Impostor",
            value=", ".join(m.mention for m in self.impostors) or "*noch niemand gewählt*",
            inline=False,
        )
        winners = self.winners
        embed.add_field(
            name=f"🏆 Bekommen einen Sieg ({len(winners)})",
            value=", ".join(m.mention for m in winners) or "–",
            inline=False,
        )
        return embed

    # ---------- Schritte ----------

    def show_team_step(self) -> None:
        self.clear_items()
        self.team = None
        self._add_button("Crewmates", "🟢", discord.ButtonStyle.success, self._pick_crew)
        self._add_button("Impostor", "🔴", discord.ButtonStyle.danger, self._pick_impostor)
        self._add_button("Abbrechen", None, discord.ButtonStyle.secondary, self._cancel)

    def show_impostor_step(self) -> None:
        self.clear_items()
        self.add_item(ImpostorSelect(self.impostors))
        confirm = self._add_button("Auswerten", "✅", discord.ButtonStyle.success, self._confirm, row=1)
        confirm.disabled = not self.impostors
        self._add_button("Zurück", None, discord.ButtonStyle.secondary, self._back, row=1)

    # ---------- Callbacks ----------

    async def _pick_crew(self, interaction: discord.Interaction) -> None:
        await self._pick(interaction, Team.CREW)

    async def _pick_impostor(self, interaction: discord.Interaction) -> None:
        await self._pick(interaction, Team.IMPOSTOR)

    async def _pick(self, interaction: discord.Interaction, team: Team) -> None:
        self.team = team
        self.show_impostor_step()
        await self.refresh(interaction)

    async def _back(self, interaction: discord.Interaction) -> None:
        self.show_team_step()
        await self.refresh(interaction)

    async def _cancel(self, interaction: discord.Interaction) -> None:
        self.stop()
        await interaction.response.edit_message(
            embed=discord.Embed(description="Abgebrochen.", color=Palette.INFO), view=None
        )

    async def _confirm(self, interaction: discord.Interaction) -> None:
        self.stop()
        await interaction.response.edit_message(
            embed=discord.Embed(description="⏳ Runde wird ausgewertet...", color=Palette.INFO), view=None
        )
        bot = self.bot_of(interaction)

        result = await evaluate_round(bot, self.host, self.team, self.winners)
        await interaction.channel.send(embed=result)

        # Runde vorbei -> alle wieder entmuten (und „taub“ vom Auto-Mute aufheben)
        report = await bot.workers.release(self.voice.members)
        if self.voice.id == Channels.AMONG_US_VOICE:
            bot.voice_status.refresh(False)

        if self.panel_message is not None:
            from crewbot.ui.views.control_panel import ControlPanelView

            await ControlPanelView.update_panel(self.panel_message, bot, "🏁 Runde beendet – bereit für die nächste")

        await interaction.edit_original_response(
            embed=discord.Embed(
                description=f"✅ Runde ausgewertet!\n{report.describe('entmutet')}",
                color=Palette.SUCCESS,
            )
        )

    # ---------- Helfer ----------

    def _add_button(self, label, emoji, style, callback, row: int = 0) -> discord.ui.Button:
        button = discord.ui.Button(label=label, emoji=emoji, style=style, row=row)
        button.callback = callback
        self.add_item(button)
        return button

    async def refresh(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(embed=self.build_embed(), view=self)
