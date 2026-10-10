/*
 * Referenzdaten des alten Python-Bots (src/test/resources/golden). Erzeugt mit dem Original-Code –
 * die Kotlin-Version muss exakt dieselben Ergebnisse liefern.
 */
package de.amongusmanager.crewbot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.fail

object Golden {
    val dir: Path = Path.of("src/test/resources/golden")

    val data: JsonObject by lazy {
        Json.parseToJsonElement(dir.resolve("golden.json").toFile().readText(Charsets.UTF_8)).jsonObject
    }

    fun section(name: String): JsonObject = data[name]!!.jsonObject
}

// ---------- JSON-Zugriffe ----------

val JsonElement.str: String get() = jsonPrimitive.content
val JsonElement.strOrNull: String? get() = if (this is JsonNull) null else jsonPrimitive.content
val JsonElement.int: Int get() = jsonPrimitive.int
val JsonElement.long: Long get() = jsonPrimitive.long
val JsonElement.bool: Boolean get() = jsonPrimitive.boolean
val JsonElement.arr: JsonArray get() = jsonArray
val JsonElement.obj: JsonObject get() = jsonObject

// ---------- Embeds vergleichen ----------

fun normEmbed(embed: MessageEmbed): JsonObject = buildJsonObject {
    put("title", embed.title)
    put("description", embed.description)
    put("color", if (embed.colorRaw == Role.DEFAULT_COLOR_RAW) null else embed.colorRaw)
    putJsonArray("fields") {
        for (field in embed.fields) {
            add(JsonArray(listOf(JsonPrimitive(field.name), JsonPrimitive(field.value), JsonPrimitive(field.isInline))))
        }
    }
    put("footer", embed.footer?.text)
    put("author", embed.author?.name)
    put("author_icon", embed.author?.iconUrl)
    put("thumbnail", embed.thumbnail?.url)
    put("image", embed.image?.url)
    put("timestamp", embed.timestamp?.toInstant()?.let { it.epochSecond + it.nano / 1e9 })
}

/** Vergleicht ein Kotlin-Embed mit dem Python-Ergebnis (optional ohne Zeitstempel). */
fun assertEmbed(expected: JsonElement, actual: MessageEmbed, context: String, ignoreTimestamp: Boolean = false) {
    val exp = expected.jsonObject
    val act = normEmbed(actual)
    for (key in listOf("title", "description", "color", "footer", "author", "author_icon", "thumbnail", "image")) {
        assertEquals(exp[key].normalized(), act[key].normalized(), "$context: Embed-Feld '$key' unterschiedlich")
    }
    val expFields = exp["fields"]!!.jsonArray
    val actFields = act["fields"]!!.jsonArray
    for (i in 0 until maxOf(expFields.size, actFields.size)) {
        assertEquals(expFields.getOrNull(i), actFields.getOrNull(i), "$context: Feld #$i unterschiedlich")
    }
    if (!ignoreTimestamp) {
        val e = exp["timestamp"]?.jsonPrimitive?.doubleOrNull
        val a = act["timestamp"]?.jsonPrimitive?.doubleOrNull
        if (e == null || a == null) {
            assertEquals(e, a, "$context: Zeitstempel unterschiedlich")
        } else if (abs(e - a) > 0.001) {
            fail("$context: Zeitstempel $a statt $e")
        }
    }
}

/** null und JsonNull gleich behandeln, Zahlen als Zahlen vergleichen. */
private fun JsonElement?.normalized(): Any? = when {
    this == null || this is JsonNull -> null
    this is JsonPrimitive && !isString && booleanOrNull == null -> double
    this is JsonPrimitive -> content
    else -> toString()
}
