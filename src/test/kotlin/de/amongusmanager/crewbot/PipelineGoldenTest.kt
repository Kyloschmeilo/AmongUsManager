package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.services.BetError
import de.amongusmanager.crewbot.services.ModRound
import de.amongusmanager.crewbot.services.ModRoundError
import de.amongusmanager.crewbot.services.buildRecap
import de.amongusmanager.crewbot.services.rivalsOf
import de.amongusmanager.crewbot.ui.buildRecapEmbed
import de.amongusmanager.crewbot.ui.buildStatsEmbed
import de.amongusmanager.crewbot.ui.mvpSuggestionText
import de.amongusmanager.crewbot.ui.rivalsText
import de.amongusmanager.crewbot.ui.views.buildModRoundEmbed
import de.amongusmanager.crewbot.ui.views.roundResultEmbed
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Spielt hunderte zufällige Operationen (Runden der Mod, Wetten, Zurücksetzen, Admin-Änderungen …)
 * nach und vergleicht nach jedem Schritt mit dem alten Python-Bot.
 */
class PipelineGoldenTest {
    private val pipeline = Golden.section("pipeline")

    @Test
    fun replayAllOperations() {
        val s = TestServices()
        for ((index, op) in pipeline["ops"]!!.arr.withIndex()) {
            val o = op.obj
            val context = "Schritt $index (${o["op"]!!.str})"
            when (o["op"]!!.str) {
                "link" -> s.links.link(o["code"]!!.str, o["user"]!!.long)
                "unlink" -> assertEquals(o["expected"]!!.arr.map { it.str }, s.links.unlink(o["user"]!!.long), context)
                "round" -> {
                    val expected = o["expected"]!!.obj
                    try {
                        val result = s.modRounds.applyWithoutRoles(ModRound.parse(o["json"]!!.str))
                        if ("error" in expected) fail("$context: Fehler '${expected["error"]!!.str}' erwartet")
                        assertEmbed(expected["embed"]!!, buildModRoundEmbed(result), context, ignoreTimestamp = true)
                        assertEquals(
                            expected["unlocks"]!!.arr.map { u -> u.arr.map { it.str } },
                            result.achievements.map {
                                listOf(it.userId.toString(), it.achievement.id, it.result.oldLevel.toString(), it.result.stats.level.toString())
                            },
                            "$context: Erfolge",
                        )
                        assertEquals(
                            expected["discord_ids"]!!.obj.mapValues { it.value.long },
                            result.discordIds.mapKeys { it.key.toString() },
                            "$context: Verknüpfte Spieler",
                        )
                    } catch (e: ModRoundError) {
                        assertEquals(expected["error"]?.str, e.message, context)
                    }
                }
                "bet" -> {
                    val expected = o["expected"]!!.obj
                    try {
                        val bet = s.bets.place(o["user"]!!.long, Team.fromValue(o["team"]!!.str)!!, o["amount"]!!.int)
                        assertEquals(
                            expected["bet"]?.arr?.map { it.str },
                            listOf(bet.userId.toString(), bet.team.value, bet.amount.toString()),
                            context,
                        )
                    } catch (e: BetError) {
                        assertEquals(expected["error"]?.str, e.message, context)
                    }
                }
                "undo" -> {
                    val expected = o["expected"]!!.obj
                    try {
                        s.modRounds.undo(o["game_id"]!!.str)
                        if ("error" in expected) fail("$context: Fehler '${expected["error"]!!.str}' erwartet")
                    } catch (e: ModRoundError) {
                        assertEquals(expected["error"]?.str, e.message, context)
                    }
                }
                "refund" -> assertEquals(
                    o["expected"]!!.arr.map { it.arr[0].long to it.arr[1].int },
                    s.bets.refundAll().map { it.userId to it.amount },
                    context,
                )
                "finish" -> {
                    val outcome = s.rounds.finishWithoutRoles(Team.fromValue(o["team"]!!.str)!!, o["winners"]!!.arr.map { it.long })
                    assertEmbed(o["expected"]!!, roundResultEmbed(outcome), context)
                }
                "award" -> {
                    val result = s.levels.addXp(o["user"]!!.long, o["amount"]!!.int, participation = o["participation"]!!.bool)
                    val e = o["expected"]!!.arr
                    assertEquals(
                        listOf(e[0].int, e[1].int, e[2].int, e[3].int, e[4].int, if (e[5].bool) 1 else 0),
                        listOf(result.stats.xp, result.stats.level, result.stats.participations, result.stats.wins,
                            result.oldLevel, if (result.leveledUp) 1 else 0),
                        context,
                    )
                }
                "admin" -> {
                    val user = o["user"]!!.long
                    val value = o["value"]!!.int
                    val e = o["expected"]!!.arr.map { it.int }
                    val actual = when (o["kind"]!!.str) {
                        "xp" -> s.levels.setXp(user, value).let { listOf(it.stats.xp, it.stats.level, it.stats.participations, it.stats.wins, it.oldLevel) }
                        "level" -> s.levels.setLevel(user, value).let { listOf(it.stats.xp, it.stats.level, it.stats.participations, it.stats.wins, it.oldLevel) }
                        "reset" -> {
                            s.levels.reset(user)
                            s.levels.get(user).let { listOf(it.xp, it.level, it.participations, it.wins) }
                        }
                        else -> s.levels.removeXp(user, kotlin.math.abs(value), participation = o["participation"]!!.bool)
                            .let { listOf(it.xp, it.level, it.participations, it.wins) }
                    }
                    assertEquals(e, actual, context)
                }
                else -> fail("Unbekannte Operation ${o["op"]}")
            }
            o["players"]?.let { players ->
                assertEquals(players.arr.map { p -> p.arr.map { it.long } }, s.players(), "$context: Spielerstand")
            }
        }

        s.assertState(pipeline["final"]!!, "Endstand")
        checkInsights(s)
    }

    private fun checkInsights(s: TestServices) {
        val all = s.history.rounds()
        assertEmbed(pipeline["recap_all"]!!, buildRecapEmbed(buildRecap(all), "🌙 Alle Runden"), "Recap alle")
        val since = Instant.parse("2026-01-01T00:00:00Z")
        assertEmbed(
            pipeline["recap_since"]!!,
            buildRecapEmbed(buildRecap(s.history.rounds(since = since)), "🌙 Seit Januar", since),
            "Recap seit",
        )
        assertEmbed(pipeline["recap_last"]!!, buildRecapEmbed(buildRecap(all.takeLast(15)), "🌙 Letzte 15"), "Recap letzte 15")
        assertEmbed(pipeline["recap_empty"]!!, buildRecapEmbed(buildRecap(emptyList()), "🌙 Leer", since), "Recap leer")
        assertEquals(pipeline["mvp_suggestion"]!!.str, mvpSuggestionText(buildRecap(all.takeLast(10))), "MVP-Vorschlag")

        for ((uid, text) in pipeline["rivals"]!!.obj) {
            assertEquals(text.str, rivalsText(rivalsOf(uid.toLong(), s.history.roundsOf(uid.toLong()))), "Rivalen $uid")
        }
        for ((uid, embed) in pipeline["stats_embeds"]!!.obj) {
            val userId = uid.toLong()
            val stats = s.stats.get(userId)
            val rivals = if (stats.games == 0) "" else rivalsText(rivalsOf(userId, s.history.roundsOf(userId)))
            val actual = buildStatsEmbed(
                "Spieler ${userId % 1000}", "https://cdn.example.com/avatars/$userId.png", stats,
                s.links.codesOf(userId), rivals, s.achievements.unlocked(userId).size,
            )
            assertEmbed(embed, actual, "/stats $uid")
        }
    }
}
