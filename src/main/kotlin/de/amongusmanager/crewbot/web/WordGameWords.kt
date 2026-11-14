/* Geheimfunk (Wort-Ratespiel der Website): eigene deutsche Wortpakete. Wörter in Großbuchstaben, je Paket ohne Doppelte. */
package de.amongusmanager.crewbot.web

object WordGameWords {
    class Pack(val id: String, val name: String, val about: String, val words: List<String>)

    private fun words(text: String): List<String> = text.split(',', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

    val CLASSIC = Pack(
        "klassisch", "Klassisch", "Alltagswörter mit vielen Bedeutungen",
        words(
            """
            Anker, Apfel, Arm, Auge, Bahn, Ball, Band, Bank, Bart, Bär, Batterie, Bauer, Baum, Becken, Berg, Bett, Biene, Bild,
            Birne, Blatt, Blitz, Block, Blume, Boden, Bogen, Bohne, Boot, Brause, Brief, Brille, Brot, Brücke, Brunnen, Buch,
            Burg, Bus, Busch, Butter, Dach, Dame, Decke, Diamant, Dieb, Doktor, Drache, Draht, Dose, Dusche, Ei, Eimer, Eis,
            Elefant, Engel, Ente, Erde, Essen, Eule, Fackel, Faden, Fahne, Falle, Fass, Feder, Feld, Fenster, Feuer, Film,
            Finger, Fisch, Flasche, Fleck, Fliege, Flügel, Fluss, Form, Frosch, Fuchs, Fuß, Gabel, Garten, Gas, Geist, Geld,
            Gericht, Gift, Gitter, Glas, Glocke, Glück, Gold, Grab, Gras, Grenze, Griff, Gurke, Gürtel, Haar, Hafen, Hahn,
            Hals, Hammer, Hand, Harfe, Hase, Haus, Haut, Heft, Held, Hemd, Herz, Hexe, Himmel, Hirsch, Hof, Höhle, Holz,
            Honig, Horn, Hose, Hotel, Hund, Hut, Igel, Insel, Jacke, Jäger, Kabel, Käfer, Kaffee, Käfig, Kaiser, Kamm,
            Kanal, Kanone, Kappe, Karte, Käse, Kasse, Kasten, Katze, Keks, Keller, Kerze, Kette, Kiefer, Kino, Kirche,
            Kissen, Kiste, Klavier, Klee, Knochen, Knopf, Knoten, Koch, Koffer, Kohle, König, Kopf, Korb, Korken, Krake,
            Kran, Kranz, Kreis, Kreuz, Krone, Küche, Kuchen, Kugel, Kuh, Kunst, Kürbis, Kutsche, Lampe, Land, Laser, Laub,
            Läufer, Leiter, Licht, Linie, Linse, Löffel, Löwe, Luft, Lunge, Mantel, Mark, Markt, Maske, Mast, Mauer, Maus,
            Meer, Messer, Milch, Mine, Mond, Moos, Motor, Mühle, Mund, Münze, Muschel, Mutter, Nacht, Nadel, Nagel, Nase,
            Nebel, Nest, Netz, Note, Nuss, Ofen, Ohr, Öl, Oper, Orange, Orden, Paket, Palme, Papier, Park, Pass, Pferd,
            Pfeife, Pfeil, Pflaster, Pilot, Pilz, Pinguin, Pinsel, Pirat, Plan, Platte, Platz, Pol, Polizei, Post, Pumpe,
            Punkt, Puppe, Quelle, Rad, Rakete, Rand, Ratte, Rauch, Raum, Regen, Reifen, Reihe, Riegel, Riese, Ring, Ritter,
            Rock, Rohr, Rolle, Rose, Rost, Rücken, Ruder, Sack, Saft, Säge, Saite, Salz, Sand, Sarg, Satz, Schale, Schatten,
            Schatz, Schere, Schiff, Schild, Schirm, Schlange, Schloss, Schlüssel, Schnee, Schnur, Schokolade, Schrank,
            Schraube, Schuh, Schule, Schuppen, Schwert, See, Seife, Seil, Sessel, Sieb, Silber, Sohle, Sonne, Spiegel,
            Spiel, Spinne, Spitze, Stab, Stadt, Stamm, Stange, Stau, Stein, Stempel, Stern, Stiefel, Stift, Stock, Strand,
            Straße, Strauß, Strom, Stuhl, Sturm, Suppe, Tafel, Tanz, Tasche, Tasse, Taste, Taube, Tau, Teller, Tempel,
            Teppich, Tiger, Tinte, Tisch, Ton, Tonne, Topf, Tor, Torte, Trommel, Tropfen, Tuch, Tür, Turm, Uhr, Vogel,
            Vulkan, Waage, Wagen, Wal, Wald, Wand, Wanne, Wasser, Watte, Weg, Welle, Welt, Wetter, Wiese, Wind, Wirbel,
            Wolf, Wolke, Wurm, Wurst, Wurzel, Wüste, Zahn, Zange, Zaun, Zelt, Ziege, Zirkus, Zitrone, Zucker, Zug, Zunge,
            Zweig, Zwerg, Zwiebel
            """,
        ),
    )

    val GAMING = Pack(
        "zocken", "Zocken & Internet", "Gaming, Discord und alles mit Bildschirm",
        words(
            """
            Impostor, Crewmate, Vent, Sabotage, Notfall, Lobby, Meeting, Leiche, Aufgabe, Reaktor, Kamera, Admin, Host,
            Server, Ping, Lag, Bug, Patch, Update, Mod, Cheat, Bann, Kick, Mute, Stream, Clip, Chat, Emoji, Meme, Troll,
            Bot, Rang, Level, Skin, Loot, Boss, Quest, Spawn, Respawn, Speedrun, Tutorial, Controller, Tastatur, Headset,
            Mikrofon, Bildschirm, Pixel, Avatar, Clan, Gilde, Turnier, Pokal, Highscore, Combo, Schild, Trank, Schwert,
            Bogen, Zauber, Drache, Zombie, Creeper, Diamant, Spitzhacke, Redstone, Nether, Portal, Dorf, Kiste, Block,
            Würfel, Karte, Joker, Schach, Bauer, Turm, Dame, König, Rakete, Panzer, Sniper, Granate, Kreis, Zone, Fallschirm,
            Bus, Tanz, Sieg, Niederlage, Rage, Noob, Profi, Carry, Tank, Heiler, Magier, Dieb, Ritter, Pirat, Ninja, Roboter,
            Alien, Raumschiff, Planet, Laser, Passwort, Link, Like, Abo, Glocke, Trend, Hype, Fail, Rekord, Wette, Münze,
            Shop, Truhe, Schlüssel, Falle, Geist, Maske, Signal, Akku, Kabel, WLAN, Cloud, Virus, Hacker, Code, Taste
            """,
        ),
    )

    val FOOD = Pack(
        "futter", "Futter & Quatsch", "Essen, Tiere und Unsinn für späte Runden",
        words(
            """
            Döner, Pizza, Pommes, Ketchup, Mayo, Brezel, Nudel, Spätzle, Knödel, Schnitzel, Currywurst, Burger, Taco, Sushi,
            Reis, Müsli, Toast, Marmelade, Nutella, Gummibär, Lakritz, Popcorn, Chips, Keks, Waffel, Pfannkuchen, Eisbecher,
            Sahne, Kakao, Limo, Cola, Energydrink, Eistee, Sprudel, Gurke, Tomate, Zwiebel, Knoblauch, Paprika, Chili, Senf,
            Essig, Pfeffer, Zimt, Banane, Ananas, Melone, Kirsche, Erdbeere, Kiwi, Mango, Kokosnuss, Avocado, Kartoffel,
            Möhre, Brokkoli, Spinat, Lama, Faultier, Waschbär, Otter, Robbe, Qualle, Hai, Krabbe, Flamingo, Papagei, Gans,
            Huhn, Schwein, Esel, Kamel, Giraffe, Nashorn, Gorilla, Hamster, Maulwurf, Dachs, Biber, Elch, Yeti, Einhorn,
            Vampir, Werwolf, Mumie, Clown, Zauberer, Opa, Oma, Nachbar, Chef, Lehrer, Hausmeister, Schwiegermutter, Socke,
            Badehose, Jogginghose, Schlafanzug, Perücke, Schnurrbart, Glatze, Furz, Rülpser, Schluckauf, Muskelkater,
            Montag, Wecker, Stau, Baustelle, Steuer, Zahnarzt, Hausaufgabe, Klopapier, Mülltonne, Staubsauger, Fernbedienung,
            Sofa, Bademantel, Gartenzwerg, Grill, Bierbank, Kirmes, Karaoke, Polonaise, Lagerfeuer, Zeltlager
            """,
        ),
    )

    val PACKS: List<Pack> = listOf(CLASSIC, GAMING, FOOD)

    fun pack(id: String): Pack? = PACKS.firstOrNull { it.id == id }

    /** Eigene Wörter des Hosts: getrennt durch Komma/Zeilen, 2–18 Zeichen, nur Buchstaben/Ziffern/Bindestrich/Leerzeichen, höchstens [limit]. */
    fun custom(text: String, limit: Int = 300): List<String> = text.split(',', ';', '\n').asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ").uppercase() }
        .filter { it.length in 2..18 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == ' ' } }
        .distinct().take(limit).toList()
}
