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
  (Lobby-Ankündigung + Wettsperre), `roleWishes` (`RoleWishes.kt`: Rollenwünsche, Admin-Zuweisungen,
  Protokoll + Verschlüsselung mit der Mod), `welcome` (Willkommens-/Abschiedsnachricht). Mehrteilige Änderungen laufen in einer DB-Transaktion; Discord-
  Aufrufe (Level-Rollen) erst danach.
- `content/` – Texte & Daten: `AmongUs.kt` (Rollen für `/rolle` inkl. Influencer, Maps), `SpecialRoles.kt`
  (30 Rollen + Modifikatoren der Rollen-Mod SpecialRoles für `/special-rolle`, zwei Menüs wegen Discords 25er-Grenze),
  `GameSettingsContent.kt` (deutsche Namen/Kategorien der Lobby-Optionen), `Achievements.kt`
  (Erfolge – ids nie umbenennen, sind gespeichert), `Minecraft.kt`, `Changelog.kt` (Inhalt von `/changelog`), `Tutorials.kt` (Inhalt von `/tutorial`).
- `ui/` – `Embeds.kt` (`Palette`, `Tone`, `notify()`, `progressBar`, `fitLines`, `safeBuild`),
  `Text.kt` (`escapeMarkdown`/`urlQuote` wie discord.py/Python), `InsightsUi.kt`, `ProfileUi.kt`,
  `LobbyUi.kt`, `MinecraftUi.kt`, `views/` (Buttons/Menüs). Menüs mit Timeout erben von
  `SessionView` (custom_id `cv:<id>:<name>`, Besitzer-Prüfung, Timeout); dauerhafte Buttons alter
  Nachrichten haben feste custom_ids (siehe unten).
- `modules/` – Slash-Commands nach Bereich (früher Cogs), Name + Emoji erscheinen automatisch in
  `/hilfe` (Reihenfolge = Liste in `CrewBot.modules`). `/emergency` wurde bewusst entfernt.

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
  Channel/Thread – wichtigste Befehle, Account verknüpfen, Special-Rollen (`content/Tutorials.kt`, `ui/TutorialUi.kt`).
  Im Standard-Design (Akzent `Palette.ACCENT` #1FBF7A, Abschnitte in Großbuchstaben, keine Emoji-Aufzählungen).
  `/befehle` im Text werden anklickbar (`linkCommands` in `ui/Text.kt`), `TutorialTest` prüft, dass es sie gibt.
  Special-Rollen-Text bei Änderungen an Installation/Bedienung der Rollen-Mod mitziehen.
- **Rollen-Mod SpecialRoles** (Repo Kyloschmeilo/SpecialRoles, seit 22.10.2026 alle 30 Rollen in 0.2.0): eigene
  Among-Us-Mod, **unabhängig von CrewStats** (keine Rollenwünsche dafür). Rollenliste: `content/SpecialRoles.kt`
  (17 Crew, 11 Impostor, 2 Neutral + Modifikator Flash; Erpresser höchstens 2× pro Spiel). Bot-Seite:
  `/special-rolle` (Modul Wissen) ergänzt `/rolle`. Texte beschreiben das Verhalten der Mod – mit `Core/Roles.cs`
  dort gemeinsam ändern. Planung: `/mnt/project-files/rollen-mod/rollen-vorschlaege.md` (Projektordner).
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
  Aktuelles Release: **v1.3.4** (17.10.2026: Rollen selbst würfeln per HashRandom-Postfix, im echten Spiel
  bestätigt). v1.3.2 friert als Host beim Rundenstart ein, 1.3.3 nie veröffentlicht (stürzte ab). Läuft das
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
- Released am 12.10.2026: Bot **v2.2.0** (Rollenwünsche, `/changelog`, Willkommen/Abschied, dritte Auto-Rolle;
  JAR am GitHub-Release, Deploy aufs Panel macht der Nutzer) und Mod **v1.3.0** (Rollenwünsche, aktualisiert alle Spieler).
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
`RoleWishTest.kt`: Rollenwünsche (XP, Limit, Plätze, Admin, Verfall, Mod-Rückmeldung) und das
Protokoll inkl. eines mit der Mod (.NET) verschlüsselten Testvektors.
`MariaDbTests.kt`: Pipeline-, Import-, Verhaltens- und Rollenwunsch-Tests nochmal gegen einen echten MariaDB-Server
(mariaDB4j startet ihn automatisch, `TestMariaDb`; die ERROR-Zeile „mysqld … failed unexpectedly“ am
Ende kommt nur vom Beenden des Test-Servers) + Umzug SQLite → MySQL. `JarSmokeTest.kt` startet das
fertige JAR gegen MariaDB: `gradlew shadowJar test -DjarSmoke=true` (vor jedem Deploy sinnvoll).
Services ohne Discord testen: `TestServices` (In-Memory-Datenbank), `*WithoutRoles`-Funktionen.
Embed-Grenzen: JDA prüft lokal (Feld ≤ 1024 UTF-16-Zeichen, Embed ≤ 6000) und wirft sonst.
