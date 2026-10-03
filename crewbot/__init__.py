"""Among Us Manager – Discord-Bot für Among-Us-Runden, Events und das Crew-Level-System.

Aufbau:
    config.py   Tokens, Discord-IDs, Spielregeln
    core/       Bot-Klasse und globaler Command-Tree (Fehlerbehandlung)
    services/   Logik ohne UI: Level, Wetten, Worker-Bots, Voice, Speicher
    content/    Statisches Among-Us-Wissen (Rollen, Maps, Texte)
    ui/         Embeds, Farben und alle Buttons/Menüs (Views)
    cogs/       Die Slash-Commands, nach Feature-Bereich gruppiert
"""
