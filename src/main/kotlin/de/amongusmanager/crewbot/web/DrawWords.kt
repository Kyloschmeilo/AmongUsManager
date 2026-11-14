/* Kritzelkiste (Zeichnen und Raten): Wortlisten nach Kategorie. Nur Begriffe, die man zeichnen kann. */
package de.amongusmanager.crewbot.web

object DrawWords {
    /** [group] ordnet die Pakete in der Lobby (Deutsch, English). */
    class Category(val id: String, val label: String, val words: List<String>, val group: String = "Deutsch")

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
        Category(
            "filme", "Filme & Serien",
            words(
                """
                Titanic, Harry Potter, Herr der Ringe, Star Wars, Spider-Man, Batman, Superman, Hulk, Iron Man, Thor, Avengers,
                Shrek, Findet Nemo, Toy Story, König der Löwen, Die Eiskönigin, Cars, Minions, Ice Age, Madagaskar, Kung Fu Panda,
                Jurassic Park, King Kong, Godzilla, Der weiße Hai, E.T., Zurück in die Zukunft, Ghostbusters, Matrix, Terminator,
                Fluch der Karibik, Indiana Jones, James Bond, Mission Impossible, Fast and Furious, Transformers, Pokémon, SpongeBob,
                Die Simpsons, Peppa Wutz, Biene Maja, Pumuckl, Die Sendung mit der Maus, Sesamstraße, Stranger Things, Squid Game,
                Game of Thrones, Breaking Bad, Wednesday, Barbie, Oppenheimer, Avatar, Ratatouille, Oben, Alles steht Kopf, Coco,
                Vaiana, Rapunzel, Aladdin, Arielle, Schneewittchen, Dornröschen, Pinocchio, Bambi, Dschungelbuch, Tarzan, Mulan,
                Die Unglaublichen, Monster AG, WALL-E, Paddington, Wallace und Gromit, Asterix, Lucky Luke, Tatort, Wer wird Millionär
                """,
            ),
        ),
        Category(
            "minecraft_en", "Minecraft Items",
            words(
                """
                Diamond Sword, Diamond Pickaxe, Netherite Axe, Iron Shovel, Golden Hoe, Wooden Sword, Stone Pickaxe, Bow, Crossbow,
                Arrow, Trident, Shield, Fishing Rod, Flint and Steel, Shears, Bucket, Water Bucket, Lava Bucket, Milk Bucket, Compass,
                Clock, Map, Spyglass, Lead, Name Tag, Saddle, Elytra, Totem of Undying, Ender Pearl, Eye of Ender, Blaze Rod,
                Ghast Tear, Slimeball, Gunpowder, Redstone Dust, Glowstone Dust, Lapis Lazuli, Emerald, Diamond, Gold Ingot,
                Iron Ingot, Copper Ingot, Netherite Ingot, Coal, Charcoal, Stick, String, Feather, Leather, Bone, Bone Meal, Book,
                Enchanted Book, Book and Quill, Paper, Sugar Cane, Wheat, Bread, Cake, Cookie, Pumpkin Pie, Apple, Golden Apple,
                Enchanted Golden Apple, Golden Carrot, Carrot, Potato, Baked Potato, Poisonous Potato, Melon Slice, Glistering Melon,
                Beetroot Soup, Mushroom Stew, Rabbit Stew, Cooked Porkchop, Steak, Rotten Flesh, Spider Eye, Pufferfish,
                Tropical Fish, Honey Bottle, Potion, Splash Potion, Glass Bottle, Experience Bottle, Firework Rocket, Snowball, Egg,
                Turtle Helmet, Iron Chestplate, Diamond Boots, Leather Cap, Chainmail Leggings, Carved Pumpkin, Jack o'Lantern,
                Torch, Lantern, Soul Lantern, Campfire, Bed, Chest, Ender Chest, Shulker Box, Barrel, Crafting Table, Furnace,
                Blast Furnace, Smoker, Anvil, Enchanting Table, Brewing Stand, Cauldron, Composter, Beacon, Conduit, Jukebox,
                Music Disc, Note Block, Hopper, Dropper, Dispenser, Observer, Piston, Sticky Piston, Lever, Button, Tripwire Hook,
                Pressure Plate, Redstone Torch, Redstone Lamp, Repeater, Comparator, Daylight Detector, TNT, Rail, Powered Rail,
                Minecart, Boat, Armor Stand, Item Frame, Painting, Flower Pot, Bell, Lightning Rod, Respawn Anchor, Lodestone,
                Recovery Compass, Echo Shard, Heart of the Sea, Nautilus Shell, Nether Star, Dragon Egg, Dragon Head, Creeper Head,
                Wither Skeleton Skull, Goat Horn, Bundle, Brush, Mace, Wind Charge, Trial Key, Heavy Core
                """,
            ),
            group = "English",
        ),
        Category(
            "minecraft_mobs_en", "Minecraft Mobs & Blocks",
            words(
                """
                Creeper, Zombie, Skeleton, Spider, Cave Spider, Enderman, Ender Dragon, Wither, Witch, Slime, Magma Cube, Ghast,
                Blaze, Piglin, Zombified Piglin, Hoglin, Strider, Phantom, Drowned, Husk, Stray, Pillager, Vindicator, Evoker,
                Ravager, Vex, Guardian, Elder Guardian, Shulker, Silverfish, Endermite, Warden, Breeze, Iron Golem, Snow Golem,
                Villager, Wandering Trader, Allay, Axolotl, Bee, Cat, Chicken, Cow, Mooshroom, Pig, Sheep, Horse, Donkey, Llama,
                Wolf, Fox, Panda, Parrot, Rabbit, Turtle, Dolphin, Squid, Glow Squid, Frog, Tadpole, Goat, Camel, Sniffer,
                Armadillo, Bat, Polar Bear, Ocelot, Grass Block, Dirt, Cobblestone, Oak Log, Oak Planks, Sand, Gravel, Glass,
                Obsidian, Bedrock, Netherrack, Soul Sand, End Stone, Sponge, Ice, Snow Block, Cactus, Bamboo, Kelp, Cobweb,
                Diamond Ore, Gold Ore, Iron Ore, Coal Ore, Mob Spawner, Nether Portal, End Portal, Ladder, Door, Trapdoor, Fence,
                Lily Pad, Sunflower, Poppy, Dandelion, Sweet Berry Bush, Scaffolding, Honey Block, Slime Block, Glowstone,
                Sea Lantern, Magma Block, Amethyst Cluster, Sculk Sensor, Mud, Moss Block, Dripstone, Pointed Dripstone, Hay Bale
                """,
            ),
            group = "English",
        ),
        Category(
            "amongus_en", "Among Us",
            words(
                """
                Impostor, Crewmate, Emergency Meeting, Dead Body, Report, Vent, Sabotage, Reactor Meltdown, Oxygen Depleted,
                Lights Out, Comms Sabotaged, Door Lock, Kill Cooldown, Ejected, Skip Vote, Voting Screen, Task Bar, Card Swipe,
                Fix Wiring, Empty Garbage, Clear Asteroids, Download Data, Upload Data, Fuel Engines, Align Engine Output,
                Calibrate Distributor, Chart Course, Clean O2 Filter, Divert Power, Inspect Sample, Prime Shields, Stabilize Steering,
                Start Reactor, Submit Scan, Unlock Manifolds, Admin Table, Security Cameras, Vitals, Door Log, Cafeteria,
                Medbay, Electrical, Navigation, Storage, Weapons, Shields, Lower Engine, Upper Engine, Ghost, Guardian Angel,
                Shapeshifter, Scientist, Engineer, Tracker, Noisemaker, Phantom, Mini Crewmate, Pet, Hat, Visor, Skin, Lobby,
                Game Code, Freeplay, Kill Animation, Sus, Self Report, Third Impostor, Double Kill, Victory, Defeat
                """,
            ),
            group = "English",
        ),
        Category(
            "games_en", "Video Games",
            words(
                """
                Minecraft, Fortnite, Among Us, Roblox, Mario Kart, Super Mario, Zelda, Pokémon, Tetris, Pac-Man, Space Invaders,
                Pong, Sonic, Donkey Kong, Kirby, Pikachu, Yoshi, Bowser, Luigi, Princess Peach, Link, Master Chief, Lara Croft,
                Kratos, Steve, Creeper, Fall Guys, Rocket League, League of Legends, Counter-Strike, Valorant, Overwatch,
                Call of Duty, GTA, Red Dead Redemption, The Sims, Animal Crossing, Stardew Valley, Terraria, Portal, Half-Life,
                Doom, Pokéball, Angry Birds, Candy Crush, Flappy Bird, Subway Surfers, Clash of Clans, Clash Royale, Geometry Dash,
                Five Nights at Freddy's, Undertale, Hollow Knight, Celeste, Cuphead, Dark Souls, Elden Ring, Skyrim, Fallout,
                Pokémon Go, Wii Sports, Just Dance, Guitar Hero, Rayman, Crash Bandicoot, Spyro, Pinball, Game Boy, Joystick,
                Controller, Headset, Gaming Chair, Respawn, Boss Fight, Speedrun, Lag, Rage Quit, Loot Box, Level Up, Game Over,
                High Score, Save Point, Extra Life, Power-Up, Checkpoint, Easter Egg, Final Boss, Health Bar, Mana Potion
                """,
            ),
            group = "English",
        ),
        Category(
            "memes_en", "Memes & Internet",
            words(
                """
                Rickroll, Doge, Grumpy Cat, Nyan Cat, Trollface, Pepe, Stonks, This is fine, Distracted Boyfriend, Shrek,
                Big Chungus, Amogus, Sus, Skibidi Toilet, Gigachad, Sigma, Rizz, Ohio, Bonk, Cheems, Harambe, Surprised Pikachu,
                Woman yelling at cat, Hide the Pain Harold, Success Kid, Bad Luck Brian, Ermahgerd, Keyboard Cat, Dramatic Chipmunk,
                Grumpy Baby, Dancing Baby, Leeroy Jenkins, Planking, Ice Bucket Challenge, Harlem Shake, Gangnam Style, Floss Dance,
                Dab, Fidget Spinner, Bottle Flip, Mannequin Challenge, Tide Pod, Area 51, Big Brain, Galaxy Brain, Facepalm, Thumbs Up,
                OK Boomer, Karen, NPC, Main Character, Touch Grass, Clickbait, Influencer, Livestream, Unboxing, Selfie Stick,
                Ring Light, Like Button, Subscribe, Notification Bell, Hashtag, Emoji, Thumbnail, Reaction Video, Mukbang, ASMR,
                Podcast, Vlog, Meme Lord, Cat Video, Dog Filter, Duck Face, Photobomb, Lag Spike, Buffering, Error 404, Wi-Fi,
                Loading Bar, Captcha, Cookie Banner, Spam, Troll, Noob, Pro Gamer, GG, AFK
                """,
            ),
            group = "English",
        ),
        Category(
            "basics_en", "English Basics",
            words(
                """
                House, Tree, Car, Bike, Bus, Train, Plane, Ship, Rocket, Moon, Sun, Star, Cloud, Rain, Snow, Rainbow, Lightning,
                Mountain, River, Island, Beach, Volcano, Desert, Forest, Bridge, Castle, Tower, Church, School, Hospital,
                Library, Garden, Fountain, Lighthouse, Windmill, Tent, Ladder, Umbrella, Glasses, Hat, Shoe, Sock, Shirt, Dress,
                Crown, Ring, Watch, Key, Lock, Door, Window, Chair, Table, Bed, Lamp, Clock, Mirror, Toothbrush, Pillow, Blanket,
                Phone, Laptop, Camera, Television, Guitar, Piano, Drum, Trumpet, Microphone, Headphones, Book, Pencil, Scissors,
                Glue, Paintbrush, Envelope, Balloon, Gift, Candle, Cake, Pizza, Burger, Hot Dog, Ice Cream, Donut, Popcorn,
                Banana, Apple, Cherry, Strawberry, Pineapple, Watermelon, Carrot, Corn, Egg, Cheese, Sandwich, Coffee, Teapot,
                Dog, Cat, Horse, Cow, Pig, Sheep, Chicken, Duck, Frog, Snake, Shark, Whale, Octopus, Crab, Turtle, Elephant,
                Giraffe, Lion, Tiger, Monkey, Penguin, Owl, Bee, Butterfly, Spider, Snail, Dragon, Unicorn, Ghost, Robot, Alien,
                Pirate, Wizard, Witch, Vampire, Mermaid, Superhero, Ninja, Knight, Princess, Snowman, Scarecrow, Skeleton
                """,
            ),
            group = "English",
        ),
    )

    val DEFAULT: Set<String> = setOf("alltag", "tiere", "essen", "amongus", "minecraft", "internet")

    fun pool(categories: Set<String>): List<String> = CATEGORIES.filter { it.id in categories }.flatMap { it.words }.distinct()
}
