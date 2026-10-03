"""Among-Us-Lobbyeinstellungen: deutsche Namen, Einheiten und Kategorien für /rundensettings.

Die Schlüssel sind die Options-Namen aus dem Spiel (Int32/Float/BoolOptionNames bzw. RoleTypes),
so wie die CrewStats-Mod sie schickt. Unbekannte (neue) Optionen zeigt der Bot roh unter „Weitere“.
"""

from collections.abc import Callable
from dataclasses import dataclass

# ==========================================
# FORMATIERUNG
# ==========================================


def _number(value: float) -> str:
    return f"{value:g}".replace(".", ",")


def seconds(value: float) -> str:
    return f"{_number(value)} s"


def multiplier(value: float) -> str:
    return f"{_number(value)}x"


def plain(value: float) -> str:
    return _number(value)


def percent(value: float) -> str:
    return f"{_number(value)} %"


def on_off(value: bool) -> str:
    return "✅ An" if value else "❌ Aus"


def choice(options: dict[int, str]) -> Callable[[int], str]:
    return lambda value: options.get(int(value), str(value))


@dataclass(frozen=True)
class Setting:
    kind: str  # ints | floats | bools
    key: str
    label: str
    format: Callable = plain


# ==========================================
# KATEGORIEN
# ==========================================


@dataclass(frozen=True)
class Category:
    key: str
    label: str
    emoji: str
    settings: tuple[Setting, ...] = ()


GENERAL = Category(
    "general", "Allgemein", "🏠",
    (
        Setting("floats", "KillCooldown", "Kill-Cooldown", seconds),
        Setting("ints", "KillDistance", "Kill-Distanz", choice({0: "Kurz", 1: "Mittel", 2: "Lang"})),
        Setting("floats", "PlayerSpeedMod", "Laufgeschwindigkeit", multiplier),
        Setting("floats", "CrewLightMod", "Crew-Sicht", multiplier),
        Setting("floats", "ImpostorLightMod", "Impostor-Sicht", multiplier),
        Setting("bools", "ConfirmImpostor", "Rauswurf bestätigen", on_off),
    ),
)
MEETINGS = Category(
    "meetings", "Meetings", "📢",
    (
        Setting("ints", "NumEmergencyMeetings", "Notfall-Meetings pro Spieler"),
        Setting("ints", "EmergencyCooldown", "Notfall-Cooldown", seconds),
        Setting("ints", "DiscussionTime", "Diskussionszeit", seconds),
        Setting("ints", "VotingTime", "Abstimmungszeit", seconds),
        Setting("bools", "AnonymousVotes", "Anonyme Stimmen", on_off),
    ),
)
TASKS = Category(
    "tasks", "Aufgaben", "🧰",
    (
        Setting("ints", "TaskBarMode", "Taskleiste", choice({0: "Immer", 1: "Nur bei Meetings", 2: "Nie"})),
        Setting("bools", "VisualTasks", "Sichtbare Tasks", on_off),
        Setting("ints", "NumCommonTasks", "Gemeinsame Tasks"),
        Setting("ints", "NumLongTasks", "Lange Tasks"),
        Setting("ints", "NumShortTasks", "Kurze Tasks"),
        Setting("bools", "GhostsDoTasks", "Geister machen Tasks", on_off),
    ),
)
ROLES = Category("roles", "Rollen", "🎭")

CATEGORIES: tuple[Category, ...] = (GENERAL, MEETINGS, TASKS, ROLES)


# ==========================================
# ROLLEN
# ==========================================


@dataclass(frozen=True)
class RoleSettings:
    game_name: str  # RoleTypes aus dem Spiel
    name: str  # Anzeigename (wie in /rolle)
    impostor: bool
    settings: tuple[Setting, ...]


ROLE_SETTINGS: tuple[RoleSettings, ...] = (
    RoleSettings("Engineer", "Engineer", False, (
        Setting("floats", "EngineerCooldown", "Vent-Cooldown", seconds),
        Setting("floats", "EngineerInVentMaxTime", "Max. Zeit im Vent", seconds),
    )),
    RoleSettings("Scientist", "Scientist", False, (
        Setting("floats", "ScientistCooldown", "Vitals-Cooldown", seconds),
        Setting("floats", "ScientistBatteryCharge", "Batteriedauer", seconds),
    )),
    RoleSettings("GuardianAngel", "Guardian Angel", False, (
        Setting("floats", "GuardianAngelCooldown", "Schutz-Cooldown", seconds),
        Setting("floats", "ProtectionDurationSeconds", "Schutzdauer", seconds),
        Setting("bools", "ImpostorsCanSeeProtect", "Impostor sehen Schutz", on_off),
    )),
    RoleSettings("Tracker", "Tracker", False, (
        Setting("floats", "TrackerCooldown", "Cooldown", seconds),
        Setting("floats", "TrackerDuration", "Dauer", seconds),
        Setting("floats", "TrackerDelay", "Verzögerung", seconds),
    )),
    RoleSettings("Noisemaker", "Noisemaker", False, (
        Setting("floats", "NoisemakerAlertDuration", "Alarmdauer", seconds),
        Setting("bools", "NoisemakerImpostorAlert", "Impostor bekommen Alarm", on_off),
    )),
    RoleSettings("Detective", "Detective", False, (
        Setting("floats", "DetectiveSuspectLimit", "Verdächtigen-Limit"),
    )),
    RoleSettings("Judge", "Judge", False, (
        Setting("floats", "JudgeTaskRequirementPercentage", "Nötige Tasks", percent),
    )),
    # Im Spiel intern „SpiritGuide“ – im Spiel und in /rolle heißt die Rolle Influencer
    RoleSettings("SpiritGuide", "Influencer", False, (
        Setting("floats", "SpiritGuideCooldownSeconds", "Cooldown", seconds),
    )),
    RoleSettings("Shapeshifter", "Shapeshifter", True, (
        Setting("floats", "ShapeshifterCooldown", "Cooldown", seconds),
        Setting("floats", "ShapeshifterDuration", "Dauer", seconds),
        Setting("bools", "ShapeshifterLeaveSkin", "Hinterlässt Beweise", on_off),
    )),
    RoleSettings("Phantom", "Phantom", True, (
        Setting("floats", "PhantomCooldown", "Cooldown", seconds),
        Setting("floats", "PhantomDuration", "Unsichtbar-Dauer", seconds),
    )),
    RoleSettings("Viper", "Viper", True, (
        Setting("floats", "ViperDissolveTime", "Auflösezeit", seconds),
    )),
)


# Optionen, die zu Hide & Seek gehören oder nur intern sind – werden nie angezeigt
IGNORED_OPTIONS = frozenset({
    "NumImpostors", "MaxImpostors", "MinPlayers", "MaxPlayers", "Tag", "ImpostorPlayerID", "RulePreset",
    "CrewmatesRemainingForVitals", "CrewmateVentUses", "CrewmateTimeInVent", "FinalEscapeTime", "EscapeTime",
    "SeekerFinalSpeed", "MaxPingTime", "CrewmateFlashlightSize", "ImpostorFlashlightSize", "UseFlashlight",
    "SeekerFinalVents", "SeekerFinalMap", "SeekerPings", "ShowCrewmateNames", "IsDefaults", "Roles",
})


def known_option_keys() -> set[str]:
    keys = {s.key for category in CATEGORIES for s in category.settings}
    keys |= {s.key for role in ROLE_SETTINGS for s in role.settings}
    return keys | IGNORED_OPTIONS
