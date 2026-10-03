"""Among-Us-Wissen: Teams, Rollen, Maps und Spaß-Texte."""

from dataclasses import dataclass
from enum import Enum


class Team(str, Enum):
    CREW = "CREW"
    IMPOSTOR = "IMPOSTOR"

    @property
    def label(self) -> str:
        return "🟢 Crewmates" if self is Team.CREW else "🔴 Impostor"


# ==========================================
# ROLLEN
# ==========================================


@dataclass(frozen=True)
class AmongUsRole:
    name: str
    team: Team
    emoji: str
    description: str


ROLES: dict[str, AmongUsRole] = {
    role.name: role
    for role in (
        AmongUsRole(
            "Crewmate", Team.CREW, "🚀",
            "Deine Aufgabe ist es, alle Tasks zu erledigen oder die Impostor zu"
            " entlarven. Halte die Augen offen!",
        ),
        AmongUsRole(
            "Impostor", Team.IMPOSTOR, "🔪",
            "Verschwinde in Vents, sabotiere die Map und eliminiere die Crew,"
            " ohne erwischt zu werden.",
        ),
        AmongUsRole(
            "Engineer", Team.CREW, "🔧",
            "Du gehörst zur Crew, kannst aber (im Gegensatz zu normalen"
            " Crewmates) die Vents zur Fortbewegung nutzen.",
        ),
        AmongUsRole(
            "Scientist", Team.CREW, "🧪",
            "Du kannst jederzeit die Vitalfunktionen (Vitals) aller Spieler"
            " einsehen, musst aber deine Batterie durch Tasks aufladen.",
        ),
        AmongUsRole(
            "Shapeshifter", Team.IMPOSTOR, "🎭",
            "Ein Impostor, der das Aussehen eines anderen Spielers annehmen"
            " kann, um Verwirrung zu stiften.",
        ),
        AmongUsRole(
            "Guardian Angel", Team.CREW, "😇",
            "Als toter Crewmate kannst du lebenden Crewmates ein Schutzschild"
            " geben, das einen Kill-Versuch blockiert.",
        ),
        AmongUsRole(
            "Phantom", Team.IMPOSTOR, "👻",
            "Ein Impostor, der sich für kurze Zeit unsichtbar machen kann, um"
            " nach einem Kill unerkannt zu entkommen.",
        ),
        AmongUsRole(
            "Tracker", Team.CREW, "📡",
            "Du kannst einen Peilsender an einem Spieler anbringen, um seine"
            " Position auf der Karte für begrenzte Zeit zu sehen.",
        ),
        AmongUsRole(
            "Noisemaker", Team.CREW, "📢",
            "Wenn du gekillt wirst, löst du einen lauten akustischen und"
            " visuellen Alarm aus, der allen zeigt, wo deine Leiche liegt.",
        ),
        AmongUsRole(
            "Viper", Team.IMPOSTOR, "🐍",
            "Ersetzt den normalen Kill durch einen Säureangriff, der die Leiche"
            " des Opfers komplett auflöst, sodass sie nicht mehr von anderen"
            " gemeldet werden kann.",
        ),
        AmongUsRole(
            "Judge", Team.CREW, "🔨",
            "Der Judge ist ein Crewmate, der während einer Versammlung mit einem"
            " Chat-Befehl sofort einen Verdächtigen eliminieren kann, aber"
            " selbst stirbt, wenn er fälschlicherweise einen unschuldigen"
            " Mitspieler wählt.",
        ),
        AmongUsRole(
            "Influencer", Team.CREW, "📸",
            "Als toter Crewmate schickst du einem lebenden Crewmate bis zu drei"
            " zufällige Bilder, um ihn vor dem Impostor zu warnen – sie können"
            " aber auch falsch verstanden werden.",
        ),
    )
}


# Rollennamen, wie die Mod sie schickt (RoleTypes aus dem Spiel) -> Anzeigename
_GAME_ROLE_NAMES = {"GuardianAngel": "Guardian Angel"}


def role_label(game_role: str) -> str:
    """'GuardianAngel' -> '😇 Guardian Angel' (unbekannte Rollen werden roh angezeigt)."""
    name = _GAME_ROLE_NAMES.get(game_role, game_role)
    role = ROLES.get(name)
    return f"{role.emoji} {name}" if role else name


# ==========================================
# MAPS
# ==========================================


@dataclass(frozen=True)
class GameMap:
    name: str
    description: str
    cameras: str
    danger_zones: str
    tasks: str
    vent_image: str


MAPS: dict[str, GameMap] = {
    game_map.name: game_map
    for game_map in (
        GameMap(
            name="The Skeld",
            description=(
                "Die klassische Map im Weltraum. Perfekt für Einsteiger und"
                " Veteranen."
            ),
            cameras=(
                "🏢 Security (überwacht Flure bei Navigation, MedBay,"
                " Cafeteria und Admin)."
            ),
            danger_zones=(
                "⚡ **Electrical** (die ultimative Todesfalle, schlechte Sicht),"
                " 🩻 **MedBay** (Sackgasse)."
            ),
            tasks=(
                "Ca. 14 verschiedene Tasks (inkl. Asteroiden schießen in"
                " Weapons)."
            ),
            vent_image="https://i.imgur.com/rNr7Cgt.png",
        ),
        GameMap(
            name="MIRA HQ",
            description=(
                "Eine HQ-Station hoch über den Wolken mit einem einzigartigen,"
                " durchgehenden Vent-System."
            ),
            cameras=(
                "🪵 Tür-Logbuch (Decontamination) – registriert, wer welchen"
                " Sensor passiert."
            ),
            danger_zones=(
                "🌱 **Greenhouse** (weit abgelegen), 🧪 **Laboratory**"
                " (Sackgasse)."
            ),
            tasks=(
                "Sehr kompakt, viele schnelle Tasks, aber langes Laufen durch"
                " Dekontamination."
            ),
            vent_image="https://i.imgur.com/y5DTp6m.png",
        ),
        GameMap(
            name="Polus",
            description=(
                "Eine riesige Forschungsstation auf einem schneebedeckten"
                " Lava-Planeten."
            ),
            cameras=(
                "🖥️ Security-Monitore (6 verschiedene Kamera-Ansichten) +"
                " Vitalfunktionen (Vitals)."
            ),
            danger_zones=(
                "🌋 **Specimen Room** (extrem weit isoliert), 🧪"
                " **Laboratory**."
            ),
            tasks=(
                "Viele manuelle Tasks (wie Temperatur regulieren oder Schlüssel"
                " drehen)."
            ),
            vent_image="https://i.imgur.com/AzrZUEg.png",
        ),
        GameMap(
            name="The Airship",
            description=(
                "Die größte Map im Spiel. Sehr unübersichtlich mit Leitern und"
                " schwebenden Plattformen."
            ),
            cameras="🏢 Security + Tresorraum-Kameras.",
            danger_zones="🏦 **Vault** (riesig und einsam), 🛋️ **Lounge**.",
            tasks=(
                "Sehr lange Wege, Tasks dauern oft länger (z.B. Safe knacken"
                " oder Burger machen)."
            ),
            vent_image="https://i.imgur.com/jVXKjCD.png",
        ),
        GameMap(
            name="The Fungle",
            description=(
                "Eine mysteriöse Pilz-Insel mit Strand, Dschungel und einer"
                " Klippe."
            ),
            cameras=(
                "🔭 Fernglas auf der Klippe (zeigt bestimmte Zonen der Map)."
            ),
            danger_zones=(
                "🍄 **Spore-Felder** (Impostor können Pilze platzen lassen, um"
                " die Sicht komplett zu blockieren)."
            ),
            tasks=(
                "Sehr verwinkelte Wege, einzigartige Tasks wie Angeln oder"
                " Marshmallows rösten."
            ),
            vent_image="https://i.imgur.com/4suuXye.png",
        ),
    )
}


# ==========================================
# SPASS & SONSTIGES
# ==========================================

FUN_TEXTS = ["Ich esse gerne Kacke", "Ultra Firma", "Schere Diggi", "Upsipupsi"]


@dataclass(frozen=True)
class SuspicionVerdict:
    max_percent: int  # obere Grenze (inklusive)
    text: str


SUSPICION_VERDICTS: tuple[SuspicionVerdict, ...] = (
    SuspicionVerdict(
        24,
        "🟢 **Unschuldig wie ein Lamm.** Deine Augen funkeln voller"
        " Ehrlichkeit. Du wirst heute wahrscheinlich als Erster gekillt, weil"
        " du zu brav bist.",
    ),
    SuspicionVerdict(
        49,
        "🟡 **Unauffällig.** Du machst brav deine Tasks und fällst im Meeting"
        " kaum auf. Ein solider Crewmate... oder ein verdammt ruhiger"
        " Impostor?",
    ),
    SuspicionVerdict(
        74,
        "🟠 **Ziemlich SUS.** Du schwitzt jetzt schon beim Kabel verbinden und"
        " schaust dich verdächtig oft nach Vents um. Die Crew behält dich im"
        " Auge!",
    ),
    SuspicionVerdict(
        100,
        "🔴 **ABSOLUTER IMPOSTOR!** Deine Augen zucken, dein Messer sitzt"
        " locker. Wenn du heute Electrical betrittst, kommt niemand lebend"
        " wieder raus. 🔪",
    ),
)


def verdict_for(percent: int) -> SuspicionVerdict:
    return next(v for v in SUSPICION_VERDICTS if percent <= v.max_percent)
