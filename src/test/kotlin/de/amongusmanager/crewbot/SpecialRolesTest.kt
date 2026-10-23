package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.SpecialTeam
import de.amongusmanager.crewbot.ui.views.SpecialRoleSelect
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.MessageEmbed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /special-rolle: Rollen aus The Other Roles (GMIA), Menüs innerhalb der Discord-Grenzen. */
class SpecialRolesTest {
    @Test
    fun allTeamsOfTheOtherRoles() {
        val byTeam = SPECIAL_ROLES.values.groupingBy { it.team }.eachCount()
        assertEquals(
            mapOf(SpecialTeam.CREW to 29, SpecialTeam.BOTH to 4, SpecialTeam.IMPOSTOR to 24, SpecialTeam.NEUTRAL to 22, SpecialTeam.MODIFIER to 13),
            byTeam,
        )
        assertEquals(SPECIAL_ROLES.size, SPECIAL_ROLES.values.map { it.name }.toSet().size, "Namen eindeutig")
        assertEquals(SPECIAL_ROLES.size, SPECIAL_ROLES.values.map { it.emoji }.toSet().size, "Emojis eindeutig")
        for (role in SPECIAL_ROLES.values) assertTrue(role.description.isNotBlank() && role.description.last() in ".!", role.name)
    }

    @Test
    fun menusShowEveryRoleOnceWithinDiscordLimits() {
        val menus = SpecialRoleSelect.rows().map { it.components.single() as StringSelectMenu }
        assertTrue(menus.size <= 5)
        assertEquals(menus.size, menus.map { it.customId }.toSet().size)
        for (menu in menus) {
            assertTrue(menu.customId.startsWith(SpecialRoleSelect.PREFIX))
            assertTrue(menu.options.size in 1..StringSelectMenu.OPTIONS_MAX_AMOUNT)
            assertTrue(menu.placeholder!!.length <= StringSelectMenu.PLACEHOLDER_MAX_LENGTH, menu.placeholder)
            for (option in menu.options) assertTrue(option.label.length <= SelectOption.LABEL_MAX_LENGTH)
        }
        assertEquals(SPECIAL_ROLES.keys.sorted(), menus.flatMap { m -> m.options.map { it.value } }.sorted())
    }

    @Test
    fun everyEmbedBuilds() {
        for (role in SPECIAL_ROLES.values) {
            val embed = SpecialRoleSelect.embed(role)
            assertEquals("${role.emoji} ${role.name}", embed.title)
            assertTrue((embed.description?.length ?: 0) <= MessageEmbed.DESCRIPTION_MAX_LENGTH)
        }
    }
}
