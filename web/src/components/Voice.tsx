// Proximity-Voice im Live-Tab: Bereich auf /lobbys + kleine Leiste auf allen anderen Seiten, solange man verbunden ist.
import { Link, useLocation } from 'react-router-dom';
import { Headphones, Mic, MicOff, PhoneOff, Volume2 } from 'lucide-react';
import { loginUrl } from '../lib/api';
import { playerColor } from '../lib/colors';
import { useVoice, useVoiceStatus, voiceSupported, type VoiceMode } from '../lib/voice';
import { CrewmateSvg } from './CrewDrift';
import '../voice.css';

const MODE_TEXT: Record<VoiceMode, { label: string; hint: string }> = {
  lobby: { label: 'Lobby', hint: 'Alle hören sich.' },
  round: { label: 'Runde', hint: 'Du hörst nur, wer in deiner Nähe ist. Geister hören alles, Lebende hören keine Geister.' },
  meeting: { label: 'Meeting', hint: 'Alle Lebenden reden miteinander. Geister hören zu.' },
  free: { label: 'Ohne Positionen', hint: 'Der Host meldet gerade nichts – alle hören sich.' },
};

export function VoicePanel() {
  const voice = useVoice();
  const status = useVoiceStatus(voice.phase === 'live' ? 15000 : 5000);
  const unsupported = voiceSupported();
  const live = voice.phase === 'live' || voice.phase === 'connecting';

  return (
    <section className={`panel--box voice${live ? ' voice--live' : ''}`} aria-labelledby="voice-title">
      <header className="voice__head">
        <div className="voice__title">
          <span className="voice__kicker"><Headphones className="icon icon--sm" aria-hidden="true" /> Proximity-Voice</span>
          <h2 id="voice-title" className="voice__name">
            {live ? (voice.mode ? MODE_TEXT[voice.mode].label : 'Verbinde …') : 'Voice-Chat im Browser'}
          </h2>
          <p className="caption">
            {live && voice.mode
              ? MODE_TEXT[voice.mode].hint
              : 'Sprich mit deiner Lobby direkt hier – wie im Spiel: nah = laut, weit weg = leise, im Meeting hören sich alle.'}
          </p>
        </div>
        {live && <VoiceControls />}
      </header>

      {live ? (
        <ul className="voice__crew" aria-label="Im Voice">
          <li>
            <span className={`voice__seat voice__seat--me${voice.speaking ? ' voice__seat--talk' : ''}`}>
              <span className="voice__ring" aria-hidden="true">{voice.muted ? <MicOff className="icon icon--sm" /> : <Mic className="icon icon--sm" />}</span>
              <span className="voice__seat-name">Du</span>
              <span className="voice__seat-state">{voice.phase === 'connecting' ? 'verbinde …' : voice.muted ? 'stumm' : 'Mikro an'}</span>
            </span>
          </li>
          {voice.peers.map((peer) => {
            const body = (
              <>
                <span className="voice__ring" aria-hidden="true">
                  <CrewmateSvg color={playerColor(peer.color, peer.key ?? peer.name)} className="voice__body" />
                </span>
                <span className="voice__seat-name">{peer.name}</span>
                <span className={`voice__seat-state voice__seat-state--${peer.connection}`}>
                  {peer.connection === 'connected' ? (peer.speaking ? 'spricht' : 'verbunden') : peer.connection === 'failed' ? 'keine Verbindung' : 'verbinde …'}
                </span>
              </>
            );
            const cls = `voice__seat${peer.speaking ? ' voice__seat--talk' : ''}${peer.connection === 'failed' ? ' voice__seat--failed' : ''}`;
            return (
              <li key={peer.id}>
                {peer.key ? <Link className={cls} to={`/spieler/${peer.key}`}>{body}</Link> : <span className={cls}>{body}</span>}
              </li>
            );
          })}
          {voice.peers.length === 0 && voice.phase === 'live' && (
            <li className="voice__alone caption">Noch niemand sonst aus deiner Lobby ist im Voice.</li>
          )}
        </ul>
      ) : (
        <div className="voice__join">
          {unsupported ? (
            <p className="voice__msg ember">{unsupported}</p>
          ) : !status || !status.enabled ? (
            <p className="caption">Voice ist auf dieser Website gerade nicht verfügbar.</p>
          ) : !status.loggedIn ? (
            <a className="btn" href={loginUrl()}>Mit Discord anmelden</a>
          ) : !status.linked ? (
            <p className="caption">Verknüpfe zuerst deinen Among-Us-Account (<span className="mono">/verknuepfen</span> im Discord).</p>
          ) : status.room ? (
            <>
              <button type="button" className="btn btn--primary" onClick={voice.join}>
                <Mic className="icon icon--sm" aria-hidden="true" /> Voice beitreten
              </button>
              <span className="caption">
                Lobby <span className="mono">{status.room.code}</span> · {status.room.members === 0 ? 'noch niemand im Voice' : `${status.room.members} im Voice`}
              </span>
            </>
          ) : (
            <p className="caption">
              Tritt im Spiel einer Lobby bei – sobald der Host CrewStats ab 1.10.0 mit der Website-Adresse nutzt, kannst du hier beitreten.
            </p>
          )}
          {voice.message && <p className="voice__msg" role="status">{voice.message}</p>}
        </div>
      )}
      <p className="caption voice__foot">
        Discord-Voice bleibt wie gehabt – das hier ist die Alternative im Browser. Kopfhörer verhindern Echo.
        Deine Position im Spiel bleibt auf dem Server; der Browser erfährt nur, wie laut er wen abspielt.
      </p>
    </section>
  );
}

function VoiceControls() {
  const voice = useVoice();
  return (
    <div className="voice__controls">
      <label className="voice__volume">
        <Volume2 className="icon icon--sm" aria-hidden="true" />
        <span className="sr-only">Lautstärke</span>
        <input type="range" min={0} max={2} step={0.05} value={voice.volume} onChange={(e) => voice.setVolume(Number(e.target.value))} />
      </label>
      <button type="button" className={`btn${voice.muted ? ' btn--warn' : ''}`} onClick={() => voice.setMuted(!voice.muted)} aria-pressed={voice.muted}>
        {voice.muted ? <MicOff className="icon icon--sm" aria-hidden="true" /> : <Mic className="icon icon--sm" aria-hidden="true" />}
        {voice.muted ? 'Stumm' : 'Mikro an'}
      </button>
      <button type="button" className="btn voice__hangup" onClick={voice.leave}>
        <PhoneOff className="icon icon--sm" aria-hidden="true" /> Verlassen
      </button>
    </div>
  );
}

/** Kleine Leiste unten, solange man im Voice ist und eine andere Seite ansieht. */
export function VoiceDock() {
  const voice = useVoice();
  const { pathname } = useLocation();
  if ((voice.phase !== 'live' && voice.phase !== 'connecting') || pathname === '/lobbys') return null;
  const talking = voice.peers.filter((p) => p.speaking).map((p) => p.name);
  return (
    <div className="voice-dock" role="region" aria-label="Voice-Chat">
      <span className={`voice-dock__dot${voice.speaking ? ' voice-dock__dot--talk' : ''}`} aria-hidden="true" />
      <Link to="/lobbys" className="voice-dock__text">
        Voice · {voice.mode ? MODE_TEXT[voice.mode].label : 'verbinde …'}
        {talking.length > 0 ? ` · ${talking.join(', ')} spricht` : ` · ${voice.peers.length} weitere`}
      </Link>
      <button type="button" className="btn btn--icon" onClick={() => voice.setMuted(!voice.muted)} aria-pressed={voice.muted}
        aria-label={voice.muted ? 'Mikro einschalten' : 'Stumm schalten'}>
        {voice.muted ? <MicOff className="icon icon--sm" aria-hidden="true" /> : <Mic className="icon icon--sm" aria-hidden="true" />}
      </button>
      <button type="button" className="btn btn--icon voice__hangup" onClick={voice.leave} aria-label="Voice verlassen">
        <PhoneOff className="icon icon--sm" aria-hidden="true" />
      </button>
    </div>
  );
}
