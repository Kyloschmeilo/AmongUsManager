"""Auswertungen aus den Mod-Runden: Highlights einer Runde, Rivalen eines Spielers und der
Spieleabend-Recap. Reine Rechenfunktionen ohne Discord – die Anzeige macht die UI.

Spieler werden als `PlayerKey` gezählt: Discord-ID (int), wenn verknüpft, sonst der Ingame-Name
(str) – so tauchen auch Unverknüpfte in Recap und Rivalen auf.
"""

from collections import Counter, defaultdict
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field

from crewbot.services.mod_rounds import ModPlayer, ModRound
from crewbot.services.round_history import HistoryRound

PlayerKey = int | str

DOUBLE_KILL_SECONDS = 30
BLITZ_ROUND_SECONDS = 180
MIN_SUS_VOTES = 3
MIN_DETECTIVE_VOTES = 2


def format_time(seconds: float) -> str:
    minutes, rest = divmod(int(seconds), 60)
    return f"{minutes}:{rest:02d}"


def key_of(history: HistoryRound, player: ModPlayer) -> PlayerKey:
    return history.discord_ids.get(player.player_id) or player.name


# ==========================================
# BAUSTEINE PRO RUNDE
# ==========================================


def votes_received(round_: ModRound) -> Counter:
    """PlayerId -> Anzahl Stimmen gegen ihn (über alle Meetings)."""
    return Counter(target for meeting in round_.meetings for _, target in meeting.votes if target is not None)


def vote_accuracy(round_: ModRound) -> dict[int, tuple[int, int]]:
    """Crewmate-PlayerId -> (richtige, falsche) Stimmen. Übersprungen zählt nicht."""
    result: dict[int, list[int]] = defaultdict(lambda: [0, 0])
    for meeting in round_.meetings:
        for voter_id, target_id in meeting.votes:
            voter, target = round_.player(voter_id), round_.player(target_id)
            if voter is None or target is None or voter.is_impostor:
                continue
            result[voter_id][0 if target.is_impostor else 1] += 1
    return {pid: (right, wrong) for pid, (right, wrong) in result.items()}


def fastest_double_kill(round_: ModRound) -> dict[int, float]:
    """Killer-PlayerId -> kürzester Abstand zwischen zwei eigenen Kills (nur ≤ DOUBLE_KILL_SECONDS)."""
    times: dict[int, list[float]] = defaultdict(list)
    for kill in round_.kills:
        times[kill.killer].append(kill.at_seconds)
    result = {}
    for killer, stamps in times.items():
        gaps = [b - a for a, b in zip(stamps, stamps[1:]) if b - a <= DOUBLE_KILL_SECONDS]
        if gaps:
            result[killer] = min(gaps)
    return result


def round_highlights(round_: ModRound, name: Callable[[ModPlayer], str]) -> list[str]:
    """Die besonderen Momente einer Runde als fertige Textzeilen."""
    lines = []
    player = round_.player

    if round_.kills:
        first = round_.kills[0]
        killer, victim = player(first.killer), player(first.victim)
        if killer and victim:
            lines.append(f"🩸 First Blood nach {format_time(first.at_seconds)}: {name(killer)} → {name(victim)}")

    for killer_id, gap in fastest_double_kill(round_).items():
        if killer := player(killer_id):
            lines.append(f"🔪 Doppelkill: {name(killer)} (2 Kills in {int(gap)} s)")

    accuracy = vote_accuracy(round_)
    detectives = [(pid, right) for pid, (right, wrong) in accuracy.items() if right >= MIN_DETECTIVE_VOTES and wrong == 0]
    if detectives:
        pid, right = max(detectives, key=lambda item: item[1])
        lines.append(f"🕵️ Detektiv der Runde: {name(player(pid))} ({right}/{right} richtige Stimmen)")

    received = votes_received(round_)
    crew_targets = [(pid, n) for pid, n in received.items() if (p := player(pid)) and not p.is_impostor]
    if crew_targets:
        pid, count = max(crew_targets, key=lambda item: item[1])
        if count >= MIN_SUS_VOTES:
            lines.append(f"🤡 Sus-Magnet: {name(player(pid))} bekam als Crewmate {count} Stimmen")

    if round_.meetings:
        for impostor in (p for p in round_.players if p.is_impostor and not p.is_dead and received[p.player_id] == 0):
            lines.append(f"🎭 Unter dem Radar: {name(impostor)} bekam als Impostor keine einzige Stimme")

    if 0 < round_.duration_seconds < BLITZ_ROUND_SECONDS:
        lines.append(f"⚡ Blitzrunde: vorbei nach {format_time(round_.duration_seconds)}")
    return lines


# ==========================================
# RIVALEN (für /stats)
# ==========================================


@dataclass
class Rivals:
    nemesis: tuple[PlayerKey, int] | None = None  # hat mich am häufigsten getötet
    favorite_victim: tuple[PlayerKey, int] | None = None  # habe ich am häufigsten getötet
    biggest_doubter: tuple[PlayerKey, int] | None = None  # hat am häufigsten gegen mich gestimmt
    my_suspect: tuple[PlayerKey, int] | None = None  # gegen den habe ich am häufigsten gestimmt

    @property
    def empty(self) -> bool:
        return not any((self.nemesis, self.favorite_victim, self.biggest_doubter, self.my_suspect))


def rivals_of(user_id: int, rounds: Iterable[HistoryRound]) -> Rivals:
    killed_me, i_killed, doubted_me, i_doubted = Counter(), Counter(), Counter(), Counter()
    for history in rounds:
        me = history.player_of(user_id)
        if me is None:
            continue
        round_ = history.round
        for kill in round_.kills:
            killer, victim = round_.player(kill.killer), round_.player(kill.victim)
            if killer is None or victim is None:
                continue
            if victim.player_id == me.player_id:
                killed_me[key_of(history, killer)] += 1
            elif killer.player_id == me.player_id:
                i_killed[key_of(history, victim)] += 1
        for meeting in round_.meetings:
            for voter_id, target_id in meeting.votes:
                voter, target = round_.player(voter_id), round_.player(target_id)
                if voter is None or target is None or voter_id == target_id:
                    continue
                if target.player_id == me.player_id:
                    doubted_me[key_of(history, voter)] += 1
                elif voter.player_id == me.player_id:
                    i_doubted[key_of(history, target)] += 1

    def top(counter: Counter) -> tuple[PlayerKey, int] | None:
        return counter.most_common(1)[0] if counter else None

    return Rivals(top(killed_me), top(i_killed), top(doubted_me), top(i_doubted))


# ==========================================
# SPIELEABEND-RECAP
# ==========================================


@dataclass
class PlayerNight:
    games: int = 0
    wins: int = 0
    kills: int = 0
    survived: int = 0
    correct_votes: int = 0
    wrong_votes: int = 0
    sus_votes: int = 0  # Stimmen gegen ihn, während er Crewmate war
    first_deaths: int = 0

    @property
    def mvp_score(self) -> int:
        return self.wins * 3 + self.kills * 2 + self.correct_votes * 2 + self.survived


@dataclass
class Recap:
    rounds: int = 0
    crew_wins: int = 0
    impostor_wins: int = 0
    total_kills: int = 0
    players: dict[PlayerKey, PlayerNight] = field(default_factory=dict)

    def best(self, attribute: str, minimum: int = 1) -> tuple[PlayerKey, int] | None:
        candidates = [(key, getattr(night, attribute)) for key, night in self.players.items()]
        candidates = [c for c in candidates if c[1] >= minimum]
        return max(candidates, key=lambda c: c[1]) if candidates else None

    def mvp_candidates(self, limit: int = 3) -> list[tuple[PlayerKey, PlayerNight]]:
        ranked = sorted(self.players.items(), key=lambda item: item[1].mvp_score, reverse=True)
        return [(key, night) for key, night in ranked if night.mvp_score > 0][:limit]


def build_recap(rounds: Iterable[HistoryRound]) -> Recap:
    recap = Recap()
    for history in rounds:
        round_ = history.round
        recap.rounds += 1
        recap.crew_wins += round_.winning_team is not None and round_.winning_team.value == "CREW"
        recap.impostor_wins += round_.winning_team is not None and round_.winning_team.value == "IMPOSTOR"
        recap.total_kills += len(round_.kills)

        accuracy = vote_accuracy(round_)
        received = votes_received(round_)
        first_victim = round_.kills[0].victim if round_.kills else None
        for player in round_.players:
            night = recap.players.setdefault(key_of(history, player), PlayerNight())
            night.games += 1
            night.wins += player.won
            night.kills += player.kills
            night.survived += not player.is_dead and not player.disconnected
            right, wrong = accuracy.get(player.player_id, (0, 0))
            night.correct_votes += right
            night.wrong_votes += wrong
            if not player.is_impostor:
                night.sus_votes += received[player.player_id]
            night.first_deaths += player.player_id == first_victim
    return recap
