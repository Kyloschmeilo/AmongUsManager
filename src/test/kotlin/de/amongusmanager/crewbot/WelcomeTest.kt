package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.ui.buildLeaveEmbed
import de.amongusmanager.crewbot.ui.buildWelcomeEmbed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WelcomeTest {
    @Test
    fun welcomeMentionsRolesAndHelp() {
        val embed = buildWelcomeEmbed("<@42>", null, 123, "</hilfe:1>")
        val text = embed.description ?: ""
        assertTrue("<@42>" in text && "<#${Channels.ROLES}>" in text && "</hilfe:1>" in text)
        assertEquals("Mitglied Nr. 123", embed.footer?.text)
    }

    @Test
    fun leaveEscapesNames() {
        val embed = buildLeaveEmbed("Mr_Steve*", null)
        assertTrue("Mr\\_Steve\\*" in (embed.description ?: ""))
        assertEquals("Mr_Steve*", embed.author?.name)
    }
}
