"""Wettbüro: XP-Wetten auf den Ausgang einer Runde.

Offene Wetten werden gespeichert, damit bei einem Neustart des Bots keine
bereits abgezogenen Einsätze verloren gehen.
"""

from dataclasses import dataclass

from crewbot.config import Rules
from crewbot.content.among_us import Team
from crewbot.services.levels import LevelService
from crewbot.services.storage import JsonStore


@dataclass(frozen=True)
class Bet:
    user_id: int
    team: Team
    amount: int

    @property
    def payout(self) -> int:
        return self.amount * Rules.BET_PAYOUT_FACTOR


class BetError(Exception):
    """Fehler, dessen Nachricht direkt dem Spieler angezeigt werden kann."""


class BetService:
    def __init__(self, store: JsonStore, levels: LevelService) -> None:
        self._store = store
        self._levels = levels
        self._bets: dict[int, Bet] = {
            int(uid): Bet(int(uid), Team(data["team"]), int(data["amount"]))
            for uid, data in store.load().items()
        }

    @property
    def active(self) -> list[Bet]:
        return list(self._bets.values())

    def place(self, user_id: int, team: Team, amount: int) -> Bet:
        """Platziert eine Wette und zieht den Einsatz sofort ab."""
        if existing := self._bets.get(user_id):
            raise BetError(
                f"Du hast bereits **{existing.amount} XP** auf"
                f" **{existing.team.label}** gesetzt. Warte bis die Runde"
                " ausgewertet wird!"
            )
        if not 0 < amount <= Rules.MAX_BET:
            raise BetError(f"Dein Einsatz muss zwischen 1 und {Rules.MAX_BET} XP liegen!")

        balance = self._levels.get(user_id).xp
        if balance < amount:
            raise BetError(f"Du hast nicht genug XP! (Dein Guthaben: {balance} XP)")

        self._levels.remove_xp(user_id, amount)
        bet = Bet(user_id, team, amount)
        self._bets[user_id] = bet
        self._save()
        return bet

    def settle(self) -> list[Bet]:
        """Beendet die Wettrunde und gibt alle Wetten zur Auszahlung zurück."""
        bets = self.active
        self._bets.clear()
        self._save()
        return bets

    def refund_all(self) -> list[Bet]:
        """Bricht die Wettrunde ab und erstattet alle Einsätze."""
        bets = self.settle()
        for bet in bets:
            self._levels.add_xp(bet.user_id, bet.amount)
        return bets

    def restore(self, bets: list[Bet]) -> None:
        """Stellt ausgewertete Wetten wieder her (für „Runde zurücksetzen“).

        Hat ein Spieler inzwischen neu gewettet, bekommt er den alten Einsatz
        stattdessen erstattet – die neue Wette bleibt bestehen.
        """
        for bet in bets:
            if bet.user_id in self._bets:
                self._levels.add_xp(bet.user_id, bet.amount)
            else:
                self._bets[bet.user_id] = bet
        self._save()

    def _save(self) -> None:
        self._store.save(
            {
                str(bet.user_id): {"team": bet.team.value, "amount": bet.amount}
                for bet in self._bets.values()
            }
        )
