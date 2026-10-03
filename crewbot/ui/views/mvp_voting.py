"""MVP-Abstimmung: ein Button pro Spieler aus dem Voice-Channel."""

from collections import Counter

import discord

from crewbot.ui.embeds import Tone, notify
from crewbot.ui.views.base import CrewView


class MvpVoteButton(discord.ui.Button["MvpVotingView"]):
    def __init__(self, candidate: discord.Member) -> None:
        super().__init__(label=candidate.display_name[:80], style=discord.ButtonStyle.secondary)
        self.candidate = candidate

    async def callback(self, interaction: discord.Interaction) -> None:
        assert self.view is not None
        await self.view.cast_vote(interaction, self.candidate)


class MvpVotingView(CrewView):
    MAX_CANDIDATES = 25  # Discord erlaubt max. 25 Buttons pro Nachricht

    def __init__(self, players: list[discord.Member], *, timeout: float) -> None:
        super().__init__(timeout=timeout)
        self.voter_ids = {player.id for player in players}
        self.votes: dict[int, int] = {}  # Wähler-ID -> Kandidaten-ID
        for player in players[: self.MAX_CANDIDATES]:
            self.add_item(MvpVoteButton(player))

    @property
    def progress_text(self) -> str:
        return f"🗳️ {len(self.votes)}/{len(self.voter_ids)} Stimmen abgegeben"

    async def cast_vote(self, interaction: discord.Interaction, candidate: discord.Member) -> None:
        voter_id = interaction.user.id
        if voter_id not in self.voter_ids:
            return await notify(interaction, "🚫 Nur Spieler aus der Runde dürfen abstimmen.", Tone.ERROR)
        if voter_id == candidate.id:
            return await notify(interaction, "😏 Für dich selbst stimmen gilt nicht!", Tone.WARNING)

        changed = voter_id in self.votes
        self.votes[voter_id] = candidate.id

        embed = interaction.message.embeds[0]
        embed.set_footer(text=self.progress_text)
        await interaction.response.edit_message(embed=embed)
        verb = "🔄 Stimme geändert auf" if changed else "🎯 Du hast gestimmt für"
        await notify(interaction, f"{verb} **{candidate.display_name}**!", Tone.SUCCESS)

    def results(self) -> tuple[list[int], int]:
        """Gibt (Gewinner-IDs, Stimmen) zurück. Bei Gleichstand gibt es mehrere Gewinner."""
        if not self.votes:
            return [], 0
        counts = Counter(self.votes.values())
        top = max(counts.values())
        return [cid for cid, count in counts.items() if count == top], top
