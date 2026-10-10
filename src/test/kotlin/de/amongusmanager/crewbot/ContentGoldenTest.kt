package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.ABILITY_NAMES
import de.amongusmanager.crewbot.content.ACHIEVEMENTS
import de.amongusmanager.crewbot.content.CATEGORIES
import de.amongusmanager.crewbot.content.DEATH_CAUSES
import de.amongusmanager.crewbot.content.DEFAULT_DEATH
import de.amongusmanager.crewbot.content.DIMENSIONS
import de.amongusmanager.crewbot.content.FUN_TEXTS
import de.amongusmanager.crewbot.content.IGNORED_OPTIONS
import de.amongusmanager.crewbot.content.MAPS
import de.amongusmanager.crewbot.content.MOBS
import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.content.ROLES
import de.amongusmanager.crewbot.content.ROLE_SETTINGS
import de.amongusmanager.crewbot.content.SUSPICION_VERDICTS
import de.amongusmanager.crewbot.content.Team
import de.amongusmanager.crewbot.content.knownOptionKeys
import de.amongusmanager.crewbot.content.roleLabel
import de.amongusmanager.crewbot.services.RANKS
import de.amongusmanager.crewbot.ui.views.REASON_TEXTS
import kotlin.test.Test
import kotlin.test.assertEquals

/** Alle Texte und Daten müssen exakt wie im alten Bot sein. */
class ContentGoldenTest {
    private val content = Golden.section("content")

    @Test
    fun roles() {
        val expected = content["roles"]!!.arr.map { r -> r.arr.map { it.str } }
        val actual = ROLES.values.map { listOf(it.name, it.team.value, it.emoji, it.description) }
        assertEquals(expected, actual)
    }

    @Test
    fun maps() {
        val expected = content["maps"]!!.arr.map { m -> m.arr.map { it.str } }
        val actual = MAPS.values.map { listOf(it.name, it.description, it.cameras, it.dangerZones, it.tasks, it.ventImage) }
        assertEquals(expected, actual)
    }

    @Test
    fun funAndVerdicts() {
        assertEquals(content["fun_texts"]!!.arr.map { it.str }, FUN_TEXTS)
        val expected = content["verdicts"]!!.arr.map { it.arr[0].int to it.arr[1].str }
        assertEquals(expected, SUSPICION_VERDICTS.map { it.maxPercent to it.text })
    }

    @Test
    fun achievements() {
        val expected = content["achievements"]!!.arr.map { a -> a.arr.map { it.strOrNull } }
        val actual = ACHIEVEMENTS.map {
            listOf(it.id, it.emoji, it.name, it.description, it.xp.toString(), it.kind.name.lowercase(), it.stat, it.target.toString())
        }
        assertEquals(expected, actual)
    }

    @Test
    fun ranks() {
        assertEquals(content["ranks"]!!.arr.map { it.arr[0].str to it.arr[1].int }, RANKS.map { it.name to it.minLevel })
    }

    @Test
    fun gameSettingsContent() {
        val expectedCategories = content["categories"]!!.arr.map { c ->
            listOf(c.arr[0].str, c.arr[1].str, c.arr[2].str, c.arr[3].arr.map { s -> s.arr.map { it.str } }.toString())
        }
        val actualCategories = CATEGORIES.map { c ->
            listOf(c.key, c.label, c.emoji, c.settings.map { listOf(it.kind, it.key, it.label) }.toString())
        }
        assertEquals(expectedCategories, actualCategories)

        val expectedRoles = content["role_settings"]!!.arr.map { r ->
            listOf(r.arr[0].str, r.arr[1].str, r.arr[2].bool.toString(), r.arr[3].arr.map { s -> s.arr.map { it.str } }.toString())
        }
        val actualRoles = ROLE_SETTINGS.map { r ->
            listOf(r.gameName, r.name, r.impostor.toString(), r.settings.map { listOf(it.kind, it.key, it.label) }.toString())
        }
        assertEquals(expectedRoles, actualRoles)
        assertEquals(content["ignored_options"]!!.arr.map { it.str }, IGNORED_OPTIONS.sorted())
        assertEquals(content["known_option_keys"]!!.arr.map { it.str }, knownOptionKeys().sorted())
    }

    @Test
    fun minecraftContent() {
        val causes = content["death_causes"]!!.obj.mapValues { (_, v) -> v.arr.map { it.strOrNull } }
        assertEquals(causes, DEATH_CAUSES.mapValues { (_, c) -> listOf(c.emoji, c.text, c.byKiller) })
        assertEquals(content["default_death"]!!.arr.map { it.strOrNull }, listOf(DEFAULT_DEATH.emoji, DEFAULT_DEATH.text, DEFAULT_DEATH.byKiller))
        assertEquals(content["mobs"]!!.obj.mapValues { (_, v) -> v.arr[0].str to v.arr[1].str }, MOBS)
        assertEquals(content["dimensions"]!!.obj.mapValues { it.value.str }, DIMENSIONS)
        assertEquals(content["ability_names"]!!.obj.mapValues { it.value.str }, ABILITY_NAMES)
        val info = content["event_info"]!!.obj.mapValues { (_, v) -> v.arr.map { it.str } }
        assertEquals(info, McEventType.entries.associate { it.value to listOf(it.info.emoji, it.info.label, it.info.description) })
    }

    @Test
    fun config() {
        assertEquals(content["game_ping_roles"]!!.arr.map { it.arr[0].str to it.arr[1].long }, GAME_PING_ROLES.toList())
        assertEquals(content["level_role_rewards"]!!.arr.map { it.arr[0].int to it.arr[1].long }, LEVEL_ROLE_REWARDS.toList())

        val rules = content["rules"]!!.obj
        val actualRules = mapOf(
            "XP_PER_LEVEL" to Rules.XP_PER_LEVEL, "EVENT_ATTENDANCE_XP" to Rules.EVENT_ATTENDANCE_XP,
            "IMPOSTOR_WIN_XP" to Rules.IMPOSTOR_WIN_XP, "CREW_WIN_XP" to Rules.CREW_WIN_XP, "MVP_XP" to Rules.MVP_XP,
            "MAX_BET" to Rules.MAX_BET, "BET_PAYOUT_FACTOR" to Rules.BET_PAYOUT_FACTOR,
            "BET_LOCK_MAX_MINUTES" to Rules.BET_LOCK_MAX_MINUTES, "BET_UNLOCK_GRACE_SECONDS" to Rules.BET_UNLOCK_GRACE_SECONDS,
            "MVP_VOTING_SECONDS" to Rules.MVP_VOTING_SECONDS, "RECAP_HOURS" to Rules.RECAP_HOURS,
            "EVENT_DURATION_HOURS" to Rules.EVENT_DURATION_HOURS, "EVENT_REMINDER_MINUTES" to Rules.EVENT_REMINDER_MINUTES,
            "EVENT_MIN_ATTENDANCE_MINUTES" to Rules.EVENT_MIN_ATTENDANCE_MINUTES,
            "FUN_TIMEOUT_CHANCE" to Rules.FUN_TIMEOUT_CHANCE, "FUN_TIMEOUT_MINUTES" to Rules.FUN_TIMEOUT_MINUTES,
        )
        assertEquals(rules.keys, actualRules.keys, "Regeln: gleiche Namen")
        for ((key, value) in actualRules) {
            assertEquals(rules[key]!!.str.toDouble(), value.toDouble(), "Regel $key")
        }

        val channels = content["channels"]!!.obj
        val actualChannels = mapOf(
            "AMONG_US_VOICE" to Channels.AMONG_US_VOICE, "AUTO_UNMUTE_VOICE" to Channels.AUTO_UNMUTE_VOICE,
            "CODES" to Channels.CODES, "LOG" to Channels.LOG, "MINECRAFT_INBOX" to Channels.MINECRAFT_INBOX,
            "ROLE_LOG" to Channels.ROLE_LOG, "MINECRAFT" to Channels.MINECRAFT,
        )
        assertEquals(channels.mapValues { it.value.long }, actualChannels)
        assertEquals(
            content["roles_ids"]!!.obj.mapValues { it.value.long },
            mapOf("AMONG_US_PING" to Roles.AMONG_US_PING, "EVENT_MANAGER" to Roles.EVENT_MANAGER),
        )
    }

    @Test
    fun labels() {
        assertEquals(content["reason_texts"]!!.obj.mapValues { it.value.str }, REASON_TEXTS)
        assertEquals(content["team_labels"]!!.obj.mapValues { it.value.str }, Team.entries.associate { it.value to it.label })
        for ((role, label) in content["role_labels"]!!.obj) {
            assertEquals(label.str, roleLabel(role), "Rolle $role")
        }
    }
}
