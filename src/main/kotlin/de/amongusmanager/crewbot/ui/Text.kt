/* Text-Helfer, die sich genau wie die entsprechenden Python-Funktionen verhalten. */
package de.amongusmanager.crewbot.ui

import java.util.regex.Matcher
import java.util.regex.Pattern

private const val MARKDOWN_ESCAPE_COMMON = """^>(?:>>)?\s|\[.+\]\(.+\)|^#{1,3}|^\s*-"""
private const val URL_REGEX = """(?<url><[^: >]+:/[^ >]+>|(?:https?|steam)://[^\s<]+[^<.,:;"'\]\s])"""
private const val MARKDOWN_STOCK_REGEX = """(?<markdown>[_\\~|*`]|$MARKDOWN_ESCAPE_COMMON)"""
private val ESCAPE_PATTERN: Pattern = Pattern.compile("(?:$URL_REGEX|$MARKDOWN_STOCK_REGEX)", Pattern.MULTILINE)

/**
 * discord.py `escape_markdown(text)`: setzt vor Markdown-Zeichen einen Backslash, Links bleiben unverändert.
 * So erscheinen Namen wie „Mr_Steve“ genau so, statt kursiv zu werden.
 */
fun escapeMarkdown(text: String): String {
    val matcher = ESCAPE_PATTERN.matcher(text)
    val result = StringBuilder()
    while (matcher.find()) {
        val url = matcher.group("url")
        matcher.appendReplacement(result, Matcher.quoteReplacement(url ?: ("\\" + matcher.group("markdown"))))
    }
    matcher.appendTail(result)
    return result.toString()
}

/** Python `urllib.parse.quote(text)`: alles außer Buchstaben, Ziffern und `_.-~/` wird %-kodiert. */
fun urlQuote(text: String): String {
    val result = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val char = c.toChar()
        if (c < 128 && (char.isLetterOrDigit() || char in "_.-~/")) {
            result.append(char)
        } else {
            result.append('%').append(String.format("%02X", c))
        }
    }
    return result.toString()
}
