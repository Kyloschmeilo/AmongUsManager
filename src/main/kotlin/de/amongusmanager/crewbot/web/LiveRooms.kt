/* Stats-Website: offene Räume der Browserspiele für die Live-Leiste der Spiele-Startseite. */
package de.amongusmanager.crewbot.web

import kotlinx.serialization.json.JsonObject

/**
 * Die Live-Leiste zeigt jeden Raum, in dem gerade jemand ist, als eigene Karte und führt direkt hinein
 * (`<Spiel>?raum=CODE`) – deshalb stehen die Codes hier (Kilian, 20.11.2026: „automatisch joinen“). Nur Code,
 * Spielerzahl und ob schon gespielt wird, keine Namen; volle Räume fehlen (dort kann man eh nicht beitreten).
 */
internal object LiveRooms {
    /** Höchstens so viele Räume je Spiel (die vollsten zuerst). */
    const val MAX = 6

    /** [players] = gerade anwesend, [taken] = belegte Plätze (auch kurz Abwesende), voll ab [max]. */
    class Room(val code: String, val players: Int, val max: Int, val playing: Boolean, val taken: Int = players)

    /** Antwort für `GET /api/<spiel>`: `rooms` = Zahl der belegten Räume (wie bisher), `players`, `list` = beitretbare Räume. */
    fun status(rooms: List<Room>, vararg extra: Pair<String, Any?>): JsonObject {
        val used = rooms.filter { it.players > 0 }
        val open = used.filter { it.taken < it.max }
            .sortedWith(compareBy<Room> { it.playing }.thenByDescending { it.players })
            .take(MAX)
            .map { obj("code" to it.code, "players" to it.players, "max" to it.max, "playing" to it.playing) }
        return obj("rooms" to used.size, "players" to used.sumOf { it.players }, "list" to open, *extra)
    }
}
