/* Anzeigen für Rollenwünsche (/rollenwunsch) und Admin-Zuweisungen (/rolle-zuweisen), siehe services/RoleWishes.kt. */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.services.RoleWish
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Kurze Erklärung, die bei /rollenwunsch angezeigt wird. */
val ROLE_WISH_RULES: String =
    "Ein Wunsch kostet **${Rules.ROLE_WISH_COST} XP**, höchstens **${Rules.ROLE_WISHES_PER_EVENING}× pro Spieleabend**" +
        " (bis ${Rules.GAME_NIGHT_END_HOUR} Uhr morgens). Er gilt für deine nächste Runde, wenn der Host die" +
        " CrewStats-Mod hat. Klappt er dort nicht (z.B. Rolle aus, zu wenige Spieler) oder spielst du an dem Abend" +
        " nicht mehr, bekommst du die XP zurück."

fun buildWishStatusEmbed(current: RoleWish?, reserved: Boolean, usedTonight: Int, balance: Int, linked: Boolean): MessageEmbed {
    val lines = ArrayList<String>()
    lines += when {
        current == null -> "Du hast gerade keinen offenen Rollenwunsch."
        current.isAdmin -> "🛡️ Ein Admin hat dir für die nächste Runde **${current.label}** zugewiesen."
        else -> "Offen: **${current.label}** für deine nächste Runde."
    }
    if (current != null && reserved) lines += "⏳ Wird gerade für eine startende Runde verwendet."
    if (!linked) lines += "⚠️ Dein Among-Us-Account ist noch nicht verknüpft – erst `/verknuepfen`, sonst erkennt die Mod dich nicht."
    lines += ""
    lines += "**Wünschen:** `/rollenwunsch rolle:…` – ändern ist kostenlos, zurückziehen gibt die XP zurück."
    lines += ROLE_WISH_RULES
    return EmbedBuilder()
        .setTitle("🎭 Dein Rollenwunsch")
        .setDescription(lines.joinToString("\n"))
        .setColor(Palette.PURPLE)
        .field("Heute benutzt", "$usedTonight/${Rules.ROLE_WISHES_PER_EVENING}")
        .field("Kosten", "${Rules.ROLE_WISH_COST} XP")
        .field("Dein Guthaben", "$balance XP")
        .build()
}

/** Übersicht für Admins: alle offenen Wünsche und Zuweisungen. */
fun buildWishOverviewEmbed(entries: List<RoleWish>, isReserved: (RoleWish) -> Boolean, evening: String): MessageEmbed {
    val lines = entries.sortedWith(compareBy<RoleWish> { !it.isAdmin }.thenBy { it.id }).map { entry ->
        val source = if (entry.isAdmin) "🛡️ von <@${entry.createdBy}>" else "🎟️ Wunsch (${entry.cost} XP)"
        val busy = if (isReserved(entry)) " ⏳" else ""
        "<@${entry.userId}> – **${entry.label}** · $source$busy"
    }
    val date = LocalDate.parse(evening).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    val embed = EmbedBuilder()
        .setTitle("🎭 Rollen für die nächste Runde")
        .setColor(Palette.PURPLE)
        .setDescription(fitLines(lines, limit = 4000, reserve = 40).ifEmpty { "Keine offenen Wünsche oder Zuweisungen." })
        .setFooter("Spieleabend vom $date • ⏳ = wird gerade angewendet • /rolle-zuweisen spieler rolle")
    return safeBuild(embed)
}

/** Warum ein Wunsch in der Runde nicht geklappt hat (Begründung der Mod). */
fun wishFailureText(reason: String?): String = when (reason) {
    "noSlot" -> "in der Runde war dafür kein Platz frei (z.B. zu wenige Spieler)"
    "disabled" -> "die Rolle ist in den Lobby-Einstellungen aus"
    else -> "die Mod konnte ihn nicht anwenden"
}
