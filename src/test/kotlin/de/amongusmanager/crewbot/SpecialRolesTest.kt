package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.SpecialTeam
import de.amongusmanager.crewbot.ui.views.SpecialRoleSelect
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /special-rolle: 30 Mod-Rollen (+ Modifikatoren), Menüs innerhalb der Discord-Grenzen. */
class SpecialRolesTest {
    @Test
    fun thirtyRolesPlusModifiers() {
        val byTeam = SPECIAL_ROLES.values.groupingBy { it.team }.eachCount()
        assertEquals(30, SPECIAL_ROLES.values.count { it.team != SpecialTeam.MODIFIER })
        assertEquals(mapOf(SpecialTeam.CREW to 17, SpecialTeam.IMPOSTOR to 11, SpecialTeam.NEUTRAL to 2, SpecialTeam.MODIFIER to 1), byTeam)
    }

    @Test
    fun namesDoNotClashWithGameRoles() {
        assertTrue(SPECIAL_ROLES.keys.intersect(ROLES.keys).isEmpty())
    }

    @Test
    fun menusShowEveryRoleOnceWithinDiscordLimits() {
        val menus = SpecialRoleSelect.rows().map { it.components.single() as StringSelectMenu }
        assertTrue(menus.size <= 5)
        for (menu in menus) {
            assertTrue(menu.customId.startsWith(SpecialRoleSelect.PREFIX))
            assertTrue(menu.options.size in 1..StringSelectMenu.OPTIONS_MAX_AMOUNT)
        }
        assertEquals(SPECIAL_ROLES.keys.sorted(), menus.flatMap { m -> m.options.map { it.value } }.sorted())
    }

    @Test
    fun everyEmbedBuilds() {
        for (role in SPECIAL_ROLES.values) {
            val embed = SpecialRoleSelect.embed(role)
            assertEquals("${role.emoji} ${role.name}", embed.title)
        }
    }
}
