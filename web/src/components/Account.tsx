// Kopfzeile: „Mit Discord anmelden“ bzw. das eigene Konto (Menü mit Profil, Bearbeiten, Abmelden),
// dazu der Hinweis nach einer abgebrochenen/fehlgeschlagenen Anmeldung.
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronDown, CircleAlert, LogIn, LogOut, PenLine, UserRound, X } from 'lucide-react';
import { loginUrl } from '../lib/api';
import { useMe } from '../lib/me';
import { Avatar } from './ui';

export function AccountButton() {
  const { me, logout } = useMe();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => setOpen(false), [location.pathname, location.search]);
  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !ref.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', close);
    };
  }, [open]);

  if (!me || (!me.loggedIn && !me.loginEnabled)) return null;

  if (!me.loggedIn) {
    return (
      <a className="btn account__login" href={loginUrl()} aria-label="Mit Discord anmelden">
        <LogIn className="icon icon--sm" aria-hidden="true" />
        <span>Mit Discord anmelden</span>
      </a>
    );
  }

  const player = me.player ?? null;
  return (
    <div className="account" ref={ref}>
      <button type="button" className="account__button" aria-expanded={open} aria-haspopup="menu" onClick={() => setOpen((v) => !v)}>
        <Avatar colorId={player?.colorId ?? null} playerKey={player?.key} src={me.user?.avatar} size="sm" />
        <span className="account__name">{player?.name ?? me.user?.name}</span>
        <ChevronDown className="icon icon--sm" aria-hidden="true" />
      </button>
      {open && (
        <div className="account__menu panel" role="menu">
          <span className="caption account__who">Angemeldet als {me.user?.name}</span>
          {player ? (
            <>
              <Link role="menuitem" to={`/spieler/${player.key}`}>
                <UserRound className="icon icon--sm" aria-hidden="true" />
                Mein Profil
              </Link>
              <Link role="menuitem" to={`/spieler/${player.key}?bearbeiten=1`}>
                <PenLine className="icon icon--sm" aria-hidden="true" />
                Profil bearbeiten
              </Link>
            </>
          ) : (
            <p className="caption account__hint">
              Zu deinem Discord-Konto gibt es noch keine Runden. Verknüpfe dich im Discord mit <span className="mono">/verknuepfen</span>, dann erscheint hier dein Profil.
            </p>
          )}
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              logout().then(() => navigate(location.pathname, { replace: true }), () => undefined);
            }}
          >
            <LogOut className="icon icon--sm" aria-hidden="true" />
            Abmelden
          </button>
        </div>
      )}
    </div>
  );
}

const LOGIN_MESSAGES: Record<string, string> = {
  fehler: 'Die Anmeldung mit Discord hat nicht geklappt. Versuch es bitte noch einmal.',
  abgebrochen: 'Anmeldung abgebrochen.',
  aus: 'Die Anmeldung ist auf dieser Website noch nicht eingerichtet.'
};

/** Hinweis nach der Rückkehr von Discord (?login=…). */
export function LoginNotice() {
  const [params, setParams] = useSearchParams();
  const status = params.get('login');
  const message = status ? LOGIN_MESSAGES[status] : null;
  if (!message) return null;
  const dismiss = () => {
    const next = new URLSearchParams(params);
    next.delete('login');
    setParams(next, { replace: true });
  };
  return (
    <div className="note note--warn login-notice" role="status">
      <CircleAlert className="icon" aria-hidden="true" />
      <span style={{ flex: 1 }}>{message}</span>
      <button type="button" className="btn btn--ghost btn--icon" aria-label="Hinweis schließen" onClick={dismiss} style={{ width: 28, height: 28 }}>
        <X className="icon icon--sm" aria-hidden="true" />
      </button>
    </div>
  );
}
