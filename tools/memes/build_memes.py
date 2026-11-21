"""Erzeugt die Meme-Pakete der Meme-Schmiede aus der memegen-Sammlung (github.com/jacebrowning/memegen, templates/).

    python3 tools/memes/build_memes.py src/main/resources/web-memes

Lädt je Vorlage config.yml + default.<jpg|png|gif> von raw.githubusercontent.com (Zwischenspeicher tools/memes/.cache/,
nicht im Repo), verkleinert die Bilder auf höchstens 720 px und schreibt <out>/vorlagen.json + <out>/<id>.jpg.
Neue Vorlage: id (Ordnername bei memegen) in ein Paket unten eintragen, Skript laufen lassen. Braucht PyYAML + Pillow.

Dazu kommen die Pakete aus tools/memes/eigene.yml (SpongeBob, Deutsche Klassiker, Brainrot …): Bilder, die es bei
memegen nicht gibt, mit selbst festgelegten Textfeldern (ohne Angabe: Text oben + unten). Die Originalbilder liegen in
tools/memes/eigene-bilder/<paket>/<id>.<endung> (Quellen in manifest.json; anderer Ordner: --bilder <ordner>).
"""
import glob
import json
import os
import sys
import urllib.request

import yaml
from PIL import Image

OUT = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))
PICS = sys.argv[sys.argv.index("--bilder") + 1] if "--bilder" in sys.argv else os.path.join(HERE, "eigene-bilder")
DEFAULT_BOXES = [{"x": 0.03, "y": 0.02, "w": 0.94, "h": 0.22, "u": 1}, {"x": 0.03, "y": 0.76, "w": 0.94, "h": 0.22, "u": 1}]
OWN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eigene.yml")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")
RAW = "https://raw.githubusercontent.com/jacebrowning/memegen/main/templates/"


def fetch(tid):
    """config.yml und Bild einer Vorlage in den Zwischenspeicher holen."""
    os.makedirs(os.path.join(CACHE, "img"), exist_ok=True)
    yml = os.path.join(CACHE, tid + ".yml")
    if not os.path.exists(yml):
        with urllib.request.urlopen(RAW + tid + "/config.yml", timeout=30) as r:
            data = r.read()
        with open(yml, "wb") as f:
            f.write(data)
    if not glob.glob(os.path.join(CACHE, "img", tid + ".*")):
        for ext in ("jpg", "png", "gif", "webp"):
            try:
                with urllib.request.urlopen(RAW + tid + "/default." + ext, timeout=60) as r:
                    data = r.read()
            except Exception:
                continue
            with open(os.path.join(CACHE, "img", tid + "." + ext), "wb") as f:
                f.write(data)
            break
    images = glob.glob(os.path.join(CACHE, "img", tid + ".*"))
    if not images:
        raise SystemExit(f"Kein Bild für {tid} geladen (default.jpg/png/gif/webp fehlt oder Netzfehler)")
    return yml, images[0]

PACKS = [
    ("klassiker", "Klassiker", "Die bekannten Bild-Memes mit Text oben und unten", """
        aag aint-got-time atis agnes bihw blb both buzz disastergirl ermg fa facepalm feelsgood fine fwp ggg happening
        harold hipster interesting iw jd kermit lrv mmm oag rollsafe saltbae sb scc sk sohappy sohot ss stonks success
        tenguy toohigh xy yodawg yuno zero-wing keanu cbg kk mini-keanu biw ll"""),
    ("auswahl", "Vergleiche & Bildfolgen", "Drake, Distracted Boyfriend, Galaxy Brain, Gru, Scooby-Doo und Co.", """
        drake db dg ds gb exit pooh vince panik-kalm-panik astronaut gru reveal right chair same pigeon handshake balloon
        drowning nails mouth spiderman slap dbg kombucha khaby-lame bongo ntot cmm woman-cat friends captain-america
        grave crow dragon jim seagull"""),
    ("film", "Filme & Serien", "Szenen aus Filmen, Serien und Cartoons", """
        ackbar afraid away bad badchoice bender bilbo box captain center chosen country crazypills dodgson dwight elf
        fetch firsttry gandalf gears genie gone hagrid inigo joker jw kramer light live michael-scott millers money
        mordor morpheus mw nice officespace older red regret remembers say soup-nazi sparta stew whatyear
        winter wonka worst waygd fry leo ski"""),
    ("tiere", "Tiere", "Doge, Grumpy Cat, Robben, Pinguine und andere Viecher", """
        doge cheems grumpycat boat ams soa sf awesome awkward fbf philosoraptor puffin sarcasticbear persian snek
        icanhas cb bs tried noidea"""),
]

DARK = {"black", "maroon"}
MAX_SIDE = 720


def clamp(v):
    return round(min(1.0, max(0.0, float(v))), 4)


def shrink(src, tid):
    """Bild öffnen (erstes Bild bei Animationen), Transparenz auf Weiß, verkleinern, als <out>/<id>.jpg speichern."""
    im = Image.open(src)
    im.seek(0)
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        bg = Image.new("RGBA", im.size, "white")
        bg.alpha_composite(im)
        im = bg
    im = im.convert("RGB")
    im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
    im.save(os.path.join(OUT, tid + ".jpg"), quality=80, optimize=True, progressive=True)
    return im.size


def own_packs(seen, packs, templates):
    """Pakete aus eigene.yml: memegen-ids + eigene Bilder mit Textfeldern (Kurzschlüssel wie in vorlagen.json)."""
    cfg = yaml.safe_load(open(OWN, encoding="utf-8"))
    for pack in cfg["packs"]:
        packs.append({"id": pack["id"], "name": pack["name"], "desc": pack["desc"]})
        for tid in (pack.get("memegen") or "").split():
            add_memegen(tid, pack["id"], seen, templates)
        for t in pack.get("templates") or []:
            tid = t["id"]
            if tid in seen:
                raise SystemExit("doppelt: " + tid)
            seen.add(tid)
            src = glob.glob(os.path.join(PICS, pack["id"], tid + ".*"))
            out = os.path.join(OUT, tid + ".jpg")
            if src:
                size = shrink(src[0], tid)
            elif os.path.exists(out):
                size = Image.open(out).size
            else:
                raise SystemExit(f"Kein Bild für {tid} (--bilder <ordner> mit {pack['id']}/{tid}.jpg angeben)")
            boxes = []
            for b in t.get("boxes") or DEFAULT_BOXES:
                box = {k: clamp(b[k]) for k in ("x", "y", "w", "h")}
                box["w"] = clamp(min(box["w"], 1 - box["x"]))
                box["h"] = clamp(min(box["h"], 1 - box["y"]))
                for k in ("r", "k", "c", "u", "f"):
                    if k in b:
                        box[k] = b[k]
                boxes.append(box)
            if not boxes:
                raise SystemExit("ohne Text: " + tid)
            templates.append({"id": tid, "name": t["name"], "pack": pack["id"], "w": size[0], "h": size[1], "boxes": boxes})


def add_memegen(tid, pack_id, seen, templates):
    """Eine memegen-Vorlage laden, verkleinern und mit ihren Textfeldern anhängen."""
    if tid in seen:
        raise SystemExit("doppelt: " + tid)
    seen.add(tid)
    yml, src = fetch(tid)
    cfg = yaml.safe_load(open(yml, encoding="utf-8"))
    size = shrink(src, tid)
    boxes = []
    for t in cfg.get("text") or []:
        x, y = clamp(t.get("anchor_x", 0)), clamp(t.get("anchor_y", 0))
        w = clamp(min(float(t.get("scale_x", 1)), 1 - x))
        h = clamp(min(float(t.get("scale_y", 0.2)), 1 - y))
        color = str(t.get("color", "white"))
        box = {"x": x, "y": y, "w": w, "h": h}
        if t.get("angle"):
            box["r"] = round(-float(t["angle"]), 1)  # memegen/PIL: positiv = gegen den Uhrzeigersinn; CSS umgekehrt
        if color in DARK:
            box["k"] = "dark"
        elif color != "white":
            box["c"] = color
        if t.get("style") == "upper":
            box["u"] = 1
        if t.get("font") == "thin":
            box["f"] = "thin"
        boxes.append(box)
    if not boxes:
        raise SystemExit("ohne Text: " + tid)
    templates.append({"id": tid, "name": cfg.get("name") or tid, "pack": pack_id, "w": size[0], "h": size[1], "boxes": boxes})


def main():
    os.makedirs(OUT, exist_ok=True)
    seen = set()
    templates = []
    packs = []
    for pack_id, name, desc, ids in PACKS:
        ids = ids.split()
        packs.append({"id": pack_id, "name": name, "desc": desc})
        for tid in ids:
            add_memegen(tid, pack_id, seen, templates)
    own_packs(seen, packs, templates)
    data = {"source": "memegen (github.com/jacebrowning/memegen, Ordner templates/) + tools/memes/eigene.yml", "packs": packs, "templates": templates}
    with open(os.path.join(OUT, "vorlagen.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(len(templates), "Vorlagen", {p["id"]: sum(1 for t in templates if t["pack"] == p["id"]) for p in packs})


main()
