/* Embeds für Minecraft-Ereignisse vom CoreSMP-Plugin (plus Beispiele für die /dc-Vorschau). */
package de.amongusmanager.crewbot.ui

import de.amongusmanager.crewbot.content.DEATH_CAUSES
import de.amongusmanager.crewbot.content.DEFAULT_DEATH
import de.amongusmanager.crewbot.content.DIMENSIONS
import de.amongusmanager.crewbot.content.MOBS
import de.amongusmanager.crewbot.content.McEventType
import de.amongusmanager.crewbot.content.abilityName
import de.amongusmanager.crewbot.content.pyTitle
import de.amongusmanager.crewbot.core.pyLong
import de.amongusmanager.crewbot.services.McEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.Instant

private val LEAVE_COLOR = Palette.DARK_GREY
private val DEATH_COLOR = Palette.DARK_RED
private val FIRST_JOIN_COLOR = Palette.TEAL

private val LEAVE_TEXTS = mapOf(
    "kicked" to "🥾 {name} wurde vom Server geworfen",
    "timed_out" to "📡 {name} hat die Verbindung verloren",
    "stop" to "🛑 {name} ist raus – der Server wurde gestoppt",
)

// Köpfe per Name statt UUID: klappt auch auf Offline-Mode-Servern
fun headUrl(player: String): String = "https://mc-heads.net/avatar/${urlQuote(player)}/64"

fun bodyUrl(player: String): String = "https://mc-heads.net/body/${urlQuote(player)}/128"

private fun bold(text: String): String = "**${escapeMarkdown(text)}**"

private fun duration(seconds: Long): String {
    val hours = Math.floorDiv(seconds, 3600L)
    val minutes = Math.floorMod(seconds, 3600L) / 60
    if (hours != 0L) return "$hours Std. $minutes Min."
    return if (minutes != 0L) "$minutes Min." else "unter 1 Min."
}

private fun online(event: McEvent): String? {
    val online = event.text("online") ?: return null
    return if (event.truthy("max")) "🟢 $online/${event.text("max")} online" else "🟢 $online online"
}

/** Der Schuldige im Dativ: Spieler/benannte Mobs mit Namen, sonst "einem **Zombie**". */
fun killerText(event: McEvent): String? {
    if (event.truthy("killer")) return bold(event.text("killer")!!)
    if (!event.truthy("killerType")) return null
    val killerType = event.text("killerType")!!
    val (article, name) = MOBS[killerType] ?: ("" to pyTitle(killerType.replace("_", " ")))
    return "$article ${bold(name)}".trim()
}

// ==========================================
// EINZELNE ARTEN
// ==========================================

private typealias Built = Pair<EmbedBuilder, List<String?>>

private fun join(event: McEvent): Built {
    val name = bold(event.player)
    val embed = if (event.truthy("first")) {
        EmbedBuilder()
            .setTitle("🎉 Neu auf dem Server!")
            .setDescription("$name ist zum **ersten Mal** da – herzlich willkommen!")
            .setColor(FIRST_JOIN_COLOR)
            .setThumbnail(bodyUrl(event.player))
    } else {
        EmbedBuilder().setDescription("📥 $name hat den Server betreten").setColor(Palette.SUCCESS)
    }
    return embed to listOf(online(event))
}

private fun leave(event: McEvent): Built {
    val reason = event.text("reason")
    val text = LEAVE_TEXTS[reason] ?: "📤 {name} hat den Server verlassen"
    val embed = EmbedBuilder().setDescription(text.replace("{name}", bold(event.player))).setColor(LEAVE_COLOR)
    val playtime = event.get("playtime")
    val footer = mutableListOf(if (playtime != null) "⏱️ ${duration(playtime.pyLong())} online" else null)
    if (reason != "stop") footer.add(online(event))
    return embed to footer
}

private fun death(event: McEvent): Built {
    val causeKey = event.get("cause")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    val known = causeKey != null && causeKey in DEATH_CAUSES
    val cause = (if (causeKey != null) DEATH_CAUSES[causeKey] else null) ?: DEFAULT_DEATH
    val killer = killerText(event)
    val text = when {
        killer == null -> cause.text
        cause.byKiller != null -> cause.byKiller.replace("{killer}", killer)
        else -> "${cause.text} – dank $killer"
    }

    var description = "${cause.emoji} ${bold(event.player)} $text"
    if (!known && killer == null && event.truthy("message")) {
        description += "\n> *${escapeMarkdown(event.text("message")!!)}*" // Originaltext von Minecraft
    }
    val embed = EmbedBuilder().setDescription(description).setColor(DEATH_COLOR)
    if (event.truthy("weapon")) embed.field("✨ Special Item", bold(event.text("weapon")!!))
    val dimension = event.get("dimension")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return embed to listOf(dimension?.let { DIMENSIONS[it] })
}

private fun craft(event: McEvent): Built {
    val item = when {
        event.truthy("item") -> event.text("item")!!
        event.truthy("itemKey") -> event.text("itemKey")!!
        else -> "?"
    }
    val embed = EmbedBuilder()
        .setTitle("✨ Special Item gecraftet!")
        .setDescription(
            "${bold(event.player)} hat ${bold(item)} gecraftet!\n" +
                "Davon gibt es nur **ein einziges Exemplar** auf dem Server."
        )
        .setColor(Palette.GOLD)
    if (event.truthy("ability")) embed.field("🔮 Fähigkeit", abilityName(event.text("ability")!!))
    embed.setThumbnail(bodyUrl(event.player))
    return embed to emptyList()
}

fun buildMinecraftEmbed(event: McEvent, server: String, `when`: Instant? = null): MessageEmbed {
    val (embed, footer) = when (event.type) {
        McEventType.JOIN -> join(event)
        McEventType.LEAVE -> leave(event)
        McEventType.DEATH -> death(event)
        McEventType.CRAFT -> craft(event)
    }
    embed.setAuthor(event.player, null, headUrl(event.player))
    embed.setFooter((footer + "⛏️ $server").filter { !it.isNullOrEmpty() }.joinToString(" • "))
    embed.setTimestamp(`when` ?: Instant.now())
    return safeBuild(embed)
}

/** Beispiele für die Vorschau in /dc. */
fun sampleMinecraftEvents(): List<McEvent> {
    fun data(vararg pairs: Pair<String, Any>): Map<String, JsonElement> = pairs.associate { (key, value) ->
        key to when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
    return listOf(
        McEvent(McEventType.JOIN, "Steve", data("online" to 3, "max" to 20)),
        McEvent(McEventType.JOIN, "Alex", data("first" to true, "online" to 4, "max" to 20)),
        McEvent(McEventType.LEAVE, "Steve", data("reason" to "disconnected", "playtime" to 4380, "online" to 3, "max" to 20)),
        McEvent(
            McEventType.DEATH, "Alex",
            data(
                "cause" to "player_attack", "killerType" to "player", "killer" to "Steve", "weapon" to "Lichtschwert",
                "dimension" to "normal",
            ),
        ),
        McEvent(McEventType.DEATH, "Steve", data("cause" to "explosion", "killerType" to "creeper", "dimension" to "normal")),
        McEvent(McEventType.CRAFT, "Steve", data("item" to "Lichtschwert", "itemKey" to "lichtschwert", "ability" to "lichtschwert")),
    )
}
