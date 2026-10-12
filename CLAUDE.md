# Among Us Manager 2

Discord-Bot (**Kotlin**, JDA 6.7, SQLite oder MySQL/MariaDB) für einen deutschen Among-Us-/Gaming-Server plus eine
Among-Us-Mod „CrewStats“ (C#, BepInEx), die Rundendaten automatisch an den Bot schickt.
Kommunikation mit dem Nutzer: **Deutsch**.

Seit 10.10.2026 in Kotlin; der alte Python-Bot (discord.py) liegt im Git-Tag `python-final`.

## Starten & Bauen
- Tokens in `.env` (`DISCORD_TOKEN`, `WORKER_TOKENS` kommagetrennt) – niemals in den Code schreiben.
  Echte Umgebungsvariablen haben Vorrang vor der `.env`.
- Datenbank: ohne weitere Angaben SQLite `data/crewbot.db`. Mit `DB_HOST`, `DB_NAME`, `DB_USER`,
  `DB_PASSWORD` in der `.env` MySQL/MariaDB (Panel-Datenbank `s12_spieleabend` auf `45.81.233.92:3306` –
  nur aus dem Panel-Netz erreichbar, von zu Hause nicht; lokal daher SQLite benutzen). Treiber:
  MariaDB Connector/J (kann MySQL und MariaDB), direkt instanziiert (nicht über DriverManager).
- Gradle-Wrapper (Gradle 9.7.1), Kotlin 2.4.21, Bytecode **Java 17** (`-Xjdk-release=17`).
  Lokal mit JDK 21 bauen: `JAVA_HOME=C:\Users\petra\.jdks\ms-21.0.9` (JDK 23 unter `C:\Program Files\Java`).
  - `gradlew run` (IntelliJ-Run-Config „Bot starten“), Arbeitsordner = Projektordner (`.env`, `data/`)
  - `gradlew test` – alle Tests (müssen grün sein)
  - `gradlew shadowJar` → `build/libs/AmongUsManager.jar` (Fat-JAR, ~33 MB, `java -jar …`)
- Produktiv läuft der Bot auf einem **Pelican-Panel** eines Freundes – ohne Zugang für Claude.
  Das Panel braucht einen **Java-Egg (17+)**, Startbefehl `java -jar AmongUsManager.jar`.
  Deploy = `AmongUsManager.jar` (+ `.env`) hochladen; `data/` auf dem Server NICHT überschreiben
  (echte Spielerdaten). Dafür baut Claude `C:\Users\petra\Desktop\crewbot-update.zip` (nur das JAR),
  der Nutzer lädt es hoch und startet den Bot neu.
- **Nie den echten Token lokal starten, solange der Panel-Bot läuft**: zwei Instanzen würden
  Runden/Buttons doppelt verarbeiten. Start-Tests mit Fake-Token in einem Temp-Ordner
  (`CREWBOT_DATA_DIR` setzt den Datenordner).

## Repos (GitHub-Account Kyloschmeilo)
- Bot: **privat** https://github.com/Kyloschmeilo/AmongUsManager (Projektordner, Branch `main`).
  `.env`, `data/` (echte Spielerdaten, `crewbot.db`) und `mod/` sind per `.gitignore` ausgeschlossen –
  vor jedem Commit sicherstellen, dass das so bleibt (staged Dateien + Inhalt auf Tokens/Webhook-URLs prüfen).
- Mod: **öffentlich** https://github.com/Kyloschmeilo/CrewStats = eigenes git-Repo im Ordner `mod/`.
- **Funktionierende (gebaute/getestete) Änderungen immer sofort committen und pushen** – Bot-Repo
  und Mod-Repo. Ein neues Mod-*Release* (`mod/release.ps1`, aktualisiert alle Spieler) nur auf Wunsch.
- git-Identität in beiden Repos: Kyloschmeilo / `216764438+Kyloschmeilo@users.noreply.github.com`
  (nie die Gmail-Adresse). `gh` liegt in `C:\Program Files\GitHub CLI` (evtl. nicht im PATH),
  git nutzt den gh-Login (`gh auth setup-git`).
- **Windows PowerShell 5 zerlegt Anführungszeichen in Argumenten an Programme**: Commit-Nachrichten
  per `git commit -F <datei>` übergeben (aus Bash), nie Text mit `"` direkt. `release.ps1` übergibt
  die Notizen deshalb als Datei. Native stderr + `$ErrorActionPreference='Stop'` bricht ab →
  `Invoke-Native` in `release.ps1`.

## Aufbau (`src/main/kotlin/de/amongusmanager/crewbot/`)
- `Config.kt` – alle Discord-IDs, Spielregeln (`Rules`), Pfade (`BotPaths`), .env-Loader (`Env`).
  Channels: `CODES` (Lobby-Codes + automatische Lobby-Ankündigung), `LOG` (Admin-/Host-Log,
  Rollen-Log, Minecraft-Rohdaten = `MINECRAFT_INBOX`), `AMONG_US_VOICE`, `AUTO_UNMUTE_VOICE`.
- `Main.kt` – Start: `.env` laden, Datenbank öffnen, JSON-Import, Bot starten, Shutdown-Hook.
- `core/CrewBot.kt` – erzeugt alle Services und Module, verbindet JDA, synchronisiert Slash-Commands,
  leitet Ereignisse weiter (`Router`) und verteilt Button-Klicks (feste custom_ids + `ViewRegistry`).
  `core/Async.kt` – **alle Handler laufen auf EINEM Thread** (`BotThread`, wie asyncio): Coroutines
  pausieren bei Discord-Anfragen (`RestAction.await()`), aber nie zwei Handler gleichzeitig.
  `core/Commands.kt` – `SlashCommand` (Name, Text, Recht, Optionen, Handler) und `CrewModule`.
  `core/Json.kt` – JSON-Helfer mit Python-Semantik (`pyInt`, `pyStr`, `pyTruthy` …).
- `db/` – `Database.kt` (eine Verbindung, `transaction {}` atomar & verschachtelbar, Tabelle `settings`
  als Schlüssel/Wert; `Dialect` SQLITE/MYSQL mit Helfern `insertIgnore`, `insertOrReplace`, `greatest`,
  `onConflictUpdate`/`excluded`, `keepNewest`; MySQL verbindet bei abgelaufener Verbindung neu),
  `Schema.kt` (Tabellen je Dialekt – Änderungen in BEIDEN Listen als neue Version anhängen; Version in
  `PRAGMA user_version` bzw. Tabelle `schema_version`), `JsonImport.kt` (einmaliger Import der alten
  JSON-Dateien, Marker `meta.json_import`), `SqliteToMysql.kt` (erster Start mit MySQL kopiert eine
  vorhandene `data/crewbot.db`). Neues SQL muss in SQLite UND MySQL laufen: Spalten `key`/`value` in
  Backticks, keine `rowid`-Tricks außer `ORDER BY rowid` (MySQL hat dafür eigene `rowid`-Spalten),
  keine Subqueries mit LIMIT, IDs als BIGINT.
- `services/` – Logik ohne UI, erreichbar als `bot.<name>`: `levels` (XP/Level/Ränge/Level-Rollen),
  `bets` (Wettbüro + Sperre), `rounds` (Rundenende), `events` (Erinnerung, Anwesenheit, Abschluss),
  `audit` (Log-Channel), `workers` (Worker-Bots fürs parallele Muten: `editVoice`, `setMute`,
  `release`), `voiceStatus` (Channel-Name gedrosselt), `links` (Friendcode ↔ Discord), `gameStats`,
  `modRounds` (Auswertung + Undo, `ModRoundModel.kt` = Rundenformat), `automute`, `gameSettings`
  (`/rundensettings`, nur neuester Stand), `minecraft`, `roundHistory` (jede Mod-Runde im Detail,
  normalisiert in `round_history*`-Tabellen, max. 1000), `Insights.kt` (reine Rechenfunktionen:
  Highlights, Rivalen, Recap/MVP-Kandidaten), `achievements` (Erfolge mit XP), `lobby`
  (Lobby-Ankündigung + Wettsperre). Mehrteilige Änderungen laufen in einer DB-Transaktion; Discord-
  Aufrufe (Level-Rollen) erst danach.
- `content/` – Texte & Daten: `AmongUs.kt` (Rollen für `/rolle` inkl. Influencer, Maps),
  `GameSettingsContent.kt` (deutsche Namen/Kategorien der Lobby-Optionen), `Achievements.kt`
  (Erfolge – ids nie umbenennen, sind gespeichert), `Minecraft.kt`.
- `ui/` – `Embeds.kt` (`Palette`, `Tone`, `notify()`, `progressBar`, `fitLines`, `safeBuild`),
  `Text.kt` (`escapeMarkdown`/`urlQuote` wie discord.py/Python), `InsightsUi.kt`, `ProfileUi.kt`,
  `LobbyUi.kt`, `MinecraftUi.kt`, `views/` (Buttons/Menüs). Menüs mit Timeout erben von
  `SessionView` (custom_id `cv:<id>:<name>`, Besitzer-Prüfung, Timeout); dauerhafte Buttons alter
  Nachrichten haben feste custom_ids (siehe unten).
- `modules/` – Slash-Commands nach Bereich (früher Cogs), Name + Emoji erscheinen automatisch in
  `/hilfe` (Reihenfolge = Liste in `CrewBot.modules`). `/emergency` wurde bewusst entfernt.

## Wichtige Entscheidungen
- **Die Mod muss fair bleiben**: keine versteckten Cheats/Vorteile (z.B. heimlich Impostor
  bestimmen wurde abgelehnt). Während der Runde zeigt die Mod nichts an. Hausregeln nur offen
  (Chat-Hinweis) und als Option.
- custom_ids bestehender Buttons (`persistent_control_panel:*`, `bt_move`, `evt_*_p`,
  `persistent_role_dropdown`, `crewstats:undo:<gameId>`, `selfrole:<roleId>`) nicht ändern – sonst gehen alte Nachrichten kaputt.
- Rollen-Panel (`/rollen-panel`, Admin): Gruppen in `SELF_ROLE_GROUPS` (`Config.kt`, eine Nachricht je Gruppe,
  `exclusive` = nur eine Rolle der Gruppe). Buttons zeigen die Mitgliederzahl; gepostete Nachrichten stehen in
  `settings` (`rolepanel.messages`) und werden bei Rollenänderungen gebündelt (2 s) aktualisiert.
- Event-XP gibt es erst nach dem Event und nur bei ≥ `EVENT_MIN_ATTENDANCE_MINUTES` im Voice.
  Events werden nach Start + `EVENT_DURATION_HOURS` automatisch gesperrt; danach automatischer
  Spieleabend-Recap, falls Mod-Runden aufgezeichnet wurden.
- Level sinkt nie durch Wetteinsätze (nur Admin-Panel/Undo setzen es zurück).
- Admin-Panel-Änderungen werden gebündelt (10 s) in den Log-Channel geschrieben.
- Leaderboard: Medaillen (🥇🥈🥉) im Nickname; beim Sync werden alle Server-Mitglieder mit Medaille
  geprüft (nicht nur das Ranking), Lock gegen parallele Aufrufe. Owner/höhere Rollen nicht änderbar.
- Mod → Bot läuft über einen **Discord-Webhook** (kein offener Port auf dem Panel nötig).
  Der Bot akzeptiert nur Nachrichten seines eigenen Webhooks (`settings`: `mod.webhook_id`, via
  `/mod-setup` – erneuert die URL, die alte wird ungültig → URL in der Mod-Config neu eintragen).
  Webhook-URLs baut der Bot selbst als `https://discord.com/api/webhooks/<id>/<token>` (JDA liefert
  `/api/v10/…`, die Mod akzeptiert nur `/api/webhooks/`!).
  Unterscheidung im Modul: Auto-Mute-JSON / Lobby-JSON im Text, `crewstats-settings.json` bzw.
  `crewstats-round.json` als Anhang. Meldungen > 60 s alt werden ignoriert, alles wird gelöscht.
  Runden werden nacheinander ausgewertet (Mutex) – doppelt gesendete zählen nur einmal.
- Mod-Runden und manuelle Auswertung (`/crew-win`, Runden-Assistent) nicht doppelt nutzen.
- Mod-Runde auswerten = Stats → Verlauf → Erfolge → Protokoll (alles in einer Transaktion), danach
  Level-Rollen. „Zurücksetzen“ nimmt alles zurück (XP, Siege, Wetten, Stats, Verlauf, Erfolge).
  Wie im alten Bot zeigen alle XP-Zeilen eines Spielers den **End-Level** der Runde, und das Protokoll
  speichert ihn als `new_level`; Undo in Vergabe-Reihenfolge landet dadurch beim Level vor der Runde.
  Highlights/Rivalen/Recap werden immer live aus dem Verlauf berechnet (nichts doppelt gespeichert).
  Unverknüpfte Spieler zählen dort unter ihrem Ingame-Namen.
- Wettbüro: Lobby-Status „started“ (Mod, Host) sperrt `/bet`; offen wieder nach der Auswertung
  (`settle`), `/runde-abbrechen`, 30 s nach Rundenende ohne Ergebnis oder spätestens nach 45 min.
  Sperre nur im Speicher (Neustart = offen).
- Slash-Commands gibt es nur auf dem Server (Kontext GUILD); Texte/Optionen sind identisch zum alten Bot.
- Bewusste kleine Abweichungen zum Python-Bot: MVP-Voting endet fest nach 60 s (wie angezeigt; früher
  verlängerte jede Stimme), Event-Footer bekommt „Anmeldung geschlossen“ nur einmal, abgelaufene Menüs
  antworten mit einem Hinweis statt „Interaktion fehlgeschlagen“, Embeds werden nie über Discords
  6000-Zeichen-Grenze gebaut (`safeBuild`).

## Minecraft-Kopplung (CoreSMP-Plugin `CoreHold`, Projekt `C:\Users\petra\Desktop\CoreSMP`)
- Plugin → Bot ebenfalls per **Webhook**, der im Log-Channel (`Channels.MINECRAFT_INBOX` =
  `Channels.LOG`) liegt; der Bot verschiebt ihn beim Start dorthin (URL bleibt gleich, braucht
  „Webhooks verwalten“). Das Plugin schickt höchstens 1×/s JSON im Text: `{"v":1,"server":…,"events":[…]}`.
  Der Bot (`modules/MinecraftModule.kt`) löscht die Rohdaten und postet Embeds (`ui/MinecraftUi.kt`) im
  eingestellten Channel. Arten: `join`, `leave`, `death`, `craft` (Special Item).
- JSON-Format = `discord/DiscordListener.java` + `DiscordBridge.java` ↔ `services/Minecraft.kt`.
  Beide Seiten gemeinsam ändern!
- `/dc` (Admin): Channel wählen, Nachrichtenarten an/aus, Vorschau, Webhook-URL (+ erneuern).
  Standard-Channel: `Channels.MINECRAFT` (vorerst #test). Einstellungen in `settings` (`minecraft.*`).
- Stand: Bot-Seite offline getestet, Plugin kompiliert, **im echten Spiel noch nicht getestet**.

## Mod (`mod/CrewStats/`)
- Among Us ist seit **2026.9.29** (Steam) **64-Bit** → BepInEx 6.0.0-be.735 muss die
  **IL2CPP-win-x64**-Variante sein (alte x86-Dateien in `Among Us\_backup_bepinex_x86`).
  Installiert in `D:\SteamLibrary\steamapps\common\Among Us`. Spielversion beim Bau: **2026.9.29**.
  Das Spiel startet nur einmal gleichzeitig (single-instance) → kein Mehrspieler-Test allein.
- Bauen: `dotnet build -c Release` (.NET 8 SDK) – kopiert die DLL nach `BepInEx/plugins`. Läuft
  das Spiel, ist die DLL gesperrt: dann umbenennen (`.old`) und die neue daneben legen (aktiv ab
  nächstem Start, `.old` löscht die Mod selbst). Nach Among-Us-Updates `AmongUs.GameLibs.Steam`
  in `CrewStats.csproj` anheben (Feed: nuget.bepinex.dev). Spiel-APIs vorher prüfen – Typen
  ändern sich (z.B. `PlayerId`-Struct statt byte, `TryGet*` mit `out`): kleines
  MetadataLoadContext-Tool gegen die GameLibs-DLLs bauen statt raten.
- Zeichnet nur als Host auf, ignoriert Freeplay/Hide & Seek. JSON-Format = `RoundRecord.cs`
  (camelCase) ↔ `services/ModRoundModel.kt`. Beide Seiten gemeinsam ändern!
- **Auto-Mute** (`AutoMute.cs`, Config `[AutoMute] Enabled`, Standard aus): meldet Phasenwechsel
  (tasks/meeting/end) als JSON im Nachrichtentext ↔ `services/AutoMute.kt`. Bot schaltet im Voice
  des Absenders (Friendcode-Verknüpfung), sonst `Channels.AMONG_US_VOICE`. Verhalten wie AutoMuteUs
  (`targetState`): Tasks = Lebende stumm+taub, Tote reden; Meeting = Lebende reden, Tote stumm;
  end = alle frei. Tote nur beim Phasenwechsel melden, damit Discord-Symbole keinen Kill verraten.
  Panel-Unmute, „Runde beenden“ und Auto-Unmute-Channel heben auch „taub“ auf. Läuft auch ohne
  Host-Rolle. Bot braucht „Mitglieder stummschalten“ + „Deafen Members“. Mute + Taub gehen in EINER
  Anfrage raus (`editMemberVoice`, bewusst ohne JDAs Cache-Abkürzung), verteilt auf die Worker-Bots.
- **Hausregel „Kein Skip bei Notfall“** (`NoSkipRule.cs`, Config `[Regeln] NoSkipOnEmergency`,
  Standard aus): nur als Host. `CastVote`-Prefix lehnt Skip-Stimmen in Meetings ohne Leiche ab und
  ruft `RpcClearVote` (Spieler kann neu wählen, klappt auch ohne Mod); Chat-Hinweis beim Meeting-Start.
  Nicht-Abstimmen bleibt möglich.
- **Lobby-Ankündigung** (`LobbyReporter.cs`, Config `[Lobby] Announce`, Standard an): nur als Host in
  Online-Lobbys; `{"crewstats":"lobby","state":"open|started|closed","code","map","players","max",
  "host","sender"}` (Spielerzahl höchstens alle 5 s) ↔ `services/Lobby.kt`. Kurze Webhook-Meldungen
  laufen über `LatestWebhookSender` (neueste gewinnt).
- **Rundeneinstellungen** (`SettingsReporter.cs`): beim Rundenstart alle Optionen generisch über
  `Int32/Float/BoolOptionNames` + Rollen-Anzahl/Chance als `crewstats-settings.json` ↔
  `services/GameSettings.kt`. Unbekannte Optionen/Rollen erscheinen roh unter „Weitere“.
  Influencer heißt im Spiel intern `SpiritGuide`.
- **ESC-Menü-Schalter** (`MenuToggles.cs`): „Auto-Mute“ und „Kein Skip bei Notfall“ als Klone eines
  Spiel-Schalters. **Bekanntes Problem:** die dritte Reihe überlappt im Spiel „Leave Game“. Zwei
  Versuche, das Raster zu verdichten, sind gescheitert (jede Schalter-Reihe ist im Spiel ein eigener
  Container; auch Weltkoordinaten-Ansatz klappte nicht) → zurückgenommen, Nutzer will es so lassen.
  Falls doch: Schalter in den Reiter „Graphics“ verschieben oder nur per Config.
- **Verteilung & Updates**: Repo `mod/` (nur Mod + Installer, kein Bot-Code, keine Webhook-URLs!).
  Aktuelles Release: **v1.2.0** (v1.1.0 hat ein kaputtes Auto-Update). `IncludeSourceRevision-
  InInformationalVersion=false` in der csproj nicht entfernen – sonst „1.2.0+commit“ und der Updater
  kann nicht vergleichen. `installer/CrewStats-Installer.bat` (Batch/PowerShell-Hybrid, **CRLF, ohne
  BOM** – `sed` entfernt CRLF, danach wiederherstellen) findet Among Us über Steam, installiert
  BepInEx x64 (SHA256 geprüft) + neueste `CrewStats.dll` aus GitHub-Releases, fragt optional die
  Webhook-URL; [2] deinstalliert. `Updater.cs` prüft beim Spielstart `releases/latest` und tauscht
  die eigene DLL. Repo-Name steht in `Updater.GitHubRepo` UND `$Repo` im Installer.
  Release: Version in `CrewStats.csproj` erhöhen, committen, dann `mod\release.ps1 "Notizen"`.
  Installer-Tests nur mit Fake-Ordner: `CREWSTATS_GAME_DIR`, `CREWSTATS_BEPINEX_ZIP`, `CREWSTATS_MOD_DLL`.
- Config: `BepInEx/config/de.amongusmanager.crewstats.cfg` (`WebhookUrl`, nur bei geschlossenem
  Spiel ändern – BepInEx überschreibt sie sonst). Log: `BepInEx/LogOutput.log`.
  Runden-Backups: `BepInEx/CrewStats/pending|sent` (nicht gesendete werden nachgeholt).
  Debug: `[Debug] RecordFreeplay = true` → Aufzeichnung + Auto-Mute auch in Freeplay.

## Stand / offen
- Im echten Spiel bestätigt: Mod lädt (x64), Auto-Update, Webhook-Upload, Phasenerkennung des
  Auto-Mute, Menü-Schalter funktionieren (überlappen aber, s.o.).
- **Noch nicht im echten Spiel getestet**: Rundenaufzeichnung mit echten Spielern, tatsächliches
  Muten/Taubschalten durch den Bot, „Kein Skip bei Notfall“, Lobby-Ankündigung, Highlights/Erfolge/
  Recap mit echten Daten.
- Kotlin-Bot: Logik/Embeds/Befehle/Import per Golden-Tests gegen den Python-Bot geprüft, JAR-Start
  (Fake-Token) getestet. **Noch nicht live auf Discord gelaufen.**
- **Offen beim Nutzer**: Pelican-Server auf einen Java-Egg (17+) umstellen lassen, Startbefehl
  `java -jar AmongUsManager.jar`, `crewbot-update.zip` (JAR) hochladen, `.env` (mit `DB_…`-Werten für
  die Panel-Datenbank) + `data/` behalten. Beim ersten Start übernimmt der Bot automatisch die JSON-Daten
  aus `data/` in die Datenbank. MySQL bisher nur gegen MariaDB 11.4 (lokal) getestet, nicht gegen den
  echten Panel-Server.

## Testen
`gradlew test` (JUnit 5, `src/test/kotlin`). Golden-Tests (`*GoldenTest.kt`) vergleichen mit
Referenzdaten des originalen Python-Bots (`src/test/resources/golden/golden.json` + `import/`),
erzeugt von `tools/golden/generate_golden.py` (braucht den Python-Bot aus Tag `python-final`):
Inhalte, Formatierer, Parser, alle Embeds, Slash-Command-Definitionen, 665 zufällige Operationen
(Runden, Wetten, Undo, Admin …) mit Spielerstand nach jedem Schritt, und der JSON-Import.
`ServiceBehaviorTest.kt`: Transaktions-Rollback, Undo, Wettsperre, Events, Datenbankdatei.
`MariaDbTests.kt`: Pipeline-, Import- und Verhaltens-Tests nochmal gegen einen echten MariaDB-Server
(mariaDB4j startet ihn automatisch, `TestMariaDb`; die ERROR-Zeile „mysqld … failed unexpectedly“ am
Ende kommt nur vom Beenden des Test-Servers) + Umzug SQLite → MySQL. `JarSmokeTest.kt` startet das
fertige JAR gegen MariaDB: `gradlew shadowJar test -DjarSmoke=true` (vor jedem Deploy sinnvoll).
Services ohne Discord testen: `TestServices` (In-Memory-Datenbank), `*WithoutRoles`-Funktionen.
Embed-Grenzen: JDA prüft lokal (Feld ≤ 1024 UTF-16-Zeichen, Embed ≤ 6000) und wirft sonst.
