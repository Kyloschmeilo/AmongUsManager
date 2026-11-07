// Kennzahlen der Bestenliste: Wert, Anzeige, Mindestanzahl Runden (damit 1 Glücksrunde nicht Platz 1 ist).
import type { PlayerSummary } from './api';
import { dec, distance, duration, num, pct, ratio } from './format';

export interface Metric {
  id: string;
  label: string;
  group: 'Allgemein' | 'Crew' | 'Impostor' | 'Details';
  description: string;
  value: (p: PlayerSummary) => number;
  format: (p: PlayerSummary) => string;
  detail: (p: PlayerSummary) => string;
  /** Wer zählt überhaupt (z.B. mindestens 5 Runden). */
  eligible: (p: PlayerSummary) => boolean;
  minLabel?: string;
}

const games = (p: PlayerSummary) => `${num(p.games)} Runden`;

export const METRICS: Metric[] = [
  {
    id: 'wins', label: 'Siege', group: 'Allgemein', description: 'Gewonnene Runden, egal in welchem Team.',
    value: (p) => p.wins, format: (p) => num(p.wins), detail: (p) => `${pct(p.wins, p.games)} von ${games(p)}`, eligible: (p) => p.games > 0
  },
  {
    id: 'winrate', label: 'Siegquote', group: 'Allgemein', description: 'Anteil gewonnener Runden.', minLabel: 'ab 5 Runden',
    value: (p) => ratio(p.wins, p.games), format: (p) => pct(p.wins, p.games), detail: (p) => `${num(p.wins)} von ${games(p)}`, eligible: (p) => p.games >= 5
  },
  {
    id: 'games', label: 'Runden', group: 'Allgemein', description: 'Gespielte Runden.',
    value: (p) => p.games, format: (p) => num(p.games), detail: (p) => `${duration(p.playSeconds)} Spielzeit`, eligible: (p) => p.games > 0
  },
  {
    id: 'survival', label: 'Überlebt', group: 'Allgemein', description: 'Anteil der Runden, in denen man am Ende noch lebt.', minLabel: 'ab 5 Runden',
    value: (p) => ratio(p.survived, p.games), format: (p) => pct(p.survived, p.games), detail: (p) => `${num(p.survived)} von ${games(p)}`, eligible: (p) => p.games >= 5
  },
  {
    id: 'crewrate', label: 'Crew-Siegquote', group: 'Crew', description: 'Siege als Crewmate.', minLabel: 'ab 5 Crew-Runden',
    value: (p) => ratio(p.crewWins, p.crewGames), format: (p) => pct(p.crewWins, p.crewGames), detail: (p) => `${num(p.crewWins)} von ${num(p.crewGames)}`, eligible: (p) => p.crewGames >= 5
  },
  {
    id: 'votes', label: 'Richtige Stimmen', group: 'Crew', description: 'Stimmen als Crewmate gegen einen Impostor, im Verhältnis zu allen Stimmen auf Spieler.', minLabel: 'ab 8 Stimmen',
    value: (p) => ratio(p.correctVotes, p.crewVotes), format: (p) => pct(p.correctVotes, p.crewVotes), detail: (p) => `${num(p.correctVotes)} von ${num(p.crewVotes)} Stimmen`, eligible: (p) => p.crewVotes >= 8
  },
  {
    id: 'ejections', label: 'Richtige Rauswürfe', group: 'Crew', description: 'Rauswürfe, bei denen man als Crewmate mitgestimmt hat: wie oft flog dabei ein Impostor raus statt eines Crewmates.', minLabel: 'ab 5 Rauswürfen',
    value: (p) => ratio(p.rightEjections, p.rightEjections + p.wrongEjections), format: (p) => pct(p.rightEjections, p.rightEjections + p.wrongEjections),
    detail: (p) => `${num(p.rightEjections)} Impostor, ${num(p.wrongEjections)} Crewmates`, eligible: (p) => p.rightEjections + p.wrongEjections >= 5
  },
  {
    id: 'tasks', label: 'Aufgaben', group: 'Crew', description: 'Erledigte Aufgaben als Crewmate.',
    value: (p) => p.tasksDone, format: (p) => num(p.tasksDone), detail: (p) => `${pct(p.tasksDone, p.tasksTotal)} erledigt`, eligible: (p) => p.tasksTotal > 0
  },
  {
    id: 'reports', label: 'Leichen gemeldet', group: 'Crew', description: 'Gefundene und gemeldete Leichen.',
    value: (p) => p.bodiesReported, format: (p) => num(p.bodiesReported), detail: (p) => `${num(p.meetingsCalled)} Notfall-Meetings`, eligible: (p) => p.games > 0
  },
  {
    id: 'kills', label: 'Kills', group: 'Impostor', description: 'Alle Kills (mit The Other Roles auch Sheriff- und Guesser-Abschüsse).',
    value: (p) => p.kills, format: (p) => num(p.kills), detail: (p) => `${num(p.firstKills)} × erster Kill`, eligible: (p) => p.kills > 0
  },
  {
    id: 'kpg', label: 'Kills pro Impostor-Runde', group: 'Impostor', description: 'Durchschnittliche Kills, wenn man Impostor ist.', minLabel: 'ab 3 Impostor-Runden',
    value: (p) => ratio(p.impostorKills, p.impostorGames), format: (p) => dec(ratio(p.impostorKills, p.impostorGames)), detail: (p) => `${num(p.impostorKills)} Kills in ${num(p.impostorGames)} Runden`, eligible: (p) => p.impostorGames >= 3
  },
  {
    id: 'imprate', label: 'Impostor-Siegquote', group: 'Impostor', description: 'Siege als Impostor.', minLabel: 'ab 3 Impostor-Runden',
    value: (p) => ratio(p.impostorWins, p.impostorGames), format: (p) => pct(p.impostorWins, p.impostorGames), detail: (p) => `${num(p.impostorWins)} von ${num(p.impostorGames)}`, eligible: (p) => p.impostorGames >= 3
  },
  {
    id: 'deceptions', label: 'Crew getäuscht', group: 'Impostor', description: 'Als Impostor für einen Crewmate gestimmt, der dann rausgewählt wurde.',
    value: (p) => p.deceptions, format: (p) => num(p.deceptions), detail: (p) => `${num(p.impostorVotes)} Stimmen auf Crewmates`, eligible: (p) => p.deceptions > 0
  },
  {
    id: 'sabotages', label: 'Sabotagen', group: 'Impostor', description: 'Gestartete Sabotagen (seit CrewStats 1.5.0).',
    value: (p) => p.sabotages, format: (p) => num(p.sabotages), detail: (p) => `${num(p.ventUses)} × im Vent`, eligible: (p) => p.sabotages > 0
  },
  {
    id: 'distance', label: 'Strecke', group: 'Details', description: 'Gelaufene Strecke laut Laufweg (seit CrewStats 1.5.0, eine Einheit des Spiels als Meter).',
    value: (p) => p.distance, format: (p) => distance(p.distance), detail: (p) => `in ${num(p.detailGames)} Runden`, eligible: (p) => p.detailGames > 0
  },
  {
    id: 'fixes', label: 'Reparaturen', group: 'Details', description: 'Beendete Sabotagen, an denen man mitrepariert hat (seit CrewStats 1.5.0).',
    value: (p) => p.sabotagesFixed, format: (p) => num(p.sabotagesFixed), detail: (p) => `in ${num(p.detailGames)} Runden`, eligible: (p) => p.sabotagesFixed > 0
  }
];

export const metric = (id: string): Metric => METRICS.find((m) => m.id === id) ?? METRICS[0];

/** Gruppen der Bestenliste in Anzeige-Reihenfolge. */
export const GROUPS: { id: Metric['group']; label: string; caption: string }[] = [
  { id: 'Allgemein', label: 'Allgemein', caption: 'Siege, Runden, Überleben' },
  { id: 'Crew', label: 'Crew', caption: 'Aufgaben, Stimmen, Meldungen' },
  { id: 'Impostor', label: 'Impostor', caption: 'Kills, Täuschung, Sabotagen' },
  { id: 'Details', label: 'Unterwegs', caption: 'Strecke und Reparaturen' }
];

export function rank(players: PlayerSummary[], m: Metric): PlayerSummary[] {
  return players
    .filter((p) => !p.unranked && m.eligible(p))
    .sort((a, b) => m.value(b) - m.value(a) || b.games - a.games || a.name.localeCompare(b.name, 'de'));
}
