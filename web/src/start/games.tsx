// Spiele der Startseite. Die Browserspiele sind eigene Dateien im Ordner seiten/ neben dem StatsWebsite.jar
// (/seiten/<datei>); Titel und Beschreibung kommen, wenn vorhanden, aus der Seite selbst (/api/pages).
// Fehlt eine Datei noch, zeigt die Kachel „Bald“. Neue Spiele hier anhängen.
import type { ReactNode } from 'react';

export interface Game {
  /** Datei in seiten/ (oder null bei eigenen Adressen wie CoreHold). */
  file: string | null;
  href: string;
  title: string;
  text: string;
  tag: string;
  /** Kachelfarbe und Schrift-/Akzentfarbe darauf. */
  color: string;
  ink: string;
  accent: string;
  icon: ReactNode;
}

const stroke = { stroke: '#120f24', strokeWidth: 3, strokeLinejoin: 'round' as const, strokeLinecap: 'round' as const };

export const GAMES: Game[] = [
  {
    file: 'schiffe.html', href: '/seiten/schiffe.html', title: 'Schiffe versenken', tag: 'Strategie',
    text: 'Mit sieben Kommandeuren und ihren Spezialangriffen.',
    color: '#2f7cf6', ink: '#ffffff', accent: '#ffd23f',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <path d="M8 38h48l-7 12H15z" fill="#ffd23f" {...stroke} />
        <path d="M30 12v26M30 14l14 14H30" fill="#fff" {...stroke} />
        <path className="g-wave" d="M4 56c6 0 6-4 12-4s6 4 12 4 6-4 12-4 6 4 12 4 6-4 8-4" fill="none" {...stroke} stroke="#fff" />
      </svg>
    ),
  },
  {
    file: 'skribbl.html', href: '/seiten/skribbl.html', title: 'Zeichnen & Raten', tag: 'Party',
    text: 'Einer malt, alle raten – je schneller, desto mehr Punkte.',
    color: '#ffc928', ink: '#120f24', accent: '#ff4f81',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <path className="g-scribble" d="M8 48c8-14 14 6 22-8s12 4 18-6" fill="none" {...stroke} stroke="#ff4f81" strokeWidth={5} />
        <path d="M40 10l14 14-24 24H16V34z" fill="#fff" {...stroke} />
        <path d="M34 16l14 14" fill="none" {...stroke} />
      </svg>
    ),
  },
  {
    file: 'gartic.html', href: '/seiten/gartic.html', title: 'Stille Post', tag: 'Party',
    text: 'Satz, Bild, Satz, Bild – was kommt am Ende raus?',
    color: '#ff6fae', ink: '#120f24', accent: '#3ad0ff',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <rect x="6" y="10" width="30" height="22" rx="8" fill="#fff" {...stroke} />
        <path d="M14 32l-4 8 10-8" fill="#fff" {...stroke} />
        <rect x="28" y="30" width="30" height="22" rx="8" fill="#3ad0ff" {...stroke} />
        <path d="M50 52l4 8-10-8" fill="#3ad0ff" {...stroke} />
        <circle cx="16" cy="21" r="2.5" fill="#120f24" /><circle cx="22" cy="21" r="2.5" fill="#120f24" /><circle cx="28" cy="21" r="2.5" fill="#120f24" />
      </svg>
    ),
  },
  {
    file: 'codenames.html', href: '/seiten/codenames.html', title: 'Geheime Wörter', tag: 'Teams',
    text: 'Zwei Teams, ein Hinweis, viele Wörter – rot gegen blau.',
    color: '#ef3b3b', ink: '#ffffff', accent: '#2f6cf6',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        {[0, 1, 2].map((r) => [0, 1, 2].map((c) => (
          <rect key={`${r}${c}`} className="g-cell" x={8 + c * 17} y={8 + r * 17} width="14" height="14" rx="3"
            fill={(r * 3 + c) % 3 === 0 ? '#2f6cf6' : (r + c) % 2 ? '#fff5e0' : '#ffb3b3'} {...stroke} strokeWidth={2.5} />
        )))}
      </svg>
    ),
  },
  {
    file: 'agar.html', href: '/seiten/agar.html', title: 'Zellen-Arena', tag: 'Arena',
    text: 'Fressen, wachsen, teilen – und bloß nicht gefressen werden.',
    color: '#2fd27a', ink: '#120f24', accent: '#ff7a1a',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <circle className="g-blob" cx="28" cy="32" r="18" fill="#ff7a1a" {...stroke} />
        <circle cx="50" cy="16" r="7" fill="#fff" {...stroke} />
        <circle cx="52" cy="46" r="5" fill="#ffd23f" {...stroke} />
        <circle cx="10" cy="54" r="3" fill="#fff" {...stroke} strokeWidth={2} />
      </svg>
    ),
  },
  {
    file: 'slither.html', href: '/seiten/slither.html', title: 'Schlangen', tag: 'Arena',
    text: 'Leuchtpunkte sammeln, lang werden, die anderen ausbremsen.',
    color: '#8b5cff', ink: '#ffffff', accent: '#b8ff3a',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        {[[10, 50], [18, 44], [24, 36], [30, 28], [38, 24], [46, 20]].map(([x, y], i) => (
          <circle key={i} className="g-seg" style={{ animationDelay: `${i * 0.08}s` }} cx={x} cy={y} r={6 + i * 0.4} fill="#b8ff3a" {...stroke} strokeWidth={2.5} />
        ))}
        <circle cx="52" cy="14" r="8" fill="#b8ff3a" {...stroke} />
        <circle cx="54" cy="12" r="2.2" fill="#120f24" />
      </svg>
    ),
  },
  {
    file: 'diep.html', href: '/seiten/diep.html', title: 'Panzer-Arena', tag: 'Arena',
    text: 'Formen abschießen, aufleveln, den eigenen Panzer ausbauen.',
    color: '#16b9e8', ink: '#120f24', accent: '#ff4f5e',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <g className="g-turret"><rect x="30" y="24" width="28" height="14" rx="2" fill="#c9d2da" {...stroke} /></g>
        <circle cx="28" cy="31" r="16" fill="#ff4f5e" {...stroke} />
        <path d="M8 56l6-10 6 10z" fill="#ffd23f" {...stroke} strokeWidth={2.5} />
      </svg>
    ),
  },
  {
    file: 'schach.html', href: '/seiten/schach.html', title: 'Schach-Analyse', tag: 'Werkzeug',
    text: 'Eigene chess.com-Partien mit Stockfish auswerten.',
    color: '#f4e3c1', ink: '#120f24', accent: '#6b4a2b',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <path d="M20 54h26l-3-8H23zM24 46c0-10 4-14 4-20-5 0-8-3-8-7 0-6 8-11 16-11 8 0 12 8 12 18v20" fill="#6b4a2b" {...stroke} />
        <circle cx="32" cy="16" r="2.5" fill="#f4e3c1" />
      </svg>
    ),
  },
  {
    file: null, href: '/corehold/', title: 'CoreHold', tag: 'Minecraft',
    text: 'Teams, PvP-Tierliste und alle Special Items mit Rezept.',
    color: '#5cb85c', ink: '#120f24', accent: '#8a5a2b',
    icon: (
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <path d="M32 6l24 12v28L32 58 8 46V18z" fill="#8a5a2b" {...stroke} />
        <path d="M8 18l24 12 24-12L32 6z" fill="#6fd16f" {...stroke} />
        <path d="M32 30v28" fill="none" {...stroke} />
        <path d="M8 18v8l6 3v-6l6 3v6l6 3v-6l6 3" fill="#6fd16f" {...stroke} strokeWidth={2.5} />
      </svg>
    ),
  },
];
