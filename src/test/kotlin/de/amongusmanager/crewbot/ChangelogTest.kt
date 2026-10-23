package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.Changelog
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.ui.buildChangelogEmbed
import de.amongusmanager.crewbot.ui.changelogCommands
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /changelog wird bei jedem Update neu geschrieben – diese Tests fangen vergessene Version und Tippfehler ab. */
class ChangelogTest {
    @Test
    fun versionMatchesBuild() {
        val build = File("build.gradle.kts").readText()
        val version = Regex("""(?m)^version = "([^"]+)"""").find(build)?.groupValues?.get(1)
        assertEquals(version, Changelog.VERSION, "Changelog.VERSION = version in build.gradle.kts")
    }

    @Test
    fun mentionedCommandsExist() {
        val commands = CrewBot(Database.inMemory()).modules.flatMap { it.commands }.map { it.name }.toSet()
        val mentioned = changelogCommands()
        assertTrue(mentioned.isNotEmpty())
        assertEquals(emptySet(), mentioned - commands, "Befehle im Changelog, die es nicht gibt")
    }

    @Test
    fun embedIsClickableAndFits() {
        val embed = buildChangelogEmbed { name -> "</$name:1>" }
        val text = embed.fields.joinToString("\n") { it.value ?: "" }
        assertTrue("</special-rolle:1>" in text && "</verknuepfen:1>" in text)
        assertTrue(embed.length <= 6000 && embed.fields.size == Changelog.sections.size)
    }
}
