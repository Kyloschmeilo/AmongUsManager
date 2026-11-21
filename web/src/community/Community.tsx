// Community-Bereich: Übersicht, Meme-Galerie, Zeichnungen, Regeln & FAQ, Neuigkeiten. Daten von /api/community.
// Admins (Recht ADMINISTRATOR im Discord) blenden Bilder aus, löschen sie, bearbeiten Regeln/FAQ und schreiben Neuigkeiten.
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router-dom';

// ---------- Daten ----------

interface Me { loginEnabled: boolean; loggedIn: boolean; admin: boolean; name: string | null }
interface Entry { title: string; text: string }
interface NewsItem { id: number | null; date: string; title: string; text: string; link: string | null; author: string | null }
interface Item {
  id: number; kind: 'meme' | 'drawing'; title: string; author: string; info: string; at: string;
  w: number; h: number; likes: number; liked: boolean; src: string; hidden?: boolean | null;
}
interface Overview {
  me: Me;
  counts: { memes: number; drawings: number };
  latest: { memes: Item[]; drawings: Item[] };
  rules: Entry[]; faq: Entry[]; customRules: boolean; customFaq: boolean;
  news: NewsItem[];
  changelog: { version: string; sections: { title: string; lines: string[] }[] };
}
interface GalleryPage { items: Item[]; more: boolean; total: number; loggedIn: boolean; admin: boolean }

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

const dateFmt = new Intl.DateTimeFormat('de-DE', { day: 'numeric', month: 'long', year: 'numeric' });
const shortFmt = new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' });
const nf = new Intl.NumberFormat('de-DE');

function day(iso: string): string {
  const d = new Date(iso.length === 10 ? iso + 'T12:00:00' : iso);
  return Number.isNaN(d.getTime()) ? iso : dateFmt.format(d);
}

function ago(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 90) return 'gerade eben';
  if (s < 3600) return `vor ${Math.round(s / 60)} min`;
  if (s < 86400) return `vor ${Math.round(s / 3600)} h`;
  if (s < 86400 * 7) return `vor ${Math.round(s / 86400)} ${Math.round(s / 86400) === 1 ? 'Tag' : 'Tagen'}`;
  return shortFmt.format(new Date(iso));
}

/** Text mit **fett** und /befehl (als Code) – ohne HTML aus den Daten. */
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

function Paragraphs({ text }: { text: string }) {
  return <>{text.split(/\n{2,}/).map((p, i) => <p key={i}>{p.split('\n').map((line, k) => <span key={k}>{k > 0 && <br />}<Rich text={line} /></span>)}</p>)}</>;
}

// ---------- Rahmen ----------

const TABS = [
  { to: '/', label: 'Übersicht', end: true },
  { to: '/memes', label: 'Memes' },
  { to: '/zeichnungen', label: 'Zeichnungen' },
  { to: '/regeln', label: 'Regeln & FAQ' },
  { to: '/neuigkeiten', label: 'Neuigkeiten' },
];

export function CommunityApp() {
  const [data, setData] = useState<Overview | null>(null);
  const [failed, setFailed] = useState(false);
  const location = useLocation();
  const reload = useCallback(() => getJson<Overview>(API).then((d) => { setData(d); setFailed(false); }, () => setFailed(true)), []);
  useEffect(() => { reload(); }, [reload]);
  useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  useEffect(() => {
    const titles: Record<string, string> = { '/memes': 'Memes', '/zeichnungen': 'Zeichnungen', '/regeln': 'Regeln & FAQ', '/neuigkeiten': 'Neuigkeiten' };
    document.title = `${titles[location.pathname] ?? 'Community'} · Spieleabend`;
  }, [location.pathname]);

  const me = data?.me;
  const back = encodeURIComponent('/community' + (location.pathname === '/' ? '' : location.pathname));
  return (
    <div className="cm">
      <a className="cm-skip" href="#inhalt">Zum Inhalt</a>
      <header className="cm-top">
        <div className="cm-wrap cm-top__row">
          <a className="cm-brand" href="/">
            <span className="cm-brand__mark" aria-hidden="true">SA</span>
            <span>Spieleabend</span>
          </a>
          <nav className="cm-nav" aria-label="Community">
            {TABS.map((t) => <NavLink key={t.to} to={t.to} end={t.end} className={({ isActive }) => 'cm-nav__item' + (isActive ? ' is-active' : '')}>{t.label}</NavLink>)}
          </nav>
          <div className="cm-top__side">
            <a className="cm-link-quiet" href="/">Alle Spiele</a>
            {me?.loggedIn ? (
              <span className="cm-user" title={me.admin ? 'Du bist Admin' : undefined}>
                {me.name ?? 'Angemeldet'}{me.admin && <span className="cm-pill cm-pill--accent">Admin</span>}
              </span>
            ) : me?.loginEnabled ? (
              <a className="cm-btn cm-btn--ghost" href={`/auth/login?back=${back}`}>Mit Discord anmelden</a>
            ) : null}
          </div>
        </div>
      </header>

      <main id="inhalt" className="cm-wrap cm-main">
        {failed && !data && <p className="cm-note cm-note--ember">Die Community-Daten konnten nicht geladen werden. <button className="cm-textbtn" onClick={reload}>Nochmal versuchen</button></p>}
        <Routes>
          <Route path="/" element={<Home data={data} />} />
          <Route path="/memes" element={<Gallery kind="memes" me={me} />} />
          <Route path="/zeichnungen" element={<Gallery kind="zeichnungen" me={me} />} />
          <Route path="/regeln" element={<RulesPage data={data} onSaved={reload} />} />
          <Route path="/neuigkeiten" element={<NewsPage data={data} onSaved={reload} />} />
          <Route path="*" element={<div className="cm-empty"><h1 className="cm-h1">Nicht gefunden</h1><p>Diese Seite gibt es im Community-Bereich nicht. <Link to="/">Zur Übersicht</Link></p></div>} />
        </Routes>
      </main>

      <footer className="cm-wrap cm-foot">
        <span>Spieleabend · Community</span>
        <span className="cm-foot__links"><a href="/">Alle Spiele</a><a href="/amongus/">Among Us</a><a href="/amongus/hilfe">Hilfe &amp; Mods</a></span>
      </footer>
    </div>
  );
}

// ---------- Übersicht ----------

function Home({ data }: { data: Overview | null }) {
  if (!data) return <Loading />;
  const news = data.news.slice(0, 3);
  return (
    <>
      <section className="cm-hero">
        <p className="cm-eyebrow">Community</p>
        <h1 className="cm-display">Alles, was wir <span className="cm-accent">zusammen</span> machen</h1>
        <p className="cm-lead">Die besten Memes aus der Meme-Schmiede, die beliebtesten Bilder aus der Kritzelkiste, unsere Regeln und was es Neues gibt.</p>
        <dl className="cm-stats">
          <div><dt>Memes</dt><dd>{nf.format(data.counts.memes)}</dd></div>
          <div><dt>Zeichnungen</dt><dd>{nf.format(data.counts.drawings)}</dd></div>
          <div><dt>Bot-Version</dt><dd>{data.changelog.version}</dd></div>
        </dl>
      </section>

      <Strip title="Neueste Memes" to="/memes" items={data.latest.memes}
        empty={<>Noch keine Memes. Spielt eine Runde <a href="/seiten/memes.html">Meme-Schmiede</a> – die besten jeder Runde landen hier.</>} />
      <Strip title="Neueste Zeichnungen" to="/zeichnungen" items={data.latest.drawings}
        empty={<>Noch keine Zeichnungen. In der <a href="/seiten/kritzeln.html">Kritzelkiste</a> kommen die Bilder hierher, die am meisten gefeiert werden.</>} />

      <div className="cm-split">
        <section className="cm-card">
          <div className="cm-card__head"><h2 className="cm-h2">Neuigkeiten</h2><Link className="cm-more" to="/neuigkeiten">Alle</Link></div>
          <ul className="cm-newslist">
            {news.map((n, i) => (
              <li key={i}><span className="cm-mono cm-faint">{shortFmt.format(new Date(n.date + 'T12:00:00'))}</span><strong>{n.title}</strong><span className="cm-muted">{n.text}</span></li>
            ))}
          </ul>
        </section>
        <section className="cm-card">
          <div className="cm-card__head"><h2 className="cm-h2">Die wichtigsten Regeln</h2><Link className="cm-more" to="/regeln">Regeln & FAQ</Link></div>
          <ol className="cm-rulelist">
            {data.rules.slice(0, 4).map((r, i) => <li key={i}><span className="cm-mono cm-accent">{String(i + 1).padStart(2, '0')}</span><strong>{r.title}</strong></li>)}
          </ol>
        </section>
      </div>
    </>
  );
}

function Strip({ title, to, items, empty }: { title: string; to: string; items: Item[]; empty: ReactNode }) {
  const [open, setOpen] = useState<Item | null>(null);
  return (
    <section className="cm-section">
      <div className="cm-section__head"><h2 className="cm-h2">{title}</h2>{items.length > 0 && <Link className="cm-more" to={to}>Alle ansehen</Link>}</div>
      {items.length ? (
        <div className="cm-strip">
          {items.map((item) => (
            <button key={item.id} className="cm-thumb" onClick={() => setOpen(item)} aria-label={`${item.title || 'Bild'} von ${item.author} groß ansehen`}>
              <img src={item.src} alt="" loading="lazy" width={item.w} height={item.h} />
              <span className="cm-thumb__by">{item.author}</span>
            </button>
          ))}
        </div>
      ) : <p className="cm-note">{empty}</p>}
      {open && <Lightbox item={open} onClose={() => setOpen(null)} />}
    </section>
  );
}

// ---------- Galerie ----------

function Gallery({ kind, me }: { kind: 'memes' | 'zeichnungen'; me: Me | undefined }) {
  const [sort, setSort] = useState<'neu' | 'beste'>('neu');
  const [page, setPage] = useState<GalleryPage | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Item | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback((offset: number) => {
    setBusy(true);
    return getJson<GalleryPage>(`${API}/galerie?art=${kind}&sort=${sort}&offset=${offset}`)
      .then((p) => { setPage(p); setItems((prev) => (offset === 0 ? p.items : [...prev, ...p.items])); setError(null); })
      .catch(() => setError('Die Galerie konnte nicht geladen werden.'))
      .finally(() => setBusy(false));
  }, [kind, sort]);
  useEffect(() => { setItems([]); setPage(null); load(0); }, [load]);

  const update = (id: number, change: Partial<Item> | null) =>
    setItems((list) => (change === null ? list.filter((i) => i.id !== id) : list.map((i) => (i.id === id ? { ...i, ...change } : i))));

  const isMemes = kind === 'memes';
  const admin = page?.admin ?? me?.admin ?? false;
  return (
    <>
      <header className="cm-pagehead">
        <p className="cm-eyebrow">{isMemes ? 'Aus der Meme-Schmiede' : 'Aus der Kritzelkiste'}</p>
        <h1 className="cm-h1">{isMemes ? 'Meme-Galerie' : 'Zeichnungen'}</h1>
        <p className="cm-lead">
          {isMemes
            ? 'Am Ende jeder Partie kommen die bestbewerteten Memes jeder Runde hierher – mit Autor und Punkten.'
            : 'Die Bilder, die in einer Partie am meisten gefeiert wurden (🔥), höchstens drei pro Partie.'}
        </p>
        <div className="cm-toolbar">
          <div className="cm-seg" role="group" aria-label="Sortierung">
            <button className={sort === 'neu' ? 'is-on' : ''} aria-pressed={sort === 'neu'} onClick={() => setSort('neu')}>Neueste</button>
            <button className={sort === 'beste' ? 'is-on' : ''} aria-pressed={sort === 'beste'} onClick={() => setSort('beste')}>Meiste Herzen</button>
          </div>
          {page && <span className="cm-faint cm-mono">{nf.format(page.total)} {page.total === 1 ? 'Bild' : 'Bilder'}</span>}
          {admin && <span className="cm-pill cm-pill--accent">Admin: Ausblenden &amp; Löschen an jedem Bild</span>}
        </div>
      </header>

      {error && <p className="cm-note cm-note--ember">{error}</p>}
      {!page && !error && <Loading />}
      {page && items.length === 0 && (
        <div className="cm-empty">
          <p>Hier ist noch nichts. {isMemes
            ? <>Startet eine Partie <a href="/seiten/memes.html">Meme-Schmiede</a>!</>
            : <>Malt eine Runde in der <a href="/seiten/kritzeln.html">Kritzelkiste</a> und feiert die besten Bilder mit 🔥.</>}</p>
        </div>
      )}
      <div className={'cm-grid' + (isMemes ? '' : ' cm-grid--drawings')}>
        {items.map((item) => (
          <Card key={item.id} item={item} loggedIn={!!(page?.loggedIn ?? me?.loggedIn)} loginEnabled={me?.loginEnabled ?? true}
            admin={admin} onOpen={() => setOpen(item)} onChange={(c) => update(item.id, c)} />
        ))}
      </div>
      {page?.more && (
        <div className="cm-center"><button className="cm-btn cm-btn--ghost" disabled={busy} onClick={() => load(items.length)}>{busy ? 'Lädt …' : 'Mehr laden'}</button></div>
      )}
      {open && <Lightbox item={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function Card({ item, loggedIn, loginEnabled, admin, onOpen, onChange }: {
  item: Item; loggedIn: boolean; loginEnabled: boolean; admin: boolean; onOpen: () => void; onChange: (c: Partial<Item> | null) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const location = useLocation();
  const heart = () => {
    if (!loggedIn) {
      if (loginEnabled) window.location.href = `/auth/login?back=${encodeURIComponent('/community' + location.pathname)}`;
      return;
    }
    const on = !item.liked;
    onChange({ liked: on, likes: item.likes + (on ? 1 : -1) });
    post<{ likes: number; liked: boolean }>(`${API}/herz`, { id: item.id, on })
      .then((r) => onChange({ likes: r.likes, liked: r.liked }), (e: Error) => { onChange({ liked: item.liked, likes: item.likes }); setError(e.message); });
  };
  const moderate = (a: 'hide' | 'show' | 'delete') => {
    post(`${API}/admin`, { a, id: item.id }).then(() => onChange(a === 'delete' ? null : { hidden: a === 'hide' }), (e: Error) => setError(e.message));
  };
  return (
    <figure className={'cm-item' + (item.hidden ? ' is-hidden' : '')}>
      <button className="cm-item__img" onClick={onOpen} aria-label="Groß ansehen" style={{ aspectRatio: `${item.w} / ${item.h}` }}>
        <img src={item.src} alt={item.kind === 'drawing' ? `Zeichnung: ${item.title}` : item.title} loading="lazy" width={item.w} height={item.h} />
        {item.hidden && <span className="cm-pill cm-pill--ember cm-item__flag">Ausgeblendet</span>}
      </button>
      <figcaption>
        <div className="cm-item__row">
          <div className="cm-item__text">
            {item.kind === 'drawing' && <strong className="cm-item__title">{item.title}</strong>}
            <span className="cm-item__by">von <strong>{item.author}</strong></span>
          </div>
          <button className={'cm-heart' + (item.liked ? ' is-on' : '')} onClick={heart} aria-pressed={item.liked}
            title={loggedIn ? (item.liked ? 'Herz zurücknehmen' : 'Herz geben') : 'Zum Herz geben mit Discord anmelden'}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20.5 4.2 13A5 5 0 0 1 11.3 6l.7.7.7-.7a5 5 0 0 1 7.1 7.1Z" /></svg>
            <span className="cm-mono">{item.likes}</span>
          </button>
        </div>
        <div className="cm-item__meta cm-faint">{item.info} · {ago(item.at)}</div>
        {error && <div className="cm-item__err">{error}</div>}
        {admin && (
          <div className="cm-item__admin">
            {item.hidden
              ? <button className="cm-textbtn" onClick={() => moderate('show')}>Wieder zeigen</button>
              : <button className="cm-textbtn" onClick={() => moderate('hide')}>Ausblenden</button>}
            {confirm
              ? <><button className="cm-textbtn cm-ember" onClick={() => moderate('delete')}>Endgültig löschen</button><button className="cm-textbtn" onClick={() => setConfirm(false)}>Abbrechen</button></>
              : <button className="cm-textbtn cm-ember" onClick={() => setConfirm(true)}>Löschen</button>}
          </div>
        )}
      </figcaption>
    </figure>
  );
}

function Lightbox({ item, onClose }: { item: Item; onClose: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', key);
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', key); document.body.style.overflow = ''; };
  }, [onClose]);
  const file = `${item.kind === 'meme' ? 'meme' : 'zeichnung'}-${item.id}.${item.src && item.kind === 'drawing' ? 'png' : 'jpg'}`;
  return (
    <div className="cm-lightbox" role="dialog" aria-modal="true" aria-label={item.title || 'Bild'} onClick={onClose}>
      <div className="cm-lightbox__box" onClick={(e) => e.stopPropagation()}>
        <img src={item.src} alt={item.title} />
        <div className="cm-lightbox__bar">
          <div>
            {item.kind === 'drawing' && <strong>{item.title} · </strong>}
            <span>von {item.author}</span>
            <span className="cm-faint"> · {item.info} · {ago(item.at)}</span>
          </div>
          <div className="cm-lightbox__actions">
            <a className="cm-btn cm-btn--ghost" href={item.src} download={file}>Herunterladen</a>
            <button className="cm-btn" onClick={onClose} autoFocus>Schließen</button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------- Regeln & FAQ ----------

function RulesPage({ data, onSaved }: { data: Overview | null; onSaved: () => void }) {
  const [editing, setEditing] = useState<'rules' | 'faq' | null>(null);
  if (!data) return <Loading />;
  const admin = data.me.admin;
  return (
    <>
      <header className="cm-pagehead">
        <p className="cm-eyebrow">Discord-Server</p>
        <h1 className="cm-h1">Regeln &amp; FAQ</h1>
        <p className="cm-lead">Damit alle Spaß haben: die Regeln für Discord, Voice und Spiele – und Antworten auf das, was oft gefragt wird.</p>
      </header>
      <div className="cm-split cm-split--rules">
        <section className="cm-card">
          <div className="cm-card__head">
            <h2 className="cm-h2">Regeln</h2>
            {admin && editing !== 'rules' && <button className="cm-textbtn" onClick={() => setEditing('rules')}>Bearbeiten</button>}
          </div>
          {editing === 'rules'
            ? <EntryEditor field="rules" entries={data.rules} custom={data.customRules} onDone={() => { setEditing(null); onSaved(); }} />
            : (
              <ol className="cm-rules">
                {data.rules.map((r, i) => (
                  <li key={i}>
                    <span className="cm-rules__no cm-mono">{String(i + 1).padStart(2, '0')}</span>
                    <div><h3 className="cm-h3">{r.title}</h3><Paragraphs text={r.text} /></div>
                  </li>
                ))}
              </ol>
            )}
        </section>
        <section className="cm-card">
          <div className="cm-card__head">
            <h2 className="cm-h2">Häufige Fragen</h2>
            {admin && editing !== 'faq' && <button className="cm-textbtn" onClick={() => setEditing('faq')}>Bearbeiten</button>}
          </div>
          {editing === 'faq'
            ? <EntryEditor field="faq" entries={data.faq} custom={data.customFaq} onDone={() => { setEditing(null); onSaved(); }} />
            : (
              <div className="cm-faq">
                {data.faq.map((f, i) => (
                  <details key={i} className="cm-faq__item">
                    <summary><span>{f.title}</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg></summary>
                    <div className="cm-faq__body"><Paragraphs text={f.text} /></div>
                  </details>
                ))}
              </div>
            )}
        </section>
      </div>
    </>
  );
}

function EntryEditor({ field, entries, custom, onDone }: { field: 'rules' | 'faq'; entries: Entry[]; custom: boolean; onDone: () => void }) {
  const [list, setList] = useState<Entry[]>(entries);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (i: number, change: Partial<Entry>) => setList((l) => l.map((e, k) => (k === i ? { ...e, ...change } : e)));
  const move = (i: number, d: number) => setList((l) => {
    const j = i + d;
    if (j < 0 || j >= l.length) return l;
    const copy = [...l];
    [copy[i], copy[j]] = [copy[j], copy[i]];
    return copy;
  });
  const save = (value: Entry[] | null) => {
    setBusy(true);
    post(`${API}/admin`, { a: 'texts', [field]: value }).then(onDone, (e: Error) => { setError(e.message); setBusy(false); });
  };
  const label = field === 'rules' ? 'Regel' : 'Frage';
  return (
    <div className="cm-editor">
      {list.map((e, i) => (
        <div className="cm-editor__entry" key={i}>
          <div className="cm-editor__row">
            <span className="cm-mono cm-faint">{String(i + 1).padStart(2, '0')}</span>
            <input className="cm-input" value={e.title} maxLength={80} placeholder={label} aria-label={`${label} ${i + 1}: Titel`} onChange={(ev) => set(i, { title: ev.target.value })} />
            <button className="cm-iconbtn" onClick={() => move(i, -1)} disabled={i === 0} aria-label="Nach oben">↑</button>
            <button className="cm-iconbtn" onClick={() => move(i, 1)} disabled={i === list.length - 1} aria-label="Nach unten">↓</button>
            <button className="cm-iconbtn cm-ember" onClick={() => setList((l) => l.filter((_, k) => k !== i))} aria-label="Entfernen">×</button>
          </div>
          <textarea className="cm-input" rows={3} maxLength={1200} value={e.text} aria-label={`${label} ${i + 1}: Text`} onChange={(ev) => set(i, { text: ev.target.value })} />
        </div>
      ))}
      <button className="cm-textbtn" onClick={() => setList((l) => [...l, { title: '', text: '' }])} disabled={list.length >= 30}>+ {label} hinzufügen</button>
      {error && <p className="cm-note cm-note--ember">{error}</p>}
      <div className="cm-editor__actions">
        <button className="cm-btn" disabled={busy} onClick={() => save(list)}>Speichern</button>
        <button className="cm-btn cm-btn--ghost" disabled={busy} onClick={onDone}>Abbrechen</button>
        {custom && <button className="cm-textbtn" disabled={busy} onClick={() => save(null)}>Auf Standard zurücksetzen</button>}
      </div>
    </div>
  );
}

// ---------- Neuigkeiten ----------

function NewsPage({ data, onSaved }: { data: Overview | null; onSaved: () => void }) {
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!data) return <Loading />;
  const admin = data.me.admin;
  const add = () => {
    setBusy(true);
    post(`${API}/admin`, { a: 'news.add', title, text })
      .then(() => { setTitle(''); setText(''); setError(null); onSaved(); }, (e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };
  const remove = (id: number) => post(`${API}/admin`, { a: 'news.delete', id }).then(onSaved, (e: Error) => setError(e.message));
  return (
    <>
      <header className="cm-pagehead">
        <p className="cm-eyebrow">Was ist neu</p>
        <h1 className="cm-h1">Neuigkeiten</h1>
        <p className="cm-lead">Neue Spiele, Funktionen auf der Website und Updates des Discord-Bots.</p>
      </header>
      <div className="cm-split cm-split--news">
        <div>
          {admin && (
            <section className="cm-card cm-compose">
              <h2 className="cm-h2">Beitrag schreiben</h2>
              <input className="cm-input" placeholder="Titel" maxLength={100} value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Titel" />
              <textarea className="cm-input" rows={4} placeholder="Was gibt es Neues?" maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} aria-label="Text" />
              {error && <p className="cm-note cm-note--ember">{error}</p>}
              <div><button className="cm-btn" disabled={busy || !title.trim() || !text.trim()} onClick={add}>Veröffentlichen</button></div>
            </section>
          )}
          <ol className="cm-timeline">
            {data.news.map((n, i) => (
              <li key={`${n.id ?? 'f'}-${i}`} className="cm-timeline__item">
                <time className="cm-mono cm-faint" dateTime={n.date}>{day(n.date)}</time>
                <h3 className="cm-h3">{n.link ? <a href={n.link}>{n.title}</a> : n.title}</h3>
                <div className="cm-muted"><Paragraphs text={n.text} /></div>
                {(n.author || (admin && n.id != null)) && (
                  <div className="cm-timeline__foot cm-faint">
                    {n.author && <span>von {n.author}</span>}
                    {admin && n.id != null && <button className="cm-textbtn cm-ember" onClick={() => remove(n.id!)}>Löschen</button>}
                  </div>
                )}
              </li>
            ))}
          </ol>
        </div>
        <aside className="cm-card cm-changelog">
          <p className="cm-eyebrow">Discord-Bot</p>
          <h2 className="cm-h2">Changelog <span className="cm-mono cm-accent">v{data.changelog.version}</span></h2>
          {data.changelog.sections.map((s, i) => (
            <div key={i} className="cm-changelog__sec">
              <h3 className="cm-h3">{s.title}</h3>
              <ul>{s.lines.map((l, k) => <li key={k}><Rich text={l} /></li>)}</ul>
            </div>
          ))}
        </aside>
      </div>
    </>
  );
}

function Loading() {
  return <p className="cm-loading" role="status">Lädt …</p>;
}
