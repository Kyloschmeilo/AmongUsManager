// Bestenliste: erst die Gruppe wählen (Allgemein, Crew, Impostor, Unterwegs), dann eine Kennzahl –
// je Kennzahl ein Kärtchen mit den Top 3, darunter Podest und die ganze Rangliste.
import { useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Cigarette, Crosshair, Footprints, Search, ShieldCheck, Trophy } from 'lucide-react';
import { useApi, type PlayerSummary } from '../lib/api';
import { GROUPS, METRICS, metric, rank, type Metric } from '../lib/metrics';
import { useMe } from '../lib/me';
import { ago, num } from '../lib/format';
import { Async, Avatar, Empty } from '../components/ui';
import { Reveal, Title, useStagger } from '../components/motion';

const GROUP_ICON: Record<Metric['group'], ReactNode> = {
  Allgemein: <Trophy className="icon" aria-hidden="true" />,
  Crew: <ShieldCheck className="icon" aria-hidden="true" />,
  Impostor: <Crosshair className="icon" aria-hidden="true" />,
  Details: <Footprints className="icon" aria-hidden="true" />,
  Crack: <Cigarette className="icon" aria-hidden="true" />
};

export function Leaderboard() {
  const players = useApi<{ players: PlayerSummary[] }>('players');
  const [params, setParams] = useSearchParams();
  const current = metric(params.get('nach') ?? 'wins');
  const select = (id: string) => setParams({ nach: id }, { replace: true, preventScrollReset: true });

  return (
    <div className="page page--tight">
      <div className="page-head">
        <Title text="Bestenliste" className="page-title" />
        <p className="lead">Erst einen Bereich wählen, dann eine Kennzahl. Quoten zählen erst ab ein paar Runden, damit eine Glücksrunde nicht reicht.</p>
        <Link className="board__more" to="/vergleich">Zwei Spieler direkt vergleichen</Link>
      </div>
      <Async state={players}>
        {(data) => (
          <>
            <Reveal>
              <div className="lb-groups" role="tablist" aria-label="Bereich">
                {GROUPS.map((g) => (
                  <button
                    key={g.id}
                    type="button"
                    role="tab"
                    aria-selected={g.id === current.group}
                    className="lb-group"
                    onClick={() => select(METRICS.find((m) => m.group === g.id)!.id)}
                  >
                    {GROUP_ICON[g.id]}
                    <span>
                      {g.label}
                      <small>{g.caption}</small>
                    </span>
                  </button>
                ))}
              </div>
            </Reveal>
            <MetricCards players={data.players} current={current} onSelect={select} />
            <Ranking players={data.players} current={current} />
          </>
        )}
      </Async>
    </div>
  );
}

/** Ein Kärtchen je Kennzahl der Gruppe mit den Top 3 – Klick zeigt die ganze Rangliste dazu. */
function MetricCards({ players, current, onSelect }: { players: PlayerSummary[]; current: Metric; onSelect: (id: string) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useStagger(ref, [current.group]);
  return (
    <div className="grid lb-cards" ref={ref}>
      {METRICS.filter((m) => m.group === current.group).map((m) => {
        const top = rank(players, m).slice(0, 3);
        return (
          <button key={m.id} type="button" data-stagger className="panel lb-card" aria-pressed={m.id === current.id} onClick={() => onSelect(m.id)}>
            <span className="lb-card__title">
              {m.label}
              {m.minLabel && <span className="caption">{m.minLabel}</span>}
            </span>
            {top.length === 0 ? (
              <span className="caption">Noch niemand.</span>
            ) : (
              <ol>
                {top.map((p, i) => (
                  <li key={p.key}>
                    <span className={`rank rank--${i + 1}`}>{i + 1}</span>
                    <Avatar colorId={p.colorId} playerKey={p.key} src={p.avatar} size="sm" />
                    <span className="n">{p.name}</span>
                    <span className="v">{m.format(p)}</span>
                  </li>
                ))}
              </ol>
            )}
          </button>
        );
      })}
    </div>
  );
}

function Ranking({ players, current }: { players: PlayerSummary[]; current: Metric }) {
  const { me } = useMe();
  const [query, setQuery] = useState('');
  const ranked = useMemo(() => rank(players, current), [players, current]);
  const myKey = me?.player?.key ?? null;
  const myPlace = myKey ? ranked.findIndex((p) => p.key === myKey) + 1 : 0;
  const q = query.trim().toLowerCase();
  const rows = ranked
    .map((p, i) => ({ p, place: i + 1 }))
    .filter(({ p, place }) => (q ? p.name.toLowerCase().includes(q) : place > 3));
  const missing = players.length - ranked.length;
  const podiumRef = useRef<HTMLDivElement>(null);
  useStagger(podiumRef, [current.id]);

  return (
    <section className="section" aria-labelledby="rangliste">
      <div className="section__head">
        <div className="stack" style={{ gap: 6 }}>
          <span className="eyebrow">Rangliste · {GROUPS.find((g) => g.id === current.group)?.label}</span>
          <h2 className="section__title" id="rangliste">{current.label}</h2>
          <span className="caption">
            {current.description}
            {current.minLabel ? ` Zählt ${current.minLabel}.` : ''}
          </span>
        </div>
        <div className="row">
          {myPlace > 0 && <span className="chip mono">Du: Platz {myPlace}</span>}
          <label className="search">
            <Search className="icon icon--sm" aria-hidden="true" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Spieler suchen" aria-label="Spieler suchen" />
          </label>
        </div>
      </div>

      {ranked.length === 0 ? (
        <Empty text="Hier ist noch niemand." hint={current.minLabel ? `Es zählt nur, wer ${current.minLabel} hat.` : undefined} />
      ) : (
        <>
          <div className="podium" ref={podiumRef}>
            {ranked.slice(0, 3).map((p, i) => (
              <Link key={p.key} to={`/spieler/${p.key}`} data-stagger className={`panel podium__place podium__place--${i + 1}${p.key === myKey ? ' is-me' : ''}`}>
                <span className="podium__rank">Platz {i + 1}</span>
                <Avatar colorId={p.colorId} playerKey={p.key} src={p.avatar} size={i === 0 ? 'xl' : 'lg'} />
                <span className="podium__info">
                  <span className="podium__name">{p.name}</span>
                  <span className="caption">{current.detail(p)}</span>
                </span>
                <span className="podium__value">{current.format(p)}</span>
              </Link>
            ))}
          </div>

          {rows.length > 0 && (
            <div className="panel table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th style={{ width: 64 }}>Rang</th>
                    <th>Spieler</th>
                    <th className="num">{current.label}</th>
                    <th className="num">Details</th>
                    <th className="num">Runden</th>
                    <th className="num">Zuletzt</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(({ p, place }) => (
                    <tr key={p.key} className={`${place === 1 ? 'first' : ''}${p.key === myKey ? ' me' : ''}`}>
                      <td><span className={`rank rank--${place}`}>{place}</span></td>
                      <td>
                        <Link className="player" to={`/spieler/${p.key}`}>
                          <Avatar colorId={p.colorId} playerKey={p.key} src={p.avatar} />
                          <span className="player__name">{p.name}</span>
                          {p.key === myKey && <span className="pill" style={{ padding: '1px 8px' }}>Du</span>}
                          {p.level != null && <span className="chip mono" style={{ fontSize: 11 }}>Lvl {p.level}</span>}
                        </Link>
                      </td>
                      <td className="num hi" style={{ fontSize: 14 }}>{current.format(p)}</td>
                      <td className="num">{current.detail(p)}</td>
                      <td className="num">{num(p.games)}</td>
                      <td className="num">{ago(p.lastSeen)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {q && rows.length === 0 && <p className="caption">Niemand mit „{query.trim()}“ in dieser Rangliste.</p>}
          {missing > 0 && <p className="caption">{num(missing)} weitere Spieler haben dafür noch zu wenige Runden.</p>}
        </>
      )}
    </section>
  );
}
