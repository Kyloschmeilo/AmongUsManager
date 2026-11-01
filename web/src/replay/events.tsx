// Ereignisse der Zeitleiste als Text („Mia hat Tom getötet (Electrical)“).
import type { ReactNode } from 'react';
import type { RoundDetail, RoundEvent } from '../lib/api';
import { deathCause, humanize, room, system, task } from '../lib/labels';
import { duration } from '../lib/format';

export const IMPORTANT = new Set([
  'kill', 'death', 'meeting', 'exile', 'sabotage', 'sabotageEnd', 'disconnect', 'roleChange', 'shapeshift', 'protect', 'revive'
]);

export function nameLookup(round: RoundDetail): (id: number | null | undefined) => string {
  const names = new Map(round.players.map((p) => [p.playerId, p.name]));
  return (id) => (id == null ? '?' : names.get(id) ?? `Spieler ${id}`);
}

/** Zeitleiste einer Runde: die Ereignisse der Mod (Format 2) oder – bei alten Runden – aus Kills und Meetings gebaut. */
export function timelineOf(round: RoundDetail): RoundEvent[] {
  if (round.events && round.events.length) return round.events;
  const events: RoundEvent[] = [];
  for (const kill of round.kills) events.push({ atSeconds: kill.atSeconds, type: 'kill', player: kill.killer, target: kill.victim, room: kill.room ?? undefined });
  for (const m of round.meetings) {
    events.push({ atSeconds: m.atSeconds, type: 'meeting', player: m.caller, target: m.body ?? undefined, reason: m.body == null ? 'emergency' : 'report' });
    events.push({ atSeconds: m.endedAtSeconds ?? m.atSeconds, type: 'exile', target: m.exiled ?? undefined, tie: m.tie });
  }
  return events.sort((a, b) => a.atSeconds - b.atSeconds);
}

export function describe(e: RoundEvent, name: (id: number | null | undefined) => string): ReactNode {
  const p = <b>{name(e.player)}</b>;
  const t = <b>{name(e.target)}</b>;
  const where = e.room ? ` (${room(e.room)})` : '';
  switch (e.type) {
    case 'kill':
      return <>{p} <span className="k">tötet</span> {t}{where}</>;
    case 'death':
      return <>{t} stirbt: {deathCause(e.reason)}{e.player != null && e.player !== e.target ? <> durch {p}</> : null}</>;
    case 'meeting':
      return e.reason === 'report' || e.target != null
        ? <>{p} meldet die Leiche von {t}{where}</>
        : <>{p} ruft ein Notfall-Meeting{where}</>;
    case 'vote':
      return e.target != null ? <>{p} stimmt für {t}</> : <>{p} überspringt</>;
    case 'exile':
      return e.target != null ? <>{t} wurde rausgewählt</> : <>Niemand fliegt raus{e.tie ? ' (Gleichstand)' : ''}</>;
    case 'task':
      return <>{p} erledigt {task(e.task)}{where}</>;
    case 'sabotage':
      return <>Sabotage: {system(e.system)}{e.player != null ? <> von {p}</> : null}</>;
    case 'sabotageEnd':
      return (
        <>
          {system(e.system)} {e.reason === 'fixed' ? 'repariert' : e.reason === 'meeting' ? 'durch Meeting beendet' : 'vorbei'}
          {e.durationSeconds != null ? ` nach ${duration(e.durationSeconds)}` : ''}
        </>
      );
    case 'repair':
      return <>{p} hilft beim Reparieren ({system(e.system)})</>;
    case 'doors':
      return <>Türen zu: {room(e.room)}{e.player != null ? <> von {p}</> : null}</>;
    case 'ventEnter':
      return <>{p} steigt in einen Vent{where}</>;
    case 'ventExit':
      return <>{p} kommt aus dem Vent{where}</>;
    case 'disconnect':
      return <>{p} hat das Spiel verlassen</>;
    case 'roleChange':
      return <>{p} ist jetzt {humanize(e.role ?? '?')}</>;
    case 'shapeshift':
      return e.reason === 'revert' ? <>{p} verwandelt sich zurück</> : <>{p} verwandelt sich in {t}</>;
    case 'protect':
      return <>{p} beschützt {t}</>;
    case 'revive':
      return <>{p} wurde wiederbelebt</>;
    default:
      return <>{humanize(e.type)}</>;
  }
}
