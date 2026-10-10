/*
 * JSON-Helfer für die Daten der Mod und des Minecraft-Plugins.
 *
 * Die Umrechnungen verhalten sich wie im alten Python-Bot (`int(...)`, `float(...)`, `bool(...)`,
 * `str(...)`), damit dieselben Nachrichten genauso akzeptiert bzw. abgelehnt werden.
 */
package de.amongusmanager.crewbot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

val JSON = Json { ignoreUnknownKeys = true }

/** Ein Wert hat den falschen Typ (in Python: TypeError/ValueError). */
class JsonValueError(message: String) : Exception(message)

/** Ein Pflichtfeld fehlt (in Python: KeyError). */
class JsonKeyError(val key: String) : Exception("'$key'")

/** Liest JSON; wirft bei ungültigem Text eine Ausnahme mit Beschreibung. */
fun parseJson(text: String): JsonElement = JSON.parseToJsonElement(text)

fun parseJsonOrNull(text: String): JsonElement? =
    try {
        parseJson(text)
    } catch (e: Exception) {
        null
    }

private fun JsonPrimitive.isBool(): Boolean = !isString && booleanOrNull != null

/** Python `int(x)`. */
fun JsonElement?.pyInt(): Int = pyLong().let {
    if (it > Int.MAX_VALUE || it < Int.MIN_VALUE) throw JsonValueError("Zahl zu groß: $it")
    it.toInt()
}

/** Python `int(x)` für große Zahlen (Discord-IDs). */
fun JsonElement?.pyLong(): Long = when (this) {
    null, JsonNull -> throw JsonValueError("int() argument must be a string or a number, not 'NoneType'")
    is JsonPrimitive -> when {
        isString -> content.trim().toLongOrNull() ?: throw JsonValueError("invalid literal for int(): '$content'")
        isBool() -> if (booleanOrNull == true) 1L else 0L
        else -> content.toLongOrNull()
            ?: content.toBigDecimalOrNull()?.let { truncateToLong(it) }
            ?: throw JsonValueError("ungültige Zahl: $content")
    }
    is JsonObject -> throw JsonValueError("int() argument must be a string or a number, not 'dict'")
    is JsonArray -> throw JsonValueError("int() argument must be a string or a number, not 'list'")
}

private fun truncateToLong(value: BigDecimal): Long {
    val truncated = value.toBigInteger()
    if (truncated.bitLength() > 63) throw JsonValueError("Zahl zu groß: $value")
    return truncated.toLong()
}

/** Python `float(x)`. */
fun JsonElement?.pyFloat(): Double = when (this) {
    null, JsonNull -> throw JsonValueError("float() argument must be a string or a real number, not 'NoneType'")
    is JsonPrimitive -> when {
        isString -> content.trim().toDoubleOrNull() ?: throw JsonValueError("could not convert string to float: '$content'")
        isBool() -> if (booleanOrNull == true) 1.0 else 0.0
        else -> content.toDoubleOrNull() ?: throw JsonValueError("ungültige Zahl: $content")
    }
    else -> throw JsonValueError("float() argument must be a string or a real number")
}

/** Python `bool(x)` (Wahrheitswert). */
fun JsonElement?.pyTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        isBool() -> booleanOrNull == true
        else -> content.toDoubleOrNull()?.let { it != 0.0 } ?: true
    }
    is JsonObject -> isNotEmpty()
    is JsonArray -> isNotEmpty()
}

/** Python `str(x)`. */
fun JsonElement?.pyStr(): String = when (this) {
    null, JsonNull -> "None"
    is JsonPrimitive -> when {
        isString -> content
        isBool() -> if (booleanOrNull == true) "True" else "False"
        else -> content
    }
    else -> toString()
}

/** Ganzzahl oder null (für Felder wie `killedBy`, `body`, `target`, die fehlen oder null sein dürfen). */
fun JsonElement?.intOrNull(): Int? = if (this == null || this is JsonNull) null else pyInt()

/** `data.get(key, default)` mit anschließendem `str(...)`. */
fun JsonObject.getStr(key: String, default: String): String = if (containsKey(key)) this[key].pyStr() else default

/** `data.get(key) or default` mit `str(...)` – leere Werte zählen als nicht vorhanden. */
fun JsonObject.getStrOr(key: String, default: String): String =
    this[key].let { if (it.pyTruthy()) it.pyStr() else default }

/** `data[key]` – das Feld muss vorhanden sein. */
fun JsonObject.require(key: String): JsonElement = this[key] ?: throw JsonKeyError(key)

/** Text eines Werts für die Anzeige (Zahlen wie geschickt, Text ohne Anführungszeichen). */
fun JsonElement?.displayText(): String = pyStr()
