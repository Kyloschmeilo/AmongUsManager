// JSON-API des StatsWebsite.jar (src/main/kotlin/.../web/StatsSnapshot.kt) – Typen und Laden.
import { useEffect, useState } from 'react';

export type Team = 'CREW' | 'IMPOSTOR' | 'NEUTRAL' | 'UNKNOWN';

export interface PlayerRef {
  key: string;
  name: string;
  colorId: number | null;
  /** Crewmate-Bild mit meistgetragener Kosmetik (Hut, Visier, Skin, Haustier), null = unbekannt. */
  crewmate?: string | null;
  /** Discord-Profilbild (von der Website ausgeliefert), sonst farbiger Crewmate. */
  avatar?: string | null;
}

export interface RosterEntry extends PlayerRef {
  team: Team;
  won: boolean;
}

export interface RoundItem {
  gameId: string;
  endedAt: string;
  map: string;
  mapId: number;
  duration: number;
  winningTeam: Team;
  endReason: string;
  mod: string | null;
  kills: number;
  meetings: number;
  replay: boolean;
  legacy: boolean;
  roster: RosterEntry[];
}

export interface PlayerSummary {
  key: string;
  name: string;
  colorId: number | null;
  /** Crewmate-Bild mit meistgetragener Kosmetik (Hut, Visier, Skin, Haustier), null = unbekannt. */
  crewmate?: string | null;
  avatar?: string | null;
  linked: boolean;
  level: number | null;
  games: number;
  wins: number;
  crewGames: number;
  crewWins: number;
  impostorGames: number;
  impostorWins: number;
  neutralGames: number;
  neutralWins: number;
  kills: number;
  impostorKills: number;
  firstKills: number;
  deaths: number;
  killed: number;
  ejected: number;
  survived: number;
  disconnects: number;
  tasksDone: number;
  tasksTotal: number;
  meetingsCalled: number;
  bodiesReported: number;
  votes: number;
  skips: number;
  crewVotes: number;
  correctVotes: number;
  distance: number;
  detailGames: number;
  ventUses: number;
  sabotages: number;
  sabotagesFixed: number;
  playSeconds: number;
  firstSeen: string | null;
  lastSeen: string | null;
  favoriteRole: string | null;
}

export interface MapSummary {
  mapId: number;
  map: string;
  rounds: number;
  crewWins: number;
  impostorWins: number;
  neutralWins: number;
  avgDuration: number;
  kills: number;
  meetings: number;
  layout: boolean;
}

export interface RecordEntry {
  id: string;
  value: number;
  player: PlayerRef | null;
  round: { gameId: string; map: string; mapId: number; endedAt: string };
}

export interface Overview {
  updatedAt: string;
  rounds: number;
  replays: number;
  players: number;
  firstRoundAt: string | null;
  lastRoundAt: string | null;
  totals: {
    kills: number;
    deaths: number;
    meetings: number;
    emergencies: number;
    reports: number;
    ejections: number;
    votes: number;
    skips: number;
    tasks: number;
    sabotages: number;
    playSeconds: number;
    distance: number;
    avgDuration: number;
  };
  teams: Record<Team, number>;
  endReasons: { reason: string; count: number }[];
  maps: MapSummary[];
  activity: { date: string; rounds: number }[];
  records: RecordEntry[];
  recent: RoundItem[];
}

export interface RoleRow {
  id: string;
  name: string;
  team: Team | null;
  mod: boolean;
  games: number;
  wins: number;
  kills: number;
  survived: number;
  description?: string | null;
}

export interface ProfileCustom {
  bio: string | null;
  colorId: number | null;
  favoriteRole: { id: string; name: string } | null;
  favoriteMap: { mapId: number; map: string } | null;
  showcase: string[];
}

export interface Profile {
  player: PlayerSummary;
  ingameName: string;
  /** Meistgetragene Kosmetik, null = keine Runde mit Kosmetik-Daten (vor CrewStats 1.5.0). */
  look: Record<'hat' | 'visor' | 'skin' | 'pet', { id: string; name: string } | null> | null;
  custom: ProfileCustom | null;
  names: string[];
  roles: RoleRow[];
  maps: { mapId: number; map: string; games: number; wins: number }[];
  victims: { player: PlayerRef; count: number }[];
  killers: { player: PlayerRef; count: number }[];
  partners: { player: PlayerRef; games: number; wins: number }[];
  deathCauses: { cause: string; count: number }[];
  form: boolean[];
  rounds: {
    round: RoundItem;
    me: { role: string; team: Team; won: boolean; kills: number; dead: boolean; deathCause: string | null; tasksDone: number; tasksTotal: number };
  }[];
}

export interface MapDetail {
  map: MapSummary;
  cellSize: number;
  cells: number[];
  rooms: { room: string; x: number; y: number; kills: number }[];
  vents: { id: number; x: number; y: number }[];
  kills: { x: number; y: number; room: string | null; at: number }[];
  roomKills: { room: string; count: number }[];
  /** Wo der Auslöser beim Meeting stand; report = Leiche gemeldet, sonst Notfall-Knopf. */
  meetingPoints: { x: number; y: number; room: string | null; report: boolean }[];
  roomMeetings: { room: string; count: number }[];
  sabotages: { system: string; count: number; avgDuration: number | null }[];
}

export interface RolesResponse {
  roles: RoleRow[];
  modifiers: { id: string; name: string; games: number; wins: number; description: string | null }[];
}

// ---------- Rohdaten einer Runde (Rundenformat 2, docs/rundenformat.md im Mod-Repo) ----------

export interface RoundPlayer {
  playerId: number;
  key: string | null;
  name: string;
  colorId: number | null;
  avatar?: string | null;
  color?: string | null;
  role: string;
  endRole?: string | null;
  roleName: string | null;
  modRole?: string | null;
  modRoleName?: string | null;
  modStartRole?: string | null;
  modifiers?: string[] | null;
  team: Team;
  won: boolean;
  isDead: boolean;
  disconnected: boolean;
  deathCause?: string | null;
  killedBy?: number | null;
  diedAtSeconds?: number | null;
  disconnectedAtSeconds?: number | null;
  kills: number;
  tasksTotal: number;
  tasksCompleted: number;
  isHost?: boolean;
  level?: number | null;
  platform?: string | null;
  summary?: {
    distance: number;
    aliveSeconds: number;
    meetingsCalled: number;
    bodiesReported: number;
    votesCast: number;
    skips: number;
    correctVotes: number;
    ventUses: number;
    ventSeconds: number;
    sabotages: number;
    sabotagesFixed: number;
    firstTaskSeconds?: number | null;
    allTasksSeconds?: number | null;
  } | null;
}

export interface RoundEvent {
  atSeconds: number;
  type: string;
  player?: number;
  target?: number;
  x?: number;
  y?: number;
  room?: string;
  system?: string;
  task?: string;
  vent?: number;
  role?: string;
  previous?: string;
  reason?: string;
  skipped?: boolean;
  tie?: boolean;
  durationSeconds?: number;
}

export interface RoundMeeting {
  atSeconds: number;
  endedAtSeconds?: number | null;
  caller: number;
  body: number | null;
  callerRoom?: string | null;
  bodyRoom?: string | null;
  votes: { voter: number; target: number | null; skipped?: boolean | null; atSeconds?: number | null }[];
  exiled: number | null;
  tie: boolean;
}

export interface RoundDetail {
  version: number;
  gameId: string;
  lobbyCode?: string;
  map: string;
  mapId?: number;
  startedAt?: string;
  endedAt?: string;
  durationSeconds: number;
  endReason: string;
  winningTeam: Team;
  mod?: string | null;
  modGameMode?: string | null;
  server?: string | null;
  gameVersion?: string | null;
  crewStatsVersion?: string | null;
  players: RoundPlayer[];
  kills: { atSeconds: number; killer: number; victim: number; x?: number | null; y?: number | null; room?: string | null }[];
  meetings: RoundMeeting[];
  events?: RoundEvent[] | null;
  tracks?: { player: number; points: number[] }[] | null;
  settings?: { numImpostors?: number; maxPlayers?: number; ints?: Record<string, number>; floats?: Record<string, number>; bools?: Record<string, boolean> } | null;
  web: { legacy: boolean };
}

// ---------- Anmeldung & eigenes Profil (/api/me) ----------

export interface ProfileSettings {
  displayName: string | null;
  bio: string;
  colorId: number | null;
  favoriteRole: string | null;
  favoriteMap: number | null;
  showcase: string[];
  showAvatar: boolean;
}

export interface ProfileOptions {
  names: string[];
  roles: { id: string; name: string }[];
  maps: { mapId: number; map: string }[];
  colors: number;
  metrics: string[];
  bioMax: number;
  showcaseMax: number;
}

export interface Me {
  loginEnabled: boolean;
  loggedIn: boolean;
  user?: { name: string; avatar: string | null; hasDiscordAvatar: boolean };
  player?: PlayerSummary | null;
  profile?: ProfileSettings;
  options?: ProfileOptions | null;
}

/** Anmelden = ganze Seite zu Discord und zurück (kein fetch). */
export function loginUrl(back: string = window.location.pathname + window.location.search): string {
  return `/auth/login?back=${encodeURIComponent(back.replace(/[?&]login=[^&]*/g, '') || '/')}`;
}

export async function fetchMe(): Promise<Me> {
  const response = await fetch('/api/me', { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
  if (!response.ok) throw new ApiError(response.status);
  return (await response.json()) as Me;
}

/** POST mit JSON; Fehlermeldungen des Servers kommen als Error-Text zurück. */
export async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify(body ?? {})
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error((data as { error?: string } | null)?.error ?? `Server antwortet mit ${response.status}`);
  return data as T;
}

// ---------- Laden ----------

const cache = new Map<string, Promise<unknown>>();

export function fetchJson<T>(path: string): Promise<T> {
  let promise = cache.get(path) as Promise<T> | undefined;
  if (!promise) {
    promise = fetch(`/api/${path}`, { headers: { Accept: 'application/json' } }).then(async (response) => {
      if (!response.ok) {
        cache.delete(path);
        throw new ApiError(response.status);
      }
      return (await response.json()) as T;
    });
    promise.catch(() => cache.delete(path));
    cache.set(path, promise);
  }
  return promise;
}

export class ApiError extends Error {
  constructor(public status: number) {
    super(status === 404 ? 'Nicht gefunden' : `Server antwortet mit ${status}`);
  }
}

/** Neue Runden: Zwischenspeicher alle 2 Minuten leeren, damit ein Neuladen der Seite frische Daten holt. */
setInterval(() => cache.clear(), 120_000);

/** Nach dem Speichern des eigenen Profils: alles neu laden (Namen/Bilder stehen überall). */
export function clearApiCache(): void {
  cache.clear();
  cacheGeneration++;
  listeners.forEach((listener) => listener());
}

let cacheGeneration = 0;
const listeners = new Set<() => void>();

function useCacheGeneration(): number {
  const [generation, setGeneration] = useState(cacheGeneration);
  useEffect(() => {
    const listener = () => setGeneration(cacheGeneration);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);
  return generation;
}

export type Loadable<T> = { data: T; error: null; loading: false } | { data: null; error: Error | null; loading: boolean };

export function useApi<T>(path: string | null): Loadable<T> {
  const [state, setState] = useState<Loadable<T>>({ data: null, error: null, loading: path !== null });
  const generation = useCacheGeneration();
  const [loadedFor, setLoadedFor] = useState<string | null>(null);
  useEffect(() => {
    if (path === null) return;
    let active = true;
    // gleicher Pfad nach clearApiCache(): alte Daten stehen lassen, bis die neuen da sind (kein Flackern)
    if (loadedFor !== path) setState({ data: null, error: null, loading: true });
    fetchJson<T>(path).then(
      (data) => {
        if (!active) return;
        setState({ data, error: null, loading: false });
        setLoadedFor(path);
      },
      (error: Error) => active && setState({ data: null, error, loading: false })
    );
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, generation]);
  return state;
}
