"""/rundensettings: Lobbyeinstellungen der letzten Runde, umschaltbar per Kategorie-Buttons."""

import discord

from crewbot.content.among_us import ROLES as ROLE_INFO
from crewbot.content.game_settings import (
    CATEGORIES,
    GENERAL,
    ROLE_SETTINGS,
    ROLES,
    Category,
    RoleSettings,
    Setting,
    known_option_keys,
)
from crewbot.services.game_settings import GameSettings
from crewbot.ui.embeds import Palette
from crewbot.ui.views.base import CrewView


def build_settings_embed(settings: GameSettings, category: Category = GENERAL) -> discord.Embed:
    embed = discord.Embed(
        title=f"⚙️ Rundeneinstellungen · {category.emoji} {category.label}",
        color=Palette.BRAND,
        timestamp=settings.captured_at,
    )
    if category is ROLES:
        _add_roles(embed, settings)
    else:
        if category is GENERAL:
            embed.description = (
                f"🗺️ **Map:** {settings.map}\n"
                f"👥 **Spieler:** {settings.players}/{settings.max_players}\n"
                f"🔪 **Impostor:** {settings.num_impostors}"
            )
        for setting in category.settings:
            if (text := _format(settings, setting)) is not None:
                embed.add_field(name=setting.label, value=text, inline=True)
        if category is GENERAL and (extra := _unknown_options(settings)):
            embed.add_field(name="🧩 Weitere Einstellungen", value=extra, inline=False)

    lobby = f"Lobby {settings.lobby_code} • " if settings.lobby_code else ""
    embed.set_footer(text=f"{lobby}Stand beim Start der letzten Runde")
    return embed


def _format(settings: GameSettings, setting: Setting) -> str | None:
    value = settings.value(setting.kind, setting.key)
    return None if value is None else setting.format(value)


def _role_name(role: RoleSettings | str) -> str:
    name = role.name if isinstance(role, RoleSettings) else role
    info = ROLE_INFO.get(name)
    return f"{info.emoji} {name}" if info else f"🎭 {name}"


def _add_roles(embed: discord.Embed, settings: GameSettings) -> None:
    for impostor, title in ((False, "🟢 Crew-Rollen"), (True, "🔴 Impostor-Rollen")):
        active, inactive = [], []
        for role in ROLE_SETTINGS:
            rate = settings.roles.get(role.game_name)
            if role.impostor != impostor or rate is None:
                continue
            if not rate.active:
                inactive.append(role.name)
                continue
            details = " · ".join(
                f"{s.label} {text}" for s in role.settings if (text := _format(settings, s)) is not None
            )
            line = f"**{_role_name(role)}** – {rate.count}× · {rate.chance} %"
            active.append(line + (f"\n└ {details}" if details else ""))
        lines = active or ["Keine aktiv"]
        if inactive:
            lines.append(f"*Aus: {', '.join(inactive)}*")
        embed.add_field(name=title, value=_fit(lines), inline=False)

    # Rollen aus neueren Spielversionen, die der Bot noch nicht kennt
    known = {role.game_name for role in ROLE_SETTINGS}
    unknown = [
        f"**{_role_name(name)}** – {rate.count}× · {rate.chance} %"
        for name, rate in settings.roles.items()
        if name not in known and rate.active
    ]
    if unknown:
        embed.add_field(name="🆕 Weitere Rollen", value=_fit(unknown), inline=False)


def _unknown_options(settings: GameSettings) -> str:
    known = known_option_keys()
    lines = [
        f"`{key}`: {value}"
        for kind in ("ints", "floats", "bools")
        for key, value in settings.options.get(kind, {}).items()
        if key not in known
    ]
    return _fit(lines)


def _fit(lines: list[str], limit: int = 1024) -> str:
    text = ""
    for index, line in enumerate(lines):
        if len(text) + len(line) + 30 > limit:
            return text + f"… und {len(lines) - index} weitere"
        text += line + "\n"
    return text.strip()


class GameSettingsView(CrewView):
    """Ein Button pro Kategorie; der aktive ist hervorgehoben. Jeder darf umschalten."""

    def __init__(self, settings: GameSettings, current: Category = GENERAL) -> None:
        super().__init__(timeout=600)
        self.settings = settings
        self.message: discord.Message | None = None
        for category in CATEGORIES:
            self.add_item(_CategoryButton(category, active=category is current))

    async def show(self, interaction: discord.Interaction, category: Category) -> None:
        for item in self.children:
            if isinstance(item, _CategoryButton):
                item.set_active(item.category is category)
        await interaction.response.edit_message(embed=build_settings_embed(self.settings, category), view=self)

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True
        if self.message is not None:
            try:
                await self.message.edit(view=self)
            except discord.HTTPException:
                pass


class _CategoryButton(discord.ui.Button["GameSettingsView"]):
    def __init__(self, category: Category, *, active: bool) -> None:
        super().__init__(label=category.label, emoji=category.emoji)
        self.category = category
        self.set_active(active)

    def set_active(self, active: bool) -> None:
        self.style = discord.ButtonStyle.primary if active else discord.ButtonStyle.secondary

    async def callback(self, interaction: discord.Interaction) -> None:
        await self.view.show(interaction, self.category)
