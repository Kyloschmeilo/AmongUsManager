// Bot steuern über die Website (StatsWebsite.jar → ControlApi.kt → Datenbank → Bot, WebControlModule.kt).
// Jede Aktion ist ein POST /api/bot/<aktion>; der Bot prüft die Rechte selbst über die Discord-Rollen.
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useMe } from './me';

export interface Person {
  key: string | null;
  name: string;
  /** Gibt es ein Spielerprofil auf der Website? */
  profile?: boolean;
  avatar?: string | null;
  colorId?: number | null;
}

export interface Permissions {
  inGuild: boolean;
  name?: string;
  admin: boolean;
  eventMaker: boolean;
  eventMakerRole?: string | null;
}

export interface GameEvent {
  id: string;
  game: string;
  startsAt: string;
  endsAt: string;
  running: boolean;
  closed: boolean;
  creator: string | null;
  channel: string | null;
  messageUrl: string;
  discordEventUrl: string | null;
  joined: boolean;
  roster: Person[];
}

export interface EventsResponse {
  events: GameEvent[];
  games: string[];
  canCreate: boolean;
  eventChannel: string | null;
  minAttendance: number;
  attendanceXp: number;
  durationHours: number;
}

export interface ActionResult {
  ok?: boolean;
  message?: string;
  webhookUrl?: string;
}

export class BotError extends Error {
  constructor(message: string, public status: number, public offline = false) {
    super(message);
  }
}

export async function callBot<T>(action: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api/bot/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
    body: JSON.stringify(body ?? {})
  });
  const data = (await response.json().catch(() => null)) as ({ error?: string; offline?: boolean } & T) | null;
  if (!response.ok || !data || data.error) {
    throw new BotError(data?.error ?? `Server antwortet mit ${response.status}`, response.status, !!data?.offline);
  }
  return data;
}

export type BotState<T> = { data: T | null; error: BotError | null; loading: boolean; reload: () => void };

/** Lädt eine Lese-Aktion des Bots; reload() holt neu (ohne die alten Daten wegzuwerfen). */
export function useBot<T>(action: string | null, body?: unknown, refreshMs?: number): BotState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<BotError | null>(null);
  const [loading, setLoading] = useState(action !== null);
  const [tick, setTick] = useState(0);
  const key = JSON.stringify(body ?? {});
  useEffect(() => {
    if (action === null) return;
    let active = true;
    setLoading(true);
    callBot<T>(action, JSON.parse(key)).then(
      (result) => {
        if (!active) return;
        setData(result);
        setError(null);
        setLoading(false);
      },
      (e: BotError) => {
        if (!active) return;
        setError(e);
        setLoading(false);
      }
    );
    return () => {
      active = false;
    };
  }, [action, key, tick]);
  useEffect(() => {
    if (!refreshMs || action === null) return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') setTick((t) => t + 1);
    }, refreshMs);
    return () => clearInterval(timer);
  }, [refreshMs, action]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, reload };
}

// ---------- Rechte der angemeldeten Person ----------

interface PermissionState {
  /** null = lädt noch / nicht angemeldet / Bot nicht erreichbar */
  perms: Permissions | null;
  refresh: () => void;
}

const PermissionContext = createContext<PermissionState>({ perms: null, refresh: () => undefined });

export function PermissionProvider({ children }: { children: ReactNode }) {
  const { me } = useMe();
  const [perms, setPerms] = useState<Permissions | null>(null);
  const [tick, setTick] = useState(0);
  const loggedIn = !!me?.loggedIn;
  useEffect(() => {
    if (!loggedIn) {
      setPerms(null);
      return;
    }
    let active = true;
    callBot<Permissions>('me').then(
      (p) => active && setPerms(p),
      () => active && setPerms(null)
    );
    return () => {
      active = false;
    };
  }, [loggedIn, tick]);
  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return <PermissionContext.Provider value={{ perms, refresh }}>{children}</PermissionContext.Provider>;
}

export const usePermissions = (): PermissionState => useContext(PermissionContext);

// ---------- Formular-Helfer ----------

/** Eine Aktion ausführen und Ergebnis/Fehler als Meldung anzeigen. */
export function useAction(onDone?: () => void) {
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ text: string; error: boolean } | null>(null);
  const run = useCallback(
    async <T extends ActionResult>(id: string, action: string, body?: unknown): Promise<T | null> => {
      setBusy(id);
      setNotice(null);
      try {
        const result = await callBot<T>(action, body);
        setNotice({ text: result.message ?? 'Erledigt.', error: false });
        onDone?.();
        return result;
      } catch (e) {
        setNotice({ text: (e as Error).message, error: true });
        return null;
      } finally {
        setBusy(null);
      }
    },
    [onDone]
  );
  return { busy, notice, setNotice, run };
}
