/* Admin-Panel: XP/Level eines Spielers bearbeiten oder zurücksetzen. */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.core.BotThread
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.AuditLog
import de.amongusmanager.crewbot.services.LevelService
import de.amongusmanager.crewbot.services.PlayerStats
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.field
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import java.time.Duration

enum class AdminMode(val value: String) { XP("XP"), LEVEL("Level") }

/** Mehrstufiges Menü: Hauptmenü -> Werte anpassen / Reset bestätigen. */
class AdminPanelView(
    private val levels: LevelService,
    private val audit: AuditLog,
    private val target: Member,
    private val actor: Member,
) : SessionView(actor.idLong, Duration.ofSeconds(300)) {
    companion object {
        val STEPS = listOf(1, 5, 10)
        const val LOG_DELAY_MILLIS = 10_000L // schnelle Klicks werden zu einem Log-Eintrag zusammengefasst
    }

    private enum class Screen { MAIN, ADJUST, RESET_CONFIRM }

    private var screen = Screen.MAIN
    private var mode: AdminMode? = null
    private var before: PlayerStats? = null // Stand vor den noch nicht geloggten Änderungen
    private var changeCount = 0
    private var logJob: Job? = null

    fun buildEmbed(note: String? = null): MessageEmbed {
        val stats = levels.get(target.idLong)
        val currentMode = mode
        val description = if (currentMode == null) {
            "Wähle aus, was du bearbeiten möchtest."
        } else {
            "✏️ **${currentMode.value} bearbeiten** – nutze die +/− Buttons."
        }
        val embed = EmbedBuilder()
            .setTitle("🛠️ Admin-Panel: ${target.effectiveName}")
            .setDescription(description)
            .setColor(Palette.BRAND)
            .setThumbnail(target.effectiveAvatarUrl)
            .field("Level", "**${stats.level}**")
            .field("XP", "**${stats.xp}**")
            .field("Siege", stats.wins.toString())
            .field("Teilnahmen", stats.participations.toString())
            .field("Rang", stats.rank.name)
        if (note != null) embed.setFooter(note)
        return embed.build()
    }

    // ---------- Ansichten ----------

    override fun render(): List<ActionRow> = when (screen) {
        Screen.MAIN -> listOf(
            ActionRow.of(
                button("xp", ButtonStyle.PRIMARY, "XP bearbeiten") { open(it, AdminMode.XP) },
                button("level", ButtonStyle.PRIMARY, "Level bearbeiten") { open(it, AdminMode.LEVEL) },
                button("reset", ButtonStyle.DANGER, "RESET") { event ->
                    screen = Screen.RESET_CONFIRM
                    refresh(event, "⚠️ Wirklich ALLE Daten von ${target.effectiveName} löschen?")
                },
            )
        )
        Screen.ADJUST -> listOf(
            ActionRow.of(STEPS.map { step -> button("plus$step", ButtonStyle.SUCCESS, "+$step") { adjust(it, step) } }),
            ActionRow.of(STEPS.map { step -> button("minus$step", ButtonStyle.DANGER, "−$step") { adjust(it, -step) } }),
            ActionRow.of(button("back", ButtonStyle.SECONDARY, "Zurück") { showMain(it) }),
        )
        Screen.RESET_CONFIRM -> listOf(
            ActionRow.of(
                button("confirmReset", ButtonStyle.DANGER, "Ja, alles löschen") { confirmReset(it) },
                button("cancelReset", ButtonStyle.SECONDARY, "Abbrechen") { showMain(it) },
            )
        )
    }

    // ---------- Callbacks ----------

    private suspend fun open(event: GenericComponentInteractionCreateEvent, newMode: AdminMode) {
        screen = Screen.ADJUST
        mode = newMode
        refresh(event)
    }

    private suspend fun showMain(event: GenericComponentInteractionCreateEvent, note: String? = null) {
        screen = Screen.MAIN
        mode = null
        refresh(event, note)
    }

    private suspend fun confirmReset(event: GenericComponentInteractionCreateEvent) {
        flushLog()
        val old = levels.get(target.idLong)
        levels.reset(target.idLong)
        audit.log(
            Actor.of(actor),
            "Spielerdaten zurückgesetzt",
            "**Spieler:** ${target.asMention}\n**Vorher:** Level ${old.level}, ${old.xp} XP," +
                " ${old.wins} Siege, ${old.participations} Teilnahmen",
            Palette.RED,
        )
        showMain(event, "✅ Daten wurden zurückgesetzt.")
    }

    private suspend fun adjust(event: GenericComponentInteractionCreateEvent, delta: Int) {
        val currentMode = mode ?: return
        val stats = levels.get(target.idLong)
        rememberChange(stats)
        val result = if (currentMode == AdminMode.XP) {
            levels.setXp(target.idLong, stats.xp + delta)
        } else {
            levels.setLevel(target.idLong, stats.level + delta)
        }
        if (result.leveledUp) levels.grantLevelRoles(target, result.stats.level)
        val signed = if (delta >= 0) "+$delta" else "$delta"
        refresh(event, "✅ $signed ${currentMode.value} angewendet.")
    }

    // ---------- Audit-Log ----------

    private fun rememberChange(stats: PlayerStats) {
        if (before == null) before = PlayerStats(xp = stats.xp, level = stats.level)
        changeCount++
        logJob?.cancel()
        logJob = BotThread.scope.launch {
            delay(LOG_DELAY_MILLIS)
            flushLog()
        }
    }

    private suspend fun flushLog() {
        val pending = logJob
        logJob = null
        val previous = before ?: run {
            pending?.cancel()
            return
        }
        val count = changeCount
        before = null
        changeCount = 0
        // Der verzögerte Job darf sich nicht selbst abbrechen, während er gerade loggt
        if (pending != null && pending != kotlin.coroutines.coroutineContext[Job]) pending.cancel()
        val now = levels.get(target.idLong)
        audit.log(
            Actor.of(actor),
            "XP/Level geändert",
            "**Spieler:** ${target.asMention}\n" +
                "**XP:** ${previous.xp} → ${now.xp}\n" +
                "**Level:** ${previous.level} → ${now.level}\n" +
                "*$count Änderung(en) im Admin-Panel*",
            Palette.ORANGE,
        )
    }

    override suspend fun onTimeout() {
        flushLog()
    }

    // ---------- Helfer ----------

    private suspend fun refresh(event: GenericComponentInteractionCreateEvent, note: String? = null) {
        (event as ButtonInteractionEvent).editMessageEmbeds(buildEmbed(note)).setComponents(components()).await()
    }
}
