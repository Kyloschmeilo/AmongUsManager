package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.SPECIAL_ROLES
import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.tutorial
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.ui.buildTutorialEmbeds
import de.amongusmanager.crewbot.ui.tutorialCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** /tutorial: genannte Befehle gibt es, jedes Embed passt in Discords Grenzen und die Auswahl ist eindeutig. */
class TutorialTest {
    @Test
    fun threeTutorialsWithUniqueKeys() {
        assertEquals(listOf("befehle", "verknuepfen", "special-rollen", "special-rollen-liste"), TUTORIALS.map { it.key })
        assertEquals(TUTORIALS.size, TUTORIALS.map { it.label }.toSet().size)
        assertEquals("verknuepfen", tutorial("verknuepfen")?.key)
        assertNull(tutorial("gibt-es-nicht"))
    }

    @Test
    fun mentionedCommandsExist() {
        val commands = CrewBot(Database.inMemory()).modules.flatMap { it.commands }.map { it.name }.toSet()
        val mentioned = tutorialCommands()
        assertTrue("verknuepfen" in mentioned && "special-rolle" in mentioned)
        assertEquals(emptySet(), mentioned - commands, "Befehle in Tutorials, die es nicht gibt")
    }

    @Test
    fun embedsAreClickableAndFit() {
        for (tutorial in TUTORIALS) {
            val embeds = buildTutorialEmbeds(tutorial) { name -> "</$name:1>" }
            val text = embeds.joinToString("\n") { e -> (e.description ?: "") + e.fields.joinToString("\n") { it.value ?: "" } }
            assertTrue("</" in text, "${tutorial.key}: Befehle sind anklickbar")
            for (embed in embeds) assertTrue(embed.length <= 6000 && embed.fields.size <= 25, tutorial.key)
            assertEquals(tutorial.title, embeds.first().title)
            assertEquals(tutorial.footer, embeds.last().footer?.text)
            assertTrue(embeds.sumOf { it.fields.size } >= tutorial.sections.size, tutorial.key)
            val lines = tutorial.sections.sumOf { it.lines.size }
            assertEquals(lines, embeds.sumOf { e -> e.fields.sumOf { (it.value ?: "").lines().size } }, "${tutorial.key}: nichts abgeschnitten")
        }
    }

    @Test
    fun listExplainsEverySpecialRole() {
        val embeds = buildTutorialEmbeds(tutorial("special-rollen-liste")!!) { name -> "</$name:1>" }
        assertTrue(embeds.size > 1, "die Liste passt nicht in eine Nachricht")
        val text = embeds.joinToString("\n") { e -> e.fields.joinToString("\n") { it.value ?: "" } }
        for (role in SPECIAL_ROLES.values) assertTrue("**${role.name}** – ${role.description}" in text, role.name)
    }

    @Test
    fun linksStayLinks() {
        val embeds = buildTutorialEmbeds(tutorial("special-rollen")!!) { name -> "</$name:1>" }
        assertEquals(1, embeds.size)
        val text = embeds.single().fields.joinToString("\n") { it.value ?: "" }
        assertTrue("(https://github.com/GMIA-Nexus/TheOtherRolesGMIA)" in text)
        assertTrue("TheOtherRoles-Installer.bat" in text)
    }
}
