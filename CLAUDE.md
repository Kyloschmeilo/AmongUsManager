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
  - GitHub-Release: `.github/workflows/release.yml` (Actions → „Release“ → „Run workflow“ mit Version, oder
    Tag `vX.Y.Z` pushen) baut das JAR und hängt es ans Release. Notizen in `release-notes/vX.Y.Z.md`.
    Aus der Cloud-Sitzung lassen sich keine Tags pushen → dort per workflow_dispatch starten.
    Version auch in `build.gradle.kts` anheben, `content/Changelog.kt` neu schreiben (`/changelog`).
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
  Mod- und Auto-Join-Webhook lagen bis 03.11.2026 in `OLD_LOG` (1462517523210834003); der Bot verschiebt sie beim
  Start nach `LOG` (URLs bleiben gleich, `mod.channel_id` wird mitgezogen), Webhooks in anderen Channels bleiben.
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
  (Lobby-Ankündigung + Wettsperre, `current()` für Auto-Join), `autoJoin` (Webhook-id für `/autojoin`), `roleWishes` (`RoleWishes.kt`: Rollenwünsche, Admin-Zuweisungen,
  Protokoll + Verschlüsselung mit der Mod), `welcome` (Willkommens-/Abschiedsnachricht). Mehrteilige Änderungen laufen in einer DB-Transaktion; Discord-
  Aufrufe (Level-Rollen) erst danach.
- `content/` – Texte & Daten: `AmongUs.kt` (Rollen für `/rolle` inkl. Influencer, Maps), `SpecialRoles.kt`
  (92 Rollen + Modifikatoren der Mod The Other Roles (GMIA) für `/special-rolle`, fünf Menüs wegen Discords 25er-Grenze),
  `GameSettingsContent.kt` (deutsche Namen/Kategorien der Lobby-Optionen), `Achievements.kt`
  (Erfolge – ids nie umbenennen, sind gespeichert), `Minecraft.kt`, `Changelog.kt` (Inhalt von `/changelog`), `Tutorials.kt` (Inhalt von `/tutorial`).
- `ui/` – `Embeds.kt` (`Palette`, `Tone`, `notify()`, `progressBar`, `fitLines`, `safeBuild`),
  `Text.kt` (`escapeMarkdown`/`urlQuote` wie discord.py/Python), `InsightsUi.kt`, `ProfileUi.kt`,
  `LobbyUi.kt`, `MinecraftUi.kt`, `views/` (Buttons/Menüs). Menüs mit Timeout erben von
  `SessionView` (custom_id `cv:<id>:<name>`, Besitzer-Prüfung, Timeout); dauerhafte Buttons alter
  Nachrichten haben feste custom_ids (siehe unten).
- `modules/` – Slash-Commands nach Bereich (früher Cogs), Name + Emoji erscheinen automatisch in
  `/hilfe` (Reihenfolge = Liste in `CrewBot.modules`). `/emergency` wurde bewusst entfernt.
- `web/` – Stats-Website (eigenes JAR, gehört nicht zum Bot), siehe Abschnitt „Stats-Website“.

## Wichtige Entscheidungen
- **Die Mod muss fair bleiben**: keine versteckten Cheats/Vorteile (z.B. heimlich Impostor
  bestimmen wurde abgelehnt). Die Mod schreibt **keine Nachrichten ins Spiel** (Nutzer: „nerven nur“,
  seit 1.3.2). Hausregeln nur als Option. Rollenwünsche/Admin-Zuweisungen hat der Nutzer ausdrücklich
  gewollt – offen für alle per Slash-Command (eine Ankündigung im Chat gab es bis 1.3.1, auf Wunsch entfernt).
  Seit 1.3.2 würfelt die Mod als Host **selbst**, wer welche Rolle bekommt (`[Rollen] SelbstWuerfeln`, Standard
  an) – Nutzer meinte, nach Wünschen kämen immer dieselben als Crewmate (Daten zeigten Zufall; das Spiel speichert
  keine Rollen-Historie). Muss gleichverteilt bleiben (`RandomNumberGenerator`, Host ohne Vorteil).
- **Rollenwünsche** (`/rollenwunsch` für alle, `/rolle-zuweisen` für Admins, beide im Mod-Modul,
  `modules/RoleWishCommands.kt`): Wunsch kostet `ROLE_WISH_COST` (20) XP sofort (Level sinkt nicht),
  höchstens `ROLE_WISHES_PER_EVENING` (2) offene+erfüllte pro Spieleabend (bis 6 Uhr morgens Berlin,
  `GAME_NIGHT_END_HOUR`); Ändern kostenlos, Zurückziehen erstattet. Gilt für die nächste Runde des
  Spielers (braucht `/verknuepfen`). Mehr Wünsche als Plätze → Fehler beim Wünschen; Grundlage sind die
  zuletzt gemeldeten Lobby-Einstellungen (`gameSettings.latest`): Spezialrolle höchstens Anzahl (nur mit
  Chance > 0), Impostor-Team höchstens `numImpostors`; unbekannte Einstellungen → nur Crewmate/Impostor
  (1). „Crewmate“/„Impostor“ = irgendeine Rolle des Teams; Geister-Rollen gehen nicht. Admin-Zuweisungen
  kostenlos, haben Vorrang, ersetzen (und erstatten) den Wunsch des Spielers; Admins sehen bei vollen
  Rollen, wer sie hat. XP zurück (+ DM), wenn die Mod den Wunsch nicht erfüllen kann; offene Wünsche
  verfallen um 6 Uhr mit Erstattung (Schleife jede Minute, ohne DM). Tabelle `role_wishes` (Schema v2).
- **Special-Rollen-Wünsche** (02.11.2026, Kilian: „adde bei rollen wunsch und zuweisung die Special rollen als extra Command“):
  `/special-rollenwunsch` (alle) und `/special-rolle-zuweisen` (Admin) für Rollen von The Other Roles, Auswahl per
  Autocomplete (`SlashCommand.autocomplete`, >25 Rollen). Gespeichert als `role_name = "tor:<id>"` (`TOR_WISH_ROLES` in
  `content/SpecialRoles.kt`, ohne Modifikatoren/Madmate/Sidekick/Pursuer/Immoralist), gleiche XP-/Abend-Regeln, Anzeigen und
  Zurückziehen weiter über `/rollenwunsch`/`/rolle-zuweisen`. Je TOR-Rolle höchstens ein offener Wunsch (Anzahl kennt der Bot
  nicht), Crew/Neutral zählen als Crew-, Impostor-Rollen als Impostor-Platz, Nice/Evil-Rollen für kein Team. Protokoll: Antwort
  enthält zusätzlich `tor` (RoleId-Namen) + `team` (CREW|IMPOSTOR|ANY). CrewStats (`TorRoleWishes.cs`, `TorWishPicker.cs`, auf
  main, noch kein Release) setzt erst das Team und lässt dann TORs `setRoleToRandomPlayer` den Wünschenden ziehen; kommt die
  Rolle nicht vor → noSlot (XP zurück). Im echten Spiel ungetestet.
- **Rollenwunsch-Protokoll** (kein offener Port): Host drückt Start → Mod schickt
  `{"crewstats":"wishes","v":1,"data":…}` (Friendcodes der Lobby) mit `?wait=true`; der Bot bearbeitet
  **genau diese Webhook-Nachricht** (Webhook-Token per `retrieveWebhookById`, nur im Speicher) zu
  `wishes-reply` mit den offenen Wünschen dieser Spieler; die Mod liest sie (`GET …/messages/<id>`) und
  löscht sie. Nach der Rollenvergabe kommt `wishes-result` (applied / failed+reason / absent). `data` ist
  AES-256-GCM (Schlüssel SHA-256 von `"crewstats-wishes:" + Webhook-Token`, Art als AAD), damit im Channel
  niemand die Wünsche sieht. Abgeholte Wünsche sind bis zur Rückmeldung (max. 3 min) gesperrt.
  Im Log-Channel stehen nur Anzahlen, keine Wünsche einzelner Spieler.
- **`/changelog`** (Admin, Modul Admin): postet `content/Changelog.kt` als Embed in den Channel, in dem er
  ausgeführt wird (Kilian macht das einmal pro Update). **Bei jedem Update den Inhalt ersetzen** (nicht anhängen):
  nur Neues seit dem letzten Changelog, kurz, für Spieler; `VERSION` = `version` in `build.gradle.kts`
  (prüft `ChangelogTest`, ebenso dass genannte `/befehle` existieren – sie werden automatisch anklickbar).
- **`/tutorial`** (Admin, Modul Admin, Auswahl `thema`): postet ein Spieler-Tutorial als Embed in den aktuellen
  Channel/Thread – wichtigste Befehle, Account verknüpfen, Special-Rollen (Installation/Bedienung) und alle Special-Rollen
  erklärt (aus `SPECIAL_ROLES` erzeugt, lange Abschnitte per `addChunked` auf mehrere Felder; passt es nicht in 6000 Zeichen,
  verteilt `buildTutorialEmbeds` es auf mehrere Nachrichten) (`content/Tutorials.kt`, `ui/TutorialUi.kt`).
  Im Standard-Design (Akzent `Palette.ACCENT` #1FBF7A, Abschnitte in Großbuchstaben, keine Emoji-Aufzählungen).
  `/befehle` im Text werden anklickbar (`linkCommands` in `ui/Text.kt`), `TutorialTest` prüft, dass es sie gibt.
  Special-Rollen-Text bei Änderungen am TheOtherRoles-Installer mitziehen (auch die Download-Karte in `web/Downloads.kt`).
- **The Other Roles** (seit 23.10.2026, Kilian: „ganz anderer Ansatz“ – ersetzt die eigene Rollen-Mod SpecialRoles):
  Das Original (TheOtherRolesAU, letzte Version 4.8.0 für Among Us 2024.11.26, 32-Bit) läuft nicht mit 2026.9.29 →
  Kilian hat **TheOtherRoles GMIA** gewählt (github.com/GMIA-Nexus/TheOtherRolesGMIA, 1.4.1 vom 11.10.2026, GameLibs
  2026.9.29, BepInEx be.735 x64, Reactor; spielt seit 30.10.2026 auf **„Modded NA“** – auf Modded EU flogen alle beim Beitreten/Rundenstart raus („Timeout while waiting for other player data“, im Spiel getestet: NA geht); der Installer schreibt `BepInEx/config/at.duikbo.regioninstall.cfg` (nur NA + Asia, Modded EU in `RemoveRegions`), Mini.RegionInstall entfernt die normalen Server; Mod-Texte
  nur Englisch/Japanisch/Chinesisch). `content/SpecialRoles.kt` = deren Rollen (Texte aus `stringData.json` in der DLL),
  `/special-rolle` (Modul Wissen) + Tutorials beschreiben sie. **Installer** `TheOtherRoles-Installer.bat` liegt **nicht
  auf GitHub** (Kilian: „gib mir nur die Datei“), sondern in `/mnt/project-files/theotherroles/`; Kilian postet ihn im
  Discord, und die Stats-Website bietet ihn aus ihrem `downloads/`-Ordner an (siehe „Hilfe & Mods“). Er kopiert das Steam-Among-Us nach `Among Us - The Other Roles` (ohne BepInEx/Mods, Marker
  `.theotherroles-installer`), entpackt dort das neueste GMIA-Release-Zip (Fallback 1.4.1), übernimmt auf Wunsch CrewStats
  (+ Config) und legt eine Desktop-Verknüpfung an; Start der Kopie direkt per exe (Steam muss laufen). Testen mit
  `TOR_GAME_DIR`, `TOR_COPY_DIR`, `TOR_ZIP` (portables pwsh, `Read-Host` per Funktion überschreiben).
  **Epic Games** (03.11.2026, Kilian: „bei den Installern Support für EpicGames einbauen“, auch im CrewStats-Installer):
  Suche über die Manifeste `%ProgramData%\Epic\EpicGamesLauncher\Data\Manifests\*.item` (Steam + Epic → Auswahl, sonst
  Ordner einfügen/Auswahlfenster; `.egstore` = Epic), Start per `com.epicgames.launcher://apps/<ns>%3A<item>%3A<app>?action=launch`.
  Epic startet nur den eigenen Ordner → TOR kommt **direkt ins Epic-Among-Us** (keine Kopie), eigene Plugin-Dateien in
  `BepInEx\theotherroles\dateien.txt`, Verknüpfungen „Among Us - The Other Roles“/„… - ohne The Other Roles“ rufen
  `Umschalten.ps1 an|aus` (verschiebt die Plugins nach `BepInEx\theotherroles\aus`) und starten über Epic. Ohne
  Schreibrechte (Epic unter „Programme“) startet sich der Installer als Admin neu (`INSTALLER_BAT`) und gibt Benutzern
  Schreibrechte auf `BepInEx` (icacls). Test-Variablen zusätzlich `TOR_STEAM_DIR`/`TOR_EPIC_MANIFESTS` (bzw. `CREWSTATS_*`).
  GMIA 1.4.1 hat nur ein Windows-Zip (gilt für Steam und Epic). **Mit echtem Epic-Among-Us ungetestet.** CRLF, ohne BOM;
  kein „ “/„ in doppelt quotierten PowerShell-Strings (zählen dort als Anführungszeichen).
  CrewStats mit TOR (Rollenwünsche/Selbst-Würfeln) ist ungetestet.
- **The Other Roles in XP & Auto-Mute** (24.10.2026, Kilian: „Integriere die other Roles in die XP-Vergabe und füge
  Mute-Features von z.B. Blackmailer auch auf Discord“; Bot 2.5.0 + CrewStats 1.4.0): CrewStats liest TOR per Reflection
  (`TorBridge.cs`, Namen gegen die echte GMIA-1.4.1-DLL geprüft – bei einer neuen TOR-Version mit dem
  MetadataLoadContext-Dump gegenprüfen; jeder Lesefehler wird einmal geloggt, dann läuft die Mod ohne diese Info).
  Rundendaten: `team` CREW|IMPOSTOR|**NEUTRAL** (Madmate = IMPOSTOR), `modRole`/`modStartRole` (TOR-nameKey), `modifiers`,
  `mod`, `modDeaths` (TOR `GameHistory`: Kill/Exile/Guess/Jailed/Suicide …), `won` = TORs `EndGameResult.CachedWinners`
  (Postfix nach TOR), `winningTeam` NEUTRAL = Sondersieg (TOR-Gründe ≥ 10, Name aus `CustomGameOverReason`, z.B.
  JesterWin). XP: Sieg nach eigenem Team (Crew 10, Impostor 25, Neutral `NEUTRAL_WIN_XP` 30); Sondersieg → alle Wetten
  verloren; `ROLE_ACTION_XP` (5) je Impostor/Neutralem, den ein Crew-Spieler ausschaltet (nicht Rauswählen/Selbst-Kill),
  auch ohne Sieg. Neutrale zählen in `/stats` nur als Spiel, Stimmen gegen Neutrale weder richtig noch falsch.
  Bot-Labels der nameKeys: `torRoleLabel`/`TOR_ROLE_NAMES` in `content/SpecialRoles.kt` (Godfather, Nice/Evil-Varianten …).
  Auto-Mute: `silenced` (Blackmailer-Opfer, Jailor-Gefangene, nur Lebende) im Meeting stumm, nicht taub; TOR zeigt beides
  im Meeting allen an, die Mod meldet neu, wenn es sich im Meeting ändert. TOR-Hide-&-Seek/Freeplay zählen nicht.
  CrewStats-`SelectRoles`-Postfix läuft vor TOR (`Priority.First`), damit Rollenwünsche verteilt sind. **Alles im echten
  Spiel noch ungetestet** (Mehrspieler nötig).
- **Rollen-Mod SpecialRoles** (nicht mehr im Bot, seit 23.10.2026 durch The Other Roles ersetzt; Repo Kyloschmeilo/SpecialRoles, alle 30 Rollen, seit 23.10.2026 als **1.0.0** veröffentlicht, ab **1.0.1** mit Auto-Update): eigene
  Among-Us-Mod, **unabhängig von CrewStats** (keine Rollenwünsche dafür). Rollenliste: `content/SpecialRoles.kt`
  (17 Crew, 11 Impostor, 2 Neutral + Modifikator Flash; Erpresser höchstens 2× pro Spiel). Die alten Bot-Texte dazu
  stehen in der Git-Historie (bis v2.3.0). Planung: `/mnt/project-files/rollen-mod/rollen-vorschlaege.md` (Projektordner).
- custom_ids bestehender Buttons (`persistent_control_panel:*`, `bt_move`, `evt_*_p`,
  `persistent_role_dropdown`, `crewstats:undo:<gameId>`, `selfrole:<roleId>`) nicht ändern – sonst gehen alte Nachrichten kaputt.
- Rollen-Panel (`/rollen-panel`, Admin, postet immer in `Channels.ROLES`): Gruppen in `SELF_ROLE_GROUPS` (`Config.kt`, eine Nachricht je Gruppe,
  `exclusive` = nur eine Rolle der Gruppe). Buttons zeigen die Mitgliederzahl; gepostete Nachrichten stehen in
  `settings` (`rolepanel.messages`) und werden bei Rollenänderungen gebündelt (2 s) aktualisiert.
  Gruppen: Clans (exklusiv), Spiele, Minecraft-Events. „Nicht mehr pingen“ gibt es bewusst nicht mehr.
- Auto-Rollen (`AUTO_ROLES`, `services/AutoRoles.kt`): jedes Mitglied (keine Bots) beim Beitreten bzw. nach
  der Mitgliederprüfung, beim Bot-Start werden fehlende an alle nachvergeben.
- Willkommen/Abschied (`services/Welcome.kt`, `ui/WelcomeUi.kt`): kurzes Embed beim Beitreten (pingt nur das
  neue Mitglied) und beim Verlassen, keine Bots. Channel `Channels.WELCOME`, 0 = Systemkanal des Servers.
- Event-XP gibt es erst nach dem Event und nur bei ≥ `EVENT_MIN_ATTENDANCE_MINUTES` im Voice.
  Events werden nach Start + `EVENT_DURATION_HOURS` automatisch gesperrt (Anwesenheits-XP).
  Der **Spieleabend-Recap** (Mod-Runden seit Event-Start, als Antwort auf die Event-Nachricht) kommt
  NICHT mehr beim Sperren, sondern erst per `/spieleabend-recap` (Admin) oder automatisch
  `RECAP_AFTER_HOURS` (5) nach Event-Start – Wunsch des Nutzers. Offene Recaps stehen in `settings`
  (`events.pending_recaps`, angelegt bei `/create-event`); ohne Runden wird nichts gepostet.
- Level sinkt nie durch Wetteinsätze (nur Admin-Panel/Undo setzen es zurück).
- Admin-Panel-Änderungen werden gebündelt (10 s) in den Log-Channel geschrieben.
- Leaderboard: Medaillen (🥇🥈🥉) im Nickname; beim Sync werden alle Server-Mitglieder mit Medaille
  geprüft (nicht nur das Ranking), Lock gegen parallele Aufrufe. Owner/höhere Rollen nicht änderbar.
- Mod → Bot läuft über einen **Discord-Webhook** (kein offener Port auf dem Panel nötig).
  Der Bot akzeptiert nur Nachrichten seines eigenen Webhooks (`settings`: `mod.webhook_id`, via
  `/mod-setup` – erneuert die URL, die alte wird ungültig → URL in der Mod-Config neu eintragen).
  Webhook-URLs baut der Bot selbst als `https://discord.com/api/webhooks/<id>/<token>` (JDA liefert
  `/api/v10/…`, die Mod akzeptiert nur `/api/webhooks/`!).
  Unterscheidung im Modul: Auto-Join-Webhook (eigene id) zuerst, dann Auto-Mute-JSON / Lobby-JSON im Text, `crewstats-settings.json` bzw.
  `crewstats-round.json` als Anhang. Meldungen > 60 s alt werden ignoriert, alles wird gelöscht.
  Runden werden nacheinander ausgewertet (Mutex) – doppelt gesendete zählen nur einmal.
- **Auto-Join** (02.11.2026, Kilian: „Auto-join funktion die dem neuesten code vom Bot joint“; Bot `/autojoin` in
  `modules/AutoJoinCommands.kt` + `services/AutoJoin.kt`, CrewStats 1.6.0 `AutoJoin.cs`/`AutoJoinRules.cs`): **eigener
  Webhook** „CrewStats Auto-Join“ (`settings` `autojoin.webhook_id`, im Mod-Channel, angelegt beim ersten `/autojoin`),
  dessen URL jeder Spieler per `/autojoin` (für alle, ephemeral) bekommt – die Mod-Webhook-URL bleibt geheim (Runden!).
  Über ihn beantwortet der Bot nur `{"crewstats":"join"}` → bearbeitet genau diese Nachricht zu `join-reply`
  (state open|started|none, code, map, players, max, host, age; kein Friendcode), unverschlüsselt (Codes stehen eh im
  Codes-Channel); alles andere wird gelöscht. Quelle = `LobbyAnnouncer.current()` (letzte Mod-Meldung, nur im Speicher,
  verfällt nach 1 h ohne Meldung) – manuell gepostete Codes zählen nicht. `/autojoin neu:True` (Admin) erneuert die URL.
  Mod: im Hauptmenü (`MainMenuManager.LateUpdate`, nur `GameStates.NotJoined`) alle ~15 s fragen, automatisch jede Lobby
  nur einmal, F5 = sofort (auch erneut), F6 = an/aus (`[AutoJoin] Url/Automatisch/TasteJetzt/TasteAnAus`), Status unter der
  Versionsnummer; Beitreten per `AmongUsClient.CoJoinOnlineGameFromCode(GameCode.GameNameToInt(code))` (aktuelle Region,
  muss Modded NA sein). Installer fragt die Auto-Join-URL optional ab. **Im echten Spiel noch ungetestet.**
  **Fix 1.7.1** (02.11.2026, Kilian: „er sagt immer das er das spiel nicht finden konnte“): Codes gelten nur auf dem Server
  der Lobby, die Mod trat in der eigenen Region bei (Kilian hostete im normalen Among Us auf „Europe“). Jetzt meldet der Host
  `region` + `server` (Adresse) in der Lobby-JSON, der Bot reicht sie in `join-reply` weiter (nur wenn gemeldet), die Mod
  sucht die Region (`AutoJoinRules.FindRegion`: Adresse, dann Name) und wechselt per `ServerManager.SetRegion`; fehlt sie
  (z.B. TOR-Kopie ohne offizielle Server), steht das im Status. Braucht Host-Mod 1.7.1 + neuen Bot.
  Bot-JAR (mit Spezial-Rollenwunsch-Befehlen) in `/mnt/project-files/bot-autojoin/crewbot-update.zip`, Deploy macht Kilian.
- **Proximity-Voice im Live-Tab** (11.11.2026, Kilian: „Voice-Chat unter den Live Tab … eine Art proximity voice chat“;
  CrewStats 1.10.0): Host-Mod (`VoiceReporter.cs`/`VoiceRules.cs`, `[Voice] Enabled`, Standard an) schickt `{"crewstats":"voice",
  fc, code, phase lobby|tasks|meeting, players:[{fc,name,color,x,y,dead,vent,mute}]}` an die Website-URL aus `[AutoJoin] Url`
  (alle 0,2 s mit Zuhörern, sonst 2 s; Antwort `voice-reply` mit `listeners`). `web/VoiceChat.kt`: Räume je Host-Friendcode
  (nur im Speicher), Regeln `VoiceRules` (Lobby/Ende alle; Runde nach Entfernung 1,2–5,3 Einheiten, Vent unhörbar, Lebende
  hören keine Geister, Geister hören Geister überall; Meeting alle Lebenden außer TOR-Stummen). Browser: `/api/voice` (Status),
  `join` (angemeldet + verknüpft + Friendcode in der Meldung), `stream` (SSE: hello/peers/mix/signal/bye, JDK-HttpServer kann kein
  WebSocket → Thread-Pool 16 + `MAX_MEMBERS` 60), `signal`, `leave`. Sprache per WebRTC-Mesh direkt zwischen den Browsern
  (`web/src/lib/voice.tsx`, Provider in `App.tsx`, bleibt beim Seitenwechsel; `components/Voice.tsx`, `voice.css`). Positionen
  gehen nie an den Browser, nur Lautstärken (`hear`) und an wen man sendet (`send` – wer einen nicht hören darf, bekommt keinen
  Ton). Host ohne Meldung > 6 s → „free“ (alle hören alle), > 5 min → Raum zu. **Mikrofon nur über https** (http://IP:Port geht
  nicht). ICE: `VOICE_ICE_SERVERS` (JSON wie `iceServers`, Standard STUN `stun.cloudflare.com`), ohne TURN klappt nicht jede
  Verbindung. Discord-Voice/Auto-Mute unverändert. Tests: `VoiceChatTest`; zwei echte Chromium-Browser gegeneinander geprüft
  (Verbinden, Nähe, Meeting, Seitenwechsel). Im echten Spiel ungetestet.
- **Spieler-Anfragen über die Website** (03.11.2026, Kilian: „mehr die Website nutzen als Webhooks, außer wenn es per
  Webhook schneller ist“; CrewStats 1.8.0): Auto-Join, Stats und Verknüpfungs-Code (Frage/Antwort) gehen an
  `POST <WEB_PUBLIC_URL>/api/mod/<Schlüssel>` der Stats-Website (`web/ModApi.kt`, gemeinsam `services/ModApi.kt`) – eine
  HTTP-Anfrage statt Webhook-Nachricht + Warten + Lesen + Löschen, ohne Discord-Rate-Limits. Website beantwortet join aus
  `settings` `lobby.current` (`LobbyStore`, schreibt der Bot bei jeder Lobby-Meldung, ohne Friendcode) und stats selbst
  (`PlayerInfoService` auf der beschreibbaren Website-Verbindung); den Link-Code erzeugt der Bot über `web_requests`
  (Aktion `mod.link`, nur mit ID 0, nicht über `/api/bot/`). Schlüssel `modapi.key` (`/autojoin neu:True` erneuert ihn,
  Website liest ihn alle 30 s), 40 Anfragen/min je IP. Website meldet `web.public_url` + `web.heartbeat` (jede Minute);
  `/autojoin` zeigt die Website-URL nur, wenn das Lebenszeichen < 10 min alt ist, sonst wie bisher den Webhook. Alte
  Webhook-URLs gehen weiter. **Bewusst Webhook bleiben**: Runden, Auto-Mute, Lobby, Rollenwünsche, Einstellungen (Host,
  Discord stellt sie dem Bot sofort zu – über die Website käme alles erst per Datenbank-Polling 0,25–1 s an, und bei
  Ausfall des Website-Servers gingen Runden verloren) und das Minecraft-Plugin. Reihenfolge beim Deploy: erst CrewStats
  1.8.0 veröffentlichen, dann Bot + Website (sonst zeigt /autojoin URLs, die alte Mods ablehnen). Tests: `ModApiTest` (+ MariaDB).
- **Live-Lobbys auf der Website** (03.11.2026, Kilian: wer gerade in welcher Lobby ist + Beitreten-Button; CrewStats 1.9.0):
  Host-Mod schickt in der Lobby-JSON `list` ([{name, fc, color}], ≤ 20). Bot speichert neben `lobby.current` die Liste
  `lobby.live` (eine Lobby je Host, neueste zuerst, ≤ 10, verfällt wie `LOBBY_STALE_AFTER`); `current()` nimmt dann die neueste
  der Live-Liste. Website `web/LiveLobbies.kt`: `GET /api/lobbies` (Spieler mit Profil-Link/Level, nie Friendcodes; Code nur für
  angemeldete + verknüpfte Spieler), `POST /api/lobbies/join` → `JoinPicks` (nur im Speicher, 3 min). Die Mod schickt mit der
  Website-URL ihren Friendcode in `{"crewstats":"join","fc":…}` und fragt im Hauptmenü alle ~6 s (auch mit Auto-Join aus);
  Antwort mit `pick` = auf der Website gewählt → beitreten, jede pick-id einmal. Seite `/lobbys` („Live“ in der Navigation,
  `pages/Lobbies.tsx`, `lobbies.css`), Hinweis-Streifen auf der Startseite. Tests: `LiveLobbiesTest` (+ MariaDB). Im echten Spiel ungetestet.
- **CrewStats-Komfort** (02.11.2026, Kilian: „pack die alle mal rein“ – Auto-Update-Hinweis, Lobby-Overlay mit eigenen
  Stats, Rundenzusammenfassung, Verknüpfen per Code, Lobby-Info, Einstellungsmenü, Verbindungsstatus, Offline-Puffer;
  CrewStats 1.7.0, alles einzeln abschaltbar unter `[Komfort]` bzw. im Spiel per **F7**). Bot: `services/ModPlayerInfo.kt`
  (`PlayerInfoProtocol`, `PlayerInfoService`, `LinkCodes`), beantwortet in `AutoJoinCommands.answerPlayerInfo` über den
  Auto-Join- **oder** Mod-Webhook (gleicher ?wait=true-Ablauf wie Auto-Join): `stats` (me + ≤ 15 Lobby-Friendcodes →
  linked/level/games/wins, für „me“ zusätzlich xp/rank/kills + letzte Runde mit Rolle/Team/Sieg/XP/„ago“) und `link`
  (6-stelliger Code, 10 min, nur im Speicher) → `/verknuepfen-code` (gleiche Besitz-Regel wie `/verknuepfen`).
  **Keine Discord-Namen/-IDs in den Antworten** (sonst könnte jeder mit der Auto-Join-URL Friendcode → Discord auflösen).
  Mod-Seite: `PlayerInfoRules.cs` (getestet), `BotClient.cs` (+ `ConnectionStatus`), `Comfort.cs`, `ComfortUi.cs`
  (Unity-IMGUI per `AddComponent`, nur `GUI.*` mit festen Rects, kein GUILayout), `RoundSummary.cs`. Im echten Spiel ungetestet.
  Am 02.11.2026 als CrewStats **v1.7.0** veröffentlicht (Kilian: „realese“), beide PRs auf main. Bot-JAR dazu:
  `/mnt/project-files/bot-crewstats-komfort/crewbot-update.zip`, Deploy macht Kilian.
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

## Stats-Website (01.11.2026, Kilian: „Mach jetzt die website“, mit GSAP, Lenis, React Bits)
- **Eigenes JAR** `StatsWebsite.jar` (`gradlew statsWebsiteJar`, Main `web/StatsWebsite.kt`), läuft als **eigener
  Pelican-Server** (Java-Egg, JARFILE `StatsWebsite.jar`, eigener Port, 256–512 MB RAM). Liest die Bot-Datenbank
  **nur** (`Database.openReadOnly` bzw. `mysql(…, readOnly = true)`: kein `Schema.migrate`, nichts wird geschrieben).
  `.env` wie beim Bot: `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` (gleiche Panel-Datenbank); ohne → SQLite
  `data/crewbot.db` (nur sinnvoll im selben Ordner wie der Bot). Port: `WEB_PORT` → `SERVER_PORT` (Panel) → 8080,
  `WEB_HOST` (0.0.0.0), `WEB_REFRESH_SECONDS` (60). `WEB_ROUNDS_DIR` = Ordner mit Runden-JSONs statt Datenbank (Tests/Vorschau).
- Datenquellen: `round_raw` (nicht `undone`, Rundenformat 2 mit Laufwegen) + `round_history` (ältere Runden ohne
  Rohdaten, Ende = Empfangszeit), `friend_links`, `players` (Level). Freeplay zählt nicht. Spieler-Schlüssel = erste 12
  Hex von SHA-256(`"crewstats-web:" + d:<Discord-ID> | f:<friendcode> | n:<name>`) – **Friendcodes, PUIDs und
  Discord-IDs gehen nie an den Browser** (`RoundDetail` entfernt sie, `StatsWebsiteTest` prüft das).
- Kotlin (`web/`): `WebModel.kt` (Runde → `WebRound`, tolerant für v1/v2), `StatsSnapshot.kt` (alle Auswertungen,
  unveränderlich, neu bei Datenänderung), `StatsStore.kt` (lädt nur neue Runden nach, Detail-Cache), `StatsSource.kt`
  (`DatabaseSource`, `DirectorySource`), `RoundDetail.kt`, `StatsServer.kt` (JDK-`HttpServer`, nur GET/HEAD, gzip,
  ETag, CSP; `/api/status|overview|players[/key]|rounds[/id]|maps[/id]|roles`, sonst Dateien aus `web/` im Klassenpfad,
  App-Adressen ohne Endung → `index.html`). Grundrisse der Maps entstehen aus den Laufwegen (Raster 0,5).
- Frontend: Quelltext in `web/` im Repo-Wurzelordner (React 19 + Vite + TypeScript, React Router, GSAP + ScrollTrigger,
  Lenis, React Bits `SplitText`/`CountUp`/`AnimatedContent` in `web/src/reactbits/` mit Lizenz, lucide-Icons,
  Schriften selbst gehostet über @fontsource – keine Google-Anfragen). `prefers-reduced-motion` wird beachtet.
  **Eigenes Design statt Standard-Design** (02.11.2026, Kilian: „ignoriere mal das Design system ein bisschen und mach, dass
  die Website nicht komplett vibe-coded aussieht“): Almanach/Anzeigetafel statt Dashboard – Barlow + Barlow Condensed,
  Hintergrund #0F1117, Text #EEEBE3, Farben nur mit Bedeutung (Visier-Blau #7CC6E4 = Crew/Links, Rot #F2584C = Impostor,
  Violett #C493EC = Neutral, Gold = Platz 1), Abschnitte mit Linien statt Karten (`.panel` = Linie oben, `.panel--box` für
  echte Kästen), Avatare als Crewmate-Umriss, Startseite mit Überschrift „CrewStats“ (03.11.2026, Kilian: statt „Wer war sus?“) und Spielstand Crew : Impostor. Keine Buchstaben-/Einblend-
  Animationen mehr (`Title`/`Reveal` in `motion.tsx` sind schlicht); CountUp, wachsende Balken, Lenis und Replay bleiben.
  Schlichte Überschriften statt Fragen/Floskeln. Bot-Embeds/Artifacts nutzen weiter das Standard-Design.
  Reaktive Animationen (02.11.2026, Kilian: „arbeite auch gerne ein paar reaktive animationen ein“, mit Verbotsliste: keine
  Lila-Verläufe/Glas/Blur/Emojis/Floskeln/gleiche Hover überall): Sichtfeld um den Zeiger über der Karte im Kopf (`Vision`
  in `Home.tsx`, wie Licht-Sabotage), Spielstand hebt beim Hover den Team-Anteil im Balken hervor, Nav-Strich rutscht zum
  aktiven Punkt, in der Rundenliste springen die Impostor hoch, Ranglisten-Crewmates hüpfen, Podest wackelt, Rekordwert
  rückt nach rechts, Band „N an Bord“ auf der Startseite mit allen Spielern als im All treibenden Crewmates (`CrewDrift.tsx`, Hover hält an + Name/Siege, Klick = Profil, Sterne folgen dem Zeiger), feiner Sternenhimmel hinter der Seite, Platz 1–3 mit Gold/Silber/Bronze-Streifen, Rundenzeilen mit Streifen in Siegerteam-Farbe (02.11.2026, Kilian: „etwas mehr leben“); moderner Schliff (03.11.2026, Kilian: „noch ein bisl moderner“): Kästen ringsum gerahmt mit 10px-Ecken, Rundenliste im Rahmen, HUD-Ecken um die Kopf-Karte, Scroll-Fortschritt unter der Kopfzeile (`ScrollProgress` in `App.tsx`), Seiten blenden beim Wechsel ein (`RouteFade`); breite Bildschirme (03.11.2026, Kilian: „am PC sieht die Seite links und rechts sehr leer aus“): Inhalt wächst ab 1760/2000/2400 px auf 1320/1440/1600 px, ab 1600 px feste Seitenleisten im Rand (`SideRails.tsx`, `rails.css`: links Inhaltsverzeichnis der Seite am HUD-Lineal, rechts „Zuletzt gespielt“ + treibender Crewmate aus der letzten Runde) – jede Stelle anders, alles unter `prefers-reduced-motion: no-preference` (Ende von `styles.css`). Seiten: Übersicht, Bestenliste, Spielerprofil, Runden, Runde mit
  **Replay** (GSAP-Zeitleiste über die Laufwege, Kills/Meetings/Sabotagen), Maps (Heatmaps: Laufwege, Kills, Meetings – Kill-/Meeting-Dichte per Gauß-Raster `Density` in `MapView.tsx`, Meetings am Ort des Auslösers `callerX/Y`, Filter Leiche/Notfall; Kill-/Meeting-Orte je Raum; 03.11.2026, Kilian: „wo gibt es die meisten Kills und Meetings“), Rollen.
  **Bauen:** `cd web && npm install && npm run build` → schreibt nach `src/main/resources/web/` (wird **mit committet**,
  damit Gradle/Windows ohne Node bauen kann), danach `gradlew statsWebsiteJar`. Entwickeln: Website-JAR auf 8080
  starten (`gradlew runWebsite` oder mit `WEB_ROUNDS_DIR`), dann `npm run dev` (Vite leitet `/api` und `/auth` an 8080 weiter).
  Der Bot-JAR enthält die Seite nicht (`exclude("web/**")`).
- **Discord-Login + eigenes Profil + Profilbilder** (01.11.2026, Kilian: „lass sie sich mit DC anmelden um dann ihr Profil
  etwas customizen“, „nehme die DC profilbilder“). Eigene Daten der Website in **`data/website.db`** (SQLite,
  `Database.openStandalone`, ohne Bot-Schema; auf dem Server nie löschen): `web_sessions` (nur SHA-256 des Cookies,
  30 Tage), `web_profiles` (JSON je Discord-ID), `web_discord_users` (Name, Spitzname, Profilbild als BLOB).
  `WebAccounts.kt` (Tabellen), `Discord.kt` (`DiscordApi`/`HttpDiscordApi`: nur REST mit dem Bot-Token – **kein**
  Gateway-Login, also keine Doppel-Verarbeitung; Server-Mitglied (Spitzname, Server-Profilbild) vor `/users/<id>`;
  Server über `WEB_GUILD_ID` oder `GET /channels/<Channels.CODES>`; `DiscordSync` holt je Durchlauf höchstens 10 fällige
  Spieler, jeder alle 12 h, altes Bild bleibt bei Fehlern), `WebAuth.kt` (OAuth2-Code-Flow, Scope nur `identify`,
  `/auth/login?back=` (nur eigene Pfade), `/auth/callback` (state im Speicher + Cookie `cs_state`), `POST /auth/logout`,
  `GET /api/me`, `POST /api/me/profile` (nur JSON, gleiche Origin, ≤ 8 KB); Cookie `cs_session` HttpOnly, SameSite=Lax,
  Secure nur bei https), `Profiles.kt` (`ProfileRules`: Anzeigename **nur einer der eigenen Ingame-/Discord-Namen**
  (keine Fremd-Namen), Über mich ≤ 160 Zeichen/3 Zeilen ohne Steuerzeichen, Farbe 0–17, Lieblingsrolle aus gespielten
  Rollen, Lieblingsmap, ≤ 3 Kennzahlen (`METRICS` = ids in `web/src/lib/metrics.ts`, Test prüft), Profilbild an/aus).
  Profil bearbeiten nur, wer verknüpft ist und Runden hat (Schlüssel `d:<Discord-ID>`). Profilbilder liefert die Website
  selbst (`/api/avatar/<key>?v=<Version>`), der Browser fragt nie bei Discord an; Anzeigename gilt in Listen/Profil,
  in einzelnen Runden bleibt der Ingame-Name. `.env`: `DISCORD_TOKEN` (Profilbilder), `DISCORD_CLIENT_SECRET`
  (Login an), `WEB_PUBLIC_URL` (Redirect `<url>/auth/callback`, muss im Developer-Portal unter OAuth2 → Redirects
  stehen), optional `DISCORD_CLIENT_ID` (sonst aus dem Token), `WEB_GUILD_ID`. Discord nimmt `http://IP:Port` als Redirect
  an (am 02.11.2026 von Kilian bestätigt). `invalid_client` beim Code-Tausch = falsches Client Secret (nicht Public Key).
- **Bot steuern über die Website** (02.11.2026, Kilian: „Event Tab … anmelden wenn dc connected, Event Maker kann Events
  machen, Admin Panel für alle mit Admin-Rolle mit allen Panel-Commands, Rollen-Zuweisung usw.“): Website hat keinen
  Gateway-Login → **Auftrags-Tabelle `web_requests`** in der Bot-Datenbank (Schema v4, `services/WebQueue.kt`). Website
  (`web/ControlApi.kt`, `POST /api/bot/<aktion>`, nur JSON + gleiche Origin + Sitzung, 60/min) schreibt Auftrag mit der
  Discord-ID aus der Sitzung (zweite, beschreibbare Verbindung ohne Migration: `Database.mysql(…, migrate = false)` bzw.
  `openShared`), wartet ≤ 30 s und bricht sonst ab (new → cancelled). Bot (`modules/WebControlModule.kt`, nicht in
  `CrewBot.modules`, damit /hilfe + Golden-Test gleich bleiben; `bot.webControl`) fragt alle 250 ms (nach Aktivität) bzw.
  1 s ab, Heartbeat `web.bot_heartbeat` alle 20 s (Website: > 60 s = offline), prüft Rechte selbst: Admin = Recht
  ADMINISTRATOR, Event Maker = `Roles.EVENT_MANAGER` oder Admin. Aktionen: `me`, `events.list` (auch Gäste als ID 0),
  `events.join|leave|create`, `admin.*` (Spieler XP/Level/Reset, Discord-Rollen nur aus `SELF_ROLE_GROUPS` +
  Event Maker + Level-Rollen, `/rolle-zuweisen` inkl. TOR-Rollen, Voice mute/unmute/move, Runde beenden/abbrechen
  (Ergebnis in den Chat des Voice-Channels), MVP-Voting, Steuerpult/Rollen-Panel/Changelog/Tutorial posten, Recap,
  Event-Channel `web.event_channel` (sonst Channel des letzten Events), Mod-Setup → Webhook-URL nur in der Antwort,
  Auftrag wird beim Abholen gelöscht). Mitglieder nur per Spieler-Schlüssel (`WebAuth.playerKeyOf`), nie Discord-IDs
  an den Browser; Channel-/Rollen-IDs als Text. Jede Admin-Aktion → Log-Channel „(Website)“. Gemeinsamer Code mit den
  Slash-Commands: `createGameEvent`, `EventSignup.signup`, `postRolePanel`, `setupModWebhook`, `mvpVotingIn`,
  `postStartedRecaps`. Frontend: `pages/Events.tsx`, `pages/Admin.tsx`, `lib/control.tsx`, `control.css`; „Admin“ in
  der Navigation nur für Admins. Tests: `WebControlTest` (+ MariaDB). Discord-Seite des Bots nur kompiliert, nicht live
  getestet. JARs: `/mnt/project-files/bot-website-steuerung/crewbot-update.zip` + neues `StatsWebsite.zip` (Anleitung
  Schritt 7).
- **Balancing** (03.11.2026, Kilian: Rollen-Statistik für The Other Roles, „Siegquote und Häufigkeit pro Rolle“):
  `/rollen/balancing` (`pages/Balance.tsx`, `balance.css`, Link auf der Rollen-Seite), `/api/balance` aus `web/RoleBalance.kt`
  (reine Rechnung auf `snapshot.views`). Nur Runden mit TOR (`isTorRound`), Rolle zu Rundenbeginn (`roleIdOf`). Je Rolle:
  Häufigkeit (Anteil der TOR-Runden), Siegquote mit 95-%-Wilson-Bereich, Vergleich = restliche Spieler desselben Teams,
  Einschätzung (`balanceVerdict`: zu stark/schwach wenn der Bereich ganz über/unter dem Team liegt, eher stark/schwach ab
  15 Pkt., unter 8 Runden keine), Kills/Überlebt/Rausgewählt/Geraten, Anzahl verschiedener Spieler; Modifikatoren gegen alle
  ohne ihn; Teams + Sondersiege. Zeitraum alle bzw. letzte 50 TOR-Runden. Tests: `RoleBalanceTest`.
- Spielerfarbe (03.11.2026, Kilian: „alle immer ihre most used color“): Listen, Profile, Bestenliste, Crewmates usw. zeigen die
  meistgenutzte Farbe (`PlayerAcc.colorId`, Gleichstand = zuletzt getragene), eine einzelne Runde/Replay die Farbe dieser Runde.
  Farben gibt es erst ab CrewStats 1.5.0 (`round_raw`); ohne Farbdaten feste Ersatzfarbe aus dem Schlüssel (`playerColor`).
- Kosmetik (03.11.2026, Kilian: „Cosmetics … bei ihren Profilen und den fliegenden Crewmates“): meistgetragener Hut/Visier/Skin/
  Haustier je Art (wie die Farbe, „nichts“ zählt mit). Bilder aus den Spieldateien (Addressables-Bundles von Kilians Among Us
  2026.9.29, `tools/cosmetics/catalog.py` + `extract.py` mit UnityPy) in `src/main/resources/web-cosmetics/` (nur im
  Website-JAR). `web/Cosmetics.kt` setzt `/api/crewmate?c=&h=&v=&s=&p=` serverseitig zusammen (Körper umgefärbt wie der
  Spieler-Shader: Rot = Farbe, Blau = Schatten, Grün = Visier; Hut/Visier 50 px über dem Ursprung, Haustier links; Rahmen
  360×340, Körper bei 131/108, 164×209). Frontend: `CrewmateFigure` in `CrewDrift.tsx`, `LookFigure` im Profil. Neue
  Kosmetik nach Spiel-Updates: Skripte erneut laufen lassen (unbekannte Ids werden einfach weggelassen).
- Bestenliste (seit 01.11.2026, Kilian: „etwas übersichtlicher, alles in eine Liste geworfen“): Bereiche Allgemein/Crew/
  Impostor/Unterwegs (`GROUPS` in `metrics.ts`) → Kärtchen je Kennzahl mit Top 3 (Handy: zum Wischen) → Podest →
  Tabelle ab Platz 4 (Suche zeigt alle), „Du: Platz N“ für Angemeldete.
- **Aus Bestenlisten ausnehmen** (07.11.2026, Kilian: „gebe admins die option leute aus bestenlisten auszunehmen“): Admin-Panel
  der Website, Reiter „Bestenliste“ (alle Website-Spieler inkl. unverknüpfter + Discord-Mitglieder ohne Runden) → Aktion
  `admin.leaderboard` {player, hidden} → Bot speichert Website-Spielerschlüssel als JSON-Liste in `settings` `leaderboard.hidden`
  (`services/LeaderboardExclusions.kt`, Discord-Mitglied = `WebAuth.playerKeyOf`), Log-Channel-Eintrag. Wirkung: Website-Bestenliste,
  Top-Listen der Startseite und Rekorde ohne sie (`unranked` in `/api/players`, `rank()` in `metrics.ts` filtert; `StatsStore` lädt
  die Liste bei jedem Abgleich, nach der Aktion sofort), `/leaderboard` im Discord überspringt sie (keine Medaille). Profil, Runden,
  Vergleich, XP und Stats bleiben. Test: `adminsCanHidePlayersFromLeaderboards`.
- **Abstimmungen / Verdachts-Statistik** (03.11.2026, Kilian: wie oft jemand zu Recht oder zu Unrecht gevotet hat): aus den
  Meeting-Stimmen (`StatsSnapshot`): als Crew Impostor getroffen / Crewmate verdächtigt / übersprungen, Rauswürfe mit eigener
  Stimme richtig/falsch (`rightEjections`/`wrongEjections`), als Impostor `deceptions` (Crewmate mit rausgewählt), erhaltene
  Crew-Stimmen `suspectedAsCrew` (zu Unrecht) / `suspectedAsImpostor` (durchschaut) + Listen `wrongSuspects`/`suspectedBy`/
  `caughtBy` im Profil (Abschnitt „Abstimmungen“). Neutrale zählen weder richtig noch falsch, Selbst-Stimmen nicht. Bestenliste:
  `ejections` (Crew, ab 5 Rauswürfen) und `deceptions` (Impostor). Test `voteStatsTellRightFromWrongSuspicion`.
- **Crack-Voting** (07.11.2026, Kilian: „nach jeder Runde auf der Website 15 min lang abstimmen, wer das meiste Crack geraucht
  hat … auch als Bestenliste“): `web/CrackVoting.kt` (`CrackPoll`, `CrackVotes`, `CrackVoting`), Stimmen in `data/website.db`
  Tabelle `web_crack_votes` (game_id, voter_id = Discord-ID, target_id = playerId der Runde). Voting läuft ab `receivedAt` der
  Runde (Uhr des Bots) 15 min, nicht bei Runden, die > 30 min nach Rundenende ankamen (Offline-Puffer) oder aus `round_history`.
  Nur angemeldet, eine Stimme je Konto (änderbar/zurückziehbar), nicht für sich selbst; Stimmenzahlen erst nach dem Ende,
  wer wen gewählt hat geht nie an den Browser. Gleichstand = alle vorne. Beendete Votings fließen beim nächsten Refresh in
  den Snapshot (`crackWins`/`crackVotes`/`crackPolls`, Profil `crack`), Bestenliste Bereich „Crack-Voting“ (`crackwins`,
  `crackvotes`, `crackrate`). Frontend `components/Crack.tsx`, `lib/crack.ts`, `crack.css`: Abschnitt auf der Runden-Seite
  (`#crack`), Hinweis-Streifen auf der Startseite, Abschnitt im Profil. Tests: `CrackVotingTest`.
- **Abzeichen im Profil** (03.11.2026, Kilian: Erfolge/Abzeichen wie „5 Impostor-Siege in Folge“): `web/Badges.kt` berechnet
  26 Abzeichen live aus den Runden (nichts gespeichert, gilt rückwirkend, auch für unverknüpfte Spieler) in vier Gruppen
  (Serien, Momente, Laufbahn, Pech gehabt), je drei Stufen Bronze/Silber/Gold mit der Runde, in der sie erreicht wurden, und
  Seltenheit (Anteil der Spieler mit mindestens Bronze). Unabhängig von den Bot-Erfolgen (`/erfolge`, XP). Neue Abzeichen in
  `BADGES` anhängen, ids nie umbenennen, Symbol in `ICONS` (`web/src/components/Badges.tsx`, `BadgesTest` prüft beides).
- **Spieler-Vergleich** (03.11.2026, Kilian: Profile nebeneinander + Duell-Bilanz): `/vergleich?a=<Schlüssel>&b=<Schlüssel>`
  (`pages/Compare.tsx`, `compare.css`), API `GET /api/compare/<a>/<b>` (`StatsSnapshot.compareJson`, Cache je Paar). Aus allen
  gemeinsamen Runden: gegeneinander (Siege beider, aufgeteilt a-Impostor/b-Crew, umgekehrt, mit Neutralen; je Map; Verlauf),
  im selben Team (zusammen gewonnen), Kills/Stimmen/Rauswählen untereinander, länger überlebt, letzte 12 gemeinsame Runden;
  dazu alle Kennzahlen Kopf an Kopf. Farben = Spielfarben der beiden (bei gleicher Farbe rechts grau). Einstieg: Knopf
  „Vergleichen“/„Mit mir vergleichen“ im Profil, Link in der Bestenliste. Test: `comparesTwoPlayersHeadToHead`.
- **Hilfe & Mods** (02.11.2026, Kilian: „ein Teil auf der Website wo die Tutorials und Mod downloads sind“, „man soll auch alle
  Rollen nachgucken können“): `web/Guides.kt` + `web/Downloads.kt`, Seite `pages/Guides.tsx` (`/hilfe` Tutorials, `/hilfe/downloads`,
  `/downloads` leitet um), Stil in `web/src/guides.css`. **Tutorials** = `TUTORIALS` des Bots (ohne „Alle Special-Rollen erklärt“)
  + Host-Anleitung `WebGuides.HOST_TUTORIAL` und Auto-Join-Anleitung `WebGuides.AUTOJOIN_TUTORIAL` (`/hilfe?t=autojoin`, nur Website); `<#Channel>` wird zu Text, Discord-only-Sätze stehen in
  `WebGuides.DISCORD_ONLY` (Test prüft, dass es sie im Bot-Text noch gibt). **Rollen-Seite** `/rollen` = Lexikon aller Rollen
  (`/api/lexicon`: `ROLES` + `SPECIAL_ROLES`, `torNameKeys` ordnet TOR-Varianten wie niceGuesser zu) mit Statistik aus `/api/roles`,
  ungespielte mit „Noch nicht gespielt“. **Downloads** (`/api/downloads`): eigene Dateien aus `downloads/` neben dem JAR
  (`WEB_DOWNLOADS_DIR`; ausgeliefert unter `/downloads/<datei>`, nur direkt im Ordner, keine versteckten) – dort liegen der
  TheOtherRoles-Installer (bleibt so vom GitHub fern), alles andere erscheint als „Weitere Dateien“. Die **Messer-Mod** soll es
  dort nicht geben (Kilian, 02.11.2026: „aus den Downloads entfernen“) – `knifemod-*.jar` wird auch dann weder gezeigt noch
  ausgeliefert, wenn die Dateien noch im Ordner liegen. CrewStats + TOR-Version: GitHub-API `releases/latest` alle 30 min
  im Hintergrund (ohne Token; Fehler → alte Infos, Links `…/releases/latest/download/<Datei>` gehen immer). CrewStats-Dateien
  lädt der Browser direkt von GitHub. `StatsWebsite.zip` enthält deshalb `downloads/` mit dem Installer.
- **Eigene Seiten / Schach-Analyse** (12.11.2026, Kilian: Schach-Analyse als eine HTML-Datei, „die ich in den aktuellen Website-Server
  reinlegen kann“): Ordner `seiten/` neben der StatsWebsite.jar (`WEB_PAGES_DIR`, `web/Pages.kt`) → `/seiten/<datei>`, wird angezeigt
  statt heruntergeladen, nur Dateien direkt im Ordner mit bekannter Endung, ohne Neustart. HTML dort bekommt eine eigene, lockerere
  CSP (`Pages.CSP`: CDN-Skripte, Inline-Skripte, fremde https-APIs, Blob-Worker, WebAssembly) – die Stats-Seite selbst bleibt streng.
  Läuft unter derselben Adresse wie die Website (gleiche Anmeldung) → nur eigene Dateien. `schach.html` liegt **nicht im Repo**,
  sondern in /mnt/project-files/schach-analyse/ (und im `StatsWebsite.zip` unter `seiten/`): chess.com-API, Stockfish 18 lite als
  Blob-Worker (jsDelivr, Ersatz unpkg, alles mit SRI-Prüfsumme), chess.js 0.10.3, Figuren aus chessground-CSS, Standard-Design.
  Erste Version ohne Statistik-Seite/Training. Mit echter Engine, aber nachgestellter chess.com-API getestet (Cloud erreicht
  chess.com und die CDNs nicht). Test: `serverShowsOwnPagesFromFolder` in `WebGuidesTest`.
- **Schiffe versenken** (14.11.2026, Kilian: Browserspiele auf der Website, jedes Spiel eine eigene Seite, „bunter“ im Stil von
  Codenames, „alles gerne animiert“): `seiten/schiffe.html` **im Repo** (Ordner `seiten/` im Wurzelordner = Standard von
  `WEB_PAGES_DIR` beim lokalen Start; aufs Panel in `seiten/` neben die JAR legen), eine Datei ohne Abhängigkeiten (Schrift Barlow
  Condensed eingebettet, Porträts als SVG im Skript, Töne per Web Audio). 10×10, Schiffe 5/4/3/3/2, gegen den Computer. Sieben
  Kommandeure (`COMMANDERS`: Euryleia Pfeilhagel 1+6 in der Nähe, Potier Surgical Strike, Wade Breitseite Kreuz aus 5, Astrid 6
  zufällig, Ferrara 3×3, Schmidt Torpedo bis zum ersten Schiff, Karslake ganze Reihe/Spalte); die Fähigkeit lädt mit jedem normalen
  Schuss (`cd`). Computer: Wahrscheinlichkeitskarte (`density`/`aiMove`), drei Stärken (ungenauer + Fähigkeit lädt 4/2/0 Schüsse
  langsamer, `SKILL_SLOW`). Regeln ohne DOM unter `window.SV` (damit per Playwright simulierbar). Eigenes buntes Design, nicht das
  Standard-Design. Kein Mehrspieler. Die Spiele-Startseite (Kacheln) baut ein anderer Thread; „Alle Spiele“ zeigt auf `/`.
- **CoreHold-Website im selben Server** (05.11.2026, Kilian: „ich kann keinen neuen [Pelican-Server] machen, wie geht es das wir es auf dem selben machen“):
  Eigenes Programm (nicht im Repo, Quelltext in /mnt/project-files/corehold-website/): Teams, PvP-Tierliste, Special Items mit
  Rezepten des Minecraft-Servers CoreHold unter `/corehold`, Admin-Login per Passwort (`ADMIN_PASSWORD`). Ein Java-Egg startet nur
  eine JAR und hat nur einen Port → `web/CoreHoldBridge.kt`: liegt `CoreHoldWebsite.jar` (oder `COREHOLD_JAR`) neben der
  StatsWebsite.jar, startet die Stats-Website sie als Kindprozess auf `127.0.0.1:COREHOLD_PORT` (18091, `COREHOLD_JAVA_OPTS`,
  Neustart bei Absturz, Ende mit dem Elternprozess) und reicht alles unter `/corehold` durch (Cookies, Origin, X-Forwarded-*).
  X-Forwarded-For/-Host/-Proto eines Reverse-Proxys zählt nur mit `WEB_TRUST_PROXY=true` (sonst Fälschung möglich). Ohne die JAR
  ändert sich nichts. Gleiche `.env` (ADMIN_PASSWORD …), Daten in `data/corehold/`. Test: `CoreHoldBridgeTest`.

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
- Bauen: `dotnet build -c Release` (.NET 8 SDK) – kopiert die DLL nach `BepInEx/plugins`.
  **Nur ins Spiel des Nutzers installieren, wenn er es sagt** (Wunsch vom 17.10.2026) – sonst bauen mit
  `dotnet build CrewStats -c Release -p:AmongUs=C:/nicht-kopieren` (DLL nur in `bin/Release/net6.0`).
  Achtung: auch `release.ps1` baut mit Kopieren. Läuft
  das Spiel, ist die DLL gesperrt: dann umbenennen (`.old`) und die neue daneben legen (aktiv ab
  nächstem Start, `.old` löscht die Mod selbst). Nach Among-Us-Updates `AmongUs.GameLibs.Steam`
  in `CrewStats.csproj` anheben (Feed: nuget.bepinex.dev). Spiel-APIs vorher prüfen – Typen
  ändern sich (z.B. `PlayerId`-Struct statt byte, `TryGet*` mit `out`): kleines
  MetadataLoadContext-Tool gegen die GameLibs-DLLs bauen statt raten.
- Zeichnet nur als Host auf, ignoriert Freeplay/Hide & Seek. JSON-Format = `RoundRecord.cs`
  (camelCase) ↔ `services/ModRoundModel.kt`. Beide Seiten gemeinsam ändern!
  **Rundenformat 2** (CrewStats 1.5.0, 01.11.2026, Kilian: „so viele Infos wie möglich … für eine detaillierte Stats-Website“):
  hängt nur Felder an (Zeitleiste `events`, Laufwege `tracks` 1 Punkt/s, Kosmetik/Level/Plattform, Aufgaben mit Zeiten,
  Lobby- + TOR-Einstellungen, Server, Kennzahlen `summary`), dokumentiert in `docs/rundenformat.md` des Mod-Repos
  (+ `docs/beispiel-runde.json`, auch in `src/test/resources/crewstats/`). Zeitleiste/Polling in `GameTimeline.cs`
  (alle 0,2 s aus HudManager.Update, jeder Teil schaltet sich nach einem Fehler einmal geloggt ab), Rechenteil in
  `RoundStats.cs` (getestet). JSON ohne Einrückung; > 7 MB → ohne Laufwege. Der Bot archiviert jede Runde unverändert in
  `round_raw` (Schema v3, `services/RoundArchive.kt`, ohne Obergrenze, Undo setzt nur `undone = 1`) – Datenquelle für die
  Website. Im echten Spiel noch ungetestet. Mit The Other Roles kommen TOR-Felder
  dazu (siehe „The Other Roles in XP & Auto-Mute“). Reine Regeln (Sieger-Team, Todesarten) in `RoundRules.cs`, getestet
  in `Tests/` (`dotnet test Tests`, ohne Among Us). GitHub baut die Mod bei jedem Push (Workflow „Build“).
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
  ruft `RpcClearVote` (Spieler kann neu wählen, klappt auch ohne Mod); kein Chat-Hinweis (Host sagt
  die Regel im Voice an). Nicht-Abstimmen bleibt möglich.
- **Kein Chat aus der Mod** (seit 1.3.2 gar keine Chat-Nachrichten). Falls doch mal wieder nötig: Hat der
  Spieler „Nur Quick Chat“ (`DataManager.Settings.Multiplayer.ChatMode == QuickChatOnly`, in
  `settings.amogus` `"chatMode": 2`), wirft der Among-Us-Server ihn bei jedem freien Chat-Text
  (`RpcSendChat`) raus („Client DC because Hacking“, danach „Banned“ für diese Lobby) – so in 1.3.0
  passiert. 1.3.1 hatte dafür `GameChat.cs` (nur mit Free Chat + 3 s Sende-Pause, sonst `AddChatWarning`
  lokal) – steht in der Git-Historie des Mod-Repos.
- **Lobby-Ankündigung** (`LobbyReporter.cs`, Config `[Lobby] Announce`, Standard an): nur als Host in
  Online-Lobbys; `{"crewstats":"lobby","state":"open|started|closed","code","map","players","max",
  "host","sender"}` (Spielerzahl höchstens alle 5 s) ↔ `services/Lobby.kt`. Kurze Webhook-Meldungen
  laufen über `LatestWebhookSender` (neueste gewinnt).
- **Rollenwünsche** (`RoleWishes.cs`, Tausch-Logik `RoleWishPlanner.cs`, Verschlüsselung `WishCrypto.cs`,
  Config `[Rollenwuensche] Enabled`, Standard an): nur als Host, Online, normaler Modus.
  `GameStartManager.BeginGame` → Wünsche holen (kein Hinweis im Spiel, klappt auch mit „Nur Quick Chat“).
  `RoleManager.SelectRoles`-Prefix startet (nur wenn es Wünsche gibt, Abruf ≤ 2 min alt) das
  Sammeln: `PlayerControl.RpcSetRole`-Prefix gibt `false` zurück und merkt sich die gewürfelten Rollen;
  der Postfix tauscht nach `RoleWishPlanner` (Admin zuerst, dann nach Wunsch-Zeit; Tausch mit Inhaber,
  sonst Spezialrolle neu, wenn Anzahl/Chance es erlauben; Impostor-Anzahl bleibt) und schickt dann alle
  Rollen per `RpcSetRole` raus (immer, auch bei Fehlern). Ohne Wünsche wird NICHT gesammelt.
  Erkennt die Mod die Rollenvergabe nicht (0 gesammelt), werden die Wünsche erstattet. Kommt der Abruf
  zu spät, gelten die Wünsche erst in der nächsten Runde. Bei doppelter Vergabe an einen Spieler gilt wie
  im Spiel (`roleAssigned`) die erste Rolle (außer `canOverride`).
- **Rollen selbst würfeln** (`RoleShuffle.cs`, Config `[Rollen] SelbstWuerfeln`, Standard an): während
  `RoleManager.SelectRoles` ersetzen Postfixe auf `HashRandom.FastNext`/`Next(max)`/`Next(min,max)` jedes
  Würfelergebnis durch `RandomNumberGenerator` (nur Spiel-Thread, nur im selben Frame). Die Rollenvergabe bleibt
  die des Spiels. Log je Runde: „Rollen selbst gewürfelt (N Würfe mit echtem Zufall)“ – 0 Würfe heißt, das Spiel
  würfelt dort nicht (mehr) mit HashRandom. **Zwei Wege sind im echten Spiel gescheitert, nicht wiederholen:**
  1.3.2 hielt in jeder Runde alle `RpcSetRole` zurück und schickte sie neu → Intro fror ein („Application Hang“);
  1.3.3 patchte `LogicRoleSelectionNormal.AssignRolesForTeam` (virtuell, `Nullable`- und Il2Cpp-Listen-Parameter)
  → Absturz in `coreclr.dll` (0xc0000005) beim Aufruf. Allgemein: nur einfache Signaturen patchen.
  Abstürze/Hänger: Windows-Ereignisprotokoll (Application Error/Hang, .NET Runtime) zeigt das Modul;
  `LogOutput.log` wird beim nächsten Start überschrieben, Unity-Log der Sitzung davor: `Player-prev.log` in
  `%USERPROFILE%\AppData\LocalLow\Innersloth\Among Us`.
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
  Aktuelles Release: **v1.7.1** (02.11.2026, Auto-Join wechselt in die Region des Hosts). Davor **v1.7.0** (02.11.2026, Komfort-Funktionen, s.o. „CrewStats-Komfort“, im echten Spiel ungetestet). Davor
  **v1.6.0** (02.11.2026, Auto-Join + TOR-Rollenwünsche, im echten Spiel ungetestet). Davor **v1.5.0** (01.11.2026, Kilians Wunsch: Rundenformat 2 für eine Stats-Website, s.o.; im echten Spiel
  ungetestet). Davor v1.4.1 (24.10.2026, Kilians Wunsch: Fix gegen Kicks mit TOR – `TorPacketFix.cs` ersetzt TORs
  `CustomOption.ShareOptionSelections` durch kleine Pakete; TOR schickte bei jedem Beitritt die Guesser-Rollenfilter in einem
  1635-Byte-Paket, das auf Modded EU nicht ankam → „Reliable packet … was not ack'd after 7500ms“ im `Player.log`, alle
  flogen ~7 s nach jedem Join raus, auch mit fremdem Host. Hilft nur, wenn der **Host** CrewStats ≥ 1.4.1 hat; am 24.10.2026 von Kilian im echten Spiel bestätigt: „es geht“).
  Davor v1.4.0 (24.10.2026 auf Kilians Wunsch per Workflow veröffentlicht: The Other Roles –
  Rollen/Gewinner/Tode, Meeting-Stumm; im echten Spiel noch ungetestet). Davor v1.3.4 (17.10.2026: Rollen selbst
  würfeln per HashRandom-Postfix, im echten Spiel bestätigt). v1.3.2 friert als Host beim Rundenstart ein, 1.3.3 nie veröffentlicht (stürzte ab). Läuft das
  Spiel, bricht `release.ps1` beim Kopieren ab → von Hand: ohne Kopieren bauen, `gh release create` mit
  `--notes-file release-notes/vX.Y.Z.md`, DLL + Installer anhängen. 1.3.0 kickt Hosts
  mit „Nur Quick Chat“ (Fix in 1.3.1), v1.1.0 hat ein kaputtes Auto-Update. `IncludeSourceRevision-
  InInformationalVersion=false` in der csproj nicht entfernen – sonst „1.2.0+commit“ und der Updater
  kann nicht vergleichen. `installer/CrewStats-Installer.bat` (Batch/PowerShell-Hybrid, **CRLF, ohne
  BOM** – `sed` entfernt CRLF, danach wiederherstellen) findet Among Us über Steam, installiert
  BepInEx x64 (SHA256 geprüft) + neueste `CrewStats.dll` aus GitHub-Releases, fragt optional die
  Webhook-URL; [2] deinstalliert. `Updater.cs` prüft beim Spielstart `releases/latest` und tauscht
  die eigene DLL. Repo-Name steht in `Updater.GitHubRepo` UND `$Repo` im Installer.
  Release: Version in `CrewStats.csproj` erhöhen, committen, dann `mod\release.ps1 "Notizen"`. Ohne Windows
  (Cloud): Notizen in `release-notes/vX.Y.Z.md` des Mod-Repos, pushen, dann Workflow „Release“ im Mod-Repo per
  workflow_dispatch starten (baut die DLL auf GitHub, hängt DLL + Installer an).
  Installer-Tests nur mit Fake-Ordner: `CREWSTATS_GAME_DIR`, `CREWSTATS_BEPINEX_ZIP`, `CREWSTATS_MOD_DLL`.
- Config: `BepInEx/config/de.amongusmanager.crewstats.cfg` (`WebhookUrl`, nur bei geschlossenem
  Spiel ändern – BepInEx überschreibt sie sonst). Log: `BepInEx/LogOutput.log`.
  Runden-Backups: `BepInEx/CrewStats/pending|sent` (nicht gesendete werden nachgeholt).
  Debug: `[Debug] RecordFreeplay = true` → Aufzeichnung + Auto-Mute auch in Freeplay.

## Stand / offen
- Im echten Spiel bestätigt: Mod lädt (x64), Auto-Update, Webhook-Upload, Phasenerkennung des
  Auto-Mute, Menü-Schalter funktionieren (überlappen aber, s.o.). Am 17.10.2026 mit 10 Spielern:
  Rundenaufzeichnung + Senden, **Rollenwünsche** (Abruf und Tausch: „1 erfüllt“, „2 erfüllt“).
- **Noch nicht im echten Spiel bestätigt**: tatsächliches Muten/Taubschalten durch den Bot,
  „Kein Skip bei Notfall“ (`RpcClearVote`), Lobby-Ankündigung im Discord, Highlights/Erfolge/Recap
  mit echten Daten.
- Rundenarchiv `round_raw` (01.11.2026, noch Version 2.5.0): JAR in `/mnt/project-files/bot-2.5.0-rundenarchiv/crewbot-update.zip`,
  Deploy macht Kilian.
- Stats-Website (01.11.2026): auf `main`, JAR in `/mnt/project-files/stats-website/StatsWebsite.zip` (+ `Anleitung.md`
  inkl. Developer-Portal-Schritten); nur mit erfundenen Testrunden geprüft (Screenshots Desktop + Handy), Discord-Login läuft
  auf dem Panel (02.11.2026 von Kilian bestätigt: „geht jetzt“). Panel-Server richtet Kilian ein. Braucht für Replays/Laufwege den
  Bot mit Rundenarchiv **und** CrewStats 1.5.0 beim Host; ältere Runden kommen ohne Replay aus `round_history`.
- Bot **v2.5.0** (24.10.2026, The Other Roles in XP-Vergabe + Discord-Mute bei Blackmailer/Jailor): auf `main`, JAR in
  `/mnt/project-files/bot-2.5.0/crewbot-update.zip`, Deploy macht Kilian. Passend dazu CrewStats **1.4.0** released.
- Bot **v2.4.0** (23.10.2026, The Other Roles in `/special-rolle` + Tutorials): auf `main`, am 23.10.2026 von Kilian aufs Panel geladen (kein GitHub-Release).
- Released am 23.10.2026: Bot **v2.3.0** (`/tutorial`, `/special-rolle`, Recap erst nach 5 h).
- Released am 12.10.2026: Bot **v2.2.0** (Rollenwünsche, `/changelog`, Willkommen/Abschied, dritte Auto-Rolle;
  JAR am GitHub-Release, Deploy aufs Panel macht der Nutzer) und Mod **v1.3.0** (Rollenwünsche, aktualisiert alle Spieler).
- Kotlin-Bot: Logik/Embeds/Befehle/Import per Golden-Tests gegen den Python-Bot geprüft. **Läuft live auf dem
  Pelican-Panel** (Java-Egg, `java -jar AmongUsManager.jar`) – spätestens seit 17.10.2026 (Rollenwünsche und
  Mod-Runden liefen über ihn). Am 23.10.2026 hat der Nutzer das Update mit „Recap erst nach 5 h /
  `/spieleabend-recap`“ hochgeladen. Ob dort MySQL oder SQLite läuft, steht in der `.env` auf dem Panel.

## Testen
`gradlew test` (JUnit 5, `src/test/kotlin`). Golden-Tests (`*GoldenTest.kt`) vergleichen mit
Referenzdaten des originalen Python-Bots (`src/test/resources/golden/golden.json` + `import/`),
erzeugt von `tools/golden/generate_golden.py` (braucht den Python-Bot aus Tag `python-final`):
Inhalte, Formatierer, Parser, alle Embeds, Slash-Command-Definitionen, 665 zufällige Operationen
(Runden, Wetten, Undo, Admin …) mit Spielerstand nach jedem Schritt, und der JSON-Import.
`ServiceBehaviorTest.kt`: Transaktions-Rollback, Undo, Wettsperre, Events, Datenbankdatei.
`RoleWishTest.kt`: Rollenwünsche (XP, Limit, Plätze, Admin, Verfall, Mod-Rückmeldung) und das
Protokoll inkl. eines mit der Mod (.NET) verschlüsselten Testvektors.
`TheOtherRolesTest.kt`: Mod-Runden mit The Other Roles (Sondersieg, Sieg-XP nach Team, Rollen-Aktionen, Wetten, Undo,
Statistik, Embed, Rollen-Labels aller GMIA-nameKeys, Auto-Mute `silenced`).
`StatsWebsiteTest.kt`: Stats-Website auf einer echten Bot-Datenbank (nur lesend): Archiv + Verlauf je einmal, Undo/Freeplay
fehlen, Verknüpfungen, keine Friendcodes in der Ausgabe, HTTP-Routen, gebaute Seite vorhanden.
`WebAccountsTest.kt`: Discord-Login (state, Abbrechen, Abmelden), Sitzungen, Profil-Regeln, Profil speichern (Origin, JSON,
nur eigenes), Profilbilder (`DiscordSync`, Route, an/aus), keine Discord-IDs/CDN-Adressen in der Ausgabe – mit Fake-Discord.
`MariaDbTests.kt`: Pipeline-, Import-, Verhaltens-, Rollenwunsch-, TOR- und Website-Tests nochmal gegen einen echten MariaDB-Server
(mariaDB4j startet ihn automatisch, `TestMariaDb`; die ERROR-Zeile „mysqld … failed unexpectedly“ am
Ende kommt nur vom Beenden des Test-Servers) + Umzug SQLite → MySQL. `JarSmokeTest.kt` startet das
fertige JAR gegen MariaDB: `gradlew shadowJar test -DjarSmoke=true` (vor jedem Deploy sinnvoll).
In der Cloud-Sitzung scheitert er nur an der letzten Prüfung (Discord ist dort nicht erreichbar, statt „Token ungültig“
kommt ein Verbindungsfehler) – Datenbank-Teil und Import laufen.
Services ohne Discord testen: `TestServices` (In-Memory-Datenbank), `*WithoutRoles`-Funktionen.
Embed-Grenzen: JDA prüft lokal (Feld ≤ 1024 UTF-16-Zeichen, Embed ≤ 6000) und wirft sonst.
