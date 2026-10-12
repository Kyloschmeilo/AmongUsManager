/* Among-Us-Wissen: Teams, Rollen, Maps und Spaß-Texte. */
package de.amongusmanager.crewbot.content

enum class Team(val value: String) {
    CREW("CREW"),
    IMPOSTOR("IMPOSTOR");

    val label: String get() = if (this == CREW) "🟢 Crewmates" else "🔴 Impostor"

    companion object {
        fun fromValue(value: String?): Team? = entries.firstOrNull { it.value == value }
    }
}

// ==========================================
// ROLLEN
// ==========================================

data class AmongUsRole(val name: String, val team: Team, val emoji: String, val description: String)

val ROLES: Map<String, AmongUsRole> = listOf(
    AmongUsRole(
        "Crewmate", Team.CREW, "🚀",
        "Deine Aufgabe ist es, alle Tasks zu erledigen oder die Impostor zu entlarven. Halte die Augen offen!",
    ),
    AmongUsRole(
        "Impostor", Team.IMPOSTOR, "🔪",
        "Verschwinde in Vents, sabotiere die Map und eliminiere die Crew, ohne erwischt zu werden.",
    ),
    AmongUsRole(
        "Engineer", Team.CREW, "🔧",
        "Du gehörst zur Crew, kannst aber (im Gegensatz zu normalen Crewmates) die Vents zur Fortbewegung nutzen.",
    ),
    AmongUsRole(
        "Scientist", Team.CREW, "🧪",
        "Du kannst jederzeit die Vitalfunktionen (Vitals) aller Spieler einsehen, musst aber deine Batterie" +
            " durch Tasks aufladen.",
    ),
    AmongUsRole(
        "Shapeshifter", Team.IMPOSTOR, "🎭",
        "Ein Impostor, der das Aussehen eines anderen Spielers annehmen kann, um Verwirrung zu stiften.",
    ),
    AmongUsRole(
        "Guardian Angel", Team.CREW, "😇",
        "Als toter Crewmate kannst du lebenden Crewmates ein Schutzschild geben, das einen Kill-Versuch blockiert.",
    ),
    AmongUsRole(
        "Phantom", Team.IMPOSTOR, "👻",
        "Ein Impostor, der sich für kurze Zeit unsichtbar machen kann, um nach einem Kill unerkannt zu entkommen.",
    ),
    AmongUsRole(
        "Tracker", Team.CREW, "📡",
        "Du kannst einen Peilsender an einem Spieler anbringen, um seine Position auf der Karte für begrenzte" +
            " Zeit zu sehen.",
    ),
    AmongUsRole(
        "Noisemaker", Team.CREW, "📢",
        "Wenn du gekillt wirst, löst du einen lauten akustischen und visuellen Alarm aus, der allen zeigt, wo" +
            " deine Leiche liegt.",
    ),
    AmongUsRole(
        "Viper", Team.IMPOSTOR, "🐍",
        "Ersetzt den normalen Kill durch einen Säureangriff, der die Leiche des Opfers komplett auflöst, sodass" +
            " sie nicht mehr von anderen gemeldet werden kann.",
    ),
    AmongUsRole(
        "Judge", Team.CREW, "🔨",
        "Der Judge ist ein Crewmate, der während einer Versammlung mit einem Chat-Befehl sofort einen" +
            " Verdächtigen eliminieren kann, aber selbst stirbt, wenn er fälschlicherweise einen unschuldigen" +
            " Mitspieler wählt.",
    ),
    AmongUsRole(
        "Influencer", Team.CREW, "📸",
        "Als toter Crewmate schickst du einem lebenden Crewmate bis zu drei zufällige Bilder, um ihn vor dem" +
            " Impostor zu warnen – sie können aber auch falsch verstanden werden.",
    ),
).associateByTo(LinkedHashMap()) { it.name }

/** Rollennamen, wie die Mod sie schickt (RoleTypes aus dem Spiel) -> Anzeigename. */
private val GAME_ROLE_NAMES = mapOf("GuardianAngel" to "Guardian Angel")

/** 'GuardianAngel' -> '😇 Guardian Angel' (unbekannte Rollen werden roh angezeigt). */
fun roleLabel(gameRole: String): String {
    val name = GAME_ROLE_NAMES[gameRole] ?: gameRole
    val role = ROLES[name]
    return if (role != null) "${role.emoji} $name" else name
}

/**
 * Rollen für /rollenwunsch und /rolle-zuweisen. `gameName` = RoleTypes im Spiel (so schickt der Bot sie an die Mod).
 * Geister-Rollen (Guardian Angel, Influencer) bekommt man erst nach dem Tod – die gehen nicht.
 * Crewmate/Impostor heißt „irgendeine Rolle dieses Teams“ (Impostor kann also auch Shapeshifter werden).
 */
data class WishRole(val gameName: String, val team: Team, val emoji: String) {
    val label: String get() = "$emoji $gameName"
    val isTeamRole: Boolean get() = gameName == "Crewmate" || gameName == "Impostor"
}

val WISH_ROLES: List<WishRole> = listOf(
    WishRole("Crewmate", Team.CREW, "🚀"),
    WishRole("Impostor", Team.IMPOSTOR, "🔪"),
    WishRole("Engineer", Team.CREW, "🔧"),
    WishRole("Scientist", Team.CREW, "🧪"),
    WishRole("Tracker", Team.CREW, "📡"),
    WishRole("Noisemaker", Team.CREW, "📢"),
    WishRole("Detective", Team.CREW, "🔍"),
    WishRole("Judge", Team.CREW, "🔨"),
    WishRole("Shapeshifter", Team.IMPOSTOR, "🎭"),
    WishRole("Phantom", Team.IMPOSTOR, "👻"),
    WishRole("Viper", Team.IMPOSTOR, "🐍"),
)

fun wishRole(gameName: String): WishRole? = WISH_ROLES.firstOrNull { it.gameName == gameName }

// ==========================================
// MAPS
// ==========================================

data class GameMap(
    val name: String,
    val description: String,
    val cameras: String,
    val dangerZones: String,
    val tasks: String,
    val ventImage: String,
)

val MAPS: Map<String, GameMap> = listOf(
    GameMap(
        name = "The Skeld",
        description = "Die klassische Map im Weltraum. Perfekt für Einsteiger und Veteranen.",
        cameras = "🏢 Security (überwacht Flure bei Navigation, MedBay, Cafeteria und Admin).",
        dangerZones = "⚡ **Electrical** (die ultimative Todesfalle, schlechte Sicht), 🩻 **MedBay** (Sackgasse).",
        tasks = "Ca. 14 verschiedene Tasks (inkl. Asteroiden schießen in Weapons).",
        ventImage = "https://i.imgur.com/rNr7Cgt.png",
    ),
    GameMap(
        name = "MIRA HQ",
        description = "Eine HQ-Station hoch über den Wolken mit einem einzigartigen, durchgehenden Vent-System.",
        cameras = "🪵 Tür-Logbuch (Decontamination) – registriert, wer welchen Sensor passiert.",
        dangerZones = "🌱 **Greenhouse** (weit abgelegen), 🧪 **Laboratory** (Sackgasse).",
        tasks = "Sehr kompakt, viele schnelle Tasks, aber langes Laufen durch Dekontamination.",
        ventImage = "https://i.imgur.com/y5DTp6m.png",
    ),
    GameMap(
        name = "Polus",
        description = "Eine riesige Forschungsstation auf einem schneebedeckten Lava-Planeten.",
        cameras = "🖥️ Security-Monitore (6 verschiedene Kamera-Ansichten) + Vitalfunktionen (Vitals).",
        dangerZones = "🌋 **Specimen Room** (extrem weit isoliert), 🧪 **Laboratory**.",
        tasks = "Viele manuelle Tasks (wie Temperatur regulieren oder Schlüssel drehen).",
        ventImage = "https://i.imgur.com/AzrZUEg.png",
    ),
    GameMap(
        name = "The Airship",
        description = "Die größte Map im Spiel. Sehr unübersichtlich mit Leitern und schwebenden Plattformen.",
        cameras = "🏢 Security + Tresorraum-Kameras.",
        dangerZones = "🏦 **Vault** (riesig und einsam), 🛋️ **Lounge**.",
        tasks = "Sehr lange Wege, Tasks dauern oft länger (z.B. Safe knacken oder Burger machen).",
        ventImage = "https://i.imgur.com/jVXKjCD.png",
    ),
    GameMap(
        name = "The Fungle",
        description = "Eine mysteriöse Pilz-Insel mit Strand, Dschungel und einer Klippe.",
        cameras = "🔭 Fernglas auf der Klippe (zeigt bestimmte Zonen der Map).",
        dangerZones = "🍄 **Spore-Felder** (Impostor können Pilze platzen lassen, um die Sicht komplett zu blockieren).",
        tasks = "Sehr verwinkelte Wege, einzigartige Tasks wie Angeln oder Marshmallows rösten.",
        ventImage = "https://i.imgur.com/4suuXye.png",
    ),
).associateByTo(LinkedHashMap()) { it.name }

// ==========================================
// SPASS & SONSTIGES
// ==========================================

val FUN_TEXTS = listOf("Ich esse gerne Kacke", "Ultra Firma", "Schere Diggi", "Upsipupsi")

data class SuspicionVerdict(val maxPercent: Int, val text: String)

val SUSPICION_VERDICTS: List<SuspicionVerdict> = listOf(
    SuspicionVerdict(
        24,
        "🟢 **Unschuldig wie ein Lamm.** Deine Augen funkeln voller Ehrlichkeit. Du wirst heute wahrscheinlich" +
            " als Erster gekillt, weil du zu brav bist.",
    ),
    SuspicionVerdict(
        49,
        "🟡 **Unauffällig.** Du machst brav deine Tasks und fällst im Meeting kaum auf. Ein solider Crewmate..." +
            " oder ein verdammt ruhiger Impostor?",
    ),
    SuspicionVerdict(
        74,
        "🟠 **Ziemlich SUS.** Du schwitzt jetzt schon beim Kabel verbinden und schaust dich verdächtig oft nach" +
            " Vents um. Die Crew behält dich im Auge!",
    ),
    SuspicionVerdict(
        100,
        "🔴 **ABSOLUTER IMPOSTOR!** Deine Augen zucken, dein Messer sitzt locker. Wenn du heute Electrical" +
            " betrittst, kommt niemand lebend wieder raus. 🔪",
    ),
)

fun verdictFor(percent: Int): SuspicionVerdict = SUSPICION_VERDICTS.first { percent <= it.maxPercent }
