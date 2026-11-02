// Events: kommende Spieleabende aus dem Discord, mit An-/Abmelden (Discord-Login) und – für Event Maker und
// Admins – einem Formular, das ein neues Event im Discord postet (wie /create-event).
import { useMemo, useState } from 'react';
import { CalendarClock, CalendarPlus, Check, CircleAlert, ExternalLink, LogIn, Users, X } from 'lucide-react';
import { loginUrl } from '../lib/api';
import { useMe } from '../lib/me';
import { useAction, useBot, usePermissions, type EventsResponse, type GameEvent } from '../lib/control';
import { day, dayMonth, time } from '../lib/format';
import { Avatar, Empty, Loading, PlayerLink } from '../components/ui';
import { Reveal, Title } from '../components/motion';

export function EventsPage() {
  const { me } = useMe();
  const events = useBot<EventsResponse>(me ? 'events.list' : null, undefined, 30_000);
  const loggedIn = !!me?.loggedIn;

  return (
    <div className="page page--tight">
      <div className="stack" style={{ gap: 16 }}>
        <span className="eyebrow">Events</span>
        <Title text="Wann wird gezockt?" className="page-title" />
        <p className="lead">
          Alle geplanten Spieleabende aus dem Discord. Trag dich direkt hier ein – das zählt genauso wie der Knopf im Discord.
        </p>
      </div>

      {me && !loggedIn && me.loginEnabled && (
        <div className="note">
          <LogIn className="icon" aria-hidden="true" />
          <span style={{ flex: 1 }}>Melde dich mit Discord an, um dich für ein Event einzutragen.</span>
          <a className="btn btn--primary" href={loginUrl()}>Mit Discord anmelden</a>
        </div>
      )}

      {events.error && !events.data && <BotProblem message={events.error.message} />}
      {!events.data && events.loading && <Loading />}
      {events.data && (
        <>
          {events.data.canCreate && <CreateEvent data={events.data} onCreated={events.reload} />}
          <EventList data={events.data} loggedIn={loggedIn} onChange={events.reload} />
        </>
      )}
    </div>
  );
}

export function BotProblem({ message }: { message: string }) {
  return (
    <div className="note note--warn" role="status">
      <CircleAlert className="icon" aria-hidden="true" />
      <span>{message}</span>
    </div>
  );
}

function EventList({ data, loggedIn, onChange }: { data: EventsResponse; loggedIn: boolean; onChange: () => void }) {
  if (data.events.length === 0) {
    return (
      <div className="panel">
        <Empty text="Gerade ist kein Event geplant." hint={data.canCreate ? 'Leg oben eins an.' : 'Neue Events erscheinen hier, sobald sie im Discord erstellt werden.'} />
      </div>
    );
  }
  return (
    <section className="section" aria-label="Geplante Events">
      <div className="event-list">
        {data.events.map((event, index) => (
          <Reveal key={event.id} delay={Math.min(index, 6) * 0.05}>
            <EventCard event={event} data={data} loggedIn={loggedIn} onChange={onChange} />
          </Reveal>
        ))}
      </div>
    </section>
  );
}

function EventCard({ event, data, loggedIn, onChange }: { event: GameEvent; data: EventsResponse; loggedIn: boolean; onChange: () => void }) {
  const { busy, notice, run } = useAction(onChange);
  const status = event.closed ? 'Vorbei' : event.running ? 'Läuft gerade' : until(event.startsAt);
  return (
    <article className={`panel event-card${event.joined ? ' event-card--joined' : ''}`}>
      <div className="event-card__when">
        <span className="event-card__day">{day(event.startsAt)}</span>
        <span className="event-card__date mono">{dayMonth(event.startsAt)}</span>
        <span className="event-card__time mono">{time(event.startsAt)}</span>
      </div>
      <div className="event-card__body">
        <div className="row" style={{ gap: 8 }}>
          <span className={`pill${event.running ? '' : event.closed ? ' pill--plain' : ' pill--sky'}`}>
            <span className="pill__dot" />
            {status}
          </span>
          {event.joined && (
            <span className="pill pill--case">
              <Check className="icon icon--sm" aria-hidden="true" /> Du bist dabei
            </span>
          )}
        </div>
        <h2 className="event-card__title">{event.game}</h2>
        <span className="caption">
          {time(event.startsAt)} bis {time(event.endsAt)} Uhr
          {event.creator ? ` · erstellt von ${event.creator}` : ''}
          {event.channel ? ` · #${event.channel}` : ''}
        </span>

        <div className="event-card__roster">
          <span className="event-card__count">
            <Users className="icon icon--sm" aria-hidden="true" />
            <span className="mono ink">{event.roster.length}</span> dabei
          </span>
          {event.roster.length > 0 ? (
            <ul className="event-roster">
              {event.roster.map((person, i) => (
                <li key={`${person.key ?? person.name}-${i}`}>
                  {person.key && person.profile ? (
                    <PlayerLink player={{ key: person.key, name: person.name, colorId: person.colorId ?? null, avatar: person.avatar }} />
                  ) : (
                    <span className="player">
                      <Avatar colorId={person.colorId ?? null} playerKey={person.key ?? person.name} src={person.avatar} />
                      <span className="player__name">{person.name}</span>
                    </span>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <span className="caption">Noch niemand – sei die erste Person!</span>
          )}
        </div>

        <div className="row event-card__actions">
          {loggedIn && !event.closed && (event.joined ? (
            <button type="button" className="btn" disabled={busy !== null} onClick={() => void run('leave', 'events.leave', { event: event.id })}>
              <X className="icon icon--sm" aria-hidden="true" />
              {busy === 'leave' ? 'Einen Moment …' : 'Diesmal nicht'}
            </button>
          ) : (
            <button type="button" className="btn btn--primary" disabled={busy !== null} onClick={() => void run('join', 'events.join', { event: event.id })}>
              <Check className="icon icon--sm" aria-hidden="true" />
              {busy === 'join' ? 'Einen Moment …' : 'Ich bin dabei'}
            </button>
          ))}
          <a className="btn btn--ghost" href={event.messageUrl} target="_blank" rel="noreferrer">
            <ExternalLink className="icon icon--sm" aria-hidden="true" /> Im Discord
          </a>
          {event.discordEventUrl && (
            <a className="btn btn--ghost" href={event.discordEventUrl} target="_blank" rel="noreferrer">
              <CalendarClock className="icon icon--sm" aria-hidden="true" /> Discord-Event
            </a>
          )}
        </div>
        {notice && <p className={notice.error ? 'form-error' : 'caption accent'} role="status">{notice.text}</p>}
        {!event.closed && !notice && loggedIn && !event.joined && (
          <span className="caption">
            +{data.attendanceXp} XP, wenn du beim Event mindestens {data.minAttendance} Minuten im Voice bist.
          </span>
        )}
      </div>
    </article>
  );
}

function CreateEvent({ data, onCreated }: { data: EventsResponse; onCreated: () => void }) {
  const { perms } = usePermissions();
  const [game, setGame] = useState(data.games[0] ?? '');
  const [start, setStart] = useState(defaultStart);
  const { busy, notice, run } = useAction(onCreated);
  const minimum = useMemo(() => localInput(new Date(Date.now() + 5 * 60_000)), []);

  return (
    <section className="panel panel--pad event-create" aria-labelledby="event-erstellen">
      <div className="panel__title" id="event-erstellen">
        <CalendarPlus className="icon" aria-hidden="true" />
        Neues Event
        <span className="caption">{perms?.admin ? 'Als Admin' : `Als ${perms?.eventMakerRole ?? 'Event Maker'}`}</span>
      </div>
      <form
        className="event-create__form"
        onSubmit={(e) => {
          e.preventDefault();
          void run('create', 'events.create', { game, start });
        }}
      >
        <label className="field">
          <span className="field__label">Spiel</span>
          <select value={game} onChange={(e) => setGame(e.target.value)} required>
            {data.games.map((g) => (
              <option key={g} value={g}>{g}</option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field__label">Start (deutsche Zeit)</span>
          <input type="datetime-local" className="input" value={start} min={minimum} step={300} onChange={(e) => setStart(e.target.value)} required />
        </label>
        <button type="submit" className="btn btn--primary" disabled={busy !== null || !game || !start}>
          <CalendarPlus className="icon icon--sm" aria-hidden="true" />
          {busy ? 'Wird gepostet …' : 'Event posten'}
        </button>
      </form>
      <p className="caption" style={{ marginTop: 12 }}>
        {data.eventChannel
          ? `Der Bot postet das Event in #${data.eventChannel}, pingt die Spiel-Rolle und legt ein Discord-Event über ${data.durationHours} Stunden an.`
          : 'Es ist noch kein Event-Channel eingestellt – ein Admin kann ihn im Admin-Panel festlegen.'}
      </p>
      {notice && <p className={notice.error ? 'form-error' : 'caption accent'} role="status" style={{ marginTop: 8 }}>{notice.text}</p>}
    </section>
  );
}

/** Nächster voller Abend um 19:00 (bzw. morgen, wenn es schon später ist). */
function defaultStart(): string {
  const d = new Date();
  if (d.getHours() >= 18) d.setDate(d.getDate() + 1);
  d.setHours(19, 0, 0, 0);
  return localInput(d);
}

/** Datum → Wert für <input type="datetime-local"> (Ortszeit des Browsers, wie der Bot sie als deutsche Zeit liest). */
function localInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function until(iso: string): string {
  const minutes = Math.round((new Date(iso).getTime() - Date.now()) / 60_000);
  if (minutes < 1) return 'Gleich';
  if (minutes < 60) return `In ${minutes} Min.`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `In ${hours} Std.`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'Morgen' : `In ${days} Tagen`;
}
