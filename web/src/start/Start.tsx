// Spiele-Startseite: oben live, was gerade läuft, darunter alle Spiele – Among Us immer als größte Kachel vorne.
import { useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent } from 'react';
import gsap from 'gsap';
import type { LiveLobby, LobbiesResponse } from '../lib/lobbies';
import type { Overview, RoundItem } from '../lib/api';
import { playerColor } from '../lib/colors';
import { Community } from './Community';
import { GAMES, joinHref, liveOf, type Game, type LiveGame, type LiveRoom } from './games';

const APP = '/amongus';

interface PageInfo { file: string; title: string | null; description: string | null }
interface PagesResponse { pages: PageInfo[]; exists?: string[]; corehold: boolean }

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
  if (!response.ok) throw new Error(String(response.status));
  return (await response.json()) as T;
}

/** Fragt [path] alle [ms] neu, solange der Tab sichtbar ist (0 = nur einmal). */
function usePoll<T>(path: string, ms: number): { data: T | null; failed: boolean; settled: boolean } {
  const [state, setState] = useState<{ data: T | null; failed: boolean }>({ data: null, failed: false });
  useEffect(() => {
    let alive = true;
    const load = () => getJson<T>(path).then((data) => alive && setState({ data, failed: false }), () => alive && setState((s) => ({ ...s, failed: true })));
    load();
    const timer = ms > 0 ? window.setInterval(() => document.visibilityState === 'visible' && load(), ms) : 0;
    return () => { alive = false; window.clearInterval(timer); };
  }, [path, ms]);
  return { ...state, settled: state.data != null || state.failed };
}

/** Startseiten-Infos zu seiten/ – inkl. Frage, welche der möglichen Spiel-Dateien es gibt (Ordner oder im JAR). */
const PAGES_URL = `/api/pages?check=${encodeURIComponent(GAMES.flatMap((g) => g.files).join(','))}`;

/** Je Spiel die erste vorhandene Datei: Kachel-Adresse → Dateiname; null solange noch gefragt wird. */
function foundPages(data: PagesResponse | null): Map<string, string> | null {
  if (!data) return null;
  const exists = new Set(data.exists ?? []);
  const map = new Map<string, string>();
  for (const game of GAMES) {
    const file = game.files.find((f) => exists.has(f));
    if (file) map.set(game.href, file);
  }
  return map;
}

function ago(iso: string | null | undefined): string {
  if (!iso) return '';
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 90) return 'gerade eben';
  if (s < 3600) return `vor ${Math.round(s / 60)} min`;
  if (s < 86400 * 1.5) return `vor ${Math.round(s / 3600)} h`;
  return `vor ${Math.round(s / 86400)} Tagen`;
}

const nf = new Intl.NumberFormat('de-DE');
const TEAM_NAME: Record<string, string> = { CREW: 'Crew', IMPOSTOR: 'Impostor', NEUTRAL: 'Neutral', UNKNOWN: '?' };

const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Ist der Einflug der Seite vorbei? Was danach neu dazukommt (Lobby, Spiel, Kachel), springt einzeln herein. */
let entered = false;

/** Lässt ein Element, das nach dem Einflug erscheint, genauso hereinspringen wie beim ersten Aufruf alle anderen. */
function useAppear<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    if (!entered || !ref.current || reduced()) return;
    const tween = gsap.from(ref.current, { y: 24, scale: 0.92, opacity: 0, duration: 0.5, ease: 'back.out(1.8)', clearProps: 'transform,opacity' });
    return () => { tween.kill(); };
  }, []);
  return ref;
}

/** Seiten, für die es die Datei gibt: Kachel-Adresse → Adresse zum Spielen. */
function gameHref(game: Game, file: string): string {
  return game.fixedHref ? game.href : `/seiten/${file}`;
}

interface RunningGame { game: Game; href: string; info: LiveGame }

/**
 * Fragt bei allen vorhandenen Browserspielen mit Status-Adresse alle 10 s nach, was los ist. Antwortet ein Spiel nicht mit
 * JSON (z.B. ältere Version ohne Status), wird es für diesen Besuch nicht mehr gefragt.
 */
function useRunningGames(found: Map<string, string> | null): { running: RunningGame[]; settled: boolean } {
  const [state, setState] = useState<{ running: RunningGame[]; settled: boolean }>({ running: [], settled: false });
  useEffect(() => {
    if (!found) return;
    const targets = GAMES.filter((g) => g.live && found.has(g.href)).map((g) => ({ game: g, href: gameHref(g, found.get(g.href)!) }));
    if (!targets.length) { setState({ running: [], settled: true }); return; }
    let alive = true;
    const dead = new Set<string>();
    const load = async () => {
      const results = await Promise.all(targets.filter((t) => !dead.has(t.game.href)).map(async (t) => {
        try {
          const info = liveOf(await getJson<unknown>(t.game.live!));
          return info ? { ...t, info } : null;
        } catch {
          dead.add(t.game.href);
          return null;
        }
      }));
      if (alive) setState({ running: results.filter((r): r is RunningGame => r != null), settled: true });
    };
    load();
    const timer = window.setInterval(() => document.visibilityState === 'visible' && load(), 10000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [found]);
  return state;
}

export function Start() {
  const lobbies = usePoll<LobbiesResponse>('/api/lobbies', 5000);
  const overview = usePoll<Overview>('/api/overview', 60000);
  const pages = usePoll<PagesResponse>(PAGES_URL, 0);
  const found = useMemo(() => foundPages(pages.data), [pages.data]);
  const games = useRunningGames(found);
  const root = useRef<HTMLDivElement>(null);

  // Einflug erst, wenn die ersten Daten da sind (höchstens 1,5 s warten) – sonst kämen Lobbys, Spiele und Zahlen ohne
  // Animation nach und nur ein Teil der Seite würde hereinfliegen.
  const [timedOut, setTimedOut] = useState(false);
  useEffect(() => { const t = window.setTimeout(() => setTimedOut(true), 1500); return () => window.clearTimeout(t); }, []);
  const ready = timedOut || (lobbies.settled && overview.settled && pages.settled && (games.settled || pages.failed));
  const [shown, setShown] = useState(false);
  const [entering, setEntering] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    const el = root.current;
    if (!ready || started.current || !el) return;
    started.current = true;
    setShown(true);
    if (reduced()) { entered = true; return; }
    // CSS-Übergänge auf transform würden GSAP ausbremsen – während des Einflugs aus (Klasse sofort setzen, React zieht nach)
    el.classList.add('st-entering');
    el.classList.remove('st-wait');
    setEntering(true);
    const done = () => { setEntering(false); entered = true; };
    const ctx = gsap.context(() => {
      gsap.timeline({ onComplete: done })
        .from('.st-word span', { y: -60, rotate: () => gsap.utils.random(-25, 25), opacity: 0, duration: 0.7, ease: 'back.out(2.2)', stagger: 0.045 }, 0)
        .from('.st-sub', { y: 20, opacity: 0, duration: 0.6, ease: 'power3.out' }, 0.3)
        .from('.st-live__label', { x: -24, opacity: 0, duration: 0.5, ease: 'power3.out' }, 0.4)
        .from('.st-live__row > *', { y: 30, scale: 0.92, opacity: 0, duration: 0.55, ease: 'back.out(1.8)', stagger: 0.07, clearProps: 'transform,opacity' }, 0.45)
        .from('.st-tile', { y: 60, rotate: () => gsap.utils.random(-6, 6), opacity: 0, duration: 0.7, ease: 'back.out(1.6)', stagger: 0.06, clearProps: 'transform,opacity' }, 0.55)
        .from('.st-foot', { y: 16, opacity: 0, duration: 0.5, ease: 'power3.out' }, '-=0.4');
    }, root);
    unmount.current = () => { ctx.revert(); done(); };
  }, [ready]);
  const unmount = useRef<() => void>(() => {});
  useEffect(() => () => unmount.current(), []);

  const live = lobbies.data?.lobbies ?? [];
  const known = useMemo(() => new Map((pages.data?.pages ?? []).map((p) => [p.file, p])), [pages.data]);
  const extra = (pages.data?.pages ?? []).filter((p) => !GAMES.some((g) => g.files.includes(p.file)));
  // Breite Kachel nur, wenn sie das Raster (4 Spalten, Among Us belegt 6 Felder) auffüllt – sonst bliebe eine Lücke
  const singles = GAMES.filter((g) => !g.wide).length + extra.length;
  const wideFits = (singles + 6) % 4 === 2;

  return (
    <div className={`st${shown ? '' : ' st-wait'}${entering ? ' st-entering' : ''}`} ref={root}>
      <Confetti />
      <header className="st-head wrap">
        <h1 className="st-word" aria-label="Spieleabend">
          {'Spieleabend'.split('').map((c, i) => <span key={i} style={{ '--i': i } as CSSProperties} aria-hidden="true">{c}</span>)}
        </h1>
        <p className="st-sub">Unser Discord-Server zum Zocken – Among Us mit Stats und Live-Lobbys, Browserspiele für zwischendurch, Minecraft und mehr.</p>
      </header>

      <main className="wrap">
        <LiveBar lobbies={live} games={games.running} loaded={lobbies.data != null} failed={lobbies.failed && !lobbies.data} overview={overview.data} />

        <section className="st-grid" aria-label="Alle Spiele">
          <AmongUsTile overview={overview.data} live={live} />
          {GAMES.map((game) => {
            if (!game.files.length) {
              return <GameTile key={game.href} game={{ ...game, wide: game.wide && wideFits }} ready={pages.data?.corehold !== false} />;
            }
            const file = found?.get(game.href);
            const page = file ? known.get(file) : undefined;
            const href = file ? gameHref(game, file) : game.href;
            return <GameTile key={game.href} game={{ ...game, href, wide: game.wide && wideFits }} page={page} ready={found ? !!file : true} />;
          })}
          {extra.map((p) => (
            <GameTile key={p.file} page={p} ready game={{
              files: [p.file], href: `/seiten/${encodeURIComponent(p.file)}`, title: p.file.replace(/\.html?$/, ''), text: '', tag: 'Neu',
              color: '#ffffff', ink: '#120f24', accent: '#ff4f81',
              icon: <svg viewBox="0 0 64 64" aria-hidden="true"><path d="M32 6l7 17 18 2-14 12 4 18-15-9-15 9 4-18L7 25l18-2z" fill="#ffd23f" stroke="#120f24" strokeWidth="3" strokeLinejoin="round" /></svg>,
            }} />
          ))}
        </section>

        <Community />
      </main>

      <footer className="st-foot wrap">
        <span>Spieleabend · Among-Us-Daten von der Mod CrewStats</span>
        <span><a href="#community">Community</a> · <a href={`${APP}/hilfe`}>Hilfe &amp; Mods</a></span>
      </footer>
    </div>
  );
}

// ---------- Live ----------

function LiveBar({ lobbies, games, loaded, failed, overview }: { lobbies: LiveLobby[]; games: RunningGame[]; loaded: boolean; failed: boolean; overview: Overview | null }) {
  const last = overview?.recent?.[0];
  const hint = [
    lobbies.length ? `${lobbies.length} ${lobbies.length === 1 ? 'Lobby' : 'Lobbys'} in Among Us` : '',
    games.length ? `${games.length} ${games.length === 1 ? 'Browserspiel läuft' : 'Browserspiele laufen'}` : '',
  ].filter(Boolean).join(' · ') || 'Among Us & Browserspiele';
  return (
    <section className="st-live" aria-label="Gerade live">
      <div className="st-live__label">
        <b><span className={`st-dot${lobbies.length || games.length ? ' is-on' : ''}`} aria-hidden="true" />Live</b>
        <span className="st-live__hint">{hint}</span>
      </div>
      <div className="st-live__row">
        {lobbies.map((lobby) => <LobbyCard key={lobby.id} lobby={lobby} />)}
        {lobbies.length > 0 && <MoreLobbies />}
        {games.map((g) => <GameLiveCards key={g.game.href} running={g} />)}
        {loaded && lobbies.length === 0 && <Idle last={last} />}
        {!loaded && !failed && <div className="st-idle st-idle--wait"><span>Schaue nach laufenden Lobbys …</span></div>}
        {failed && <div className="st-idle"><span>Live-Daten gerade nicht erreichbar.</span></div>}
      </div>
    </section>
  );
}

function MoreLobbies() {
  const ref = useAppear<HTMLAnchorElement>();
  return (
    <a ref={ref} className="st-lobby st-lobby--more" href={`${APP}/lobbys`}>
      <b className="st-lobby__map">Alle Lobbys</b>
      <span className="st-lobby__host">Wer drin ist, Level, Beitreten über Auto-Join</span>
      <span className="st-tile__go">Zur Live-Seite <Arrow /></span>
    </a>
  );
}

function Idle({ last }: { last: RoundItem | undefined }) {
  const ref = useAppear<HTMLDivElement>();
  return (
    <div ref={ref} className="st-idle">
      <b>Gerade keine offene Among-Us-Lobby.</b>
      <span>
        {last ? <>Letzte Runde {ago(last.endedAt)} auf {last.map} – <LastWinner round={last} />.</> : 'Sobald ein Host mit CrewStats eine Lobby aufmacht, steht sie hier.'}
      </span>
      <a className="st-chip" href={`${APP}/lobbys`}>Live-Seite öffnen</a>
    </div>
  );
}

/**
 * Laufendes Browserspiel: Raum-Spiele zeigen jeden beitretbaren Raum als eigene Karte (Klick = direkt in den Raum),
 * Arenen eine Karte mit Spielerzahl (Klick = direkt in die Arena). Sind alle Räume voll, führt die Karte nur zum Spiel.
 */
function GameLiveCards({ running }: { running: RunningGame }) {
  const { href, info } = running;
  if (info.list && info.list.length) {
    return <>{info.list.map((room) => <GameLiveCard key={room.code} running={running} room={room} href={joinHref(href, room.code)} />)}</>;
  }
  return <GameLiveCard running={running} href={info.list ? href : joinHref(href)} />;
}

function GameLiveCard({ running, room, href }: { running: RunningGame; room?: LiveRoom; href: string }) {
  const ref = useAppear<HTMLAnchorElement>();
  const { game, info } = running;
  const style = { '--c': game.color, '--ink': game.ink, '--acc': game.accent } as CSSProperties;
  const count = room ? room.players : info.players;
  const people = room ? `${count}/${room.max} Spieler` : count ? `${count} ${count === 1 ? 'spielt gerade' : 'spielen gerade'}` : '';
  const what = room
    ? `Raum ${room.code} · ${room.playing ? 'läuft' : 'Lobby'}`
    : info.list ? `${info.rooms} ${info.rooms === 1 ? 'Raum' : 'Räume'}, alle voll` : '';
  const go = room ? 'Beitreten' : info.list ? 'Zum Spiel' : 'Mitspielen';
  return (
    <a ref={ref} className="st-lobby st-lobby--game" href={href} style={style}
      aria-label={room ? `${game.title}: Raum ${room.code} beitreten` : `${game.title}: ${go}`}>
      <span className="st-lobby__state">{game.tag}</span>
      <span className="st-lobby__icon">{game.icon}</span>
      <b className="st-lobby__map">{game.title}</b>
      <span className="st-lobby__host">{[what, people].filter(Boolean).join(' · ')}</span>
      {count > 0 && (
        <span className="st-lobby__crew" aria-hidden="true">
          {Array.from({ length: Math.min(12, count) }, (_, i) => <i key={i} style={{ background: 'var(--acc)', animationDelay: `${i * 0.12}s` }} />)}
        </span>
      )}
      <span className="st-tile__go">{go} <Arrow /></span>
    </a>
  );
}

function LastWinner({ round }: { round: RoundItem }) {
  const team = round.winningTeam;
  return <span className={`st-team st-team--${team.toLowerCase()}`}>{TEAM_NAME[team] ?? team} gewinnt</span>;
}

function LobbyCard({ lobby }: { lobby: LiveLobby }) {
  const ref = useAppear<HTMLAnchorElement>();
  const fill = lobby.max > 0 ? Math.min(1, lobby.players / lobby.max) : 0;
  return (
    <a ref={ref} className={`st-lobby st-lobby--${lobby.state}`} href={`${APP}/lobbys`}>
      <span className="st-lobby__state">{lobby.state === 'open' ? 'Lobby offen' : 'Runde läuft'}</span>
      <b className="st-lobby__map">{lobby.map}</b>
      <span className="st-lobby__host">Host {lobby.host}</span>
      <span className="st-lobby__crew" aria-label={`${lobby.players} von ${lobby.max} Spielern`}>
        {lobby.list.slice(0, 15).map((p, i) => (
          <i key={i} title={p.name} style={{ background: playerColor(p.colorId, p.key ?? p.name), animationDelay: `${i * 0.12}s` }} />
        ))}
      </span>
      <span className="st-lobby__bar"><span style={{ transform: `scaleX(${fill})` }} /></span>
      <span className="st-lobby__count">{lobby.players}/{lobby.max} {lobby.state === 'open' && !lobby.full ? '· Beitreten' : ''}</span>
    </a>
  );
}

// ---------- Kacheln ----------

/** Kachel kippt leicht zum Zeiger (CSS-Variablen, ohne Neuzeichnen von React). */
function tilt(e: PointerEvent<HTMLElement>) {
  const el = e.currentTarget;
  const r = el.getBoundingClientRect();
  el.style.setProperty('--tx', String((e.clientX - r.left) / r.width - 0.5));
  el.style.setProperty('--ty', String((e.clientY - r.top) / r.height - 0.5));
}
function untilt(e: PointerEvent<HTMLElement>) {
  e.currentTarget.style.setProperty('--tx', '0');
  e.currentTarget.style.setProperty('--ty', '0');
}

function AmongUsTile({ overview, live }: { overview: Overview | null; live: LiveLobby[] }) {
  // Crewmates in den Farben der letzten Runde (sonst ein bunter Standard-Trupp)
  const colors = useMemo(() => {
    const roster = overview?.recent?.[0]?.roster ?? [];
    const list = roster.map((p) => playerColor(p.colorId, p.key)).slice(0, 7);
    return list.length >= 3 ? list : ['#c51111', '#132ed1', '#117f2d', '#ed54ba', '#ef7d0d', '#f5f557', '#38fedc'];
  }, [overview]);
  const open = live.filter((l) => l.state === 'open').length;
  return (
    <article className="st-tile st-among" onPointerMove={tilt} onPointerLeave={untilt}>
      <a className="st-among__link" href={`${APP}/`} aria-label="Among Us – zu den Stats" />
      <div className="st-among__space" aria-hidden="true">
        {colors.map((c, i) => <Crewmate key={i} color={c} index={i} />)}
      </div>
      <div className="st-among__body">
        <span className="st-tag st-tag--light">Hauptspiel</span>
        {live.length > 0 && <span className="st-tag st-tag--live">{open ? `${open} Lobby offen` : 'Runde läuft'}</span>}
        <h2 className="st-among__title">Among Us</h2>
        <p className="st-among__text">Jede Runde automatisch aufgezeichnet: Bestenliste, Profile, Replays, Heatmaps und Live-Lobbys zum Beitreten.</p>
        {overview && (
          <dl className="st-among__stats">
            <div><dt>Runden</dt><dd>{nf.format(overview.rounds)}</dd></div>
            <div><dt>Spieler</dt><dd>{nf.format(overview.players)}</dd></div>
            <div><dt>Kills</dt><dd>{nf.format(overview.totals.kills)}</dd></div>
          </dl>
        )}
        <div className="st-among__actions">
          <a className="st-btn st-btn--big" href={`${APP}/`}>Zu den Stats <Arrow /></a>
          <a className="st-btn" href={`${APP}/lobbys`}>Live</a>
          <a className="st-btn" href={`${APP}/bestenliste`}>Bestenliste</a>
          <a className="st-btn" href={`${APP}/runden`}>Runden</a>
        </div>
      </div>
    </article>
  );
}

function GameTile({ game, page, ready }: { game: Game; page?: PageInfo; ready: boolean }) {
  const linkRef = useAppear<HTMLAnchorElement>();
  const boxRef = useAppear<HTMLDivElement>();
  const style = { '--c': game.color, '--ink': game.ink, '--acc': game.accent } as CSSProperties;
  const title = page?.title?.split(/\s[–|-]\s/)[0] || game.title;
  const body = (
    <>
      <span className="st-tile__icon">{game.icon}</span>
      <span className="st-tag">{ready ? game.tag : 'Bald'}</span>
      <h3 className="st-tile__title">{title}</h3>
      <p className="st-tile__text">{page?.description || game.text}</p>
      {ready && <span className="st-tile__go">Spielen <Arrow /></span>}
    </>
  );
  return ready ? (
    <a ref={linkRef} className={`st-tile st-game${game.wide ? ' st-game--wide' : ''}`} href={game.href} style={style} onPointerMove={tilt} onPointerLeave={untilt}>{body}</a>
  ) : (
    <div ref={boxRef} className={`st-tile st-game is-soon${game.wide ? ' st-game--wide' : ''}`} style={style} aria-disabled="true">{body}</div>
  );
}

function Arrow() {
  return <svg className="st-arrow" viewBox="0 0 16 16" aria-hidden="true"><path d="M2 8h11M9 3.5 13.5 8 9 12.5" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}

function Crewmate({ color, index }: { color: string; index: number }) {
  const style = { '--d': `${index * -2.3}s`, '--x': `${8 + ((index * 37) % 84)}%`, '--y': `${4 + ((index * 53) % 40)}%`, '--s': `${0.7 + ((index * 17) % 5) / 10}` } as CSSProperties;
  return (
    <svg className="st-crew" style={style} viewBox="0 0 44 50">
      <rect x="2" y="17" width="10" height="19" rx="3.5" fill={color} stroke="#120f24" strokeWidth="2.6" />
      <path d="M9 18C9 8 15.5 3 23 3s14 5 14 15v24c0 3-1.8 5-4.2 5h-4.6c-2.3 0-3.7-1.6-3.7-4v-4h-5v4c0 2.4-1.4 4-3.7 4h-2.6C10.8 47 9 45 9 42Z" fill={color} stroke="#120f24" strokeWidth="2.6" strokeLinejoin="round" />
      <path d="M33 22c2 0 4-1 4-3v23c0 3-1.8 5-4.2 5h-1Z" fill="#000" opacity=".2" />
      <rect x="21" y="11" width="20" height="11.5" rx="5.75" fill="#9ccfe2" stroke="#120f24" strokeWidth="2.6" />
      <rect x="29" y="13.3" width="8" height="3.4" rx="1.7" fill="#fff" opacity=".8" />
    </svg>
  );
}

/** Bunte Formen im Hintergrund, die langsam treiben und dem Zeiger ein Stück ausweichen. */
function Confetti() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el || reduced()) return;
    let frame = 0;
    const move = (e: globalThis.PointerEvent) => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        el.style.setProperty('--mx', String(e.clientX / window.innerWidth - 0.5));
        el.style.setProperty('--my', String(e.clientY / window.innerHeight - 0.5));
      });
    };
    window.addEventListener('pointermove', move, { passive: true });
    return () => { window.removeEventListener('pointermove', move); cancelAnimationFrame(frame); };
  }, []);
  const shapes = ['#ff4f81', '#ffd23f', '#2fd27a', '#2f7cf6', '#8b5cff', '#16b9e8', '#ff7a1a', '#ef3b3b', '#b8ff3a', '#ff6fae', '#ffd23f', '#2f7cf6'];
  return (
    <div className="st-confetti" ref={ref} aria-hidden="true">
      {shapes.map((c, i) => (
        <i key={i} className={`st-shape st-shape--${i % 4}`} style={{ '--c': c, '--i': i, left: `${(i * 83) % 100}%`, top: `${(i * 47) % 100}%` } as CSSProperties} />
      ))}
    </div>
  );
}
