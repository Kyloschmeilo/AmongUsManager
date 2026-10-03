"""Dauerhafte Speicherung in JSON-Dateien."""

import json
import os
from pathlib import Path
from typing import Any


class JsonStore:
    """Liest und schreibt eine JSON-Datei.

    Gespeichert wird atomar (erst Temp-Datei, dann Austausch), damit bei einem
    Absturz mitten im Schreiben keine kaputte Datei übrig bleibt.
    """

    def __init__(self, path: Path) -> None:
        self.path = path

    def load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {}
        with self.path.open(encoding="utf-8") as f:
            return json.load(f)

    def save(self, data: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
        with tmp_path.open("w", encoding="utf-8") as f:
            json.dump(data, f, indent=4, ensure_ascii=False)
        os.replace(tmp_path, self.path)
