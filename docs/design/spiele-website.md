# Design-System „Spieleabend“ – Spiele-Website

Gilt für alles auf **spieleabend.timonso.de außerhalb von `/amongus`**: die Spiele-Startseite (`/`), die Browserspiele unter
`/seiten/…` und `/spiele/…` und kleine Zusatzseiten. Der Among-Us-Teil hat ein eigenes System:
[amongus.md](amongus.md).

Stand: 22.11.2026, ausgelesen aus `web/src/start/start.css`, `web/src/start/games.tsx`, den Dateien in `seiten/` und dem
Hell/Dunkel-Umschalter aus PR #34 (`web/src/lib/theme.tsx`).

## Die Idee in einem Satz

Ein buntes Brettspiel-Regal bei Nacht: dunkler Grund, **Sticker** mit dicker dunkler Kontur und hartem Versatz-Schatten,
kräftige Bonbonfarben, jedes Spiel hat seine eigene Farbe. Laut und verspielt, aber aufgeräumt – Among Us steht immer
vorne und am größten.

Herkunft: Das Standard-Design des Projekts („CastCrafter“, Artifact FDYKUE3X77xyXqkEPPKxb1) ist ruhig, dunkelgrün und ohne
Lila. Für die Spiele hat Kilian es am 14.11.2026 ausdrücklich gelockert („Lass ein bisschen vom Design system los und mach
die ganzen Seiten etwas bunter … alles auch gerne animiert“). Übernommen aus dem Standard-Design werden nur die
Grundregeln: 4-px-Abstandsraster, sichtbarer Fokus-Ring, Kontrast 4,5 : 1, keine Emojis als Icons, keine Floskeln,
keine Verläufe als Hintergrund, kein Glas/Blur.

## Farben

### Grund und Text

| Token | Dunkel (Standard) | Hell | Wofür |
| --- | --- | --- | --- |
| `--bg` | `#16122b` | `#fff1dc` | Seitengrund, mit Punktraster (26 px) |
| `--bg-2` | `#1f1940` | `#ffffff` | Große Flächen wie die Live-Leiste |
| `--paper` | `#fff7ea` | `#fff7ea` | Text auf Dunkel, Füllung von Chips/Knöpfen |
| `--line` | `#120f24` | `#120f24` | **Konturen, harte Schatten, Text auf bunten Flächen.** In beiden Themes gleich |
| `--muted` | `#b9b2d6` | `#5a5378` | Nebentext, Hinweise, Fußzeile |

Im hellen Theme wird `body` auf `--line` als Textfarbe gesetzt; das Punktraster wird dunkel (`rgba(18,15,36,.1)`).

### Bonbonfarben (Akzente)

| Name | Wert | Typische Verwendung |
| --- | --- | --- |
| Pink | `#ff4f81` | Buchstabe 1 im Logo-Schriftzug, Akzente |
| Gelb | `#ffd23f` | Hover-Füllung von Knöpfen, Haupt-Knopf, gestartete Lobby, Sonne-Knopf |
| Grün | `#2fd27a` | „Live“-Etikett, Erfolg |
| Cyan | `#3ad0ff` | Logo-Buchstabe, Info |
| Violett | `#b08cff` | Logo-Buchstabe, Mond-Knopf (hell aktiv) |
| Orange | `#ff8a3d` | Logo-Buchstabe |
| Rot (Live) | `#ff3b47` | Pulsierender Live-Punkt |
| Lobby-Rot | `#ff4f5e` | Offene Among-Us-Lobby in der Live-Leiste |
| Among-Us-Rot | `#d62a20` | Die große Among-Us-Kachel (mit Sternenpunkten) |

Der Schriftzug oben färbt die Buchstaben reihum Pink → Gelb → Grün → Cyan → Violett → Orange.

### Spielfarben (Kacheln)

Jedes Spiel in `GAMES` (`web/src/start/games.tsx`) hat drei Farben: `color` (Kachelgrund), `ink` (Text darauf, nur
`#120f24` oder `#ffffff`) und `accent` (Etikett). Beispiele: Schiffe versenken `#2f7cf6`/weiß/`#ffd23f`, Kritzelkiste
`#ffc928`/dunkel/`#ff4f81`. Regel: Ist die Kachel hell (Gelb, Pink, Grün, Cyan, Beige), ist `ink` dunkel; ist sie satt
und dunkel (Blau, Rot, Violett), ist `ink` weiß. Neue Spiele bekommen eine Farbe, die es im Regal noch nicht gibt.

### Team-Farben (nur wo Among-Us-Daten stehen)

Crew `#7cc6e4`, Impostor `#ff6b5e`, Neutral `#c493ec`. Hell: `#1c7499`, `#c9362b`, `#8549bb`. Gleiche Bedeutung wie im
Among-Us-System, damit eine Lobby auf der Startseite gleich aussieht wie in `/amongus`.

## Schrift

| Rolle | Schrift | Schnitte | Wo |
| --- | --- | --- | --- |
| Display | **Lilita One** | 400 | Schriftzug, Kacheltitel, Zahlen in Stat-Kästchen, großer Knopf |
| Text | **Barlow** | 500, 600 | Fließtext (17 px / 1,45), Chips, Knöpfe |
| Etiketten | **Barlow Condensed** | 600 | Etiketten in Großbuchstaben, 11–12 px, Laufweite .12em |

Alle selbst gehostet über `@fontsource` (keine Google-Anfragen). Größen:

- Schriftzug: `clamp(52px, 10vw, 128px)`, Zeilenhöhe .95, Kontur `-webkit-text-stroke: 3px var(--line)` + `text-shadow: 0 6px 0 var(--line)`
- Among-Us-Titel: `clamp(56px, 7vw, 96px)` mit gleicher Kontur
- Kacheltitel 28 px, Lobby-Map 26 px, Live-Label 30 px (alle Lilita One)
- Untertitel 18 px, Text 15–17 px, Nebentext 14 px

Die Spiele in `seiten/` haben eigene, abgerundete Schriften (Fredoka, Nunito, Baloo 2, Lilita One, Barlow Condensed bei
Schiffe versenken). Das ist erlaubt, solange es eine runde, freundliche Schrift ist. Comic Sans gibt es nur als
Meme-Schrift innerhalb der Meme-Schmiede.

## Formen, Abstände, Schatten

- **Kontur:** 3 px `--line` an allem, was man anfassen kann (Kacheln, Knöpfe, Live-Leiste), 2,5 px an Chips und Etiketten.
- **Harter Schatten** statt weicher: `--pop: 6px` → `box-shadow: 6px 6px 0 var(--line)` an Kacheln und Leiste,
  4 px an Lobby-Karten, 3 px an Knöpfen. Nie verschwommen.
- **Radien:** Kachel 22 px, Live-Leiste 20 px, Lobby-Karte/Theme-Knopf 14 px, Knopf/Stat-Kästchen 12 px, Chip/Etikett 99 px (Pille).
- **Abstände:** Raster in 4-px-Schritten. Kachel-Innenabstand 18 px (Among Us 26 px), Lücke im Raster 20 px (Handy 16 px),
  Live-Leiste 14 px innen, 26 px über und 28 px unter ihr.
- **Breite:** `min(1240px, 100% - 32px)`.
- **Raster:** 4 Spalten; Among Us `span 2` × `span 3` (mind. 460 px hoch). Unter 1020 px 2 Spalten, unter 620 px eine.
  Breite Kacheln (`st-game--wide`) nur, wenn das Raster dadurch aufgeht.

## Bausteine

### Kopfzeile / Kopf
Kein Menübalken. Oben mittig der bunte Schriftzug (jeder Buchstabe ein eigener Sticker), darunter ein Satz in `--muted`,
rechts oben der Hell/Dunkel-Knopf (48 × 48, Gelb mit Sonne; im hellen Theme Violett mit Mond).

### Live-Leiste (`.st-live`)
Ein großer Kasten (`--bg-2`, Kontur, harter Schatten). Links das Label „Live“ mit pulsierendem roten Punkt, getrennt durch
eine gestrichelte Linie; rechts eine waagerecht scrollende Reihe aus Karten:
- **Lobby-Karte** (250 px): offen = Lobby-Rot, gestartet = Gelb; Status-Etikett, Map (Lilita One), Host, kleine hüpfende
  Crewmates je Spieler, Füllbalken (Spieler / max).
- **Spiel-Karte** (`.st-lobby--game`): Kachelfarbe des Spiels, Icon oben rechts schräg, Raum-Code / Spielerzahl, Link
  tritt direkt bei (`?raum=CODE` bzw. `?los=1`).
- Nichts los: Satz in `--muted` + helle Chips mit den letzten Runden.

### Kacheln (`.st-tile`)
- **Among Us** – immer die erste und größte Kachel: Rot mit Sternenpunkten, schwebende Crewmates, unten Titel, Satz,
  Stat-Kästchen (Papier mit Kontur) und Knöpfe.
- **Spiel-Kachel** – Kachelfarbe, 64-px-Icon (SVG mit 3-px-Kontur `#120f24`, runde Enden), Etikett oben rechts
  (z.B. STRATEGIE, PARTY), Titel, ein Satz, unten „Spielen →“ (Lilita One 18 px).
- **Bald** – Spiel ohne Datei: entsättigt (`saturate(.35) brightness(.8)`), ohne Hover.
- **Neu** – unbekannte Datei in `seiten/`, Titel/Beschreibung aus `<title>`/`<meta name="description">`.

### Community (`.st-cm`, unter den Kacheln)
Kasten wie die Live-Leiste (`--bg-2`, Kontur, harter Schatten, links das Label „Community“ in Lilita One 30 px, gestrichelt
getrennt). Rechts drei Sticker-Karten (14 px Radius, 4 px Schatten, heben sich beim Hover): **Memes** in der Kachelfarbe der
Meme-Schmiede, **Zeichnungen** in der Farbe der Kritzelkiste – jeweils mit dem Icon des Spiels oben rechts (wackelt beim
Hover) und der Anzahl als Etikett –, **Changelog** auf Papier mit gelben Kästchen als Aufzählung. Vorschaubilder sind kleine
Sticker (12 px Radius, 3 px Schatten, eindrücken beim Klick), Herzen als SVG-Icon in einem Papier-Chip. Großansicht: Papier-Karte
mit 8-px-Schatten, gelbe Blätter-Knöpfe, Schließen als Papier-Knopf. Moderation über `.st-chip`. Die Karten springen nach dem
Seiten-Einflug herein (`whenEntered` in `start/entered.ts`).

### Knöpfe (`.st-btn`)
Papier-Füllung, 3 px Kontur, 12 px Radius, 3 px harter Schatten, Barlow 600.
- Hover: hebt sich 2 px nach links oben, Schatten 5 px, Füllung Gelb.
- Gedrückt: rutscht 2 px nach rechts unten, Schatten 1 px („eingedrückt“).
- Groß (`--big`): Gelb, Lilita One 22 px; Hover Weiß.
- Pfeil-Icon rutscht beim Hover 4 px nach rechts.

### Chips und Etiketten
- **Chip** (`.st-chip`): Papier, Kontur 2,5 px, Pille, Text dunkel 14 px. Hover Gelb.
- **Etikett** (`.st-tag`): Pille, Barlow Condensed 12 px in Großbuchstaben, Füllung = Akzent des Spiels.
  `--live` Grün, `--light` Papier.

### Tabs / Auswahl in den Spielen
Die Spiele zeichnen Tabs und Schalter selbst, aber nach derselben Regel: Sticker mit Kontur, ausgewählt = gefüllt
(Gelb oder Spielfarbe) und „eingedrückt“, nicht ausgewählt = Papier oder Grund mit Kontur.

### Fuß
Klein, `--muted`, Links unterstrichen (3 px Abstand), Hover `--paper`.

## Bewegung

Alles Animierte steht unter `@media (prefers-reduced-motion: no-preference)`; mit „weniger Bewegung“ steht alles still
und ist sofort sichtbar.

- **Einflug:** Die Seite bleibt unsichtbar, bis Lobbys/Übersicht/Seiten da sind (max. 1,5 s), dann fliegt alles in einer
  GSAP-Zeitleiste herein. Später Erscheinendes springt einzeln herein (`useAppear`).
- **Kacheln** kippen leicht zum Zeiger (perspective 900 px, bis 5°/7°) und heben sich beim Hover.
- **Icons** spielen beim Hover ihr eigenes Mini-Thema: Wellen wackeln, Geschütz schwenkt, Schlange wippt, Kritzel zeichnet
  sich, Blob pulsiert. Jede Kachel anders.
- **Schriftzug:** Buchstaben hüpfen beim Hover einzeln hoch und kippen (federnd, `cubic-bezier(.3,1.8,.5,1)`).
- **Hintergrund:** Konfetti-Formen (Kreis, Quadrat, Strich, Dreieck) mit Kontur verschieben sich leicht mit der Maus.
- **Live:** Punkt pulsiert (`st-ping`), Crewmates in Lobby-Karten wippen, Spiel-Icons wackeln beim Hover.
- Federnde Kurven für „Spaß“-Bewegung, `ease-out` 120–250 ms für Zustandswechsel.

## Hell / Dunkel (PR #34)

- Eine Einstellung für die ganze Domain: `localStorage` `spiele.thema` (`dark` | `light`), Standard Dunkel. Gesetzt wird
  `data-theme="light"` am `<html>`, schon vor dem ersten Rendern (`initTheme`, kein Aufblitzen). Wechselt man in einem Tab,
  ziehen alle anderen mit (`storage`-Ereignis).
- Hell = warmes Papier `#fff1dc`, weiße Flächen, Text `#120f24`. **Konturen und harte Schatten bleiben dunkel** – der
  Sticker-Look bleibt in beiden Themes gleich. Die bunten Kacheln ändern sich nicht.
- Team-Farben werden im Hellen dunkler (siehe oben), damit sie auf Papier lesbar sind.
- Browserspiele binden `/seiten/spiele-ton.js` im `<head>` ein (Ton/Musik-Knöpfe). Die Spiele selbst haben bisher nur ihr
  eigenes Farbschema und lesen `spiele.thema` nicht; wer ein helles Spiel baut, sollte den Schlüssel übernehmen.

## Barrierefreiheit

- Text auf Kacheln nur `#120f24` oder `#ffffff`, je nachdem, was mehr Kontrast hat (≥ 4,5 : 1).
- Fokus sichtbar: Ring wie im Standard-Design (2 px Grund, dann 2 px Akzent). Die Startseite nutzt bisher den Fokus des
  Browsers – nie `outline: none` ohne Ersatz.
- Live/Status nie nur per Farbe: immer ein Wort dazu (OFFEN, GESTARTET, LIVE, BALD).
- Icons sind `aria-hidden`, der Kacheltitel trägt die Bedeutung. Knöpfe mit nur einem Icon haben `aria-label`.

## Do / Don't

**Do**
- Among Us bleibt die größte Kachel vorne, egal wie viele Spiele dazukommen.
- Jedes neue Spiel: eigene Kachelfarbe, eigenes SVG-Icon mit 3-px-Kontur, eigene kleine Hover-Animation, Etikett.
- Dicke Kontur + harter Schatten für alles Anklickbare; „hochheben“ beim Hover, „eindrücken“ beim Klick.
- Kurze, lockere Sätze („Mit sieben Kommandeuren und ihren Spezialangriffen.“), Meme-Humor in den Spielen ist erwünscht.
- Eigene Spielnamen und eigenes Aussehen (Kritzelkiste statt Skribbl, Geheimfunk statt Codenames).

**Don't**
- Keine weichen, verschwommenen Schatten, kein Glas/Blur, kein Neon-Glühen.
- Keine Verläufe als Seitenhintergrund (Ausnahme: der dunkle Lesestreifen unten in der Among-Us-Kachel).
- Keine Emojis als Icons oder Aufzählungszeichen (Emoji-Reaktionen im Spiel sind Inhalt, kein Icon).
- Kein Nachbau fremder Spiele (Namen, Figuren, Look).
- Nicht das ruhige Among-Us-Design hier verwenden – und umgekehrt keine Sticker-Konturen in `/amongus`.
- Keine Schriften aus Googles Servern laden; keine Inter/Roboto/Arial als Hauptschrift.
