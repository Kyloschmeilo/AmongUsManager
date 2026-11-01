// Deutsche Texte für Werte aus den Rundendaten (Spielenden, Todesarten, Räume, Sabotagen …).
import type { Team } from './api';

export const TEAM_LABEL: Record<Team, string> = {
  CREW: 'Crew',
  IMPOSTOR: 'Impostor',
  NEUTRAL: 'Neutral',
  UNKNOWN: 'Unbekannt'
};

export const TEAM_WIN_LABEL: Record<Team, string> = {
  CREW: 'Crew gewinnt',
  IMPOSTOR: 'Impostor gewinnen',
  NEUTRAL: 'Sondersieg',
  UNKNOWN: 'Kein Ergebnis'
};

/** Farbe eines Teams: Crew = Akzent, Impostor = Ember, Neutral = Gold (selten), Unbekannt = grau. */
export const TEAM_COLOR: Record<Team, string> = {
  CREW: 'var(--accent)',
  IMPOSTOR: 'var(--ember)',
  NEUTRAL: 'var(--gold)',
  UNKNOWN: 'var(--ink-faint)'
};

export const teamPillClass = (team: Team): string =>
  team === 'CREW' ? 'pill' : team === 'IMPOSTOR' ? 'pill pill--ember' : team === 'NEUTRAL' ? 'pill pill--gold' : 'pill pill--plain';

const END_REASONS: Record<string, string> = {
  HumansByTask: 'Alle Aufgaben erledigt',
  HumansByVote: 'Alle Impostor rausgewählt',
  HumansDisconnect: 'Impostor haben verlassen',
  ImpostorByKill: 'Genug Crew getötet',
  ImpostorBySabotage: 'Sabotage nicht repariert',
  ImpostorByVote: 'Crew rausgewählt',
  ImpostorDisconnect: 'Crew hat verlassen',
  JesterWin: 'Jester gewinnt',
  ArsonistWin: 'Arsonist gewinnt',
  JackalWin: 'Jackal gewinnt',
  LoversWin: 'Liebespaar gewinnt',
  VultureWin: 'Vulture gewinnt',
  LawyerSoloWin: 'Lawyer gewinnt',
  ProsecutorWin: 'Prosecutor gewinnt',
  EveryoneLose: 'Alle verlieren',
  DeadlockDraw: 'Unentschieden',
  ForceEnd: 'Abgebrochen',
  MiniLose: 'Mini wurde rausgewählt',
  TaskMasterCrewWin: 'Task Master gewinnt',
  OpportunistWin: 'Opportunist gewinnt',
  PlagueDoctorWin: 'Plague Doctor gewinnt',
  FoxWin: 'Fox gewinnt',
  AkujoWin: 'Akujo gewinnt',
  MoriartyWin: 'Moriarty gewinnt',
  CupidLoversWin: 'Liebespaar gewinnt',
  JekyllAndHydeWin: 'Jekyll und Hyde gewinnen',
  SchrodingersCatWin: 'Schrödingers Katze gewinnt',
  YasunaJrWin: 'Yasuna Jr. gewinnt'
};

/** "JesterWin" -> "Jester Win", "UpperEngine" -> "Upper Engine". */
export const humanize = (value: string): string =>
  value
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z0-9])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .trim();

export const endReason = (reason: string): string => END_REASONS[reason] ?? (reason ? humanize(reason) : 'Unbekannt');

const DEATH_CAUSES: Record<string, string> = {
  kill: 'Getötet',
  ejected: 'Rausgewählt',
  disconnect: 'Verlassen',
  suicide: 'Selbst-Kill',
  guess: 'Erraten',
  misfire: 'Fehlschuss',
  jailed: 'Eingesperrt',
  loverSuicide: 'Liebeskummer',
  lawyerSuicide: 'Lawyer gescheitert',
  arson: 'Verbrannt',
  bomb: 'Bombe',
  exile: 'Rausgewählt'
};

export const deathCause = (cause: string | null | undefined): string =>
  cause ? DEATH_CAUSES[cause] ?? humanize(cause) : 'Überlebt';

const ROOMS: Record<string, string> = {
  LifeSupp: 'O2',
  Nav: 'Navigation',
  UpperEngine: 'Upper Engine',
  LowerEngine: 'Lower Engine',
  MedBay: 'MedBay',
  Comms: 'Comms',
  VaultRoom: 'Vault',
  MeetingRoom: 'Meeting Room',
  HallOfPortraits: 'Hall of Portraits',
  CargoBay: 'Cargo Bay',
  ViewingDeck: 'Viewing Deck',
  GapRoom: 'Gap Room',
  MainHall: 'Main Hall',
  BoilerRoom: 'Boiler Room',
  LockerRoom: 'Locker Room',
  Decontamination2: 'Decontamination',
  Decontamination3: 'Decontamination',
  SleepingQuarters: 'Sleeping Quarters',
  FishingDock: 'Fishing Dock',
  MiningPit: 'Mining Pit',
  RecRoom: 'Rec Room'
};

export const room = (name: string | null | undefined): string => (name ? ROOMS[name] ?? humanize(name) : 'Flur');

const SYSTEMS: Record<string, string> = {
  Reactor: 'Reaktor',
  Laboratory: 'Reaktor',
  Oxygen: 'Sauerstoff',
  LifeSupp: 'Sauerstoff',
  Lights: 'Licht',
  Electrical: 'Licht',
  Comms: 'Kommunikation',
  Crash: 'Absturz',
  HeliSabotage: 'Absturz',
  MushroomMixup: 'Pilz-Chaos',
  MushroomMixupSabotage: 'Pilz-Chaos'
};

export const system = (name: string | null | undefined): string => (name ? SYSTEMS[name] ?? humanize(name) : '?');

export const task = (name: string | null | undefined): string => (name ? humanize(name) : 'Aufgabe');

const MAP_SHORT: Record<number, string> = { 0: 'Skeld', 1: 'MIRA HQ', 2: 'Polus', 3: 'dlekS', 4: 'Airship', 5: 'Fungle' };
export const mapShort = (mapId: number, fallback: string): string => MAP_SHORT[mapId] ?? fallback;

export const RECORD_LABEL: Record<string, { title: string; unit: 'count' | 'seconds' | 'distance'; who: 'player' | 'round' }> = {
  mostKills: { title: 'Meiste Kills in einer Runde', unit: 'count', who: 'player' },
  fastestKill: { title: 'Schnellster Kill', unit: 'seconds', who: 'player' },
  fastestTasks: { title: 'Schnellste Aufgaben', unit: 'seconds', who: 'player' },
  mostDistance: { title: 'Weiteste Strecke', unit: 'distance', who: 'player' },
  longestRound: { title: 'Längste Runde', unit: 'seconds', who: 'round' },
  shortestRound: { title: 'Kürzeste Runde', unit: 'seconds', who: 'round' },
  mostMeetings: { title: 'Meiste Meetings', unit: 'count', who: 'round' }
};

export const PLATFORMS: Record<string, string> = {
  StandaloneSteamPC: 'Steam',
  StandaloneEpicPC: 'Epic',
  StandaloneWin10: 'Microsoft Store',
  StandaloneItch: 'itch.io',
  StandaloneMac: 'Mac',
  Android: 'Android',
  IPhone: 'iPhone',
  Switch: 'Switch',
  Xbox: 'Xbox',
  Playstation: 'PlayStation'
};

/** "TheOtherRoles GMIA 1.4.1" -> "The Other Roles GMIA 1.4.1" */
export const modLabel = (mod: string): string => mod.replace(/TheOtherRoles/g, 'The Other Roles');
