import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useApi, type PlayerSummary } from '../lib/api';
import { METRICS, metric, rank } from '../lib/metrics';
import { ago, num } from '../lib/format';
import { Async, Avatar, Empty } from '../components/ui';
import { Reveal, Title } from '../components/motion';

export function Leaderboard() {
  const players = useApi<{ players: PlayerSummary[] }>('players');
  const [params, setParams] = useSearchParams();
  const current = metric(params.get('nach') ?? 'wins');
  const [query, setQuery] = useState('');

  return (
    <div className="page page--tight">
      <div className="stack" style={{ gap: 16 }}>
        <span className="eyebrow">Bestenliste</span>
        <Title text="Wer ist vorne?" className="page-title" />
        <p className="lead">Alle Spieler über alle aufgezeichneten Runden. Quoten zählen erst ab ein paar Runden, damit eine Glücksrunde nicht reicht.</p>
      </div>
      <Async state={players}>
        {(data) => (
          <Reveal>
            <div className="panel" style={{ overflow: 'hidden' }}>
              <div className="tabs" role="tablist" aria-label="Kennzahl">
                {(['Allgemein', 'Crew', 'Impostor', 'Details'] as const).map((group) => (
                  <Group key={group} group={group} current={current.id} onSelect={(id) => setParams({ nach: id }, { replace: true })} />
                ))}
              </div>
              <div className="row" style={{ padding: '16px 24px', borderBottom: '1px solid var(--border)' }}>
                <div className="stack" style={{ gap: 2 }}>
                  <span className="ink" style={{ fontWeight: 600 }}>{current.label}</span>
                  <span className="caption">{current.description}{current.minLabel ? ` Zählt ${current.minLabel}.` : ''}</span>
                </div>
                <span className="spacer" />
                <label className="search">
                  <Search className="icon icon--sm" aria-hidden="true" />
                  <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Spieler suchen" aria-label="Spieler suchen" />
                </label>
              </div>
              <Table players={data.players} metricId={current.id} query={query} />
            </div>
          </Reveal>
        )}
      </Async>
    </div>
  );
}

function Group({ group, current, onSelect }: { group: string; current: string; onSelect: (id: string) => void }) {
  return (
    <>
      <span className="tabs__group">{group.toUpperCase()}</span>
      {METRICS.filter((m) => m.group === group).map((m) => (
        <button key={m.id} className="tab" role="tab" aria-selected={m.id === current} onClick={() => onSelect(m.id)}>
          {m.label}
        </button>
      ))}
    </>
  );
}

function Table({ players, metricId, query }: { players: PlayerSummary[]; metricId: string; query: string }) {
  const m = metric(metricId);
  const ranked = useMemo(() => rank(players, m), [players, m]);
  const q = query.trim().toLowerCase();
  const rows = ranked.map((p, i) => ({ p, place: i + 1 })).filter(({ p }) => !q || p.name.toLowerCase().includes(q));
  const missing = players.length - ranked.length;
  if (!ranked.length) return <div style={{ padding: 24 }}><Empty text="Hier ist noch niemand." hint={m.minLabel ? `Es zählt nur, wer ${m.minLabel} hat.` : undefined} /></div>;
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th style={{ width: 56 }}>Rang</th>
            <th>Spieler</th>
            <th className="num">{m.label}</th>
            <th className="num">Details</th>
            <th className="num">Runden</th>
            <th className="num">Zuletzt</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ p, place }) => (
            <tr key={p.key} className={place === 1 ? 'first' : undefined}>
              <td><span className={`rank rank--${place}`}>{place}</span></td>
              <td>
                <Link className="player" to={`/spieler/${p.key}`}>
                  <Avatar colorId={p.colorId} playerKey={p.key} />
                  <span className="player__name">{p.name}</span>
                  {p.level != null && <span className="chip mono" style={{ fontSize: 11 }}>Lvl {p.level}</span>}
                </Link>
              </td>
              <td className="num hi" style={{ fontSize: 14 }}>{m.format(p)}</td>
              <td className="num">{m.detail(p)}</td>
              <td className="num">{num(p.games)}</td>
              <td className="num">{ago(p.lastSeen)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {missing > 0 && <p className="caption" style={{ padding: '12px 16px' }}>{num(missing)} weitere Spieler haben dafür noch zu wenige Runden.</p>}
    </div>
  );
}
