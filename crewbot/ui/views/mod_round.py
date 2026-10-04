"""Anzeige automatisch ausgewerteter Mod-Runden + „Zurücksetzen“-Button."""

import discord

from crewbot.content.among_us import Team, role_label
from crewbot.services.insights import round_highlights
from crewbot.services.mod_rounds import ModRoundError, ModRoundResult
from crewbot.ui.embeds import Palette, Tone, notify
from crewbot.ui.views.base import PrivateView

REASON_TEXTS = {
    "CrewmatesByVote": "Alle Impostor rausgevotet",
    "CrewmatesByTask": "Alle Tasks erledigt",
    "ImpostorsByVote": "Crew hat sich selbst rausgevotet",
    "ImpostorsByKill": "Alle Crewmates getötet",
    "ImpostorsBySabotage": "Sabotage nicht rechtzeitig repariert",
    "ImpostorDisconnect": "Impostor hat das Spiel verlassen",
    "CrewmateDisconnect": "Crewmates haben das Spiel verlassen",
}
DEATH_ICONS = {"kill": "💀", "ejected": "🚪", "disconnect": "🔌"}
FIELD_LIMIT = 1024
UNDONE_PREFIX = "↩️ ZURÜCKGESETZT • "


def _fit(lines: list[str], empty: str = "–") -> str:
    """Zeilen zusammenfügen, ohne das Discord-Limit pro Feld zu sprengen."""
    text = ""
    for index, line in enumerate(lines):
        if len(text) + len(line) + 30 > FIELD_LIMIT:
            return text + f"… und {len(lines) - index} weitere"
        text += line + "\n"
    return text.strip() or empty


def build_mod_round_embed(result: ModRoundResult) -> discord.Embed:
    round_ = result.round
    team = round_.winning_team
    if team is Team.IMPOSTOR:
        title, color = "🔪 IMPOSTOR SIEG", Palette.for_team(team)
    elif team is Team.CREW:
        title, color = "🚀 CREWMATE SIEG", Palette.for_team(team)
    else:
        title, color = "❔ RUNDE OHNE KLAREN SIEGER", Palette.INFO

    minutes, seconds = divmod(int(round_.duration_seconds), 60)
    reason = REASON_TEXTS.get(round_.end_reason, round_.end_reason)
    embed = discord.Embed(
        title=title,
        description=f"🗺️ **{round_.map}** • ⏱️ {minutes}:{seconds:02d} min • {reason}",
        color=color,
    )

    impostors = [p for p in round_.players if p.team == Team.IMPOSTOR.value]
    crew = [p for p in round_.players if p.team != Team.IMPOSTOR.value]

    def death(p) -> str:
        return f" {DEATH_ICONS[p.death_cause]}" if p.death_cause in DEATH_ICONS else ""

    embed.add_field(
        name="🔪 Impostor",
        value=_fit([f"{result.mention(p)} – {role_label(p.role)} • {p.kills} Kill(s){death(p)}" for p in impostors]),
        inline=False,
    )
    embed.add_field(
        name="🚀 Crew",
        value=_fit(
            [
                f"{result.mention(p)} – {role_label(p.role)} • 🧰 {p.tasks_completed}/{p.tasks_total}{death(p)}"
                for p in crew
            ]
        ),
        inline=False,
    )

    # Highlights
    highlights = []
    tasks_done = sum(p.tasks_completed for p in crew)
    tasks_total = sum(p.tasks_total for p in crew)
    highlights.append(f"🧰 Tasks: **{tasks_done}/{tasks_total}**")
    reports = sum(m.body is not None for m in round_.meetings)
    highlights.append(f"📢 Meetings: **{len(round_.meetings)}** ({reports}× Leiche gemeldet)")
    for meeting in round_.meetings:
        if (exiled := round_.player(meeting.exiled)) is not None:
            verdict = "✅ war Impostor" if exiled.team == Team.IMPOSTOR.value else "❌ war unschuldig"
            highlights.append(f"🚪 {result.mention(exiled)} rausgewählt – {verdict}")
    highlights += round_highlights(round_, result.mention)
    embed.add_field(name="📊 Highlights", value=_fit(highlights), inline=False)

    # Belohnungen
    outcome = result.outcome
    if outcome is not None:
        rewards = []
        for user_id, xp_result in outcome.winners:
            line = f"<@{user_id}> +{outcome.xp_per_winner} XP"
            if xp_result.leveled_up:
                line += f" 🎊 Level {xp_result.stats.level}!"
            rewards.append(line)
        embed.add_field(name="🏆 Belohnungen", value=_fit(rewards, "Keine verknüpften Gewinner"), inline=False)
        if outcome.bets:
            bet_lines = [
                f"✅ <@{bet.user_id}> gewinnt **{bet.payout} XP**" if res else f"❌ <@{bet.user_id}> verliert {bet.amount} XP"
                for bet, res in outcome.bets
            ]
            embed.add_field(name="🎰 Wettbüro", value=_fit(bet_lines), inline=False)

    if result.achievements:
        lines = []
        for unlock in result.achievements:
            line = f"<@{unlock.user_id}> {unlock.achievement.label} (+{unlock.achievement.xp} XP)"
            if unlock.result.leveled_up:
                line += f" 🎊 Level {unlock.result.stats.level}!"
            lines.append(line)
        embed.add_field(name="🏅 Erfolge freigeschaltet", value=_fit(lines), inline=False)

    if result.unlinked:
        embed.add_field(
            name="⚠️ Nicht verknüpft (bekommen keine XP)",
            value=_fit([f"*{p.name}*" for p in result.unlinked])
            + "\n→ Mit `/verknuepfen` den Among-Us-Friendcode hinterlegen!",
            inline=False,
        )

    footer = "Automatisch ausgewertet per CrewStats-Mod"
    if round_.lobby_code:
        footer += f" • Lobby {round_.lobby_code}"
    embed.set_footer(text=footer)
    embed.timestamp = discord.utils.utcnow()
    return embed


# ==========================================
# ZURÜCKSETZEN
# ==========================================


class UndoRoundButton(
    discord.ui.DynamicItem[discord.ui.Button], template=r"crewstats:undo:(?P<game_id>[0-9a-f]+)"
):
    """Dauerhafter Button – funktioniert dank DynamicItem auch nach Bot-Neustarts."""

    def __init__(self, game_id: str, *, disabled: bool = False) -> None:
        super().__init__(
            discord.ui.Button(
                label="Zurücksetzen",
                emoji="↩️",
                style=discord.ButtonStyle.danger,
                custom_id=f"crewstats:undo:{game_id}",
                disabled=disabled,
            )
        )
        self.game_id = game_id

    @classmethod
    async def from_custom_id(cls, interaction, item, match) -> "UndoRoundButton":
        return cls(match["game_id"])

    @staticmethod
    def view_for(game_id: str, *, disabled: bool = False) -> discord.ui.View:
        view = discord.ui.View(timeout=None)
        view.add_item(UndoRoundButton(game_id, disabled=disabled))
        return view

    async def callback(self, interaction: discord.Interaction) -> None:
        if not interaction.user.guild_permissions.mute_members:
            return await notify(interaction, "🚫 Nur Hosts dürfen Runden zurücksetzen.", Tone.ERROR)
        confirm = UndoConfirmView(interaction.user.id, self.game_id, interaction.message)
        await interaction.response.send_message(
            embed=discord.Embed(
                description=(
                    "⚠️ **Runde wirklich zurücksetzen?**\nAlle XP, Siege, Wetten-Auszahlungen und"
                    " Statistiken dieser Runde werden wieder entfernt. Offene Wetten werden wiederhergestellt."
                ),
                color=Palette.WARNING,
            ),
            view=confirm,
            ephemeral=True,
        )


class UndoConfirmView(PrivateView):
    def __init__(self, owner_id: int, game_id: str, summary: discord.Message) -> None:
        super().__init__(owner_id, timeout=60)
        self.game_id = game_id
        self.summary = summary

    @discord.ui.button(label="Ja, zurücksetzen", emoji="↩️", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.stop()
        bot = self.bot_of(interaction)
        try:
            bot.mod_rounds.undo(self.game_id)
        except ModRoundError as exc:
            return await interaction.response.edit_message(
                embed=discord.Embed(description=f"❌ {exc}", color=Palette.ERROR), view=None
            )

        embed = self.summary.embeds[0]
        embed.title = UNDONE_PREFIX + (embed.title or "")
        embed.color = discord.Color.dark_grey()
        embed.set_footer(text=f"Zurückgesetzt von {interaction.user.display_name} • {embed.footer.text or ''}")
        try:
            await self.summary.edit(embed=embed, view=UndoRoundButton.view_for(self.game_id, disabled=True))
        except discord.HTTPException:
            pass

        await bot.audit.log(
            interaction.user,
            "Mod-Runde zurückgesetzt",
            f"[Zur Runde]({self.summary.jump_url}) – XP, Siege, Wetten und Statistiken wurden entfernt.",
            Palette.WARNING,
        )
        await interaction.response.edit_message(
            embed=discord.Embed(description="✅ Runde wurde zurückgesetzt.", color=Palette.SUCCESS), view=None
        )

    @discord.ui.button(label="Abbrechen", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.stop()
        await interaction.response.edit_message(
            embed=discord.Embed(description="Abgebrochen.", color=Palette.INFO), view=None
        )
