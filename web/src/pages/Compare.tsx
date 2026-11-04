import { useMemo, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowLeftRight, Search, X } from 'lucide-react';
import { useApi, type Compare, type CompareRoundSide, type DuelSide, type PlayerSummary } from '../lib/api';
import { ago, distance, num, pct } from '../lib/format';
import { TEAM_LABEL } from '../lib/labels';
import { playerColor } from '../lib/colors';
import { useMe } from '../lib/me';
import { Async, Avatar, Empty, RoundList, Section } from '../components/ui';
import { Count, Title } from '../components/motion';
import '../compare.css';

/**
 * Zwei Spieler nebeneinander: Duell-Bilanz aus allen gemeinsamen Runden (gegeneinander, nach Impostor/Crew
 * aufgeteilt, Kills und Stimmen untereinander), zusammen im Team und alle Kennzahlen Kopf an Kopf.
 * Adresse: /vergleich?a=<Schlüssel>&b=<Schlüssel>.
 */
export function ComparePage() {
  const [params, setParams] = useSearchParams();
  const { me } = useMe();
  const players = useApi<{ players: PlayerSummary[] }>('players');
  const myKey = me?.player?.key ?? null;
  const a = params.get('a') ?? null;
  // ohne zweiten Spieler: man selbst (wenn angemeldet und nicht schon links)
  const b = params.get('b') ?? (myKey && myKey !== a ? myKey : null);
  const set = (next: { a?: string | null; b?: string | null }) => {
    const p = new URLSearchParams(params);
    const na = 'a' in next ? next.a : a;
    const nb = 'b' in next ? next.b : b;
    if (na) p.set('a', na); else p.delete('a');
    if (nb) p.set('b', nb); else p.delete('b');
    setParams(p, { replace: true, preventScrollReset: true });
  };
  const ready = !!a && !!b && a !== b;
  const compare = useApi<Compare>(ready ? `compare/${encodeURIComponent(a!)}/${encodeURIComponent(b!)}` : null);
  const list = players.data?.players ?? [];
  const byKey = (key: string | null) => (key ? list.find((p) => p.key === key) ?? null : null);

  return (
    <div className="page">
      <div className="stack" style={{ gap: 24 }}>
      <div className="page-head">
        <Title text="Spieler-Vergleich" className="page-title" />
        <p className="lead">
          Zwei Spieler nebeneinander, und wie es ausgeht, wenn beide in derselben Runde sind: wer gegeneinander gewinnt,
          wer wen erwischt, wer wen rauswählt.
        </p>
      </div>

      <div className="vs-pick">
        <PlayerPicker side="a" player={byKey(a)} players={list} exclude={b} onPick={(key) => set({ a: key })} />
        <button
          type="button"
          className="btn btn--icon vs-pick__swap"
          onClick={() => set({ a: b, b: a })}
          disabled={!a || !b}
          aria-label="Seiten tauschen"
          title="Seiten tauschen"
        >
          <ArrowLeftRight className="icon icon--sm" aria-hidden="true" />
        </button>
        <PlayerPicker side="b" player={byKey(b)} players={list} exclude={a} onPick={(key) => set({ b: key })} />
      </div>
      </div>

      {!ready ? (
        <Empty text="Wähle zwei Spieler aus." hint="Oben links und rechts suchen. Aus jedem Profil geht es auch über „Vergleichen“ hierher." />
      ) : (
        <Async state={compare}>{(c) => <CompareView c={c} />}</Async>
      )}
    </div>
  );
}

function PlayerPicker({ side, player, players, exclude, onPick }: {
  side: 'a' | 'b';
  player: PlayerSummary | null;
  players: PlayerSummary[];
  exclude: string | null;
  onPick: (key: string | null) => void;
}) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const q = query.trim().toLowerCase();
  const matches = useMemo(
    () => players.filter((p) => p.key !== exclude && (!q || p.name.toLowerCase().includes(q))).slice(0, 8),
    [players, exclude, q]
  );
  if (player && !open) {
    return (
      <div className={`vs-pick__player vs-pick__player--${side}`} style={{ ['--pc' as string]: playerColor(player.colorId, player.key) }}>
        <Avatar colorId={player.colorId} playerKey={player.key} src={player.avatar} size="lg" />
        <div className="stack" style={{ gap: 2, minWidth: 0 }}>
          <Link className="vs-pick__name" to={`/spieler/${player.key}`}>{player.name}</Link>
          <span className="caption">
            {num(player.games)} Runden{player.level != null ? ` · Level ${player.level}` : ''} · zuletzt {ago(player.lastSeen)}
          </span>
        </div>
        <button type="button" className="btn btn--ghost btn--icon vs-pick__change" onClick={() => setOpen(true)} aria-label="Anderen Spieler wählen" title="Anderen Spieler wählen">
          <X className="icon icon--sm" aria-hidden="true" />
        </button>
      </div>
    );
  }
  const pick = (key: string) => {
    onPick(key);
    setOpen(false);
    setQuery('');
  };
  return (
    <div className={`vs-pick__search vs-pick__search--${side}`}>
      <label className="search">
        <Search className="icon icon--sm" aria-hidden="true" />
        <input
          autoFocus={open}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && matches[0]) pick(matches[0].key);
            if (e.key === 'Escape' && player) setOpen(false);
          }}
          placeholder={side === 'a' ? 'Ersten Spieler suchen' : 'Zweiten Spieler suchen'}
          aria-label={side === 'a' ? 'Ersten Spieler suchen' : 'Zweiten Spieler suchen'}
        />
      </label>
      <ul className="vs-pick__list">
        {matches.map((p) => (
          <li key={p.key}>
            <button type="button" onClick={() => pick(p.key)}>
              <Avatar colorId={p.colorId} playerKey={p.key} src={p.avatar} size="sm" />
              <span className="player__name">{p.name}</span>
              <span className="caption">{num(p.games)} Runden</span>
            </button>
          </li>
        ))}
        {matches.length === 0 && <li className="caption">Niemand gefunden.</li>}
      </ul>
    </div>
  );
}

function CompareView({ c }: { c: Compare }) {
  const { a, b } = c;
  const colorA = playerColor(a.colorId, a.key);
  const rawB = playerColor(b.colorId, b.key);
  // gleiche Spielfarbe: rechts neutral, sonst wären die Balken nicht zu unterscheiden
  const colorB = rawB === colorA ? 'var(--ink-muted)' : rawB;
  const vars = { ['--ca' as string]: colorA, ['--cb' as string]: colorB };
  const d = c.against;
  const lead = d.aWins === d.bWins ? null : d.aWins > d.bWins ? a : b;

  return (
    <div className="compare" style={vars}>
      {c.shared === 0 ? (
        <Empty text={`${a.name} und ${b.name} waren noch nie in derselben Runde.`} hint="Unten stehen trotzdem alle Zahlen nebeneinander." />
      ) : (
        <section className="score duel" aria-label={`${a.name} ${d.aWins} Siege, ${b.name} ${d.bWins} Siege gegeneinander`}>
          <div className="score__line duel__line">
            <div className="score__side duel__side duel__side--a">
              <span className="score__team">{a.name}</span>
              <span className="score__num"><Count value={d.aWins} /></span>
            </div>
            <span className="score__colon" aria-hidden="true">:</span>
            <div className="score__side duel__side duel__side--b">
              <span className="score__team">{b.name}</span>
              <span className="score__num"><Count value={d.bWins} /></span>
            </div>
          </div>
          {d.games > 0 && (
            <div className="split duel__split">
              {d.aWins > 0 && <div className="duel__a" style={{ flexGrow: d.aWins }} />}
              {d.bWins > 0 && <div className="duel__b" style={{ flexGrow: d.bWins }} />}
              {d.games - d.aWins - d.bWins > 0 && <div className="duel__none" style={{ flexGrow: d.games - d.aWins - d.bWins }} />}
            </div>
          )}
          <span className="score__note">
            {d.games > 0
              ? <>Siege in {num(d.games)} {d.games === 1 ? 'Runde' : 'Runden'} gegeneinander{lead ? `, ${lead.name} liegt vorn` : ', Gleichstand'}.</>
              : <>Noch nie gegeneinander gespielt.</>}
            {' '}Insgesamt {num(c.shared)} gemeinsame Runden, davon {num(c.together.games)} im selben Team.
          </span>
          {c.form.length > 0 && (
            <div className="row" style={{ gap: 12 }}>
              <span className="eyebrow">Verlauf</span>
              <span className="form duel__form">
                {[...c.form].reverse().map((r, i) => (
                  <i key={i} className={`duel__f duel__f--${r}`} title={r === 'a' ? `${a.name} gewinnt` : r === 'b' ? `${b.name} gewinnt` : r === 'both' ? 'beide gewinnen' : 'keiner gewinnt'} />
                ))}
              </span>
              <span className="caption">rechts das neueste Duell</span>
            </div>
          )}
        </section>
      )}

      {d.games > 0 && (
        <Section title="Impostor gegen Crew" eyebrow="wer gewinnt in welcher Rolle">
          <div className="grid grid--3">
            <Matchup title={`${a.name} Impostor, ${b.name} Crew`} side={d.aImpostor} a={a.name} b={b.name} />
            <Matchup title={`${b.name} Impostor, ${a.name} Crew`} side={d.bImpostor} a={a.name} b={b.name} />
            {d.neutral.games > 0 && <Matchup title="Mit neutraler Rolle" side={d.neutral} a={a.name} b={b.name} note="mindestens einer war Jester, Jackal o. Ä." />}
          </div>
        </Section>
      )}

      {c.shared > 0 && (
        <Section title="Untereinander" eyebrow="was die beiden sich gegenseitig antun">
          <div className="panel vs-table">
            <VsHead a={a.name} b={b.name} />
            <VsRow label="getötet" sub="wie oft der eine den anderen" a={c.kills.aKilledB} b={c.kills.bKilledA} />
            <VsRow label="Stimmen gegen den anderen" a={c.votes.aVotedB} b={c.votes.bVotedA} better="none" />
            <VsRow label="davon richtig" sub="der andere war Impostor" a={c.votes.aVotedBImpostor} b={c.votes.bVotedAImpostor} />
            <VsRow label="rausgewählt" sub="mitgestimmt, als der andere flog" a={c.votes.aEjectedB} b={c.votes.bEjectedA} />
            <VsRow label="länger überlebt" a={c.survival.aLonger} b={c.survival.bLonger} />
          </div>
        </Section>
      )}

      {c.together.games > 0 && (
        <Section title="Im selben Team" eyebrow={`${num(c.together.games)} Runden zusammen`}>
          <div className="grid grid--3">
            <div className="panel panel--pad stack" style={{ gap: 6 }}>
              <span className="eyebrow">Zusammen gewonnen</span>
              <span className="duel-big">{pct(c.together.wins, c.together.games)}</span>
              <span className="caption">{num(c.together.wins)} von {num(c.together.games)} Runden</span>
            </div>
            {c.together.teams.filter((t) => t.games > 0).map((t) => (
              <div key={t.team} className="panel panel--pad stack" style={{ gap: 6 }}>
                <span className="eyebrow">Beide {TEAM_LABEL[t.team]}</span>
                <span className="duel-big" style={{ color: t.team === 'IMPOSTOR' ? 'var(--ember)' : 'var(--accent)' }}>{pct(t.wins, t.games)}</span>
                <span className="caption">{num(t.wins)} Siege in {num(t.games)} Runden</span>
              </div>
            ))}
          </div>
        </Section>
      )}

      <Section title="Alle Zahlen" eyebrow="über alle Runden, nicht nur gemeinsame">
        <div className="panel vs-table">
          <VsHead a={a.name} b={b.name} />
          <VsRow label="Runden" a={a.games} b={b.games} />
          <VsRow label="Siegquote" a={rate(a.wins, a.games)} b={rate(b.wins, b.games)} format={fmtPct} />
          <VsRow label="als Crew" sub="Siegquote" a={rate(a.crewWins, a.crewGames)} b={rate(b.crewWins, b.crewGames)} format={fmtPct} />
          <VsRow label="als Impostor" sub="Siegquote" a={rate(a.impostorWins, a.impostorGames)} b={rate(b.impostorWins, b.impostorGames)} format={fmtPct} />
          <VsRow label="Kills pro Impostor-Runde" a={per(a.impostorKills, a.impostorGames)} b={per(b.impostorKills, b.impostorGames)} format={fmtDec} />
          <VsRow label="erster Kill der Runde" a={a.firstKills} b={b.firstKills} />
          <VsRow label="überlebt" sub="Anteil der Runden" a={rate(a.survived, a.games)} b={rate(b.survived, b.games)} format={fmtPct} />
          <VsRow label="richtige Stimmen" sub="als Crew" a={rate(a.correctVotes, a.crewVotes)} b={rate(b.correctVotes, b.crewVotes)} format={fmtPct} />
          <VsRow label="Aufgaben erledigt" a={a.tasksDone} b={b.tasksDone} />
          <VsRow label="Leichen gemeldet" a={a.bodiesReported} b={b.bodiesReported} />
          <VsRow label="Notfall-Meetings" a={a.meetingsCalled} b={b.meetingsCalled} better="none" />
          <VsRow label="Strecke pro Runde" a={per(a.distance, a.detailGames)} b={per(b.distance, b.detailGames)} format={(v) => (v == null ? '–' : distance(v))} better="none" />
          <VsRow label="Spiel verlassen" a={a.disconnects} b={b.disconnects} better="lower" />
          {(a.level != null || b.level != null) && <VsRow label="Level" a={a.level} b={b.level} />}
        </div>
      </Section>

      {d.maps.length > 0 && (
        <Section title="Duelle nach Map">
          <div className="panel vs-table">
            <VsHead a={a.name} b={b.name} />
            {d.maps.map((m) => (
              <VsRow key={m.mapId} label={<Link to={`/maps/${m.mapId}`}>{m.map}</Link>} sub={`${num(m.games)} Duelle`} a={m.aWins} b={m.bWins} />
            ))}
          </div>
        </Section>
      )}

      {c.rounds.length > 0 && (
        <Section title="Gemeinsame Runden" action={<Link className="btn" to={`/runden?spieler=${a.key}`}>Alle von {a.name}</Link>}>
          <RoundList
            rounds={c.rounds.map((r) => r.round)}
            extra={(round) => {
              const row = c.rounds.find((r) => r.round.gameId === round.gameId);
              if (!row) return null;
              return (
                <span className="duel-round">
                  <RoundSide name={a.name} s={row.a} />
                  <span className="faint">{row.together ? ' zusammen mit ' : ' gegen '}</span>
                  <RoundSide name={b.name} s={row.b} />
                </span>
              );
            }}
          />
        </Section>
      )}
    </div>
  );
}

function RoundSide({ name, s }: { name: string; s: CompareRoundSide }) {
  return (
    <span style={{ color: s.won ? 'var(--ink)' : 'var(--ink-faint)' }}>
      {name} als {s.role}{s.kills > 0 ? ` (${s.kills} ${s.kills === 1 ? 'Kill' : 'Kills'})` : ''}
    </span>
  );
}

function Matchup({ title, side, a, b, note }: { title: string; side: DuelSide; a: string; b: string; note?: string }) {
  return (
    <div className="panel panel--pad stack matchup" style={{ gap: 8 }}>
      <span className="eyebrow">{title}</span>
      {side.games === 0 ? (
        <span className="caption">Kam noch nicht vor.</span>
      ) : (
        <>
          <span className="duel-big">
            <span className="duel-a">{num(side.aWins)}</span>
            <span className="faint"> : </span>
            <span className="duel-b">{num(side.bWins)}</span>
          </span>
          <div className="split duel__split">
            {side.aWins > 0 && <div className="duel__a" style={{ flexGrow: side.aWins }} />}
            {side.bWins > 0 && <div className="duel__b" style={{ flexGrow: side.bWins }} />}
            {side.games - side.aWins - side.bWins > 0 && <div className="duel__none" style={{ flexGrow: side.games - side.aWins - side.bWins }} />}
          </div>
          <span className="caption">
            {num(side.games)} {side.games === 1 ? 'Runde' : 'Runden'}: {a} {num(side.aWins)}, {b} {num(side.bWins)} Siege
            {note ? ` (${note})` : ''}
          </span>
        </>
      )}
    </div>
  );
}

const rate = (part: number, total: number): number | null => (total > 0 ? part / total : null);
const per = (value: number, games: number): number | null => (games > 0 ? value / games : null);
const fmtPct = (v: number | null) => (v == null ? '–' : pct(v * 1000, 1000));
const fmtDec = (v: number | null) => (v == null ? '–' : v.toLocaleString('de-DE', { maximumFractionDigits: 2 }));

function VsHead({ a, b }: { a: string; b: string }) {
  return (
    <div className="vs-row vs-row--head" aria-hidden="true">
      <span className="vs-row__a duel-a">{a}</span>
      <span />
      <span className="vs-row__b duel-b">{b}</span>
    </div>
  );
}

/**
 * Eine Zeile „links Wert – Bezeichnung mit Balken – rechts Wert“. [better]: welcher Wert vorne liegt
 * (höher, niedriger oder keiner – dann wird nichts hervorgehoben).
 */
function VsRow({ label, sub, a, b, format = (v) => num(v), better = 'higher' }: {
  label: ReactNode;
  sub?: string;
  a: number | null;
  b: number | null;
  format?: (v: number | null) => string;
  better?: 'higher' | 'lower' | 'none';
}) {
  const av = a ?? 0;
  const bv = b ?? 0;
  const total = av + bv;
  const win = better === 'none' || a == null || b == null || av === bv ? null : (better === 'higher') === av > bv ? 'a' : 'b';
  return (
    <div className="vs-row">
      <span className={`vs-row__a${win === 'a' ? ' is-lead' : ''}`}>{format(a)}</span>
      <span className="vs-row__mid">
        <span className="vs-row__label">{label}{sub && <span className="caption"> · {sub}</span>}</span>
        <span className="vs-row__bar" aria-hidden="true">
          <i className="duel__a" style={{ width: `${total > 0 ? (av / total) * 50 : 0}%` }} />
          <i className="duel__b" style={{ width: `${total > 0 ? (bv / total) * 50 : 0}%` }} />
        </span>
      </span>
      <span className={`vs-row__b${win === 'b' ? ' is-lead' : ''}`}>{format(b)}</span>
    </div>
  );
}
