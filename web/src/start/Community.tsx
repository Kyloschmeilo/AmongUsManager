// Community-Abschnitt der Startseite (22.11.2026, Kilian: nur Zeichnungen, Memes und Changelog, klein auf der Startseite):
// neueste Memes der Meme-Schmiede, beliebteste Zeichnungen der Kritzelkiste, Changelog des Bots. Daten von /api/community.
// Großansicht blättert durch die ganze Galerie (lädt weitere Seiten nach); Herzen nur angemeldet, Admins blenden aus/löschen.
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

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

export function Community() {
  const [data, setData] = useState<Overview | null>(null);
  const [open, setOpen] = useState<{ kind: Kind; index: number } | null>(null);
  const reload = useCallback(() => getJson<Overview>(API).then(setData, () => {}), []);
  useEffect(() => { reload(); }, [reload]);
  if (!data) return null;

  const me = data.me;
  const strips: { kind: Kind; title: string; from: string; items: Item[]; total: number; empty: string; href: string }[] = [
    { kind: 'memes', title: 'Memes', from: 'Meme-Schmiede', items: data.latest.memes, total: data.counts.memes, empty: 'Die besten Memes jeder Partie landen hier.', href: '/seiten/memes.html' },
    { kind: 'zeichnungen', title: 'Zeichnungen', from: 'Kritzelkiste', items: data.latest.drawings, total: data.counts.drawings, empty: 'Gefeierte Bilder (🔥) jeder Partie landen hier.', href: '/seiten/kritzeln.html' },
  ];
  return (
    <section className="st-cm" id="community" aria-label="Community">
      <div className="st-cm__head">
        <b>Community</b>
        {me.admin && <span className="st-cm__badge">Admin</span>}
        {!me.loggedIn && me.loginEnabled && <a className="st-cm__login" href={LOGIN}>Anmelden für Herzen</a>}
      </div>
      <div className="st-cm__cols">
        {strips.map((s) => (
          <div className="st-cm__strip" key={s.kind}>
            <h2>{s.title} <span>aus der <a href={s.href}>{s.from}</a>{s.total > 0 ? ` · ${s.total}` : ''}</span></h2>
            {s.items.length === 0 ? (
              <p className="st-cm__empty">{s.empty}</p>
            ) : (
              <div className="st-cm__thumbs">
                {s.items.map((item, i) => (
                  <button key={item.id} className={'st-cm__thumb' + (item.hidden ? ' is-hidden' : '')} onClick={() => setOpen({ kind: s.kind, index: i })}
                    title={`${item.title} – ${item.author}`} aria-label={`${item.kind === 'drawing' ? 'Zeichnung' : 'Meme'}: ${item.title}, von ${item.author}`}>
                    <img src={item.src} alt="" loading="lazy" width={item.w} height={item.h} />
                    {item.likes > 0 && <span className="st-cm__likes">♥ {item.likes}</span>}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
        <Changelog log={data.changelog} />
      </div>
      {open && <Viewer kind={open.kind} start={open.index} me={me} first={open.kind === 'memes' ? data.latest.memes : data.latest.drawings}
        onClose={() => { setOpen(null); reload(); }} />}
    </section>
  );
}

function Changelog({ log }: { log: Overview['changelog'] }) {
  const [all, setAll] = useState(false);
  const lines = log.sections.flatMap((s) => s.lines.map((line) => ({ section: s.title, line })));
  const shown = all ? lines : lines.slice(0, 3);
  return (
    <div className={'st-cm__strip st-cm__log' + (all ? '' : ' is-short')}>
      <h2>Changelog <span>Bot v{log.version}</span></h2>
      <ul>
        {shown.map((l, i) => (
          <li key={i}>{(i === 0 || shown[i - 1].section !== l.section) && l.section && <em>{l.section}</em>}<Rich text={l.line} /></li>
        ))}
      </ul>
      {lines.length > 3 && <button className="st-cm__more" onClick={() => setAll(!all)}>{all ? 'Weniger' : `Alles zeigen (${lines.length})`}</button>}
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
      <button className="st-cm-view__close" onClick={onClose} aria-label="Schließen">×</button>
      <button className="st-cm-view__nav st-cm-view__nav--prev" onClick={() => go(-1)} disabled={index === 0} aria-label="Vorheriges">‹</button>
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
            ♥ {item.likes}
          </button>
        </figcaption>
        {me.admin && (
          <div className="st-cm-view__admin">
            <button onClick={() => moderate(item.hidden ? 'show' : 'hide')}>{item.hidden ? 'Wieder zeigen' : 'Ausblenden'}</button>
            {confirm
              ? <><button className="is-danger" onClick={() => moderate('delete')}>Wirklich löschen</button><button onClick={() => setConfirm(false)}>Abbrechen</button></>
              : <button onClick={() => setConfirm(true)}>Löschen</button>}
          </div>
        )}
        {error && <p className="st-cm-view__error">{error}</p>}
      </figure>
      <button className="st-cm-view__nav st-cm-view__nav--next" onClick={() => go(1)} disabled={index >= items.length - 1} aria-label="Nächstes">›</button>
    </div>
  );
}
