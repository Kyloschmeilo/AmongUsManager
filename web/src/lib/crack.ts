// Crack-Voting (StatsWebsite.jar → web/CrackVoting.kt): nach jeder Runde 15 Minuten lang abstimmen, wer am meisten
// Crack geraucht hat. Eine Stimme je Discord-Konto und Runde, Ergebnis erst nach dem Ende.
import { useCallback, useEffect, useRef, useState } from 'react';
import { postJson } from './api';

export interface CrackPlayer {
  playerId: number;
  key: string | null;
  name: string;
  colorId: number | null;
  avatar: string | null;
  /** Das bin ich (für mich selbst kann ich nicht stimmen). */
  me: boolean;
  /** Stimmen – erst nach dem Ende, vorher null. */
  votes: number | null;
  winner: boolean;
}

export interface CrackPollState {
  gameId: string;
  /** none = diese Runde hat kein Voting (ältere/nachgereichte Runde). */
  state: 'open' | 'closed' | 'none';
  now: string;
  opensAt: string | null;
  closesAt: string | null;
  total: number;
  loggedIn: boolean;
  loginEnabled: boolean;
  myVote: number | null;
  players: CrackPlayer[];
}

export interface OpenCrackPoll {
  gameId: string;
  map: string;
  mapId: number;
  endedAt: string;
  closesAt: string;
  total: number;
  voted: boolean;
  roster: { key: string; name: string; colorId: number | null }[];
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
  if (!response.ok) throw new Error(`Server antwortet mit ${response.status}`);
  return (await response.json()) as T;
}

/** Abstand Server-Uhr zu Browser-Uhr (ms), damit der Countdown auch bei falsch gehender PC-Uhr stimmt. */
export const clockOffset = (serverNow: string): number => new Date(serverNow).getTime() - Date.now();

/** Sekunden bis [iso] nach Server-Uhr, sekündlich neu. */
export function useCountdown(iso: string | null | undefined, offset: number): number {
  const target = iso ? new Date(iso).getTime() : 0;
  const left = () => Math.max(0, Math.round((target - (Date.now() + offset)) / 1000));
  const [seconds, setSeconds] = useState(left);
  useEffect(() => {
    setSeconds(left());
    if (!iso) return;
    const timer = window.setInterval(() => setSeconds(left()), 1000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [iso, offset]);
  return seconds;
}

export const countdown = (seconds: number): string => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;

/** Stand eines Votings; solange es offen ist, alle 10 s neu (und direkt nach dem Ende). */
export function useCrackPoll(gameId: string) {
  const [data, setData] = useState<CrackPollState | null>(null);
  const active = useRef(true);

  const load = useCallback(async () => {
    try {
      const body = await getJson<CrackPollState>(`/api/crack/${encodeURIComponent(gameId)}`);
      if (active.current) setData(body);
    } catch {
      // Voting ist Nebensache – Fehler blenden den Abschnitt einfach aus
    }
  }, [gameId]);

  useEffect(() => {
    active.current = true;
    void load();
    return () => {
      active.current = false;
    };
  }, [load]);

  useEffect(() => {
    if (data?.state !== 'open') return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, 10000);
    const end = data.closesAt ? new Date(data.closesAt).getTime() - (Date.now() + clockOffset(data.now)) : 0;
    const closing = window.setTimeout(() => void load(), Math.max(1000, end + 1500));
    return () => {
      window.clearInterval(timer);
      window.clearTimeout(closing);
    };
  }, [data?.state, data?.closesAt, data?.now, load]);

  const vote = useCallback(async (playerId: number | null) => {
    const body = await postJson<CrackPollState>(`/api/crack/${encodeURIComponent(gameId)}`, { playerId });
    if (active.current) setData(body);
  }, [gameId]);

  return { data, vote, reload: load };
}

/** Offene Votings (für den Hinweis auf der Startseite), alle 30 s neu. */
export function useOpenCrackPolls(intervalMs = 30000) {
  const [data, setData] = useState<{ now: string; loggedIn: boolean; open: OpenCrackPoll[] } | null>(null);
  useEffect(() => {
    let active = true;
    const load = () => getJson<{ now: string; loggedIn: boolean; open: OpenCrackPoll[] }>('/api/crack').then((d) => active && setData(d), () => undefined);
    void load();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, intervalMs);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [intervalMs]);
  return data;
}
