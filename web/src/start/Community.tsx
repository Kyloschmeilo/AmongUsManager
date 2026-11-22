// Community-Abschnitt der Startseite (22.11.2026, Kilian: nur Zeichnungen, Memes und Changelog, klein auf der Startseite):
// neueste Memes der Meme-Schmiede, beliebteste Zeichnungen der Kritzelkiste, Changelog des Bots. Daten von /api/community.
// Großansicht blättert durch die ganze Galerie (lädt weitere Seiten nach); Herzen nur angemeldet, Admins blenden aus/löschen.
// Aussehen nach docs/design/spiele-website.md: jede Galerie ist ein Sticker in der Farbe ihres Spiels (mit dessen Icon),
// dicke Kontur + harter Schatten, hochheben beim Hover, eindrücken beim Klick, keine Emojis als Icons.
import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import gsap from 'gsap';
import { GAMES } from './games';
import { whenEntered } from './entered';

interface Me { loginEnabled: boolean; loggedIn: boolean; admin: boolean; name: string | null }
interface Item {
  id: number; kind: 'meme' | 'drawing'; title: string; author: string; info: string; at: string;
  w: number; h: number; likes: number; liked: boolean; src: string; hidden?: boolean | null;
}
interface Overview {
  me: Me;
  counts: { memes: number; drawings: number };
  latest: { memes: Item[]; drawings: Item[] };
  changelog: { version: string; sections: { title: string; lines: string[] }[] };
}
interface GalleryPage { items: Item[]; more: boolean; total: number }
type Kind = 'memes' | 'zeichnungen';

const API = '/api/community';

async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(path, { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
  if (!r.ok) throw new Error(String(r.status));
  return (await r.json()) as T;
}

async function post<T = Record<string, unknown>>(path: string, body: unknown): Promise<T> {
  const r = await fetch(path, {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body),
  });
  const data = (await r.json().catch(() => ({}))) as T & { error?: string };
  if (!r.ok) throw new Error(data.error || `Fehler ${r.status}`);
  return data;
}

function ago(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 90) return 'gerade eben';
  if (s < 3600) return `vor ${Math.round(s / 60)} min`;
  if (s < 86400) return `vor ${Math.round(s / 3600)} h`;
  const d = Math.round(s / 86400);
  return d < 14 ? `vor ${d} ${d === 1 ? 'Tag' : 'Tagen'}` : new Date(iso).toLocaleDateString('de-DE');
}

/** Changelog-Zeile mit **fett** und /befehl – ohne HTML aus den Daten. */
function Rich({ text }: { text: string }) {
  const parts: ReactNode[] = [];
  text.split(/(\*\*[^*]+\*\*|\/[a-z][a-z0-9-]*)/g).forEach((part, i) => {
    if (!part) return;
    if (part.startsWith('**') && part.endsWith('**')) parts.push(<strong key={i}>{part.slice(2, -2)}</strong>);
    else if (/^\/[a-z][a-z0-9-]*$/.test(part)) parts.push(<code key={i}>{part}</code>);
    else parts.push(part);
  });
  return <>{parts}</>;
}

const LOGIN = `/auth/login?back=${encodeURIComponent('/#community')}`;

const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

function Heart({ on }: { on?: boolean }) {
  return (
    <svg className="st-cm-heart" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 20.5s-7.5-4.6-9.2-9.4C1.6 7.6 3.8 4.5 7 4.5c2 0 3.5 1.1 5 3 1.5-1.9 3-3 5-3 3.2 0 5.4 3.1 4.2 6.6-1.7 4.8-9.2 9.4-9.2 9.4z"
        fill={on ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.4" strokeLinejoin="round" />
    </svg>
  );
}

function Chevron({ dir }: { dir: 'left' | 'right' }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={dir === 'left' ? 'M15 5l-7 7 7 7' : 'M9 5l7 7-7 7'} fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function Cross() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" />
    </svg>
  );
}

/** Farbe und Icon des Spiels, aus dem eine Galerie kommt (wie seine Kachel). */
function gameOf(href: string) {
  return GAMES.find((g) => g.href === href);
}

export function Community() {
  const [data, setData] = useState<Overview | null>(null);
  const [open, setOpen] = useState<{ kind: Kind; index: number } | null>(null);
  const root = useRef<HTMLElement>(null);
  const reload = useCallback(() => getJson<Overview>(API).then(setData, () => {}), []);
  useEffect(() => { reload(); }, [reload]);

  // Einmal hereinspringen, sobald Daten da sind und der Einflug der Seite vorbei ist (wie alles, was später erscheint)
  const shown = data != null;
  useEffect(() => {
    if (!shown || reduced()) return;
    let tween: gsap.core.Timeline | null = null;
    let gone = false;
    whenEntered.then(() => {
      const el = root.current;
      if (gone || !el) return;
      tween = gsap.timeline()
        .from(el.querySelector('.st-cm__label'), { x: -24, opacity: 0, duration: 0.5, ease: 'power3.out', clearProps: 'transform,opacity' }, 0)
        .from(el.querySelectorAll('.st-cm__card'), {
          y: 40, rotate: () => gsap.utils.random(-5, 5), opacity: 0, duration: 0.6, ease: 'back.out(1.7)', stagger: 0.08, clearProps: 'transform,opacity',
        }, 0.05);
    });
    return () => { gone = true; tween?.kill(); };
  }, [shown]);
  if (!data) return null;

  const me = data.me;
  const strips: { kind: Kind; title: string; from: string; items: Item[]; total: number; empty: string; href: string }[] = [
    { kind: 'memes', title: 'Memes', from: 'Meme-Schmiede', items: data.latest.memes, total: data.counts.memes, empty: 'Die besten Memes jeder Partie landen hier.', href: '/seiten/memes.html' },
    { kind: 'zeichnungen', title: 'Zeichnungen', from: 'Kritzelkiste', items: data.latest.drawings, total: data.counts.drawings, empty: 'Gefeierte Bilder jeder Partie landen hier.', href: '/seiten/kritzeln.html' },
  ];
  return (
    <section className="st-cm" id="community" aria-label="Community" ref={root}>
      <div className="st-cm__label">
        <b>Community</b>
        <span className="st-cm__hint">Das Beste aus euren Partien</span>
        <span className="st-cm__extra">
          {me.admin && <span className="st-tag st-tag--admin">Admin</span>}
          {!me.loggedIn && me.loginEnabled && <a className="st-chip" href={LOGIN}>Anmelden für Herzen</a>}
        </span>
      </div>
      <div className="st-cm__cols">
        {strips.map((s) => {
          const game = gameOf(s.href);
          const style = { '--c': game?.color ?? '#fff7ea', '--ink': game?.ink ?? '#120f24', '--acc': game?.accent ?? '#ffd23f' } as CSSProperties;
          return (
            <div className="st-cm__card" key={s.kind} style={style}>
              {game && <span className="st-cm__icon">{game.icon}</span>}
              <h2 className="st-cm__title">{s.title}</h2>
              <p className="st-cm__from">
                aus der <a href={s.href}>{s.from}</a>
                {s.total > 0 && <span className="st-tag">{s.total}</span>}
              </p>
              {s.items.length === 0 ? (
                <p className="st-cm__empty">{s.empty}</p>
              ) : (
                <div className="st-cm__thumbs">
                  {s.items.map((item, i) => (
                    <button key={item.id} className={'st-cm__thumb' + (item.hidden ? ' is-hidden' : '')} onClick={() => setOpen({ kind: s.kind, index: i })}
                      title={`${item.title} – ${item.author}`} aria-label={`${item.kind === 'drawing' ? 'Zeichnung' : 'Meme'}: ${item.title}, von ${item.author}`}>
                      <img src={item.src} alt="" loading="lazy" width={item.w} height={item.h} />
                      {item.likes > 0 && <span className="st-cm__likes"><Heart on />{item.likes}</span>}
                    </button>
                  ))}
                </div>
              )}
            </div>
          );
        })}
        <Changelog log={data.changelog} />
      </div>
      {open && <Viewer kind={open.kind} start={open.index} me={me} first={open.kind === 'memes' ? data.latest.memes : data.latest.drawings}
        onClose={() => { setOpen(null); reload(); }} />}
    </section>
  );
}

function Changelog({ log }: { log: Overview['changelog'] }) {
  const [all, setAll] = useState(false);
  // Abschnittstitel ohne vorangestelltes Emoji (Discord-Embed) – auf der Seite sind Emojis keine Icons
  const lines = log.sections.flatMap((s) => s.lines.map((line) => ({ section: s.title.replace(/^[^\p{L}\p{N}]+/u, ''), line })));
  const shown = all ? lines : lines.slice(0, 3);
  return (
    <div className={'st-cm__card st-cm__log' + (all ? '' : ' is-short')}>
      <span className="st-cm__icon" aria-hidden="true">
        <svg viewBox="0 0 64 64">
          <rect className="g-note" x="12" y="8" width="40" height="48" rx="6" fill="#fff" stroke="#120f24" strokeWidth="3" strokeLinejoin="round" />
          <path d="M20 22h24M20 32h24M20 42h14" fill="none" stroke="#120f24" strokeWidth="3" strokeLinecap="round" />
          <circle cx="48" cy="12" r="8" fill="#2fd27a" stroke="#120f24" strokeWidth="3" />
        </svg>
      </span>
      <h2 className="st-cm__title">Changelog</h2>
      <p className="st-cm__from">Discord-Bot <span className="st-tag">v{log.version}</span></p>
      <ul>
        {shown.map((l, i) => (
          <li key={i}>{(i === 0 || shown[i - 1].section !== l.section) && l.section && <em>{l.section}</em>}<Rich text={l.line} /></li>
        ))}
      </ul>
      {lines.length > 3 && <button className="st-chip st-cm__more" onClick={() => setAll(!all)} aria-expanded={all}>{all ? 'Weniger' : `Alles zeigen (${lines.length})`}</button>}
    </div>
  );
}

/** Großansicht: blättert durch die ganze Galerie dieser Art, lädt weitere Seiten nach. */
function Viewer({ kind, start, first, me, onClose }: { kind: Kind; start: number; first: Item[]; me: Me; onClose: () => void }) {
  const [items, setItems] = useState<Item[]>(first);
  const [more, setMore] = useState(true);
  const [index, setIndex] = useState(start);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const loading = useRef(false);
  const dialog = useRef<HTMLDivElement>(null);

  const loadMore = useCallback(() => {
    if (loading.current || !more) return;
    loading.current = true;
    getJson<GalleryPage>(`${API}/galerie?art=${kind}&offset=${items.length}`)
      .then((p) => { setItems((list) => [...list, ...p.items.filter((n) => !list.some((o) => o.id === n.id))]); setMore(p.more); })
      .catch(() => setMore(false))
      .finally(() => { loading.current = false; });
  }, [kind, items.length, more]);

  useEffect(() => { if (index >= items.length - 2) loadMore(); }, [index, items.length, loadMore]);
  useEffect(() => { setError(null); setConfirm(false); }, [index]);
  const go = useCallback((step: number) => setIndex((i) => Math.min(Math.max(0, i + step), items.length - 1)), [items.length]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowRight') go(1);
      else if (e.key === 'ArrowLeft') go(-1);
    };
    window.addEventListener('keydown', key);
    dialog.current?.focus();
    return () => window.removeEventListener('keydown', key);
  }, [go, onClose]);

  const item = items[index];
  if (!item) return null;
  const change = (c: Partial<Item> | null) => {
    if (c === null) {
      setItems((list) => list.filter((i) => i.id !== item.id));
      setIndex((i) => Math.max(0, Math.min(i, items.length - 2)));
      if (items.length <= 1) onClose();
    } else {
      setItems((list) => list.map((i) => (i.id === item.id ? { ...i, ...c } : i)));
    }
  };
  const heart = () => {
    if (!me.loggedIn) { if (me.loginEnabled) window.location.href = LOGIN; return; }
    const on = !item.liked;
    change({ liked: on, likes: item.likes + (on ? 1 : -1) });
    post<{ likes: number; liked: boolean }>(`${API}/herz`, { id: item.id, on })
      .then((r) => change({ likes: r.likes, liked: r.liked }), (e: Error) => { change({ liked: item.liked, likes: item.likes }); setError(e.message); });
  };
  const moderate = (a: 'hide' | 'show' | 'delete') =>
    post(`${API}/admin`, { a, id: item.id }).then(() => change(a === 'delete' ? null : { hidden: a === 'hide' }), (e: Error) => setError(e.message));

  return (
    <div className="st-cm-view" role="dialog" aria-modal="true" aria-label={item.title} tabIndex={-1} ref={dialog}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <button className="st-cm-view__close" onClick={onClose} aria-label="Schließen"><Cross /></button>
      <button className="st-cm-view__nav st-cm-view__nav--prev" onClick={() => go(-1)} disabled={index === 0} aria-label="Vorheriges"><Chevron dir="left" /></button>
      <figure className={'st-cm-view__card' + (item.hidden ? ' is-hidden' : '')}>
        <img src={item.src} alt={item.kind === 'drawing' ? `Zeichnung: ${item.title}` : item.title} width={item.w} height={item.h} />
        <figcaption>
          <div className="st-cm-view__text">
            <b>{item.kind === 'drawing' ? item.title : item.author}</b>
            <span>{item.kind === 'drawing' ? `von ${item.author}` : item.title}</span>
            <small>{item.info} · {ago(item.at)}{item.hidden ? ' · ausgeblendet' : ''}</small>
          </div>
          <button className={'st-cm-view__heart' + (item.liked ? ' is-on' : '')} onClick={heart} aria-pressed={item.liked}
            title={me.loggedIn ? (item.liked ? 'Herz zurücknehmen' : 'Herz geben') : 'Zum Herzen geben mit Discord anmelden'}>
            <Heart on={item.liked} />{item.likes}
          </button>
        </figcaption>
        {me.admin && (
          <div className="st-cm-view__admin">
            <button className="st-chip" onClick={() => moderate(item.hidden ? 'show' : 'hide')}>{item.hidden ? 'Wieder zeigen' : 'Ausblenden'}</button>
            {confirm
              ? <><button className="st-chip is-danger" onClick={() => moderate('delete')}>Wirklich löschen</button><button className="st-chip" onClick={() => setConfirm(false)}>Abbrechen</button></>
              : <button className="st-chip" onClick={() => setConfirm(true)}>Löschen</button>}
          </div>
        )}
        {error && <p className="st-cm-view__error">{error}</p>}
      </figure>
      <button className="st-cm-view__nav st-cm-view__nav--next" onClick={() => go(1)} disabled={index >= items.length - 1} aria-label="Nächstes"><Chevron dir="right" /></button>
    </div>
  );
}
