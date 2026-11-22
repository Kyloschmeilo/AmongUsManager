// Hell/Dunkel für Stats-Website und Spiele-Startseite (21.11.2026, Komfort-Ideen). Eine Einstellung für beide Seiten
// (gleiche Adresse → gleicher localStorage), Standard = Dunkel wie bisher. Gesetzt wird `data-theme` am <html>.
import { useEffect, useState } from 'react';

export type Theme = 'dark' | 'light';
export const THEME_KEY = 'spiele.thema';

export function storedTheme(): Theme {
  try {
    return localStorage.getItem(THEME_KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  if (theme === 'light') root.dataset.theme = 'light';
  else delete root.dataset.theme;
  root.style.colorScheme = theme;
  const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (meta) {
    meta.dataset.dark ??= meta.content; // Farbe der jeweiligen Seite im dunklen Design
    meta.content = theme === 'light' ? (meta.dataset.light ?? '#f4f1ea') : meta.dataset.dark;
  }
}

/** Gleich beim Laden aufrufen (vor dem ersten Rendern), damit nichts aufblitzt. */
export function initTheme() {
  applyTheme(storedTheme());
  window.addEventListener('storage', (e) => { if (e.key === THEME_KEY) applyTheme(storedTheme()); });
}

export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(storedTheme);
  useEffect(() => {
    const sync = (e: StorageEvent) => { if (e.key === THEME_KEY) setTheme(storedTheme()); };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, []);
  const toggle = () => {
    const next: Theme = theme === 'light' ? 'dark' : 'light';
    try { localStorage.setItem(THEME_KEY, next); } catch { /* privater Modus: gilt nur für diesen Besuch */ }
    applyTheme(next);
    setTheme(next);
  };
  return [theme, toggle];
}

/** Knopf Sonne/Mond. [className] für das Aussehen der jeweiligen Seite. */
export function ThemeToggle({ className }: { className: string }) {
  const [theme, toggle] = useTheme();
  const light = theme === 'light';
  return (
    <button type="button" className={className} onClick={toggle} aria-pressed={light}
      title={light ? 'Dunkles Design' : 'Helles Design'} aria-label={light ? 'Zum dunklen Design wechseln' : 'Zum hellen Design wechseln'}>
      {light ? (
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z" /></svg>
      ) : (
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" /><path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1" /></svg>
      )}
    </button>
  );
}
