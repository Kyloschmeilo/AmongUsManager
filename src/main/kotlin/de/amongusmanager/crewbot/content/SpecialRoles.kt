/*
 * Rollen der Mod „The Other Roles“ für /special-rolle und das Tutorial – wir spielen TheOtherRoles GMIA
 * (Weiterentwicklung von The Other Roles für das 64-Bit-Among-Us, github.com/GMIA-Nexus/TheOtherRolesGMIA).
 * Namen wie im Spiel (die Mod ist englisch), Beschreibungen nach den Rollentexten der Mod (stringData.json).
 */
package de.amongusmanager.crewbot.content

/** Neutrale spielen für sich; Modifikatoren kommen zusätzlich zu einer Rolle; BOTH gibt es als Crew- und Impostor-Version. */
enum class SpecialTeam(val label: String) {
    CREW("🟢 Crewmates"),
    IMPOSTOR("🔴 Impostor"),
    NEUTRAL("🟣 Neutral"),
    BOTH("⚪ Crew oder Impostor"),
    MODIFIER("🟡 Modifikator"),
}

/** [id] steht in den Menüs (custom value) – nie umbenennen. */
data class SpecialRole(val id: String, val name: String, val team: SpecialTeam, val emoji: String, val description: String)

private fun crew(id: String, name: String, emoji: String, description: String) = SpecialRole(id, name, SpecialTeam.CREW, emoji, description)
private fun impostor(id: String, name: String, emoji: String, description: String) = SpecialRole(id, name, SpecialTeam.IMPOSTOR, emoji, description)
private fun neutral(id: String, name: String, emoji: String, description: String) = SpecialRole(id, name, SpecialTeam.NEUTRAL, emoji, description)
private fun both(id: String, name: String, emoji: String, description: String) = SpecialRole(id, name, SpecialTeam.BOTH, emoji, description)
private fun modifier(id: String, name: String, emoji: String, description: String) = SpecialRole(id, name, SpecialTeam.MODIFIER, emoji, description)

val SPECIAL_ROLES: Map<String, SpecialRole> = listOf(
    // ---------- Crewmates ----------
    crew("archaeologist", "Archaeologist", "🏺",
        "Du spürst Antiquitäten auf (Pfeile zeigen den Raum) und gräbst sie aus. Jede verrät dir einen Hinweis auf die Rollen im Spiel."),
    crew("bait", "Bait", "🎣",
        "Wer dich tötet, muss deine Leiche sofort selbst melden. Du siehst außerdem, ob jemand in einem Vent sitzt."),
    crew("busker", "Busker", "🎻",
        "Du kannst deinen Tod vortäuschen und als Geist herumlaufen. Drück rechtzeitig wieder, sonst stirbst du wirklich – auch beim Meeting oder wenn deine Leiche weggeräumt wird."),
    crew("collator", "Collator", "🧫",
        "Du nimmst Proben von Spielern. Im Meeting siehst du, ob zwei Proben zum selben Team gehören."),
    crew("deputy", "Deputy", "🔗",
        "Du hilfst dem Sheriff und legst Spielern Handschellen an. Die merken das erst, wenn sie töten, venten, melden oder eine Fähigkeit nutzen wollen – das geht dann nämlich nicht."),
    crew("detective", "Detective", "🔍",
        "Du siehst die Fußspuren aller Spieler. Meldest du eine Leiche, bekommst du Hinweise auf den Mörder – je schneller du sie findest, desto genauer."),
    crew("engineer", "Engineer", "🔧",
        "Du reparierst Sabotagen von überall auf der Map und darfst venten. Impostor sehen aber, wenn du im Vent bist."),
    crew("fortuneTeller", "Fortune Teller", "🔮",
        "Du hältst dich zuerst für einen normalen Crewmate. Nach einigen Tasks erwachen deine Kräfte, und du kannst die Rolle eines Spielers vorhersagen."),
    crew("hacker", "Hacker", "💻",
        "Mit deiner Fähigkeit zeigen dir Admin-Tisch und Vitals für kurze Zeit viel mehr – zum Beispiel, wer wo steht."),
    crew("jailor", "Jailor", "🔒",
        "Du sperrst Spieler ein, redest im Meeting heimlich mit ihnen und darfst sie hinrichten. Erwischst du einen Crewmate, stirbst du oder verlierst die Fähigkeit."),
    crew("lighter", "Lighter", "🕯️",
        "Du zündest ein Licht an, das deine Sicht vergrößert – sogar durch Wände und Schatten."),
    crew("mayor", "Mayor", "🎩",
        "Deine Stimme zählt im Meeting mehrfach, und du darfst immer ein Notfall-Meeting rufen – auch wenn eigentlich keins mehr übrig ist."),
    crew("medic", "Medic", "🩺",
        "Du gibst einem Spieler einen Schild – er kann dann nicht getötet werden (rausgewählt aber schon). Bei Leichen siehst du, wie lange der Tod her ist."),
    crew("medium", "Medium", "👻",
        "Du siehst, wo Spieler gestorben sind, und befragst ihre Seelen. Die verraten dir im Chat zufällige Infos über sich oder den Mörder."),
    crew("noisemaker", "Noisemaker", "📢",
        "Du wählst einen Spieler. Stirbt er, zeigt ein Pfeil – je nach Einstellung dir, der Crew oder allen –, wo er liegt."),
    crew("portalmaker", "Portalmaker", "🌀",
        "Du baust zwei verbundene Portale, die nach dem nächsten Meeting jeder benutzen kann. Im Meeting erfährst du, wer sie wann benutzt hat."),
    crew("securityGuard", "Security Guard", "📹",
        "Mit deinen Schrauben versiegelst du Vents oder baust neue Kameras. Sind sie aufgebraucht, schaust du Kameras von überall und holst Spieler aus Vents."),
    crew("seer", "Seer", "👁️",
        "Du siehst die Seelen der Toten aus der letzten Runde und bekommst einen blauen Blitz, wenn irgendwo jemand stirbt."),
    crew("sheriff", "Sheriff", "🤠",
        "Du darfst Impostor erschießen. Triffst du einen Crewmate, stirbst du selbst – schieß also nur, wenn du dir sicher bist."),
    crew("sherlock", "Sherlock", "🕵️",
        "Nach einigen Tasks kannst du Verbrechen untersuchen. Ein besonderer Pfeil warnt dich, wenn sich Impostor gefährlich bewegen."),
    crew("shifter", "Shifter", "🔄",
        "Du übernimmst die Rolle eines anderen Crewmates, er wird dann normaler Crewmate. Der Tausch passiert im nächsten Meeting. Erwischst du einen Impostor oder Neutralen, stirbst du."),
    crew("snitch", "Snitch", "🔦",
        "Hast du alle Tasks erledigt, zeigen dir Pfeile die Impostor. Kurz vorher sehen die Impostor aber auch dich – beeil dich!"),
    crew("sprinter", "Sprinter", "💨",
        "Du wirst für ein paar Sekunden unsichtbar und kannst in der Zeit von niemandem anvisiert werden."),
    crew("spy", "Spy", "🕶️",
        "Du kannst nichts Besonderes – aber für die Impostor siehst du aus wie ein weiterer Impostor. Verwirr sie!"),
    crew("taskMaster", "Task Master", "📋",
        "Nach deinen Tasks bekommst du Extra-Tasks. Schaffst du alle und lebst noch, gewinnt die Crew sofort."),
    crew("teleporter", "Teleporter", "🔀",
        "Du wählst zwei Spieler aus und tauschst ihre Positionen."),
    crew("timeMaster", "Time Master", "⏳",
        "Du aktivierst einen Zeitschild. Greift dich jemand an, während er aktiv ist, wird die Zeit zurückgespult – der Kill passiert nicht."),
    crew("tracker", "Tracker", "📡",
        "Du wählst einen Spieler. Ein Pfeil zeigt dir immer wieder, wo er zuletzt war."),
    crew("veteran", "Veteran", "🏅",
        "Mit „Alert“ wirst du für kurze Zeit wachsam. Wer dich dann angreift oder eine Fähigkeit an dir nutzt (außer Crewmates), stirbt sofort."),

    // ---------- Beide Teams ----------
    both("guesser", "Guesser", "🎯",
        "Als Nice Guesser (Crew) oder Evil Guesser (Impostor) erschießt du im Meeting einen Spieler, indem du seine Rolle errätst. Liegst du falsch, stirbst du selbst."),
    both("swapper", "Swapper", "🔃",
        "Als Nice Swapper (Crew) oder Evil Swapper (Impostor) tauschst du im Meeting die Stimmen zweier Spieler. Dafür darfst du keine Notfall-Meetings rufen und weder Licht noch Funk reparieren."),
    both("watcher", "Watcher", "👀",
        "Als Nice Watcher (Crew) oder Evil Watcher (Impostor) siehst du im Meeting, wer wen gewählt hat. Je nach Einstellung bringt dir ein beobachteter Kill einen Extra-Vorteil."),
    both("yasuna", "Yasuna", "⚖️",
        "Als Yasuna (Crew) oder Evil Yasuna (Impostor) wählst du einen Spieler raus, egal wie die anderen abstimmen – aber nur ein paar Mal pro Spiel."),

    // ---------- Impostor ----------
    impostor("assassin", "Assassin", "🗡️",
        "Du markierst einen Spieler und springst später von überall zu ihm, um ihn zu töten. Danach bist du kurz unsichtbar, hinterlässt aber Spuren."),
    impostor("blackmailer", "Blackmailer", "🤐",
        "Du bringst einen Spieler fürs nächste Meeting zum Schweigen – je nach Einstellung darf er auch nicht abstimmen."),
    impostor("bomber", "Bomber", "💣",
        "Ihr seid zwei Bomber und legt Bomben an Spieler. Berührt ihr euch danach, fliegt eins der Ziele sofort in die Luft – auch Schilde helfen nicht."),
    impostor("bountyHunter", "Bounty Hunter", "💰",
        "Du bekommst immer neue Kopfgeld-Ziele. Tötest du dein Ziel, ist dein Kill viel schneller wieder bereit, bei anderen dauert es länger."),
    impostor("camouflager", "Camouflager", "🫥",
        "Du schaltest die Tarnung ein: Für kurze Zeit sehen alle gleich aus, ohne Namen, Hüte und Haustiere."),
    impostor("cleaner", "Cleaner", "🧹",
        "Du lässt Leichen verschwinden. Töten und Aufräumen teilen sich die Abklingzeit."),
    impostor("eraser", "Eraser", "🧽",
        "Du löschst die Rolle eines Spielers – im nächsten Meeting ist sie weg, selbst wenn du vorher stirbst."),
    impostor("evilHacker", "Evil Hacker", "🖥️",
        "Du siehst den Admin-Tisch jederzeit und kannst einen Spieler zum Madmate machen."),
    impostor("evilTracker", "Evil Tracker", "🛰️",
        "Du verfolgst einen Spieler und siehst immer, wo er ist. Pfeile zeigen dir außerdem deine Mit-Impostor."),
    impostor("mafia", "Mafia", "🕴️",
        "Drei Impostor als Familie: Der **Godfather** erfährt die Rolle seiner Opfer, der **Mafioso** kann Meetings überspringen lassen, der **Janitor** lässt Leichen verschwinden."),
    impostor("madmate", "Madmate", "🤪",
        "Du bist eigentlich Crewmate, hilfst aber heimlich den Impostor und gewinnst mit ihnen. Töten kannst du nicht."),
    impostor("mimic", "Mimic", "🪞",
        "Ihr seid zu zweit: Der **Killer** nimmt nach jedem Kill das Aussehen des Opfers an und lässt die Leiche verschwinden. Der **Assistant** kann sich in den Killer verwandeln und tötet erst, wenn der Killer tot ist."),
    impostor("morphling", "Morphling", "🎭",
        "Du scannst das Aussehen eines Spielers und verwandelst dich später für kurze Zeit in ihn."),
    impostor("nekoKabocha", "Neko-Kabocha", "🎃",
        "Du stirbst nie allein: Wer dich tötet – Sheriff, Jackal oder sonst wer –, stirbt mit dir."),
    impostor("ninja", "Ninja", "🥷",
        "Du wirst unsichtbar und bist dabei schneller. Das kostet dich aber Zeit beim nächsten Kill."),
    impostor("serialKiller", "Serial Killer", "🔪",
        "Dein Kill ist viel schneller wieder bereit. Aber nach dem ersten Kill musst du rechtzeitig wieder töten, sonst stirbst du selbst."),
    impostor("trapper", "Trapper", "🪤",
        "Du stellst Fallen auf. Wer hineinläuft, steckt fest und stirbt, wenn ihn niemand rettet."),
    impostor("trickster", "Trickster", "🎁",
        "Du versteckst drei Springteufel-Boxen. Sind alle platziert, werden sie zu deinem eigenen Vent-Netz, und du kannst das Licht der Crew ausschalten."),
    impostor("undertaker", "Undertaker", "⚰️",
        "Du ziehst Leichen hinter dir her und versteckst sie."),
    impostor("vampire", "Vampire", "🧛",
        "Du beißt statt zu töten: Gebissene sterben erst nach ein paar Sekunden. Knoblauch, den jeder Spieler platzieren kann, schützt davor."),
    impostor("warlock", "Warlock", "🧙",
        "Du verfluchst einen Spieler. Steht er neben jemandem, kannst du diesen über ihn töten – danach bist du kurz bewegungsunfähig."),
    impostor("witch", "Witch", "🪄",
        "Du verhext Spieler. Im nächsten Meeting sind sie markiert und sterben danach. Wird die Witch rausgewählt, sind sie je nach Einstellung gerettet."),
    impostor("yoyo", "Yo-Yo", "🪀",
        "Du markierst einen Ort, springst dorthin und kehrst nach kurzer Zeit automatisch zurück. Spieler in der Nähe bekommen dabei einen Blackout."),
    impostor("zephyr", "Zephyr", "🌪️",
        "Du feuerst Luftkanonen ab: Getroffene Crewmates werden weggeschleudert und sterben bei der Landung."),

    // ---------- Neutral ----------
    neutral("akujo", "Akujo", "💋",
        "Du wählst eine wahre Liebe und ein Backup. Mit der wahren Liebe bist du verliebt – stirbt sie, stirbst du auch. Das Backup ist dir egal."),
    neutral("arsonist", "Arsonist", "🔥",
        "Du hast keine Tasks und übergießt Spieler mit Benzin, indem du kurz bei ihnen stehst. Sind alle Lebenden übergossen, zündest du sie an und gewinnst allein."),
    neutral("chainShifter", "Chain-Shifter", "⛓️",
        "Du klaust die Rolle eines beliebigen Spielers, er wird dafür zum Chain-Shifter. Wer die Rolle am Ende hat, verliert."),
    neutral("cupid", "Cupid", "💘",
        "Du machst zwei Spieler zum Liebespaar – stirbt einer, sterben alle. Du kannst außerdem dein Leben opfern, um jemanden zu beschützen."),
    neutral("doomsayer", "Doomsayer", "📜",
        "Du gewinnst, wenn du genug Rollen richtig errätst. Jede Runde beobachtest du einen Spieler und bekommst im Meeting Hinweise auf seine Rolle."),
    neutral("fox", "Fox", "🦊",
        "Du erledigst deine eigenen Tasks und musst dich vor Crew und Impostor verstecken. Du weißt immer, wo Gefahren lauern, und hast Fähigkeiten zum Schutz."),
    neutral("immoralist", "Immoralist", "🃏",
        "Du hilfst dem Fox um jeden Preis – notfalls mit deinem eigenen Leben."),
    neutral("jackal", "Jackal", "🐺",
        "Du bist ein eigenes Team und tötest alle: Crew, Impostor und Neutrale. Einmal kannst du einen Spieler zu deinem Sidekick machen."),
    neutral("sidekick", "Sidekick", "🐕",
        "Der Jackal hat dich rekrutiert – ihr wollt zusammen alle anderen ausschalten. Stirbt der Jackal, wirst du je nach Einstellung selbst zum Jackal."),
    neutral("jekyllAndHyde", "Jekyll and Hyde", "🧪",
        "Du hast zwei Seiten: Als Jekyll kannst du nicht töten, als Hyde schon, musst es aber rechtzeitig tun. Nach jedem Kill wechselst du die Seite."),
    neutral("jester", "Jester", "🤡",
        "Du hast keine Tasks und gewinnst, wenn du rausgewählt wirst. Mach dich also verdächtig – aber nicht zu sehr."),
    neutral("kataomoi", "Kataomoi", "💔",
        "Unerwiderte Liebe: Du starrst dein Ziel immer wieder an. Hast du es oft genug geschafft, wird der Knopf zum Liebes-Knopf – und du gewinnst allein."),
    neutral("lawyer", "Lawyer", "💼",
        "Dein Mandant ist ein Impostor oder Jackal. Gewinnt er, gewinnst du mit. Stirbt er, wirst du zum Pursuer."),
    neutral("pursuer", "Pursuer", "🏃",
        "Du musst am Ende nur noch leben. Dafür lädst du Killern (auch dem Sheriff) Platzpatronen in die Waffe – ihr nächster Kill geht daneben."),
    neutral("moriarty", "Moriarty", "🎓",
        "Du bist ein eigener Killer. Du kannst Spieler einer Gehirnwäsche unterziehen, damit sie für dich töten. Erreichst du genug Kills, gewinnst du allein."),
    neutral("opportunist", "Opportunist", "🪙",
        "Du gewinnst mit dem Siegerteam, egal welches – Hauptsache, du lebst am Ende noch."),
    neutral("pelican", "Pelican", "🦤",
        "Du verschluckst Spieler lebendig. Stirbst du, kommen alle wieder frei – kommt aber ein Meeting, sterben sie. Hast du alle verschluckt, gewinnst du."),
    neutral("plagueDoctor", "Plague Doctor", "🦠",
        "Du infizierst einen Spieler. Wer lange genug in seiner Nähe ist, steckt sich an. Sind alle Lebenden infiziert, gewinnst du."),
    neutral("schrodingersCat", "Schrodinger's Cat", "🐈",
        "Wirst du zum ersten Mal getötet, stirbst du nicht, sondern wechselst ins Team deines Mörders."),
    neutral("thief", "Thief", "🦹",
        "Du musst einen anderen Killer (Impostor, Jackal …) töten und übernimmst dann seine Rolle und sein Team. Versuchst du es bei jemand Harmlosem, stirbst du."),
    neutral("vulture", "Vulture", "🦅",
        "Du hast keine Tasks und frisst Leichen. Hast du genug gefressen, gewinnst du allein."),
    neutral("yandere", "Yandere", "🥀",
        "Du hast einen Schwarm. Wer zu lange in seiner Nähe ist, wird dein Rivale – und Rivalen darfst du töten. Ist dein Schwarm tot oder findest du keine Rivalen, drehst du durch."),

    // ---------- Modifikatoren (zusätzlich zur Rolle) ----------
    modifier("antiTeleport", "Anti Teleport", "📍",
        "Bei einem Meeting wirst du nicht zum Tisch teleportiert – du startest die nächste Runde dort, wo du warst."),
    modifier("armored", "Armored", "🛡️",
        "Du überlebst den ersten Angriff, der dich töten würde."),
    modifier("bloody", "Bloody", "🩸",
        "Wirst du getötet, hinterlässt dein Mörder eine Zeit lang eine Blutspur in deiner Farbe."),
    modifier("chameleon", "Chameleon", "🦎",
        "Stehst du eine Weile still, wirst du (teilweise) unsichtbar."),
    modifier("diseased", "Diseased", "🤒",
        "Nur für Crewmates: Wer dich tötet, muss viel länger auf seinen nächsten Kill warten."),
    modifier("invert", "Invert", "🙃",
        "Deine Steuerung ist umgekehrt."),
    modifier("lovers", "Lovers", "💞",
        "Zwei Spieler sind verliebt: Stirbt einer, stirbt auch der andere. Leben am Ende beide, gewinnen sie – selbst wenn einer Impostor ist."),
    modifier("mini", "Mini", "🧒",
        "Du bist kleiner und für alle erkennbar. Bis du erwachsen bist, kann dich niemand töten – rausgewählt werden kannst du aber."),
    modifier("multitasker", "Multitasker", "🪟",
        "Nur für Crewmates: Deine Task-Fenster sind durchsichtig."),
    modifier("radar", "Radar", "🧭",
        "Ein Pfeil zeigt dir immer den Spieler, der dir am nächsten ist."),
    modifier("sunglasses", "Sunglasses", "😎",
        "Deine Sicht ist etwas kleiner – auch, wenn das Licht sabotiert ist."),
    modifier("tiebreaker", "Tiebreaker", "🎲",
        "Endet eine Abstimmung unentschieden, zählt deine Stimme heimlich doppelt."),
    modifier("vip", "VIP", "⭐",
        "Stirbst du, bekommen alle einen Blitz auf den Bildschirm – je nach Einstellung in deiner Teamfarbe."),
).associateByTo(LinkedHashMap()) { it.id }

// ==========================================
// ROLLEN AUS DEN RUNDENDATEN (CrewStats mit The Other Roles)
// ==========================================

/**
 * Interne Rollen-Namen von The Other Roles (nameKey, so schickt CrewStats sie), die hier keine eigene [SpecialRole] haben:
 * nameKey -> (id in [SPECIAL_ROLES] für das Emoji, Anzeigename).
 */
private val TOR_ROLE_NAMES: Map<String, Pair<String, String>> = mapOf(
    "godfather" to ("mafia" to "Godfather"),
    "mafioso" to ("mafia" to "Mafioso"),
    "janitor" to ("mafia" to "Janitor"),
    "niceSwapper" to ("swapper" to "Nice Swapper"),
    "evilSwapper" to ("swapper" to "Evil Swapper"),
    "niceShifter" to ("shifter" to "Shifter"),
    "corruptedShifter" to ("chainShifter" to "Chain-Shifter"),
    "niceGuesser" to ("guesser" to "Nice Guesser"),
    "evilGuesser" to ("guesser" to "Evil Guesser"),
    "niceWatcher" to ("watcher" to "Nice Watcher"),
    "evilWatcher" to ("watcher" to "Evil Watcher"),
    "niceYasuna" to ("yasuna" to "Nice Yasuna"),
    "evilYasuna" to ("yasuna" to "Evil Yasuna"),
    "mimicK" to ("mimic" to "Mimic (Killer)"),
    "mimicA" to ("mimic" to "Mimic (Assistant)"),
    "lover" to ("lovers" to "Lover"),
    "antiTeleportPostfix" to ("antiTeleport" to "Anti Teleport"),
)

/** Rollen ohne Besonderheit – dafür zeigt der Bot die Rolle des Spiels (Crewmate/Impostor). */
private val TOR_PLAIN_ROLES = setOf("", "crewmate", "impostor")

/** Die [SpecialRole] zu einem nameKey aus den Rundendaten (null, wenn unbekannt oder ohne Besonderheit). */
fun specialRoleFor(nameKey: String): SpecialRole? = SPECIAL_ROLES[TOR_ROLE_NAMES[nameKey]?.first ?: nameKey]

/** Anzeige einer TOR-Rolle aus den Rundendaten, z.B. "🤠 Sheriff" – null für Crewmate/Impostor ohne Rolle. */
fun torRoleLabel(nameKey: String): String? {
    val name = torRoleName(nameKey) ?: return null
    val role = specialRoleFor(nameKey)
    return if (role != null) "${role.emoji} $name" else name
}

/** Name einer TOR-Rolle ohne Emoji (Stats-Website), z.B. "Sheriff" – null für Crewmate/Impostor ohne Rolle. */
fun torRoleName(nameKey: String): String? {
    if (nameKey in TOR_PLAIN_ROLES) return null
    return TOR_ROLE_NAMES[nameKey]?.second ?: specialRoleFor(nameKey)?.name ?: nameKey
}
