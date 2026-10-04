"""Erfolge: prüft nach jeder Mod-Runde, wer etwas Neues freigeschaltet hat, und vergibt XP.

Freigeschaltete Erfolge stehen in `data/achievements.json` ({user_id: {achievement_id: Zeitpunkt}}).
Wird eine Runde zurückgesetzt, nimmt `revoke` die dort freigeschalteten Erfolge samt XP zurück.
"""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone

import discord

from crewbot.content.achievements import ACHIEVEMENTS, ACHIEVEMENTS_BY_ID, STREAK_LENGTH, Achievement
from crewbot.services.game_stats import GameStats
from crewbot.services.insights import fastest_double_kill, vote_accuracy, votes_received
from crewbot.services.levels import LevelService, XpResult
from crewbot.services.mod_rounds import ModPlayer
from crewbot.services.round_history import HistoryRound
from crewbot.services.storage import JsonStore


@dataclass
class Unlock:
    user_id: int
    achievement: Achievement
    result: XpResult


# ---------- Prüfungen für Runden-Erfolge: (Runde, Spieler) -> geschafft? ----------


def _double_kill(history: HistoryRound, player: ModPlayer) -> bool:
    return player.player_id in fastest_double_kill(history.round)


def _invisible(history: HistoryRound, player: ModPlayer) -> bool:
    round_ = history.round
    return (
        player.is_impostor and player.won and bool(round_.meetings)
        and votes_received(round_)[player.player_id] == 0
    )


def _lone_wolf(history: HistoryRound, player: ModPlayer) -> bool:
    partners = [p for p in history.round.players if p.is_impostor and p.player_id != player.player_id]
    return player.is_impostor and player.won and not player.is_dead and bool(partners) and all(p.is_dead for p in partners)


def _sharp_eye(history: HistoryRound, player: ModPlayer) -> bool:
    right, wrong = vote_accuracy(history.round).get(player.player_id, (0, 0))
    return right >= 2 and wrong == 0


ROUND_CHECKS: dict[str, Callable[[HistoryRound, ModPlayer], bool]] = {
    "double_kill": _double_kill,
    "invisible": _invisible,
    "lone_wolf": _lone_wolf,
    "sharp_eye": _sharp_eye,
}

# ---------- Serien: (Spieler in einer Runde) -> zählt für die Serie? ----------

STREAK_CHECKS: dict[str, Callable[[ModPlayer], bool]] = {
    "survivor_streak": lambda p: not p.is_dead and not p.disconnected,
    "win_streak": lambda p: p.won,
}


class AchievementService:
    def __init__(self, store: JsonStore, levels: LevelService) -> None:
        self._store = store
        self._levels = levels
        self._unlocked: dict[int, dict[str, str]] = {int(uid): dict(ids) for uid, ids in store.load().items()}

    def unlocked(self, user_id: int) -> dict[str, datetime]:
        return {aid: datetime.fromisoformat(at) for aid, at in self._unlocked.get(user_id, {}).items()}

    @staticmethod
    def progress(achievement: Achievement, stats: GameStats) -> tuple[int, int] | None:
        """(aktuell, Ziel) für Gesamtziele, sonst None."""
        if achievement.kind != "stat" or achievement.stat is None:
            return None
        return min(getattr(stats, achievement.stat), achievement.target), achievement.target

    async def evaluate(
        self,
        guild: discord.Guild | None,
        history: HistoryRound,
        stats_of: Callable[[int], GameStats],
        rounds_of: Callable[[int], list[HistoryRound]],
    ) -> list[Unlock]:
        """Prüft alle verknüpften Spieler einer (bereits gespeicherten) Runde."""
        unlocks = []
        for player in history.round.players:
            user_id = history.discord_ids.get(player.player_id)
            if user_id is None:
                continue
            have = self._unlocked.get(user_id, {})
            stats = stats_of(user_id)
            streak_rounds = rounds_of(user_id)[-STREAK_LENGTH:]
            for achievement in ACHIEVEMENTS:
                if achievement.id in have or not self._earned(achievement, history, player, stats, user_id, streak_rounds):
                    continue
                result = await self._levels.award(guild, user_id, achievement.xp)
                self._unlocked.setdefault(user_id, {})[achievement.id] = datetime.now(timezone.utc).isoformat()
                unlocks.append(Unlock(user_id, achievement, result))
        if unlocks:
            self._save()
        return unlocks

    def revoke(self, user_id: int, achievement_id: str) -> None:
        if self._unlocked.get(user_id, {}).pop(achievement_id, None) is not None:
            self._save()

    @staticmethod
    def _earned(
        achievement: Achievement,
        history: HistoryRound,
        player: ModPlayer,
        stats: GameStats,
        user_id: int,
        streak_rounds: list[HistoryRound],
    ) -> bool:
        if achievement.kind == "stat":
            return achievement.stat is not None and getattr(stats, achievement.stat) >= achievement.target
        if achievement.kind == "round":
            check = ROUND_CHECKS.get(achievement.id)
            return check is not None and check(history, player)
        if achievement.kind == "streak":
            check = STREAK_CHECKS.get(achievement.id)
            if check is None or len(streak_rounds) < STREAK_LENGTH:
                return False
            return all((p := r.player_of(user_id)) is not None and check(p) for r in streak_rounds)
        return False

    def _save(self) -> None:
        self._store.save({str(uid): ids for uid, ids in self._unlocked.items() if ids})


def achievement(achievement_id: str) -> Achievement | None:
    return ACHIEVEMENTS_BY_ID.get(achievement_id)
