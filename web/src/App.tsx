import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react';
import { NavLink, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useSmoothScroll } from './components/motion';
import { useApi, type Overview } from './lib/api';
import { ago } from './lib/format';
import { Home } from './pages/Home';
import { Leaderboard } from './pages/Leaderboard';
import { PlayerPage } from './pages/Player';
import { RoundsPage } from './pages/Rounds';
import { RoundPage } from './pages/Round';
import { MapsPage, MapPage } from './pages/Maps';
import { RolesPage } from './pages/Roles';
import { BalancePage } from './pages/Balance';
import { DownloadsPage, TutorialsPage } from './pages/Guides';
import { NotFound } from './pages/NotFound';
import { AccountButton, LoginNotice } from './components/Account';
import { MeProvider } from './lib/me';
import { PermissionProvider, usePermissions } from './lib/control';
import { EventsPage } from './pages/Events';
import { AdminPage } from './pages/Admin';
import { SideRails } from './components/SideRails';
import { LobbiesPage } from './pages/Lobbies';
import './control.css';
import './rails.css';

const NAV = [
  { to: '/', label: 'Übersicht', end: true },
  { to: '/lobbys', label: 'Live' },
  { to: '/events', label: 'Events' },
  { to: '/bestenliste', label: 'Bestenliste' },
  { to: '/runden', label: 'Runden' },
  { to: '/maps', label: 'Maps' },
  { to: '/rollen', label: 'Rollen' },
  { to: '/hilfe', label: 'Hilfe & Mods' }
];

/** Navigation; „Admin“ nur für Mitglieder mit der Admin-Rolle im Discord. */
function Nav() {
  const { perms } = usePermissions();
  const items = perms?.admin ? [...NAV, { to: '/admin', label: 'Admin' }] : NAV;
  const ref = useRef<HTMLElement>(null);
  const marker = useRef<HTMLSpanElement>(null);
  const location = useLocation();
  // Der Strich unter dem aktiven Punkt rutscht beim Seitenwechsel hinüber, statt zu springen.
  useLayoutEffect(() => {
    const place = () => {
      const active = ref.current?.querySelector<HTMLElement>('a.active');
      const m = marker.current;
      if (!m) return;
      if (!active) { m.style.opacity = '0'; return; }
      m.style.opacity = '1';
      m.style.width = `${active.offsetWidth - 20}px`;
      m.style.transform = `translateX(${active.offsetLeft + 10}px)`;
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  }, [location.pathname, items.length]);
  return (
    <nav className="nav" aria-label="Hauptnavigation" ref={ref}>
      <span className="nav__marker" ref={marker} aria-hidden="true" />
      {items.map((item) => (
        <NavLink key={item.to} to={item.to} end={'end' in item ? item.end : undefined} className={({ isActive }) => (isActive ? 'active' : undefined)}>
          {item.label}
        </NavLink>
      ))}
    </nav>
  );
}

function Brand() {
  return (
    <Link to="/" className="brand" aria-label="Crew Stats – Übersicht">
      <svg className="brand__mark" viewBox="0 0 20 24" aria-hidden="true">
        <rect x="0" y="8" width="4" height="10" rx="1.5" fill="#9b1212" />
        <path d="M3 9a7 7 0 0 1 14 0v13a2 2 0 0 1-2 2h-3v-4H8v4H5a2 2 0 0 1-2-2z" fill="#d8231c" />
        <rect x="8" y="5" width="11" height="6" rx="3" fill="#a9d9ec" />
        <rect x="14" y="6.5" width="3" height="1.6" rx=".8" fill="#ffffff" opacity=".8" />
      </svg>
      <span className="brand__name">Crew<b>Stats</b></span>
    </Link>
  );
}

function Footer() {
  const overview = useApi<Overview>('overview');
  return (
    <footer className="footer">
      <div className="wrap footer__inner">
        <span className="caption">
          Aufgezeichnet von der Mod CrewStats beim jeweiligen Host, verschickt erst nach Rundenende. Kein offizielles Among-Us-Angebot.
        </span>
        <span className="caption mono">
          {overview.data ? `Aktualisiert ${ago(overview.data.updatedAt)}` : ' '}
        </span>
      </div>
    </footer>
  );
}

/** Dünne Linie unter der Kopfzeile, die mit dem Scrollen wächst. */
function ScrollProgress() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let frame = 0;
    const update = () => {
      frame = 0;
      const el = ref.current;
      if (!el) return;
      const max = document.documentElement.scrollHeight - window.innerHeight;
      el.style.transform = `scaleX(${max > 0 ? Math.min(1, window.scrollY / max) : 0})`;
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => { window.removeEventListener('scroll', onScroll); window.removeEventListener('resize', onScroll); cancelAnimationFrame(frame); };
  }, []);
  return <div className="progress" aria-hidden="true"><div ref={ref} className="progress__bar" /></div>;
}

/** Jede Seite blendet beim Wechsel kurz von unten ein (nur ohne reduzierte Bewegung, per CSS). */
function RouteFade({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  return <div key={pathname} className="route">{children}</div>;
}

export function App() {
  useSmoothScroll();
  return (
    <MeProvider>
      <PermissionProvider>
      <a className="sr-only" href="#inhalt">Zum Inhalt</a>
      <header className="topbar">
        <div className="wrap topbar__inner">
          <Brand />
          <Nav />
          <div className="topbar__account">
            <AccountButton />
          </div>
        </div>
      </header>
      <ScrollProgress />
      <SideRails />
      <main id="inhalt" className="wrap">
        <LoginNotice />
        <RouteFade>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/lobbys" element={<LobbiesPage />} />
          <Route path="/bestenliste" element={<Leaderboard />} />
          <Route path="/spieler/:key" element={<PlayerPage />} />
          <Route path="/runden" element={<RoundsPage />} />
          <Route path="/runden/:gameId" element={<RoundPage />} />
          <Route path="/maps" element={<MapsPage />} />
          <Route path="/maps/:mapId" element={<MapPage />} />
          <Route path="/rollen" element={<RolesPage />} />
          <Route path="/rollen/balancing" element={<BalancePage />} />
          <Route path="/hilfe" element={<TutorialsPage />} />
          <Route path="/hilfe/downloads" element={<DownloadsPage />} />
          <Route path="/downloads" element={<Navigate to="/hilfe/downloads" replace />} />
          <Route path="/events" element={<EventsPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
        </RouteFade>
      </main>
      <Footer />
      </PermissionProvider>
    </MeProvider>
  );
}
