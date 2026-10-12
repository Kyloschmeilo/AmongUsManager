package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.db.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Slash-Commands sind für Discord identisch zum alten Bot (Namen, Texte, Optionen, Auswahllisten, Rechte).
 * Einziger Unterschied: Die Befehle erscheinen nur noch auf dem Server (nicht in DMs) – der alte Bot hat sie dort
 * ohnehin mit „funktioniert nur auf dem Server“ abgelehnt.
 * Neue Befehle, die es im alten Bot nicht gab, stehen in [NEW_IN_KOTLIN] und werden beim Vergleich übersprungen.
 */
class CommandsGoldenTest {
    private val commands = Golden.section("commands")
    private val bot = CrewBot(Database.inMemory())

    private val NEW_IN_KOTLIN = setOf("rollen-panel", "rollenwunsch", "rolle-zuweisen")

    @Test
    fun modulesAndOrderMatchHelp() {
        val expected = commands["cogs"]!!.arr.map { cog ->
            listOf(cog.arr[0].str, cog.arr[1].str, cog.arr[2].arr.map { it.str }.toString())
        }
        val actual = bot.modules.map { m -> listOf(m.name, m.emoji, m.commands.map { it.name }.filter { it !in NEW_IN_KOTLIN }.toString()) }
        assertEquals(expected, actual)
    }

    @Test
    fun commandPayloadsMatch() {
        val expected = commands["payloads"]!!.arr.associateBy { it.obj["name"]!!.str }
        val actual = bot.modules.flatMap { it.commands }.filter { it.name !in NEW_IN_KOTLIN }.associate { command ->
            command.name to Json.parseToJsonElement(command.data().toData().toString()).jsonObject
        }
        assertEquals(expected.keys, actual.keys)
        for ((name, exp) in expected) {
            val act = actual.getValue(name)
            val e = exp.obj
            assertEquals(e["description"]!!.str, act["description"]!!.str, "/$name Beschreibung")
            assertEquals(e["type"]!!.int, act["type"]!!.int, "/$name Typ")
            assertEquals(
                e["default_member_permissions"].numberOrNull(),
                act["default_member_permissions"].numberOrNull(),
                "/$name Rechte",
            )
            assertEquals(listOf(0), act["contexts"]!!.arr.map { it.int }, "/$name nur auf dem Server")
            assertEquals(normOptions(e["options"]), normOptions(act["options"]), "/$name Optionen")
        }
    }

    private fun JsonElement?.numberOrNull(): Long? =
        if (this == null || this is JsonNull) null else (this as JsonPrimitive).content.toLong()

    /** Nur die Felder, die Discord auswertet; fehlende Felder = Standardwerte. */
    private fun normOptions(options: JsonElement?): List<Map<String, String?>> =
        (options as? JsonArray ?: JsonArray(emptyList())).map { option ->
            val o = option.jsonObject
            mapOf(
                "type" to o["type"]?.str,
                "name" to o["name"]?.str,
                "description" to o["description"]?.str,
                "required" to (o["required"]?.bool ?: false).toString(),
                "choices" to (o["choices"] as? JsonArray)?.map { c -> c.obj["name"]!!.str to c.obj["value"]!!.str }?.toString(),
                "min_value" to o.number("min_value"),
                "max_value" to o.number("max_value"),
                "min_length" to o.number("min_length"),
                "max_length" to o.number("max_length"),
                "channel_types" to (o["channel_types"] as? JsonArray)?.map { it.int }?.sorted()?.toString(),
                "autocomplete" to (o["autocomplete"]?.bool ?: false).toString(),
            )
        }

    private fun JsonObject.number(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.let { (it as JsonPrimitive).content.toDouble().toLong().toString() }
}
