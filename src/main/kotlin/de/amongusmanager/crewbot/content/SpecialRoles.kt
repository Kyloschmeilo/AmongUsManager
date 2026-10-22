/* Rollen der eigenen Rollen-Mod (30 Spezialrollen + Modifikatoren) für /special-rolle. */
package de.amongusmanager.crewbot.content

/** Neutrale gewinnen allein; Modifikatoren kommen zusätzlich zu einer Rolle und zählen nicht zu den 30. */
enum class SpecialTeam(val label: String) {
    CREW("🟢 Crewmates"),
    IMPOSTOR("🔴 Impostor"),
    NEUTRAL("🟣 Neutral"),
    MODIFIER("🟡 Modifikator"),
}

data class SpecialRole(val name: String, val team: SpecialTeam, val emoji: String, val description: String)

val SPECIAL_ROLES: Map<String, SpecialRole> = listOf(
    // ---------- Crewmates ----------
    SpecialRole(
        "Sheriff", SpecialTeam.CREW, "🤠",
        "Du darfst einen Spieler erschießen. Triffst du einen Impostor, ist er raus – triffst du einen" +
            " Crewmate, stirbst du selbst. Schieß also nur, wenn du dir sicher bist.",
    ),
    SpecialRole(
        "Medic", SpecialTeam.CREW, "🩺",
        "Du gibst einem Spieler einen Schild, der einen Angriff abwehrt. Wird der Schild gebrochen, erfahrt ihr" +
            " es beide, und nach kurzer Zeit kannst du einen neuen vergeben.",
    ),
    SpecialRole(
        "Bürgermeister", SpecialTeam.CREW, "🎩",
        "Deine Stimme zählt im Meeting doppelt. Du kannst dich offen zeigen – dann bist du aber auch ein Ziel.",
    ),
    SpecialRole(
        "Snitch", SpecialTeam.CREW, "🔦",
        "Hast du alle Tasks erledigt, zeigen dir Pfeile, wo die Impostor sind. Kurz vorher sehen die Impostor" +
            " aber auch dich – beeil dich!",
    ),
    SpecialRole(
        "Hacker", SpecialTeam.CREW, "💻",
        "Du kannst für ein paar Sekunden die Admin-Karte von überall auf der Map sehen.",
    ),
    SpecialRole(
        "Spurenleser", SpecialTeam.CREW, "👣",
        "Du siehst für einige Sekunden die farbigen Fußspuren aller Spieler.",
    ),
    SpecialRole(
        "Fallensteller", SpecialTeam.CREW, "🪤",
        "Du stellst Fallen auf, die nur du siehst. Läuft jemand hinein, erfährst du, wer es war.",
    ),
    SpecialRole(
        "Medium", SpecialTeam.CREW, "🔮",
        "An einer frischen Leiche siehst du, aus welcher Richtung der Mörder kam.",
    ),
    SpecialRole(
        "Veteran", SpecialTeam.CREW, "🏅",
        "Du kannst für kurze Zeit wachsam werden. Wer dich in dieser Zeit angreift, stirbt selbst.",
    ),
    SpecialRole(
        "Nachtsicht", SpecialTeam.CREW, "🦉",
        "Bei einer Licht-Sabotage siehst du ganz normal weiter.",
    ),
    SpecialRole(
        "Altruist", SpecialTeam.CREW, "💖",
        "Du kannst eine Leiche wiederbeleben – stirbst dabei aber selbst.",
    ),
    SpecialRole(
        "Mechaniker", SpecialTeam.CREW, "🔩",
        "Du reparierst einmal pro Runde alle Sabotagen von überall auf der Map.",
    ),
    SpecialRole(
        "Transporter", SpecialTeam.CREW, "🔄",
        "Du markierst einen Spieler und tauschst ihn dann mit einem zweiten – beide landen am Platz des anderen.",
    ),
    SpecialRole(
        "Orakel", SpecialTeam.CREW, "🧿",
        "Du wählst einen Spieler. Stirbst du, erfahren alle, in welchem Team er ist – auch im Meeting an seinem Namen.",
    ),
    SpecialRole(
        "Kameramann", SpecialTeam.CREW, "🎥",
        "Du stellst zwei eigene Kameras irgendwo auf der Map auf und schaust durch sie hindurch. Nur du siehst sie.",
    ),
    SpecialRole(
        "Türwächter", SpecialTeam.CREW, "🚪",
        "Du schließt kurz alle Türen in deiner Nähe – ein Impostor sitzt dann fest.",
    ),
    SpecialRole(
        "Glückspilz", SpecialTeam.CREW, "🍀",
        "Wirst du angegriffen, überlebst du einmal mit 50 % Chance. Der Angreifer merkt nur, dass es nicht geklappt hat.",
    ),
    // ---------- Impostor ----------
    SpecialRole(
        "Putzkraft", SpecialTeam.IMPOSTOR, "🧹",
        "Du lässt eine Leiche verschwinden. Dafür teilst du dir den Cooldown mit deinem Kill.",
    ),
    SpecialRole(
        "Bestatter", SpecialTeam.IMPOSTOR, "🪦",
        "Du trägst Leichen weg und legst sie woanders ab. Solange du trägst, bist du langsamer.",
    ),
    SpecialRole(
        "Vampir", SpecialTeam.IMPOSTOR, "🧛",
        "Du beißt statt zu töten: Dein Opfer stirbt erst 10 Sekunden später – dann bist du längst weg. Wird vorher" +
            " ein Meeting einberufen, stirbt es sofort.",
    ),
    SpecialRole(
        "Bomber", SpecialTeam.IMPOSTOR, "💣",
        "Du klebst einem Spieler eine Bombe an. Nach ein paar Sekunden explodiert sie und tötet ihn und alle" +
            " Crewmates in der Nähe. Kommt vorher ein Meeting, ist sie entschärft.",
    ),
    SpecialRole(
        "Grenadier", SpecialTeam.IMPOSTOR, "💥",
        "Du wirfst eine Blendgranate: Crewmates in der Nähe sehen kurz fast nichts.",
    ),
    SpecialRole(
        "Maulwurf", SpecialTeam.IMPOSTOR, "🕳️",
        "Du baust bis zu drei eigene Vents, die miteinander verbunden sind.",
    ),
    SpecialRole(
        "Erpresser", SpecialTeam.IMPOSTOR, "🤐",
        "Ein Spieler darf im nächsten Meeting nicht schreiben und nicht reden (hinter seinem Namen steht" +
            " „erpresst“). Höchstens zweimal pro Spiel.",
    ),
    SpecialRole(
        "Tarner", SpecialTeam.IMPOSTOR, "🫥",
        "Für ein paar Sekunden sehen alle gleich grau aus, ohne Namen.",
    ),
    SpecialRole(
        "Hexenmeister", SpecialTeam.IMPOSTOR, "🧙",
        "Du verfluchst einen Spieler und löst den Fluch später aus: Wer dann neben ihm steht, stirbt – er steht" +
            " neben der Leiche, du kannst weit weg sein.",
    ),
    SpecialRole(
        "Armor", SpecialTeam.IMPOSTOR, "🦺",
        "Du überlebst den ersten tödlichen Treffer (Sheriff, Bombe, Veteran …). Der Angreifer merkt nur, dass es" +
            " nicht geklappt hat.",
    ),
    SpecialRole(
        "Saboteur", SpecialTeam.IMPOSTOR, "🔌",
        "Solange du lebst, sind Sabotagen doppelt so schnell wieder bereit.",
    ),
    // ---------- Neutral ----------
    SpecialRole(
        "Narr", SpecialTeam.NEUTRAL, "🃏",
        "Du gewinnst allein, wenn du rausgewählt wirst. Mach dich also verdächtig – aber nicht zu sehr.",
    ),
    SpecialRole(
        "Vergesslicher", SpecialTeam.NEUTRAL, "🧠",
        "Du weißt noch nicht, wer du bist: Stell dich an eine Leiche und übernimm ihre Rolle. War sie Impostor," +
            " wirst du Impostor.",
    ),
    // ---------- Modifikatoren (zusätzlich zur Rolle, zählen nicht zu den 30) ----------
    SpecialRole(
        "Flash", SpecialTeam.MODIFIER, "⚡",
        "Zusatz zu deiner Rolle: Du läufst schneller als alle anderen. Jeder kann Flash bekommen.",
    ),
).associateByTo(LinkedHashMap()) { it.name }
