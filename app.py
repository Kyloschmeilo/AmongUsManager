"""Startpunkt des Among Us Managers.  Starten mit:  python app.py"""

from crewbot.config import DISCORD_TOKEN
from crewbot.core.client import AmongUsBot


def main() -> None:
    if not DISCORD_TOKEN:
        raise SystemExit(
            "❌ Kein DISCORD_TOKEN gefunden. Lege eine .env-Datei an"
            " (Vorlage: .env.example)."
        )
    AmongUsBot().run(DISCORD_TOKEN, root_logger=True)


if __name__ == "__main__":
    main()
