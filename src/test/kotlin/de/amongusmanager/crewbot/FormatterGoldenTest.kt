package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.CATEGORIES
import de.amongusmanager.crewbot.content.ROLE_SETTINGS
import de.amongusmanager.crewbot.content.abilityName
import de.amongusmanager.crewbot.content.formatG
import de.amongusmanager.crewbot.content.pyTitle
import de.amongusmanager.crewbot.core.parseJson
import de.amongusmanager.crewbot.modules.parseEventStart
import de.amongusmanager.crewbot.modules.stripMedal
import de.amongusmanager.crewbot.services.ActionReport
import de.amongusmanager.crewbot.services.AutoMuteEvent
import de.amongusmanager.crewbot.services.GameStats
import de.amongusmanager.crewbot.services.LobbyEvent
import de.amongusmanager.crewbot.services.McPayloadError
import de.amongusmanager.crewbot.services.formatFixed
import de.amongusmanager.crewbot.services.formatTime
import de.amongusmanager.crewbot.services.isValidFriendCode
import de.amongusmanager.crewbot.services.levelForXp
import de.amongusmanager.crewbot.services.nextRank
import de.amongusmanager.crewbot.services.normalizeFriendCode
import de.amongusmanager.crewbot.services.parsePayload
import de.amongusmanager.crewbot.services.rankFor
import de.amongusmanager.crewbot.services.targetState
import de.amongusmanager.crewbot.ui.escapeMarkdown
import de.amongusmanager.crewbot.ui.progressBar
import de.amongusmanager.crewbot.ui.urlQuote
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** Zahlen-, Text- und Parser-Hilfen verhalten sich wie im alten Bot. */
class FormatterGoldenTest {
    private val f = Golden.section("formatters")
    private val parsing = Golden.section("parsing")

    @Test
    fun formatG() {
        for (case in f["format_g"]!!.arr) {
            val value = parseJson(case.arr[1].str)
            assertEquals(case.arr[2].str, formatG(value.str.toDouble()), "formatG(${case.arr[0].str})")
        }
    }

    @Test
    fun rates() {
        for (case in f["rate"]!!.arr) {
            assertEquals(case.arr[2].str, GameStats.rate(case.arr[0].int, case.arr[1].int), "rate(${case.arr[0]}, ${case.arr[1]})")
        }
        for (case in f["fixed1"]!!.arr) {
            val (kills, games) = case.arr[0].int to case.arr[1].int
            assertEquals(case.arr[2].str, formatFixed(kills.toDouble() / games, 1), "$kills / $games")
        }
    }

    @Test
    fun progressAndTime() {
        for (case in f["progress"]!!.arr) {
            assertEquals(case.arr[2].str, progressBar(case.arr[0].int, case.arr[1].int), "progress(${case.arr[0]}, ${case.arr[1]})")
        }
        for (case in f["format_time"]!!.arr) {
            assertEquals(case.arr[1].str, formatTime(case.arr[0].str.toDouble()), "formatTime(${case.arr[0]})")
        }
    }

    @Test
    fun textHelpers() {
        for (case in f["strip_medal"]!!.arr) assertEquals(case.arr[1].str, stripMedal(case.arr[0].str), "stripMedal")
        for (case in f["escape_markdown"]!!.arr) {
            assertEquals(case.arr[1].str, escapeMarkdown(case.arr[0].str), "escapeMarkdown(${case.arr[0].str})")
        }
        for (case in f["url_quote"]!!.arr) assertEquals(case.arr[1].str, urlQuote(case.arr[0].str), "quote(${case.arr[0].str})")
        for (case in f["title"]!!.arr) assertEquals(case.arr[1].str, pyTitle(case.arr[0].str), "title(${case.arr[0].str})")
        for (case in f["ability_names"]!!.arr) assertEquals(case.arr[1].str, abilityName(case.arr[0].str))
    }

    @Test
    fun levelsAndRanks() {
        for (case in f["level_for_xp"]!!.arr) assertEquals(case.arr[1].int, levelForXp(case.arr[0].int), "level(${case.arr[0]})")
        for (case in f["ranks"]!!.arr) {
            val level = case.arr[0].int
            assertEquals(case.arr[1].str, rankFor(level).name, "rank($level)")
            assertEquals(case.arr[2].strOrNull, nextRank(level)?.name, "next($level)")
            assertEquals(case.arr[3].strOrNull?.toInt(), nextRank(level)?.minLevel, "nextLevel($level)")
        }
    }

    @Test
    fun friendCodes() {
        for (case in f["friend_codes"]!!.arr) {
            val code = case.arr[0].str
            assertEquals(case.arr[1].bool, isValidFriendCode(code), "valid($code)")
            assertEquals(case.arr[2].str, normalizeFriendCode(code), "normalize($code)")
        }
    }

    @Test
    fun settingFormats() {
        val settings = (CATEGORIES.flatMap { it.settings } + ROLE_SETTINGS.flatMap { it.settings }).associateBy { it.kind to it.key }
        for (case in f["setting_formats"]!!.arr) {
            val setting = settings.getValue(case.arr[0].str to case.arr[1].str)
            assertEquals(case.arr[3].str, setting.format(parseJson(case.arr[2].str)), "${setting.key} = ${case.arr[2].str}")
        }
    }

    @Test
    fun voiceHelpers() {
        for (case in f["action_reports"]!!.arr) {
            assertEquals(case.arr[2].str, ActionReport(case.arr[0].int, case.arr[1].int).describe("gemutet"))
        }
        for (case in f["target_state"]!!.arr) {
            val (mute, deaf) = targetState(case.arr[0].str, case.arr[1].bool)
            assertEquals(listOf(case.arr[2].arr[0].bool, case.arr[2].arr[1].bool), listOf(mute, deaf), "targetState $case")
        }
    }

    @Test
    fun eventDates() {
        for (case in f["event_dates"]!!.arr) {
            val text = case.arr[0].str
            val expected = case.arr[1].strOrNull?.toDouble()?.toLong()
            assertEquals(expected, parseEventStart(text)?.toEpochSecond(), "create-event '$text'")
        }
    }

    @Test
    fun parseAutoMute() {
        for (case in parsing["automute"]!!.arr) {
            val text = case.arr[0].str
            val expected = case.arr[1]
            val actual = AutoMuteEvent.parse(text)
            if (expected is JsonNull) {
                assertEquals(null, actual, "automute $text")
            } else if (expected.toString().contains("error:")) {
                assertEquals(null, actual, "automute $text (Python warf einen Fehler, Kotlin ignoriert die Nachricht)")
            } else {
                val e = expected.arr
                assertEquals(listOf(e[0].str, e[1].str, e[2].arr.map { it.str }.sorted().toString()),
                    listOf(actual!!.phase, actual.sender, actual.dead.sorted().toString()), "automute $text")
            }
        }
    }

    @Test
    fun parseLobby() {
        for (case in parsing["lobby"]!!.arr) {
            val text = case.arr[0].str
            val actual = LobbyEvent.parse(text)
            if (case.arr[1] is JsonNull) {
                assertEquals(null, actual, "lobby $text")
            } else {
                val e = case.arr[1].arr
                assertEquals(
                    listOf(e[0].str, e[1].str, e[2].str, e[3].int.toString(), e[4].int.toString(), e[5].str, e[6].str),
                    listOf(actual!!.state, actual.code, actual.map, actual.players.toString(), actual.maxPlayers.toString(),
                        actual.host, actual.sender),
                    "lobby $text",
                )
            }
        }
    }

    @Test
    fun parseMinecraft() {
        for (case in parsing["minecraft"]!!.arr) {
            val text = case.arr[0].str
            val expected = case.arr[1]
            val actual = try {
                parsePayload(text)
            } catch (e: McPayloadError) {
                null
            }
            if (expected is kotlinx.serialization.json.JsonPrimitive) {
                assertEquals(null, actual, "minecraft $text")
            } else {
                assertEquals(expected.obj["server"]!!.str, actual!!.server, "server $text")
                assertEquals(
                    expected.obj["events"]!!.arr.map { listOf(it.arr[0].str, it.arr[1].str) },
                    actual.events.map { listOf(it.type.value, it.player) },
                    "events $text",
                )
            }
        }
    }
}
