package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.CATEGORIES
import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.services.GameSettings
import de.amongusmanager.crewbot.services.GameSettingsError
import de.amongusmanager.crewbot.services.GameStats
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.McEvent
import de.amongusmanager.crewbot.services.PlayerStats
import de.amongusmanager.crewbot.ui.buildAchievementsEmbed
import de.amongusmanager.crewbot.ui.buildCrewCardEmbed
import de.amongusmanager.crewbot.ui.buildLobbyEmbed
import de.amongusmanager.crewbot.ui.buildMinecraftEmbed
import de.amongusmanager.crewbot.ui.buildProfileEmbed
import de.amongusmanager.crewbot.ui.views.ControlPanel
import de.amongusmanager.crewbot.ui.views.EventRoster
import de.amongusmanager.crewbot.ui.views.buildEventEmbed
import de.amongusmanager.crewbot.ui.views.buildSettingsEmbed
import de.amongusmanager.crewbot.ui.views.closedEventEmbed
import de.amongusmanager.crewbot.ui.views.eventStartFromEmbed
import kotlinx.serialization.json.jsonObject
import net.dv8tion.jda.api.EmbedBuilder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Jedes Embed sieht genauso aus wie im alten Bot. */
class EmbedGoldenTest {
    private val embeds = Golden.section("embeds")

    @Test
    fun gameSettings() {
        for (case in embeds["settings"]!!.arr) {
            val settings = GameSettings.parse(case.obj["raw"]!!.str)
            val category = CATEGORIES.first { it.key == case.obj["category"]!!.str }
            assertEmbed(case.obj["embed"]!!, buildSettingsEmbed(settings, category), "Einstellungen ${category.key}")
        }
        for (case in embeds["settings_invalid"]!!.arr) {
            val raw = case.arr[0].str
            if (case.arr[1].str == "error") {
                assertFailsWith<GameSettingsError>(raw) { GameSettings.parse(raw) }
            } else {
                GameSettings.parse(raw)
            }
        }
    }

    @Test
    fun lobby() {
        for (case in embeds["lobby"]!!.arr) {
            val e = case.obj["event"]!!.arr
            val event = LobbyEvent(e[0].str, e[1].str, e[2].str, e[3].int, e[4].int, e[5].str, "")
            assertEmbed(case.obj["embed"]!!, buildLobbyEmbed(event, case.obj["host"]!!.str), "Lobby $e", ignoreTimestamp = true)
        }
    }

    @Test
    fun minecraft() {
        val `when` = Instant.parse("2026-10-01T20:15:30Z")
        for (case in embeds["minecraft"]!!.arr) {
            val type = McEventType.fromValue(case.obj["type"]!!.str)!!
            val data = parseJson(case.obj["data"]!!.str).jsonObject
            val event = McEvent(type, case.obj["player"]!!.str, data)
            assertEmbed(case.obj["embed"]!!, buildMinecraftEmbed(event, "CoreSMP", `when`), "Minecraft ${case.obj["data"]}")
        }
    }

    @Test
    fun controlPanel() {
        for (case in embeds["control_panel"]!!.arr) {
            val embed = ControlPanel.buildEmbed(case.obj["status"]!!.str, case.obj["bets"]!!.int)
            assertEmbed(case.obj["embed"]!!, embed, "Panel")
        }
    }

    @Test
    fun events() {
        for (case in embeds["event"]!!.arr) {
            val start = Instant.ofEpochSecond(case.obj["start"]!!.str.toDouble().toLong()).atZone(TIMEZONE)
            val url = case.obj["url"]!!.strOrNull
            val game = case.obj["game"]!!.str
            val creator = case.obj["creator"]!!.str
            assertEmbed(case.obj["embed"]!!, buildEventEmbed(game, start, creator, url), "Event $game")

            val withRoster = EmbedBuilder(buildEventEmbed(game, start, creator, url))
            val roster = EventRoster.fromEmbed(withRoster.build())
            roster.entries.addAll(listOf("<@111>", "<@!222>", "OldName", "<@333>"))
            val rosterEmbed = roster.writeTo(withRoster).build()
            assertEmbed(case.obj["with_roster"]!!, rosterEmbed, "Event-Liste $game")
            assertEquals(case.obj["member_ids"]!!.arr.map { it.long }, EventRoster.fromEmbed(rosterEmbed).memberIds())
            assertEquals(case.obj["start_from_embed"]!!.str.toDouble().toLong(), eventStartFromEmbed(rosterEmbed)!!.epochSecond)
            assertEmbed(case.obj["closed"]!!, closedEventEmbed(rosterEmbed), "Event geschlossen $game")
            // Zweimal schließen ändert nichts mehr (der alte Bot hängte den Hinweis jedes Mal neu an)
            assertEmbed(case.obj["closed"]!!, closedEventEmbed(closedEventEmbed(rosterEmbed)), "Event doppelt geschlossen")
        }
    }

    @Test
    fun achievements() {
        for ((index, case) in embeds["achievements"]!!.arr.withIndex()) {
            val unlocked = case.obj["unlocked"]!!.obj.mapValues { (_, at) -> Instant.ofEpochSecond(at.str.toDouble().toLong()) }
            val stats = GameStats.fromMap(case.obj["stats"]!!.obj.mapValues { it.value.int })
            val embed = buildAchievementsEmbed(
                "Spieler_Fünf", "https://cdn.example.com/avatars/555.png", LinkedHashMap(unlocked), stats,
            )
            assertEmbed(case.obj["embed"]!!, embed, "Erfolge #$index")
        }
    }

    @Test
    fun profiles() {
        for (case in embeds["profiles"]!!.arr) {
            val o = case.obj
            val s = o["stats"]!!.arr
            val stats = PlayerStats(xp = s[0].int, level = s[1].int, participations = s[2].int, wins = s[3].int)
            val id = o["id"]!!.long
            val avatar = "https://cdn.example.com/avatars/$id.png"
            assertEmbed(o["profile"]!!, buildProfileEmbed(o["name"]!!.str, avatar, stats), "Profil $id")
            assertEmbed(o["card"]!!, buildCrewCardEmbed(o["name"]!!.str, avatar, id, o["offline"]!!.bool, stats), "Crew-Card $id")
        }
    }
}
