// Live-Lobbys (StatsWebsite.jar → web/LiveLobbies.kt): wer gerade in welcher Lobby ist, „Beitreten“ über Auto-Join der Mod.
import { useCallback, useEffect, useRef, useState } from 'react';
import { postJson } from './api';

export interface LobbyPlayer {
  name: string;
  /** Spieler-Schlüssel, nur wenn es ein Profil auf der Website gibt. */
  key: string | null;
  profile: boolean;
  colorId: number | null;
  avatar: string | null;
  level: number | null;
  games: number | null;
  wins: number | null;
  host: boolean;
  me: boolean;
}

export interface LiveLobby {
  id: string;
  /** Nur für angemeldete, verknüpfte Spieler. */
  code: string | null;
  state: 'open' | 'started';
  map: string;
  players: number;
  max: number;
  host: string;
  region: string | null;
  /** Sekunden seit der letzten Meldung des Hosts. */
  age: number;
  full: boolean;
  joinable: boolean;
  list: LobbyPlayer[];
}

export interface LobbiesResponse {
  lobbies: LiveLobby[];
  me: {
    loggedIn: boolean;
    linked: boolean;
    inLobby: string | null;
    join: { code: string; sent: boolean; age: number } | null;
  } | null;
  updatedAt?: string;
}

/** Lädt /api/lobbies und fragt alle [intervalMs] neu (nur solange der Tab sichtbar ist). */
export function useLobbies(intervalMs = 5000) {
  const [data, setData] = useState<LobbiesResponse | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const active = useRef(true);

  const load = useCallback(async () => {
    try {
      const response = await fetch('/api/lobbies', { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
      if (!response.ok) throw new Error(`Server antwortet mit ${response.status}`);
      const body = (await response.json()) as LobbiesResponse;
      if (active.current) {
        setData(body);
        setError(null);
      }
    } catch (e) {
      if (active.current) setError(e as Error);
    }
  }, []);

  useEffect(() => {
    active.current = true;
    void load();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, intervalMs);
    const onVisible = () => { if (document.visibilityState === 'visible') void load(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active.current = false;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [load, intervalMs]);

  return { data, error, reload: load };
}

export function joinLobby(code: string): Promise<{ ok: boolean; message: string }> {
  return postJson('/api/lobbies/join', { code });
}

export function cancelJoin(): Promise<{ ok: boolean; message: string }> {
  return postJson('/api/lobbies/join', { cancel: 'true' });
}
