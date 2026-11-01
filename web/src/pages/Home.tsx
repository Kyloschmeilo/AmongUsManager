import { useRef } from 'react';
import { Link } from 'react-router-dom';
import { Clock, Crosshair, Map as MapIcon, Skull, Trophy, Users, Vote } from 'lucide-react';
import { useApi, type MapDetail, type Overview, type PlayerSummary, type RecordEntry } from '../lib/api';
import { date, distance as dist, duration, nightLabel, num, pct } from '../lib/format';
import { RECORD_LABEL, TEAM_COLOR, TEAM_LABEL, endReason, mapShort } from '../lib/labels';
import { metric, rank } from '../lib/metrics';
import { Async, Avatar, BarList, Empty, Leaderboard, RoundList, Section, StatCard } from '../components/ui';
import { Reveal, Title, useGrow } from '../components/motion';
import { MapView } from '../components/MapView';

export function Home() {
  const overview = useApi<Overview>('overview');
  const players = useApi<{ players: PlayerSummary[] }>('players');
  return (
    <div className="page">
      <Async state={overview}>
        {(o) => (
          <>
            <Hero overview={o} />
            {o.rounds === 0 ? (
              <Empty
                text="Noch keine Runden aufgezeichnet."
                hint="Sobald ein Host mit CrewStats eine Runde zu Ende spielt, erscheint sie hier."
              />
            ) : (
              <>
                <div className="grid grid--stats">
                  <StatCard icon={<Trophy className="icon" />} label="Runden" value={o.rounds} chip={o.firstRoundAt ? `seit ${date(o.firstRoundAt)}` : undefined} caption={`${num(o.replays)} davon mit Replay`} />
                  <StatCard icon={<Users className="icon" />} label="Spieler" value={o.players} chip="alle" caption={`im Schnitt ${duration(o.totals.avgDuration)} pro Runde`} />
                  <StatCard icon={<Skull className="icon" />} label="Kills" value={o.totals.kills} chip="gesamt" caption={`${num(o.totals.ejections)} Mal rausgewählt`} />
                  <StatCard icon={<Clock className="icon" />} label="Spielzeit" value={0} raw={duration(o.totals.playSeconds)} chip="gesamt" caption={`${num(o.totals.tasks)} Aufgaben erledigt`} />
                </div>
                <Winners overview={o} />
                {players.data && <Boards players={players.data.players} />}
                {o.records.length > 0 && <Records records={o.records} />}
                {o.activity.length > 1 && <Activity overview={o} />}
                <Section eyebrow="Zuletzt gespielt" title="Letzte Runden" action={<Link className="btn" to="/runden">Alle Runden</Link>}>
                  <RoundList rounds={o.recent} />
                </Section>
                <Section eyebrow="Wo gespielt wird" title="Maps" action={<Link className="btn" to="/maps">Zu den Maps</Link>}>
                  <div className="grid grid--3">
                    {o.maps.map((m) => (
                      <Link key={m.mapId} to={`/maps/${m.mapId}`} className="panel mapcard">
                        <span className="eyebrow">{num(m.rounds)} Runden</span>
                        <h3>{m.map}</h3>
                        <TeamSplit crew={m.crewWins} impostor={m.impostorWins} neutral={m.neutralWins} />
                        <span className="caption">Crew gewinnt {pct(m.crewWins, m.rounds)} · im Schnitt {duration(m.avgDuration)}</span>
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

function Hero({ overview }: { overview: Overview }) {
  const top = overview.maps.find((m) => m.layout) ?? null;
  return (
    <section className="hero">
      <div className="hero__text">
        <span className="eyebrow">Among Us · Spieleabend</span>
        <Title text="Crew Stats" className="hero__title" />
        <p className="lead">
          Jede Runde vom Spieleabend, automatisch aufgezeichnet von CrewStats: wer gewinnt, wer tötet, wer richtig abstimmt
          und wer am meisten läuft.
        </p>
        <div className="row">
          <span className="pill pill--mono">{num(overview.rounds)} Runden</span>
          <span className="pill pill--mono">{num(overview.players)} Spieler</span>
          {overview.lastRoundAt && <span className="pill pill--plain">Zuletzt am {date(overview.lastRoundAt)}</span>}
        </div>
        <div className="row">
          <Link className="btn btn--primary" to="/bestenliste">Zur Bestenliste</Link>
          <Link className="btn" to="/runden">Runden ansehen</Link>
        </div>
      </div>
      <Reveal delay={0.15}>{top ? <HeroMap mapId={top.mapId} /> : <HeroBlocks />}</Reveal>
    </section>
  );
}

/** Grundriss der meistgespielten Map als Heatmap aus allen Laufwegen, mit den Kill-Orten. */
function HeroMap({ mapId }: { mapId: number }) {
  const map = useApi<MapDetail>(`maps/${mapId}`);
  if (!map.data || map.data.cells.length < 30) return <HeroBlocks />;
  const m = map.data;
  return (
    <div className="stack">
      <MapView layout={m} heat showRooms={false} showVents={false} label={`Laufwege auf ${m.map.map}`}>
        {m.kills.slice(-400).map((k, i) => (
          <rect key={i} x={k.x - 0.22} y={-k.y - 0.22} width={0.44} height={0.44} rx={0.06} fill="var(--ember)" />
        ))}
      </MapView>
      <span className="caption">
        {m.map.map}: wo alle hinlaufen (grün) und wo gestorben wird (orange), aus {num(m.map.rounds)} Runden.
      </span>
    </div>
  );
}

/** Ohne Laufwege: gestufte Blöcke wie im Standard-Design. */
function HeroBlocks() {
  const columns = [2, 2, 3, 3, 4, 3, 4, 5, 5, 6];
  return (
    <svg viewBox="0 0 480 288" className="hero__art" role="img" aria-label="Blöcke">
      {columns.map((height, c) =>
        Array.from({ length: height }, (_, r) => {
          const top = r === height - 1;
          const fill = r < 2 ? 'var(--moss)' : top && c === 7 ? 'var(--gold)' : top && c === 5 ? 'var(--ember)' : 'var(--accent)';
          return <rect key={`${c}-${r}`} x={2 + c * 48} y={242 - r * 48} width={44} height={44} rx={4} fill={fill} />;
        })
      )}
    </svg>
  );
}

function TeamSplit({ crew, impostor, neutral }: { crew: number; impostor: number; neutral: number }) {
  const total = Math.max(1, crew + impostor + neutral);
  return (
    <div className="split" aria-label={`Crew ${crew}, Impostor ${impostor}, Neutral ${neutral}`}>
      {crew > 0 && <div style={{ width: `${(crew / total) * 100}%`, background: TEAM_COLOR.CREW }} />}
      {impostor > 0 && <div style={{ width: `${(impostor / total) * 100}%`, background: TEAM_COLOR.IMPOSTOR }} />}
      {neutral > 0 && <div style={{ width: `${(neutral / total) * 100}%`, background: TEAM_COLOR.NEUTRAL }} />}
    </div>
  );
}

function Winners({ overview }: { overview: Overview }) {
  const t = overview.teams;
  const decided = t.CREW + t.IMPOSTOR + t.NEUTRAL;
  return (
    <Section eyebrow="Ergebnisse" title={<>Wer <em>gewinnt</em>?</>}>
      <div className="grid grid--2">
        <div className="panel panel--pad stack" style={{ gap: 20 }}>
          <h3 className="panel__title" style={{ margin: 0 }}><Trophy className="icon" />Siege nach Team</h3>
          <div className="row" style={{ gap: 32, alignItems: 'flex-end' }}>
            {(['CREW', 'IMPOSTOR', 'NEUTRAL'] as const).filter((team) => team !== 'NEUTRAL' || t.NEUTRAL > 0).map((team) => (
              <div key={team} className="stack" style={{ gap: 4 }}>
                <span className="eyebrow">{TEAM_LABEL[team]}</span>
                <span className="mono" style={{ fontSize: 28, lineHeight: '32px', color: TEAM_COLOR[team] }}>{pct(t[team], decided)}</span>
                <span className="caption">{num(t[team])} Runden</span>
              </div>
            ))}
          </div>
          <TeamSplit crew={t.CREW} impostor={t.IMPOSTOR} neutral={t.NEUTRAL} />
          {t.UNKNOWN > 0 && <span className="caption">{num(t.UNKNOWN)} Runden ohne Ergebnis (abgebrochen).</span>}
        </div>
        <div className="panel panel--pad">
          <h3 className="panel__title"><Vote className="icon" />Wie Runden enden</h3>
          <BarList
            items={overview.endReasons.slice(0, 7).map((r) => ({
              label: endReason(r.reason),
              value: r.count,
              text: `${num(r.count)} · ${pct(r.count, overview.rounds)}`,
              tone: r.reason.startsWith('Impostor') ? 'ember' : r.reason.startsWith('Humans') ? 'accent' : 'gold'
            }))}
          />
        </div>
      </div>
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
    <Section eyebrow="Bestenliste" title="Ganz vorne" action={<Link className="btn" to="/bestenliste">Ganze Bestenliste</Link>}>
      <div className="grid grid--3">
        {boards.map((b) => {
          const m = metric(b.id);
          return (
            <Leaderboard
              key={b.id}
              icon={b.icon}
              title={m.label}
              rows={rank(players, m).slice(0, 5).map((p) => ({ player: p, value: m.format(p) }))}
              more={{ to: `/bestenliste?nach=${m.id}`, label: 'Mehr anzeigen' }}
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
    <Section eyebrow="Rekorde" title="Für die Geschichtsbücher">
      <div className="grid grid--3">
        {records.filter((r) => RECORD_LABEL[r.id]).map((r) => (
          <Link key={r.id} to={`/runden/${r.round.gameId}`} className="panel record mapcard" style={{ gap: 8 }}>
            <span className="eyebrow">{RECORD_LABEL[r.id].title}</span>
            <span className="record__value">{recordValue(r)}</span>
            <span className="record__who">
              {r.player ? (
                <>
                  <Avatar colorId={r.player.colorId} playerKey={r.player.key} src={r.player.avatar} size="sm" />
                  <span className="ink">{r.player.name}</span>
                  <span className="faint">· {mapShort(r.round.mapId, r.round.map)}, {date(r.round.endedAt)}</span>
                </>
              ) : (
                <>
                  <MapIcon className="icon icon--sm" style={{ color: 'var(--ink-faint)' }} />
                  <span className="ink">{mapShort(r.round.mapId, r.round.map)}</span>
                  <span className="faint">· {date(r.round.endedAt)}</span>
                </>
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
    <Section eyebrow="Aktivität" title="Runden pro Spieleabend">
      <div className="panel panel--pad">
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
          <span>höchstens {max} Runden an einem Abend</span>
          <span>{nightLabel(days[days.length - 1].date)}</span>
        </div>
      </div>
    </Section>
  );
}
