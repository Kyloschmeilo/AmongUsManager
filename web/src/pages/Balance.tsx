// Balancing der Rollen mit The Other Roles (/api/balance): Häufigkeit und Siegquote je Rolle, verglichen mit dem
// Rest des eigenen Teams. Punkt = Siegquote, Strich = 95-%-Bereich, senkrechte Marke = Quote des restlichen Teams.
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { useApi, type Team } from '../lib/api';
import { duration, num, pct } from '../lib/format';
import { TEAM_COLOR, TEAM_LABEL, endReason, teamPillClass } from '../lib/labels';
import { Async, Empty, Segmented } from '../components/ui';
import { Reveal, Title } from '../components/motion';
import '../balance.css';

type Verdict = 'strong' | 'weak' | 'trend_strong' | 'trend_weak' | 'ok' | 'few';

interface BalanceRow {
  id: string;
  name: string;
  team: Team;
  mod: boolean;
  games: number;
  rounds: number;
  share: number;
  players: number;
  wins: number;
  winRate: number;
  low: number;
  high: number;
  baseline: number | null;
  delta: number | null;
  verdict: Verdict;
  kills: number;
  survived: number;
  killed: number;
  ejected: number;
  guessed: number;
}

interface BalancePeriod {
  rounds: number;
  from: string | null;
  to: string | null;
  avgDuration: number;
  winners: Record<'CREW' | 'IMPOSTOR' | 'NEUTRAL' | 'UNKNOWN', number>;
  teams: { team: Team; games: number; wins: number }[];
  specialWins: { reason: string; count: number }[];
  roles: BalanceRow[];
  modifiers: BalanceRow[];
}

interface BalanceResponse {
  minGames: number;
  trend: number;
  recentRounds: number;
  all: BalancePeriod;
  recent: BalancePeriod;
}

type Period = 'all' | 'recent';
type Sort = 'delta' | 'share' | 'winRate';
type Scope = 'tor' | 'all';

const VERDICT_LABEL: Record<Verdict, string> = {
  strong: 'Zu stark',
  weak: 'Zu schwach',
  trend_strong: 'Eher stark',
  trend_weak: 'Eher schwach',
  ok: 'Ausgeglichen',
  few: 'Zu wenig Daten'
};

const VERDICT_CLASS: Record<Verdict, string> = {
  strong: 'pill pill--gold',
  weak: 'pill pill--sky',
  trend_strong: 'pill pill--plain verdict--up',
  trend_weak: 'pill pill--plain verdict--down',
  ok: 'pill pill--plain',
  few: 'pill pill--plain verdict--few'
};

const TEAMS: Team[] = ['CREW', 'IMPOSTOR', 'NEUTRAL'];

const signed = (value: number | null): string => {
  if (value == null) return '–';
  const points = Math.round(value * 100);
  return `${points > 0 ? '+' : points < 0 ? '−' : '±'}${Math.abs(points)} Pkt.`;
};

export function BalancePage() {
  const api = useApi<BalanceResponse>('balance');
  const [period, setPeriod] = useState<Period>('all');
  return (
    <div className="page page--tight">
      <div className="page-head">
        <Link className="text-link" to="/rollen">
          <ArrowLeft className="icon icon--sm" aria-hidden="true" />
          Alle Rollen
        </Link>
        <Title text="Balancing" className="page-title" />
        <p className="lead">
          Wie oft kommt jede Rolle von The Other Roles vor, und gewinnt sie öfter als der Rest ihres Teams? Gezählt werden
          nur Runden mit The Other Roles und die Rolle zu Rundenbeginn.
        </p>
        {api.data && (
          <div className="row">
            <Segmented<Period>
              label="Zeitraum"
              value={period}
              onChange={setPeriod}
              options={[
                { value: 'all', label: `Alle Runden ${num(api.data.all.rounds)}` },
                { value: 'recent', label: `Letzte ${api.data.recentRounds}` }
              ]}
            />
          </div>
        )}
      </div>
      <Async state={api}>
        {(data) =>
          data[period].rounds === 0 ? (
            <Empty text="Noch keine Runde mit The Other Roles." hint="Sobald ein Host mit CrewStats und The Other Roles spielt, erscheinen hier die Rollen." />
          ) : (
            <BalanceBody data={data} period={data[period]} />
          )
        }
      </Async>
    </div>
  );
}

function BalanceBody({ data, period }: { data: BalanceResponse; period: BalancePeriod }) {
  const [sort, setSort] = useState<Sort>('delta');
  const [scope, setScope] = useState<Scope>('tor');
  const rated = period.roles.filter((r) => r.mod && r.verdict !== 'few' && r.delta != null);
  const strongest = [...rated].sort((a, b) => (b.delta ?? 0) - (a.delta ?? 0)).filter((r) => (r.delta ?? 0) > 0).slice(0, 3);
  const weakest = [...rated].sort((a, b) => (a.delta ?? 0) - (b.delta ?? 0)).filter((r) => (r.delta ?? 0) < 0).slice(0, 3);
  const roles = period.roles.filter((r) => scope === 'all' || r.mod);
  return (
    <>
      <Reveal>
        <TeamOverview period={period} />
      </Reveal>
      <Reveal>
        <section className="stack balance-callouts">
          <h2 className="panel__title">Auffällig</h2>
          {strongest.length + weakest.length === 0 ? (
            <p className="caption">
              Noch keine Rolle mit mindestens {data.minGames} Runden weicht deutlich vom Team ab.
            </p>
          ) : (
            <div className="grid grid--2">
              <Callout title="Gewinnt öfter als das Team" rows={strongest} empty="Keine Rolle deutlich über dem Team." />
              <Callout title="Gewinnt seltener als das Team" rows={weakest} empty="Keine Rolle deutlich unter dem Team." />
            </div>
          )}
        </section>
      </Reveal>
      <Reveal>
        <section className="stack">
          <div className="row">
            <h2 className="panel__title">Alle Rollen</h2>
            <span className="spacer" />
            <Segmented<Scope>
              label="Rollen"
              value={scope}
              onChange={setScope}
              options={[
                { value: 'tor', label: 'Nur The Other Roles' },
                { value: 'all', label: 'Mit Crewmate/Impostor' }
              ]}
            />
            <Segmented<Sort>
              label="Sortierung"
              value={sort}
              onChange={setSort}
              options={[
                { value: 'delta', label: 'Abweichung' },
                { value: 'share', label: 'Häufigkeit' },
                { value: 'winRate', label: 'Siegquote' }
              ]}
            />
          </div>
          <Legend />
          {TEAMS.map((team) => (
            <TeamBlock key={team} team={team} rows={roles.filter((r) => r.team === team)} sort={sort} />
          ))}
          <p className="caption balance-note">
            Verglichen wird mit allen anderen Spielern desselben Teams im gewählten Zeitraum. „Zu stark“/„Zu schwach“ heißt:
            auch der 95-%-Bereich liegt ganz über bzw. unter der Team-Quote. „Eher stark/schwach“: mindestens{' '}
            {Math.round(data.trend * 100)} Prozentpunkte Abstand, aber noch nicht sicher. Unter {data.minGames} Runden gibt es keine
            Einschätzung. Spielt eine Rolle fast immer dieselbe Person, misst die Quote eher die Person als die Rolle.
          </p>
        </section>
      </Reveal>
      {period.modifiers.length > 0 && (
        <Reveal>
          <section className="stack">
            <h2 className="panel__title">Modifikatoren</h2>
            <p className="caption">Verglichen mit allen Spielern ohne diesen Modifikator.</p>
            <RoleTable rows={period.modifiers} modifier />
          </section>
        </Reveal>
      )}
    </>
  );
}

function TeamOverview({ period }: { period: BalancePeriod }) {
  const total = period.rounds;
  const w = period.winners;
  const parts: { team: Team; count: number }[] = [
    { team: 'CREW', count: w.CREW },
    { team: 'IMPOSTOR', count: w.IMPOSTOR },
    { team: 'NEUTRAL', count: w.NEUTRAL },
    { team: 'UNKNOWN', count: w.UNKNOWN }
  ];
  return (
    <section className="stack">
      <div className="grid--stats">
        <div className="stat"><span className="eyebrow">Runden</span><b className="stat__value">{num(total)}</b><span className="caption">Ø {duration(period.avgDuration)}</span></div>
        {TEAMS.map((team) => (
          <div className="stat" key={team}>
            <span className="eyebrow">{team === 'NEUTRAL' ? 'Sondersiege' : `${TEAM_LABEL[team]} gewinnt`}</span>
            <b className="stat__value">{pct(w[team as 'CREW'], total)}</b>
            <span className="caption">{num(w[team as 'CREW'])} Runden</span>
          </div>
        ))}
      </div>
      <div className="split balance-split" role="img" aria-label={parts.filter((p) => p.count).map((p) => `${TEAM_LABEL[p.team]} ${pct(p.count, total)}`).join(', ')}>
        {parts.filter((p) => p.count > 0).map((p) => (
          <div key={p.team} style={{ flex: p.count, background: TEAM_COLOR[p.team] }} title={`${TEAM_LABEL[p.team]}: ${num(p.count)} von ${num(total)}`} />
        ))}
      </div>
      {period.specialWins.length > 0 && (
        <div className="row">
          <span className="eyebrow">Sondersiege</span>
          {period.specialWins.map((s) => (
            <span key={s.reason} className="pill pill--neutral pill--case">{endReason(s.reason)} · {num(s.count)}</span>
          ))}
        </div>
      )}
    </section>
  );
}

function Callout({ title, rows, empty }: { title: string; rows: BalanceRow[]; empty: string }) {
  return (
    <div className="panel">
      <span className="eyebrow">{title}</span>
      {rows.length === 0 ? (
        <p className="caption">{empty}</p>
      ) : (
        <ol className="balance-callout">
          {rows.map((r) => (
            <li key={r.id}>
              <span className={teamPillClass(r.team)}><span className="pill__dot" />{TEAM_LABEL[r.team]}</span>
              <b>{r.name}</b>
              <span className="spacer" />
              <span className="balance-callout__num">{pct(r.wins, r.games)}</span>
              <span className="caption">{signed(r.delta)} · {num(r.games)} Runden</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function Legend() {
  return (
    <div className="row balance-legend caption" aria-hidden="true">
      <span><i className="balance-legend__dot" /> Siegquote</span>
      <span><i className="balance-legend__ci" /> 95-%-Bereich</span>
      <span><i className="balance-legend__base" /> Rest des Teams</span>
    </div>
  );
}

function sortRows(rows: BalanceRow[], sort: Sort): BalanceRow[] {
  const few = (r: BalanceRow) => (r.verdict === 'few' ? 1 : 0);
  return [...rows].sort((a, b) => {
    if (sort === 'share') return b.share - a.share || b.games - a.games;
    if (sort === 'winRate') return few(a) - few(b) || b.winRate - a.winRate;
    return few(a) - few(b) || Math.abs(b.delta ?? 0) - Math.abs(a.delta ?? 0) || b.games - a.games;
  });
}

function TeamBlock({ team, rows, sort }: { team: Team; rows: BalanceRow[]; sort: Sort }) {
  const sorted = useMemo(() => sortRows(rows, sort), [rows, sort]);
  if (!rows.length) return null;
  return (
    <div className="balance-team">
      <h3 className="balance-team__title">
        <span className={teamPillClass(team)}><span className="pill__dot" />{TEAM_LABEL[team]}</span>
        <span className="caption">{rows.length} Rollen</span>
      </h3>
      <div className="balance-rows" role="list">
        <div className="balance-row balance-row--head" aria-hidden="true">
          <span>Rolle</span>
          <span>Häufigkeit</span>
          <span className="balance-axis"><i>0 %</i><i>50 %</i><i>100 %</i></span>
          <span>Einschätzung</span>
        </div>
        {sorted.map((r) => <DotRow key={r.id} row={r} />)}
      </div>
    </div>
  );
}

function DotRow({ row }: { row: BalanceRow }) {
  const color = TEAM_COLOR[row.team];
  const tip = [
    `${row.name}: ${pct(row.wins, row.games)} Siege (${num(row.wins)} von ${num(row.games)})`,
    `95-%-Bereich ${Math.round(row.low * 100)}–${Math.round(row.high * 100)} %`,
    row.baseline != null ? `Rest des Teams ${Math.round(row.baseline * 100)} % (${signed(row.delta)})` : 'Kein Vergleich (einzige Rolle im Team)',
    `In ${pct(row.share, 1)} der Runden · ${num(row.players)} Spieler`,
    `Kills ${num(row.kills)} · überlebt ${pct(row.survived, row.games)} · rausgewählt ${pct(row.ejected, row.games)}${row.guessed ? ` · geraten ${pct(row.guessed, row.games)}` : ''}`
  ].join('\n');
  return (
    <div className={`balance-row${row.verdict === 'few' ? ' balance-row--few' : ''}`} role="listitem" title={tip}>
      <span className="balance-row__name">
        <b>{row.name}</b>
        <span className="caption">{num(row.games)} Runden · {num(row.players)} Spieler</span>
      </span>
      <span className="balance-share">
        <span className="balance-share__bar"><i style={{ width: `${Math.min(100, row.share * 100)}%` }} /></span>
        <span className="balance-share__num">{pct(row.share, 1)}</span>
      </span>
      <span className="balance-plot" aria-label={tip}>
        <i className="balance-plot__mid" />
        {row.baseline != null && <i className="balance-plot__base" style={{ left: `${row.baseline * 100}%` }} />}
        <i className="balance-plot__ci" style={{ left: `${row.low * 100}%`, width: `${Math.max(0.5, (row.high - row.low) * 100)}%`, background: color }} />
        <i className="balance-plot__dot" style={{ left: `${row.winRate * 100}%`, background: color }} />
        <b className="balance-plot__num">{pct(row.wins, row.games)}</b>
      </span>
      <span className="balance-row__verdict">
        <span className={VERDICT_CLASS[row.verdict]}>{VERDICT_LABEL[row.verdict]}</span>
        {row.delta != null && row.verdict !== 'few' && <span className="caption">{signed(row.delta)}</span>}
      </span>
    </div>
  );
}

function RoleTable({ rows, modifier }: { rows: BalanceRow[]; modifier?: boolean }) {
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>{modifier ? 'Modifikator' : 'Rolle'}</th>
            <th className="num">Runden</th>
            <th className="num">Häufigkeit</th>
            <th className="num">Siegquote</th>
            <th className="num">Vergleich</th>
            <th>Einschätzung</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td><b>{r.name}</b></td>
              <td className="num">{num(r.games)}</td>
              <td className="num">{pct(r.share, 1)}</td>
              <td className="num">{pct(r.wins, r.games)}</td>
              <td className="num">{r.baseline != null ? `${Math.round(r.baseline * 100)} % (${signed(r.delta)})` : '–'}</td>
              <td><span className={VERDICT_CLASS[r.verdict]}>{VERDICT_LABEL[r.verdict]}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
