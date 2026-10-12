/*
 * Auto-Rollen: Jedes Mitglied bekommt die Rollen aus `AUTO_ROLES` – beim Beitreten und beim Bot-Start
 * für alle, denen eine fehlt (z.B. Mitglieder von vor dem Feature). Bots bekommen sie nicht. Wer noch
 * die Server-Regeln bestätigen muss (Mitgliederprüfung, „pending“), bekommt sie erst danach.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.AUTO_ROLES
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.isForbidden
import de.amongusmanager.crewbot.core.isNotFound
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.slf4j.LoggerFactory

/** Auto-Rollen, die dem Mitglied noch fehlen. */
fun missingAutoRoles(memberRoleIds: Set<Long>, autoRoles: List<Long> = AUTO_ROLES): List<Long> =
    autoRoles.filter { it !in memberRoleIds }

class AutoRoleService(private val autoRoles: List<Long> = AUTO_ROLES) {
    private val log = LoggerFactory.getLogger(AutoRoleService::class.java)
    private val warnedMissing = HashSet<Long>()
    private var warnedForbidden = false

    /** Gibt [member] die fehlenden Auto-Rollen (eine Anfrage). true = es wurde etwas vergeben. */
    suspend fun apply(member: Member): Boolean {
        if (member.user.isBot || member.isPending) return false
        val guild = member.guild
        val roles = missingAutoRoles(member.roles.map { it.idLong }.toSet(), autoRoles).mapNotNull { roleOf(guild, it) }
        if (roles.isEmpty()) return false
        return try {
            guild.modifyMemberRoles(member, roles, emptyList()).reason("Auto-Rollen").await()
            true
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            when {
                e.isNotFound() -> Unit // inzwischen weg
                e.isForbidden() -> if (!warnedForbidden) {
                    warnedForbidden = true // nicht für jedes Mitglied einzeln
                    log.warn("Auto-Rollen: keine Rechte ({}) – Bot-Rolle über die Auto-Rollen schieben.", e.describe())
                }
                else -> log.warn("Auto-Rollen für {} fehlgeschlagen: {}", member.user.name, e.describe())
            }
            false
        }
    }

    /** Beim Start: alle Mitglieder prüfen (JDA drosselt die Anfragen selbst). */
    suspend fun syncAll(guild: Guild) {
        var given = 0
        for (member in guild.members) {
            if (apply(member)) given++
        }
        if (given > 0) log.info("🏷️ Auto-Rollen an {} Mitglieder vergeben.", given)
    }

    private fun roleOf(guild: Guild, id: Long) = guild.getRoleById(id).also {
        if (it == null && warnedMissing.add(id)) log.warn("Auto-Rolle {} gibt es auf dem Server nicht.", id)
    }
}
