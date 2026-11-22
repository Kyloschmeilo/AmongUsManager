# Design-System „CrewStats“ – Among-Us-Teil der Website

Gilt für alles unter **spieleabend.timonso.de/amongus** (Stats-Website: Übersicht, Bestenliste, Profile, Runden + Replay,
Maps, Rollen, Balancing, Vergleich, Live-Lobbys, Events, Admin, Hilfe & Mods). Die Spiele-Startseite und die Browserspiele
haben ein eigenes System: [spiele-website.md](spiele-website.md).

Stand: 22.11.2026, ausgelesen aus `web/src/styles.css` (inkl. Nachträgen am Ende), den Seiten-CSS-Dateien in `web/src/`
(`badges.css`, `lobbies.css`, `rails.css` …) und dem hellen Theme aus PR #34 (`web/src/theme.css`, `web/src/lib/theme.tsx`).

## Die Idee in einem Satz

Ein **Spieltag-Almanach** statt Dashboard: dunkles Blau wie das All um die Skeld, warmes Papierweiß für Text, Zahlen in
schmaler Schrift wie auf einer Anzeigetafel, Abschnitte durch kräftige Linien getrennt statt durch Karten. **Farbe nur mit
Bedeutung** – Visier-Blau ist die Crew, Rot der Impostor, Violett die Neutralen, Gold Platz 1.

Herkunft: Am 02.11.2026 hat Kilian die Website vom Standard-Design gelöst („ignoriere mal das Design system ein bisschen
und mach, dass die Website nicht komplett vibe-coded aussieht“). Vom Standard-Design („CastCrafter“) geblieben sind die
Token-Namen (`surface-*`, `ink-*`, `accent`, `gold`, `ember` …), das 4-px-Raster, der Fokus-Ring und die Verbote
(keine Glas-Flächen, kein Glühen, keine Emojis als Icons). Bot-Embeds und Artifacts bleiben beim Standard-Design.

## Farben

| Token | Dunkel (Standard) | Hell | Bedeutung / Wofür |
| --- | --- | --- | --- |
| `--surface-0` | `#0f1117` | `#f4f1ea` | Seitengrund (das All) |
| `--surface-1` | `#161922` | `#fbfaf6` | Kästen (`.panel--box`), Menüs, Suchfeld |
| `--surface-2` | `#1f2330` | `#ebe7dc` | Hover, gedrückte Zustände |
| `--border` | `#272c39` | `#ddd8cb` | Feine 1-px-Linien zwischen Zeilen, Rahmen von Kästen |
| `--border-control` | `#4a5164` | `#a8a292` | Rahmen von Knöpfen, Eingaben, Chips (≥ 3 : 1) |
| `--rule` | `#3a4052` | `#cdc6b6` | Trennlinie über Panels, unter Tabs und Kopfzeile |
| `--rule-strong` | `#d9d5cb` | `#2a2d36` | Die kräftige 3-px-Linie über jedem Abschnitt |
| `--ink` | `#eeebe3` | `#15171e` | Überschriften, Namen, wichtige Zahlen |
| `--ink-muted` | `#a9adb9` | `#474b57` | Fließtext, Nav |
| `--ink-faint` | `#858b9e` | `#6a6f7d` | Etiketten, Bildunterschriften, Ränge |
| `--accent` | `#7cc6e4` | `#1c7499` | **Visier-Blau = Crew** und Links, Fokus |
| `--accent-hover` | `#a6dbef` | `#12577a` | Hover von Links |
| `--on-accent` | `#071820` | `#ffffff` | Text auf Visier-Blau |
| `--accent-soft` | `#172c36` | `#dcecf3` | Grund von Crew-Pills |
| `--moss` | `#2b5262` | `#a8cfdc` | Ruhige Balkenfüllung, nie Text |
| `--ember` | `#f2584c` | `#c9362b` | **Impostor**, Kills, Gefahr; Farbstück auf Abschnittslinien |
| `--on-ember` | `#1d0402` | `#ffffff` | Text auf Rot |
| `--neutral` | `#c493ec` | `#8549bb` | **Neutrale Rollen** (Jester & Co., Sondersiege) |
| `--gold` | `#f2b52c` | `#a26f00` | **Platz 1**, Rekorde, Warnhinweise |
| `--on-gold` | `#1a1200` | `#ffffff` | Text auf Gold |
| `--sky` | `#8fa7d6` | `#4762a0` | Reine Info-Hinweise |

Medaillen (`--medal`, 3-px-Streifen links): Platz 1 `--gold`, Platz 2 Silber `#c9ccd3` (hell `#7d828c`), Platz 3 Bronze
`#c98a52` (hell `#a5652b`).
Pill-Gründe dunkel: Gold `#2a2210`, Rot `#2e1517`, Violett `#261c33`, Sky `#1b2132`.

**Spielerfarben** (Avatare, Crewmates, Replay) kommen aus den 18 Among-Us-Farben + 24 TOR-Farben (`web/src/lib/colors.ts`) –
sie sind Daten, keine Design-Farben, und dürfen nirgends sonst als Akzent auftauchen.

## Schrift

| Rolle | Token | Schrift | Wo |
| --- | --- | --- | --- |
| Display | `--font-display` | **Barlow Condensed** 500/600/700 | Überschriften, Zahlen, Etiketten, Knöpfe, Pills |
| Text | `--font-sans` | **Barlow** 400/500/600 (+ 400 kursiv) | Fließtext, Nav, Tabs, Namen |
| Zahlen | `--font-mono` | Barlow mit `tabular-nums` | Tabellenwerte (feste Breite, keine echte Mono) |
| Code | `--font-code` | `ui-monospace`, Cascadia Mono, Consolas | Nur URLs/Webhooks/Codes |

Selbst gehostet über `@fontsource`. Text-Stile:

| Stil | Größe / Zeile | Schnitt |
| --- | --- | --- |
| `masthead__title` (Startseite) | 76 / 68 px (Handy 56 / 52), GROSS | Condensed 700 |
| `page-title` | 56 / 56 px | Condensed 700 |
| `section__title` | 34 / 38 px, −.01em | Condensed 600 |
| `panel__title`, `lb-card__title` | 19 / 24 px | Condensed 600 |
| `stat__value` | 44 / 48 px (Podest Platz 1: 46 / 50) | Condensed 600 |
| `lead` | 19 / 29 px, max. 620 px breit | Barlow 400 |
| Fließtext | 16 / 25 px | Barlow 400 |
| `eyebrow`, `stat__label` | 13–15 px, GROSS, Laufweite .04–.05em | Condensed 600 |
| `caption` | 13 / 18 px | Barlow 400 |

Zahlen mit deutschem Trenner (1.234), Dauern als „12 min“, „3 h“.

## Abstände, Breiten, Radien

- **Raster:** `--space-1` 4 · `-2` 8 · `-3` 12 · `-4` 16 · `-5` 20 · `-6` 24 · `-8` 32 · `-12` 48 · `-20` 72 px
  (Handy 48 px). Zwischen Seitenabschnitten `--space-20`, im Abschnitt `--space-5`, Kästen-Lücke `--space-4`.
- **Breite:** `--content` 1200 px + `--gutter` 32 px (Handy 16 px). Breite Bildschirme: ab 1760/2000/2400 px wächst der Inhalt
  auf 1320/1440/1600 px; ab 1600 px zusätzlich feste **Seitenleisten** (`rails.css`: links Inhaltsverzeichnis am HUD-Lineal,
  rechts „Zuletzt gespielt“).
- **Radien** (seit „Moderner Schliff“): `--radius-sm` 4 · `-md` 6 · `-lg` 10 · `-pill` 4 px. Pills sind also eckige
  Etiketten, keine runden Pillen.
- **Schatten:** keine – außer `--shadow-raise` (`0 10px 30px #00000080`) für Menüs/Overlays und `--shadow-focus`.

## Bausteine

### Kopfzeile (`.topbar`)
Klebt oben, 60 px hoch, Grund `#0c0e14` (hell `#efebe2`), unten 1-px-Linie. Links die Marke: kleiner Crewmate +
„CREW**STATS**“ (Condensed 700, 22 px, GROSS, „STATS“ in `--ember`). Daneben die Nav (Barlow 500, 16 px, `--ink-muted`,
aktiv `--ink`) mit einem **Strich, der zum aktiven Punkt rutscht** (`.nav__marker`, 3 px, 350 ms). Rechts: „Alle Spiele“
(Link zur Startseite), Hell/Dunkel-Knopf (34 × 34, Rahmen, Sonne/Mond), Konto (Discord-Avatar + Name, Menü).
Darunter ein 2-px-**Scroll-Fortschritt** (Visier-Blau → Rot). Unter 860 px: Marke + Konto oben, Nav als scrollende zweite Zeile.

### Abschnitt (`.section`)
3-px-Linie in `--rule-strong` oben, darauf links ein 56-px-Stück in `--ember`. Darunter Titel links, kleiner Zusatz
(`section__kicker`) daneben, Link „Alle →“ rechts.

### Panels und Kästen
- `.panel` – nur eine 1-px-Linie oben, kein Hintergrund (Standard für Inhalte).
- `.panel--box` / Spalten-Kästen in `.grid--2/3` – ringsum 1-px-Rahmen, 10-px-Ecken, feine Lichtkante oben
  (`inset 0 1px 0 #ffffff0a`); Hover ändert nur den Rahmen.
- `.rounds` – Tabelle/Rundenliste in einem Rahmen; jede Zeile hat links einen 3-px-**Streifen in der Siegerteam-Farbe**.

### Kennzahl (`.stat`)
Große Zahl (44 px Condensed) zuerst, darunter Etikett in Großbuchstaben, darunter Bildunterschrift. Kein Kasten.
In Bilanz-Reihen (`grid--tally`) eine 3-px-Linie in der Team-Farbe obendrüber. Zahlen zählen beim Erscheinen hoch (CountUp).

### Knöpfe (`.btn`)
Condensed 600, 16 px, GROSS, Laufweite .03em, 8 × 14 px Innenabstand, Rahmen `--border-control`, transparent.
- Hover: `--surface-2`.
- `--primary`: gefüllt in `--ink`, Text `--surface-0`; Hover Weiß (hell: Schwarz mit weißer Schrift).
- `--ghost`: ohne Rahmen. `--icon`: 40 × 40.
- Deaktiviert: `--ink-faint` auf `--surface-1`, Rahmen `--border`.

### Tabs (`.tabs`, `.tab`)
Reihe über einer 1-px-Linie, Barlow 500 15 px. Aktiv: Text `--ink` + **3-px-Strich unten in `--ink`**. Gruppenname als
`tabs__group` (Condensed GROSS, `--ink-faint`). Bestenliste-Bereiche: aktiver Bereich mit Strich in `--accent`.

### Umschalter (`.segmented`)
Kleine gerahmte Knöpfe; gewählt = gefüllt `--ink` mit Text `--surface-0`.

### Pills und Chips
- `.pill` – Etikett: 3-px-Farbstreifen links + getönter Grund + Text in der Farbe (Condensed 600 14 px GROSS).
  Varianten `--gold`, `--ember`, `--neutral`, `--sky`, `--plain`, `--mono` (Zahlen), `--case` (normale Schreibung).
- `.chip` – dünner Rahmen, Barlow 13 px, `--ink-muted`.

### Avatar
Kleiner **Crewmate-Umriss** in der Spielfarbe (runder Kopf, Visier), Größen sm 17 × 20, normal, lg 62 × 72, xl 82 × 96;
tot = grau. Mit Discord-Bild rund (`avatar--img`). Profile/Treibende Crewmates zeigen Hut/Visier/Skin/Haustier
(`/api/crewmate`).

### Bestenliste
`.board` (Liste: Rang, Avatar, Name, Wert; Platz 1 Wert in Gold; Platz 1–3 mit Gold/Silber/Bronze-Streifen),
`.lb-card` (Top 3 je Kennzahl, gewählt = Rahmen + 3-px-Innenlinie in `--accent`), `.podium` (Platz 2 · 1 · 3,
Platz 1 höher, Grund `#1c1a14`, 3-px-Goldlinie oben, eigener Platz mit Akzent-Rahmen).

### Karten und Replay
Kopf-Karte mit **HUD-Ecken** (oben Visier-Blau, unten Rot, ziehen sich beim Hover zusammen) und **Sichtfeld** um den
Zeiger (`Vision`, wie Licht-Sabotage). Heatmaps per Gauß-Raster, Replay als GSAP-Zeitleiste.

### Hinweise (`.note`)
Linker Streifen + Icon, `--note--warn` in Gold.

### Crew-Band („N an Bord“)
Alle Spieler als im All treibende Crewmates (`CrewDrift.tsx`) – **bleibt auch im hellen Theme dunkel** (das All).

## Bewegung

Alles Verspielte nur unter `prefers-reduced-motion: no-preference`; „weniger Bewegung“ zeigt alles sofort und still.

- Zustandswechsel: 120–150 ms `ease-out` auf Farbe, Rahmen, Hintergrund.
- Weiches Scrollen mit **Lenis**; Seitenwechsel blenden ein (`route-in`, 350 ms, 10 px von unten).
- **CountUp** für Kennzahlen, **wachsende Balken** beim Hineinscrollen, Replay-Zeitleiste (GSAP + ScrollTrigger).
- Reaktive Kleinigkeiten, **jede Stelle anders**: Nav-Strich rutscht, Spielstand hebt beim Hover den Team-Anteil hervor,
  in der Rundenliste springen die Impostor hoch, Ranglisten-Crewmates hüpfen, Podest wackelt, Rekordwert rückt nach rechts,
  Kartenkästen heben sich 3 px, Sterne folgen dem Zeiger.
- **Keine** Buchstaben- oder Einblend-Animationen für Überschriften (`Title`/`Reveal` sind bewusst schlicht).

## Hell / Dunkel (PR #34)

- Gleicher Schalter und Schlüssel wie auf der Startseite: `localStorage` `spiele.thema`, `data-theme="light"` am `<html>`,
  Standard Dunkel, wird vor dem ersten Rendern gesetzt.
- Hell = **warmes Papier** statt All, gleiche Idee (Almanach, Farbe nur mit Bedeutung). Alle Team-Farben sind dunkler
  gewählt, damit sie auf Papier ≥ 4,5 : 1 erreichen. `--rule-strong` kippt auf Dunkel.
- Der Sternenhimmel hinter der Seite wird ausgeblendet; das Crew-Band bleibt dunkel.
- Neue Farben immer in **beiden** Themes festlegen: Dunkel in `styles.css` (`:root`), Hell in `theme.css`
  (`html[data-theme='light']`).

## Datenschutz im Design

Friendcodes, PUIDs und Discord-IDs werden nie angezeigt; Spieler erscheinen mit Anzeigename, Spielfarbe und Profilbild
der Website (`/api/avatar/…`), nie direkt von Discord geladen.

## Do / Don't

**Do**
- Farbe nur, wenn sie etwas bedeutet: Crew/Links = Visier-Blau, Impostor = Rot, Neutral = Violett, Platz 1 = Gold.
- Abschnitte mit Linien gliedern; Kästen nur für echte Gruppen (Spalten, Karten, Menüs).
- Zahlen groß in Barlow Condensed, Etiketten in Großbuchstaben, Namen in `--ink`.
- Schlichte Überschriften („Bestenliste“, „Letzte Runden“) statt Fragen oder Floskeln.
- Spieler immer als Crewmate in ihrer meistgenutzten Farbe (eine einzelne Runde: die Farbe dieser Runde).
- Neue Reaktions-Animationen jeweils eigen gestalten, nie derselbe Hover überall.

**Don't**
- Keine Lila-Verläufe, kein Glas/Blur, kein Glühen, keine weichen Schatten an Karten.
- Keine Emojis als Icons, keine Floskeln („Willkommen in deinem Dashboard!“).
- Keine dicken Sticker-Konturen oder harten Versatz-Schatten – das ist der Look der Spiele-Startseite.
- Keine Spielerfarben als Deko-Akzent; kein Rot für etwas anderes als Impostor/Gefahr.
- Keine Schriften von Google laden; keine echte Monospace für Zahlen (nur für Codes).
