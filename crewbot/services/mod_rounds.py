"""Automatische Auswertung der Runden, die die CrewStats-Mod schickt.

Ablauf: Mod -> Discord-Webhook (JSON-Datei) -> Bot liest sie -> XP, Siege,
Wetten & Statistiken werden vergeben. Jede Auswertung wird mitprotokolliert,
damit sie per Button komplett zurückgesetzt werden kann.
"""

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any

import discord

from crewbot.content.among_us import Team
from crewbot.services.betting import Bet, BetService
from crewbot.services.game_stats import GameStatsService
from crewbot.services.levels import LevelService
from crewbot.services.links import LinkService
from crewbot.services.rounds import RoundOutcome, RoundService
from crewbot.services.storage import JsonStore

if TYPE_CHECKING:  # nur für Typen – die Module importieren selbst mod_rounds
    from crewbot.services.achievements import AchievementService, Unlock
    from crewbot.services.round_history import RoundHistoryService

MAX_STORED_ROUNDS = 200


class ModRoundError(Exception):
    """Fehler mit einer Nachricht, die direkt angezeigt werden kann."""


# ==========================================
# DATENMODELL (entspricht RoundRecord.cs der Mod)
# ==========================================


@dataclass
class ModPlayer:
    player_id: int
    name: str
    friend_code: str
    role: str
    team: str  # CREW | IMPOSTOR
    won: bool
    is_dead: bool
    disconnected: bool
    death_cause: str | None
    killed_by: int | None
    kills: int
    tasks_total: int
    tasks_completed: int
    died_at: float | None = None  # Sekunden seit Rundenstart

    @property
    def is_impostor(self) -> bool:
        return self.team == Team.IMPOSTOR.value

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ModPlayer":
        died_at = data.get("diedAtSeconds")
        return cls(
            player_id=int(data["playerId"]),
            name=str(data.get("name", "?")),
            friend_code=str(data.get("friendCode", "")),
            role=str(data.get("role", "")),
            team=str(data.get("team", "CREW")),
            won=bool(data.get("won", False)),
            is_dead=bool(data.get("isDead", False)),
            disconnected=bool(data.get("disconnected", False)),
            death_cause=data.get("deathCause"),
            killed_by=data.get("killedBy"),
            kills=int(data.get("kills", 0)),
            tasks_total=int(data.get("tasksTotal", 0)),
            tasks_completed=int(data.get("tasksCompleted", 0)),
            died_at=float(died_at) if died_at is not None else None,
        )


@dataclass
class ModKill:
    at_seconds: float
    killer: int
    victim: int

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ModKill":
        return cls(float(data.get("atSeconds", 0)), int(data["killer"]), int(data["victim"]))


@dataclass
class ModMeeting:
    caller: int
    body: int | None
    votes: list[tuple[int, int | None]]
    exiled: int | None
    tie: bool
    at_seconds: float = 0

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ModMeeting":
        return cls(
            caller=int(data.get("caller", 255)),
            body=data.get("body"),
            votes=[(int(v["voter"]), v.get("target")) for v in data.get("votes", [])],
            exiled=data.get("exiled"),
            tie=bool(data.get("tie", False)),
            at_seconds=float(data.get("atSeconds", 0)),
        )


@dataclass
class ModRound:
    game_id: str
    lobby_code: str
    map: str
    duration_seconds: float
    end_reason: str
    winning_team: Team | None
    players: list[ModPlayer]
    meetings: list[ModMeeting]
    kills: list[ModKill] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict, repr=False)  # Originaldaten für den Rundenverlauf

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ModRound":
        try:
            team = data.get("winningTeam")
            return cls(
                game_id=str(data["gameId"]),
                lobby_code=str(data.get("lobbyCode", "")),
                map=str(data.get("map", "?")),
                duration_seconds=float(data.get("durationSeconds", 0)),
                end_reason=str(data.get("endReason", "")),
                winning_team=Team(team) if team in (Team.CREW.value, Team.IMPOSTOR.value) else None,
                players=[ModPlayer.from_dict(p) for p in data.get("players", [])],
                meetings=[ModMeeting.from_dict(m) for m in data.get("meetings", [])],
                kills=sorted((ModKill.from_dict(k) for k in data.get("kills", [])), key=lambda k: k.at_seconds),
                raw=data,
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ModRoundError(f"Ungültige Rundendaten: {exc}") from exc

    def player(self, player_id: int | None) -> ModPlayer | None:
        return next((p for p in self.players if p.player_id == player_id), None)


# ==========================================
# ERGEBNIS
# ==========================================


@dataclass
class ModRoundResult:
    round: ModRound
    discord_ids: dict[int, int]  # Ingame-PlayerId -> Discord-ID (nur verknüpfte)
    outcome: RoundOutcome | None  # None, wenn der Sieger unbekannt ist
    unlinked: list[ModPlayer] = field(default_factory=list)
    achievements: list["Unlock"] = field(default_factory=list)  # in dieser Runde freigeschaltet

    def mention(self, player: ModPlayer) -> str:
        user_id = self.discord_ids.get(player.player_id)
        return f"<@{user_id}>" if user_id else f"*{player.name}*"


# ==========================================
# SERVICE
# ==========================================


class ModRoundService:
    def __init__(
        self,
        store: JsonStore,
        levels: LevelService,
        bets: BetService,
        rounds: RoundService,
        links: LinkService,
        stats: GameStatsService,
        history: "RoundHistoryService",
        achievements: "AchievementService",
    ) -> None:
        self._store = store
        self._levels = levels
        self._bets = bets
        self._rounds = rounds
        self._links = links
        self._stats = stats
        self._history = history
        self._achievements = achievements
        self._ledger: dict[str, dict[str, Any]] = store.load()

    def is_known(self, game_id: str) -> bool:
        return game_id in self._ledger

    async def apply(self, guild: discord.Guild, round: ModRound) -> ModRoundResult:
        if self.is_known(round.game_id):
            raise ModRoundError("Diese Runde wurde bereits ausgewertet.")

        discord_ids = {
            p.player_id: uid for p in round.players if (uid := self._links.user_for(p.friend_code))
        }
        unlinked = [p for p in round.players if p.player_id not in discord_ids]

        outcome = None
        if round.winning_team is not None:
            winner_ids = [discord_ids[p.player_id] for p in round.players if p.won and p.player_id in discord_ids]
            outcome = await self._rounds.finish(guild, round.winning_team, winner_ids)

        deltas = self._stat_deltas(round, discord_ids)
        self._stats.apply(deltas)
        history = self._history.add(round, discord_ids)
        unlocks = await self._achievements.evaluate(guild, history, self._stats.get, self._history.rounds_of)
        self._remember(round.game_id, outcome, deltas, unlocks)
        return ModRoundResult(round, discord_ids, outcome, unlinked, unlocks)

    def attach_message(self, game_id: str, channel_id: int, message_id: int) -> None:
        entry = self._ledger.get(game_id)
        if entry is not None:
            entry["channel_id"], entry["message_id"] = channel_id, message_id
            self._save()

    def undo(self, game_id: str) -> None:
        """Nimmt eine automatische Auswertung komplett zurück (XP, Siege, Wetten, Statistik)."""
        entry = self._ledger.get(game_id)
        if entry is None:
            raise ModRoundError("Diese Runde ist nicht (mehr) gespeichert.")
        if entry.get("undone"):
            raise ModRoundError("Diese Runde wurde bereits zurückgesetzt.")

        for award in entry["awards"]:
            self._levels.revert_xp(
                award["user_id"],
                award["xp"],
                old_level=award["old_level"],
                new_level=award["new_level"],
                win=award["win"],
            )
        for unlock in entry.get("achievements", []):  # ältere Einträge haben noch keine
            self._levels.revert_xp(
                unlock["user_id"], unlock["xp"], old_level=unlock["old_level"], new_level=unlock["new_level"]
            )
            self._achievements.revoke(unlock["user_id"], unlock["id"])
        self._bets.restore([Bet(b["user_id"], Team(b["team"]), b["amount"]) for b in entry["bets"]])
        self._stats.apply({int(uid): delta for uid, delta in entry["stats"].items()}, sign=-1)
        self._history.remove(game_id)

        entry["undone"] = True
        self._save()

    # ---------- intern ----------

    @staticmethod
    def _stat_deltas(round: ModRound, discord_ids: dict[int, int]) -> dict[int, dict[str, int]]:
        deltas: dict[int, dict[str, int]] = defaultdict(lambda: defaultdict(int))
        for player in round.players:
            user_id = discord_ids.get(player.player_id)
            if user_id is None:
                continue
            d = deltas[user_id]
            is_impostor = player.team == Team.IMPOSTOR.value
            d["games"] += 1
            d["impostor_games" if is_impostor else "crew_games"] += 1
            if player.won:
                d["impostor_wins" if is_impostor else "crew_wins"] += 1
            d["kills"] += player.kills
            d["deaths"] += player.death_cause == "kill"
            d["ejected"] += player.death_cause == "ejected"
            d["survived"] += not player.is_dead and not player.disconnected
            d["tasks_completed"] += player.tasks_completed

        for meeting in round.meetings:
            if (user_id := discord_ids.get(meeting.caller)) is not None:
                deltas[user_id]["bodies_reported" if meeting.body is not None else "meetings_called"] += 1
            for voter_id, target_id in meeting.votes:
                voter, target = round.player(voter_id), round.player(target_id)
                user_id = discord_ids.get(voter_id)
                if user_id is None or voter is None or target is None or voter.team != Team.CREW.value:
                    continue
                deltas[user_id]["votes_correct" if target.team == Team.IMPOSTOR.value else "votes_wrong"] += 1

        return {uid: dict(d) for uid, d in deltas.items()}

    def _remember(
        self,
        game_id: str,
        outcome: RoundOutcome | None,
        deltas: dict[int, dict[str, int]],
        unlocks: list["Unlock"],
    ) -> None:
        awards = []
        bets = []
        if outcome is not None:
            for user_id, result in outcome.winners:
                awards.append(
                    {"user_id": user_id, "xp": outcome.xp_per_winner, "win": True,
                     "old_level": result.old_level, "new_level": result.stats.level}
                )
            for bet, result in outcome.bets:
                bets.append({"user_id": bet.user_id, "team": bet.team.value, "amount": bet.amount})
                if result is not None:
                    awards.append(
                        {"user_id": bet.user_id, "xp": bet.payout, "win": False,
                         "old_level": result.old_level, "new_level": result.stats.level}
                    )

        self._ledger[game_id] = {
            "created_at": datetime.now(timezone.utc).isoformat(),
            "undone": False,
            "awards": awards,
            "bets": bets,
            "stats": {str(uid): delta for uid, delta in deltas.items()},
            "achievements": [
                {"user_id": u.user_id, "id": u.achievement.id, "xp": u.achievement.xp,
                 "old_level": u.result.old_level, "new_level": u.result.stats.level}
                for u in unlocks
            ],
        }
        # Nur die letzten Runden aufheben
        for old_id in list(self._ledger)[:-MAX_STORED_ROUNDS]:
            del self._ledger[old_id]
        self._save()

    def _save(self) -> None:
        self._store.save(self._ledger)


class ModSettings:
    """Merkt sich den Webhook, über den die Mod Runden schickt."""

    def __init__(self, store: JsonStore) -> None:
        self._store = store
        data = store.load()
        self.webhook_id: int | None = data.get("webhook_id")
        self.channel_id: int | None = data.get("channel_id")

    def set(self, webhook_id: int, channel_id: int) -> None:
        self.webhook_id, self.channel_id = webhook_id, channel_id
        self._store.save({"webhook_id": webhook_id, "channel_id": channel_id})
