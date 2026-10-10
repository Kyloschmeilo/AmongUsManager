package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.AchievementService
import de.amongusmanager.crewbot.services.BetService
import de.amongusmanager.crewbot.services.EventService
import de.amongusmanager.crewbot.services.GameSettingsService
import de.amongusmanager.crewbot.services.GameStats
import de.amongusmanager.crewbot.services.GameStatsService
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.MinecraftService
import de.amongusmanager.crewbot.services.ModRoundService
import de.amongusmanager.crewbot.services.ModSettings
import de.amongusmanager.crewbot.services.RoundHistoryService
import de.amongusmanager.crewbot.services.RoundService
import kotlinx.serialization.json.JsonElement
import kotlin.test.assertEquals

/** Alle Services wie im Bot, aber ohne Discord – auf einer Test-Datenbank. */
class TestServices(val db: Database = Database.inMemory()) {
    val levels = LevelService(db)
    val bets = BetService(db, levels)
    val rounds = RoundService(db, levels, bets)
    val links = LinkService(db)
    val stats = GameStatsService(db)
    val history = RoundHistoryService(db)
    val achievements = AchievementService(db, levels)
    val modRounds = ModRoundService(db, levels, bets, rounds, links, stats, history, achievements)
    val events = EventService(db)
    val modSettings = ModSettings(db)
    val gameSettings = GameSettingsService(db)
    val minecraft = MinecraftService(db, 1, 2)

    fun players(): List<List<Long>> =
        db.query("SELECT user_id, xp, level, participations, wins FROM players ORDER BY id") {
            listOf(it.long("user_id"), it.long("xp"), it.long("level"), it.long("participations"), it.long("wins"))
        }

    fun statsOf(userId: Long): GameStats = stats.get(userId)

    fun allStatUsers(): List<Long> = db.query("SELECT user_id FROM game_stats ORDER BY user_id") { it.long("user_id") }

    fun achievementIds(): Map<String, List<String>> {
        val result = sortedMapOf<Long, MutableList<String>>()
        db.query("SELECT user_id, achievement_id FROM achievements") {
            result.getOrPut(it.long("user_id")) { ArrayList() }.add(it.string("achievement_id"))
        }
        return result.entries.associate { (uid, ids) -> uid.toString() to ids.sorted() }
    }

    fun ledger(): List<Pair<String, Boolean>> =
        db.query("SELECT game_id, undone FROM mod_rounds ORDER BY id") { it.string("game_id") to it.bool("undone") }

    fun links(): List<Pair<String, Long>> =
        db.query("SELECT friend_code, user_id FROM friend_links ORDER BY rowid") { it.string("friend_code") to it.long("user_id") }

    /** Vergleicht den kompletten Zustand mit dem `state()` des Python-Bots. */
    fun assertState(expected: JsonElement, context: String) {
        val e = expected.obj
        assertEquals(e["players"]!!.arr.map { p -> p.arr.map { it.long } }, players(), "$context: Spieler")
        e["ranking"]?.let { ranking ->
            assertEquals(ranking.arr.map { it.long }, levels.ranking().map { it.first }, "$context: Rangliste")
        }
        val expectedStats = e["stats"]!!.obj
        assertEquals(expectedStats.keys.map { it.toLong() }.sorted(), allStatUsers(), "$context: Statistik-Spieler")
        for ((uid, values) in expectedStats) {
            val actual = statsOf(uid.toLong())
            for ((field, value) in values.obj) assertEquals(value.int, actual[field], "$context: Statistik $uid.$field")
        }
        assertEquals(
            e["bets"]!!.arr.map { listOf(it.arr[0].long.toString(), it.arr[1].str, it.arr[2].int.toString()) },
            bets.active.map { listOf(it.userId.toString(), it.team.value, it.amount.toString()) },
            "$context: Wetten",
        )
        assertEquals(e["links"]!!.arr.map { it.arr[0].str to it.arr[1].long }, links(), "$context: Verknüpfungen")
        assertEquals(e["achievements"]!!.obj.mapValues { (_, ids) -> ids.arr.map { it.str } }, achievementIds(), "$context: Erfolge")
        assertEquals(e["history"]!!.arr.map { it.str }, history.rounds().map { it.round.gameId }, "$context: Rundenverlauf")
        assertEquals(e["ledger"]!!.arr.map { it.arr[0].str to it.arr[1].bool }, ledger(), "$context: Auswertungen")
    }
}
