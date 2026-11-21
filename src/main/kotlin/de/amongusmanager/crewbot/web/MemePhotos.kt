/*
 * Echte Meme-Vorlagen der Meme-Schmiede (Bilder + Textfeld-Positionen), in Paketen.
 *
 * Quelle: die Vorlagen-Sammlung von memegen (github.com/jacebrowning/memegen, Ordner templates/): je Vorlage ein Bild und
 * die Lage der Textfelder (Anteile 0–1, ggf. gedreht, Farbe). `tools/memes/build_memes.py` sucht sie aus, verkleinert die
 * Bilder und schreibt `src/main/resources/web-memes/` (vorlagen.json + <id>.jpg). Nur im Website-JAR.
 * Die Website liefert Liste und Bilder selbst aus (`/api/memes/vorlagen`, `/api/memes/bild/<id>.jpg`) – die Browser fragen
 * nirgends sonst an.
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MemePhotos {
    class Pack(val id: String, val name: String, val desc: String)
    class Photo(val id: String, val pack: String, val slots: Int)

    private const val DIR = "/web-memes/"
    private val ID = Regex("[a-z0-9-]{1,40}")

    /** vorlagen.json unverändert (geht so an den Browser). */
    val json: ByteArray by lazy { MemePhotos::class.java.getResourceAsStream(DIR + "vorlagen.json")?.use { it.readBytes() } ?: ByteArray(0) }

    private val parsed: Pair<List<Pack>, List<Photo>> by lazy {
        if (json.isEmpty()) return@lazy emptyList<Pack>() to emptyList()
        val root = Json.parseToJsonElement(json.toString(Charsets.UTF_8)).jsonObject
        val packs = root["packs"]!!.jsonArray.map { it.jsonObject }.map { Pack(it.s("id"), it.s("name"), it.s("desc")) }
        val photos = root["templates"]!!.jsonArray.map { it.jsonObject }.map { t ->
            Photo(t.s("id"), t.s("pack"), t["boxes"]!!.jsonArray.size)
        }
        packs to photos
    }

    val PACKS: List<Pack> get() = parsed.first
    val ALL: List<Photo> get() = parsed.second
    val BY_ID: Map<String, Photo> by lazy { ALL.associateBy { it.id } }

    fun count(pack: String): Int = ALL.count { it.pack == pack }

    /** Bild einer Vorlage (nur bekannte ids, sonst null). */
    fun image(id: String): ByteArray? {
        if (!ID.matches(id) || id !in BY_ID) return null
        return MemePhotos::class.java.getResourceAsStream("$DIR$id.jpg")?.use { it.readBytes() }
    }

    private fun JsonObject.s(name: String) = this[name]!!.jsonPrimitive.content
}
