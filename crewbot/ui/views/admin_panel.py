"""Admin-Panel: XP/Level eines Spielers bearbeiten oder zurücksetzen."""

import asyncio
from collections.abc import Awaitable, Callable
from enum import Enum

import discord

from crewbot.services.audit import AuditLog
from crewbot.services.levels import LevelService, PlayerStats, XpResult
from crewbot.ui.embeds import Palette
from crewbot.ui.views.base import PrivateView

Callback = Callable[[discord.Interaction], Awaitable[None]]


class AdminMode(Enum):
    XP = "XP"
    LEVEL = "Level"


class AdminPanelView(PrivateView):
    """Mehrstufiges Menü: Hauptmenü -> Werte anpassen / Reset bestätigen."""

    STEPS = (1, 5, 10)
    LOG_DELAY_SECONDS = 10  # schnelle Klicks werden zu einem Log-Eintrag zusammengefasst

    def __init__(
        self, levels: LevelService, audit: AuditLog, target: discord.Member, actor: discord.Member
    ) -> None:
        super().__init__(actor.id, timeout=300)
        self.levels = levels
        self.audit = audit
        self.target = target
        self.actor = actor
        self.mode: AdminMode | None = None
        self._before: PlayerStats | None = None  # Stand vor den noch nicht geloggten Änderungen
        self._change_count = 0
        self._log_task: asyncio.Task | None = None
        self._show_main()

    def build_embed(self, note: str | None = None) -> discord.Embed:
        stats = self.levels.get(self.target.id)
        if self.mode is None:
            description = "Wähle aus, was du bearbeiten möchtest."
        else:
            description = f"✏️ **{self.mode.value} bearbeiten** – nutze die +/− Buttons."

        embed = discord.Embed(
            title=f"🛠️ Admin-Panel: {self.target.display_name}",
            description=description,
            color=Palette.BRAND,
        )
        embed.set_thumbnail(url=self.target.display_avatar.url)
        embed.add_field(name="Level", value=f"**{stats.level}**")
        embed.add_field(name="XP", value=f"**{stats.xp}**")
        embed.add_field(name="Siege", value=str(stats.wins))
        embed.add_field(name="Teilnahmen", value=str(stats.participations))
        embed.add_field(name="Rang", value=stats.rank.name)
        if note:
            embed.set_footer(text=note)
        return embed

    # ---------- Ansichten ----------

    def _show_main(self) -> None:
        self.clear_items()
        self.mode = None
        self._add_button("XP bearbeiten", discord.ButtonStyle.primary, self._open_xp)
        self._add_button("Level bearbeiten", discord.ButtonStyle.primary, self._open_level)
        self._add_button("RESET", discord.ButtonStyle.danger, self._ask_reset)

    def _show_adjust(self, mode: AdminMode) -> None:
        self.clear_items()
        self.mode = mode
        for step in self.STEPS:
            self._add_button(f"+{step}", discord.ButtonStyle.success, self._adjust(step), row=0)
        for step in self.STEPS:
            self._add_button(f"−{step}", discord.ButtonStyle.danger, self._adjust(-step), row=1)
        self._add_button("Zurück", discord.ButtonStyle.secondary, self._back, row=2)

    def _show_reset_confirm(self) -> None:
        self.clear_items()
        self._add_button("Ja, alles löschen", discord.ButtonStyle.danger, self._confirm_reset)
        self._add_button("Abbrechen", discord.ButtonStyle.secondary, self._back)

    # ---------- Callbacks ----------

    async def _open_xp(self, interaction: discord.Interaction) -> None:
        self._show_adjust(AdminMode.XP)
        await self._refresh(interaction)

    async def _open_level(self, interaction: discord.Interaction) -> None:
        self._show_adjust(AdminMode.LEVEL)
        await self._refresh(interaction)

    async def _ask_reset(self, interaction: discord.Interaction) -> None:
        self._show_reset_confirm()
        await self._refresh(
            interaction, f"⚠️ Wirklich ALLE Daten von {self.target.display_name} löschen?"
        )

    async def _confirm_reset(self, interaction: discord.Interaction) -> None:
        await self._flush_log()
        old = self.levels.get(self.target.id)
        self.levels.reset(self.target.id)
        await self.audit.log(
            self.actor,
            "Spielerdaten zurückgesetzt",
            f"**Spieler:** {self.target.mention}\n**Vorher:** Level {old.level}, {old.xp} XP,"
            f" {old.wins} Siege, {old.participations} Teilnahmen",
            discord.Color.red(),
        )
        self._show_main()
        await self._refresh(interaction, "✅ Daten wurden zurückgesetzt.")

    async def _back(self, interaction: discord.Interaction) -> None:
        self._show_main()
        await self._refresh(interaction)

    def _adjust(self, delta: int) -> Callback:
        async def callback(interaction: discord.Interaction) -> None:
            stats = self.levels.get(self.target.id)
            self._remember_change(stats)
            result: XpResult
            if self.mode is AdminMode.XP:
                result = self.levels.set_xp(self.target.id, stats.xp + delta)
            else:
                result = self.levels.set_level(self.target.id, stats.level + delta)
            if result.leveled_up:
                await self.levels.grant_level_roles(self.target, result.stats.level)
            await self._refresh(interaction, f"✅ {delta:+d} {self.mode.value} angewendet.")

        return callback

    # ---------- Audit-Log ----------

    def _remember_change(self, stats: PlayerStats) -> None:
        if self._before is None:
            self._before = PlayerStats(xp=stats.xp, level=stats.level)
        self._change_count += 1
        if self._log_task and not self._log_task.done():
            self._log_task.cancel()
        self._log_task = asyncio.create_task(self._flush_log_later())

    async def _flush_log_later(self) -> None:
        await asyncio.sleep(self.LOG_DELAY_SECONDS)
        await asyncio.shield(self._flush_log())

    async def _flush_log(self) -> None:
        if self._log_task and not self._log_task.done() and self._log_task is not asyncio.current_task():
            self._log_task.cancel()
        if self._before is None:
            return
        before, count = self._before, self._change_count
        self._before, self._change_count = None, 0
        now = self.levels.get(self.target.id)
        await self.audit.log(
            self.actor,
            "XP/Level geändert",
            f"**Spieler:** {self.target.mention}\n"
            f"**XP:** {before.xp} → {now.xp}\n"
            f"**Level:** {before.level} → {now.level}\n"
            f"*{count} Änderung(en) im Admin-Panel*",
            discord.Color.orange(),
        )

    async def on_timeout(self) -> None:
        await self._flush_log()

    # ---------- Helfer ----------

    def _add_button(
        self, label: str, style: discord.ButtonStyle, callback: Callback, row: int = 0
    ) -> None:
        button = discord.ui.Button(label=label, style=style, row=row)
        button.callback = callback
        self.add_item(button)

    async def _refresh(self, interaction: discord.Interaction, note: str | None = None) -> None:
        await interaction.response.edit_message(embed=self.build_embed(note), view=self)
