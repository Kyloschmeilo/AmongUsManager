"""Minecraft-Wissen für die Kopplung mit dem CoreSMP-Plugin: Nachrichtenarten, Todesursachen, Mobs."""

from dataclasses import dataclass
from enum import Enum


# ==========================================
# NACHRICHTENARTEN (per /dc an- und abschaltbar)
# ==========================================


@dataclass(frozen=True)
class EventInfo:
    emoji: str
    label: str
    description: str


class McEventType(str, Enum):
    """Werte = Feld `type` im JSON des Plugins (DiscordListener.java)."""

    JOIN = "join"
    LEAVE = "leave"
    DEATH = "death"
    CRAFT = "craft"

    @property
    def info(self) -> EventInfo:
        return EVENT_INFO[self]


EVENT_INFO: dict[McEventType, EventInfo] = {
    McEventType.JOIN: EventInfo("📥", "Beitreten", "Jemand betritt den Server (erstes Mal extra)"),
    McEventType.LEAVE: EventInfo("📤", "Verlassen", "Jemand verlässt den Server, mit Spielzeit"),
    McEventType.DEATH: EventInfo("💀", "Tode", "Todesursache, Mörder und Special-Item-Waffe"),
    McEventType.CRAFT: EventInfo("✨", "Special Items", "Ein Special Item wurde gecraftet"),
}


# ==========================================
# TODESURSACHEN (Minecraft-Damage-Types)
# ==========================================


@dataclass(frozen=True)
class DeathCause:
    emoji: str
    text: str  # ohne Schuldigen
    by_killer: str | None = None  # mit Schuldigem, {killer} steht im Dativ ("einem **Zombie**")


DEFAULT_DEATH = DeathCause("💀", "ist gestorben", "wurde von {killer} getötet")

_MELEE = DeathCause("⚔️", "wurde erschlagen", "wurde von {killer} erschlagen")
_EXPLOSION = DeathCause("💥", "ist explodiert", "wurde von {killer} in die Luft gesprengt")
_FIRE = DeathCause("🔥", "ist in Flammen aufgegangen", "ist im Kampf mit {killer} verbrannt")
_FIREBALL = DeathCause("☄️", "wurde von einem Feuerball getroffen", "wurde von {killer} mit einem Feuerball getroffen")
_MAGIC = DeathCause("🧪", "wurde durch Magie getötet", "wurde von {killer} mit Magie getötet")
_DRIPSTONE = DeathCause("🪨", "wurde von einem Tropfstein aufgespießt")

DEATH_CAUSES: dict[str, DeathCause] = {
    "player_attack": _MELEE,
    "mob_attack": _MELEE,
    "mob_attack_no_aggro": _MELEE,
    "arrow": DeathCause("🏹", "wurde erschossen", "wurde von {killer} erschossen"),
    "trident": DeathCause("🔱", "wurde aufgespießt", "wurde von {killer} aufgespießt"),
    "mob_projectile": DeathCause("🎯", "wurde abgeschossen", "wurde von {killer} abgeschossen"),
    "thrown": DeathCause("🎯", "wurde abgeworfen", "wurde von {killer} abgeworfen"),
    "spit": DeathCause("💦", "wurde zu Tode gespuckt", "wurde von {killer} zu Tode gespuckt"),
    "fireball": _FIREBALL,
    "unattributed_fireball": _FIREBALL,
    "wither_skull": DeathCause("💀", "wurde von einem Wither-Schädel getroffen", "wurde von {killer} mit einem Schädel getroffen"),
    "sting": DeathCause("🐝", "wurde zu Tode gestochen", "wurde von {killer} zu Tode gestochen"),
    "wind_charge": DeathCause("🌪️", "wurde weggepustet", "wurde von {killer} weggepustet"),
    "mace_smash": DeathCause("🔨", "wurde zerschmettert", "wurde von {killer} zerschmettert"),
    "sonic_boom": DeathCause("🔊", "wurde von einem Schallstoß ausgelöscht", "wurde von {killer} mit einem Schallstoß ausgelöscht"),
    "explosion": _EXPLOSION,
    "player_explosion": _EXPLOSION,
    "fireworks": DeathCause("🎆", "ist mit einem Knall gegangen", "wurde von {killer} mit Feuerwerk erwischt"),
    "bad_respawn_point": DeathCause("🛏️", "hat im falschen Bett geschlafen"),
    "fall": DeathCause("🪂", "ist zu tief gefallen", "wurde von {killer} in den Tod gestoßen"),
    "ender_pearl": DeathCause("🟣", "hat sich zu Tode teleportiert"),
    "fly_into_wall": DeathCause("🪽", "hat die Wand zu spät gesehen"),
    "out_of_world": DeathCause("🕳️", "ist aus der Welt gefallen", "wurde von {killer} ins Nichts gestoßen"),
    "outside_border": DeathCause("🧱", "hat die Weltgrenze unterschätzt"),
    "lava": DeathCause("🌋", "wollte in Lava schwimmen", "ist auf der Flucht vor {killer} in Lava gefallen"),
    "in_fire": _FIRE,
    "campfire": _FIRE,
    "on_fire": DeathCause("🔥", "ist verbrannt", "ist im Kampf mit {killer} verbrannt"),
    "hot_floor": DeathCause("🔥", "hat den Magmablock unterschätzt", "ist auf der Flucht vor {killer} auf Magma verbrannt"),
    "lightning_bolt": DeathCause("⚡", "wurde vom Blitz getroffen", "wurde von {killer} mit einem Blitz erwischt"),
    "drown": DeathCause("🫧", "ist ertrunken", "ist auf der Flucht vor {killer} ertrunken"),
    "dry_out": DeathCause("🐟", "ist ausgetrocknet"),
    "starve": DeathCause("🍖", "ist verhungert"),
    "in_wall": DeathCause("🧱", "ist in einer Wand erstickt"),
    "cramming": DeathCause("🫠", "wurde zerquetscht"),
    "cactus": DeathCause("🌵", "wurde zu Tode gepikst", "ist auf der Flucht vor {killer} in einen Kaktus gelaufen"),
    "sweet_berry_bush": DeathCause("🫐", "wurde von einem Beerenstrauch zerstochen"),
    "freeze": DeathCause("🥶", "ist erfroren", "wurde von {killer} eingefroren"),
    "magic": _MAGIC,
    "indirect_magic": _MAGIC,
    "wither": DeathCause("🥀", "ist verdorrt"),
    "dragon_breath": DeathCause("🐉", "wurde vom Drachenatem geröstet"),
    "thorns": DeathCause("🌹", "wurde von Dornen getötet", "wurde beim Angriff auf {killer} von Dornen getötet"),
    "falling_anvil": DeathCause("🔨", "wurde von einem Amboss zerquetscht"),
    "falling_block": DeathCause("🪨", "wurde von einem Block erschlagen"),
    "falling_stalactite": _DRIPSTONE,
    "stalagmite": _DRIPSTONE,
    "generic_kill": DeathCause("☠️", "wurde getötet", "wurde von {killer} getötet"),
    "generic": DEFAULT_DEATH,
}


# ==========================================
# MOBS (Artikel im Dativ, Name)
# ==========================================

MOBS: dict[str, tuple[str, str]] = {
    "zombie": ("einem", "Zombie"),
    "zombie_villager": ("einem", "Zombiedorfbewohner"),
    "husk": ("einem", "Wüstenzombie"),
    "drowned": ("einem", "Ertrunkenen"),
    "skeleton": ("einem", "Skelett"),
    "stray": ("einem", "Eiswanderer"),
    "bogged": ("einem", "Sumpfskelett"),
    "wither_skeleton": ("einem", "Witherskelett"),
    "spider": ("einer", "Spinne"),
    "cave_spider": ("einer", "Höhlenspinne"),
    "creeper": ("einem", "Creeper"),
    "enderman": ("einem", "Enderman"),
    "endermite": ("einer", "Endermite"),
    "silverfish": ("einem", "Silberfischchen"),
    "witch": ("einer", "Hexe"),
    "slime": ("einem", "Schleim"),
    "magma_cube": ("einem", "Magmawürfel"),
    "blaze": ("einer", "Lohe"),
    "ghast": ("einem", "Ghast"),
    "guardian": ("einem", "Wächter"),
    "elder_guardian": ("einem", "Großen Wächter"),
    "phantom": ("einem", "Phantom"),
    "pillager": ("einem", "Plünderer"),
    "vindicator": ("einem", "Diener"),
    "evoker": ("einem", "Magier"),
    "evoker_fangs": ("einem", "Magier"),
    "vex": ("einem", "Plagegeist"),
    "ravager": ("einem", "Verwüster"),
    "illusioner": ("einem", "Illusionisten"),
    "piglin": ("einem", "Piglin"),
    "piglin_brute": ("einem", "Piglin-Barbaren"),
    "zombified_piglin": ("einem", "Zombifizierten Piglin"),
    "hoglin": ("einem", "Hoglin"),
    "zoglin": ("einem", "Zoglin"),
    "shulker": ("einem", "Shulker"),
    "breeze": ("einer", "Böe"),
    "creaking": ("einem", "Knarz"),
    "warden": ("dem", "Warden"),
    "wither": ("dem", "Wither"),
    "ender_dragon": ("dem", "Enderdrachen"),
    "iron_golem": ("einem", "Eisengolem"),
    "snow_golem": ("einem", "Schneegolem"),
    "wolf": ("einem", "Wolf"),
    "bee": ("einer", "Biene"),
    "llama": ("einem", "Lama"),
    "trader_llama": ("einem", "Händlerlama"),
    "polar_bear": ("einem", "Eisbären"),
    "panda": ("einem", "Panda"),
    "goat": ("einer", "Ziege"),
    "dolphin": ("einem", "Delfin"),
    "pufferfish": ("einem", "Kugelfisch"),
    "end_crystal": ("einem", "Endkristall"),
    "tnt": ("einem", "TNT"),
    "lightning_bolt": ("einem", "Blitz"),
}

DIMENSIONS: dict[str, str] = {
    "normal": "🌍 Oberwelt",
    "nether": "🔥 Nether",
    "the_end": "🌌 End",
}

# Fähigkeiten, deren Name sich nicht einfach aus der ID ergibt (sonst: "lichtschwert" -> "Lichtschwert")
ABILITY_NAMES: dict[str, str] = {
    "sculkstab": "Sculk-Stab",
    "schildbrecher": "Schildbrecher-Axt",
}


def ability_name(ability_id: str) -> str:
    return ABILITY_NAMES.get(ability_id, ability_id.replace("_", " ").title())
