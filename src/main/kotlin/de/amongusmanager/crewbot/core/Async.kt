/*
 * Nebenläufigkeit: Die gesamte Bot-Logik läuft auf EINEM Thread (wie asyncio im alten Python-Bot).
 * Handler können während Discord-Anfragen pausieren (suspend), aber nie gleichzeitig Daten ändern.
 */
package de.amongusmanager.crewbot.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.RestAction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger("crewbot")

object BotThread {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "crewbot") }
    val dispatcher = executor.asCoroutineDispatcher()

    /** Scope für alles, was der Bot tut. Ein Fehler in einem Handler stoppt nie den ganzen Bot. */
    val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error ->
            log.error("Unbehandelter Fehler", error)
        }
    )

    /** Keine neuen Aufgaben mehr annehmen (laufende dürfen fertig werden). */
    fun shutdown() {
        executor.shutdown()
    }

    /** Wartet, bis die laufende Aufgabe fertig ist (höchstens [timeout]). */
    fun awaitIdle(timeout: Long, unit: java.util.concurrent.TimeUnit) {
        executor.awaitTermination(timeout, unit)
    }
}

/** Wartet auf eine Discord-Anfrage, ohne den Bot-Thread zu blockieren. */
suspend fun <T> RestAction<T>.await(): T = submit().await()

/** Coroutine-Abbrüche dürfen nie von `catch (e: Exception)` verschluckt werden. */
fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}

/** 403: fehlende Rechte (lokal von JDA erkannt oder von Discord abgelehnt). */
fun Throwable.isForbidden(): Boolean =
    this is PermissionException || (this is ErrorResponseException && (
        errorResponse == ErrorResponse.MISSING_PERMISSIONS || errorResponse == ErrorResponse.MISSING_ACCESS ||
            response.code == 403
        ))

/** 404: Nachricht, Channel, Webhook o.ä. existiert nicht mehr. */
fun Throwable.isNotFound(): Boolean =
    this is ErrorResponseException && (
        errorResponse in setOf(
            ErrorResponse.UNKNOWN_MESSAGE,
            ErrorResponse.UNKNOWN_CHANNEL,
            ErrorResponse.UNKNOWN_WEBHOOK,
            ErrorResponse.UNKNOWN_MEMBER,
            ErrorResponse.UNKNOWN_USER,
            ErrorResponse.UNKNOWN_GUILD,
        ) || response.code == 404
        )

/** Kurzbeschreibung eines Fehlers für Log und Anzeige. */
fun Throwable.describe(): String = when (this) {
    is ErrorResponseException -> "${errorResponse.name} (${meaning})"
    else -> message ?: javaClass.simpleName
}

/** `<t:…:R>` usw. – Discord zeigt die Zeit in der Zeitzone des Lesers. */
fun Instant.discordTimestamp(style: String): String = "<t:$epochSecond:$style>"
