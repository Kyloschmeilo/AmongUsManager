"""Level- und XP-System: Spielerdaten, Ränge und automatische Level-Rollen."""

import logging
from dataclasses import dataclass, field
from typing import Any, ClassVar

import discord

from crewbot.config import LEVEL_ROLE_REWARDS, Rules
from crewbot.services.storage import JsonStore

log = logging.getLogger(__name__)


def level_for_xp(xp: int) -> int:
    return xp // Rules.XP_PER_LEVEL + 1


def xp_for_level(level: int) -> int:
    return (level - 1) * Rules.XP_PER_LEVEL


# ==========================================
# RÄNGE
# ==========================================


@dataclass(frozen=True)
class Rank:
    name: str
    min_level: int


RANKS: tuple[Rank, ...] = (
    Rank("🌱 Crew-Anfänger", 1),
    Rank("🛡️ Erfahrener Astronaut", 5),
    Rank("🚀 Skeld-Veteran", 10),
    Rank("👑 Intergalaktische Legende", 20),
)


def rank_for(level: int) -> Rank:
    return [rank for rank in RANKS if level >= rank.min_level][-1]


def next_rank(level: int) -> Rank | None:
    return next((rank for rank in RANKS if rank.min_level > level), None)


# ==========================================
# SPIELERDATEN
# ==========================================


@dataclass
class PlayerStats:
    xp: int = 0
    level: int = 1
    participations: int = 0
    wins: int = 0
    # Unbekannte Felder aus älteren Versionen (z.B. "achievements") bleiben erhalten
    extra: dict[str, Any] = field(default_factory=dict)

    FIELDS: ClassVar[tuple[str, ...]] = ("xp", "level", "participations", "wins")

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "PlayerStats":
        return cls(
            xp=int(data.get("xp", 0)),
            level=int(data.get("level", 1)),
            participations=int(data.get("participations", 0)),
            wins=int(data.get("wins", 0)),
            extra={k: v for k, v in data.items() if k not in cls.FIELDS},
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "xp": self.xp,
            "level": self.level,
            "participations": self.participations,
            "wins": self.wins,
            **self.extra,
        }

    @property
    def rank(self) -> Rank:
        return rank_for(self.level)

    @property
    def xp_in_level(self) -> int:
        return self.xp % Rules.XP_PER_LEVEL


@dataclass
class XpResult:
    """Ergebnis einer XP-Änderung – weiß, ob es ein Level-Up gab."""

    stats: PlayerStats
    old_level: int
    new_roles: list[discord.Role] = field(default_factory=list)

    @property
    def leveled_up(self) -> bool:
        return self.stats.level > self.old_level

    def announcement(self) -> str:
        lines = []
        if self.leveled_up:
            lines.append(f"🎊 **LEVEL UP!** Du bist jetzt Level {self.stats.level}!")
        lines += [f"🏅 Neue Rolle freigeschaltet: **{r.name}**" for r in self.new_roles]
        return "\n".join(lines)


# ==========================================
# SERVICE
# ==========================================


class LevelService:
    """Verwaltet XP, Level, Siege und Teilnahmen aller Spieler."""

    def __init__(self, store: JsonStore) -> None:
        self._store = store
        self._players: dict[str, PlayerStats] = {
            uid: PlayerStats.from_dict(data) for uid, data in store.load().items()
        }

    # ---------- Lesen ----------

    def get(self, user_id: int) -> PlayerStats:
        """Liefert die Daten eines Spielers (oder Standardwerte, ohne zu speichern)."""
        return self._players.get(str(user_id)) or PlayerStats()

    def ranking(self) -> list[tuple[int, PlayerStats]]:
        """Alle Spieler, sortiert nach XP (höchste zuerst)."""
        return sorted(
            ((int(uid), stats) for uid, stats in self._players.items()),
            key=lambda item: item[1].xp,
            reverse=True,
        )

    # ---------- Schreiben ----------

    def add_xp(
        self,
        user_id: int,
        amount: int,
        *,
        participation: bool = False,
        win: bool = False,
    ) -> XpResult:
        stats = self._ensure(user_id)
        old_level = stats.level
        stats.xp += amount
        # Level sinkt nie durch Wetteinsätze, steigt aber, sobald genug XP da sind
        stats.level = max(stats.level, level_for_xp(stats.xp))
        if participation:
            stats.participations += 1
        if win:
            stats.wins += 1
        self.save()
        return XpResult(stats, old_level)

    def remove_xp(
        self, user_id: int, amount: int, *, participation: bool = False
    ) -> PlayerStats:
        """Zieht XP ab. `participation=True` macht zusätzlich eine Teilnahme rückgängig."""
        stats = self._ensure(user_id)
        stats.xp = max(0, stats.xp - amount)
        if participation:
            stats.participations = max(0, stats.participations - 1)
        self.save()
        return stats

    def revert_xp(
        self, user_id: int, amount: int, *, old_level: int, new_level: int, win: bool = False
    ) -> PlayerStats:
        """Macht eine frühere XP-Vergabe rückgängig (inkl. Level-Up und Sieg)."""
        stats = self._ensure(user_id)
        stats.xp = max(0, stats.xp - amount)
        if win:
            stats.wins = max(0, stats.wins - 1)
        if new_level > old_level and stats.level == new_level:
            stats.level = old_level
        self.save()
        return stats

    def set_xp(self, user_id: int, xp: int) -> XpResult:
        """Admin: XP direkt setzen, Level wird daraus neu berechnet."""
        stats = self._ensure(user_id)
        old_level = stats.level
        stats.xp = max(0, xp)
        stats.level = level_for_xp(stats.xp)
        self.save()
        return XpResult(stats, old_level)

    def set_level(self, user_id: int, level: int) -> XpResult:
        """Admin: Level direkt setzen, XP werden auf den Level-Anfang gesetzt."""
        stats = self._ensure(user_id)
        old_level = stats.level
        stats.level = max(1, level)
        stats.xp = xp_for_level(stats.level)
        self.save()
        return XpResult(stats, old_level)

    def reset(self, user_id: int) -> None:
        self._players[str(user_id)] = PlayerStats()
        self.save()

    def save(self) -> None:
        self._store.save({uid: stats.to_dict() for uid, stats in self._players.items()})

    # ---------- Discord-Belohnungen ----------

    async def award(
        self,
        guild: discord.Guild | None,
        user_id: int,
        amount: int,
        *,
        participation: bool = False,
        win: bool = False,
    ) -> XpResult:
        """XP gutschreiben und bei Level-Up automatisch Level-Rollen vergeben."""
        result = self.add_xp(user_id, amount, participation=participation, win=win)
        if result.leveled_up and guild is not None:
            member = guild.get_member(user_id)
            if member is not None:
                result.new_roles = await self.grant_level_roles(member, result.stats.level)
        return result

    @staticmethod
    async def grant_level_roles(member: discord.Member, level: int) -> list[discord.Role]:
        """Vergibt alle Level-Rollen, die der Spieler erreicht, aber noch nicht hat."""
        granted = []
        for required_level, role_id in LEVEL_ROLE_REWARDS.items():
            role = member.guild.get_role(role_id)
            if level < required_level or role is None or role in member.roles:
                continue
            try:
                await member.add_roles(role, reason=f"Level {required_level} erreicht")
            except discord.HTTPException as exc:
                log.warning("Konnte Rolle %s nicht an %s vergeben: %s", role, member, exc)
                continue
            granted.append(role)
        return granted

    # ---------- intern ----------

    def _ensure(self, user_id: int) -> PlayerStats:
        return self._players.setdefault(str(user_id), PlayerStats())
