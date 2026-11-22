// Rollen-Wiki: eine Seite je Rolle (/rollen/<slug>) – Bilder der Fähigkeiten (The Other Roles), Beschreibung, Statistik
// aus den Runden (/api/roles/detail), Einschätzung aus dem Balancing, wer sie am meisten spielt und die letzten Runden.
import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Crown, Map as MapIcon } from 'lucide-react';
import { useApi, type RoundItem, type PlayerRef, type Team } from '../lib/api';
import { date, num, pct } from '../lib/format';
import { LEXICON_TEAM_LABEL, RichText, lexiconPillClass, type LexiconRole } from '../lib/guides';
import { RoleIcon, rolePath, roleImageUrl } from '../lib/roleArt';
import { Async, BarList, Empty, Leaderboard, RoundList, Section } from '../components/ui';
import { Reveal } from '../components/motion';
import '../guides.css';
import '../extras.css';

interface RoleDetail {
  games: number;
  wins: number;
  kills: number;
  survived: number;
  firstPlayedAt: string | null;
  lastPlayedAt: string | null;
  players: (PlayerRef & { games: number; wins: number; kills: number })[];
  maps: { mapId: number; name: string; games: number; wins: number }[];
  rounds: (RoundItem & { holders: { key: string; name: string; colorId: number | null; team: Team; won: boolean; role: string; kills: number }[] })[];
}

interface BalanceRow {
  id: string;
  share: number;
  winRate: number;
  low: number;
  high: number;
  baseline: number | null;
  verdict: 'strong' | 'weak' | 'trend_strong' | 'trend_weak' | 'ok' | 'few';
}

const VERDICT: Record<BalanceRow['verdict'], string> = {
  strong: 'Zu stark',
  weak: 'Zu schwach',
  trend_strong: 'Eher stark',
  trend_weak: 'Eher schwach',
  ok: 'Ausgeglichen',
  few: 'Noch zu wenige Runden'
};

export function RoleWikiPage() {
  const { slug = '' } = useParams();
  const lexicon = useApi<{ roles: LexiconRole[] }>('lexicon');
  const role = lexicon.data?.roles.find((r) => r.slug === slug.toLowerCase()) ?? null;
  const detail = useApi<RoleDetail>(role ? `roles/detail?ids=${encodeURIComponent(role.statIds.join(','))}` : null);
  const balance = useApi<{ all: { roles: BalanceRow[]; modifiers: BalanceRow[] } }>(role?.source === 'tor' ? 'balance' : null);
  const balanceRow = useMemo(() => {
    if (!role || !balance.data) return null;
    const rows = [...balance.data.all.roles, ...balance.data.all.modifiers];
    return role.statIds.map((id) => rows.find((r) => r.id === id)).find(Boolean) ?? null;
  }, [role, balance.data]);
  const neighbours = useMemo(() => {
    if (!role || !lexicon.data) return { prev: null, next: null, same: [] as LexiconRole[] };
    const list = lexicon.data.roles.filter((r) => r.source === role.source);
    const i = list.findIndex((r) => r.id === role.id);
    const same = lexicon.data.roles.filter((r) => r.team === role.team && r.id !== role.id);
    // gleiche Auswahl bei jedem Besuch derselben Rolle
    const seed = [...role.id].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7);
    const picked = same.map((r, k) => ({ r, k: ((k + 1) * 2654435761 + seed) >>> 0 })).sort((a, b) => a.k - b.k).slice(0, 4).map((x) => x.r);
    return { prev: i > 0 ? list[i - 1] : null, next: i >= 0 && i < list.length - 1 ? list[i + 1] : null, same: picked };
  }, [role, lexicon.data]);

  if (lexicon.data && !role) {
    return (
      <div className="page page--tight">
        <Empty text="Diese Rolle gibt es nicht." hint={<Link className="text-link" to="/rollen">Zu allen Rollen</Link>} />
      </div>
    );
  }

  return (
    <Async state={lexicon}>
      {() =>
        role && (
          <div className="page page--tight wiki">
            <div className="page-head wiki__head">
              <Link className="text-link wiki__back" to="/rollen">
                <ArrowLeft className="icon icon--sm" aria-hidden="true" />
                Alle Rollen
              </Link>
              <div className="wiki__hero">
                <RoleIcon role={role} size="lg" />
                <div className="stack" style={{ gap: 8 }}>
                  <div className="row" style={{ gap: 8 }}>
                    <span className={lexiconPillClass(role.team)}>{LEXICON_TEAM_LABEL[role.team]}</span>
                    <span className="pill pill--plain">{role.source === 'tor' ? 'The Other Roles' : 'Among Us'}</span>
                    {role.ghost && <span className="pill pill--plain">Nach dem Tod</span>}
                  </div>
                  <h1 className="page-title">{role.name}</h1>
                  {role.variants.length > 0 && <span className="caption">Im Spiel als: {role.variants.join(' · ')}</span>}
                </div>
              </div>
              <p className="lead wiki__text"><RichText text={role.description} /></p>
            </div>

            {role.images.length > 0 && (
              <Reveal>
                <Section title="Fähigkeiten" eyebrow={role.images.length === 1 ? 'Knopf im Spiel' : `${role.images.length} Knöpfe im Spiel`}>
                  <div className="wiki__abilities">
                    {role.images.map((img) => (
                      <figure key={img.file + img.label} className="wiki__ability">
                        <img src={roleImageUrl(img.file)} alt="" loading="lazy" decoding="async" />
                        <figcaption>{img.label}</figcaption>
                      </figure>
                    ))}
                  </div>
                  <p className="caption">Bilder aus der Mod The Other Roles GMIA (GPL-3.0).</p>
                </Section>
              </Reveal>
            )}

            <Async state={detail}>
              {(d) => (
                <>
                  <Section title="In unseren Runden" eyebrow={d.games > 0 ? `seit ${date(d.firstPlayedAt)}` : undefined}>
                    {d.games === 0 ? (
                      <Empty text="Noch nicht gespielt." hint="Sobald jemand die Rolle in einer aufgezeichneten Runde hat, steht hier, wie sie läuft." />
                    ) : (
                      <div className="grid grid--stats">
                        <WikiStat label="Gespielt" value={num(d.games)} caption={`zuletzt ${date(d.lastPlayedAt)}`} />
                        <WikiStat label="Siegquote" value={pct(d.wins, d.games)} caption={`${num(d.wins)} Siege`} accent />
                        {role.team !== 'MODIFIER' && <WikiStat label="Kills" value={num(d.kills)} caption={`${(d.kills / d.games).toFixed(1).replace('.', ',')} je Runde`} />}
                        {role.team !== 'MODIFIER' && <WikiStat label="Überlebt" value={pct(d.survived, d.games)} caption="bis Rundenende" />}
                        {balanceRow && (
                          <WikiStat
                            label="Balancing"
                            value={VERDICT[balanceRow.verdict]}
                            caption={`in ${pct(balanceRow.share * 1000, 1000)} der TOR-Runden`}
                            to="/rollen/balancing"
                          />
                        )}
                      </div>
                    )}
                  </Section>
                  {d.games > 0 && (
                    <div className="grid grid--2">
                      <Leaderboard
                        icon={<Crown className="icon" aria-hidden="true" />}
                        title="Spielen sie am meisten"
                        rows={d.players.slice(0, 5).map((p) => ({ player: p, value: `${num(p.games)} × · ${pct(p.wins, p.games)}` }))}
                      />
                      <div className="panel">
                        <h3 className="panel__title">
                          <MapIcon className="icon" aria-hidden="true" />
                          Siegquote je Map
                        </h3>
                        <BarList items={d.maps.map((m) => ({ label: m.name, value: m.games ? m.wins / m.games : 0, text: `${pct(m.wins, m.games)} · ${num(m.games)}×` }))} max={1} />
                      </div>
                    </div>
                  )}
                  {d.rounds.length > 0 && (
                    <Section title="Letzte Runden mit der Rolle" eyebrow={`die neuesten ${d.rounds.length}`}>
                      <RoundList
                        rounds={d.rounds}
                        extra={(round) => {
                          const holders = d.rounds.find((r) => r.gameId === round.gameId)?.holders ?? [];
                          return (
                            <span className="caption wiki__holders">
                              {holders.map((h) => `${h.name}${h.won ? ' (gewonnen)' : ''}`).join(', ')}
                            </span>
                          );
                        }}
                      />
                    </Section>
                  )}
                </>
              )}
            </Async>

            {neighbours.same.length > 0 && (
              <Section title={`Weitere Rollen: ${LEXICON_TEAM_LABEL[role.team]}`}>
                <div className="wiki__more">
                  {neighbours.same.map((r) => (
                    <Link key={r.id} to={rolePath(r)} className="wiki__chip">
                      <RoleIcon role={r} size="sm" />
                      <span>{r.name}</span>
                    </Link>
                  ))}
                </div>
              </Section>
            )}
            <nav className="wiki__pager" aria-label="Rollen blättern">
              {neighbours.prev ? (
                <Link className="text-link" to={rolePath(neighbours.prev)}>
                  <ArrowLeft className="icon icon--sm" aria-hidden="true" />
                  {neighbours.prev.name}
                </Link>
              ) : <span />}
              {neighbours.next && (
                <Link className="text-link" to={rolePath(neighbours.next)}>
                  {neighbours.next.name}
                  <ArrowRight className="icon icon--sm" aria-hidden="true" />
                </Link>
              )}
            </nav>
          </div>
        )
      }
    </Async>
  );
}

function WikiStat({ label, value, caption, accent, to }: { label: string; value: string; caption?: string; accent?: boolean; to?: string }) {
  const text = !/\d/.test(value);
  const body = (
    <>
      <div className="stat__top"><span className="stat__label">{label}</span></div>
      <div className={`stat__value${accent ? ' accent' : ''}${text ? ' stat__value--text' : ''}`}>{value}</div>
      {caption && <span className="caption">{caption}</span>}
    </>
  );
  return to ? <Link to={to} className="panel stat wiki__stat-link">{body}</Link> : <div className="panel stat">{body}</div>;
}
