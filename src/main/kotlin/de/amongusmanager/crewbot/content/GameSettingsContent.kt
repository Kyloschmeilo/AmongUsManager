/*
 * Among-Us-Lobbyeinstellungen: deutsche Namen, Einheiten und Kategorien für /rundensettings.
 *
 * Die Schlüssel sind die Options-Namen aus dem Spiel (Int32/Float/BoolOptionNames bzw. RoleTypes),
 * so wie die CrewStats-Mod sie schickt. Unbekannte (neue) Optionen zeigt der Bot roh unter „Weitere“.
 */
package de.amongusmanager.crewbot.content

import de.amongusmanager.crewbot.core.pyFloat
import de.amongusmanager.crewbot.core.pyInt
import de.amongusmanager.crewbot.core.pyStr
import de.amongusmanager.crewbot.core.pyTruthy
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// ==========================================
// FORMATIERUNG
// ==========================================

/** Python `f"{value:g}"`: 6 signifikante Stellen, ohne überflüssige Nullen. */
fun formatG(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
    val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN))
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent in -4 until 6) return rounded.stripTrailingZeros().toPlainString()
    val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
    val sign = if (exponent < 0) "-" else "+"
    return "${mantissa}e$sign${kotlin.math.abs(exponent).toString().padStart(2, '0')}"
}

private fun number(value: JsonElement): String = formatG(value.pyFloat()).replace(".", ",")

typealias SettingFormat = (JsonElement) -> String

val SECONDS: SettingFormat = { "${number(it)} s" }
val MULTIPLIER: SettingFormat = { "${number(it)}x" }
val PLAIN: SettingFormat = { number(it) }
val PERCENT: SettingFormat = { "${number(it)} %" }
val ON_OFF: SettingFormat = { if (it.pyTruthy()) "✅ An" else "❌ Aus" }

fun choice(options: Map<Int, String>): SettingFormat = { options[it.pyInt()] ?: it.pyStr() }

data class Setting(
    val kind: String, // ints | floats | bools
    val key: String,
    val label: String,
    val format: SettingFormat = PLAIN,
)

// ==========================================
// KATEGORIEN
// ==========================================

data class Category(val key: String, val label: String, val emoji: String, val settings: List<Setting> = emptyList())

val GENERAL = Category(
    "general", "Allgemein", "🏠",
    listOf(
        Setting("floats", "KillCooldown", "Kill-Cooldown", SECONDS),
        Setting("ints", "KillDistance", "Kill-Distanz", choice(mapOf(0 to "Kurz", 1 to "Mittel", 2 to "Lang"))),
        Setting("floats", "PlayerSpeedMod", "Laufgeschwindigkeit", MULTIPLIER),
        Setting("floats", "CrewLightMod", "Crew-Sicht", MULTIPLIER),
        Setting("floats", "ImpostorLightMod", "Impostor-Sicht", MULTIPLIER),
        Setting("bools", "ConfirmImpostor", "Rauswurf bestätigen", ON_OFF),
    ),
)
val MEETINGS = Category(
    "meetings", "Meetings", "📢",
    listOf(
        Setting("ints", "NumEmergencyMeetings", "Notfall-Meetings pro Spieler"),
        Setting("ints", "EmergencyCooldown", "Notfall-Cooldown", SECONDS),
        Setting("ints", "DiscussionTime", "Diskussionszeit", SECONDS),
        Setting("ints", "VotingTime", "Abstimmungszeit", SECONDS),
        Setting("bools", "AnonymousVotes", "Anonyme Stimmen", ON_OFF),
    ),
)
val TASKS = Category(
    "tasks", "Aufgaben", "🧰",
    listOf(
        Setting("ints", "TaskBarMode", "Taskleiste", choice(mapOf(0 to "Immer", 1 to "Nur bei Meetings", 2 to "Nie"))),
        Setting("bools", "VisualTasks", "Sichtbare Tasks", ON_OFF),
        Setting("ints", "NumCommonTasks", "Gemeinsame Tasks"),
        Setting("ints", "NumLongTasks", "Lange Tasks"),
        Setting("ints", "NumShortTasks", "Kurze Tasks"),
        Setting("bools", "GhostsDoTasks", "Geister machen Tasks", ON_OFF),
    ),
)
val ROLES_CATEGORY = Category("roles", "Rollen", "🎭")

val CATEGORIES: List<Category> = listOf(GENERAL, MEETINGS, TASKS, ROLES_CATEGORY)

// ==========================================
// ROLLEN
// ==========================================

data class RoleSettings(
    val gameName: String, // RoleTypes aus dem Spiel
    val name: String, // Anzeigename (wie in /rolle)
    val impostor: Boolean,
    val settings: List<Setting>,
)

val ROLE_SETTINGS: List<RoleSettings> = listOf(
    RoleSettings(
        "Engineer", "Engineer", false,
        listOf(
            Setting("floats", "EngineerCooldown", "Vent-Cooldown", SECONDS),
            Setting("floats", "EngineerInVentMaxTime", "Max. Zeit im Vent", SECONDS),
        ),
    ),
    RoleSettings(
        "Scientist", "Scientist", false,
        listOf(
            Setting("floats", "ScientistCooldown", "Vitals-Cooldown", SECONDS),
            Setting("floats", "ScientistBatteryCharge", "Batteriedauer", SECONDS),
        ),
    ),
    RoleSettings(
        "GuardianAngel", "Guardian Angel", false,
        listOf(
            Setting("floats", "GuardianAngelCooldown", "Schutz-Cooldown", SECONDS),
            Setting("floats", "ProtectionDurationSeconds", "Schutzdauer", SECONDS),
            Setting("bools", "ImpostorsCanSeeProtect", "Impostor sehen Schutz", ON_OFF),
        ),
    ),
    RoleSettings(
        "Tracker", "Tracker", false,
        listOf(
            Setting("floats", "TrackerCooldown", "Cooldown", SECONDS),
            Setting("floats", "TrackerDuration", "Dauer", SECONDS),
            Setting("floats", "TrackerDelay", "Verzögerung", SECONDS),
        ),
    ),
    RoleSettings(
        "Noisemaker", "Noisemaker", false,
        listOf(
            Setting("floats", "NoisemakerAlertDuration", "Alarmdauer", SECONDS),
            Setting("bools", "NoisemakerImpostorAlert", "Impostor bekommen Alarm", ON_OFF),
        ),
    ),
    RoleSettings(
        "Detective", "Detective", false,
        listOf(Setting("floats", "DetectiveSuspectLimit", "Verdächtigen-Limit")),
    ),
    RoleSettings(
        "Judge", "Judge", false,
        listOf(Setting("floats", "JudgeTaskRequirementPercentage", "Nötige Tasks", PERCENT)),
    ),
    // Im Spiel intern „SpiritGuide“ – im Spiel und in /rolle heißt die Rolle Influencer
    RoleSettings(
        "SpiritGuide", "Influencer", false,
        listOf(Setting("floats", "SpiritGuideCooldownSeconds", "Cooldown", SECONDS)),
    ),
    RoleSettings(
        "Shapeshifter", "Shapeshifter", true,
        listOf(
            Setting("floats", "ShapeshifterCooldown", "Cooldown", SECONDS),
            Setting("floats", "ShapeshifterDuration", "Dauer", SECONDS),
            Setting("bools", "ShapeshifterLeaveSkin", "Hinterlässt Beweise", ON_OFF),
        ),
    ),
    RoleSettings(
        "Phantom", "Phantom", true,
        listOf(
            Setting("floats", "PhantomCooldown", "Cooldown", SECONDS),
            Setting("floats", "PhantomDuration", "Unsichtbar-Dauer", SECONDS),
        ),
    ),
    RoleSettings(
        "Viper", "Viper", true,
        listOf(Setting("floats", "ViperDissolveTime", "Auflösezeit", SECONDS)),
    ),
)

/** Optionen, die zu Hide & Seek gehören oder nur intern sind – werden nie angezeigt. */
val IGNORED_OPTIONS: Set<String> = setOf(
    "NumImpostors", "MaxImpostors", "MinPlayers", "MaxPlayers", "Tag", "ImpostorPlayerID", "RulePreset",
    "CrewmatesRemainingForVitals", "CrewmateVentUses", "CrewmateTimeInVent", "FinalEscapeTime", "EscapeTime",
    "SeekerFinalSpeed", "MaxPingTime", "CrewmateFlashlightSize", "ImpostorFlashlightSize", "UseFlashlight",
    "SeekerFinalVents", "SeekerFinalMap", "SeekerPings", "ShowCrewmateNames", "IsDefaults", "Roles",
)

fun knownOptionKeys(): Set<String> {
    val keys = HashSet<String>()
    CATEGORIES.forEach { category -> category.settings.forEach { keys.add(it.key) } }
    ROLE_SETTINGS.forEach { role -> role.settings.forEach { keys.add(it.key) } }
    return keys + IGNORED_OPTIONS
}
