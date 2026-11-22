/*
 * Kritzelkiste-Zeichnung als PNG (für die Community-Galerie). Malt die gespeicherten Striche genauso wie die Seite
 * (seiten/kritzeln.html: `paint`/`fill`): runde Linien, Punkt bei nur einem Punkt, Farbeimer mit Toleranz 110 und
 * mitgefärbten Kanten, „u“ = letzten Strich zurücknehmen. Läuft headless (java.awt ohne Bildschirm).
 */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

object DrawingRender {
    const val W = DrawRules.WIDTH
    const val H = DrawRules.HEIGHT

    /** Gleiche Reihenfolge wie PALETTE in seiten/kritzeln.html (DrawGameTest prüft das). */
    val PALETTE = listOf(
        "#FFFFFF", "#C1C1C1", "#EF130B", "#FF7100", "#FFE400", "#00CC00", "#00B2FF", "#231FD3", "#A300BA", "#D37CAA", "#A0522D", "#FFC5A1",
        "#000000", "#4C4C4C", "#740B07", "#C23800", "#E8A200", "#005510", "#00569E", "#0E0865", "#550069", "#A75574", "#63300D", "#2BD67B",
    )

    /** Gleich wie SIZES in seiten/kritzeln.html. */
    val SIZES = listOf(3, 7, 13, 22, 36)

    /** Sichtbare Aktionen nach allen Rücknahmen (wie `addOp` der Seite). */
    fun visible(ops: List<JsonObject>): List<JsonObject> {
        val actions = ArrayList<JsonObject>()
        for (op in ops) {
            when (op.str("k")) {
                "u" -> {
                    val last = actions.lastOrNull() ?: continue
                    if (last.str("k") == "l") {
                        val id = last.int("i")
                        while (actions.isNotEmpty() && actions.last().str("k") == "l" && actions.last().int("i") == id) actions.removeAt(actions.size - 1)
                    } else {
                        actions.removeAt(actions.size - 1)
                    }
                }
                "l", "f" -> actions += op
            }
        }
        return actions
    }

    /** Hat die Zeichnung überhaupt etwas Sichtbares? */
    fun hasContent(ops: List<JsonObject>): Boolean = visible(ops).isNotEmpty()

    fun image(ops: List<JsonObject>): BufferedImage {
        val image = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, W, H)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            for (a in visible(ops)) {
                val color = Color.decode(PALETTE.getOrElse(a.int("c") ?: 12) { "#000000" })
                if (a.str("k") == "f") {
                    fill(image, (a.int("x") ?: 0).coerceIn(0, W - 1), (a.int("y") ?: 0).coerceIn(0, H - 1), color)
                    continue
                }
                val p = (a["p"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: continue
                if (p.size < 2) continue
                val width = SIZES.getOrElse(a.int("w") ?: 1) { 7 }.toFloat()
                g.color = color
                if (p.size == 2) {
                    g.fill(Ellipse2D.Float(p[0] - width / 2, p[1] - width / 2, width, width))
                    continue
                }
                g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val path = Path2D.Float()
                path.moveTo(p[0].toFloat(), p[1].toFloat())
                var i = 2
                while (i + 1 < p.size) {
                    path.lineTo(p[i].toFloat(), p[i + 1].toFloat())
                    i += 2
                }
                g.draw(path)
            }
        } finally {
            g.dispose()
        }
        return image
    }

    fun png(ops: List<JsonObject>): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image(ops), "png", out)
        return out.toByteArray()
    }

    /** Farbeimer wie in der Seite (Scanline, Toleranz 110, Kanten bis 420 mitfärben). */
    private fun fill(image: BufferedImage, x: Int, y: Int, color: Color) {
        val d = image.getRGB(0, 0, W, H, null, 0, W)
        val target = d[y * W + x]
        val tr = target shr 16 and 255
        val tg = target shr 8 and 255
        val tb = target and 255
        if (abs(tr - color.red) < 8 && abs(tg - color.green) < 8 && abs(tb - color.blue) < 8) return
        val rgb = color.rgb
        val seen = BooleanArray(W * H)
        fun diff(i: Int): Int { val v = d[i]; return abs((v shr 16 and 255) - tr) + abs((v shr 8 and 255) - tg) + abs((v and 255) - tb) }
        fun same(i: Int) = diff(i) <= 110
        val stack = ArrayDeque<Int>()
        stack.addLast(x); stack.addLast(y)
        while (stack.isNotEmpty()) {
            val py = stack.removeLast()
            var px = stack.removeLast()
            var i = py * W + px
            while (px >= 0 && !seen[i] && same(i)) { px--; i-- }
            px++; i++
            var up = false
            var down = false
            while (px < W && !seen[i] && same(i)) {
                seen[i] = true
                d[i] = rgb
                if (py > 0) { val k = i - W; if (!seen[k] && same(k)) { if (!up) { stack.addLast(px); stack.addLast(py - 1); up = true } } else up = false }
                if (py < H - 1) { val k = i + W; if (!seen[k] && same(k)) { if (!down) { stack.addLast(px); stack.addLast(py + 1); down = true } } else down = false }
                px++; i++
            }
        }
        for (k in 0 until W * H) {
            if (seen[k]) continue
            val kx = k % W
            if ((kx > 0 && seen[k - 1]) || (kx < W - 1 && seen[k + 1]) || (k >= W && seen[k - W]) || (k < W * (H - 1) && seen[k + W])) {
                if (diff(k) <= 420) d[k] = rgb
            }
        }
        image.setRGB(0, 0, W, H, d, 0, W)
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}
