"""Erzeugt Referenzdaten (Golden Files) mit dem ORIGINALEN Python-Bot.

Die Kotlin-Tests (src/test) vergleichen ihre Ergebnisse exakt mit diesen Daten. Es wird ausschließlich
der Original-Code benutzt. Der Python-Bot liegt seit dem Umstieg nur noch im Git-Tag `python-final`:

    git worktree add ../crewbot-python python-final
    cd ../crewbot-python
    python -m venv .venv && .venv/Scripts/pip install discord.py tzdata
    .venv/Scripts/python.exe -X utf8 <Projekt>/tools/golden/generate_golden.py <Projekt>/src/test/resources/golden

Neu erzeugen ist nur nötig, wenn sich am Python-Verhalten etwas ändern soll – normalerweise nie.
"""

import asyncio
import json
import os
import random
import shutil
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, os.getcwd())

import discord  # noqa: E402

from crewbot import config  # noqa: E402
from crewbot.content import achievements as ach_content  # noqa: E402
from crewbot.content import among_us, game_settings as gs_content, minecraft as mc_content  # noqa: E402
from crewbot.services import levels as levels_mod  # noqa: E402
from crewbot.services.achievements import AchievementService  # noqa: E402
from crewbot.services.automute import AutoMuteEvent, target_state  # noqa: E402
from crewbot.services.betting import BetError, BetService  # noqa: E402
from crewbot.services.events import EventService  # noqa: E402
from crewbot.services.game_settings import GameSettings, GameSettingsError, GameSettingsService  # noqa: E402
from crewbot.services.game_stats import GameStats, GameStatsService  # noqa: E402
from crewbot.services.insights import build_recap, format_time, rivals_of  # noqa: E402
from crewbot.services.levels import LevelService  # noqa: E402
from crewbot.services.links import LinkService, is_valid_friend_code, normalize_friend_code  # noqa: E402
from crewbot.services.lobby import LobbyEvent  # noqa: E402
from crewbot.services.minecraft import McEvent, McPayloadError, MinecraftService, parse_payload  # noqa: E402
from crewbot.services.mod_rounds import ModRound, ModRoundError, ModRoundService, ModSettings  # noqa: E402
from crewbot.services.round_history import RoundHistoryService  # noqa: E402
from crewbot.services.rounds import RoundService  # noqa: E402
from crewbot.services.storage import JsonStore  # noqa: E402
from crewbot.services.voice import ActionReport  # noqa: E402
from crewbot.ui.embeds import progress_bar  # noqa: E402
from crewbot.ui.insights import build_achievements_embed, build_recap_embed, mvp_suggestion_text, rivals_text  # noqa: E402
from crewbot.ui.lobby import build_lobby_embed  # noqa: E402
from crewbot.ui.minecraft import build_event_embed as mc_embed, sample_events  # noqa: E402
from crewbot.ui.views.control_panel import ControlPanelView  # noqa: E402
from crewbot.ui.views.event_signup import (  # noqa: E402
    EventRoster,
    build_event_embed,
    close_event_message,
    event_start_from_embed,
)
from crewbot.ui.views.game_settings import build_settings_embed  # noqa: E402
from crewbot.ui.views.mod_round import REASON_TEXTS, build_mod_round_embed  # noqa: E402
from crewbot.ui.views.round_flow import round_result_embed  # noqa: E402
from crewbot.cogs.amongus_mod import AmongUsModCog  # noqa: E402
from crewbot.cogs.progression import ProgressionCog, strip_medal  # noqa: E402

OUT = Path(sys.argv[1])
RNG = random.Random(20261010)
FIXED_NOW = datetime(2026, 10, 10, 18, 30, 0, tzinfo=timezone.utc)


# ==========================================
# HELFER
# ==========================================


def norm_embed(embed: discord.Embed) -> dict:
    d = embed.to_dict()
    return {
        "title": d.get("title"),
        "description": d.get("description"),
        "color": d.get("color"),
        "fields": [[f["name"], f["value"], bool(f.get("inline", False))] for f in d.get("fields", [])],
        "footer": d.get("footer", {}).get("text"),
        "author": d.get("author", {}).get("name"),
        "author_icon": d.get("author", {}).get("icon_url"),
        "thumbnail": d.get("thumbnail", {}).get("url"),
        "image": d.get("image", {}).get("url"),
        "timestamp": embed.timestamp.timestamp() if embed.timestamp else None,
    }


def stats_tuple(s):
    return [s.xp, s.level, s.participations, s.wins]


class FakeAvatar:
    def __init__(self, url):
        self.url = url


class FakeMember:
    def __init__(self, uid, name, status=discord.Status.online):
        self.id = uid
        self.display_name = name
        self.display_avatar = FakeAvatar(f"https://cdn.example.com/avatars/{uid}.png")
        self.status = status
        self.mention = f"<@{uid}>"
        self.bot = False


class FakeResponse:
    def __init__(self):
        self.embed = None

    async def send_message(self, *args, embed=None, **kwargs):
        self.embed = embed

    def is_done(self):
        return False


class FakeInteraction:
    def __init__(self, user):
        self.user = user
        self.response = FakeResponse()


# ==========================================
# INHALTE
# ==========================================


def dump_content():
    return {
        "roles": [[r.name, r.team.value, r.emoji, r.description] for r in among_us.ROLES.values()],
        "maps": [[m.name, m.description, m.cameras, m.danger_zones, m.tasks, m.vent_image] for m in among_us.MAPS.values()],
        "fun_texts": list(among_us.FUN_TEXTS),
        "verdicts": [[v.max_percent, v.text] for v in among_us.SUSPICION_VERDICTS],
        "achievements": [
            [a.id, a.emoji, a.name, a.description, a.xp, a.kind, a.stat, a.target] for a in ach_content.ACHIEVEMENTS
        ],
        "ranks": [[r.name, r.min_level] for r in levels_mod.RANKS],
        "categories": [
            [c.key, c.label, c.emoji, [[s.kind, s.key, s.label] for s in c.settings]] for c in gs_content.CATEGORIES
        ],
        "role_settings": [
            [r.game_name, r.name, r.impostor, [[s.kind, s.key, s.label] for s in r.settings]]
            for r in gs_content.ROLE_SETTINGS
        ],
        "ignored_options": sorted(gs_content.IGNORED_OPTIONS),
        "death_causes": {k: [v.emoji, v.text, v.by_killer] for k, v in mc_content.DEATH_CAUSES.items()},
        "default_death": [mc_content.DEFAULT_DEATH.emoji, mc_content.DEFAULT_DEATH.text, mc_content.DEFAULT_DEATH.by_killer],
        "mobs": {k: list(v) for k, v in mc_content.MOBS.items()},
        "dimensions": dict(mc_content.DIMENSIONS),
        "ability_names": dict(mc_content.ABILITY_NAMES),
        "event_info": {t.value: [t.info.emoji, t.info.label, t.info.description] for t in mc_content.McEventType},
        "game_ping_roles": [[k, v] for k, v in config.GAME_PING_ROLES.items()],
        "level_role_rewards": [[k, v] for k, v in config.LEVEL_ROLE_REWARDS.items()],
        "rules": {k: v for k, v in vars(config.Rules).items() if k.isupper()},
        "channels": {k: v for k, v in vars(config.Channels).items() if k.isupper()},
        "roles_ids": {k: v for k, v in vars(config.Roles).items() if k.isupper()},
        "reason_texts": dict(REASON_TEXTS),
        "team_labels": {t.value: t.label for t in among_us.Team},
        "role_labels": {r: among_us.role_label(r) for r in [
            "Crewmate", "Impostor", "GuardianAngel", "Engineer", "Scientist", "Shapeshifter", "Phantom", "Tracker",
            "Noisemaker", "Viper", "Judge", "Influencer", "SpiritGuide", "Detective", "CrewmateGhost", "",
        ]},
        "known_option_keys": sorted(gs_content.known_option_keys()),
    }


# ==========================================
# FORMATIERER
# ==========================================


def dump_formatters():
    floats = [0, 0.1, 0.25, 0.5, 1, 1.0, 1.25, 1.5, 2.0, 2.5, 7.5, 10, 12.345678, 22.5, 30, 45.0, 60, 100, 0.333,
              123456, 999999.5, 1234567, 0.0001, 0.00001234, 3.14159265, 2.675, 1e-7, 1e21, -1.5, -0.0001]
    fmt_g = [[repr(v), json.dumps(v), f"{v:g}"] for v in floats]
    rate = []
    for total in [0, 1, 2, 3, 6, 7, 8, 9, 11, 13, 40, 200, 333]:
        for part in range(0, min(total, 40) + 1):
            rate.append([part, total, GameStats.rate(part, total)])
    fixed1 = []
    for kills in range(0, 60):
        for games in range(1, 16):
            fixed1.append([kills, games, f"{kills / games:.1f}"])
    progress = [[v, t, progress_bar(v, t)] for t in [0, 1, 3, 10, 50, 7, 12] for v in range(0, 14)]
    times = [[s, format_time(s)] for s in [0, 0.4, 1, 59.9, 60, 61.5, 119, 3599.99, 3600, 7322.7]]
    medals = ["Alex", "🥇 Alex", "🥇🥈 Alex", "🥉  🥇 Bob", "Bob 🥇", "🥈", "🥇 ", " 🥇 X", "🥇\tTab", "🥇🥇🥇 Z"]
    strip = [[m, strip_medal(m)] for m in medals]
    md_inputs = [
        "Steve", "Mr_Steve", "__init__", "a*b*c", "~~strike~~", "back`tick`", "pipe|pipe", "back\\slash",
        "> quote", ">>> big quote", "# Header", "### h3", "#### h4", "- list", "  - indented", "[link](https://x.y)",
        "https://example.com/a_b_c", "<https://example.com/x_y>", "steam://run/123_4", "text https://a.b/c_d.", "multi\n# line\n- x",
        "Ümläut_ß", "emoji 🎮_x", "trailing_",
    ]
    escape = [[s, discord.utils.escape_markdown(s)] for s in md_inputs]
    from urllib.parse import quote
    quote_inputs = ["Steve", "Mr_Steve", "a b", "ä", "x/y", "a+b&c=d", "~tilde.dot-dash", "🎮", "%", "Ab12"]
    quotes = [[s, quote(s)] for s in quote_inputs]
    titles = [[s, s.title()] for s in ["lichtschwert", "sculk stab", "zombie villager", "end crystal", "abc1def", "x_y z",
                                        "ALREADY UP", "mIxEd cAsE", "o'neil", "über ding"]]
    levels = [[xp, levels_mod.level_for_xp(xp)] for xp in [0, 1, 49, 50, 99, 100, 249, 250, 1000, 9999]]
    ranks = []
    for lvl in [1, 2, 4, 5, 9, 10, 19, 20, 21, 100]:
        nxt = levels_mod.next_rank(lvl)
        ranks.append([lvl, levels_mod.rank_for(lvl).name, nxt.name if nxt else None, nxt.min_level if nxt else None])
    codes = ["happyfox#1234", "HappyFox#1234", "  happyfox#1234  ", "a#1234", "ab#1234", "abc#123", "abc#12345",
             "abc_def#1234", "abcdefghijklmnopqrst#0000", "abcdefghijklmnopqrstu#0000", "abc#12a4", "ÄBC#1234", ""]
    friend = [[c, is_valid_friend_code(c), normalize_friend_code(c)] for c in codes]
    abilities = [[a, mc_content.ability_name(a)] for a in ["lichtschwert", "sculkstab", "schildbrecher", "frost_axt", "x"]]
    setting_formats = []
    for cat in list(gs_content.CATEGORIES):
        for s in cat.settings:
            values = {"ints": [0, 1, 2, 3, 15, 120], "floats": [0.25, 1, 1.5, 22.5, 30, 0.1, 2.0], "bools": [True, False]}[s.kind]
            for v in values:
                setting_formats.append([s.kind, s.key, json.dumps(v), s.format(v)])
    for role in gs_content.ROLE_SETTINGS:
        for s in role.settings:
            values = {"ints": [0, 1, 5], "floats": [0.25, 10, 2.5, 35, 0.1, 75.0], "bools": [True, False]}[s.kind]
            for v in values:
                setting_formats.append([s.kind, s.key, json.dumps(v), s.format(v)])
    reports = [[s, f, ActionReport(s, f).describe("gemutet")] for s, f in [(0, 0), (3, 0), (2, 1), (0, 5)]]
    # /create-event: genau wie EventsCog.create_event (strptime + deutsche Zeitzone)
    date_inputs = ["25.08.2026 19:00", "5.8.2026 9:00", " 25.08.2026 19:00 ", "25.08.26 19:00", "31.02.2026 19:00",
                   "29.02.2028 12:00", "29.02.2027 12:00", "25.08.2026 24:00", "25.08.2026 23:59", "25.08.2026",
                   "25-08-2026 19:00", "25.08.2026 19:5", "00.08.2026 19:00", "25.13.2026 19:00", "1.1.2030 0:00",
                   "25.08.2026  19:00", "abc", ""]
    dates = []
    for text in date_inputs:
        try:
            start = datetime.strptime(text.strip(), "%d.%m.%Y %H:%M").replace(tzinfo=ZoneInfo(config.TIMEZONE))
            dates.append([text, start.timestamp()])
        except ValueError:
            dates.append([text, None])
    target = [[p, d, list(target_state(p, dead=d))] for p in ["tasks", "meeting", "end", "other"] for d in [False, True]]
    return {
        "format_g": fmt_g, "rate": rate, "fixed1": fixed1, "progress": progress, "format_time": times,
        "strip_medal": strip, "escape_markdown": escape, "url_quote": quotes, "title": titles, "level_for_xp": levels,
        "ranks": ranks, "friend_codes": friend, "ability_names": abilities, "setting_formats": setting_formats,
        "action_reports": reports, "target_state": target, "event_dates": dates,
    }


# ==========================================
# PARSEN
# ==========================================


def dump_parsing():
    automute_inputs = [
        '{"crewstats":"automute","v":1,"phase":"tasks","sender":"happyfox#1234","dead":["a#1111","b#2222"]}',
        '{"crewstats":"automute","phase":"meeting","sender":"","dead":[]}',
        '{"crewstats":"automute","phase":"end"}',
        '{"crewstats":"automute","phase":"lobby"}',
        '{"crewstats":"lobby","phase":"tasks"}',
        '{"crewstats":"automute","phase":"tasks","dead":["x#1234",5,null,"y#9999"]}',
        '{"crewstats":"automute","phase":"tasks","sender":null,"dead":null}',
        'kein json', '[1,2]', '', '{"crewstats":"automute","phase":"tasks","dead":"ab"}',
    ]
    automute = []
    for text in automute_inputs:
        try:
            ev = AutoMuteEvent.parse(text)
            automute.append([text, None if ev is None else [ev.phase, ev.sender, sorted(ev.dead)]])
        except Exception as exc:  # noqa: BLE001
            automute.append([text, "error:" + type(exc).__name__])
    lobby_inputs = [
        '{"crewstats":"lobby","v":1,"state":"open","code":"ABCDEF","map":"Polus","players":5,"max":10,"host":"Kylo","sender":"happyfox#1234"}',
        '{"crewstats":"lobby","state":"started","code":"ABCDEF"}',
        '{"crewstats":"lobby","state":"closed"}',
        '{"crewstats":"lobby","state":"weird"}',
        '{"crewstats":"lobby","state":"open","players":"7","max":null,"host":null}',
        '{"crewstats":"lobby","state":"open","players":"x"}',
        '{"crewstats":"automute","state":"open"}', 'nope',
    ]
    lobby = []
    for text in lobby_inputs:
        ev = LobbyEvent.parse(text)
        lobby.append([text, None if ev is None else [ev.state, ev.code, ev.map, ev.players, ev.max_players, ev.host, ev.sender]])
    mc_inputs = [
        '{"v":1,"server":"CoreSMP","events":[{"type":"join","player":"Steve","online":3,"max":20},{"type":"unknown","player":"X"},{"type":"death","player":"Alex","cause":"lava"}]}',
        '{"v":1,"events":[{"type":"leave"}]}',
        '{"v":1,"server":"","events":[5,{"type":"craft","player":"Bob","item":"Axt"}]}',
        '{"v":1}', 'kaputt', '[1]', '{"events":"nope"}',
    ]
    mc = []
    for text in mc_inputs:
        try:
            p = parse_payload(text)
            mc.append([text, {"server": p.server, "events": [[e.type.value, e.player] for e in p.events]}])
        except McPayloadError:
            mc.append([text, "error"])
    return {"automute": automute, "lobby": lobby, "minecraft": mc}


# ==========================================
# EMBEDS
# ==========================================

SETTINGS_FULL = {
    "crewstats": "settings", "v": 1, "capturedAt": "2026-10-09T19:45:12.1234567Z", "lobbyCode": "QWERTY", "map": "Polus",
    "players": 9, "maxPlayers": 15, "numImpostors": 2,
    "ints": {"KillDistance": 1, "NumEmergencyMeetings": 1, "EmergencyCooldown": 15, "DiscussionTime": 30,
             "VotingTime": 120, "TaskBarMode": 1, "NumCommonTasks": 2, "NumLongTasks": 1, "NumShortTasks": 5,
             "MaxImpostors": 3, "BrandNewInt": 42, "RulePreset": 0},
    "floats": {"KillCooldown": 22.5, "PlayerSpeedMod": 1.25, "CrewLightMod": 0.75, "ImpostorLightMod": 1.5,
               "EngineerCooldown": 30, "EngineerInVentMaxTime": 15, "ScientistCooldown": 15, "ScientistBatteryCharge": 5,
               "GuardianAngelCooldown": 60, "ProtectionDurationSeconds": 10, "TrackerCooldown": 15, "TrackerDuration": 30,
               "TrackerDelay": 1, "NoisemakerAlertDuration": 10, "ShapeshifterCooldown": 10, "ShapeshifterDuration": 30,
               "PhantomCooldown": 15, "PhantomDuration": 10, "ViperDissolveTime": 20, "DetectiveSuspectLimit": 3,
               "JudgeTaskRequirementPercentage": 75, "SpiritGuideCooldownSeconds": 25.5, "NewFloatThing": 0.333},
    "bools": {"ConfirmImpostor": True, "AnonymousVotes": False, "VisualTasks": False, "GhostsDoTasks": True,
              "ImpostorsCanSeeProtect": False, "NoisemakerImpostorAlert": True, "ShapeshifterLeaveSkin": False,
              "SomethingNew": True},
    "roles": {"Engineer": {"count": 2, "chance": 100}, "Scientist": {"count": 1, "chance": 50},
              "GuardianAngel": {"count": 0, "chance": 100}, "Tracker": {"count": 1, "chance": 0},
              "Noisemaker": {"count": 1, "chance": 100}, "Detective": {"count": 1, "chance": 30},
              "Judge": {"count": 0, "chance": 0}, "SpiritGuide": {"count": 1, "chance": 100},
              "Shapeshifter": {"count": 1, "chance": 100}, "Phantom": {"count": 0, "chance": 0},
              "Viper": {"count": 1, "chance": 25}, "FutureRole": {"count": 2, "chance": 60},
              "OffFutureRole": {"count": 0, "chance": 60}},
}
SETTINGS_MINIMAL = {"crewstats": "settings", "capturedAt": "2026-10-09T19:45:12Z"}
SETTINGS_NOROLES = {"crewstats": "settings", "capturedAt": "2026-10-09T21:00:00", "lobbyCode": "", "map": "The Skeld",
                    "players": 4, "maxPlayers": 10, "numImpostors": 1, "ints": {}, "floats": {"KillCooldown": 45},
                    "bools": None, "roles": {}}
SETTINGS_INVALID = [
    {"crewstats": "lobby", "capturedAt": "2026-10-09T19:45:12Z"},
    {"crewstats": "settings"},
    {"crewstats": "settings", "capturedAt": "2026-10-09T19:45:12Z", "players": None},
    {"crewstats": "settings", "capturedAt": "2026-10-09T19:45:12Z", "roles": {"X": 5}},
    {"crewstats": "settings", "capturedAt": "kein datum"},
]


def dump_embeds():
    result = {}
    # Rundeneinstellungen
    settings_cases = []
    for raw in (SETTINGS_FULL, SETTINGS_MINIMAL, SETTINGS_NOROLES):
        parsed = GameSettings.from_dict(raw)
        for cat in gs_content.CATEGORIES:
            settings_cases.append({"raw": json.dumps(raw), "category": cat.key, "embed": norm_embed(build_settings_embed(parsed, cat))})
    result["settings"] = settings_cases
    invalid = []
    for raw in SETTINGS_INVALID:
        try:
            GameSettings.from_dict(raw)
            invalid.append([json.dumps(raw), "ok"])
        except GameSettingsError:
            invalid.append([json.dumps(raw), "error"])
    result["settings_invalid"] = invalid

    # Lobby
    lobby_cases = []
    for state, code, mp, pl, mx, host in [
        ("open", "ABCDEF", "Polus", 5, 10, "<@123>"), ("started", "ABCDEF", "The Skeld", 10, 10, "Kylo"),
        ("closed", "ABCDEF", "?", 0, 10, "?"), ("open", "XYZ", "?", 3, 0, "Host"), ("open", "Q", "Mira HQ", 12, 15, "Kylo_X"),
    ]:
        ev = LobbyEvent(state, code, mp, pl, mx, host, "")
        e = build_lobby_embed(ev, host)
        n = norm_embed(e)
        n["timestamp"] = None
        lobby_cases.append({"event": [state, code, mp, pl, mx, host], "host": host, "embed": n})
    result["lobby"] = lobby_cases

    # Minecraft
    when = datetime(2026, 10, 1, 20, 15, 30, tzinfo=timezone.utc)
    mc_events = list(sample_events()) + [
        McEvent(mc_content.McEventType.JOIN, "Mr_Steve", {"online": 1}),
        McEvent(mc_content.McEventType.JOIN, "Lonely", {}),
        McEvent(mc_content.McEventType.LEAVE, "Kicked_Guy", {"reason": "kicked", "playtime": 59, "online": 0, "max": 20}),
        McEvent(mc_content.McEventType.LEAVE, "Stopper", {"reason": "stop", "playtime": 7322, "online": 0, "max": 20}),
        McEvent(mc_content.McEventType.LEAVE, "Timeout", {"reason": "timed_out", "playtime": 3600}),
        McEvent(mc_content.McEventType.DEATH, "Fall_Guy", {"cause": "fall", "dimension": "nether"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {"cause": "starve", "killerType": "zombie", "dimension": "the_end"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {"cause": "weird_new_cause", "message": "Bob was *weird*_ed"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {"cause": "weird_new_cause", "killerType": "new_mob_type"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {"cause": "arrow", "killerType": "skeleton", "weapon": "Bogen_X"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {"cause": "magic", "killerType": "witch", "killer": "Hexe Hilde"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {"killer": "Steve"}),
        McEvent(mc_content.McEventType.DEATH, "Bob", {}),
        McEvent(mc_content.McEventType.CRAFT, "Alex", {"itemKey": "frost_axt", "ability": "frost_axt"}),
        McEvent(mc_content.McEventType.CRAFT, "Alex", {"ability": "sculkstab"}),
    ]
    mc_cases = []
    for ev in mc_events:
        mc_cases.append({"type": ev.type.value, "player": ev.player, "data": json.dumps(ev.data),
                         "embed": norm_embed(mc_embed(ev, "CoreSMP", when))})
    result["minecraft"] = mc_cases

    # Control Panel
    result["control_panel"] = [
        {"status": s, "bets": b, "embed": norm_embed(ControlPanelView.build_embed(s, b))}
        for s, b in [("⏸️ Bereit", 0), ("🤫 Runde läuft", 3), ("🏁 Runde beendet – bereit für die nächste", 12)]
    ]

    # Event-Embed (+ Teilnehmerliste + Schließen)
    berlin = ZoneInfo(config.TIMEZONE)
    event_cases = []

    async def close_case(embed):
        class Msg:
            def __init__(self, e):
                self.embeds = [e]
                self.edited = None

            async def edit(self, embed=None, view=None):
                self.edited = embed
        m = Msg(embed)
        await close_event_message(m)
        return m.edited

    for game, start, creator, url in [
        ("Among Us", datetime(2026, 10, 12, 19, 0, tzinfo=berlin), "Kylo", "https://discord.com/events/1/2"),
        ("R.E.P.O.", datetime(2026, 12, 31, 23, 30, tzinfo=berlin), "Host_Name", None),
        ("Ultimate Chicken Horse", datetime(2027, 10, 31, 2, 30, tzinfo=berlin), "Ä Ö", None),
    ]:
        embed = build_event_embed(game, start, creator, url)
        roster = EventRoster.from_embed(embed)
        roster.entries += ["<@111>", "<@!222>", "OldName", "<@333>"]
        roster.write_to(embed)
        reparsed = EventRoster.from_embed(embed)
        closed = asyncio.run(close_case(discord.Embed.from_dict(embed.to_dict())))
        event_cases.append({
            "game": game, "start": start.timestamp(), "creator": creator, "url": url,
            "embed": norm_embed(build_event_embed(game, start, creator, url)),
            "with_roster": norm_embed(embed), "member_ids": reparsed.member_ids(),
            "start_from_embed": event_start_from_embed(embed).timestamp(),
            "closed": norm_embed(closed),
        })
    result["event"] = event_cases

    # Erfolge (/erfolge) mit festen Zeitpunkten
    tmp = Path(tempfile.mkdtemp())
    ach_cases = []
    for idx, (unlocked_ids, stats_values) in enumerate([
        ([], {}),
        (["first_game", "first_kill", "double_kill"], {"games": 7, "kills": 3, "tasks_completed": 45, "votes_correct": 2}),
        ([a.id for a in ach_content.ACHIEVEMENTS], {"games": 80, "kills": 40, "impostor_wins": 12, "crew_wins": 30,
                                                     "tasks_completed": 250, "votes_correct": 30}),
        (["win_streak", "regular"], {"games": 55, "crew_wins": 24, "tasks_completed": 199, "votes_correct": 24, "kills": 24,
                                      "impostor_wins": 9}),
    ]):
        stamps = {aid: (FIXED_NOW - timedelta(days=i * 3 + 1)).isoformat() for i, aid in enumerate(unlocked_ids)}
        store = JsonStore(tmp / f"ach{idx}.json")
        store.save({"555": stamps} if stamps else {})
        service = AchievementService(store, LevelService(JsonStore(tmp / f"lv{idx}.json")))
        member = FakeMember(555, "Spieler_Fünf")
        stats = GameStats.from_dict(stats_values)
        embed = build_achievements_embed(member, service, stats)
        ach_cases.append({"unlocked": {k: datetime.fromisoformat(v).timestamp() for k, v in stamps.items()},
                          "stats": stats_values, "embed": norm_embed(embed)})
    result["achievements"] = ach_cases

    # /profile und /crew-card über die echten Cog-Funktionen
    class FakeBot:
        pass
    bot = FakeBot()
    bot.levels = LevelService(JsonStore(tmp / "levels_profile.json"))
    profile_cases = []
    cog = ProgressionCog(bot)
    for uid, name, xp, level, part, wins, status in [
        (1, "Neuling", 0, 1, 0, 0, discord.Status.online), (2, "Mittel_Mann", 260, 6, 4, 9, discord.Status.offline),
        (3, "Profi ß", 1234, 25, 40, 77, discord.Status.idle), (4, "Leer", None, None, None, None, discord.Status.offline),
        (5, "Grenze", 450, 10, 1, 2, discord.Status.dnd), (6, "Neunzehn", 999, 19, 3, 3, discord.Status.online),
    ]:
        if xp is not None:
            bot.levels._players[str(uid)] = levels_mod.PlayerStats(xp=xp, level=level, participations=part, wins=wins)
        member = FakeMember(uid, name, status)
        inter = FakeInteraction(member)
        asyncio.run(ProgressionCog.profile.callback(cog, inter, None))
        profile = norm_embed(inter.response.embed)
        inter = FakeInteraction(member)
        asyncio.run(ProgressionCog.crew_card.callback(cog, inter, None))
        card = norm_embed(inter.response.embed)
        stats = bot.levels.get(uid)
        profile_cases.append({"id": uid, "name": name, "offline": status == discord.Status.offline,
                              "stats": stats_tuple(stats), "profile": profile, "card": card})
    result["profiles"] = profile_cases
    shutil.rmtree(tmp, ignore_errors=True)
    return result


# ==========================================
# PIPELINE: viele zufällige Operationen gegen die echten Services
# ==========================================

USERS = [300000000000000000 + i * 7919 for i in range(1, 13)]
ROLE_POOL_CREW = ["Crewmate", "Crewmate", "Crewmate", "Engineer", "Scientist", "GuardianAngel", "Tracker", "Noisemaker",
                  "Detective", "Judge", "SpiritGuide"]
ROLE_POOL_IMP = ["Impostor", "Impostor", "Shapeshifter", "Phantom", "Viper"]
MAPS_POOL = ["The Skeld", "MIRA HQ", "Polus", "The Airship", "The Fungle", "Dleks"]


def make_game_id(rng):
    return "".join(rng.choice("0123456789abcdef") for _ in range(32))


def make_round(rng, codes):
    """Eine zufällige Runde im Format der Mod (RoundRecord.cs, camelCase)."""
    n = rng.randint(4, 12)
    chosen = rng.sample(codes, min(n, len(codes)))
    players = []
    for pid, code in enumerate(chosen):
        name = "Gast_" + str(pid) if code.startswith("gast") else code.split("#")[0].capitalize()
        players.append({"playerId": pid, "name": name, "friendCode": code, "puid": "", "role": "", "team": "CREW",
                        "won": False, "isDead": False, "disconnected": False, "deathCause": None, "killedBy": None,
                        "diedAtSeconds": None, "kills": 0, "tasksTotal": 0, "tasksCompleted": 0})
    num_imp = rng.choice([1, 1, 2, 2, 3]) if n >= 7 else 1
    impostors = rng.sample(players, num_imp)
    for p in players:
        if p in impostors:
            p["team"] = "IMPOSTOR"
            p["role"] = rng.choice(ROLE_POOL_IMP)
        else:
            p["role"] = rng.choice(ROLE_POOL_CREW)
            p["tasksTotal"] = rng.randint(0, 10)
            p["tasksCompleted"] = rng.randint(0, p["tasksTotal"])
    duration = rng.choice([rng.uniform(60, 179), rng.uniform(180, 1500), rng.uniform(180, 900)])
    kills = []
    t = rng.uniform(5, 60)
    alive_crew = [p for p in players if p["team"] == "CREW"]
    while t < duration and alive_crew and rng.random() < 0.75:
        killer = rng.choice(impostors)
        victim = rng.choice(alive_crew)
        alive_crew.remove(victim)
        kills.append({"atSeconds": round(t, 3), "killer": killer["playerId"], "victim": victim["playerId"]})
        victim.update(isDead=True, deathCause="kill", killedBy=killer["playerId"], diedAtSeconds=round(t, 3))
        killer["kills"] += 1
        t += rng.choice([rng.uniform(3, 29.9), rng.uniform(30, 31), rng.uniform(31, 200)])
    meetings = []
    for _ in range(rng.randint(0, 4)):
        alive = [p for p in players if not p["isDead"]]
        if len(alive) < 2:
            break
        dead = [p for p in players if p["isDead"]]
        body = rng.choice(dead)["playerId"] if dead and rng.random() < 0.6 else None
        votes = []
        for voter in alive:
            r = rng.random()
            if r < 0.15:
                target = None
            elif r < 0.18:
                target = voter["playerId"]
            elif r < 0.2:
                target = 77  # unbekannte Spieler-ID
            else:
                target = rng.choice(alive)["playerId"]
            votes.append({"voter": voter["playerId"], "target": target})
        exiled = None
        tie = rng.random() < 0.15
        if not tie and rng.random() < 0.6:
            exiled = rng.choice(alive)["playerId"]
            for p in players:
                if p["playerId"] == exiled:
                    p.update(isDead=True, deathCause="ejected", diedAtSeconds=round(t, 3))
        caller = rng.choice(alive)["playerId"] if rng.random() < 0.95 else 255
        meetings.append({"atSeconds": round(rng.uniform(10, duration), 3), "caller": caller, "body": body, "votes": votes,
                         "exiled": exiled, "tie": tie})
    for p in players:
        if not p["isDead"] and rng.random() < 0.05:
            p.update(disconnected=True, isDead=True, deathCause="disconnect")
    winning = rng.choice(["CREW", "CREW", "IMPOSTOR", "IMPOSTOR", "UNKNOWN"])
    for p in players:
        p["won"] = winning != "UNKNOWN" and p["team"] == winning
    rng.shuffle(kills)  # der Bot muss nach Zeit sortieren
    return {
        "version": 1, "gameId": make_game_id(rng), "lobbyCode": rng.choice(["", "ABCDEF", "QWERTZ", "XYZXYZ"]),
        "map": rng.choice(MAPS_POOL), "startedAt": "2026-10-09T18:00:00Z", "endedAt": "2026-10-09T18:10:00Z",
        "durationSeconds": round(duration, 3),
        "endReason": rng.choice(list(REASON_TEXTS) + ["HumansByVote", "SomethingNew"]),
        "winningTeam": winning, "players": players, "kills": kills, "meetings": meetings,
    }


class Pipeline:
    def __init__(self, folder: Path):
        self.folder = folder
        self.levels = LevelService(JsonStore(folder / "levels.json"))
        self.bets = BetService(JsonStore(folder / "bets.json"), self.levels)
        self.rounds = RoundService(self.levels, self.bets)
        self.links = LinkService(JsonStore(folder / "links.json"))
        self.stats = GameStatsService(JsonStore(folder / "game_stats.json"))
        self.history = RoundHistoryService(JsonStore(folder / "round_history.json"))
        self.achievements = AchievementService(JsonStore(folder / "achievements.json"), self.levels)
        self.mod_rounds = ModRoundService(JsonStore(folder / "mod_rounds.json"), self.levels, self.bets, self.rounds,
                                          self.links, self.stats, self.history, self.achievements)

    def players(self):
        return [[int(uid), *stats_tuple(s)] for uid, s in self.levels._players.items()]

    def state(self):
        return {
            "players": self.players(),
            "ranking": [uid for uid, _ in self.levels.ranking()],
            "stats": {str(uid): s.to_dict() for uid, s in sorted(self.stats._stats.items())},
            "bets": [[b.user_id, b.team.value, b.amount] for b in self.bets.active],
            "links": [[code, uid] for code, uid in self.links._links.items()],
            "achievements": {str(uid): sorted(ids) for uid, ids in sorted(self.achievements._unlocked.items()) if ids},
            "history": [e["game_id"] for e in self.history._entries],
            "ledger": [[gid, e["undone"]] for gid, e in self.mod_rounds._ledger.items()],
        }


async def run_pipeline():
    from crewbot.content.among_us import Team
    folder = Path(tempfile.mkdtemp())
    pipe = Pipeline(folder)
    rng = RNG
    codes = []
    for i, _ in enumerate(USERS):
        codes.append(f"spieler{i}#{1000 + i}")
        if i % 4 == 0:
            codes.append(f"zweit{i}#{2000 + i}")
    guest_codes = [f"gast{i}#{9000 + i}" for i in range(6)]
    all_codes = codes + guest_codes

    ops = []
    for code in codes:
        index = int(code.split("#")[0].replace("spieler", "").replace("zweit", ""))
        uid = USERS[index]
        pipe.links.link(code, uid)
        ops.append({"op": "link", "code": code, "user": uid})

    applied = []
    sent_rounds = []
    for step in range(650):
        r = rng.random()
        if r < 0.45 or step < 5:
            if sent_rounds and rng.random() < 0.04:
                data = rng.choice(sent_rounds)  # doppelt gesendet
            else:
                data = make_round(rng, all_codes)
                sent_rounds.append(data)
            text = json.dumps(data)
            try:
                rnd = ModRound.from_dict(json.loads(text))
                result = await pipe.mod_rounds.apply(None, rnd)
                applied.append(rnd.game_id)
                expected = {
                    "embed": norm_embed(build_mod_round_embed(result)),
                    "unlocks": [[u.user_id, u.achievement.id, u.result.old_level, u.result.stats.level] for u in result.achievements],
                    "discord_ids": {str(k): v for k, v in result.discord_ids.items()},
                }
                expected["embed"]["timestamp"] = None
            except ModRoundError as exc:
                expected = {"error": str(exc)}
            ops.append({"op": "round", "json": text, "expected": expected})
        elif r < 0.62:
            uid = rng.choice(USERS)
            team = rng.choice(["CREW", "IMPOSTOR"])
            amount = rng.choice([0, 1, 5, 10, 25, 50, 51, rng.randint(1, 50)])
            try:
                bet = pipe.bets.place(uid, Team(team), amount)
                expected = {"bet": [bet.user_id, bet.team.value, bet.amount]}
            except BetError as exc:
                expected = {"error": str(exc)}
            ops.append({"op": "bet", "user": uid, "team": team, "amount": amount, "expected": expected})
        elif r < 0.69:
            game_id = rng.choice(applied) if applied and rng.random() < 0.9 else make_game_id(rng)
            try:
                pipe.mod_rounds.undo(game_id)
                expected = {"ok": True}
            except ModRoundError as exc:
                expected = {"error": str(exc)}
            ops.append({"op": "undo", "game_id": game_id, "expected": expected})
        elif r < 0.72:
            refunded = pipe.bets.refund_all()
            ops.append({"op": "refund", "expected": [[b.user_id, b.amount] for b in refunded]})
        elif r < 0.78:
            team = rng.choice(["CREW", "IMPOSTOR"])
            winners = rng.sample(USERS, rng.randint(0, 5))
            if winners and rng.random() < 0.2:
                winners.append(winners[0])  # gleicher Spieler doppelt (/impostor-win mit 2x derselben Person)
            outcome = await pipe.rounds.finish(None, Team(team), winners)
            ops.append({"op": "finish", "team": team, "winners": winners, "expected": norm_embed(round_result_embed(outcome))})
        elif r < 0.84:
            uid = rng.choice(USERS)
            amount = rng.choice([10, 50, 5, 25])
            participation = rng.random() < 0.5
            res = await pipe.levels.award(None, uid, amount, participation=participation)
            ops.append({"op": "award", "user": uid, "amount": amount, "participation": participation,
                        "expected": [*stats_tuple(res.stats), res.old_level, res.leveled_up]})
        elif r < 0.9:
            uid = rng.choice(USERS)
            kind = rng.choice(["xp", "level", "reset", "remove"])
            value = rng.choice([-5, -1, 0, 1, 5, 10, 49, 50, 51, 120, 999])
            participation = rng.random() < 0.5
            if kind == "xp":
                res = pipe.levels.set_xp(uid, value)
                out = [*stats_tuple(res.stats), res.old_level]
            elif kind == "level":
                res = pipe.levels.set_level(uid, value)
                out = [*stats_tuple(res.stats), res.old_level]
            elif kind == "reset":
                pipe.levels.reset(uid)
                out = stats_tuple(pipe.levels.get(uid))
            else:
                out = stats_tuple(pipe.levels.remove_xp(uid, abs(value), participation=participation))
            ops.append({"op": "admin", "kind": kind, "user": uid, "value": value, "participation": participation,
                        "expected": out})
        elif r < 0.95:
            code = rng.choice(all_codes)
            uid = rng.choice(USERS)
            pipe.links.link(code, uid)
            ops.append({"op": "link", "code": code, "user": uid})
        else:
            uid = rng.choice(USERS)
            removed = pipe.links.unlink(uid)
            ops.append({"op": "unlink", "user": uid, "expected": removed})
        ops[-1]["players"] = pipe.players()

    final = pipe.state()

    # Auswertungen über alle Runden
    all_rounds = pipe.history.rounds()
    recap_all = norm_embed(build_recap_embed(build_recap(all_rounds), "🌙 Alle Runden"))
    since = datetime(2026, 1, 1, tzinfo=timezone.utc)
    recap_since = norm_embed(build_recap_embed(build_recap(pipe.history.rounds(since=since)), "🌙 Seit Januar", since))
    recap_last = norm_embed(build_recap_embed(build_recap(all_rounds[-15:]), "🌙 Letzte 15"))
    recap_empty = norm_embed(build_recap_embed(build_recap([]), "🌙 Leer", since))
    suggestion = mvp_suggestion_text(build_recap(all_rounds[-10:]))
    rivals = {str(uid): rivals_text(rivals_of(uid, pipe.history.rounds_of(uid))) for uid in USERS}

    class FakeBot:
        pass
    bot = FakeBot()
    bot.game_stats, bot.links, bot.round_history, bot.achievements = pipe.stats, pipe.links, pipe.history, pipe.achievements
    cog = AmongUsModCog(bot)
    stats_embeds = {}
    for uid in USERS + [999]:
        inter = FakeInteraction(FakeMember(uid, f"Spieler {uid % 1000}"))
        await AmongUsModCog.stats.callback(cog, inter, None)
        stats_embeds[str(uid)] = norm_embed(inter.response.embed)

    # Zusätzliche Dateien für den Import-Test
    events = EventService(JsonStore(folder / "events.json"))
    start = datetime(2026, 10, 20, 19, 0, tzinfo=ZoneInfo(config.TIMEZONE))
    events.add(1500000000000000001, 1463920288214548684, start, start + timedelta(hours=2), FIXED_NOW)
    events.record_attendance(1500000000000000001, [USERS[0], USERS[1]], 20)
    events.record_attendance(1500000000000000001, [USERS[1]], 3)
    events.add(1500000000000000002, 1463920288214548684, start + timedelta(minutes=5),
               start + timedelta(hours=2, minutes=5), start)
    events.save()
    old_event = json.loads((folder / "events.json").read_text(encoding="utf-8"))
    old_event["1500000000000000003"] = {"channel_id": 1, "ends_at": "2026-09-01T21:00:00+02:00", "reminded": True,
                                        "attendance": {str(USERS[2]): 16}}
    (folder / "events.json").write_text(json.dumps(old_event, indent=4), encoding="utf-8")
    ModSettings(JsonStore(folder / "mod.json")).set(1499999999999999999, 1463920288214548684)
    mc = MinecraftService(JsonStore(folder / "minecraft.json"), 1, 2)
    mc.webhook_id = 1488888888888888888
    mc._webhook_url = "https://discord.com/api/webhooks/1488888888888888888/abc"
    mc.channel_id = 77
    mc.enabled = {mc_content.McEventType.DEATH, mc_content.McEventType.JOIN}
    mc._save()
    GameSettingsService(JsonStore(folder / "game_settings.json")).update(SETTINGS_FULL)
    # offene Wetten, damit auch deren Import geprüft wird
    rich = [uid for uid in USERS if pipe.levels.get(uid).xp >= 20][:3]
    for i, uid in enumerate(rich):
        pipe.bets.place(uid, Team.CREW if i % 2 == 0 else Team.IMPOSTOR, 10 + i)
    import_state = pipe.state()
    levels_raw = json.loads((folder / "levels.json").read_text(encoding="utf-8"))
    first = next(iter(levels_raw))
    levels_raw[first].update({"sus_count": 0, "clutch_wins": 2, "achievements": ["first_game"]})
    (folder / "levels.json").write_text(json.dumps(levels_raw, indent=4, ensure_ascii=False), encoding="utf-8")

    import_dir = OUT / "import"
    if import_dir.exists():
        shutil.rmtree(import_dir)
    import_dir.mkdir(parents=True)
    for name in ["levels.json", "bets.json", "events.json", "links.json", "game_stats.json", "mod_rounds.json", "mod.json",
                 "game_settings.json", "round_history.json", "achievements.json", "minecraft.json"]:
        shutil.copy(folder / name, import_dir / name)

    # Nach dem Import: die neueste noch aktive Runde zurücksetzen (mit frisch geladenen Services)
    reloaded = Pipeline(folder)
    extra = next(gid for gid, e in reversed(list(reloaded.mod_rounds._ledger.items())) if not e["undone"])
    reloaded.mod_rounds.undo(extra)
    after_undo = reloaded.state()

    shutil.rmtree(folder, ignore_errors=True)
    return {
        "users": USERS, "ops": ops, "final": final,
        "recap_all": recap_all, "recap_since": recap_since, "recap_last": recap_last, "recap_empty": recap_empty,
        "mvp_suggestion": suggestion, "rivals": rivals, "stats_embeds": stats_embeds,
        "import_extra": {"state": import_state, "levels_extra_user": int(first),
                         "levels_extra": {"sus_count": 0, "clutch_wins": 2, "achievements": ["first_game"]},
                         "undo_game_id": extra, "after_undo": after_undo},
    }




# ==========================================
# SLASH-COMMANDS (genau das, was discord.py an Discord schickt)
# ==========================================


async def dump_commands_async():
    from discord.ext import commands as ext_commands
    from crewbot.core.client import EXTENSIONS
    bot = ext_commands.Bot(command_prefix="!", intents=discord.Intents.none())
    for extension in EXTENSIONS:
        await bot.load_extension(extension)
    payloads = [cmd.to_dict(bot.tree) for cmd in bot.tree.get_commands()]
    cogs = []
    for cog in bot.cogs.values():
        cogs.append([cog.qualified_name, cog.emoji, [c.name for c in cog.get_app_commands()]])
    for extension in list(bot.extensions):
        await bot.unload_extension(extension)
    return {"payloads": payloads, "cogs": cogs}


def dump_commands():
    return asyncio.run(dump_commands_async())


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    golden = {
        "content": dump_content(),
        "formatters": dump_formatters(),
        "parsing": dump_parsing(),
        "embeds": dump_embeds(),
        "pipeline": asyncio.run(run_pipeline()),
        "commands": dump_commands(),
    }
    (OUT / "golden.json").write_text(json.dumps(golden, ensure_ascii=False, indent=1), encoding="utf-8")
    p = golden["pipeline"]
    print("ops:", len(p["ops"]), "rounds:", sum(1 for o in p["ops"] if o["op"] == "round"),
          "history:", len(p["final"]["history"]), "achievement users:", len(p["final"]["achievements"]))
    counts = {}
    for o in p["ops"]:
        if o["op"] == "round" and "embed" in o["expected"]:
            for u in o["expected"]["unlocks"]:
                counts[u[1]] = counts.get(u[1], 0) + 1
    print("unlocks:", counts)
    errors = {}
    for o in p["ops"]:
        if isinstance(o.get("expected"), dict) and "error" in o["expected"]:
            errors[o["expected"]["error"][:40]] = errors.get(o["expected"]["error"][:40], 0) + 1
    print("errors:", errors)


if __name__ == "__main__":
    main()
