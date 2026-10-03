"""Rundenende: Gewinner bestimmen, XP & Siege vergeben und Wetten auszahlen."""

from dataclasses import dataclass

import discord

from crewbot.config import Rules
from crewbot.content.among_us import Team
from crewbot.services.betting import Bet, BetService
from crewbot.services.levels import LevelService, XpResult


@dataclass
class RoundOutcome:
    winning_team: Team
    xp_per_winner: int
    winners: list[tuple[int, XpResult]]  # (Discord-ID, Ergebnis)
    bets: list[tuple[Bet, XpResult | None]]  # None = Wette verloren


class RoundService:
    def __init__(self, levels: LevelService, bets: BetService) -> None:
        self._levels = levels
        self._bets = bets

    @staticmethod
    def winners_for(
        team: Team, impostors: list[discord.Member], players: list[discord.Member]
    ) -> list[discord.Member]:
        """Impostor-Sieg: die Impostor. Crew-Sieg: alle Spieler außer den Impostorn."""
        if team is Team.IMPOSTOR:
            return impostors
        impostor_ids = {m.id for m in impostors}
        return [p for p in players if not p.bot and p.id not in impostor_ids]

    async def finish(
        self, guild: discord.Guild, winning_team: Team, winner_ids: list[int]
    ) -> RoundOutcome:
        xp = Rules.IMPOSTOR_WIN_XP if winning_team is Team.IMPOSTOR else Rules.CREW_WIN_XP
        winner_results = [
            (user_id, await self._levels.award(guild, user_id, xp, win=True)) for user_id in winner_ids
        ]

        bet_results: list[tuple[Bet, XpResult | None]] = []
        for bet in self._bets.settle():
            if bet.team is winning_team:
                bet_results.append((bet, await self._levels.award(guild, bet.user_id, bet.payout)))
            else:
                bet_results.append((bet, None))

        return RoundOutcome(winning_team, xp, winner_results, bet_results)
