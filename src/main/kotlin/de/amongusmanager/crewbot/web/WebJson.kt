/* Stats-Website: kleine Helfer, um Antworten der JSON-API zu bauen. */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.roundToLong

/** JSON-Objekt aus Paaren (Reihenfolge bleibt erhalten); Werte werden mit [toJson] umgewandelt. */
internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>(pairs.size * 2)
    for ((key, value) in pairs) map[key] = value.toJson()
    return JsonObject(map)
}

/** Zahlen werden auf 2 Nachkommastellen gerundet (hält die Antworten klein), NaN/∞ werden null. */
internal fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> if (isFinite()) JsonPrimitive(round2(this)) else JsonNull
    is Float -> toDouble().toJson()
    is Number -> JsonPrimitive(this)
    is Instant -> JsonPrimitive(toString())
    is Map<*, *> -> {
        val map = LinkedHashMap<String, JsonElement>(size * 2)
        for ((key, value) in this) map[key.toString()] = value.toJson()
        JsonObject(map)
    }
    is IntArray -> JsonArray(map { JsonPrimitive(it) })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

internal fun round2(value: Double): Double {
    val rounded = (value * 100).roundToLong() / 100.0
    return if (rounded == 0.0) 0.0 else rounded // keine "-0.0"
}

/** Kurzer, stabiler Schlüssel (12 Hex-Zeichen) – Friendcodes und Discord-IDs gehen nie an den Browser. */
internal fun shortHash(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}
