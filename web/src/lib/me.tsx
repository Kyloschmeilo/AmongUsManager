// Wer ist angemeldet? (Discord-Login der Website) – einmal laden, überall lesen.
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { clearApiCache, fetchMe, postJson, type Me, type ProfileSettings } from './api';

interface MeState {
  /** null = lädt noch (oder Server nicht erreichbar). */
  me: Me | null;
  saveProfile: (settings: ProfileSettings) => Promise<void>;
  logout: () => Promise<void>;
}

const MeContext = createContext<MeState>({
  me: null,
  saveProfile: async () => undefined,
  logout: async () => undefined
});

export function MeProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    let active = true;
    fetchMe().then(
      (data) => active && setMe(data),
      () => active && setMe({ loginEnabled: false, loggedIn: false })
    );
    return () => {
      active = false;
    };
  }, []);

  const saveProfile = useCallback(async (settings: ProfileSettings) => {
    const data = await postJson<Me>('/api/me/profile', settings);
    setMe(data);
    clearApiCache(); // Name/Bild/Profil stehen überall – alles neu laden
  }, []);

  const logout = useCallback(async () => {
    const data = await postJson<Me>('/auth/logout');
    setMe(data);
  }, []);

  return <MeContext.Provider value={{ me, saveProfile, logout }}>{children}</MeContext.Provider>;
}

export const useMe = (): MeState => useContext(MeContext);
