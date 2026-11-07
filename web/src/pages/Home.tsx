import { useRef, type PointerEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Clock, Crosshair, Skull, Trophy, Users, Vote } from 'lucide-react';
import { useApi, type MapDetail, type Overview, type PlayerSummary, type RecordEntry } from '../lib/api';
import { date, distance as dist, duration, nightLabel, num, pct } from '../lib/format';
import { RECORD_LABEL, TEAM_COLOR, endReason, mapShort } from '../lib/labels';
import { metric, rank } from '../lib/metrics';
import { Async, Avatar, BarList, Empty, Leaderboard, RoundList, Section, StatCard } from '../components/ui';
import { Count, reducedMotion, useGrow } from '../components/motion';
import { MapView } from '../components/MapView';
import { CrewDrift } from '../components/CrewDrift';
import { LiveLobbyStrip } from './Lobbies';
import { CrackStrip } from '../components/Crack';

export function Home() {
  const overview = useApi<Overview>('overview');
  const players = useApi<{ players: PlayerSummary[] }>('players');
  return (
    <div className="page">
      <Async state={overview}>
        {(o) => (
          <>
            <Masthead overview={o} />
            <LiveLobbyStrip />
            <CrackStrip />
            {o.rounds === 0 ? (
              <Empty
                text="Noch keine Runden aufgezeichnet."
                hint="Sobald ein Host mit CrewStats eine Runde zu Ende spielt, steht sie hier."
              />
            ) : (
              <>
                {players.data && players.data.players.length > 0 && <CrewDrift players={players.data.players} />}
                <div className="grid grid--stats grid--tally">
                  <StatCard icon={<Trophy className="icon" />} label="Runden" value={o.rounds} caption={`${num(o.replays)} mit Replay`} />
                  <StatCard icon={<Users className="icon" />} label="Spieler" value={o.players} caption={`Runde dauert im Schnitt ${duration(o.totals.avgDuration)}`} />
                  <StatCard icon={<Skull className="icon" />} label="Kills" value={o.totals.kills} caption={`dazu ${num(o.totals.ejections)}× rausgewählt`} />
                  <StatCard icon={<Clock className="icon" />} label="Gespielt" value={0} raw={duration(o.totals.playSeconds)} caption={`${num(o.totals.tasks)} Aufgaben erledigt`} />
                </div>
                {players.data && <Boards players={players.data.players} />}
                <EndReasons overview={o} />
                {o.records.length > 0 && <Records records={o.records} />}
                {o.activity.length > 1 && <Activity overview={o} />}
                <Section title="Letzte Runden" action={<Link className="btn" to="/runden">Alle Runden</Link>}>
                  <RoundList rounds={o.recent} />
                </Section>
                <Section title="Maps" action={<Link className="btn" to="/maps">Zu den Maps</Link>}>
                  <div className="grid grid--3">
                    {o.maps.map((m) => (
                      <Link key={m.mapId} to={`/maps/${m.mapId}`} className="panel mapcard">
                        <h3>{m.map}</h3>
                        <TeamSplit crew={m.crewWins} impostor={m.impostorWins} neutral={m.neutralWins} />
                        <span className="caption">
                          {num(m.rounds)} Runden · Crew gewinnt {pct(m.crewWins, m.rounds)} · im Schnitt {duration(m.avgDuration)}
                        </span>
                      </Link>
                    ))}
                  </div>
                </Section>
              </>
            )}
          </>
        )}
      </Async>
    </div>
  );
}

/** Kopf der Startseite: Titel, ein Satz, worum es geht, und der Spielstand Crew gegen Impostor. */
function Masthead({ overview }: { overview: Overview }) {
  const top = overview.maps.find((m) => m.layout) ?? null;
  const t = overview.teams;
  return (
    <section className="masthead">
      <div className="masthead__text">
        <h1 className="masthead__title">
          Crew<span>Stats</span>
        </h1>
        <p className="lead">
          Unsere Among-Us-Runden vom Spieleabend
          {overview.firstRoundAt ? <>, mitgeschrieben seit dem {date(overview.firstRoundAt)}</> : null}. Wer gewinnt, wer
          tötet, wer beim Abstimmen richtig liegt.
        </p>
        {overview.rounds > 0 && (
          <div className="score">
            <div className="score__line" aria-label={`Crew ${t.CREW} Siege, Impostor ${t.IMPOSTOR} Siege`}>
              <div className="score__side score__side--crew">
                <span className="score__team">Crew</span>
                <span className="score__num"><Count value={t.CREW} /></span>
              </div>
              <span className="score__colon" aria-hidden="true">:</span>
              <div className="score__side score__side--imp">
                <span className="score__team">Impostor</span>
                <span className="score__num"><Count value={t.IMPOSTOR} /></span>
              </div>
            </div>
            <TeamSplit crew={t.CREW} impostor={t.IMPOSTOR} neutral={t.NEUTRAL} />
            <span className="score__note">
              Siege insgesamt
              {t.NEUTRAL > 0 ? `, dazu ${num(t.NEUTRAL)}× ein neutraler Sieg (z. B. Jester)` : ''}
              {t.UNKNOWN > 0 ? `, ${num(t.UNKNOWN)} abgebrochen` : ''}
              {overview.lastRoundAt ? `. Zuletzt gespielt am ${date(overview.lastRoundAt)}.` : '.'}
            </span>
          </div>
        )}
      </div>
      {top ? <HeroMap mapId={top.mapId} /> : <HeroBlocks />}
    </section>
  );
}

/** Grundriss der meistgespielten Map als Heatmap aus allen Laufwegen, mit den Kill-Orten. */
function HeroMap({ mapId }: { mapId: number }) {
  const map = useApi<MapDetail>(`maps/${mapId}`);
  if (!map.data || map.data.cells.length < 30) return <HeroBlocks />;
  const m = map.data;
  return (
    <figure className="masthead__map">
      <Vision>
      <MapView layout={m} heat showRooms={false} showVents={false} label={`Laufwege auf ${m.map.map}`}>
        {m.kills.slice(-400).map((k, i) => (
          <path key={i} d={`M${k.x - 0.24} ${-k.y - 0.24}l.48 .48m0 -.48l-.48 .48`} stroke="var(--ember)" strokeWidth={0.16} strokeLinecap="round" />
        ))}
      </MapView>
      </Vision>
      <figcaption>
        {m.map.map} aus {num(m.map.rounds)} Runden: je heller, desto öfter lief jemand dort lang. Jedes rote Kreuz ist ein Kill.
        Mit der Maus siehst du nur so weit wie ein Crewmate bei Licht aus.
      </figcaption>
    </figure>
  );
}

/**
 * Sichtfeld wie im Spiel: Solange die Maus (oder ein Finger) über der Karte ist, wird alles außerhalb eines Kreises
 * um den Zeiger abgedunkelt – so wie Among Us die Sicht nach Licht-Sabotage begrenzt.
 */
function Vision({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const move = (e: PointerEvent<HTMLDivElement>) => {
    const el = ref.current;
    if (!el || reducedMotion()) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty('--mx', `${e.clientX - r.left}px`);
    el.style.setProperty('--my', `${e.clientY - r.top}px`);
    el.classList.add('is-looking');
  };
  const leave = () => ref.current?.classList.remove('is-looking');
  return (
    <div className="vision" ref={ref} onPointerMove={move} onPointerLeave={leave} onPointerCancel={leave}>
      {children}
    </div>
  );
}

/** Ohne Laufwege: ein Crewmate-Umriss als Platzhalter. */
function HeroBlocks() {
  return (
    <svg viewBox="0 0 480 300" className="hero__art" role="img" aria-label="Crewmate">
      <g transform="translate(170 30)">
        <rect x="0" y="80" width="40" height="110" rx="14" fill="var(--surface-2)" />
        <path d="M30 90a75 75 0 0 1 150 0v150a16 16 0 0 1-16 16h-34v-46H80v46H46a16 16 0 0 1-16-16z" fill="var(--surface-1)" stroke="var(--rule)" strokeWidth="3" />
        <rect x="80" y="55" width="115" height="62" rx="31" fill="var(--accent-soft)" stroke="var(--accent)" strokeWidth="3" />
      </g>
    </svg>
  );
}

function TeamSplit({ crew, impostor, neutral }: { crew: number; impostor: number; neutral: number }) {
  const total = Math.max(1, crew + impostor + neutral);
  return (
    <div className="split" aria-label={`Crew ${crew}, Impostor ${impostor}, Neutral ${neutral}`}>
      {crew > 0 && <div className="split__crew" style={{ width: `${(crew / total) * 100}%`, background: TEAM_COLOR.CREW }} />}
      {impostor > 0 && <div className="split__imp" style={{ width: `${(impostor / total) * 100}%`, background: TEAM_COLOR.IMPOSTOR }} />}
      {neutral > 0 && <div className="split__neutral" style={{ width: `${(neutral / total) * 100}%`, background: TEAM_COLOR.NEUTRAL }} />}
    </div>
  );
}

function EndReasons({ overview }: { overview: Overview }) {
  return (
    <Section title="Wie Runden enden" eyebrow={`von ${num(overview.rounds)} Runden`}>
      <BarList
        items={overview.endReasons.slice(0, 7).map((r) => ({
          label: endReason(r.reason),
          value: r.count,
          text: `${num(r.count)} · ${pct(r.count, overview.rounds)}`,
          tone: r.reason.startsWith('Impostor') ? 'ember' : r.reason.startsWith('Humans') ? 'accent' : 'neutral'
        }))}
      />
    </Section>
  );
}

function Boards({ players }: { players: PlayerSummary[] }) {
  const boards = [
    { id: 'wins', icon: <Trophy className="icon" /> },
    { id: 'kills', icon: <Crosshair className="icon" /> },
    { id: 'votes', icon: <Vote className="icon" /> }
  ];
  return (
    <Section title="Bestenliste" eyebrow="Top 5" action={<Link className="btn" to="/bestenliste">Ganze Tabelle</Link>}>
      <div className="grid grid--3">
        {boards.map((b) => {
          const m = metric(b.id);
          return (
            <Leaderboard
              key={b.id}
              icon={b.icon}
              title={m.label}
              rows={rank(players, m).slice(0, 5).map((p) => ({ player: p, value: m.format(p) }))}
              more={{ to: `/bestenliste?nach=${m.id}`, label: `Alle nach ${m.label}` }}
            />
          );
        })}
      </div>
    </Section>
  );
}

function recordValue(r: RecordEntry): string {
  const label = RECORD_LABEL[r.id];
  if (!label) return num(r.value);
  if (label.unit === 'seconds') return duration(r.value);
  if (label.unit === 'distance') return dist(r.value);
  return num(r.value);
}

function Records({ records }: { records: RecordEntry[] }) {
  return (
    <Section title="Rekorde">
      <div className="records">
        {records.filter((r) => RECORD_LABEL[r.id]).map((r) => (
          <Link key={r.id} to={`/runden/${r.round.gameId}`} className="record">
            <span className="record__value">{recordValue(r)}</span>
            <span className="record__title">{RECORD_LABEL[r.id].title}</span>
            <span className="record__who">
              {r.player ? (
                <>
                  <Avatar colorId={r.player.colorId} playerKey={r.player.key} src={r.player.avatar} size="sm" />
                  <span className="ink">{r.player.name}</span>
                  <span className="faint">· {mapShort(r.round.mapId, r.round.map)}, {date(r.round.endedAt)}</span>
                </>
              ) : (
                <span className="faint">{mapShort(r.round.mapId, r.round.map)}, {date(r.round.endedAt)}</span>
              )}
            </span>
          </Link>
        ))}
      </div>
    </Section>
  );
}

function Activity({ overview }: { overview: Overview }) {
  const ref = useRef<HTMLDivElement>(null);
  const days = overview.activity.slice(-40);
  const max = Math.max(1, ...days.map((d) => d.rounds));
  useGrow(ref, 'y', [days.length]);
  return (
    <Section title="Runden pro Abend" eyebrow={`die letzten ${days.length} Spieleabende`}>
      <div>
        <div className="columns" ref={ref}>
          {days.map((d) => (
            <div className="columns__col" key={d.date} tabIndex={0} aria-label={`${nightLabel(d.date)}: ${d.rounds} Runden`}>
              <div data-grow style={{ height: `${Math.max(4, (d.rounds / max) * 100)}%` }} />
              <span className="tip">{nightLabel(d.date)} · {d.rounds} Runden</span>
            </div>
          ))}
        </div>
        <div className="columns__axis caption mono">
          <span>{nightLabel(days[0].date)}</span>
          <span>Rekord: {max} Runden an einem Abend</span>
          <span>{nightLabel(days[days.length - 1].date)}</span>
        </div>
      </div>
    </Section>
  );
}
