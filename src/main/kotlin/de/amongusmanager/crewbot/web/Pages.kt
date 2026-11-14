/*
 * Eigene Seiten: Dateien aus dem Ordner seiten/ neben dem JAR, ausgeliefert unter /seiten/<datei> und im Browser
 * angezeigt (nicht als Download wie downloads/). Gedacht für einzelne, selbstständige HTML-Dateien wie schach.html,
 * die ihre Bibliotheken von CDNs laden und fremde APIs fragen – dafür gilt hier eine eigene, lockerere CSP.
 * Die Seiten laufen unter derselben Adresse wie die Stats-Website (gleiche Anmeldung): nur eigene Dateien ablegen.
 */
package de.amongusmanager.crewbot.web

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class Pages(val dir: Path) {
    /** Datei direkt im Ordner (keine Unterordner, keine versteckten, keine Links nach draußen, nur bekannte Endungen), sonst null. */
    fun file(name: String): Path? {
        if (!offered(name) || !Files.isDirectory(dir)) return null
        val root = dir.toRealPath()
        val file = root.resolve(name).normalize()
        if (file.parent != root || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(file) > MAX_BYTES) return null
        return file
    }

    /** Namen aller ausgelieferten Dateien, alphabetisch. */
    fun files(): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.toList().map { it.fileName.toString() }.filter { file(it) != null }.sortedBy { it.lowercase() }
        }
    }

    /** Titel und Beschreibung einer HTML-Seite (aus <title> und <meta name="description">) – für die Kacheln der Startseite. */
    class Info(val file: String, val title: String?, val description: String?)

    /** Alle HTML-Seiten mit Titel/Beschreibung, alphabetisch. */
    fun htmlPages(): List<Info> = files().filter { it.endsWith(".html") || it.endsWith(".htm") }.map { name ->
        val head = runCatching {
            Files.newInputStream(file(name) ?: return@runCatching "").use { String(it.readNBytes(HEAD_BYTES), Charsets.UTF_8) }
        }.getOrDefault("")
        Info(name, headText(head, TITLE), headText(head, DESCRIPTION))
    }

    companion object {
        private const val HEAD_BYTES = 64 * 1024
        private val TITLE = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        private val DESCRIPTION = Regex("<meta\\s+name=[\"']description[\"']\\s+content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

        /** Text aus dem Kopf einer Seite, Entities aufgelöst, Leerraum zusammengefasst, höchstens 160 Zeichen. */
        internal fun headText(head: String, pattern: Regex): String? {
            val raw = pattern.find(head)?.groupValues?.get(1) ?: return null
            val text = raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
            return text.take(160).ifEmpty { null }
        }


        const val PREFIX = "/seiten/"
        const val MAX_BYTES = 20L * 1024 * 1024

        /** Skripte/Stile/Bilder/Anfragen auch von fremden https-Adressen, Skripte in der Seite, Web Worker aus Blobs, WebAssembly. */
        const val CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' blob: https:; worker-src 'self' blob:; " +
            "style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; font-src 'self' data: https:; " +
            "connect-src 'self' https: wss:; media-src 'self' data: blob: https:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

        private val TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8", "js" to "application/javascript; charset=utf-8", "mjs" to "application/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8", "txt" to "text/plain; charset=utf-8",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
            "gif" to "image/gif", "ico" to "image/x-icon", "woff2" to "font/woff2", "woff" to "font/woff", "wasm" to "application/wasm",
        )

        fun contentType(name: String): String? = TYPES[name.substringAfterLast('.', "").lowercase()]

        fun offered(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith(".") && ".." !in name && name.none { it == '/' || it == '\\' || it == '\u0000' } &&
                contentType(name) != null
    }
}
