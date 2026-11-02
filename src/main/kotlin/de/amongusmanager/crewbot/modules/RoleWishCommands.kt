/*
 * Rollenwünsche (/rollenwunsch), Admin-Zuweisungen (/rolle-zuweisen) und der Austausch mit der Mod.
 * Rollen von The Other Roles gehen über eigene Befehle (/special-rollenwunsch, /special-rolle-zuweisen, mit
 * Vorschlägen beim Tippen statt fester Auswahl – es sind mehr als Discords 25); Anzeigen/Zurückziehen bleibt bei
 * /rollenwunsch bzw. /rolle-zuweisen, weil jeder Spieler nur einen offenen Wunsch hat.
 * Gehört zum Mod-Modul (erscheint dort in /hilfe); die Logik steckt in services/RoleWishes.kt.
 */
package de.amongusmanager.crewbot.modules

import de.amongusmanager.crewbot.Rules
import de.amongusmanager.crewbot.content.TOR_WISH_ROLES
import de.amongusmanager.crewbot.content.WISH_ROLES
import de.amongusmanager.crewbot.content.WishRole
import de.amongusmanager.crewbot.content.torWishTeam
import de.amongusmanager.crewbot.content.wishRole
import de.amongusmanager.crewbot.core.CommandError
import de.amongusmanager.crewbot.core.CrewBot
import de.amongusmanager.crewbot.core.SlashCommand
import de.amongusmanager.crewbot.core.author
import de.amongusmanager.crewbot.core.await
import de.amongusmanager.crewbot.core.describe
import de.amongusmanager.crewbot.core.memberOption
import de.amongusmanager.crewbot.core.rethrowIfCancelled
import de.amongusmanager.crewbot.core.stringOption
import de.amongusmanager.crewbot.services.Actor
import de.amongusmanager.crewbot.services.RoleWishError
import de.amongusmanager.crewbot.services.WishMessage
import de.amongusmanager.crewbot.services.WishProtocol
import de.amongusmanager.crewbot.services.WishProtocolError
import de.amongusmanager.crewbot.ui.Palette
import de.amongusmanager.crewbot.ui.ROLE_WISH_RULES
import de.amongusmanager.crewbot.ui.Tone
import de.amongusmanager.crewbot.ui.buildWishOverviewEmbed
import de.amongusmanager.crewbot.ui.buildWishStatusEmbed
import de.amongusmanager.crewbot.ui.notify
import de.amongusmanager.crewbot.ui.simpleEmbed
import de.amongusmanager.crewbot.ui.wishFailureText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory

private const val WITHDRAW = "zurueckziehen"
private const val REMOVE = "entfernen"
private const val MAX_SUGGESTIONS = 25 // Discord-Grenze für Vorschläge
private const val REQUEST_CLEANUP_MS = 30_000L // die Mod löscht ihre Anfrage selbst – sonst räumt der Bot danach auf

/** Hinweis bei Special-Rollen: welche Rollen es gibt, entscheidet The Other Roles. */
private const val TOR_WISH_NOTE =
    "🎲 Special-Rollen klappen nur, wenn die Rolle in der Runde vorkommt (Einstellungen von The Other Roles beim Host)" +
        " – sonst gibt es die XP zurück."

class RoleWishCommands(private val bot: CrewBot) {
    private val log = LoggerFactory.getLogger(RoleWishCommands::class.java)
    private var webhookToken: Pair<Long, String>? = null // Token des Mod-Webhooks (wird nicht gespeichert)
    private var expiryStarted = false

    private val roleChoices = WISH_ROLES.map { Command.Choice(it.label, it.gameName) }

    val commands = listOf(
        SlashCommand(
            "rollenwunsch",
            "Wünsch dir eine Rolle für deine nächste Among-Us-Runde" +
                " (${Rules.ROLE_WISH_COST} XP, ${Rules.ROLE_WISHES_PER_EVENING}× pro Spieleabend)",
            options = listOf(
                OptionData(OptionType.STRING, "rolle", "Welche Rolle? (leer = deinen Wunsch anzeigen)", false)
                    .addChoices(roleChoices + Command.Choice("❌ Wunsch zurückziehen (XP zurück)", WITHDRAW)),
            ),
        ) { event -> onWish(event) },
        SlashCommand(
            "rolle-zuweisen", "Legt fest, welche Rolle ein Spieler in der nächsten Among-Us-Runde bekommt",
            Permission.ADMINISTRATOR,
            options = listOf(
                OptionData(OptionType.USER, "spieler", "Wer? (leer = Übersicht aller Wünsche und Zuweisungen)", false),
                OptionData(OptionType.STRING, "rolle", "Welche Rolle? (leer = Stand des Spielers anzeigen)", false)
                    .addChoices(roleChoices + Command.Choice("❌ Wunsch/Zuweisung entfernen", REMOVE)),
            ),
        ) { event -> onAssign(event) },
        SlashCommand(
            "special-rollenwunsch",
            "Wünsch dir eine Special-Rolle (The Other Roles) für deine nächste Runde" +
                " (${Rules.ROLE_WISH_COST} XP, ${Rules.ROLE_WISHES_PER_EVENING}× pro Spieleabend)",
            options = listOf(
                OptionData(OptionType.STRING, "rolle", "Welche Special-Rolle? (tippen zum Suchen)", true).setAutoComplete(true),
            ),
            autocomplete = ::suggestTorRoles,
        ) { event -> onWish(event, torChoice(event)) },
        SlashCommand(
            "special-rolle-zuweisen", "Legt fest, welche Special-Rolle (The Other Roles) ein Spieler in der nächsten Runde bekommt",
            Permission.ADMINISTRATOR,
            options = listOf(
                OptionData(OptionType.USER, "spieler", "Wer?", true),
                OptionData(OptionType.STRING, "rolle", "Welche Special-Rolle? (tippen zum Suchen)", true).setAutoComplete(true),
            ),
            autocomplete = ::suggestTorRoles,
        ) { event -> onAssign(event, torChoice(event)) },
    )

    // ==========================================
    // BEFEHLE
    // ==========================================

    /** Vorschläge für /special-…: Name enthält den getippten Text (Groß-/Kleinschreibung egal). */
    private suspend fun suggestTorRoles(event: CommandAutoCompleteInteractionEvent) {
        if (event.focusedOption.name != "rolle") return
        val query = event.focusedOption.value.trim().lowercase()
        val matches = TOR_WISH_ROLES
            .filter { query.isEmpty() || query in it.name.lowercase() }
            .sortedBy { if (it.name.lowercase().startsWith(query)) 0 else 1 }
            .take(MAX_SUGGESTIONS)
        event.replyChoices(matches.map { Command.Choice("${it.label} · ${torWishTeam(it)}", it.gameName) }).await()
    }

    /** Die gewählte TOR-Rolle – auch wenn jemand den Namen nur eintippt, ohne einen Vorschlag anzuklicken. */
    private fun torChoice(event: SlashCommandInteractionEvent): String {
        val text = event.stringOption("rolle")?.trim().orEmpty()
        val role: WishRole? = TOR_WISH_ROLES.firstOrNull { it.gameName == text }
            ?: TOR_WISH_ROLES.firstOrNull { it.name.equals(text, ignoreCase = true) || it.label.equals(text, ignoreCase = true) }
        return role?.gameName ?: throw CommandError(
            "❌ Unbekannte Special-Rolle **${text.take(50)}** – wähl eine aus den Vorschlägen beim Tippen." +
                " Modifikatoren (z.B. Lovers, Mini) kann man sich nicht wünschen."
        )
    }

    private suspend fun onWish(event: SlashCommandInteractionEvent, special: String? = null) {
        val userId = event.author.idLong
        val wishes = bot.roleWishes
        try {
            when (val choice = special ?: event.stringOption("rolle")) {
                null -> {
                    wishes.expireOld()
                    val current = wishes.pendingOf(userId)
                    val embed = buildWishStatusEmbed(
                        current,
                        reserved = current != null && wishes.isReserved(current),
                        usedTonight = wishes.usedTonight(userId),
                        balance = bot.levels.get(userId).xp,
                        linked = bot.links.codesOf(userId).isNotEmpty(),
                    )
                    event.replyEmbeds(embed).setEphemeral(true).await()
                }
                WITHDRAW -> {
                    val withdrawn = wishes.withdraw(userId)
                    event.notify(
                        "↩️ Wunsch **${withdrawn.label}** zurückgezogen – deine ${withdrawn.cost} XP sind wieder da" +
                            " (Guthaben: ${bot.levels.get(userId).xp} XP).",
                        Tone.SUCCESS,
                    )
                }
                else -> {
                    val role = wishRole(choice) ?: throw RoleWishError("❌ Unbekannte Rolle.")
                    val change = wishes.wish(userId, role)
                    val previous = change.previous
                    val text = if (previous != null) {
                        "🔄 Wunsch geändert: ~~${wishRole(previous)?.label ?: previous}~~ → **${role.label}** (kostenlos)."
                    } else {
                        "✅ Gewünscht: **${role.label}** für deine nächste Runde.\n" +
                            "−${Rules.ROLE_WISH_COST} XP (Guthaben: ${change.balance} XP) •" +
                            " Wünsche heute: ${change.usedTonight}/${Rules.ROLE_WISHES_PER_EVENING}"
                    }
                    val torNote = if (role.isTor) "\n\n$TOR_WISH_NOTE" else ""
                    event.notify("$text$torNote\n\n$ROLE_WISH_RULES", Tone.SUCCESS)
                }
            }
        } catch (e: RoleWishError) {
            event.notify(e.message ?: "❌ Das geht gerade nicht.", Tone.ERROR)
        }
    }

    private suspend fun onAssign(event: SlashCommandInteractionEvent, special: String? = null) {
        val admin = event.author
        val wishes = bot.roleWishes
        val target = event.memberOption("spieler")
        val choice = special ?: event.stringOption("rolle")
        try {
            wishes.expireOld()
            if (target == null) {
                if (choice != null) throw RoleWishError("❌ Bitte auch einen Spieler auswählen.")
                val embed = buildWishOverviewEmbed(wishes.pending(), wishes::isReserved, wishes.currentEvening)
                event.replyEmbeds(embed).setEphemeral(true).await()
                return
            }
            if (target.user.isBot) throw RoleWishError("🤖 Bots spielen kein Among Us.")

            when (choice) {
                null -> {
                    val current = wishes.pendingOf(target.idLong)
                    val text = when {
                        current == null ->
                            "ℹ️ ${target.asMention} hat für die nächste Runde weder einen Wunsch noch eine zugewiesene Rolle."
                        current.isAdmin ->
                            "🛡️ ${target.asMention} bekommt in der nächsten Runde **${current.label}**" +
                                " (zugewiesen von <@${current.createdBy}>)."
                        else -> "🎟️ ${target.asMention} wünscht sich **${current.label}** (${current.cost} XP bezahlt)."
                    }
                    event.notify(text, Tone.INFO)
                }
                REMOVE -> {
                    val removed = wishes.unassign(target.idLong)
                    val refund = if (removed.cost > 0) " – die ${removed.cost} XP sind erstattet" else ""
                    event.notify("🗑️ **${removed.label}** für ${target.asMention} entfernt$refund.", Tone.SUCCESS)
                    bot.audit.log(
                        Actor.of(admin),
                        "🎭 Rolle entfernt",
                        "${target.asMention}: ${if (removed.isAdmin) "Zuweisung" else "Wunsch"} **${removed.label}** entfernt$refund",
                    )
                    if (removed.cost > 0) {
                        dm(event.jda, target.idLong, "↩️ Ein Admin hat deinen Rollenwunsch **${removed.label}** entfernt – du bekommst deine **${removed.cost} XP** zurück.")
                    }
                }
                else -> {
                    val role = wishRole(choice) ?: throw RoleWishError("❌ Unbekannte Rolle.")
                    val result = wishes.assign(admin.idLong, target.idLong, role)
                    val replaced = result.replaced
                    val note = when {
                        replaced == null -> ""
                        replaced.cost > 0 -> "\n↩️ Der Wunsch **${replaced.label}** wurde ersetzt, die ${replaced.cost} XP sind erstattet."
                        else -> "\n(Vorher zugewiesen: ${replaced.label})"
                    }
                    event.notify(
                        "✅ ${target.asMention} bekommt in der nächsten Runde **${role.label}**.$note\n\n" +
                            "Gilt für die nächste Runde der Person, wenn der Host die CrewStats-Mod hat." +
                            (if (role.isTor) "\n$TOR_WISH_NOTE" else ""),
                        Tone.SUCCESS,
                    )
                    bot.audit.log(Actor.of(admin), "🎭 Rolle zugewiesen", "${target.asMention} → **${role.label}** (nächste Runde)$note")
                    if (replaced != null && replaced.cost > 0) {
                        dm(event.jda, target.idLong, "↩️ Ein Admin hat deinen Rollenwunsch **${replaced.label}** ersetzt – du bekommst deine **${replaced.cost} XP** zurück.")
                    }
                }
            }
        } catch (e: RoleWishError) {
            event.notify(e.message ?: "❌ Das geht gerade nicht.", Tone.ERROR)
        }
    }

    // ==========================================
    // AUSTAUSCH MIT DER MOD
    // ==========================================

    /** Anfrage oder Rückmeldung der Mod (kam über den Mod-Webhook, `fresh` = höchstens 60 s alt). */
    suspend fun onModMessage(message: Message, webhookId: Long, wish: WishMessage, fresh: Boolean) {
        val token = tokenOf(message.jda, webhookId)
        if (token == null) {
            delete(message)
            return
        }
        try {
            val plaintext = WishProtocol.open(wish, token)
            if (wish.kind == WishProtocol.REQUEST) answer(message, webhookId, token, plaintext, fresh) else applyResults(message, plaintext)
        } catch (e: WishProtocolError) {
            log.warn("Rollenwünsche: ungültige Nachricht der Mod ({})", e.message)
            delete(message)
        }
    }

    /** Wünsche der Spieler in der Lobby heraussuchen und als Bearbeitung der Anfrage zurückschicken. */
    private suspend fun answer(message: Message, webhookId: Long, token: String, plaintext: String, fresh: Boolean) {
        if (!fresh) {
            delete(message) // z.B. während eines Neustarts liegen geblieben – die Mod wartet nicht mehr
            return
        }
        val players = WishProtocol.requestPlayers(plaintext)
        val reserved = bot.roleWishes.reserve(players)
        val (content, sent) = WishProtocol.reply(reserved, token)
        val dropped = reserved.filter { it !in sent }
        if (dropped.isNotEmpty()) bot.roleWishes.release(dropped.map { it.id })
        try {
            WebhookClient.createClient(message.jda, webhookId.toString(), token).editMessageById(message.idLong, content).await()
            log.info("Rollenwünsche an die Mod geschickt: {} für {} Spieler", sent.size, players.size)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Rollenwünsche: Antwort an die Mod fehlgeschlagen: {}", e.describe())
            bot.roleWishes.release(sent.map { it.id })
        }
        bot.scope.launch {
            delay(REQUEST_CLEANUP_MS)
            delete(message)
        }
    }

    /** Rückmeldung nach der Rollenvergabe: Erfülltes verbuchen, Unmögliches erstatten (+ DM an den Spieler). */
    private suspend fun applyResults(message: Message, plaintext: String) {
        val results = bot.roleWishes.applyResults(WishProtocol.outcomes(plaintext))
        delete(message)
        if (results.applied.isEmpty() && results.refunded.isEmpty()) return
        log.info("Rollenwünsche angewendet: {} erfüllt, {} nicht möglich", results.applied.size, results.refunded.size)

        for (wish in results.refunded) {
            if (wish.cost <= 0) continue
            dm(
                message.jda, wish.userId,
                "↩️ Dein Rollenwunsch **${wish.label}** hat in der Runde nicht geklappt – ${wishFailureText(wish.reason, wish.role?.isTor == true)}." +
                    " Du bekommst deine **${wish.cost} XP** zurück.",
            )
        }

        // Im Log nur Anzahlen – wer sich was gewünscht hat, bleibt geheim. Admin-Zuweisungen dürfen Admins sehen.
        val lines = ArrayList<String>()
        lines += "✅ Erfüllt: ${results.applied.count { !it.isAdmin }} Wünsche, ${results.applied.count { it.isAdmin }} Zuweisungen"
        val failedWishes = results.refunded.count { !it.isAdmin }
        if (failedWishes > 0) lines += "↩️ Nicht möglich: $failedWishes Wünsche (XP erstattet)"
        for (entry in results.refunded.filter { it.isAdmin }) {
            lines += "⚠️ Zuweisung nicht möglich: <@${entry.userId}> → ${entry.label} (${wishFailureText(entry.reason, entry.role?.isTor == true)})"
        }
        bot.audit.log(Actor.of(message.jda.selfUser), "🎭 Rollenwünsche angewendet", lines.joinToString("\n"))
    }

    /** Offene Wünsche vergangener Spieleabende regelmäßig erstatten. */
    fun startExpiry() {
        if (expiryStarted) return
        expiryStarted = true
        bot.scope.launch {
            while (isActive) {
                try {
                    val expired = bot.roleWishes.expireOld()
                    if (expired.isNotEmpty()) log.info("{} offene Rollenwünsche verfallen (Spieleabend vorbei)", expired.size)
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                    log.error("Rollenwünsche: Verfallen lassen fehlgeschlagen", e)
                }
                delay(60_000)
            }
        }
    }

    // ---------- intern ----------

    /** Token des Mod-Webhooks (Schlüssel für die Verschlüsselung und zum Bearbeiten der Anfrage). */
    private suspend fun tokenOf(jda: JDA, webhookId: Long): String? {
        webhookToken?.let { (id, token) -> if (id == webhookId) return token }
        val token = try {
            jda.retrieveWebhookById(webhookId).await().token
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            log.warn("Rollenwünsche: Webhook nicht abrufbar ({}) – fehlt das Recht „Webhooks verwalten“?", e.describe())
            null
        }
        if (token != null) webhookToken = webhookId to token
        return token
    }

    private suspend fun dm(jda: JDA, userId: Long, text: String) {
        try {
            val user = jda.retrieveUserById(userId).await()
            user.openPrivateChannel().await().sendMessageEmbeds(simpleEmbed(text, Palette.WARNING)).await()
        } catch (e: Exception) {
            e.rethrowIfCancelled() // DMs geschlossen o.ä. – nicht schlimm, die XP sind trotzdem zurück
        }
    }

    private suspend fun delete(message: Message) {
        try {
            message.delete().await()
        } catch (e: Exception) {
            e.rethrowIfCancelled() // schon gelöscht (macht die Mod selbst) oder fehlendes Recht
        }
    }
}
