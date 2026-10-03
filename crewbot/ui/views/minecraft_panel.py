"""/dc: Minecraft-Ankündigungen einstellen – Kanal, Nachrichtenarten, Vorschau und Webhook fürs Plugin."""

import discord

from crewbot.content.minecraft import McEventType
from crewbot.services.audit import AuditLog
from crewbot.services.minecraft import McSetupError, MinecraftService
from crewbot.ui.embeds import Palette, Tone, notify
from crewbot.ui.minecraft import build_event_embed, sample_events
from crewbot.ui.views.base import PrivateView

PLUGIN_CONFIG_PATH = "plugins/CoreHold/config.yml"


def _types_text(types: set[McEventType]) -> str:
    return ", ".join(t.info.label for t in McEventType if t in types) or "keine"


class MinecraftPanelView(PrivateView):
    def __init__(self, service: MinecraftService, audit: AuditLog, actor: discord.Member) -> None:
        super().__init__(actor.id, timeout=600)
        self.service = service
        self.audit = audit
        self.actor = actor
        self._build()

    def build_embed(self, note: str | None = None) -> discord.Embed:
        s = self.service
        embed = discord.Embed(
            title="⛏️ Minecraft → Discord",
            description="Ankündigungen vom CoreSMP-Server. Hier stellst du ein, **wo** sie erscheinen und **welche**.",
            color=Palette.SUCCESS,
        )
        embed.add_field(name="📢 Kanal", value=f"<#{s.channel_id}>")
        if not s.webhook_id:
            status = "❌ Noch nicht verbunden – klick auf **Webhook-URL**"
        elif s.last_received:
            status = f"✅ Letzte Nachricht {discord.utils.format_dt(s.last_received, 'R')}"
        else:
            status = "⏳ Seit dem Bot-Start noch nichts empfangen"
        embed.add_field(name="🔌 Plugin", value=status)
        embed.add_field(
            name="📨 Nachrichten",
            value="\n".join(
                f"{'✅' if s.is_enabled(t) else '❌'} {t.info.emoji} {t.info.label}" for t in McEventType
            ),
            inline=False,
        )
        if note:
            embed.set_footer(text=note)
        return embed

    def _build(self) -> None:
        self.clear_items()
        self.channel_select = discord.ui.ChannelSelect(
            placeholder="📢 Anderen Kanal für die Ankündigungen wählen",
            channel_types=[discord.ChannelType.text, discord.ChannelType.news],
            row=0,
        )
        self.channel_select.callback = self._on_channel
        self.add_item(self.channel_select)

        self.type_select = discord.ui.Select(
            placeholder="📨 Welche Nachrichten sollen gesendet werden?",
            min_values=0,
            max_values=len(McEventType),
            options=[
                discord.SelectOption(
                    label=t.info.label,
                    value=t.value,
                    emoji=t.info.emoji,
                    description=t.info.description,
                    default=self.service.is_enabled(t),
                )
                for t in McEventType
            ],
            row=1,
        )
        self.type_select.callback = self._on_types
        self.add_item(self.type_select)

        for label, style, callback in (
            ("👀 Vorschau", discord.ButtonStyle.secondary, self._preview),
            ("🔗 Webhook-URL", discord.ButtonStyle.primary, self._webhook),
        ):
            button = discord.ui.Button(label=label, style=style, row=2)
            button.callback = callback
            self.add_item(button)

    async def _refresh(self, interaction: discord.Interaction, note: str | None = None) -> None:
        self._build()
        await interaction.response.edit_message(embed=self.build_embed(note), view=self)

    # ---------- Callbacks ----------

    async def _on_channel(self, interaction: discord.Interaction) -> None:
        picked = self.channel_select.values[0]
        channel = interaction.guild.get_channel(picked.id)
        if channel is None:
            return await self._refresh(interaction, "❌ Diesen Kanal kann ich nicht sehen.")
        perms = channel.permissions_for(interaction.guild.me)
        if not (perms.view_channel and perms.send_messages and perms.embed_links):
            return await self._refresh(
                interaction, f"❌ In #{channel.name} fehlen mir Rechte (Nachrichten senden + Links einbetten)."
            )

        old = self.service.channel_id
        self.service.set_channel(channel.id)
        await self._refresh(interaction, f"✅ Ankündigungen kommen ab jetzt in #{channel.name}.")
        if old != channel.id:
            await self.audit.log(self.actor, "Minecraft-Kanal geändert", f"<#{old}> → {channel.mention}")

    async def _on_types(self, interaction: discord.Interaction) -> None:
        before = set(self.service.enabled)
        after = {McEventType(value) for value in self.type_select.values}
        self.service.set_enabled(after)
        await self._refresh(interaction, "✅ Auswahl gespeichert." if after else "🔕 Alle Minecraft-Nachrichten sind aus.")
        if before != after:
            await self.audit.log(
                self.actor,
                "Minecraft-Nachrichten geändert",
                f"**Vorher:** {_types_text(before)}\n**Jetzt:** {_types_text(after)}",
            )

    async def _preview(self, interaction: discord.Interaction) -> None:
        samples = [e for e in sample_events() if self.service.is_enabled(e.type)]
        text = "👀 So sehen die Nachrichten aus (Beispiele, nur für dich sichtbar):"
        if not samples:
            samples = sample_events()
            text = "🔕 Gerade ist alles aus – so *würden* die Nachrichten aussehen:"
        embeds = [build_event_embed(e, "CoreSMP") for e in samples]
        await interaction.response.send_message(content=text, embeds=embeds[:10], ephemeral=True)

    async def _webhook(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer()
        try:
            url = await self.service.webhook_url(interaction.client, reason=f"/dc von {interaction.user}")
        except discord.Forbidden:
            return await notify(interaction, "❌ Mir fehlt im Log-Channel das Recht **Webhooks verwalten**.", Tone.ERROR)
        except (McSetupError, discord.HTTPException) as exc:
            return await notify(interaction, f"❌ Webhook konnte nicht eingerichtet werden: {exc}", Tone.ERROR)

        self._build()
        await interaction.edit_original_response(embed=self.build_embed(), view=self)
        view = WebhookView(self.service, self.audit, self.actor)
        await interaction.followup.send(embed=webhook_embed(url), view=view, ephemeral=True)


def webhook_embed(url: str) -> discord.Embed:
    return discord.Embed(
        title="🔗 Plugin mit dem Bot verbinden",
        description=(
            f"1. Auf dem Minecraft-Server `{PLUGIN_CONFIG_PATH}` öffnen\n"
            "2. Ganz unten bei `discord:` die URL eintragen (mit Anführungszeichen):\n"
            f'```yaml\ndiscord:\n  webhook-url: "{url}"\n```'
            "3. `/special reload` ausführen (oder Server neu starten)\n\n"
            "⚠️ **Die URL ist geheim!** Wer sie kennt, kann gefälschte Nachrichten einschleusen."
            " Falls sie rausgerutscht ist: **Neue URL erzeugen** – die alte funktioniert dann nicht mehr."
        ),
        color=Palette.INFO,
    )


class WebhookView(PrivateView):
    """Unter der Webhook-URL: bei Bedarf eine neue erzeugen (die alte wird ungültig)."""

    def __init__(self, service: MinecraftService, audit: AuditLog, actor: discord.Member) -> None:
        super().__init__(actor.id, timeout=600)
        self.service = service
        self.audit = audit
        self.actor = actor

    @discord.ui.button(label="♻️ Neue URL erzeugen", style=discord.ButtonStyle.danger)
    async def renew(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        await interaction.response.defer()
        try:
            url = await self.service.webhook_url(
                interaction.client, reason=f"/dc von {interaction.user}: neue URL", renew=True
            )
        except discord.Forbidden:
            return await notify(interaction, "❌ Mir fehlt im Log-Channel das Recht **Webhooks verwalten**.", Tone.ERROR)
        except (McSetupError, discord.HTTPException) as exc:
            return await notify(interaction, f"❌ Webhook konnte nicht erneuert werden: {exc}", Tone.ERROR)

        embed = webhook_embed(url)
        embed.set_footer(text="♻️ Neue URL erzeugt – die alte ist ab sofort ungültig.")
        await interaction.edit_original_response(embed=embed, view=self)
        await self.audit.log(self.actor, "Minecraft-Webhook erneuert", "Die alte Plugin-URL ist ungültig.")
