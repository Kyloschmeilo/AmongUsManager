import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { X } from 'lucide-react';
import { fetchJson, useApi, type MapSummary, type PlayerSummary, type RoundItem } from '../lib/api';
import { num } from '../lib/format';
import { mapShort } from '../lib/labels';
import { Empty, Failed, Loading, RoundList, Segmented } from '../components/ui';
import { Reveal, Title } from '../components/motion';

const PAGE = 30;
type TeamFilter = 'all' | 'CREW' | 'IMPOSTOR' | 'NEUTRAL';

export function RoundsPage() {
  const [params, setParams] = useSearchParams();
  const map = params.get('map') ?? 'all';
  const team = (params.get('sieger') ?? 'all') as TeamFilter;
  const player = params.get('spieler');
  const maps = useApi<{ maps: MapSummary[] }>('maps');
  const players = useApi<{ players: PlayerSummary[] }>('players');
  const [items, setItems] = useState<RoundItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const query = (offset: number) => {
    const q = new URLSearchParams({ offset: String(offset), limit: String(PAGE) });
    if (map !== 'all') q.set('map', map);
    if (team !== 'all') q.set('team', team);
    if (player) q.set('player', player);
    return `rounds?${q}`;
  };

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    fetchJson<{ total: number; items: RoundItem[] }>(query(0)).then(
      (r) => {
        if (!active) return;
        setItems(r.items);
        setTotal(r.total);
        setLoading(false);
      },
      (e: Error) => active && (setError(e), setLoading(false))
    );
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, team, player]);

  const more = () => {
    setLoading(true);
    fetchJson<{ total: number; items: RoundItem[] }>(query(items.length)).then(
      (r) => {
        setItems((current) => [...current, ...r.items]);
        setLoading(false);
      },
      (e: Error) => (setError(e), setLoading(false))
    );
  };

  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value == null || value === 'all') next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  };

  const playerName = player ? players.data?.players.find((p) => p.key === player)?.name : null;

  return (
    <div className="page page--tight">
      <div className="page-head">
        <Title text="Alle Runden" className="page-title" />
        <p className="lead">Jede aufgezeichnete Runde mit Rollen, Kills und Abstimmungen. Runden mit Replay haben das Film-Symbol.</p>
      </div>
      <Reveal>
        <div className="stack" style={{ gap: 12 }}>
          <div className="row">
            <span className="eyebrow" style={{ width: 64 }}>Map</span>
            <Segmented
              label="Map"
              value={map}
              onChange={(v) => set('map', v)}
              options={[{ value: 'all', label: 'Alle' }, ...(maps.data?.maps ?? []).map((m) => ({ value: String(m.mapId), label: mapShort(m.mapId, m.map) }))]}
            />
          </div>
          <div className="row">
            <span className="eyebrow" style={{ width: 64 }}>Sieger</span>
            <Segmented<TeamFilter>
              label="Sieger"
              value={team}
              onChange={(v) => set('sieger', v)}
              options={[
                { value: 'all', label: 'Alle' },
                { value: 'CREW', label: 'Crew' },
                { value: 'IMPOSTOR', label: 'Impostor' },
                { value: 'NEUTRAL', label: 'Sondersieg' }
              ]}
            />
          </div>
          {player && (
            <div className="row">
              <span className="eyebrow" style={{ width: 64 }}>Spieler</span>
              <span className="pill">{playerName ?? 'Ausgewählter Spieler'}</span>
              <button type="button" className="btn btn--ghost" onClick={() => set('spieler', null)}>
                <X className="icon icon--sm" /> Alle Spieler
              </button>
              {playerName && <Link to={`/spieler/${player}`} className="caption">Zum Profil</Link>}
            </div>
          )}
        </div>
      </Reveal>
      {error ? (
        <Failed error={error} />
      ) : items.length === 0 && loading ? (
        <Loading />
      ) : items.length === 0 ? (
        <Empty text="Keine Runden mit diesen Filtern." />
      ) : (
        <div className="stack" style={{ gap: 16 }}>
          <span className="caption mono">{num(total)} Runden</span>
          <RoundList rounds={items} />
          {items.length < total && (
            <button type="button" className="btn" style={{ alignSelf: 'center' }} onClick={more} disabled={loading}>
              {loading ? 'Lädt …' : `${num(Math.min(PAGE, total - items.length))} weitere laden`}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
