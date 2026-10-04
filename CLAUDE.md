# Among Us Manager 2

Discord-Bot (Python, discord.py 2.x) für einen deutschen Among-Us-/Gaming-Server plus eine
Host-only-Among-Us-Mod (C#, BepInEx), die Rundendaten automatisch an den Bot schickt.
Kommunikation mit dem Nutzer: **Deutsch**.

## Starten
- Bot: `python app.py` (PyCharm-Run-Config „Bot starten“). Tokens stehen in `.env`
  (`DISCORD_TOKEN`, `WORKER_TOKENS` kommagetrennt) – niemals in den Code schreiben.
- Lokale venv: `.venv` (Python 3.14, discord.py 2.7.1). Mindestens Python 3.10 nötig.
- Produktiv läuft der Bot auf einem **Pelican-Panel** eines Freundes. Deploy = `crewbot/`,
  `app.py`, `requirements.txt`, `.env` hochladen; `data/` auf dem Server NICHT überschreiben
  (dort liegen die echten Spielerdaten).

## Repos (GitHub-Account Kyloschmeilo)
- Bot: **privat** https://github.com/Kyloschmeilo/AmongUsManager (Projektordner, Branch `main`).
  `.env`, `data/` (echte Spielerdaten) und `mod/` sind per `.gitignore` ausgeschlossen – vor jedem
  Commit sicherstellen, dass das so bleibt.
- **Funktionierende (gebaute/getestete) Änderungen immer sofort committen und pushen** – Bot-Repo
  und Mod-Repo. Ein neues Mod-*Release* (`mod/release.ps1`, aktualisiert alle Spieler) nur auf Wunsch.
- Mod: **öffentlich** https://github.com/Kyloschmeilo/CrewStats = eigenes git-Repo im Ordner `mod/`.
- `gh` liegt in `C:\Program Files\GitHub CLI` (evtl. nicht im PATH), git nutzt den gh-Login.

## Aufbau (`crewbot/`)
- `config.py` – alle Discord-IDs (Channels, Rollen), Spielregeln (`Rules`), Dateipfade, .env-Loader.
- `core/client.py` – `AmongUsBot`: erzeugt alle Services, registriert persistente Views,
  lädt die Cogs (`EXTENSIONS`). `core/tree.py` – zentrale Fehlerbehandlung der Slash-Commands.
- `services/` – Logik ohne UI, erreichbar als `bot.<name>` bzw. `interaction.client.<name>`:
  `levels` (XP/Level/Ränge/Level-Rollen), `bets` (Wettbüro), `rounds` (Rundenende),
  `events` (Erinnerung, Anwesenheit, Abschluss), `audit` (Log-Channel), `workers`
  (Worker-Bots fürs parallele Muten), `voice` (Channel-Status gedrosselt), `links`
  (Friendcode ↔ Discord), `game_stats`, `mod_rounds` (Auswertung + Undo der Mod-Runden),
  `automute` (Mutes passend zur Spielphase, die die Mod meldet), `game_settings`
  (Lobbyeinstellungen der letzten Runde für `/rundensettings`, nur der neueste Stand),
  `minecraft` (Minecraft-Kopplung: Webhook, Ziel-Channel, eingeschaltete Nachrichten),
  `round_history` (jede Mod-Runde im Detail, `data/round_history.json`, max. 1000),
  `insights` (reine Rechenfunktionen: Runden-Highlights, Rivalen, Recap/MVP-Kandidaten),
  `achievements` (Erfolge mit XP, Definitionen in `content/achievements.py`, ids nie umbenennen),
  `lobby` (automatische Lobby-Ankündigung im Codes-Channel + Wettbüro-Sperre).
  Speicherung als JSON in `data/` über `services/storage.JsonStore` (atomar).
- `content/among_us.py` – Rollen, Maps, Texte als Datenklassen. `content/minecraft.py` –
  Nachrichtenarten, deutsche Todesursachen, Mob-Namen.
- `ui/embeds.py` – `Palette`, `Tone`, `notify()`. `ui/views/` – alle Buttons/Menüs.
  Basisklassen: `CrewView` → `PersistentView` (überlebt Neustarts, feste custom_ids) /
  `PrivateView` (nur der Öffnende darf klicken).
- `cogs/` – Slash-Commands nach Bereich, alle erben von `CrewCog` (Name + `emoji` erscheinen
  automatisch in `/hilfe`).

## Wichtige Entscheidungen
- custom_ids bestehender Buttons (`persistent_control_panel:*`, `bt_move`, `evt_*_p`,
  `persistent_role_dropdown`) nicht ändern – sonst funktionieren alte Nachrichten nicht mehr.
- Event-XP gibt es erst nach dem Event und nur bei ≥ `EVENT_MIN_ATTENDANCE_MINUTES` im Voice.
  Events werden nach Start + `EVENT_DURATION_HOURS` automatisch gesperrt.
- Level sinkt nie durch Wetteinsätze (nur Admin-Panel/Undo setzen es zurück).
- Admin-Panel-Änderungen werden gebündelt (10 s) in den Log-Channel (`Channels.LOG`) geschrieben.
- Mod → Bot läuft über einen **Discord-Webhook** (kein offener Port auf dem Panel nötig).
  Der Bot akzeptiert nur Nachrichten seines eigenen Webhooks (`data/mod.json`, via `/mod-setup`).
  Jede Mod-Runde hat einen „Zurücksetzen“-Button (`DynamicItem`, custom_id `crewstats:undo:<gameId>`).
- Mod-Runden und manuelle Auswertung (`/crew-win`, Runden-Assistent) nicht doppelt nutzen.
- Mod-Runde auswerten = Stats → `round_history.add` → `achievements.evaluate` → Ledger. „Zurücksetzen“
  nimmt alles zurück (XP, Siege, Wetten, Stats, Verlauf, Erfolge). Highlights/Rivalen/Recap werden
  immer live aus dem Verlauf berechnet (nichts doppelt gespeichert).
- Wettbüro: Lobby-Status „started“ (Mod, Host) sperrt `/bet`; offen wieder nach der Auswertung
  (`settle`), `/runde-abbrechen`, 30 s nach Rundenende ohne Ergebnis oder spätestens nach 45 min.
  Sperre nur im Speicher (Neustart = offen). Recap: `/recap` und automatisch nach jedem Event.

## Minecraft-Kopplung (CoreSMP-Plugin `CoreHold`, Projekt `C:\Users\petra\Desktop\CoreSMP`)
- Plugin → Bot ebenfalls per **Webhook**, der im Log-Channel (`Channels.MINECRAFT_INBOX` = `Channels.LOG`) liegt; der Bot verschiebt ihn beim Start dorthin (URL bleibt gleich). Das Plugin
  schickt höchstens 1×/s eine Nachricht mit JSON im Text: `{"v":1,"server":…,"events":[…]}`.
  Der Bot (`cogs/minecraft.py`) löscht die Rohdaten und postet Embeds (`ui/minecraft.py`) im
  eingestellten Channel. Arten: `join`, `leave`, `death`, `craft` (Special Item).
- JSON-Format = `discord/DiscordListener.java` + `DiscordBridge.java` ↔ `services/minecraft.py`.
  Beide Seiten gemeinsam ändern! Unbekannte Arten/Todesursachen ignoriert der Bot bzw. zeigt
  einen Standardtext.
- `/dc` (Admin): Channel wählen, Nachrichtenarten an/aus, Vorschau, Webhook-URL (+ erneuern).
  Standard-Channel: `Channels.MINECRAFT` (vorerst #test). Einstellungen in `data/minecraft.json`.
- Stand: Bot-Seite offline getestet, Plugin kompiliert, **im echten Spiel noch nicht getestet**.

## Mod (`mod/CrewStats/`)
- Among Us ist seit **2026.9.29** (Steam) **64-Bit** → BepInEx 6.0.0-be.735 muss die
  **IL2CPP-win-x64**-Variante sein. Installiert in `D:\SteamLibrary\steamapps\common\Among Us`.
  Spielversion beim Bau: **2026.9.29**. Das Spiel startet nur einmal gleichzeitig (single-instance).
- Bauen: `dotnet build -c Release` (.NET 8 SDK installiert) – kopiert die DLL automatisch nach
  `BepInEx/plugins`. Nach Among-Us-Updates `AmongUs.GameLibs.Steam` in `CrewStats.csproj`
  auf die neue Version heben (Feed: nuget.bepinex.dev).
- Zeichnet nur als Host auf, zeigt während der Runde nichts an (kein Vorteil), ignoriert
  Freeplay/Hide & Seek. JSON-Format = `RoundRecord.cs` (camelCase) ↔ `services/mod_rounds.py`.
  Beide Seiten gemeinsam ändern!
- **Auto-Mute** (`AutoMute.cs`, Schalter im ESC-Menü `MenuToggles.cs`, Config
  `[AutoMute] Enabled`, Standard aus): meldet Phasenwechsel (tasks/meeting/end) als JSON im
  Nachrichtentext über denselben Webhook ↔ `services/automute.py`. Beide Seiten gemeinsam ändern!
  Bot schaltet im Voice des Absenders (Friendcode-Verknüpfung), sonst `Channels.AMONG_US_VOICE`.
  Verhalten wie AutoMuteUs (`target_state`): Tasks = Lebende stumm+taub, Tote reden; Meeting =
  Lebende reden, Tote stumm; end = alle frei. Tote (`dead`) nur beim Phasenwechsel, damit Discord-
  Symbole keinen Kill verraten. Panel-Unmute, „Runde beenden“ und Auto-Unmute-Channel heben auch
  „taub“ auf (`WorkerManager.release`). Läuft auch ohne Host-Rolle. Debug: `[Debug] RecordFreeplay = true` → Aufzeichnung + Auto-Mute
  auch in Freeplay.
- **Hausregel „Kein Skip bei Notfall“** (`NoSkipRule.cs`, Schalter in `MenuToggles.cs`, Config
  `[Regeln] NoSkipOnEmergency`, Standard aus): nur als Host. `CastVote`-Prefix lehnt Skip-Stimmen in
  Meetings ohne Leiche ab und ruft `RpcClearVote` (Spieler kann neu wählen, klappt auch ohne Mod);
  Chat-Hinweis beim Meeting-Start. Nicht-Abstimmen (Zeit ablaufen) bleibt möglich.
- **Lobby-Ankündigung** (`LobbyReporter.cs`, Config `[Lobby] Announce`, Standard an): nur als Host in
  Online-Lobbys; sendet `{"crewstats":"lobby","state":"open|started|closed","code","map","players",
  "max","host","sender"}` (Spielerzahl höchstens alle 5 s, Zustandswechsel sofort) ↔ `services/lobby.py`.
  Kurze Webhook-Meldungen laufen über `LatestWebhookSender` (neueste gewinnt), auch Auto-Mute.
- **Rundeneinstellungen** (`SettingsReporter.cs`): beim Rundenstart (auch ohne Host) werden alle
  Optionen generisch über `Int32/Float/BoolOptionNames` + Rollen-Anzahl/Chance als
  `crewstats-settings.json` (Anhang) gesendet ↔ `services/game_settings.py`; deutsche Namen und
  Kategorien in `content/game_settings.py`, Anzeige `ui/views/game_settings.py`. Unbekannte
  Optionen/Rollen erscheinen roh unter „Weitere“. Influencer heißt im Spiel intern `SpiritGuide`.
- **Verteilung & Updates**: `mod/` ist ein eigenes, öffentliches GitHub-Repo
  https://github.com/Kyloschmeilo/CrewStats (nur Mod + Installer, kein Bot-Code, keine Webhook-URLs!).
  git-Identität im Repo: Kyloschmeilo / noreply-Adresse. `gh` liegt in `C:\Program Files\GitHub CLI`
  (evtl. nicht im PATH der Shell). Aktuelles Release: **v1.1.1** (v1.1.0 hat ein kaputtes Auto-Update).
  `IncludeSourceRevisionInInformationalVersion=false` in der csproj nicht entfernen – sonst wird die
  Version „1.1.1+commit“ und alte Updater-Versionen können sie nicht vergleichen. `installer/CrewStats-Installer.bat` (Batch/PowerShell-Hybrid,
  CRLF, ohne BOM) findet Among Us über Steam, installiert BepInEx x64 (SHA256 geprüft) + neueste
  `CrewStats.dll` aus GitHub-Releases, fragt optional die Webhook-URL. `Updater.cs` prüft beim
  Spielstart `releases/latest` und tauscht die eigene DLL (geladene DLL umbenennen → .old, beim
  nächsten Start löschen). Repo-Name steht in `Updater.GitHubRepo` UND `$Repo` im Installer.
  Release: Version in `CrewStats.csproj` erhöhen, dann `mod\release.ps1 "Notizen"` (braucht `gh`).
  Installer-Tests nur mit Fake-Ordner: `CREWSTATS_GAME_DIR`, `CREWSTATS_BEPINEX_ZIP`, `CREWSTATS_MOD_DLL`.
- Config: `BepInEx/config/de.amongusmanager.crewstats.cfg` (`WebhookUrl`). Log:
  `BepInEx/LogOutput.log`. Runden-Backups: `BepInEx/CrewStats/pending|sent`.
- Stand: kompiliert und Bot-Seite offline getestet, Webhook-Upload mit Fake-Runde erfolgreich.
  **Aufzeichnung und Auto-Mute (inkl. Menü-Schalter) im echten Spiel noch nicht getestet.**

## Testen
Es gibt keine Test-Suite im Repo. Bisher wurde offline getestet: Bot instanziieren, alle
Extensions laden (ohne Login), Services/Views mit Fake-Objekten aufrufen, Datenpfade in
`crewbot.config` vorher auf ein Temp-Verzeichnis umbiegen (nie `data/` verändern).
