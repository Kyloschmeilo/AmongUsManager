package de.amongusmanager.crewbot

import de.amongusmanager.crewbot.content.WISH_ROLES
import de.amongusmanager.crewbot.content.wishRole
import de.amongusmanager.crewbot.db.Database
import de.amongusmanager.crewbot.services.GameSettings
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.LinkService
import de.amongusmanager.crewbot.services.ReservedWish
import de.amongusmanager.crewbot.services.RoleRate
import de.amongusmanager.crewbot.services.RoleWishError
import de.amongusmanager.crewbot.services.RoleWishService
import de.amongusmanager.crewbot.services.WishOutcome
import de.amongusmanager.crewbot.services.WishProtocol
import de.amongusmanager.crewbot.services.WishProtocolError
import de.amongusmanager.crewbot.services.WishStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rollenwünsche (/rollenwunsch) und Admin-Zuweisungen: XP, Limits, Plätze, Mod-Austausch, Verschlüsselung. */
open class RoleWishTest {
    /** Datenbank für den Test (Standard SQLite; Unterklasse für MariaDB). */
    open fun newDb(): Database = Database.inMemory()

    private inner class Setup {
        val db = newDb()
        val levels = LevelService(db)
        val links = LinkService(db)
        var now: Instant = Instant.parse("2026-10-12T18:00:00Z") // 20:00 in Berlin
        var settings: GameSettings? = gameSettings(impostors = 2, "Engineer" to RoleRate(1, 100), "Scientist" to RoleRate(0, 100),
            "Shapeshifter" to RoleRate(1, 50), "Tracker" to RoleRate(2, 0))
        val wishes = RoleWishService(db, levels, links, { settings }, { now })

        fun player(userId: Long, xp: Int = 100): Long {
            links.link("spieler$userId#${1000 + userId}", userId)
            if (xp > 0) levels.addXp(userId, xp)
            return userId
        }

        fun xp(userId: Long) = levels.get(userId).xp
    }

    private fun gameSettings(impostors: Int, vararg roles: Pair<String, RoleRate>) = GameSettings(
        capturedAt = Instant.parse("2026-10-12T17:00:00Z"),
        lobbyCode = "ABCDEF",
        map = "The Skeld",
        players = 10,
        maxPlayers = 15,
        numImpostors = impostors,
        options = emptyMap(),
        roles = mapOf(*roles),
    )

    private fun role(name: String) = wishRole(name)!!

    @Test
    fun wishCostsXpAndIsLimitedPerEvening() {
        val s = Setup()
        val user = s.player(1)

        val first = s.wishes.wish(user, role("Engineer"))
        assertEquals(80, s.xp(user))
        assertEquals(1, first.usedTonight)
        assertNull(first.previous)

        // Ändern kostet nichts und zählt nicht extra
        val changed = s.wishes.wish(user, role("Impostor"))
        assertEquals("Engineer", changed.previous)
        assertEquals(80, s.xp(user))
        assertEquals(1, s.wishes.usedTonight(user))
        assertFailsWith<RoleWishError> { s.wishes.wish(user, role("Impostor")) } // gleiche Rolle nochmal

        // Zurückziehen erstattet und zählt nicht
        s.wishes.withdraw(user)
        assertEquals(100, s.xp(user))
        assertEquals(0, s.wishes.usedTonight(user))
        assertFailsWith<RoleWishError> { s.wishes.withdraw(user) }

        // Zwei erfüllte Wünsche pro Abend, dann ist Schluss
        repeat(2) {
            val wish = s.wishes.wish(user, role("Crewmate")).wish
            s.wishes.reserve(listOf("spieler1#1001"))
            s.wishes.applyResults(listOf(WishOutcome(wish.id, "applied", null)))
            assertEquals(WishStatus.USED, s.wishes.byId(wish.id)!!.status)
        }
        assertEquals(60, s.xp(user))
        assertEquals(2, s.wishes.usedTonight(user))
        val error = assertFailsWith<RoleWishError> { s.wishes.wish(user, role("Crewmate")) }
        assertTrue("2 Rollenwünsche" in error.message!!, error.message)

        // Um 5:59 läuft noch derselbe Abend, ab 6 Uhr geht es wieder
        s.now = Instant.parse("2026-10-13T03:59:00Z")
        assertFailsWith<RoleWishError> { s.wishes.wish(user, role("Crewmate")) }
        s.now = Instant.parse("2026-10-13T04:00:00Z")
        s.wishes.wish(user, role("Crewmate"))
        assertEquals(40, s.xp(user))
    }

    @Test
    fun notEnoughXpOrNotLinked() {
        val s = Setup()
        val poor = s.player(1, xp = 19)
        val error = assertFailsWith<RoleWishError> { s.wishes.wish(poor, role("Engineer")) }
        assertTrue("20 XP" in error.message!!)
        assertEquals(19, s.xp(poor))
        assertTrue(s.wishes.pending().isEmpty())

        s.levels.addXp(2, 100) // nicht verknüpft
        assertFailsWith<RoleWishError> { s.wishes.wish(2, role("Engineer")) }
        assertFailsWith<RoleWishError> { s.wishes.assign(99, 2, role("Engineer")) }
        assertEquals(100, s.xp(2))
    }

    @Test
    fun moreWishesThanRolesGiveAnError() {
        val s = Setup()
        s.settings = gameSettings(impostors = 1, "Engineer" to RoleRate(1, 100), "Scientist" to RoleRate(0, 100),
            "Shapeshifter" to RoleRate(1, 100), "Tracker" to RoleRate(2, 0))
        val (a, b, c, d) = listOf(1L, 2L, 3L, 4L).map { s.player(it) }

        s.wishes.wish(a, role("Impostor"))
        val full = assertFailsWith<RoleWishError> { s.wishes.wish(b, role("Impostor")) }
        assertTrue("1 Impostor" in full.message!!, full.message)
        assertFailsWith<RoleWishError> { s.wishes.wish(b, role("Shapeshifter")) } // Impostor-Team ist voll
        assertEquals(100, s.xp(b), "abgelehnte Wünsche kosten nichts")

        s.wishes.wish(b, role("Engineer"))
        val taken = assertFailsWith<RoleWishError> { s.wishes.wish(c, role("Engineer")) }
        assertTrue("1× 🔧 Engineer" in taken.message!!, taken.message)
        assertTrue("<@" !in taken.message!!, "Spieler sehen nicht, wer die Rolle hat")
        assertFailsWith<RoleWishError> { s.wishes.wish(c, role("Scientist")) } // Anzahl 0
        assertFailsWith<RoleWishError> { s.wishes.wish(c, role("Tracker")) } // Chance 0
        assertFailsWith<RoleWishError> { s.wishes.wish(c, role("Noisemaker")) } // gar nicht eingestellt

        // Admins bekommen denselben Fehler, sehen aber, wer die Rolle hat
        val admin = assertFailsWith<RoleWishError> { s.wishes.assign(99, c, role("Engineer")) }
        assertTrue("<@$b>" in admin.message!!, admin.message)

        // Der eigene Wunsch blockiert sich beim Ändern nicht selbst; danach ist der Platz wieder frei
        s.wishes.wish(a, role("Crewmate"))
        s.wishes.wish(d, role("Impostor"))
        s.wishes.withdraw(b)
        s.wishes.wish(c, role("Engineer"))
        assertEquals(setOf(a, c, d), s.wishes.pending().map { it.userId }.toSet())
    }

    @Test
    fun withoutKnownSettingsOnlyTeamRoles() {
        val s = Setup()
        s.settings = null
        val (a, b) = listOf(1L, 2L).map { s.player(it) }
        assertFailsWith<RoleWishError> { s.wishes.wish(a, role("Engineer")) }
        s.wishes.wish(a, role("Impostor"))
        assertFailsWith<RoleWishError> { s.wishes.wish(b, role("Impostor")) } // ohne Einstellungen: 1 Impostor
        s.wishes.wish(b, role("Crewmate"))
    }

    @Test
    fun adminAssignmentReplacesWish() {
        val s = Setup()
        val user = s.player(1)
        s.wishes.wish(user, role("Engineer"))
        assertEquals(80, s.xp(user))

        val result = s.wishes.assign(99, user, role("Impostor"))
        assertEquals(100, s.xp(user), "ersetzter Wunsch wird erstattet")
        assertEquals("Engineer", result.replaced!!.roleName)
        assertEquals(WishStatus.REFUNDED, result.replaced!!.status)
        assertEquals(0, s.wishes.usedTonight(user))
        assertEquals(99, result.entry.createdBy)
        assertEquals(0, result.entry.cost)

        // Solange ein Admin etwas festgelegt hat, kann der Spieler nichts wünschen oder zurückziehen
        assertFailsWith<RoleWishError> { s.wishes.wish(user, role("Crewmate")) }
        assertFailsWith<RoleWishError> { s.wishes.withdraw(user) }
        assertFailsWith<RoleWishError> { s.wishes.assign(99, user, role("Impostor")) }

        val other = s.wishes.assign(98, user, role("Crewmate"))
        assertEquals(WishStatus.CANCELLED, other.replaced!!.status)
        s.wishes.unassign(user)
        assertEquals(100, s.xp(user))
        assertTrue(s.wishes.pending().isEmpty())
        assertFailsWith<RoleWishError> { s.wishes.unassign(user) }

        // Admin entfernt einen bezahlten Wunsch -> XP zurück
        s.wishes.wish(user, role("Engineer"))
        s.wishes.unassign(user)
        assertEquals(100, s.xp(user))
    }

    @Test
    fun modFetchesWishesAndReportsResults() {
        val s = Setup()
        val (a, b, c, d) = listOf(1L, 2L, 3L, 4L).map { s.player(it) }
        val wishA = s.wishes.wish(a, role("Engineer")).wish
        val wishB = s.wishes.wish(b, role("Impostor")).wish
        val wishC = s.wishes.wish(c, role("Crewmate")).wish
        val adminD = s.wishes.assign(99, d, role("Impostor")).entry

        // Friendcodes werden normalisiert; nur Spieler in der Lobby, Admin-Zuweisungen zuerst
        val reserved = s.wishes.reserve(listOf("SPIELER2#1002", " spieler4#1004", "spieler1#1001", "fremd#0000", "spieler1#1001"))
        assertEquals(
            listOf(
                ReservedWish(adminD.id, "spieler4#1004", "Impostor", true),
                ReservedWish(wishA.id, "spieler1#1001", "Engineer", false),
                ReservedWish(wishB.id, "spieler2#1002", "Impostor", false),
            ),
            reserved,
        )

        // Abgeholte Wünsche sind gesperrt, bis die Rückmeldung kommt (oder ein paar Minuten vergehen)
        assertFailsWith<RoleWishError> { s.wishes.withdraw(a) }
        assertFailsWith<RoleWishError> { s.wishes.wish(b, role("Crewmate")) }
        s.wishes.withdraw(c) // war nicht in der Lobby

        val results = s.wishes.applyResults(
            listOf(
                WishOutcome(adminD.id, "applied", null),
                WishOutcome(wishA.id, "failed", "noSlot"),
                WishOutcome(wishB.id, "absent", null),
                WishOutcome(wishC.id, "applied", null), // schon zurückgezogen -> zählt nicht
                WishOutcome(12345, "applied", null),
            )
        )
        assertEquals(listOf(adminD.id), results.applied.map { it.id })
        assertEquals(listOf(wishA.id), results.refunded.map { it.id })
        assertEquals("noSlot", s.wishes.byId(wishA.id)!!.reason)
        assertEquals(100, s.xp(a), "nicht erfüllbar -> XP zurück")
        assertEquals(0, s.wishes.usedTonight(a))
        assertEquals(WishStatus.REFUNDED, s.wishes.byId(wishC.id)!!.status)
        assertEquals(WishStatus.PENDING, s.wishes.byId(wishB.id)!!.status, "nicht dabei -> bleibt für die nächste Runde")
        s.wishes.wish(b, role("Crewmate")) // wieder frei

        // Doppelte Rückmeldung ändert nichts
        s.wishes.applyResults(listOf(WishOutcome(wishA.id, "failed", "noSlot")))
        assertEquals(100, s.xp(a))

        // Ohne Rückmeldung läuft die Sperre ab
        val again = s.wishes.reserve(listOf("spieler2#1002"))
        assertEquals(1, again.size)
        assertFailsWith<RoleWishError> { s.wishes.withdraw(b) }
        s.now += Duration.ofMinutes(Rules.ROLE_WISH_RESERVE_MINUTES)
        s.wishes.withdraw(b)
        assertEquals(100, s.xp(b))
    }

    @Test
    fun openWishesExpireAfterTheEvening() {
        val s = Setup()
        val (a, b) = listOf(1L, 2L).map { s.player(it) }
        s.wishes.wish(a, role("Engineer"))
        s.wishes.assign(99, b, role("Impostor"))

        s.now = Instant.parse("2026-10-13T03:30:00Z") // 5:30 – noch derselbe Abend
        assertTrue(s.wishes.expireOld().isEmpty())

        s.now = Instant.parse("2026-10-13T04:01:00Z") // 6:01
        val expired = s.wishes.expireOld()
        assertEquals(listOf(WishStatus.REFUNDED, WishStatus.CANCELLED), expired.map { it.status })
        assertEquals(listOf("expired", "expired"), expired.map { it.reason })
        assertEquals(100, s.xp(a))
        assertTrue(s.wishes.pending().isEmpty())
        assertTrue(s.wishes.reserve(listOf("spieler1#1001", "spieler2#1002")).isEmpty())
    }

    @Test
    fun eveningsAreBerlinTimeUntilSixInTheMorning() {
        val s = Setup()
        assertEquals("2026-10-12", s.wishes.eveningOf(Instant.parse("2026-10-12T04:00:00Z"))) // 6:00 Sommerzeit
        assertEquals("2026-10-11", s.wishes.eveningOf(Instant.parse("2026-10-12T03:59:59Z")))
        assertEquals("2026-12-24", s.wishes.eveningOf(Instant.parse("2026-12-25T04:59:00Z"))) // 5:59 Winterzeit
    }

    @Test
    fun wishableRolesAreRealGameRoles() {
        // Namen = RoleTypes im Spiel (Among Us 2026.9.29); Geister-Rollen kann man sich nicht wünschen
        assertEquals(
            listOf("Crewmate", "Impostor", "Engineer", "Scientist", "Tracker", "Noisemaker", "Detective", "Judge", "Shapeshifter", "Phantom", "Viper"),
            WISH_ROLES.map { it.gameName },
        )
        assertTrue(WISH_ROLES.size + 1 <= 25, "Discord erlaubt höchstens 25 Auswahlmöglichkeiten")
    }

    // ---------- Protokoll ----------

    @Test
    fun protocolRoundTrip() {
        val token = "geheimer-token"
        val request = WishProtocol.parse(WishProtocol.seal(WishProtocol.REQUEST, """{"players":["a#1234","b#5678",""]}""", token))!!
        assertEquals(WishProtocol.REQUEST, request.kind)
        assertEquals(listOf("a#1234", "b#5678"), WishProtocol.requestPlayers(WishProtocol.open(request, token)))

        // Falscher Token, vertauschte Art oder manipulierte Daten fallen auf
        assertFailsWith<WishProtocolError> { WishProtocol.open(request, "anderer-token") }
        assertFailsWith<WishProtocolError> { WishProtocol.open(request.copy(kind = WishProtocol.RESULT), token) }
        val tampered = request.data.toCharArray().also { it[20] = if (it[20] == 'A') 'B' else 'A' }.concatToString()
        assertFailsWith<WishProtocolError> { WishProtocol.open(request.copy(data = tampered), token) }

        // Antwort: Klartext steht nicht in der Nachricht
        val wishes = listOf(ReservedWish(7, "a#1234", "Engineer", false), ReservedWish(3, "b#5678", "Impostor", true))
        val (content, sent) = WishProtocol.reply(wishes, token)
        assertEquals(wishes, sent)
        assertTrue("Engineer" !in content && "a#1234" !in content)
        val reply = Json.parseToJsonElement(content).jsonObject
        assertEquals(WishProtocol.REPLY, reply["crewstats"]!!.jsonPrimitive.content)
        assertNull(WishProtocol.parse(content), "die eigene Antwort ist keine Anfrage")
        val plain = WishProtocol.open(
            de.amongusmanager.crewbot.services.WishMessage(WishProtocol.REPLY, reply["data"]!!.jsonPrimitive.content), token,
        )
        assertEquals("""{"wishes":[{"id":7,"fc":"a#1234","role":"Engineer","admin":false},{"id":3,"fc":"b#5678","role":"Impostor","admin":true}]}""", plain)

        // Rückmeldung der Mod
        val result = WishProtocol.parse(
            WishProtocol.seal(
                WishProtocol.RESULT,
                """{"results":[{"id":7,"status":"applied"},{"id":3,"status":"failed","reason":"noSlot"},{"id":"x"},{"status":"absent"}]}""",
                token,
            )
        )!!
        assertEquals(
            listOf(WishOutcome(7, "applied", null), WishOutcome(3, "failed", "noSlot")),
            WishProtocol.outcomes(WishProtocol.open(result, token)),
        )

        // Andere Mod-Nachrichten werden nicht als Rollenwunsch erkannt
        assertNull(WishProtocol.parse("""{"crewstats":"lobby","state":"open"}"""))
        assertNull(WishProtocol.parse("""{"crewstats":"wishes"}"""))
        assertNull(WishProtocol.parse("kein json"))
    }

    @Test
    fun replyFitsIntoOneDiscordMessage() {
        val wishes = (1L..40L).map { ReservedWish(it * 1_000_000, "langername$it#${1000 + it}", "Shapeshifter", it % 2 == 0L) }
        val (content, sent) = WishProtocol.reply(wishes, "token")
        assertTrue(content.length <= WishProtocol.MAX_CONTENT)
        assertTrue(sent.size in 15 until 40, "so viele Spieler hat keine Lobby – aber nichts darf abgeschnitten werden")
        assertEquals(wishes.take(sent.size), sent)
    }

    @Test
    fun decryptsWhatTheModEncrypts() {
        // Mit der Mod (RoleWishes.cs, .NET AesGcm) erzeugt – beide Seiten müssen dasselbe Format sprechen
        val message = assertNotNull(WishProtocol.parse(MOD_VECTOR))
        assertEquals(WishProtocol.REQUEST, message.kind)
        assertEquals(listOf("happyfox#1234", "bluecat#9876"), WishProtocol.requestPlayers(WishProtocol.open(message, MOD_TOKEN)))
    }

    companion object {
        const val MOD_TOKEN = "AbCdEf-ghijklmnop_1234567890qrstuvwxyz"
        const val MOD_VECTOR =
            """{"crewstats":"wishes","v":1,"data":"7DY0eTx1C3cGM4Vkr817LOYtuEbO6TAdrp5qMP4V4Z6tJHwPyzBNq0uUKcEv6V8MoEOMjQQqW7i1wM8yOLXhtTQM7/a5TlJg"}"""
    }
}
