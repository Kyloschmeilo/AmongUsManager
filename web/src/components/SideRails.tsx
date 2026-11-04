// Seitenleisten für breite Bildschirme (03.11.2026, Kilian: „am PC sieht die Seite links und rechts sehr leer aus“).
// Links: Inhaltsverzeichnis der aktuellen Seite (Abschnitte anklickbar, aktiver Abschnitt markiert) an einem Lineal
// wie im HUD. Rechts: die letzten Runden als Funkprotokoll und ein Crewmate aus der letzten Runde, der vorbeitreibt.
// Nur ab 1600 px Breite; darunter wird nichts geladen.
import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { fetchJson, type RoundItem } from '../lib/api';
import { playerColor } from '../lib/colors';
import { ago } from '../lib/format';
import { TEAM_COLOR, TEAM_LABEL } from '../lib/labels';
import { CrewmateSvg } from './CrewDrift';
import { scrollToElement } from './motion';

const WIDE = '(min-width: 1600px)';

function useWide(): boolean {
  const [wide, setWide] = useState(() => typeof window !== 'undefined' && window.matchMedia(WIDE).matches);
  useEffect(() => {
    const mq = window.matchMedia(WIDE);
    const update = () => setWide(mq.matches);
    mq.addEventListener('change', update);
    return () => mq.removeEventListener('change', update);
  }, []);
  return wide;
}

const PAGE_NAMES: [RegExp, string][] = [
  [/^\/$/, 'Übersicht'],
  [/^\/events/, 'Events'],
  [/^\/bestenliste/, 'Bestenliste'],
  [/^\/spieler\//, 'Spieler'],
  [/^\/runden\/./, 'Runde'],
  [/^\/runden/, 'Runden'],
  [/^\/maps/, 'Maps'],
  [/^\/rollen/, 'Rollen'],
  [/^\/hilfe/, 'Hilfe & Mods'],
  [/^\/admin/, 'Admin']
];

interface Heading { id: number; text: string; el: HTMLElement }

/** Abschnitte der Seite (Überschriften `.section__title`), neu bei Seitenwechsel und wenn Inhalt nachlädt. */
function useHeadings(pathname: string): Heading[] {
  const [items, setItems] = useState<Heading[]>([]);
  useEffect(() => {
    const main = document.getElementById('inhalt');
    if (!main) return;
    let timer = 0;
    const read = () => {
      timer = 0;
      const found = Array.from(main.querySelectorAll<HTMLElement>('.section__title'))
        .filter((el) => el.offsetParent !== null && (el.textContent ?? '').trim())
        .slice(0, 12)
        .map((el, id) => ({ id, el, text: (el.textContent ?? '').trim() }));
      setItems((old) =>
        old.length === found.length && old.every((h, i) => h.el === found[i].el && h.text === found[i].text) ? old : found
      );
    };
    const schedule = () => { if (!timer) timer = window.setTimeout(read, 150); };
    read();
    const observer = new MutationObserver(schedule);
    observer.observe(main, { childList: true, subtree: true, characterData: true });
    return () => { observer.disconnect(); window.clearTimeout(timer); };
  }, [pathname]);
  return items;
}

/** Index des Abschnitts, der gerade oben im Bild ist. */
function useActive(headings: Heading[]): number {
  const [active, setActive] = useState(-1);
  useEffect(() => {
    let frame = 0;
    const update = () => {
      frame = 0;
      const line = window.innerHeight * 0.35;
      let current = -1;
      headings.forEach((h, i) => { if (h.el.getBoundingClientRect().top < line) current = i; });
      setActive(current);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => { window.removeEventListener('scroll', onScroll); window.removeEventListener('resize', onScroll); cancelAnimationFrame(frame); };
  }, [headings]);
  return active;
}

function PageIndex() {
  const { pathname } = useLocation();
  const headings = useHeadings(pathname);
  const active = useActive(headings);
  const page = PAGE_NAMES.find(([re]) => re.test(pathname))?.[1] ?? 'Crew Stats';
  return (
    <aside className="rail rail--left" aria-label="Auf dieser Seite">
      <div className="rail__head">
        <span className="rail__eyebrow">Deck</span>
        <span className="rail__title">{page}</span>
      </div>
      {headings.length >= 2 ? (
        <ol className="rail__index">
          {headings.map((h, i) => (
            <li key={`${h.id}-${h.text}`} className={i === active ? 'is-active' : i < active ? 'is-past' : undefined}>
              <button type="button" onClick={() => scrollToElement(h.el.closest('section') ?? h.el)}>
                <span className="rail__num mono">{String(i + 1).padStart(2, '0')}</span>
                <span className="rail__label">{h.text}</span>
              </button>
            </li>
          ))}
        </ol>
      ) : (
        <div className="rail__ruler" aria-hidden="true" />
      )}
    </aside>
  );
}

function RadioLog() {
  const [rounds, setRounds] = useState<RoundItem[] | null>(null);
  const [pick, setPick] = useState(0);
  useEffect(() => {
    let active = true;
    fetchJson<{ total: number; items: RoundItem[] }>('rounds?offset=0&limit=6').then(
      (r) => {
        if (!active) return;
        setRounds(r.items);
        setPick(Math.floor(Math.random() * Math.max(1, r.items[0]?.roster.length ?? 1)));
      },
      () => active && setRounds([])
    );
    return () => { active = false; };
  }, []);
  if (!rounds || !rounds.length) return null;
  // Ein Crewmate aus der letzten Runde treibt unten durchs Bild – jeden Besuch ein anderer.
  const latest = rounds[0];
  const drifter = latest.roster[pick] ?? latest.roster[0] ?? null;
  return (
    <aside className="rail rail--right" aria-label="Zuletzt gespielt">
      <div className="rail__head">
        <span className="rail__eyebrow">Funk</span>
        <span className="rail__title">Zuletzt gespielt</span>
      </div>
      <ol className="rail__log">
        {rounds.map((r) => (
          <li key={r.gameId} style={{ ['--team' as string]: TEAM_COLOR[r.winningTeam] }}>
            <Link to={`/runden/${r.gameId}`}>
              <span className="rail__map">{r.map}</span>
              <span className="rail__meta">
                <b>{TEAM_LABEL[r.winningTeam]}</b> · {ago(r.endedAt)}
              </span>
            </Link>
          </li>
        ))}
      </ol>
      <Link to="/runden" className="rail__more">Alle Runden</Link>
      {drifter && (
        <div className="rail__drifter" aria-hidden="true">
          <CrewmateSvg color={playerColor(drifter.colorId, drifter.key)} />
        </div>
      )}
    </aside>
  );
}

export function SideRails() {
  const wide = useWide();
  if (!wide) return null;
  return (
    <>
      <PageIndex />
      <RadioLog />
    </>
  );
}
