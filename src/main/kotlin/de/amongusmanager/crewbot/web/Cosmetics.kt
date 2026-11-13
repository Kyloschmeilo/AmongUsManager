/*
 * Kosmetik der Spieler (Hut, Visier, Skin, Haustier) für die Stats-Website.
 *
 * Die Bilder stammen aus den Spieldateien von Among Us (Addressables, ausgelesen mit tools/cosmetics/extract.py) und
 * liegen in src/main/resources/web-cosmetics/ (Index cosmetics.json: Datei, Größe, Pivot in Pixeln). Die Website setzt
 * daraus selbst ein Bild zusammen (Körper in der Spielerfarbe + Kosmetik) – wie im Spiel: alle Teile liegen im selben
 * Maßstab am Ursprung des Spielers, Hut und Visier 50 px höher, das Haustier links neben den Füßen.
 */
package de.amongusmanager.crewbot.web

import de.amongusmanager.crewbot.core.parseJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Was ein Spieler trägt; null/leer = nichts. Ids wie im Spiel (`hat_pk05_Cheese`). */
data class CrewLook(val hat: String?, val visor: String?, val skin: String?, val pet: String?) {
    fun normalized(): CrewLook = CrewLook(hat.clean(), visor.clean(), skin.clean(), pet.clean())

    val isEmpty: Boolean get() = hat == null && visor == null && skin == null && pet == null

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it !in NONE_IDS }

    companion object {
        /** So meldet das Spiel „nichts angezogen“. */
        val NONE_IDS = setOf("hat_NoHat", "visor_EmptyVisor", "skin_None", "pet_EmptyPet", "missing")
    }
}

object Cosmetics {
    const val FRAME_WIDTH = 360
    const val FRAME_HEIGHT = 340
    private const val ORIGIN_X = 220
    private const val ORIGIN_Y = 210
    private const val HEAD_X = -4
    private const val HEAD_Y = -50
    private const val PET_X = -120
    private const val PET_Y = 40

    /**
     * Spielfarben (Körper) und ihre Schattenfarben, ColorId 0–17 = Spiel, 18–41 = Zusatzfarben von The Other Roles GMIA
     * (`CustomColors.Load`, nötig ab 19 Spielern) – wie `PLAYER_COLORS` in web/src/lib/colors.ts.
     */
    private val BODY = intArrayOf(
        0xC51111, 0x132ED1, 0x117F2D, 0xED54BA, 0xEF7D0D, 0xF5F557, 0x3F474E, 0xD6E0F0, 0x6B2FBB,
        0x71491E, 0x38FEDC, 0x50EF39, 0x5F1D2E, 0xECC0D3, 0xF0E7A8, 0x758593, 0x918877, 0xD76464,
        0xEFBFC0, 0x6D071A, 0x9A8C3D, 0x1684B0, 0x6FC09C, 0xAD7EC9, 0xA06538, 0xFFA477, 0x708F2E, 0xFF3366, 0x006369,
        0xDBFD2F, 0xF74417, 0x25B8BF, 0x593CD6, 0xFFCA19, 0xA8DFFF, 0xA41181, 0x095221, 0xF4FFBC, 0x092B77, 0x242728,
        0x379FDA, 0xFCC264,
    )
    private val SHADOW = intArrayOf(
        0x7A0838, 0x09158E, 0x0A4D2E, 0xAC2BAE, 0xB43E15, 0xC38822, 0x1E1F26, 0x8495C0, 0x3B177C,
        0x5E2615, 0x24A8BE, 0x15A842, 0x410F1A, 0xDE92B3, 0xD2BC89, 0x465664, 0x51413E, 0xB44362,
        0xB67772, 0x36020B, 0x685F28, 0x0F5975, 0x41946F, 0x833ACB, 0x730F4E, 0xEE8064, 0x485C1D, 0xE8003A, 0x003D36,
        0x74E510, 0x9B2E0F, 0x128986, 0x291796, 0xDB4442, 0x599FC8, 0x68034F, 0x002E08, 0xA7EF70, 0x000D38, 0x0A0A0A,
        0x3E5C9E, 0xC56236,
    )
    private const val VISOR = 0x95CADC
    val colorCount: Int get() = BODY.size

    private class Part(val file: String, val px: Double, val py: Double)
    private class Item(val kind: String, val front: Part?, val back: Part?, val match: Boolean, val inFront: Boolean, val blocksVisors: Boolean, val behindHats: Boolean)

    private val items: Map<String, Item> by lazy { loadIndex() }

    private fun loadIndex(): Map<String, Item> {
        val text = Cosmetics::class.java.getResourceAsStream("/web-cosmetics/cosmetics.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return emptyMap()
        val root = parseJson(text) as JsonObject
        fun part(o: Any?): Part? {
            val p = o as? JsonObject ?: return null
            val file = (p["file"] as? JsonPrimitive)?.content ?: return null
            return Part(file, (p["px"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, (p["py"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
        }
        fun flag(o: JsonObject, name: String): Boolean {
            val v = o[name] as? JsonPrimitive ?: return false
            return v.booleanOrNull ?: ((v.intOrNull ?: 0) != 0)
        }
        return root.mapNotNull { (id, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            val kind = (o["kind"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            id to Item(
                kind = kind,
                front = part(o["front"]),
                back = part(o["back"]),
                match = flag(o, "match"),
                inFront = o["inFront"]?.let { flag(o, "inFront") } ?: true,
                blocksVisors = flag(o, "blocksVisors"),
                behindHats = flag(o, "behindHats"),
            )
        }.toMap()
    }

    /** Gibt es diese Kosmetik (der passenden Art) im Bilderbestand? */
    fun known(kind: String, id: String?): Boolean = id != null && items[id]?.kind == kind

    /** Nur Teile, für die es Bilder gibt (unbekannte, z.B. neuere als der Bestand, fallen weg). */
    fun drawable(look: CrewLook): CrewLook = CrewLook(
        look.hat.takeIf { known("hat", it) },
        look.visor.takeIf { known("visor", it) },
        look.skin.takeIf { known("skin", it) },
        look.pet.takeIf { known("pet", it) },
    )

    /** Adresse des zusammengesetzten Bilds. */
    fun url(colorId: Int, look: CrewLook): String {
        val l = drawable(look)
        val params = buildList {
            add("c=$colorId")
            l.hat?.let { add("h=${urlEncode(it)}") }
            l.visor?.let { add("v=${urlEncode(it)}") }
            l.skin?.let { add("s=${urlEncode(it)}") }
            l.pet?.let { add("p=${urlEncode(it)}") }
        }
        return "/api/crewmate?" + params.joinToString("&")
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /** Lesbarer Name aus der Id: `hat_pk05_Cheese` → „Cheese“, `skin_D2Titan` → „D2 Titan“. */
    fun displayName(id: String): String {
        var s = id.substringAfter('_', id)
        s = s.replace(Regex("^(pk\\d+|w\\d+|hw\\d+|[a-z]{1,3}\\d{2,4})_", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("(?<=[a-z0-9])(Skin|Hat|Visor|Pet|View)$"), "").ifEmpty { s }
        s = s.replace('_', ' ').replace('-', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z]{2})"), " ")
        return s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { id }
    }

    // ---------- Zeichnen ----------

    private val images = java.util.concurrent.ConcurrentHashMap<String, BufferedImage>()

    private fun image(file: String): BufferedImage? = images[file] ?: run {
        val img = Cosmetics::class.java.getResourceAsStream("/web-cosmetics/img/$file.png")?.use { ImageIO.read(it) } ?: return null
        val argb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        argb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        if (images.size < 400) images[file] = argb
        argb
    }

    /** Färbt wie der Spieler-Shader: Rot-Anteil = Körperfarbe, Blau = Schatten, Grün = Visier. */
    private fun recolor(src: BufferedImage, colorId: Int): BufferedImage {
        val body = BODY[colorId]
        val shadow = SHADOW[colorId]
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val p = src.getRGB(x, y)
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16 and 0xFF) / 255.0
            val g = (p shr 8 and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            fun ch(shift: Int): Int = (r * (body shr shift and 0xFF) + b * (shadow shr shift and 0xFF) + g * (VISOR shr shift and 0xFF)).toInt().coerceIn(0, 255)
            out.setRGB(x, y, (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
        }
        return out
    }

    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 300
    }

    /** PNG (FRAME_WIDTH × FRAME_HEIGHT), null = Farbe/Teile unbekannt oder kein Bilderbestand. */
    fun render(colorId: Int, look: CrewLook): ByteArray? {
        if (colorId !in BODY.indices) return null
        val l = look.normalized()
        if (drawable(l) != l) return null
        val key = "$colorId|${l.hat}|${l.visor}|${l.skin}|${l.pet}"
        synchronized(cache) { cache[key] }?.let { return it }
        val body = items["_body"]?.front ?: return null
        val canvas = BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        g.composite = AlphaComposite.SrcOver
        fun draw(part: Part?, dx: Int, dy: Int, match: Boolean) {
            part ?: return
            val img = image(part.file) ?: return
            val drawn = if (match) recolor(img, colorId) else img
            g.drawImage(drawn, Math.round(ORIGIN_X + dx - part.px).toInt(), Math.round(ORIGIN_Y + dy - part.py).toInt(), null)
        }
        val hat = l.hat?.let { items[it] }
        val visor = l.visor?.let { items[it] }?.takeIf { hat?.blocksVisors != true }
        val skin = l.skin?.let { items[it] }
        val pet = l.pet?.let { items[it] }

        draw(pet?.front, PET_X, PET_Y, false)
        draw(hat?.back, HEAD_X, HEAD_Y, hat?.match == true)
        if (hat != null && !hat.inFront) draw(hat.front, HEAD_X, HEAD_Y, hat.match)
        draw(body, 0, 0, true)
        draw(skin?.front, 0, 0, skin?.match == true)
        if (visor != null && visor.behindHats) draw(visor.front, HEAD_X, HEAD_Y, visor.match)
        if (hat != null && hat.inFront) draw(hat.front, HEAD_X, HEAD_Y, hat.match)
        if (visor != null && !visor.behindHats) draw(visor.front, HEAD_X, HEAD_Y, visor.match)
        g.dispose()

        val bytes = ByteArrayOutputStream().also { ImageIO.write(canvas, "png", it) }.toByteArray()
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }
}
