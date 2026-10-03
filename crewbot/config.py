"""Zentrale Konfiguration: Tokens, Discord-IDs und Spielregeln.

Tokens stehen NICHT im Code, sondern in der Datei `.env` im Projektordner
(Vorlage: `.env.example`). Alles andere, was man am Server ändern möchte
(Kanäle, Rollen, XP-Werte), wird ausschließlich hier eingestellt.
"""

import os
from pathlib import Path

# ==========================================
# PFADE
# ==========================================

PROJECT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_DIR / "data"
LEVELS_FILE = DATA_DIR / "levels.json"
BETS_FILE = DATA_DIR / "bets.json"
EVENTS_FILE = DATA_DIR / "events.json"
LINKS_FILE = DATA_DIR / "links.json"  # Among-Us-Friendcode -> Discord-ID
GAME_STATS_FILE = DATA_DIR / "game_stats.json"  # Statistiken aus der Mod
MOD_ROUNDS_FILE = DATA_DIR / "mod_rounds.json"  # ausgewertete Mod-Runden (für Zurücksetzen)
MOD_CONFIG_FILE = DATA_DIR / "mod.json"  # Webhook der Mod
GAME_SETTINGS_FILE = DATA_DIR / "game_settings.json"  # Lobbyeinstellungen der letzten Runde (/rundensettings)
MINECRAFT_FILE = DATA_DIR / "minecraft.json"  # Minecraft-Plugin: Webhook, Channel, Nachrichten (/dc)


# ==========================================
# TOKENS (.env)
# ==========================================


def _load_env_file(path: Path) -> None:
    """Minimaler .env-Loader (KEY=VALUE pro Zeile) ohne externe Abhängigkeit."""
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


_load_env_file(PROJECT_DIR / ".env")

DISCORD_TOKEN = os.getenv("DISCORD_TOKEN", "").strip()
WORKER_TOKENS = [
    token.strip()
    for token in os.getenv("WORKER_TOKENS", "").split(",")
    if token.strip()
]

TIMEZONE = "Europe/Berlin"


# ==========================================
# DISCORD IDs
# ==========================================


class Channels:
    AMONG_US_VOICE = 1462514637680672861
    AUTO_UNMUTE_VOICE = 1462514709612859706  # Wer hier beitritt, wird entmutet
    CODES = 1463921458249076959  # Lobby-Codes (/code)
    LOG = 1463920288214548684  # Admin-/Host-Log (Admin-Panel, /mod-setup, ausgewertete Runden …)
    # Rohdaten des Minecraft-Plugins (Webhook, Bot löscht sie sofort). Der Bot verschiebt den
    # Webhook beim Start selbst hierher – die URL im Plugin bleibt dabei gleich.
    MINECRAFT_INBOX = LOG
    ROLE_LOG = 1463920288214548684
    MINECRAFT = 1463920288214548684  # Standard für Minecraft-Ankündigungen (vorerst #test, änderbar mit /dc)


class Roles:
    AMONG_US_PING = 1515459584557322422
    EVENT_MANAGER = 1515473925990256740


# Spiel -> Rolle, die beim Event gepingt wird (Reihenfolge = Auswahlmenü)
GAME_PING_ROLES: dict[str, int] = {
    "Among Us": Roles.AMONG_US_PING,
    "Risiko": 1515459654010802226,
    "R.E.P.O.": 1515459719769227334,
    "Stickfight": 1515459966012756139,
    "Ultimate Chicken Horse": 1515460094559522896,
    "GeoGuessr": 1515462477847072828,
    "PEAK": 1515459802065797131,
    "Browser Games": 1515473357477515425,
    "Lethal Company": 1515462551150788798,
    "Meccha Chameleon": 1521928174298992680,
    "ROUNDS": 1515459848290959601,
}

# Level -> Rolle, die ab diesem Level automatisch vergeben wird
LEVEL_ROLE_REWARDS: dict[int, int] = {
    5: Roles.EVENT_MANAGER,
}


# ==========================================
# SPIELREGELN
# ==========================================


class Rules:
    XP_PER_LEVEL = 50

    EVENT_ATTENDANCE_XP = 10  # gibt es erst, wenn man beim Event wirklich im Voice war
    IMPOSTOR_WIN_XP = 25
    CREW_WIN_XP = 10
    MVP_XP = 50

    MAX_BET = 50
    BET_PAYOUT_FACTOR = 2

    MVP_VOTING_SECONDS = 60
    EVENT_DURATION_HOURS = 2
    EVENT_REMINDER_MINUTES = 15  # Erinnerung so viele Minuten vor Start
    EVENT_MIN_ATTENDANCE_MINUTES = 15  # so lange muss man im Voice sein, um als anwesend zu zählen

    FUN_TIMEOUT_CHANCE = 0.10
    FUN_TIMEOUT_MINUTES = 10
