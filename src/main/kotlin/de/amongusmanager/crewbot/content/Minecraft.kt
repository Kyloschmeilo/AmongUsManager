/* Minecraft-Wissen für die Kopplung mit dem CoreSMP-Plugin: Nachrichtenarten, Todesursachen, Mobs. */
package de.amongusmanager.crewbot.content

// ==========================================
// NACHRICHTENARTEN (per /dc an- und abschaltbar)
// ==========================================

data class EventInfo(val emoji: String, val label: String, val description: String)

/** Werte = Feld `type` im JSON des Plugins (DiscordListener.java). */
enum class McEventType(val value: String, val info: EventInfo) {
    JOIN("join", EventInfo("📥", "Beitreten", "Jemand betritt den Server (erstes Mal extra)")),
    LEAVE("leave", EventInfo("📤", "Verlassen", "Jemand verlässt den Server, mit Spielzeit")),
    DEATH("death", EventInfo("💀", "Tode", "Todesursache, Mörder und Special-Item-Waffe")),
    CRAFT("craft", EventInfo("✨", "Special Items", "Ein Special Item wurde gecraftet"));

    companion object {
        fun fromValue(value: String?): McEventType? = entries.firstOrNull { it.value == value }
    }
}

// ==========================================
// TODESURSACHEN (Minecraft-Damage-Types)
// ==========================================

data class DeathCause(
    val emoji: String,
    val text: String, // ohne Schuldigen
    val byKiller: String? = null, // mit Schuldigem, {killer} steht im Dativ ("einem **Zombie**")
)

val DEFAULT_DEATH = DeathCause("💀", "ist gestorben", "wurde von {killer} getötet")

private val MELEE = DeathCause("⚔️", "wurde erschlagen", "wurde von {killer} erschlagen")
private val EXPLOSION = DeathCause("💥", "ist explodiert", "wurde von {killer} in die Luft gesprengt")
private val FIRE = DeathCause("🔥", "ist in Flammen aufgegangen", "ist im Kampf mit {killer} verbrannt")
private val FIREBALL = DeathCause(
    "☄️", "wurde von einem Feuerball getroffen", "wurde von {killer} mit einem Feuerball getroffen",
)
private val MAGIC = DeathCause("🧪", "wurde durch Magie getötet", "wurde von {killer} mit Magie getötet")
private val DRIPSTONE = DeathCause("🪨", "wurde von einem Tropfstein aufgespießt")

val DEATH_CAUSES: Map<String, DeathCause> = mapOf(
    "player_attack" to MELEE,
    "mob_attack" to MELEE,
    "mob_attack_no_aggro" to MELEE,
    "arrow" to DeathCause("🏹", "wurde erschossen", "wurde von {killer} erschossen"),
    "trident" to DeathCause("🔱", "wurde aufgespießt", "wurde von {killer} aufgespießt"),
    "mob_projectile" to DeathCause("🎯", "wurde abgeschossen", "wurde von {killer} abgeschossen"),
    "thrown" to DeathCause("🎯", "wurde abgeworfen", "wurde von {killer} abgeworfen"),
    "spit" to DeathCause("💦", "wurde zu Tode gespuckt", "wurde von {killer} zu Tode gespuckt"),
    "fireball" to FIREBALL,
    "unattributed_fireball" to FIREBALL,
    "wither_skull" to DeathCause(
        "💀", "wurde von einem Wither-Schädel getroffen", "wurde von {killer} mit einem Schädel getroffen",
    ),
    "sting" to DeathCause("🐝", "wurde zu Tode gestochen", "wurde von {killer} zu Tode gestochen"),
    "wind_charge" to DeathCause("🌪️", "wurde weggepustet", "wurde von {killer} weggepustet"),
    "mace_smash" to DeathCause("🔨", "wurde zerschmettert", "wurde von {killer} zerschmettert"),
    "sonic_boom" to DeathCause(
        "🔊", "wurde von einem Schallstoß ausgelöscht", "wurde von {killer} mit einem Schallstoß ausgelöscht",
    ),
    "explosion" to EXPLOSION,
    "player_explosion" to EXPLOSION,
    "fireworks" to DeathCause("🎆", "ist mit einem Knall gegangen", "wurde von {killer} mit Feuerwerk erwischt"),
    "bad_respawn_point" to DeathCause("🛏️", "hat im falschen Bett geschlafen"),
    "fall" to DeathCause("🪂", "ist zu tief gefallen", "wurde von {killer} in den Tod gestoßen"),
    "ender_pearl" to DeathCause("🟣", "hat sich zu Tode teleportiert"),
    "fly_into_wall" to DeathCause("🪽", "hat die Wand zu spät gesehen"),
    "out_of_world" to DeathCause("🕳️", "ist aus der Welt gefallen", "wurde von {killer} ins Nichts gestoßen"),
    "outside_border" to DeathCause("🧱", "hat die Weltgrenze unterschätzt"),
    "lava" to DeathCause("🌋", "wollte in Lava schwimmen", "ist auf der Flucht vor {killer} in Lava gefallen"),
    "in_fire" to FIRE,
    "campfire" to FIRE,
    "on_fire" to DeathCause("🔥", "ist verbrannt", "ist im Kampf mit {killer} verbrannt"),
    "hot_floor" to DeathCause(
        "🔥", "hat den Magmablock unterschätzt", "ist auf der Flucht vor {killer} auf Magma verbrannt",
    ),
    "lightning_bolt" to DeathCause("⚡", "wurde vom Blitz getroffen", "wurde von {killer} mit einem Blitz erwischt"),
    "drown" to DeathCause("🫧", "ist ertrunken", "ist auf der Flucht vor {killer} ertrunken"),
    "dry_out" to DeathCause("🐟", "ist ausgetrocknet"),
    "starve" to DeathCause("🍖", "ist verhungert"),
    "in_wall" to DeathCause("🧱", "ist in einer Wand erstickt"),
    "cramming" to DeathCause("🫠", "wurde zerquetscht"),
    "cactus" to DeathCause(
        "🌵", "wurde zu Tode gepikst", "ist auf der Flucht vor {killer} in einen Kaktus gelaufen",
    ),
    "sweet_berry_bush" to DeathCause("🫐", "wurde von einem Beerenstrauch zerstochen"),
    "freeze" to DeathCause("🥶", "ist erfroren", "wurde von {killer} eingefroren"),
    "magic" to MAGIC,
    "indirect_magic" to MAGIC,
    "wither" to DeathCause("🥀", "ist verdorrt"),
    "dragon_breath" to DeathCause("🐉", "wurde vom Drachenatem geröstet"),
    "thorns" to DeathCause("🌹", "wurde von Dornen getötet", "wurde beim Angriff auf {killer} von Dornen getötet"),
    "falling_anvil" to DeathCause("🔨", "wurde von einem Amboss zerquetscht"),
    "falling_block" to DeathCause("🪨", "wurde von einem Block erschlagen"),
    "falling_stalactite" to DRIPSTONE,
    "stalagmite" to DRIPSTONE,
    "generic_kill" to DeathCause("☠️", "wurde getötet", "wurde von {killer} getötet"),
    "generic" to DEFAULT_DEATH,
)

// ==========================================
// MOBS (Artikel im Dativ, Name)
// ==========================================

val MOBS: Map<String, Pair<String, String>> = mapOf(
    "zombie" to ("einem" to "Zombie"),
    "zombie_villager" to ("einem" to "Zombiedorfbewohner"),
    "husk" to ("einem" to "Wüstenzombie"),
    "drowned" to ("einem" to "Ertrunkenen"),
    "skeleton" to ("einem" to "Skelett"),
    "stray" to ("einem" to "Eiswanderer"),
    "bogged" to ("einem" to "Sumpfskelett"),
    "wither_skeleton" to ("einem" to "Witherskelett"),
    "spider" to ("einer" to "Spinne"),
    "cave_spider" to ("einer" to "Höhlenspinne"),
    "creeper" to ("einem" to "Creeper"),
    "enderman" to ("einem" to "Enderman"),
    "endermite" to ("einer" to "Endermite"),
    "silverfish" to ("einem" to "Silberfischchen"),
    "witch" to ("einer" to "Hexe"),
    "slime" to ("einem" to "Schleim"),
    "magma_cube" to ("einem" to "Magmawürfel"),
    "blaze" to ("einer" to "Lohe"),
    "ghast" to ("einem" to "Ghast"),
    "guardian" to ("einem" to "Wächter"),
    "elder_guardian" to ("einem" to "Großen Wächter"),
    "phantom" to ("einem" to "Phantom"),
    "pillager" to ("einem" to "Plünderer"),
    "vindicator" to ("einem" to "Diener"),
    "evoker" to ("einem" to "Magier"),
    "evoker_fangs" to ("einem" to "Magier"),
    "vex" to ("einem" to "Plagegeist"),
    "ravager" to ("einem" to "Verwüster"),
    "illusioner" to ("einem" to "Illusionisten"),
    "piglin" to ("einem" to "Piglin"),
    "piglin_brute" to ("einem" to "Piglin-Barbaren"),
    "zombified_piglin" to ("einem" to "Zombifizierten Piglin"),
    "hoglin" to ("einem" to "Hoglin"),
    "zoglin" to ("einem" to "Zoglin"),
    "shulker" to ("einem" to "Shulker"),
    "breeze" to ("einer" to "Böe"),
    "creaking" to ("einem" to "Knarz"),
    "warden" to ("dem" to "Warden"),
    "wither" to ("dem" to "Wither"),
    "ender_dragon" to ("dem" to "Enderdrachen"),
    "iron_golem" to ("einem" to "Eisengolem"),
    "snow_golem" to ("einem" to "Schneegolem"),
    "wolf" to ("einem" to "Wolf"),
    "bee" to ("einer" to "Biene"),
    "llama" to ("einem" to "Lama"),
    "trader_llama" to ("einem" to "Händlerlama"),
    "polar_bear" to ("einem" to "Eisbären"),
    "panda" to ("einem" to "Panda"),
    "goat" to ("einer" to "Ziege"),
    "dolphin" to ("einem" to "Delfin"),
    "pufferfish" to ("einem" to "Kugelfisch"),
    "end_crystal" to ("einem" to "Endkristall"),
    "tnt" to ("einem" to "TNT"),
    "lightning_bolt" to ("einem" to "Blitz"),
)

val DIMENSIONS: Map<String, String> = mapOf(
    "normal" to "🌍 Oberwelt",
    "nether" to "🔥 Nether",
    "the_end" to "🌌 End",
)

/** Fähigkeiten, deren Name sich nicht einfach aus der ID ergibt (sonst: "lichtschwert" -> "Lichtschwert"). */
val ABILITY_NAMES: Map<String, String> = mapOf(
    "sculkstab" to "Sculk-Stab",
    "schildbrecher" to "Schildbrecher-Axt",
)

fun abilityName(abilityId: String): String = ABILITY_NAMES[abilityId] ?: pyTitle(abilityId.replace("_", " "))

/** Python `str.title()`: erster Buchstabe jedes Worts groß, der Rest klein. */
fun pyTitle(text: String): String {
    val result = StringBuilder(text.length)
    var previousCased = false
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val cased = Character.isUpperCase(codePoint) || Character.isLowerCase(codePoint) ||
            Character.isTitleCase(codePoint)
        val converted = if (previousCased) {
            String(Character.toChars(codePoint)).lowercase()
        } else {
            String(Character.toChars(codePoint)).uppercase()
        }
        result.append(converted)
        previousCased = cased
        index += Character.charCount(codePoint)
    }
    return result.toString()
}
