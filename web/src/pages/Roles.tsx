// Rollen: alle Rollen aus Among Us und The Other Roles (Lexikon, /api/lexicon) – mit Statistik, sobald sie gespielt
// wurden (/api/roles). Rollen, die in Runden vorkommen, aber nicht im Lexikon stehen, kommen hinten dran.
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Search } from 'lucide-react';
import { useApi, type RolesResponse } from '../lib/api';
import { num, pct } from '../lib/format';
import { LEXICON_TEAM_LABEL, RichText, lexiconPillClass, type LexiconRole, type LexiconTeam } from '../lib/guides';
import { Async, Empty, Segmented } from '../components/ui';
import { Reveal, Title } from '../components/motion';
import '../guides.css';

type TeamFilter = 'all' | LexiconTeam;
type SourceFilter = 'all' | 'game' | 'tor';
type Sort = 'played' | 'az';

interface RoleEntry {
  key: string;
  name: string;
  team: LexiconTeam;
  source: 'game' | 'tor';
  ghost: boolean;
  description: string | null;
  variants: string[];
  games: number;
  wins: number;
  kills: number;
  survived: number;
  modifier: boolean;
}

function buildEntries(lexicon: LexiconRole[], stats: RolesResponse | null): RoleEntry[] {
  const rows = new Map<string, { games: number; wins: number; kills: number; survived: number; team: string | null; name: string; description?: string | null }>();
  for (const r of stats?.roles ?? []) rows.set(r.id, { games: r.games, wins: r.wins, kills: r.kills, survived: r.survived, team: r.team, name: r.name, description: r.description });
  for (const m of stats?.modifiers ?? []) rows.set(`tor:${m.id}`, { games: m.games, wins: m.wins, kills: 0, survived: 0, team: null, name: m.name, description: m.description });
  const used = new Set<string>();
  const entries: RoleEntry[] = lexicon.map((role) => {
    const entry: RoleEntry = {
      key: role.id,
      name: role.name,
      team: role.team,
      source: role.source,
      ghost: role.ghost,
      description: role.description,
      variants: role.variants,
      games: 0,
      wins: 0,
      kills: 0,
      survived: 0,
      modifier: role.team === 'MODIFIER'
    };
    for (const id of role.statIds) {
      const row = rows.get(id);
      if (!row || used.has(id)) continue;
      used.add(id);
      entry.games += row.games;
      entry.wins += row.wins;
      entry.kills += row.kills;
      entry.survived += row.survived;
    }
    return entry;
  });
  // gespielt, aber unbekannt (z.B. neue Rolle einer neueren Mod-Version)
  for (const [id, row] of rows) {
    if (used.has(id)) continue;
    const team: LexiconTeam = row.team === 'IMPOSTOR' ? 'IMPOSTOR' : row.team === 'NEUTRAL' ? 'NEUTRAL' : row.team === 'CREW' ? 'CREW' : 'MODIFIER';
    entries.push({
      key: id,
      name: row.name,
      team,
      source: id.startsWith('tor:') ? 'tor' : 'game',
      ghost: false,
      description: row.description ?? null,
      variants: [],
      games: row.games,
      wins: row.wins,
      kills: row.kills,
      survived: row.survived,
      modifier: row.team === null
    });
  }
  return entries;
}

export function RolesPage() {
  const lexicon = useApi<{ roles: LexiconRole[] }>('lexicon');
  const stats = useApi<RolesResponse>('roles');
  const [team, setTeam] = useState<TeamFilter>('all');
  const [source, setSource] = useState<SourceFilter>('all');
  const [sort, setSort] = useState<Sort>('played');
  const [query, setQuery] = useState('');
  // Statistik ist ein Zusatz: fehlt sie (Fehler), steht das Lexikon trotzdem da
  const statsReady = stats.data != null || stats.error != null;
  const entries = useMemo(
    () => (lexicon.data && statsReady ? buildEntries(lexicon.data.roles, stats.data) : null),
    [lexicon.data, stats.data, statsReady]
  );
  const state = entries ? { data: entries, error: null, loading: false } : { data: null, error: lexicon.error, loading: true };
  const played = entries?.filter((e) => e.games > 0).length ?? 0;
  const count = (t: TeamFilter) => entries?.filter((e) => (t === 'all' || e.team === t) && (source === 'all' || e.source === source)).length ?? 0;
  return (
    <div className="page page--tight">
      <div className="page-head">
        <Title text="Alle Rollen" className="page-title" />
        <p className="lead">
          Jede Rolle aus Among Us und der Mod The Other Roles – was sie kann und, sobald sie gespielt wurde, wie oft sie gewinnt.
          Gezählt wird die Rolle zu Rundenbeginn.
        </p>
        {entries && (
          <div className="row">
            <span className="pill pill--mono">{num(entries.length)} Rollen</span>
            <span className="pill pill--plain">{num(played)} schon gespielt</span>
            <Link className="text-link" to="/rollen/balancing">
              Balancing: Siegquoten der Rollen
              <ArrowRight className="icon icon--sm" aria-hidden="true" />
            </Link>
            <Link className="text-link" to="/hilfe?t=special-rollen">
              The Other Roles installieren
              <ArrowRight className="icon icon--sm" aria-hidden="true" />
            </Link>
          </div>
        )}
      </div>
      <Async state={state}>
        {(data) => (
          <>
            <Reveal>
              <div className="stack roles-filters">
                <div className="row">
                  <Segmented<TeamFilter>
                    label="Team"
                    value={team}
                    onChange={setTeam}
                    options={(['all', 'CREW', 'IMPOSTOR', 'NEUTRAL', 'BOTH', 'MODIFIER'] as TeamFilter[])
                      .filter((t) => t === 'all' || count(t) > 0 || t === team)
                      .map((t) => ({ value: t, label: `${t === 'all' ? 'Alle' : t === 'BOTH' ? 'Beide Teams' : t === 'MODIFIER' ? 'Modifikatoren' : LEXICON_TEAM_LABEL[t]} ${count(t)}` }))}
                  />
                </div>
                <div className="row">
                  <Segmented<SourceFilter>
                    label="Herkunft"
                    value={source}
                    onChange={setSource}
                    options={[
                      { value: 'all', label: 'Alle' },
                      { value: 'game', label: 'Among Us' },
                      { value: 'tor', label: 'The Other Roles' }
                    ]}
                  />
                  <Segmented<Sort>
                    label="Sortierung"
                    value={sort}
                    onChange={setSort}
                    options={[
                      { value: 'played', label: 'Meist gespielt' },
                      { value: 'az', label: 'A–Z' }
                    ]}
                  />
                  <span className="spacer" />
                  <label className="search">
                    <Search className="icon icon--sm" aria-hidden="true" />
                    <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Rolle suchen" aria-label="Rolle suchen" />
                  </label>
                </div>
              </div>
            </Reveal>
            <RoleGrid entries={data} team={team} source={source} sort={sort} query={query} />
          </>
        )}
      </Async>
    </div>
  );
}

function RoleGrid({ entries, team, source, sort, query }: { entries: RoleEntry[]; team: TeamFilter; source: SourceFilter; sort: Sort; query: string }) {
  const q = query.trim().toLowerCase();
  const roles = useMemo(() => {
    const filtered = entries.filter(
      (r) =>
        (team === 'all' || r.team === team) &&
        (source === 'all' || r.source === source) &&
        (!q || r.name.toLowerCase().includes(q) || r.variants.some((v) => v.toLowerCase().includes(q)))
    );
    const byName = (a: RoleEntry, b: RoleEntry) => a.name.localeCompare(b.name, 'de');
    return filtered.sort(sort === 'az' ? byName : (a, b) => b.games - a.games || byName(a, b));
  }, [entries, team, source, sort, q]);
  if (!roles.length) return <Empty text="Keine Rolle gefunden." />;
  return (
    <div className="grid grid--3">
      {roles.map((r) => (
        <div key={r.key} className={`panel role-card${r.games === 0 ? ' role-card--unplayed' : ''}`}>
          <div className="row" style={{ gap: 8 }}>
            <span className={lexiconPillClass(r.team)}>{LEXICON_TEAM_LABEL[r.team]}</span>
            {r.source === 'tor' && <span className="pill pill--plain">The Other Roles</span>}
            {r.ghost && <span className="pill pill--plain">Nach dem Tod</span>}
          </div>
          <div className="stack" style={{ gap: 2 }}>
            <h3>{r.name}</h3>
            {r.variants.length > 0 && <span className="caption">Im Spiel: {r.variants.join(' · ')}</span>}
          </div>
          {r.description && <p className="role-card__text"><RichText text={r.description} /></p>}
          {r.games > 0 ? (
            <div className="role-card__nums">
              <div><span className="eyebrow">Runden</span><b>{num(r.games)}</b></div>
              <div><span className="eyebrow">Siegquote</span><b className="accent">{pct(r.wins, r.games)}</b></div>
              {!r.modifier && <div><span className="eyebrow">Kills</span><b>{num(r.kills)}</b></div>}
              {!r.modifier && <div><span className="eyebrow">Überlebt</span><b>{pct(r.survived, r.games)}</b></div>}
            </div>
          ) : (
            <span className="caption role-card__none">Noch nicht gespielt</span>
          )}
        </div>
      ))}
    </div>
  );
}
