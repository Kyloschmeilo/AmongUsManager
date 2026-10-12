/*
 * Rollen-Panel: Nachrichten mit einem Button je Rolle (Gruppen aus `SELF_ROLE_GROUPS`).
 * Ein Klick gibt die Rolle bzw. nimmt sie wieder weg; die Buttons zeigen, wie viele Mitglieder sie haben.
 *
 * Die geposteten Nachrichten merkt sich der Bot in `settings` (`rolepanel.messages`). Ändern sich Rollen
 * (Klick, Admin, Mitglied verlässt den Server), werden die Zahlen gebündelt nachgezogen – nur Nachrichten,
 * deren Zahlen sich wirklich geändert haben, werden bearbeitet.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.SELF_ROLE_GROUPS
import de.amongusmanager.crewbot.SelfRoleGroup
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.isNotFound
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import org.slf4j.LoggerFactory

/** Eine gepostete Panel-Nachricht. */
data class RolePanelMessage(val group: String, val channelId: Long, val messageId: Long)

/** Was ein Klick auf [clicked] ändert. */
data class RoleToggle(val add: Long?, val remove: List<Long>)

/**
 * Klick auf eine Rolle: hat man sie, wird sie abgegeben; sonst bekommt man sie –
 * bei einer exklusiven Gruppe fallen dabei die anderen Rollen der Gruppe weg.
 */
fun planRoleToggle(group: SelfRoleGroup, memberRoleIds: Set<Long>, clicked: Long): RoleToggle {
    if (clicked in memberRoleIds) return RoleToggle(add = null, remove = listOf(clicked))
    val others = if (group.exclusive) group.roles.map { it.roleId }.filter { it != clicked && it in memberRoleIds } else emptyList()
    return RoleToggle(add = clicked, remove = others)
}

/** Gruppe, zu der ein Rollen-Button gehört (null = steht nicht mehr im Panel). */
fun selfRoleGroupOf(roleId: Long, groups: List<SelfRoleGroup> = SELF_ROLE_GROUPS): SelfRoleGroup? =
    groups.firstOrNull { group -> group.roles.any { it.roleId == roleId } }

/** Baut die Buttons einer Gruppe; die Funktion bekommt die Mitgliederzahl je Rollen-ID. */
typealias RolePanelRenderer = (SelfRoleGroup, Map<Long, Int>) -> List<MessageTopLevelComponent>

class RolePanelService(
    private val db: Database,
    private val jda: () -> JDA,
    private val render: RolePanelRenderer,
    private val scope: CoroutineScope,
    private val groups: List<SelfRoleGroup> = SELF_ROLE_GROUPS,
    private val refreshDelayMillis: Long = 2_000,
) {
    private val log = LoggerFactory.getLogger(RolePanelService::class.java)
    private val refreshLock = Mutex()
    private var refreshScheduled = false

    /** Zuletzt angezeigte Zahlen je Nachricht – unveränderte Nachrichten werden nicht bearbeitet. */
    private val shownCounts = HashMap<Long, Map<Long, Int>>()

    val roleIds: Set<Long> get() = groups.flatMap { group -> group.roles.map { it.roleId } }.toSet()

    fun group(key: String): SelfRoleGroup? = groups.firstOrNull { it.key == key }

    // ---------- Gespeicherte Nachrichten ----------

    fun messages(): List<RolePanelMessage> =
        db.getSetting(SETTING_KEY).orEmpty().split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val channelId = parts[1].toLongOrNull() ?: return@mapNotNull null
            val messageId = parts[2].toLongOrNull() ?: return@mapNotNull null
            RolePanelMessage(parts[0], channelId, messageId)
        }

    fun track(message: RolePanelMessage) {
        save(messages().filter { it.messageId != message.messageId } + message)
    }

    fun forget(messageId: Long) {
        shownCounts.remove(messageId)
        save(messages().filter { it.messageId != messageId })
    }

    /** Zahlen, die gerade für [messageId] angezeigt werden (nach dem Posten nicht nochmal bearbeiten). */
    fun markShown(messageId: Long, counts: Map<Long, Int>) {
        shownCounts[messageId] = counts
    }

    private fun save(list: List<RolePanelMessage>) {
        db.setSetting(
            SETTING_KEY,
            list.joinToString(";") { "${it.group}:${it.channelId}:${it.messageId}" }.ifEmpty { null },
        )
    }

    // ---------- Zahlen ----------

    /** Mitglieder je Rolle der Gruppe (aus dem Mitglieder-Cache – der Bot lädt beim Start alle Mitglieder). */
    fun counts(guild: Guild, group: SelfRoleGroup): Map<Long, Int> =
        group.roles.associate { role ->
            role.roleId to (guild.getRoleById(role.roleId)?.let { guild.getMembersWithRoles(it).size } ?: 0)
        }

    /** Zahlen bald neu anzeigen; mehrere Änderungen kurz hintereinander ergeben nur eine Aktualisierung. */
    fun requestRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        scope.launch {
            delay(refreshDelayMillis)
            refreshScheduled = false
            refreshAll()
        }
    }

    suspend fun refreshAll() = refreshLock.withLock {
        for (panel in messages()) {
            val group = group(panel.group)
            if (group == null) {
                log.info("Rollen-Panel {}: Gruppe '{}' gibt es nicht mehr – vergessen.", panel.messageId, panel.group)
                forget(panel.messageId)
                continue
            }
            val channel = jda().getChannelById(GuildMessageChannel::class.java, panel.channelId)
            if (channel == null) {
                log.warn("Rollen-Panel {}: Channel {} nicht gefunden.", panel.messageId, panel.channelId)
                continue
            }
            val counts = counts(channel.guild, group)
            if (shownCounts[panel.messageId] == counts) continue
            try {
                channel.editMessageComponentsById(panel.messageId, render(group, counts)).await()
                shownCounts[panel.messageId] = counts
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                if (e.isNotFound()) {
                    log.info("Rollen-Panel {} wurde gelöscht – vergessen.", panel.messageId)
                    forget(panel.messageId)
                } else {
                    log.warn("Rollen-Panel {} konnte nicht aktualisiert werden: {}", panel.messageId, e.describe())
                }
            }
        }
    }

    companion object {
        const val SETTING_KEY = "rolepanel.messages"
    }
}
