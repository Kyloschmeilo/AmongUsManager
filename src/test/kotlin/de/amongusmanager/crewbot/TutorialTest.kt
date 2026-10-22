package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.TUTORIALS
import de.amongusmanager.crewbot.content.tutorial
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.ui.buildTutorialEmbed
import de.amongusmanager.crewbot.ui.tutorialCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** /tutorial: genannte Befehle gibt es, jedes Embed passt in Discords Grenzen und die Auswahl ist eindeutig. */
class TutorialTest {
    @Test
    fun threeTutorialsWithUniqueKeys() {
        assertEquals(listOf("befehle", "verknuepfen", "special-rollen"), TUTORIALS.map { it.key })
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
            val embed = buildTutorialEmbed(tutorial) { name -> "</$name:1>" }
            val text = (embed.description ?: "") + embed.fields.joinToString("\n") { it.value ?: "" }
            assertTrue("</" in text, "${tutorial.key}: Befehle sind anklickbar")
            assertTrue(embed.length <= 6000 && embed.fields.size == tutorial.sections.size, tutorial.key)
            assertTrue(embed.fields.none { (it.value ?: "").contains("… und") }, "${tutorial.key}: nichts abgeschnitten")
        }
    }

    @Test
    fun linksStayLinks() {
        val embed = buildTutorialEmbed(tutorial("special-rollen")!!) { name -> "</$name:1>" }
        val text = embed.fields.joinToString("\n") { it.value ?: "" }
        assertTrue("(https://github.com/Kyloschmeilo/SpecialRoles/releases)" in text)
    }
}
