import { useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import { useApi, type RolesResponse, type Team } from '../lib/api';
import { num, pct } from '../lib/format';
import { TEAM_LABEL, teamPillClass } from '../lib/labels';
import { Async, Empty, Section, Segmented } from '../components/ui';
import { Reveal, Title } from '../components/motion';

type Filter = 'all' | Team;

export function RolesPage() {
  const roles = useApi<RolesResponse>('roles');
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  return (
    <div className="page page--tight">
      <div className="stack" style={{ gap: 16 }}>
        <span className="eyebrow">Rollen</span>
        <Title text="Welche Rolle gewinnt?" className="page-title" />
        <p className="lead">Alle Rollen, mit denen gespielt wurde – aus dem Spiel und aus The Other Roles. Gezählt wird die Rolle zu Rundenbeginn.</p>
      </div>
      <Async state={roles}>
        {(data) => (
          <>
            <Reveal>
              <div className="row">
                <Segmented<Filter>
                  label="Team"
                  value={filter}
                  onChange={setFilter}
                  options={[
                    { value: 'all', label: 'Alle' },
                    { value: 'CREW', label: 'Crew' },
                    { value: 'IMPOSTOR', label: 'Impostor' },
                    { value: 'NEUTRAL', label: 'Neutral' }
                  ]}
                />
                <span className="spacer" />
                <label className="search">
                  <Search className="icon icon--sm" aria-hidden="true" />
                  <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Rolle suchen" aria-label="Rolle suchen" />
                </label>
              </div>
            </Reveal>
            <RoleGrid data={data} filter={filter} query={query} />
            {data.modifiers.length > 0 && (
              <Section eyebrow="The Other Roles" title="Modifikatoren">
                <div className="panel table-wrap">
                  <table className="table">
                    <thead><tr><th>Modifikator</th><th className="num">Runden</th><th className="num">Siegquote</th><th>Was er macht</th></tr></thead>
                    <tbody>
                      {data.modifiers.map((m) => (
                        <tr key={m.id}>
                          <td className="main">{m.name}</td>
                          <td className="num">{num(m.games)}</td>
                          <td className="num hi">{pct(m.wins, m.games)}</td>
                          <td style={{ minWidth: 260 }}>{m.description ?? ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Section>
            )}
          </>
        )}
      </Async>
    </div>
  );
}

function RoleGrid({ data, filter, query }: { data: RolesResponse; filter: Filter; query: string }) {
  const q = query.trim().toLowerCase();
  const roles = useMemo(
    () => data.roles.filter((r) => (filter === 'all' || r.team === filter) && (!q || r.name.toLowerCase().includes(q))),
    [data, filter, q]
  );
  if (!roles.length) return <Empty text="Keine Rolle gefunden." />;
  return (
    <div className="grid grid--3">
      {roles.map((r) => (
        <div key={r.id} className="panel role-card">
          <div className="row" style={{ gap: 8 }}>
            {r.team && <span className={teamPillClass(r.team)}>{TEAM_LABEL[r.team]}</span>}
            {r.mod && <span className="pill pill--plain">The Other Roles</span>}
          </div>
          <h3>{r.name}</h3>
          <div className="role-card__nums">
            <div><span className="eyebrow">Runden</span><b>{num(r.games)}</b></div>
            <div><span className="eyebrow">Siegquote</span><b className="accent">{pct(r.wins, r.games)}</b></div>
            <div><span className="eyebrow">Kills</span><b>{num(r.kills)}</b></div>
            <div><span className="eyebrow">Überlebt</span><b>{pct(r.survived, r.games)}</b></div>
          </div>
          {r.description && <p style={{ fontSize: 14, lineHeight: '22px' }}>{r.description}</p>}
        </div>
      ))}
    </div>
  );
}
