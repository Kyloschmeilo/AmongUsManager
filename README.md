# Among Us Manager 2

Discord-Bot (Python, discord.py 2.x) für unseren Among-Us- und Gaming-Server: Level & XP,
Wettbüro, Events mit Anwesenheit, Voice-Steuerung mit Worker-Bots, Minecraft-Kopplung und die
automatische Auswertung der **CrewStats-Mod** ([eigenes Repo](https://github.com/Kyloschmeilo/CrewStats)).

## Starten

1. Python **3.10+** installieren, dann im Projektordner:
   ```
   python -m venv .venv
   .venv\Scripts\pip install -r requirements.txt
   ```
2. `.env.example` nach `.env` kopieren und die Tokens eintragen
   (`DISCORD_TOKEN`, optional `WORKER_TOKENS` kommagetrennt). **`.env` niemals hochladen.**
3. `python app.py` (in PyCharm: Run-Config „Bot starten“).

Alle Discord-IDs (Channels, Rollen) und Spielregeln stehen in `crewbot/config.py`.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `crewbot/core/` | Bot-Klasse, Laden der Cogs, zentrale Fehlerbehandlung |
| `crewbot/cogs/` | Slash-Commands nach Bereich (erscheinen automatisch in `/hilfe`) |
| `crewbot/services/` | Logik ohne UI: Level, Wetten, Events, Auto-Mute, Mod-Runden, … |
| `crewbot/ui/` | Embeds, Buttons und Menüs |
| `crewbot/content/` | Texte & Daten: Rollen, Maps, Einstellungsnamen |
| `data/` | Spielerdaten als JSON – **nicht im Repo** |

Mehr Details (Entscheidungen, Mod-Schnittstelle, Testen) in [`CLAUDE.md`](CLAUDE.md).

## Deploy (Pelican-Panel)

`crewbot/`, `app.py`, `requirements.txt` und `.env` hochladen und den Bot neu starten.
`data/` auf dem Server **nicht** überschreiben – dort liegen die echten Spielerdaten.
