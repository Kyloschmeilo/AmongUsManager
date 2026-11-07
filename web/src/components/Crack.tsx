// Crack-Voting: Abstimmen auf der Runden-Seite, Hinweis auf der Startseite, Abschnitt im Profil.
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, Cigarette, Crown } from 'lucide-react';
import { loginUrl, type Profile } from '../lib/api';
import { clockOffset, countdown, useCountdown, useCrackPoll, useOpenCrackPolls, type CrackPlayer, type CrackPollState } from '../lib/crack';
import { date, num, pct, time } from '../lib/format';
import { Avatar, PlayerLink, Section, StatCard } from './ui';
import { scrollToElement } from './motion';
import '../crack.css';

/** Abschnitt auf der Runden-Seite: offen = abstimmen, beendet = Ergebnis, ohne Voting = nichts. */
export function CrackVote({ gameId }: { gameId: string }) {
  const { data, vote } = useCrackPoll(gameId);
  const scrolled = useRef(false);
  const shown = !!data && data.state !== 'none';
  useEffect(() => {
    // Link „Abstimmen“ von der Startseite: der Abschnitt lädt erst nach der Runde – dann hinscrollen
    if (!shown || scrolled.current || window.location.hash !== '#crack') return;
    scrolled.current = true;
    const el = document.getElementById('crack');
    if (el) window.setTimeout(() => scrollToElement(el), 50);
  }, [shown]);
  if (!data || data.state === 'none') return null;
  if (data.state === 'closed' && data.total === 0) {
    return (
      <Section title="Crack-Voting" id="crack">
        <p className="caption">Niemand hat abgestimmt, wer in dieser Runde am meisten Crack geraucht hat.</p>
      </Section>
    );
  }
  return data.state === 'open' ? <OpenPoll poll={data} vote={vote} /> : <Result poll={data} />;
}

function OpenPoll({ poll, vote }: { poll: CrackPollState; vote: (playerId: number | null) => Promise<void> }) {
  const left = useCountdown(poll.closesAt, clockOffset(poll.now));
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const canVote = poll.loggedIn && left > 0;

  const pick = async (player: CrackPlayer) => {
    if (!canVote || player.me || busy != null) return;
    setBusy(player.playerId);
    setError(null);
    try {
      await vote(poll.myVote === player.playerId ? null : player.playerId);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <Section title="Crack-Voting" eyebrow={left > 0 ? `noch ${countdown(left)}` : 'wird ausgezählt'} id="crack">
      <div className="panel panel--box crack">
        <div className="crack__head">
          <Cigarette className="icon" aria-hidden="true" />
          <p className="crack__question">Wer hat in dieser Runde am meisten Crack geraucht?</p>
          <span className="crack__count mono">{num(poll.total)} {poll.total === 1 ? 'Stimme' : 'Stimmen'}</span>
        </div>
        <div className="crack__grid" role="group" aria-label="Spieler wählen">
          {poll.players.map((p) => {
            const mine = poll.myVote === p.playerId;
            return (
              <button
                key={p.playerId}
                type="button"
                className={`crack__pick${mine ? ' is-mine' : ''}${p.me ? ' is-self' : ''}`}
                disabled={!canVote || p.me || busy != null}
                aria-pressed={mine}
                title={p.me ? 'Für dich selbst kannst du nicht stimmen' : mine ? 'Stimme zurückziehen' : `Für ${p.name} stimmen`}
                onClick={() => void pick(p)}
              >
                <Avatar colorId={p.colorId} playerKey={p.key} src={p.avatar} />
                <span className="crack__name">{p.name}</span>
                {mine && <Check className="icon icon--sm crack__check" aria-hidden="true" />}
                {p.me && <span className="caption">du</span>}
              </button>
            );
          })}
        </div>
        <div className="crack__foot">
          {!poll.loggedIn ? (
            poll.loginEnabled ? (
              <a className="btn btn--primary" href={loginUrl(window.location.pathname + '#crack')}>Mit Discord anmelden und abstimmen</a>
            ) : (
              <span className="caption">Abstimmen geht nur mit Discord-Anmeldung – die ist auf dieser Website aus.</span>
            )
          ) : (
            <span className="caption">
              {poll.myVote != null ? 'Deine Stimme ist drin – bis zum Ende kannst du sie ändern oder per Klick zurückziehen.' : 'Eine Stimme pro Person. Wer vorne liegt, siehst du, wenn das Voting vorbei ist.'}
            </span>
          )}
          {error && <span className="crack__error">{error}</span>}
        </div>
      </div>
    </Section>
  );
}

function Result({ poll }: { poll: CrackPollState }) {
  const ranked = poll.players.filter((p) => (p.votes ?? 0) > 0).sort((a, b) => (b.votes ?? 0) - (a.votes ?? 0) || a.name.localeCompare(b.name, 'de'));
  const top = ranked[0]?.votes ?? 1;
  const winners = ranked.filter((p) => p.winner);
  return (
    <Section title="Crack-Voting" eyebrow={`${num(poll.total)} ${poll.total === 1 ? 'Stimme' : 'Stimmen'}`} id="crack">
      <div className="panel panel--box crack">
        <div className="crack__head">
          <Crown className="icon crack__crown" aria-hidden="true" />
          <p className="crack__question">
            {winners.length === 1 ? <>Am meisten Crack geraucht hat <b>{winners[0].name}</b>.</> : <>Am meisten Crack geraucht haben <b>{winners.map((w) => w.name).join(' und ')}</b> – Gleichstand.</>}
          </p>
        </div>
        <ol className="crack__result">
          {ranked.map((p) => (
            <li key={p.playerId} className={p.winner ? 'is-winner' : undefined}>
              <PlayerLink player={p} />
              <span className="crack__bar"><i style={{ width: `${((p.votes ?? 0) / top) * 100}%` }} /></span>
              <span className="mono">{num(p.votes)}</span>
            </li>
          ))}
        </ol>
        {poll.myVote != null && <span className="caption">Deine Stimme ging an {poll.players.find((p) => p.playerId === poll.myVote)?.name ?? '?'}.</span>}
      </div>
    </Section>
  );
}

/** Hinweis auf der Startseite, solange irgendwo abgestimmt werden kann. */
export function CrackStrip() {
  const data = useOpenCrackPolls();
  const poll = data?.open[0];
  const left = useCountdown(poll?.closesAt, data ? clockOffset(data.now) : 0);
  if (!poll || left <= 0) return null;
  return (
    <Link to={`/runden/${encodeURIComponent(poll.gameId)}#crack`} className="live-strip crack-strip">
      <Cigarette className="icon icon--sm" aria-hidden="true" />
      <span className="live-strip__label">Crack-Voting</span>
      <span className="live-strip__text">
        {poll.map} von {time(poll.endedAt)} Uhr · noch {countdown(left)} · {num(poll.total)} {poll.total === 1 ? 'Stimme' : 'Stimmen'}
        {data!.open.length > 1 ? ` · ${data!.open.length} Runden offen` : ''}
      </span>
      <span className="live-strip__go">{poll.voted ? 'Ändern' : 'Abstimmen'}</span>
    </Link>
  );
}

/** Profil: wie oft man im Crack-Voting vorne lag. */
export function CrackSection({ profile }: { profile: Profile }) {
  const c = profile.crack;
  if (!c || c.polls === 0) return null;
  return (
    <Section title="Crack-Voting" eyebrow="wer am meisten geraucht hat">
      <div className="grid grid--stats">
        <StatCard icon={<Crown className="icon" />} label="Crack-Siege" value={c.wins} chip="vorne gelegen" caption={`in ${num(c.polls)} Votings · ${pct(c.wins, c.polls)}`} />
        <StatCard icon={<Cigarette className="icon" />} label="Crack-Stimmen" value={c.votes} chip="bekommen" caption={`im Schnitt ${(c.votes / c.polls).toLocaleString('de-DE', { maximumFractionDigits: 1 })} je Voting`} />
      </div>
      {c.won.length > 0 && (
        <ol className="panel crack__won">
          {c.won.map((w) => (
            <li key={w.round.gameId}>
              <Link to={`/runden/${encodeURIComponent(w.round.gameId)}#crack`}>{w.round.map}</Link>
              <span className="caption">{date(w.round.endedAt)}, {time(w.round.endedAt)} Uhr</span>
              <span className="mono">{num(w.votes)} {w.votes === 1 ? 'Stimme' : 'Stimmen'}</span>
            </li>
          ))}
        </ol>
      )}
    </Section>
  );
}
