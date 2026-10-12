package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.RolePanelMessage
import de.amongusmanager.crewbot.services.RolePanelService
import de.amongusmanager.crewbot.services.RoleToggle
import de.amongusmanager.crewbot.services.missingAutoRoles
import de.amongusmanager.crewbot.services.planRoleToggle
import de.amongusmanager.crewbot.services.selfRoleGroupOf
import de.amongusmanager.crewbot.ui.views.RolePanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.components.buttons.Button
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rollen-Panel (/rollen-panel): Konfiguration, Klick-Logik, Buttons und gespeicherte Nachrichten. */
class RolePanelTest {
    private val clans = SelfRoleGroup(
        "clans", "Clans", "Such dir einen Clan aus",
        listOf(SelfRole("🏛️", "NOVA", 1), SelfRole("🪵", "SPP", 2), SelfRole("🐵", "MAC", 3)),
        exclusive = true,
    )

    @Test
    fun configIsValid() {
        val keys = SELF_ROLE_GROUPS.map { it.key }
        assertEquals(keys.distinct(), keys, "Gruppen-Keys doppelt")
        val ids = SELF_ROLE_GROUPS.flatMap { group -> group.roles.map { it.roleId } }
        assertEquals(ids.distinct(), ids, "Rolle doppelt im Panel")
        for (group in SELF_ROLE_GROUPS) {
            assertTrue(group.roles.size in 1..25, "${group.key}: 1 bis 25 Rollen")
            // baut ohne Fehler (JDA prüft Länge der Beschriftung und Emoji)
            RolePanel.components(group, group.roles.associate { it.roleId to 9999 })
        }
    }

    @Test
    fun gamesContainAllPingRolesAndLockdown() {
        val games = SELF_ROLE_GROUPS.first { it.key == "games" }.roles.map { it.roleId }.toSet()
        assertTrue(games.containsAll(GAME_PING_ROLES.values), "jede Event-Ping-Rolle ist im Panel")
        assertTrue(1556707082386808883L in games, "Lockdown Protocol")
        assertEquals(12, games.size)
    }

    @Test
    fun groupsInChannelOrder() {
        assertEquals(listOf("clans", "games", "minecraft"), SELF_ROLE_GROUPS.map { it.key })
        val clans = SELF_ROLE_GROUPS.first { it.key == "clans" }
        assertTrue(clans.exclusive, "nur ein Clan pro Person")
        assertEquals(9, clans.roles.size)
        assertEquals(listOf("PVP", "MCC"), SELF_ROLE_GROUPS.first { it.key == "minecraft" }.roles.map { it.name })
        // Buttons gleichmäßig auf möglichst wenige Reihen (max. 5 pro Reihe) verteilt
        assertEquals(listOf(5, 4), RolePanel.components(clans, emptyMap()).map { it.components.size })
        assertEquals(listOf(2), RolePanel.components(SELF_ROLE_GROUPS.last(), emptyMap()).map { it.components.size })
    }

    @Test
    fun autoRoles() {
        assertEquals(3, AUTO_ROLES.size)
        assertEquals(AUTO_ROLES.size, AUTO_ROLES.toSet().size, "keine doppelten Auto-Rollen")
        val panelIds = SELF_ROLE_GROUPS.flatMap { group -> group.roles.map { it.roleId } }.toSet()
        assertTrue(AUTO_ROLES.none { it in panelIds }, "Auto-Rollen gehören nicht ins Panel")
        val (a, b, c) = AUTO_ROLES
        assertEquals(listOf(a, b, c), missingAutoRoles(emptySet()))
        assertEquals(listOf(b, c), missingAutoRoles(setOf(a, 42)))
        assertEquals(emptyList(), missingAutoRoles(setOf(a, b, c)))
    }

    @Test
    fun toggleAddsAndRemoves() {
        val games = SELF_ROLE_GROUPS.first { it.key == "games" }
        val amongUs = GAME_PING_ROLES.getValue("Among Us")
        val risiko = GAME_PING_ROLES.getValue("Risiko")
        assertEquals(RoleToggle(add = amongUs, remove = emptyList()), planRoleToggle(games, setOf(risiko), amongUs))
        assertEquals(RoleToggle(add = null, remove = listOf(amongUs)), planRoleToggle(games, setOf(amongUs, risiko), amongUs))
    }

    @Test
    fun exclusiveGroupSwapsRole() {
        assertEquals(RoleToggle(add = 2, remove = listOf(1, 3)), planRoleToggle(clans, setOf(1, 3, 99), 2))
        assertEquals(RoleToggle(add = null, remove = listOf(2)), planRoleToggle(clans, setOf(2), 2))
        assertEquals(RoleToggle(add = 1, remove = emptyList()), planRoleToggle(clans, emptySet(), 1))
    }

    @Test
    fun groupLookup() {
        assertEquals("clans", selfRoleGroupOf(3, listOf(clans))?.key)
        assertEquals(null, selfRoleGroupOf(42, listOf(clans)))
        assertEquals("games", selfRoleGroupOf(GAME_PING_ROLES.getValue("PEAK"))?.key)
    }

    @Test
    fun buttonsShowCounts() {
        val games = SELF_ROLE_GROUPS.first { it.key == "games" }
        val rows = RolePanel.components(games, mapOf(GAME_PING_ROLES.getValue("Among Us") to 30))
        assertEquals(listOf(4, 4, 4), rows.map { it.components.size })
        val buttons = rows.flatMap { it.components }.map { it as Button }
        assertEquals("Among Us · 30", buttons[0].label)
        assertEquals("selfrole:${GAME_PING_ROLES.getValue("Among Us")}", buttons[0].customId)
        assertEquals("Risiko · 0", buttons[1].label, "fehlende Zahl = 0")
        assertEquals("🔫", buttons.last().emoji?.name)
        buttons.forEach { assertTrue(RolePanel.PATTERN.matches(it.customId!!)) }
    }

    @Test
    fun messagesAreStored() {
        val db = Database.inMemory()
        val service = RolePanelService(db, { error("kein Discord") }, RolePanel::components, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(emptyList(), service.messages())
        service.track(RolePanelMessage("games", 10, 100))
        service.track(RolePanelMessage("games", 11, 101))
        service.track(RolePanelMessage("games", 10, 100)) // doppelt -> nur einmal
        assertEquals(listOf(RolePanelMessage("games", 11, 101), RolePanelMessage("games", 10, 100)), service.messages())
        service.forget(101)
        assertEquals(listOf(RolePanelMessage("games", 10, 100)), service.messages())
        service.forget(100)
        assertEquals(null, db.getSetting(RolePanelService.SETTING_KEY))
    }
}
