/*
 * Stats-Website: Bilder für das Rollen-Wiki – die Fähigkeiten-Knöpfe der Mod The Other Roles GMIA (GPL-3.0, aus den
 * eingebetteten Ressourcen der TheOtherRoles.dll 1.4.1, verkleinert als WebP in web/public/role-icons/). Rollen ohne
 * eigenen Knopf (Sheriff schießt mit dem Kill-Knopf, Mayor/Snitch/Jester haben keinen …) zeigt die Seite mit dem
 * leeren Knopf und ihren Anfangsbuchstaben.
 */
package de.amongusmanager.crewbot.web

/** Ein Bild einer Rollen-Fähigkeit: Datei unter /amongus/role-icons/<file>.webp und was der Knopf macht. */
data class RoleImage(val file: String, val label: String)

object RoleArt {
    /** Ordner der Bilder im gebauten Frontend (Klassenpfad web/role-icons/). */
    const val DIR = "role-icons"

    /** Leerer Knopf als Hintergrund für Rollen ohne eigenes Bild. */
    const val BLANK = "blank"

    private fun art(vararg pairs: Pair<String, String>) = pairs.map { RoleImage(it.first, it.second) }

    /** Lexikon-Id ("tor:<id>" aus SPECIAL_ROLES) → Bilder, das erste ist das Hauptbild. */
    val IMAGES: Map<String, List<RoleImage>> = mapOf(
        // Crew
        "tor:archaeologist" to art("archaeologist-detect" to "Antiquitäten aufspüren", "excavate" to "Ausgraben"),
        "tor:busker" to art("busker" to "Tod vortäuschen"),
        "tor:collator" to art("collator-sample" to "Probe nehmen"),
        "tor:deputy" to art("deputy-handcuff" to "Handschellen anlegen"),
        "tor:detective" to art("detective" to "Ermitteln"),
        "tor:engineer" to art("repair" to "Sabotage reparieren"),
        "tor:hacker" to art("hacker" to "Hacken"),
        "tor:jailor" to art("jailor" to "Einsperren"),
        "tor:lighter" to art("lighter" to "Licht anzünden"),
        "tor:mayor" to art("emergency" to "Notfall-Meeting von überall"),
        "tor:medic" to art("shield" to "Schild geben"),
        "tor:medium" to art("medium" to "Seele befragen", "soul" to "Seele eines Toten"),
        "tor:noisemaker" to art("noisemaker" to "Spieler wählen"),
        "tor:portalmaker" to art("place-portal" to "Portal bauen", "use-portal" to "Portal benutzen"),
        "tor:securityGuard" to art("close-vent" to "Vent versiegeln", "place-camera" to "Kamera bauen"),
        "tor:seer" to art("seer" to "Seher-Blick", "soul" to "Seelen der Toten"),
        "tor:sherlock" to art("sherlock-investigate" to "Verbrechen untersuchen", "sherlock-watch" to "Beobachten"),
        "tor:shifter" to art("shift" to "Rolle tauschen"),
        "tor:sprinter" to art("sprint" to "Sprinten"),
        "tor:teleporter" to art("teleporter" to "Spieler tauschen"),
        "tor:timeMaster" to art("time-shield" to "Zeitschild", "rewind" to "Zeit zurückdrehen"),
        "tor:tracker" to art("tracker" to "Spieler verfolgen", "pathfind" to "Leichen aufspüren"),
        "tor:veteran" to art("alert" to "Wachsam sein"),
        // Crew oder Impostor
        "tor:swapper" to art("swapper-check" to "Stimmen tauschen"),
        "tor:yasuna" to art("yasuna-target" to "Ziel wählen"),
        // Impostor
        "tor:assassin" to art("assassin-mark" to "Ziel markieren", "assassin-assassinate" to "Zuschlagen"),
        "tor:blackmailer" to art("blackmailer-blackmail" to "Erpressen"),
        "tor:bomber" to art("plant-bomb" to "Bombe legen"),
        "tor:camouflager" to art("camo" to "Alle tarnen"),
        "tor:cleaner" to art("clean" to "Leiche wegräumen"),
        "tor:eraser" to art("eraser" to "Rolle löschen"),
        "tor:evilHacker" to art("admin-button-airship" to "Admin-Tisch von überall"),
        "tor:evilTracker" to art("tracker" to "Ziel verfolgen"),
        "tor:mafia" to art("clean" to "Janitor: Leiche wegräumen"),
        "tor:morphling" to art("sample" to "Probe nehmen", "morph" to "Verwandeln"),
        "tor:ninja" to art("ninja" to "Markieren und zuschlagen"),
        "tor:serialKiller" to art("suicide" to "Selbstmord-Timer"),
        "tor:trapper" to art("trapper-place" to "Falle stellen", "trap" to "Falle"),
        "tor:trickster" to art("place-jack-in-the-box" to "Kiste aufstellen", "trickster-vent" to "Durch Kisten reisen", "lights-out" to "Licht aus"),
        "tor:undertaker" to art("drag" to "Leiche ziehen", "drop" to "Leiche ablegen"),
        "tor:vampire" to art("vampire" to "Beißen", "garlic" to "Knoblauch"),
        "tor:warlock" to art("curse" to "Verfluchen", "curse-kill" to "Fluch auslösen"),
        "tor:witch" to art("spell" to "Verhexen"),
        "tor:yoyo" to art("yoyo-mark" to "Ort markieren", "yoyo-blink" to "Hin und zurück"),
        "tor:zephyr" to art("zephyr" to "Windstoß"),
        // Neutral
        "tor:akujo" to art("akujo-honmei" to "Herzblatt wählen", "akujo-keep" to "Warmhalten"),
        "tor:arsonist" to art("douse" to "Mit Benzin übergießen", "ignite" to "Anzünden"),
        "tor:chainShifter" to art("shift" to "Rolle tauschen"),
        "tor:cupid" to art("cupid" to "Pfeil schießen"),
        "tor:doomsayer" to art("doomsayer" to "Rolle vorhersagen"),
        "tor:fox" to art("fox-hide" to "Verstecken", "fox-immoralist" to "Immoralist ernennen"),
        "tor:jackal" to art("sidekick" to "Sidekick ernennen"),
        "tor:kataomoi" to art(
            "kataomoi-love" to "Lieben", "kataomoi-stare" to "Anstarren", "kataomoi-stalking" to "Nachstellen", "kataomoi-search" to "Suchen",
        ),
        "tor:pursuer" to art("pursuer" to "Platzpatrone"),
        "tor:moriarty" to art("brainwash" to "Gehirnwäsche"),
        "tor:plagueDoctor" to art("infect" to "Infizieren"),
        "tor:vulture" to art("vulture" to "Leiche fressen"),
    )

    /** Alle Dateien (ohne Endung), die es geben muss – prüft RoleArtTest. */
    val FILES: Set<String> get() = IMAGES.values.flatten().map { it.file }.toSet() + BLANK
}
