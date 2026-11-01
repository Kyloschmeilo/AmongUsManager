// Bausteine der Seite im Standard-Design: Avatar, Spieler-Link, Pills, Kennzahl-Karten, Balken, Bestenliste, Rundenliste.
import { useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, CircleAlert, Film, Inbox } from 'lucide-react';
import type { PlayerRef, RoundItem, Team } from '../lib/api';
import { playerColor } from '../lib/colors';
import { day, dayMonth, duration, num, time } from '../lib/format';
import { TEAM_WIN_LABEL, mapShort, teamPillClass } from '../lib/labels';
import { Count, Reveal, useGrow } from './motion';

/** Discord-Profilbild, sonst eine eckige Kachel in der Spielfarbe (Crewmate). */
export function Avatar({ colorId, playerKey, src, size, dead, title }: { colorId: number | null | undefined; playerKey?: string | null; src?: string | null; size?: 'sm' | 'lg' | 'xl'; dead?: boolean; title?: string }) {
  const [failed, setFailed] = useState<string | null>(null);
  const showImage = !!src && failed !== src;
  return (
    <span
      className={`avatar${size ? ` avatar--${size}` : ''}${dead ? ' avatar--dead' : ''}${showImage ? ' avatar--img' : ''}`}
      style={{ ['--av' as string]: playerColor(colorId, playerKey) }}
      title={title}
      aria-hidden={title ? undefined : true}
    >
      {showImage && <img src={src!} alt="" loading="lazy" decoding="async" onError={() => setFailed(src!)} />}
    </span>
  );
}

export function PlayerLink({ player, dead, children }: { player: PlayerRef | { key: string | null; name: string; colorId: number | null; avatar?: string | null }; dead?: boolean; children?: ReactNode }) {
  const content = (
    <>
      <Avatar colorId={player.colorId} playerKey={player.key} src={player.avatar} dead={dead} />
      <span className="player__name">{player.name}</span>
      {children}
    </>
  );
  if (!player.key) return <span className="player">{content}</span>;
  return (
    <Link className="player" to={`/spieler/${player.key}`}>
      {content}
    </Link>
  );
}

export function TeamPill({ team, label }: { team: Team; label?: string }) {
  return (
    <span className={teamPillClass(team)}>
      <span className="pill__dot" />
      {label ?? TEAM_WIN_LABEL[team]}
    </span>
  );
}

export function StatCard({ icon, label, value, unit, chip, caption, raw }: { icon: ReactNode; label: string; value: number; unit?: string; chip?: string; caption?: ReactNode; raw?: string }) {
  return (
    <div className="panel stat">
      <div className="stat__top">
        <span className="stat__label">
          {icon}
          {label}
        </span>
        {chip && <span className="chip">{chip}</span>}
      </div>
      <div className="stat__value">
        {raw ?? <Count value={value} />}
        {unit && <small>{unit}</small>}
      </div>
      {caption && <span className="caption">{caption}</span>}
    </div>
  );
}

export function Section({ title, eyebrow, action, children, id }: { title: ReactNode; eyebrow?: string; action?: ReactNode; children: ReactNode; id?: string }) {
  return (
    <section className="section" id={id}>
      <Reveal>
        <div className="section__head">
          <div className="stack" style={{ gap: 6 }}>
            {eyebrow && <span className="eyebrow">{eyebrow}</span>}
            <h2 className="section__title">{title}</h2>
          </div>
          {action}
        </div>
      </Reveal>
      <Reveal delay={0.05}>{children}</Reveal>
    </section>
  );
}

export interface BarItem {
  label: ReactNode;
  value: number;
  text: string;
  tone?: 'accent' | 'moss' | 'ember' | 'gold';
  title?: string;
}

/** Waagrechte Balken (flach, Wert immer als Text daneben). */
export function BarList({ items, max }: { items: BarItem[]; max?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const top = max ?? Math.max(1, ...items.map((i) => i.value));
  useGrow(ref, 'x', [items.length]);
  if (!items.length) return <p className="caption">Noch keine Daten.</p>;
  return (
    <div className="bars" ref={ref}>
      {items.map((item, index) => (
        <div className="bar" key={index} title={item.title}>
          <span className="bar__label">{item.label}</span>
          <div className="bar__track">
            <div
              data-grow
              className={`bar__fill${item.tone && item.tone !== 'accent' ? ` bar__fill--${item.tone}` : ''}`}
              style={{ width: `${Math.max(1.5, (item.value / top) * 100)}%` }}
            />
          </div>
          <span className="bar__value">{item.text}</span>
        </div>
      ))}
    </div>
  );
}

export interface BoardRow {
  player: PlayerRef;
  value: string;
}

export function Leaderboard({ icon, title, rows, more }: { icon: ReactNode; title: string; rows: BoardRow[]; more?: { to: string; label: string } }) {
  return (
    <div className="panel board">
      <h3 className="panel__title">
        {icon}
        {title}
      </h3>
      {rows.length === 0 ? (
        <p className="caption">Noch niemand mit genug Runden.</p>
      ) : (
        <ol>
          {rows.map((row, index) => (
            <li key={row.player.key}>
              <span className={`rank rank--${index + 1}`}>{index + 1}</span>
              <Avatar colorId={row.player.colorId} playerKey={row.player.key} src={row.player.avatar} />
              <Link className="name" to={`/spieler/${row.player.key}`}>
                {row.player.name}
              </Link>
              <span className="val">{row.value}</span>
            </li>
          ))}
        </ol>
      )}
      {more && (
        <Link className="btn btn--ghost" to={more.to} style={{ marginTop: 12, marginLeft: -16 }}>
          {more.label}
        </Link>
      )}
    </div>
  );
}

export function Roster({ roster }: { roster: RoundItem['roster'] }) {
  return (
    <span className="roster" aria-hidden="true">
      {roster.map((p) => (
        <span
          key={p.key}
          className={`avatar${p.team === 'IMPOSTOR' ? ' imp' : ''}`}
          style={{ ['--av' as string]: playerColor(p.colorId, p.key) }}
          title={p.name}
        />
      ))}
    </span>
  );
}

export function RoundRow({ round, extra }: { round: RoundItem; extra?: ReactNode }) {
  return (
    <Link className="round-row" to={`/runden/${round.gameId}`}>
      <span className="round-row__when">
        <b>{day(round.endedAt)} {dayMonth(round.endedAt)}</b>
        <span className="caption">{time(round.endedAt)} Uhr</span>
      </span>
      <span className="round-row__map">
        {mapShort(round.mapId, round.map)}
        {extra && <span className="caption" style={{ display: 'block' }}>{extra}</span>}
      </span>
      <span className="round-row__result">
        <TeamPill team={round.winningTeam} />
      </span>
      <span className="round-row__roster">
        <Roster roster={round.roster} />
      </span>
      <span className="round-row__meta">
        {duration(round.duration)}
        <br />
        {num(round.kills)} Kills
      </span>
      {round.replay ? <Film className="icon icon--sm" aria-label="Replay" /> : <ChevronRight className="icon icon--sm" aria-hidden="true" />}
    </Link>
  );
}

export function RoundList({ rounds, extra }: { rounds: RoundItem[]; extra?: (round: RoundItem) => ReactNode }) {
  if (!rounds.length) return <Empty text="Noch keine Runden." />;
  return (
    <div className="panel rounds">
      {rounds.map((round) => (
        <RoundRow key={round.gameId} round={round} extra={extra?.(round)} />
      ))}
    </div>
  );
}

export function Form({ results }: { results: boolean[] }) {
  return (
    <span className="form" aria-label={`Letzte ${results.length} Runden: ${results.filter(Boolean).length} Siege`}>
      {results.map((won, i) => (
        <i key={i} className={won ? 'w' : 'l'} title={won ? 'Sieg' : 'Niederlage'} />
      ))}
    </span>
  );
}

export function Segmented<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string }[]; onChange: (value: T) => void; label: string }) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((option) => (
        <button key={option.value} type="button" aria-pressed={option.value === value} onClick={() => onChange(option.value)}>
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function Loading() {
  return (
    <div className="loading" role="status" aria-label="Lädt">
      <span className="loading__blocks">
        <i />
        <i />
        <i />
      </span>
    </div>
  );
}

export function Empty({ text, hint }: { text: string; hint?: ReactNode }) {
  return (
    <div className="panel empty">
      <Inbox className="icon" aria-hidden="true" />
      <p className="ink">{text}</p>
      {hint && <p className="caption">{hint}</p>}
    </div>
  );
}

export function Failed({ error }: { error: Error | null }) {
  return (
    <div className="panel empty">
      <CircleAlert className="icon" style={{ color: 'var(--ember)' }} aria-hidden="true" />
      <p className="ink">Das hat nicht geklappt.</p>
      <p className="caption">{error?.message ?? 'Unbekannter Fehler'}</p>
    </div>
  );
}

/** Lädt-Anzeige, Fehler oder Inhalt. */
export function Async<T>({ state, children }: { state: { data: T | null; error: Error | null; loading: boolean }; children: (data: T) => ReactNode }) {
  if (state.data != null) return <>{children(state.data)}</>;
  if (state.error) return <Failed error={state.error} />;
  return <Loading />;
}
