/*
 * Texte des Community-Bereichs (/community): Server-Regeln, FAQ und die festen Neuigkeiten.
 * Regeln und FAQ sind Vorschläge – Admins können sie auf der Website überschreiben (gespeichert in data/website.db),
 * dann gilt ihr Text. Neuigkeiten: diese Liste + eigene Beiträge der Admins + der aktuelle /changelog des Bots.
 * `/befehle` im Text erscheinen auf der Seite als Code.
 */
package de.amongusmanager.crewbot.web

object CommunityContent {
    class Entry(val title: String, val text: String)

    class News(val date: String, val title: String, val text: String, val link: String? = null)

    val RULES: List<Entry> = listOf(
        Entry(
            "Respekt",
            "Wir spielen zum Spaß. Keine Beleidigungen, kein Rassismus, kein Sexismus, keine Hetze gegen Einzelne oder Gruppen – " +
                "weder im Chat noch im Voice noch im Spiel. Trash-Talk im Spiel ist okay, solange alle mitlachen.",
        ),
        Entry(
            "Kein Spam, keine Werbung",
            "Keine Kettenbriefe, kein Massen-Pingen und keine Werbung für andere Server oder Shops ohne Erlaubnis eines Admins. " +
                "Lobby-Codes gehören in den Codes-Channel.",
        ),
        Entry(
            "Fair spielen",
            "Keine Cheats, Hacks oder Mods, die einen Vorteil bringen. Erlaubt sind die Mods, die wir selbst anbieten " +
                "(CrewStats, The Other Roles). Wer tot ist, verrät nichts – weder im Voice noch per Nachricht (kein Ghosting).",
        ),
        Entry(
            "Voice-Chat",
            "Im Among-Us-Voice schaltet der Bot während der Runde stumm. Nicht selbst entstummen, kein Musik-Bot und keine " +
                "lauten Geräusche ins Mikro. Wer spricht, lässt andere ausreden.",
        ),
        Entry(
            "Inhalte",
            "Keine NSFW-, Gewalt- oder Ekel-Inhalte – auch nicht in Memes, Zeichnungen oder Profilbildern. Was in der " +
                "Community-Galerie landet, ist für alle sichtbar; Admins entfernen Unpassendes.",
        ),
        Entry(
            "Admins & Strafen",
            "Anweisungen der Admins gelten. Bei Verstößen gibt es je nach Schwere eine Verwarnung, einen Timeout oder einen " +
                "Bann. Fragen oder Beschwerden bitte per Nachricht an einen Admin, nicht öffentlich.",
        ),
    )

    val FAQ: List<Entry> = listOf(
        Entry(
            "Wie verknüpfe ich mein Among Us mit Discord?",
            "Im Discord /verknuepfen mit deinem Friendcode eingeben. Mit CrewStats geht es auch im Spiel: F7 → „Verknüpfen“ zeigt " +
                "einen Code, den gibst du im Discord bei /verknuepfen-code ein. Danach zählen deine Runden für XP, Stats und die Website.",
        ),
        Entry(
            "Was ist CrewStats und brauche ich das?",
            "CrewStats ist unsere Among-Us-Mod. Der Host braucht sie, damit Runden automatisch ausgewertet werden (XP, Stats, " +
                "Replays, Auto-Mute). Für alle anderen ist sie praktisch (Auto-Join, eigene Stats im Spiel), aber kein Muss. " +
                "Den Installer gibt es unter Hilfe & Mods auf der Among-Us-Seite.",
        ),
        Entry(
            "Wie komme ich in die Lobby, ohne den Code abzutippen?",
            "Mit Auto-Join von CrewStats: /autojoin im Discord gibt dir die Adresse für die Mod. Danach trittst du im Hauptmenü " +
                "automatisch der neuesten Lobby bei (F5 = sofort, F6 = an/aus) oder klickst auf der Website unter „Live“ auf Beitreten.",
        ),
        Entry(
            "Wie bekomme ich XP und Level?",
            "Für Siege in Mod-Runden (Crew 10, Impostor 25, neutrale Rollen 30 XP), für Rollen-Aktionen, Erfolge und Events " +
                "(wer lange genug im Voice war). Dein Stand: /stats, die Bestenliste: /leaderboard oder auf der Website.",
        ),
        Entry(
            "Kann ich mir eine Rolle wünschen?",
            "Ja: /rollenwunsch (normale Rollen) bzw. /special-rollenwunsch (The Other Roles) kostet 20 XP, höchstens zwei pro " +
                "Spieleabend. Klappt es nicht, bekommst du die XP zurück. Der Host braucht dafür CrewStats.",
        ),
        Entry(
            "Wie spiele ich mit The Other Roles?",
            "Lade den TheOtherRoles-Installer unter Hilfe & Mods herunter, er richtet eine eigene Kopie von Among Us ein. Gespielt " +
                "wird auf dem Server „Modded NA“. Welche Rolle was kann, zeigt /special-rolle oder die Rollen-Seite.",
        ),
        Entry(
            "Wie melde ich mich auf der Website an?",
            "Oben auf „Anmelden“ – das geht über Discord, die Website sieht nur deinen Namen und dein Profilbild. Angemeldet kannst " +
                "du dein Profil gestalten, Räume in den Browserspielen eröffnen, bei Events mitmachen und in der Galerie Herzen verteilen.",
        ),
        Entry(
            "Wie kommt mein Meme oder meine Zeichnung in die Galerie?",
            "Von selbst: Am Ende einer Partie Meme-Schmiede kommen die besten Memes jeder Runde in die Galerie, in der Kritzelkiste " +
                "die Zeichnungen, die am meisten gefeiert wurden (🔥). Gefällt dir etwas nicht oder willst du deins entfernen lassen, " +
                "schreib einem Admin.",
        ),
    )

    /** Feste Neuigkeiten (neueste zuerst). Datum als ISO-Tag, damit sich eigene Beiträge der Admins dazwischen sortieren lassen. */
    val NEWS: List<News> = listOf(
        News("2026-11-21", "Community-Bereich", "Galerien mit den besten Memes und Zeichnungen, Server-Regeln, FAQ und diese Neuigkeiten.", "/community"),
        News("2026-11-21", "Browserspiele mit Discord-Name", "Wer angemeldet ist, spielt automatisch unter seinem Discord-Namen. Räume eröffnen geht nur noch mit Anmeldung, beitreten weiter ohne."),
        News("2026-11-21", "Meme-Schmiede mit echten Memes", "Statt nur Zeichnungen gibt es jetzt Pakete mit echten Meme-Vorlagen – SpongeBob, Deutsche Klassiker, Brainrot und mehr.", "/seiten/memes.html"),
        News("2026-11-20", "Schach gegen andere", "Schach online gegen Freunde oder den Computer, mit Analyse nach der Partie.", "/seiten/schach.html"),
        News("2026-11-20", "Direkt beitreten", "Ein Klick auf ein laufendes Spiel unter „Live“ bringt dich gleich in den Raum – ohne Code abtippen."),
        News("2026-11-14", "Neue Browserspiele", "Kritzelkiste, Kritzelpost, Meme-Schmiede, Geheimfunk, Schiffe versenken, Nudelsalat, MAMPF! und die Ballerbude – alle auf der Startseite.", "/"),
        News("2026-11-13", "Bis zu 24 Spieler", "Mit The Other Roles auf Modded NA passen jetzt bis zu 24 Leute in eine Lobby (/size N im Lobby-Chat als Host)."),
        News("2026-11-07", "Crack-Voting", "Nach jeder Among-Us-Runde 15 Minuten lang abstimmen, wer am meisten Crack geraucht hat – mit eigener Bestenliste.", "/amongus/"),
        News("2026-11-03", "Live-Lobbys, Balancing & Abzeichen", "Auf der Among-Us-Seite: wer gerade in welcher Lobby ist, Siegquoten je Rolle, Abzeichen im Profil und der Spieler-Vergleich.", "/amongus/lobbys"),
    )
}
