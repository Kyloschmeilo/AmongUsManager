import { NavLink, Link, Navigate, Route, Routes } from 'react-router-dom';
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
import { DownloadsPage, TutorialsPage } from './pages/Guides';
import { NotFound } from './pages/NotFound';
import { AccountButton, LoginNotice } from './components/Account';
import { MeProvider } from './lib/me';
import { PermissionProvider, usePermissions } from './lib/control';
import { EventsPage } from './pages/Events';
import { AdminPage } from './pages/Admin';
import './control.css';

const NAV = [
  { to: '/', label: 'Übersicht', end: true },
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
  return (
    <nav className="nav" aria-label="Hauptnavigation">
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
      <main id="inhalt" className="wrap">
        <LoginNotice />
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/bestenliste" element={<Leaderboard />} />
          <Route path="/spieler/:key" element={<PlayerPage />} />
          <Route path="/runden" element={<RoundsPage />} />
          <Route path="/runden/:gameId" element={<RoundPage />} />
          <Route path="/maps" element={<MapsPage />} />
          <Route path="/maps/:mapId" element={<MapPage />} />
          <Route path="/rollen" element={<RolesPage />} />
          <Route path="/hilfe" element={<TutorialsPage />} />
          <Route path="/hilfe/downloads" element={<DownloadsPage />} />
          <Route path="/downloads" element={<Navigate to="/hilfe/downloads" replace />} />
          <Route path="/events" element={<EventsPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      <Footer />
      </PermissionProvider>
    </MeProvider>
  );
}
