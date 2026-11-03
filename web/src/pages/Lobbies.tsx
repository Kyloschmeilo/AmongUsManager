// Live: offene Lobbys aller Hosts mit CrewStats – wer drin ist, und „Beitreten“ (die Mod im Hauptmenü holt es ab).
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, Copy, LogIn } from 'lucide-react';
import { loginUrl } from '../lib/api';
import { useMe } from '../lib/me';
import { playerColor } from '../lib/colors';
import { cancelJoin, joinLobby, useLobbies, type LiveLobby, type LobbiesResponse, type LobbyPlayer } from '../lib/lobbies';
import { CrewmateSvg } from '../components/CrewDrift';
import { Empty, Loading } from '../components/ui';
import { Title } from '../components/motion';
import '../lobbies.css';

export function LobbiesPage() {
  const { data, error, reload } = useLobbies(5000);
  const { me: account } = useMe();
  const me = data?.me ?? null;

  return (
    <div className="page page--tight">
      <div className="page-head">
        <Title text="Lobbys" className="page-title" />
        <p className="lead">
          Wer gerade wo spielt – live aus den Lobbys aller Hosts mit CrewStats. Mit einem Klick auf „Beitreten“ tritt dein
          Among Us der Lobby bei, sobald es im Hauptmenü ist.
        </p>
      </div>

      {account && !account.loggedIn && account.loginEnabled && (
        <div className="note">
          <LogIn className="icon" aria-hidden="true" />
          <span style={{ flex: 1 }}>Melde dich mit Discord an, um Lobby-Codes zu sehen und per Klick beizutreten.</span>
          <a className="btn btn--primary" href={loginUrl()}>Mit Discord anmelden</a>
        </div>
      )}
      {me?.loggedIn && !me.linked && (
        <div className="note note--warn">
          <span>
            Dein Discord ist noch mit keinem Among-Us-Account verknüpft. Nutze <span className="mono">/verknuepfen</span> im
            Discord oder den Code aus dem F7-Menü von CrewStats – dann siehst du hier die Codes und kannst beitreten.
          </span>
        </div>
      )}

      {!data && !error && <Loading />}
      {!data && error && <div className="note note--warn" role="status">Lobbys gerade nicht abrufbar ({error.message}).</div>}
      {data && data.lobbies.length === 0 && (
        <div className="panel">
          <Empty text="Gerade ist keine Lobby offen." hint="Sobald ein Host mit CrewStats eine Online-Lobby aufmacht, steht sie hier." />
        </div>
      )}
      {data && data.lobbies.length > 0 && (
        <section className="lobby-list" aria-label="Offene Lobbys" aria-live="polite">
          {data.lobbies.map((lobby) => (
            <LobbyCard key={lobby.id} lobby={lobby} me={me} onChange={() => void reload()} />
          ))}
        </section>
      )}
      <p className="caption">
        Beitreten braucht CrewStats ab 1.9.0 mit der Website-URL von <span className="mono">/autojoin</span> in der Config
        (<span className="mono">[AutoJoin] Url</span>) und einen verknüpften Account. Hosts zeigen ihre Spieler ab CrewStats 1.9.0.{' '}
        <Link to="/hilfe?t=autojoin">Anleitung Auto-Join</Link>
      </p>
    </div>
  );
}

function LobbyCard({ lobby, me, onChange }: { lobby: LiveLobby; me: LobbiesResponse['me']; onChange: () => void }) {
  const running = lobby.state === 'started';
  const mine = me?.inLobby != null && me.inLobby === lobby.code;
  const seats = Math.max(0, Math.min(lobby.max, 15) - lobby.list.length);
  return (
    <article className={`panel--box lobby${running ? ' lobby--running' : ''}${mine ? ' lobby--mine' : ''}`}>
      <header className="lobby__head">
        <div className="lobby__title">
          <span className={`lobby__state${running ? ' lobby__state--running' : ''}`}>
            <span className="lobby__pulse" aria-hidden="true" />
            {running ? 'Runde läuft' : lobby.full ? 'Voll' : 'Offen'}
          </span>
          <h2 className="lobby__map">{lobby.map}</h2>
          <span className="caption">
            Host {lobby.host || '?'}
            {lobby.region ? ` · ${lobby.region}` : ''} · {sinceText(lobby.age)}
          </span>
        </div>
        <div className="lobby__count" aria-label={`${lobby.players} von ${lobby.max} Plätzen belegt`}>
          <span className="lobby__num mono">{lobby.players}</span>
          <span className="lobby__max mono">/{lobby.max || '?'}</span>
          <span className="lobby__bar" aria-hidden="true">
            <span style={{ width: `${lobby.max > 0 ? Math.min(100, (lobby.players / lobby.max) * 100) : 0}%` }} />
          </span>
        </div>
      </header>

      {lobby.list.length > 0 ? (
        <ul className="lobby__crew">
          {lobby.list.map((player, index) => (
            <li key={`${player.key ?? player.name}-${index}`} style={{ ['--i' as string]: index }}>
              <Seat player={player} />
            </li>
          ))}
          {Array.from({ length: seats }, (_, i) => (
            <li key={`frei-${i}`} className="lobby__free" aria-hidden="true">
              <span className="seat seat--free">
                <CrewmateSvg color="transparent" className="seat__body" />
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="caption lobby__nolist">
          {lobby.players} Spieler drin – die Namen zeigt der Host ab CrewStats 1.9.0.
        </p>
      )}

      <JoinBar lobby={lobby} me={me} mine={mine} onChange={onChange} />
    </article>
  );
}

function Seat({ player }: { player: LobbyPlayer }) {
  const body = (
    <>
      <CrewmateSvg color={playerColor(player.colorId, player.key ?? player.name)} className="seat__body" />
      <span className="seat__name">{player.name}</span>
      <span className="seat__meta">
        {player.host ? <b>Host</b> : null}
        {player.me ? <b className="seat__you">Du</b> : null}
        {player.level != null ? <span className="mono">Lv {player.level}</span> : null}
        {player.games != null && player.level == null ? <span className="mono">{player.games} R.</span> : null}
      </span>
    </>
  );
  const title = player.games != null ? `${player.name} – ${player.games} Runden, ${player.wins ?? 0} Siege` : player.name;
  return player.key && player.profile ? (
    <Link className={`seat${player.me ? ' seat--me' : ''}`} to={`/spieler/${player.key}`} title={title}>{body}</Link>
  ) : (
    <span className={`seat${player.me ? ' seat--me' : ''}`} title={`${player.name} – noch keine Runde mit Stats`}>{body}</span>
  );
}

function JoinBar({ lobby, me, mine, onChange }: { lobby: LiveLobby; me: LobbiesResponse['me']; mine: boolean; onChange: () => void }) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ text: string; error: boolean } | null>(null);
  const [copied, setCopied] = useState(false);
  const pending = me?.join && lobby.code && me.join.code === lobby.code ? me.join : null;

  const join = async () => {
    if (!lobby.code) return;
    setBusy(true);
    setNotice(null);
    try {
      const result = await joinLobby(lobby.code);
      setNotice({ text: result.message, error: false });
      onChange();
    } catch (e) {
      setNotice({ text: (e as Error).message, error: true });
    } finally {
      setBusy(false);
    }
  };
  const cancel = async () => {
    setBusy(true);
    try {
      await cancelJoin();
      setNotice(null);
      onChange();
    } finally {
      setBusy(false);
    }
  };
  const copy = () => {
    if (!lobby.code) return;
    void navigator.clipboard?.writeText(lobby.code).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <footer className="lobby__foot">
      {lobby.code ? (
        <button type="button" className="lobby__code" onClick={copy} aria-label={`Code ${lobby.code} kopieren`}>
          <span className="lobby__code-text">{lobby.code}</span>
          {copied ? <Check className="icon icon--sm" aria-hidden="true" /> : <Copy className="icon icon--sm" aria-hidden="true" />}
        </button>
      ) : (
        <span className="lobby__code lobby__code--hidden" aria-label="Code nur für angemeldete Spieler">••••••</span>
      )}

      <div className="lobby__action">
        {mine ? (
          <span className="pill pill--case"><Check className="icon icon--sm" aria-hidden="true" /> Du bist drin</span>
        ) : pending ? (
          <>
            <span className="lobby__wait">
              {pending.sent ? 'Dein Spiel hat es abgeholt und tritt bei …' : 'Wartet auf dein Spiel – Among Us im Hauptmenü öffnen.'}
            </span>
            <button type="button" className="btn btn--ghost" disabled={busy} onClick={() => void cancel()}>Abbrechen</button>
          </>
        ) : me?.linked ? (
          <button type="button" className="btn btn--primary" disabled={busy || !lobby.joinable} onClick={() => void join()}>
            {busy ? 'Einen Moment …' : lobby.state === 'started' ? 'Runde läuft' : lobby.full ? 'Voll' : 'Beitreten'}
          </button>
        ) : !me?.loggedIn ? (
          <a className="btn" href={loginUrl()}>Anmelden zum Beitreten</a>
        ) : null}
      </div>
      {notice && !pending && <span className={`lobby__notice${notice.error ? ' ember' : ''}`} role="status">{notice.text}</span>}
    </footer>
  );
}

function sinceText(seconds: number): string {
  if (seconds < 60) return 'gerade aktualisiert';
  const minutes = Math.round(seconds / 60);
  return minutes === 1 ? 'zuletzt vor 1 Minute' : `zuletzt vor ${minutes} Minuten`;
}

/** Kurzer Hinweis auf der Startseite, wenn gerade Lobbys offen sind. */
export function LiveLobbyStrip() {
  const { data } = useLobbies(15000);
  const lobbies = data?.lobbies ?? [];
  if (lobbies.length === 0) return null;
  const open = lobbies.filter((l) => l.state === 'open');
  const first = open[0] ?? lobbies[0];
  return (
    <Link to="/lobbys" className="live-strip">
      <span className="lobby__pulse" aria-hidden="true" />
      <span className="live-strip__label">Live</span>
      <span className="live-strip__text">
        {lobbies.length === 1
          ? `${first.state === 'open' ? 'Lobby offen' : 'Runde läuft'} · ${first.map} · ${first.players}/${first.max} · Host ${first.host}`
          : `${lobbies.length} Lobbys · ${open.length} offen · ${lobbies.reduce((n, l) => n + l.players, 0)} Spieler`}
      </span>
      <span className="live-strip__go">Ansehen</span>
    </Link>
  );
}
