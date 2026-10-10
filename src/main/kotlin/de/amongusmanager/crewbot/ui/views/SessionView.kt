/*
 * Basis für alle Menüs mit Buttons/Auswahlfeldern, die nur im Speicher leben (wie discord.py-Views
 * mit Timeout). Dauerhafte Buttons alter Nachrichten (Control Panel, Events, Rollen, „Zurücksetzen“)
 * laufen dagegen über feste custom_ids im ComponentRouter.
 *
 * custom_id-Format: "cv:<View-ID>:<Name>" – der Name ist pro Button fest, damit ein schneller zweiter
 * Klick auf denselben Button auch nach dem Neuzeichnen noch funktioniert.
 */
package de.amongusmanager.crewbot.ui.views

import de.amongusmanager.crewbot.core.BotThread
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.notify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

typealias ComponentHandler = suspend (GenericComponentInteractionCreateEvent) -> Unit

abstract class SessionView(
    /** Nur diese Person darf das Menü bedienen (null = alle). */
    private val ownerId: Long?,
    /** Nach so langer Zeit ohne Klick läuft das Menü ab (null = nie). */
    private val timeout: Duration?,
    /** Klicks verlängern den Timeout (wie in discord.py). false = feste Endzeit. */
    private val resetTimeoutOnUse: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val id: String = ViewRegistry.nextId()
    private val handlers = HashMap<String, ComponentHandler>()
    private var timeoutJob: Job? = null
    private val finished = CompletableDeferred<Unit>()
    private var registered = false

    var isFinished: Boolean = false
        private set

    /** Baut die Komponenten für den aktuellen Zustand (Handler werden dabei neu registriert). */
    protected abstract fun render(): List<ActionRow>

    /** Wird aufgerufen, wenn das Menü abläuft (nicht bei stop()). */
    protected open suspend fun onTimeout() {}

    /** Komponenten zum Senden/Bearbeiten der Nachricht. Meldet das Menü beim ersten Mal an. */
    fun components(): List<ActionRow> {
        handlers.clear()
        val rows = render()
        if (!registered && !isFinished) {
            registered = true
            ViewRegistry.register(this)
            restartTimeout()
        }
        return rows
    }

    /** Menü beenden: keine Klicks mehr, kein onTimeout. */
    fun stop() {
        finish()
        timeoutJob?.cancel()
    }

    /** Wartet, bis das Menü beendet ist oder abläuft. */
    suspend fun await() {
        finished.await()
    }

    // ---------- Bausteine ----------

    protected fun key(name: String, handler: ComponentHandler): String {
        handlers[name] = handler
        return "cv:$id:$name"
    }

    protected fun button(
        name: String,
        style: ButtonStyle,
        label: String,
        emoji: String? = null,
        disabled: Boolean = false,
        handler: ComponentHandler,
    ): Button {
        var button = Button.of(style, key(name, handler), label)
        if (emoji != null) button = button.withEmoji(Emoji.fromUnicode(emoji))
        return button.withDisabled(disabled)
    }

    // ---------- intern ----------

    internal suspend fun handle(event: GenericComponentInteractionCreateEvent, name: String) {
        if (ownerId != null && event.user.idLong != ownerId) {
            event.notify("🚫 Das ist nicht dein Menü.", Tone.ERROR)
            return
        }
        val handler = handlers[name]
        if (handler == null) {
            event.notify("⌛ Dieses Menü ist veraltet. Bitte nutze die aktuelle Nachricht.", Tone.WARNING)
            return
        }
        if (resetTimeoutOnUse) restartTimeout()
        try {
            handler(event)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.error("Fehler in {}", javaClass.simpleName, e)
            try {
                event.notify("❌ Da ist etwas schiefgelaufen. Versuch es nochmal!", Tone.ERROR)
            } catch (inner: Exception) {
                inner.rethrowIfCancelled()
            }
        }
    }

    private fun restartTimeout() {
        val duration = timeout ?: return
        timeoutJob?.cancel()
        timeoutJob = BotThread.scope.launch {
            delay(duration.toMillis())
            if (isFinished) return@launch
            finish()
            try {
                onTimeout()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                log.warn("Fehler beim Ablauf von {}", javaClass.simpleName, e)
            }
        }
    }

    private fun finish() {
        if (isFinished) return
        isFinished = true
        ViewRegistry.unregister(this)
        finished.complete(Unit)
    }
}

/** Alle aktiven Menüs (nur vom Bot-Thread benutzt). */
object ViewRegistry {
    private const val PREFIX = "cv:"
    private val counter = AtomicLong(System.currentTimeMillis() % 1_000_000)
    private val views = HashMap<String, SessionView>()

    fun nextId(): String = counter.incrementAndGet().toString(36)

    fun register(view: SessionView) {
        views[view.id] = view
    }

    fun unregister(view: SessionView) {
        views.remove(view.id)
    }

    /** true, wenn die custom_id zu einem (aktiven oder abgelaufenen) Menü gehört. */
    suspend fun dispatch(event: GenericComponentInteractionCreateEvent): Boolean {
        val customId = event.componentId
        if (!customId.startsWith(PREFIX)) return false
        val parts = customId.split(":", limit = 3)
        val view = views[parts.getOrNull(1)]
        if (view == null || parts.size < 3) {
            event.notify("⌛ Dieses Menü ist abgelaufen. Führe den Befehl einfach nochmal aus.", Tone.WARNING)
            return true
        }
        view.handle(event, parts[2])
        return true
    }
}
