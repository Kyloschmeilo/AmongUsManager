// Rollen-Zufallsgenerator (/rollen/zufall): würfelt ein Rollen-Setup für The Other Roles – welche Rollen in der nächsten
// Lobby an sind – und verteilt sie auf Wunsch gleich an Spieler (für Challenge-Runden). Läuft nur im Browser; das Ergebnis
// steht samt Zufallszahl in der Adresse, ein geteilter Link zeigt also dasselbe Setup.
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Copy, Dices, Shuffle, Users } from 'lucide-react';
import { useApi } from '../lib/api';
import { LEXICON_TEAM_LABEL, type LexiconRole, type LexiconTeam } from '../lib/guides';
import { RoleIcon, rolePath } from '../lib/roleArt';
import { Async, Section } from '../components/ui';
import { Title } from '../components/motion';
import '../extras.css';

/** Rollen, die TOR selbst im Spiel erzeugt – werden nicht gewürfelt. */
const NOT_ROLLED = new Set(['tor:sidekick', 'tor:pursuer', 'tor:immoralist']);
/** Bei GMIA ist Madmate ein Modifikator (kommt zu einer Crew-Rolle dazu). */
const AS_MODIFIER = new Set(['tor:madmate']);
/** Rollen, die mehrere Impostor-Plätze belegen (Godfather + Mafioso + Janitor, Bomber A + B, Mimic K + A). */
const SLOTS: Record<string, number> = { 'tor:mafia': 3, 'tor:bomber': 2, 'tor:mimic': 2 };

type Pool = 'IMPOSTOR' | 'CREW' | 'NEUTRAL' | 'MODIFIER';

interface Pick {
  role: LexiconRole;
  pool: Pool;
  /** Angezeigter Name, z.B. „Evil Guesser“ bei Rollen für beide Teams. */
  label: string;
  slots: number;
}

interface Settings {
  players: number;
  impostors: number;
  crew: number;
  neutral: number;
  modifiers: number;
  excluded: string[];
}

/** Kleiner Zufallsgenerator mit Startwert (gleicher Startwert = gleiches Ergebnis). */
function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function shuffle<T>(list: T[], rnd: () => number): T[] {
  const out = [...list];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

const newSeed = (): number => crypto.getRandomValues(new Uint32Array(1))[0];

export const defaultImpostors = (players: number): number => (players <= 6 ? 1 : players <= 11 ? 2 : 3);

function variantName(role: LexiconRole, evil: boolean): string {
  const prefix = evil ? 'Evil' : 'Nice';
  return role.variants.find((v) => v.startsWith(prefix)) ?? `${role.name} (${evil ? 'Impostor' : 'Crew'})`;
}

/** Würfelt ein Setup. Rollen für beide Teams können als Crew- oder Impostor-Version kommen, aber nur einmal. */
export function rollSetup(roles: LexiconRole[], s: Settings, seed: number): Pick[] {
  const rnd = mulberry32(seed);
  const excluded = new Set(s.excluded);
  const tor = roles.filter((r) => r.source === 'tor' && !NOT_ROLLED.has(r.id) && !excluded.has(r.id));
  const used = new Set<string>();
  const picks: Pick[] = [];
  // Impostor
  let free = s.impostors;
  const impostorPool = shuffle(tor.filter((r) => (r.team === 'IMPOSTOR' && !AS_MODIFIER.has(r.id)) || r.team === 'BOTH'), rnd);
  for (const role of impostorPool) {
    if (free <= 0) break;
    const slots = SLOTS[role.id] ?? 1;
    if (slots > free) continue;
    used.add(role.id);
    free -= slots;
    picks.push({ role, pool: 'IMPOSTOR', label: role.team === 'BOTH' ? variantName(role, true) : role.name, slots });
  }
  // Neutrale
  const neutralPool = shuffle(tor.filter((r) => r.team === 'NEUTRAL'), rnd).slice(0, s.neutral);
  neutralPool.forEach((role) => {
    used.add(role.id);
    picks.push({ role, pool: 'NEUTRAL', label: role.name, slots: 1 });
  });
  // Crew
  const crewPool = shuffle(tor.filter((r) => (r.team === 'CREW' || r.team === 'BOTH') && !used.has(r.id)), rnd).slice(0, s.crew);
  crewPool.forEach((role) => picks.push({ role, pool: 'CREW', label: role.team === 'BOTH' ? variantName(role, false) : role.name, slots: 1 }));
  // Modifikatoren
  shuffle(tor.filter((r) => r.team === 'MODIFIER' || AS_MODIFIER.has(r.id)), rnd)
    .slice(0, s.modifiers)
    .forEach((role) => picks.push({ role, pool: 'MODIFIER', label: role.name, slots: role.id === 'tor:lovers' ? 2 : 1 }));
  return picks;
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, Number.isFinite(v) ? Math.round(v) : lo));

function readSettings(params: URLSearchParams): Settings {
  const players = clamp(Number(params.get('n') ?? 10), 4, 24);
  const impostors = clamp(Number(params.get('i') ?? defaultImpostors(players)), 1, 3);
  const neutral = clamp(Number(params.get('x') ?? (players >= 8 ? 1 : 0)), 0, 4);
  const crewMax = Math.max(0, players - impostors - neutral);
  const crew = clamp(Number(params.get('c') ?? Math.min(crewMax, Math.ceil(players / 2))), 0, crewMax);
  const modifiers = clamp(Number(params.get('m') ?? 2), 0, 6);
  const excluded = (params.get('aus') ?? '').split(',').filter(Boolean).map((id) => `tor:${id}`);
  return { players, impostors, crew, neutral, modifiers, excluded };
}

const POOL_TITLE: Record<Pool, string> = { IMPOSTOR: 'Impostor', CREW: 'Crew', NEUTRAL: 'Neutral', MODIFIER: 'Modifikatoren' };
const POOL_TEAM: Record<Pool, LexiconTeam> = { IMPOSTOR: 'IMPOSTOR', CREW: 'CREW', NEUTRAL: 'NEUTRAL', MODIFIER: 'MODIFIER' };

export function RoleRandomizerPage() {
  const lexicon = useApi<{ roles: LexiconRole[] }>('lexicon');
  const [params, setParams] = useSearchParams();
  const settings = useMemo(() => readSettings(params), [params]);
  const seed = Number(params.get('s')) >>> 0;
  const [names, setNames] = useState('');
  const [copied, setCopied] = useState(false);
  const [showPool, setShowPool] = useState(false);

  const update = (next: Partial<Settings> & { seed?: number }) => {
    const s = { ...settings, ...next };
    s.impostors = clamp(s.impostors, 1, 3);
    s.neutral = clamp(s.neutral, 0, 4);
    s.crew = clamp(s.crew, 0, Math.max(0, s.players - s.impostors - s.neutral));
    const p = new URLSearchParams();
    p.set('n', String(s.players));
    p.set('i', String(s.impostors));
    p.set('c', String(s.crew));
    p.set('x', String(s.neutral));
    p.set('m', String(s.modifiers));
    if (s.excluded.length) p.set('aus', s.excluded.map((id) => id.replace(/^tor:/, '')).join(','));
    p.set('s', String(next.seed ?? (seed || newSeed())));
    setParams(p, { replace: true });
  };

  // Erster Besuch ohne Startwert: gleich einmal würfeln
  useEffect(() => {
    if (!params.get('s')) update({ seed: newSeed() });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const roles = lexicon.data?.roles ?? [];
  const picks = useMemo(() => (roles.length && seed ? rollSetup(roles, settings, seed) : []), [roles, settings, seed]);
  const plainCrew = Math.max(0, settings.players - settings.impostors - settings.neutral - picks.filter((p) => p.pool === 'CREW').length);
  const plainImpostors = Math.max(0, settings.impostors - picks.filter((p) => p.pool === 'IMPOSTOR').reduce((a, p) => a + p.slots, 0));

  // Spielern zuteilen: jeder Name bekommt einen Platz (Rolle oder normaler Crewmate/Impostor)
  const assignment = useMemo(() => {
    const list = names.split(/[\n,;]+/).map((n) => n.trim()).filter(Boolean).slice(0, 24);
    if (!list.length || !picks.length) return [];
    const seats: { label: string; team: LexiconTeam; role: LexiconRole | null }[] = [];
    for (const p of picks) {
      if (p.pool === 'MODIFIER') continue;
      for (let k = 0; k < p.slots; k++) seats.push({ label: p.slots > 1 ? `${p.label} (${k + 1}/${p.slots})` : p.label, team: POOL_TEAM[p.pool], role: p.role });
    }
    for (let k = 0; k < plainImpostors; k++) seats.push({ label: 'Impostor', team: 'IMPOSTOR', role: null });
    while (seats.length < list.length) seats.push({ label: 'Crewmate', team: 'CREW', role: null });
    const rnd = mulberry32(seed ^ 0x9e3779b9);
    const order = shuffle(seats, rnd);
    // Modifikatoren an zufällige Spieler; Madmate nur an Crew
    const modOf = new Map<number, Pick>();
    for (const mod of shuffle(picks.filter((p) => p.pool === 'MODIFIER'), rnd)) {
      const free = shuffle(list.map((_, i) => i).filter((i) => !modOf.has(i) && (!AS_MODIFIER.has(mod.role.id) || order[i].team === 'CREW')), rnd);
      if (free.length) modOf.set(free[0], mod);
    }
    return list.map((name, i) => ({ name, seat: order[i], modifier: modOf.get(i) ?? null }));
  }, [names, picks, plainImpostors, seed]);

  const torRoles = roles.filter((r) => r.source === 'tor' && !NOT_ROLLED.has(r.id));

  return (
    <div className="page page--tight dice">
      <div className="page-head">
        <Link className="text-link wiki__back" to="/rollen">
          <ArrowLeft className="icon icon--sm" aria-hidden="true" />
          Alle Rollen
        </Link>
        <Title text="Rollen-Zufallsgenerator" className="page-title" />
        <p className="lead">
          Keine Lust, die Rollen selbst auszusuchen? Stell ein, wie viele ihr seid, und würfle, welche Rollen von The Other Roles in der
          nächsten Lobby an sind. Der Link merkt sich das Ergebnis – einfach in den Discord schicken.
        </p>
      </div>
      <Async state={lexicon}>
        {() => (
          <>
            <section className="panel panel--box panel--pad dice__controls" aria-label="Einstellungen">
              <NumberField label="Spieler" value={settings.players} min={4} max={24} onChange={(v) => update({ players: v, impostors: defaultImpostors(v), crew: Math.ceil(v / 2), neutral: v >= 8 ? Math.max(settings.neutral, 1) : 0 })} />
              <NumberField label="Impostor" value={settings.impostors} min={1} max={3} onChange={(v) => update({ impostors: v })} />
              <NumberField label="Crew-Rollen" value={settings.crew} min={0} max={Math.max(0, settings.players - settings.impostors - settings.neutral)} onChange={(v) => update({ crew: v })} />
              <NumberField label="Neutrale" value={settings.neutral} min={0} max={4} onChange={(v) => update({ neutral: v })} />
              <NumberField label="Modifikatoren" value={settings.modifiers} min={0} max={6} onChange={(v) => update({ modifiers: v })} />
              <div className="dice__actions">
                <button type="button" className="btn btn--primary dice__roll" onClick={() => update({ seed: newSeed() })}>
                  <Dices className="icon icon--sm" aria-hidden="true" />
                  Neu würfeln
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    void navigator.clipboard?.writeText(window.location.href).then(() => {
                      setCopied(true);
                      window.setTimeout(() => setCopied(false), 1600);
                    });
                  }}
                >
                  <Copy className="icon icon--sm" aria-hidden="true" />
                  {copied ? 'Link kopiert' : 'Link kopieren'}
                </button>
              </div>
            </section>

            <div className="dice__result" key={seed}>
              {(['IMPOSTOR', 'CREW', 'NEUTRAL', 'MODIFIER'] as Pool[]).map((pool) => {
                const list = picks.filter((p) => p.pool === pool);
                const extra = pool === 'CREW' ? plainCrew : pool === 'IMPOSTOR' ? plainImpostors : 0;
                if (!list.length && !extra) return null;
                return (
                  <Section key={pool} title={POOL_TITLE[pool]} eyebrow={pool === 'MODIFIER' ? 'kommen zusätzlich zu einer Rolle' : undefined}>
                    <div className="dice__cards">
                      {list.map((p, i) => (
                        <Link key={p.role.id} to={rolePath(p.role)} className={`dice__card dice__card--${pool.toLowerCase()}`} style={{ ['--i' as string]: i }}>
                          <RoleIcon role={p.role} size="md" />
                          <span className="dice__name">{p.label}</span>
                          <span className="caption">{p.slots > 1 ? `${p.slots} Plätze` : pool === 'MODIFIER' ? (AS_MODIFIER.has(p.role.id) ? 'Modifikator für Crew' : 'Modifikator') : LEXICON_TEAM_LABEL[p.role.team]}</span>
                        </Link>
                      ))}
                      {extra > 0 && (
                        <div className="dice__card dice__card--plain" style={{ ['--i' as string]: list.length }}>
                          <span className="dice__count">{extra}×</span>
                          <span className="dice__name">{pool === 'CREW' ? 'Crewmate' : 'Impostor'}</span>
                          <span className="caption">ohne Spezialrolle</span>
                        </div>
                      )}
                    </div>
                  </Section>
                );
              })}
              <p className="caption">
                So stellst du es ein: im Lobby-Menü von The Other Roles die gewürfelten Rollen auf 100 % stellen, alle anderen auf 0 %.
                Rollen wie Sidekick, Pursuer und Immoralist entstehen erst im Spiel und werden nicht gewürfelt.
              </p>
            </div>

            <Section title="Gleich an Spieler verteilen" eyebrow="für Challenge-Runden">
              <div className="grid grid--2 dice__assign">
                <label className="field">
                  <span className="field__label">
                    <Users className="icon icon--sm" aria-hidden="true" /> Namen (eine Zeile oder mit Komma getrennt)
                  </span>
                  <textarea value={names} onChange={(e) => setNames(e.target.value)} rows={6} placeholder={'Kilian\nLena\nMax'} maxLength={600} />
                  <span className="caption">Jeder bekommt zufällig einen Platz aus dem Setup oben. Gilt nur zum Spaß – im Spiel würfelt The Other Roles selbst.</span>
                </label>
                <div>
                  {assignment.length === 0 ? (
                    <p className="caption">Trag Namen ein, dann steht hier, wer was spielt.</p>
                  ) : (
                    <ol className="dice__seats">
                      {assignment.map((a, i) => (
                        <li key={i} style={{ ['--i' as string]: i }}>
                          <span className="dice__seat-name">{a.name}</span>
                          <span className={`dice__seat-role dice__seat-role--${a.seat.team.toLowerCase()}`}>
                            {a.seat.role ? <Link to={rolePath(a.seat.role)}>{a.seat.label}</Link> : a.seat.label}
                            {a.modifier && <span className="caption"> + {a.modifier.label}</span>}
                          </span>
                        </li>
                      ))}
                    </ol>
                  )}
                </div>
              </div>
            </Section>

            <Section
              title="Rollen ausschließen"
              eyebrow={settings.excluded.length ? `${settings.excluded.length} ausgeschlossen` : 'alle im Topf'}
              action={
                <button type="button" className="btn btn--ghost" onClick={() => setShowPool((v) => !v)} aria-expanded={showPool}>
                  <Shuffle className="icon icon--sm" aria-hidden="true" />
                  {showPool ? 'Zuklappen' : 'Topf bearbeiten'}
                </button>
              }
            >
              {showPool ? (
                <div className="dice__pool">
                  {torRoles.map((r) => {
                    const off = settings.excluded.includes(r.id);
                    return (
                      <button
                        key={r.id}
                        type="button"
                        className={`dice__toggle${off ? ' dice__toggle--off' : ''}`}
                        aria-pressed={!off}
                        onClick={() => update({ excluded: off ? settings.excluded.filter((id) => id !== r.id) : [...settings.excluded, r.id] })}
                      >
                        <RoleIcon role={r} size="sm" />
                        {r.name}
                      </button>
                    );
                  })}
                </div>
              ) : (
                <p className="caption">Rollen, die ihr nicht mögt, kommen nicht mehr dran. Die Auswahl steht mit im Link.</p>
              )}
            </Section>
          </>
        )}
      </Async>
    </div>
  );
}

function NumberField({ label, value, min, max, onChange }: { label: string; value: number; min: number; max: number; onChange: (v: number) => void }) {
  return (
    <div className="field dice__num">
      <span className="field__label">{label}</span>
      <div className="dice__stepper">
        <button type="button" className="btn btn--icon" onClick={() => onChange(Math.max(min, value - 1))} disabled={value <= min} aria-label={`${label} weniger`}>−</button>
        <output aria-live="polite">{value}</output>
        <button type="button" className="btn btn--icon" onClick={() => onChange(Math.min(max, value + 1))} disabled={value >= max} aria-label={`${label} mehr`}>+</button>
      </div>
    </div>
  );
}
