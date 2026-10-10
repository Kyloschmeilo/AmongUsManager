/*
 * Worker-Bots: zusätzliche Bot-Accounts, die das Muten parallel übernehmen.
 *
 * Discord begrenzt, wie schnell ein einzelner Bot Mitglieder bearbeiten darf.
 * Mit mehreren Bots werden die Mutes gleichmäßig verteilt und laufen parallel.
 */
package de.amongusmanager.crewbot.services

import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.Route
import net.dv8tion.jda.api.utils.data.DataObject
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** Gewünschter Server-Mute/-Taub eines Mitglieds (null = nicht ändern). */
data class VoiceChange(val member: Member, val mute: Boolean? = null, val deafen: Boolean? = null)

class WorkerManager(
    private val mainJda: () -> JDA,
    private val tokens: List<String>,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(WorkerManager::class.java)
    private val workers = CopyOnWriteArrayList<JDA>()

    /** Haupt-Bot + alle Worker, die gerade online sind. */
    val readyClients: List<JDA>
        get() = listOf(mainJda()) + workers.filter { it.status == JDA.Status.CONNECTED }

    fun start() {
        log.info("🔍 Starte {} Worker-Bot(s)...", tokens.size)
        tokens.forEachIndexed { index, token ->
            val number = index + 1
            scope.launch {
                try {
                    // build() prüft den Token per Anfrage – nicht auf dem Bot-Thread warten
                    val worker = withContext(Dispatchers.IO) {
                        JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
                            .addEventListeners(object : ListenerAdapter() {
                                override fun onReady(event: ReadyEvent) {
                                    log.info("🤖 Worker #{} online als {}", number, event.jda.selfUser.name)
                                }
                            })
                            .build()
                    }
                    workers.add(worker)
                } catch (e: InvalidTokenException) {
                    log.error("❌ Worker #{}: Ungültiger Token!", number)
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                    log.error("❌ Worker #{} ist abgestürzt", number, e)
                }
            }
        }
    }

    fun close() {
        for (worker in workers) worker.shutdown()
    }

    /** Mutet/entmutet die Mitglieder, verteilt auf alle verfügbaren Bots. */
    suspend fun setMute(members: List<Member>, muted: Boolean): ActionReport =
        editVoice(members.map { VoiceChange(it, mute = muted) })

    /** Hebt Server-Mute und -Taub auf (nur bei denen, die es gerade haben). */
    suspend fun release(members: List<Member>): ActionReport {
        val changes = members.mapNotNull { member ->
            val voice = member.voiceState
            if (voice == null || voice.channel == null) return@mapNotNull null
            val change = VoiceChange(
                member,
                mute = if (voice.isGuildMuted) false else null,
                deafen = if (voice.isGuildDeafened) false else null,
            )
            if (change.mute == null && change.deafen == null) null else change
        }
        return editVoice(changes)
    }

    /** Setzt mute/deafen pro Mitglied – ein Aufruf pro Person, reihum auf alle Bots verteilt. */
    suspend fun editVoice(changes: List<VoiceChange>): ActionReport {
        if (changes.isEmpty()) return ActionReport(0, 0)
        val clients = readyClients
        val results = coroutineScope {
            changes.mapIndexed { index, change ->
                async {
                    try {
                        editOne(clients[index % clients.size], change)
                        true
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                        log.debug("Voice-Änderung für {} fehlgeschlagen: {}", change.member.effectiveName, e.describe())
                        false
                    }
                }
            }.awaitAll()
        }
        return ActionReport.fromResults(results)
    }

    /**
     * Eine Anfrage mit mute und deaf zusammen (wie `member.edit(mute=…, deafen=…)` im alten Bot).
     * Läuft über die Verbindung des jeweiligen Bots, damit jeder sein eigenes Rate-Limit nutzt.
     * Bewusst ohne JDAs Cache-Abkürzung: Der Cache eines Workers kann kurz veraltet sein.
     */
    private suspend fun editOne(client: JDA, change: VoiceChange) {
        val guildId = change.member.guild.idLong
        val jda = if (client.getGuildById(guildId) != null) client else mainJda()
        editMemberVoice(jda, guildId, change.member.idLong, change.mute, change.deafen)
    }
}

/** PATCH /guilds/{guild}/members/{user} mit mute/deaf in einer einzigen Anfrage. */
suspend fun editMemberVoice(jda: JDA, guildId: Long, userId: Long, mute: Boolean?, deafen: Boolean?) {
    val body = DataObject.empty()
    if (mute != null) body.put("mute", mute)
    if (deafen != null) body.put("deaf", deafen)
    val route = Route.Guilds.MODIFY_MEMBER.compile(guildId.toString(), userId.toString())
    AuditableRestActionImpl<Void>(jda, route, body).await()
}
