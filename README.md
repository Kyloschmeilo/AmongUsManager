# Among Us Manager 2

Discord-Bot (**Kotlin**, JDA 6, SQLite) für unseren Among-Us- und Gaming-Server: Level & XP, Wettbüro,
Events mit Anwesenheit, Voice-Steuerung mit Worker-Bots, Minecraft-Kopplung und die automatische
Auswertung der **CrewStats-Mod** ([eigenes Repo](https://github.com/Kyloschmeilo/CrewStats)).

Bis Oktober 2026 war der Bot in Python geschrieben – der letzte Stand liegt im Git-Tag `python-final`.

## Starten

1. **Java 17 oder neuer** installieren (zum Bauen ein JDK 17+).
2. `.env.example` nach `.env` kopieren und die Tokens eintragen
   (`DISCORD_TOKEN`, optional `WORKER_TOKENS` kommagetrennt). **`.env` niemals hochladen.**
3. Starten:
   - IntelliJ: Run-Config **„Bot starten“** (Gradle-Task `run`)
   - oder im Terminal: `gradlew run`
   - oder als fertiges JAR: `gradlew shadowJar`, dann `java -jar build/libs/AmongUsManager.jar`

Alle Discord-IDs (Channels, Rollen) und Spielregeln stehen in
`src/main/kotlin/de/amongusmanager/crewbot/Config.kt`.

## Daten

Alles liegt in der SQLite-Datenbank `data/crewbot.db` (wird beim ersten Start angelegt).
Liegen beim ersten Start noch die JSON-Dateien des alten Python-Bots in `data/`, werden sie
**automatisch übernommen** und bleiben unverändert als Backup liegen.

## Aufbau (`src/main/kotlin/de/amongusmanager/crewbot/`)

| Ordner | Inhalt |
|---|---|
| `core/` | Bot-Klasse (Discord-Verbindung, Ereignisse), Slash-Command-Framework, Hilfen |
| `db/` | Datenbank, Tabellen (`Schema.kt`) und Import der alten JSON-Dateien |
| `services/` | Logik ohne UI: Level, Wetten, Events, Auto-Mute, Mod-Runden, … |
| `ui/` | Embeds, Buttons und Menüs (`ui/views/`) |
| `content/` | Texte & Daten: Rollen, Maps, Erfolge, Einstellungsnamen |
| `modules/` | Slash-Commands nach Bereich (erscheinen automatisch in `/hilfe`) |

## Tests

`gradlew test` – vergleicht Logik, Embeds, Befehle und den Datenimport mit Referenzdaten, die
mit dem originalen Python-Bot erzeugt wurden (`src/test/resources/golden`, Generator in `tools/golden`).

Mehr Details (Entscheidungen, Mod-Schnittstelle, Deploy) in [`CLAUDE.md`](CLAUDE.md).

## Deploy (Pelican-Panel)

Das Panel braucht einen **Java-Egg** (Java 17+), Startbefehl `java -jar AmongUsManager.jar`.
`AmongUsManager.jar` und `.env` hochladen und den Bot neu starten.
`data/` auf dem Server **nicht** überschreiben – dort liegen die echten Spielerdaten.
