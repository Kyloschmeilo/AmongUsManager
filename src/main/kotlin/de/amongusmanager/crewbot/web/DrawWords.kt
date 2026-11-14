/* Kritzelkiste (Zeichnen und Raten): Wortlisten nach Kategorie. Nur Begriffe, die man zeichnen kann. */
package de.amongusmanager.crewbot.web

object DrawWords {
    class Category(val id: String, val label: String, val words: List<String>)

    private fun words(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val CATEGORIES: List<Category> = listOf(
        Category(
            "alltag", "Alltag",
            words(
                """
                Regenschirm, Kühlschrank, Zahnbürste, Wecker, Fahrrad, Leiter, Rucksack, Brille, Schlüssel, Kerze, Glühbirne, Staubsauger,
                Waschmaschine, Fernbedienung, Kopfhörer, Briefkasten, Ampel, Rolltreppe, Schaukel, Trampolin, Gießkanne, Hängematte,
                Zelt, Lagerfeuer, Leuchtturm, Windmühle, Vulkan, Regenbogen, Schneemann, Iglu, Wasserfall, Insel, Rakete, Ufo,
                Hubschrauber, U-Boot, Heißluftballon, Traktor, Bagger, Feuerwehr, Krankenwagen, Müllabfuhr, Skateboard, Achterbahn,
                Riesenrad, Zirkus, Schatzkarte, Krone, Ritter, Pirat, Zauberer, Geist, Vampir, Roboter, Astronaut, Clown, Detektiv,
                Zahnarzt, Friseur, Bauarbeiter, Koch, Gitarre, Schlagzeug, Mikrofon, Trompete, Klavier, Sanduhr, Kompass, Fernglas,
                Lupe, Magnet, Anker, Fallschirm, Drachen, Luftballon, Geschenk, Pokal, Medaille, Würfel, Schachbrett, Puzzle,
                Taschenlampe, Steckdose, Batterie, Thermometer, Pflaster, Spritze, Gipsbein, Rollstuhl, Krücken, Badewanne, Klobürste,
                Toilettenpapier, Spiegel, Kamm, Föhn, Nagellack, Lippenstift, Krawatte, Socken, Gummistiefel, Handschuhe, Bademantel,
                Sonnenbrille, Strohhut, Zylinder, Handschellen, Schatztruhe, Vogelscheuche, Gartenzwerg, Rasenmäher, Schubkarre
                """,
            ),
        ),
        Category(
            "tiere", "Tiere",
            words(
                """
                Pinguin, Giraffe, Elefant, Krokodil, Känguru, Faultier, Flamingo, Papagei, Eule, Fledermaus, Igel, Eichhörnchen,
                Waschbär, Fuchs, Wolf, Eisbär, Panda, Koala, Nashorn, Nilpferd, Zebra, Kamel, Lama, Esel, Ziege, Schaf, Schwein, Kuh,
                Huhn, Truthahn, Ente, Schwan, Storch, Pfau, Specht, Möwe, Hai, Wal, Delfin, Oktopus, Qualle, Seepferdchen, Krabbe,
                Hummer, Seestern, Schildkröte, Frosch, Chamäleon, Schlange, Skorpion, Spinne, Ameise, Biene, Schmetterling, Raupe,
                Marienkäfer, Libelle, Schnecke, Regenwurm, Maulwurf, Hamster, Meerschweinchen, Dackel, Mops, Pudel, Katze, Löwe,
                Tiger, Gorilla, Schimpanse, Biber, Otter, Robbe, Walross, Strauß, Kolibri, Tukan, Dinosaurier, Mammut, Einhorn, Drache
                """,
            ),
        ),
        Category(
            "essen", "Essen & Trinken",
            words(
                """
                Pizza, Döner, Currywurst, Brezel, Spaghetti, Burger, Pommes, Hotdog, Sushi, Taco, Pfannkuchen, Waffel, Donut, Muffin,
                Croissant, Spiegelei, Rührei, Müsli, Käsekuchen, Schwarzwälder Kirschtorte, Eis am Stiel, Zuckerwatte, Popcorn,
                Gummibärchen, Lutscher, Schokolade, Kaugummi, Lebkuchen, Bratwurst, Schnitzel, Kartoffelsalat, Knödel, Sauerkraut,
                Leberkäse, Fischstäbchen, Ananas, Wassermelone, Banane, Erdbeere, Kirsche, Zitrone, Kokosnuss, Avocado, Brokkoli,
                Karotte, Zwiebel, Knoblauch, Kürbis, Maiskolben, Paprika, Chili, Pilz, Erdnuss, Honig, Marmelade, Ketchup, Senf,
                Salzstreuer, Teebeutel, Kaffeemaschine, Milchshake, Limonade, Energydrink, Bierkrug, Sektglas, Eiswürfel, Nudelholz,
                Schneebesen, Toaster, Mikrowelle, Pfanne, Grill, Lunchbox, Stäbchen, Dosenöffner, Käsefondue, Raclette
                """,
            ),
        ),
        Category(
            "amongus", "Among Us",
            words(
                """
                Impostor, Crewmate, Notfallknopf, Lüftungsschacht, Sabotage, Leiche, Geist, Meeting, Abstimmung, Rauswurf, Sheriff,
                Jester, Raumschiff, Cafeteria, Reaktor, Sicherheitskamera, Kabelsalat, Kartenleser, Asteroiden, Schutzschild,
                Kommunikation, Sauerstoff, Lichtschalter, Medbay, Scanner, Müllschacht, Tankstelle, Navigation, Lagerraum,
                Elektrik, Admin-Tisch, Vitalmonitor, Türblockade, Mini-Crewmate, Alibi, Verdächtiger, Zeuge, Detektiv, Ingenieur,
                Gestaltwandler, Schutzengel, Wissenschaftler, Bürgermeister, Hacker, Vampir, Bombe, Fußspuren, Luftschleuse,
                Polus, Luftschiff, Pilzwald, Laborkittel, Astronautenhelm, Klopapierhut, Eierhut, Haustier, Stimmzettel, Megafon
                """,
            ),
        ),
        Category(
            "minecraft", "Minecraft",
            words(
                """
                Creeper, Spitzhacke, Diamant, Werkbank, Ofen, Truhe, Enderdrache, Enderman, Zombie, Skelett, Dorfbewohner,
                Eisengolem, Netherportal, Lavasee, Redstone, Kolben, Lore, Schienen, Leuchtfeuer, Zaubertisch, Amboss, Braustand,
                Elytra, Dreizack, Schild, Angel, Bett, Fackel, TNT, Obsidian, Grasblock, Kaktus, Bienenstock, Schleimblock,
                Hexenhütte, Dorf, Mine, Kürbislaterne, Schneegolem, Wither, Phantom, Hexe, Plünderer, Axolotl, Lama, Papagei,
                Fuchs, Schildkrötenei, Totem, Goldapfel, Kuchen, Enderperle, Kompass, Karte, Rüstungsständer, Bücherregal,
                Notenblock, Trichter, Spender, Endkristall, Shulkerkiste, Piglin, Lohe, Ghast, Magmawürfel, Warden, Allay
                """,
            ),
        ),
        Category(
            "internet", "Gaming & Internet",
            words(
                """
                Controller, Headset, Tastatur, Maus, Bildschirm, Streamer, Lagspike, Ladebalken, Bluescreen, WLAN, Router, Passwort,
                Selfie, Hashtag, Emoji, Daumen hoch, Like, Abo-Glocke, Livestream, Speedrun, Bossgegner, Endgegner, Lootbox,
                Highscore, Cheater, AFK, Rage Quit, Tastatur-Krieger, Meme, Katzenvideo, Rickroll, Clickbait, Influencer, Podcast,
                Sprachnachricht, Akku leer, Ladekabel, Powerbank, Handyhülle, Bildschirmriss, Update, Virus, Spam, Troll, Hacker,
                Roboterstaubsauger, Drohne, VR-Brille, Gaming-Stuhl, Energydrink, Pizza-Karton, Nachtschicht, Discord-Ping,
                Stummgeschaltet, Mikrofon an, Bildschirm teilen, Videoanruf, Avatar, Battle Royale, Fallschirmsprung, Schatzkiste,
                Respawn, Heiltrank, Schwert, Zauberstab, Pixel, Würfelglück, Glitch, Spielstand, Game Over
                """,
            ),
        ),
        Category(
            "chaos", "Chaos (schwer)",
            words(
                """
                Montagmorgen, Fremdscham, Zahnarzttermin, Muskelkater, Ohrwurm, Lampenfieber, Schluckauf, Sonnenbrand, Gänsehaut,
                Kopfkino, Fernweh, Heimweh, Schnapsidee, Sturmfrei, Weltuntergang, Zeitreise, Schwerkraft, Stromausfall, Stau,
                Verspätung, Hausaufgaben, Klassenfahrt, Sitzenbleiben, Führerscheinprüfung, Steuererklärung, Kindergeburtstag,
                Familienfeier, Katzenwäsche, Bauchklatscher, Fehlstart, Eigentor, Elfmeter, Fotofinish, Siegerehrung, Flaschenpost,
                Schatzsuche, Geisterstunde, Albtraum, Schlafwandler, Luftgitarre, Karaoke, Flashmob, Polonaise, Schunkeln,
                Fettnäpfchen, Eselsbrücke, Katzensprung, Bärenhunger, Angsthase, Pechvogel, Glückspilz, Spaßbremse, Morgenmuffel,
                Couchpotato, Besserwisser, Dreikäsehoch, Hals über Kopf, Tomaten auf den Augen, Schmetterlinge im Bauch,
                Elefant im Porzellanladen, Nadel im Heuhaufen, Sturm im Wasserglas, Eulen nach Athen tragen, Zwei linke Hände
                """,
            ),
        ),
    )

    val DEFAULT: Set<String> = setOf("alltag", "tiere", "essen", "amongus", "minecraft", "internet")

    fun pool(categories: Set<String>): List<String> = CATEGORIES.filter { it.id in categories }.flatMap { it.words }.distinct()
}
