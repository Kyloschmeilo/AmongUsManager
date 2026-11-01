import { NavLink, Link, Route, Routes } from 'react-router-dom';
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
import { NotFound } from './pages/NotFound';
import { AccountButton, LoginNotice } from './components/Account';
import { MeProvider } from './lib/me';

const NAV = [
  { to: '/', label: 'Übersicht', end: true },
  { to: '/bestenliste', label: 'Bestenliste' },
  { to: '/runden', label: 'Runden' },
  { to: '/maps', label: 'Maps' },
  { to: '/rollen', label: 'Rollen' }
];

function Brand() {
  return (
    <Link to="/" className="brand" aria-label="Crew Stats – Übersicht">
      <span className="brand__mark" aria-hidden="true">
        <i className="n" /><i className="n" /><i className="g" />
        <i className="n" /><i className="a" /><i className="a" />
        <i /><i className="a" /><i className="a" />
      </span>
      <span className="brand__name">Crew Stats</span>
    </Link>
  );
}

function Footer() {
  const overview = useApi<Overview>('overview');
  return (
    <footer className="footer">
      <div className="wrap footer__inner">
        <span className="caption">
          Daten von der Among-Us-Mod CrewStats, aufgezeichnet beim Host und erst nach Rundenende verschickt.
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
      <a className="sr-only" href="#inhalt">Zum Inhalt</a>
      <header className="topbar">
        <div className="wrap topbar__inner">
          <Brand />
          <nav className="nav" aria-label="Hauptnavigation">
            {NAV.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => (isActive ? 'active' : undefined)}>
                {item.label}
              </NavLink>
            ))}
          </nav>
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
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      <Footer />
    </MeProvider>
  );
}
