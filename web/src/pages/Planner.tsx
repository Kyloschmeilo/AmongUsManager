// Lobby-Planer (/planer, /planer/<id>): Terminabstimmung für den nächsten Among-Us-Abend wie bei Doodle.
// Server: web/LobbyPlanner.kt. Mitmachen nur mit Discord-Anmeldung; Event Maker können den festgelegten Termin
// direkt als Discord-Event posten (Event-Seite mit vorausgefülltem Start).
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, CalendarCheck, CalendarPlus, Check, HelpCircle, Plus, Trash2, X } from 'lucide-react';
import { clearApiCache, postJson, useApi } from '../lib/api';
import { useMe } from '../lib/me';
import { usePermissions } from '../lib/control';
import { Async, Avatar, Empty, Section } from '../components/ui';
import { Title } from '../components/motion';
import '../extras.css';

interface PlanSlot {
  id: number;
  at: string;
  yes: number;
  maybe: number;
  no: number;
}

interface PlanSummary {
  id: string;
  title: string;
  note: string;
  owner: string;
  mine: boolean;
  createdAt: string;
  chosen: number | null;
  best: number | null;
  voters: number;
  answered: boolean;
  slots: PlanSlot[];
}

interface PlanPerson {
  name: string;
  me: boolean;
  owner: boolean;
  key: string | null;
  avatar: string | null;
  colorId: number | null;
  answers: Record<string, number>;
}

interface PlanDetail extends PlanSummary {
  loginEnabled: boolean;
  loggedIn: boolean;
  people: PlanPerson[];
  myAnswers: Record<string, number>;
}

const YES = 2;
const MAYBE = 1;
const NO = 0;

const weekday = new Intl.DateTimeFormat('de-DE', { weekday: 'short', timeZone: 'Europe/Berlin' });
const dayFormat = new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit', timeZone: 'Europe/Berlin' });
const clockFormat = new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin' });
const longFormat = new Intl.DateTimeFormat('de-DE', { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin' });

function SlotDate({ at }: { at: string }) {
  const d = new Date(at);
  return (
    <span className="slot-date">
      <span className="slot-date__day">{weekday.format(d)}</span>
      <b>{dayFormat.format(d)}</b>
      <span className="slot-date__time">{clockFormat.format(d)}</span>
    </span>
  );
}

/** Datum → Wert für <input type="datetime-local"> (Ortszeit des Browsers). */
function localInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function nextEvenings(count: number): string[] {
  const out: string[] = [];
  const d = new Date();
  if (d.getHours() >= 18) d.setDate(d.getDate() + 1);
  for (let i = 0; i < count; i++) {
    const day = new Date(d);
    day.setDate(d.getDate() + i);
    day.setHours(20, 0, 0, 0);
    out.push(localInput(day));
  }
  return out;
}

export function PlannerPage() {
  const list = useApi<{ loginEnabled: boolean; loggedIn: boolean; plans: PlanSummary[] }>('planer');
  const { me } = useMe();
  const [creating, setCreating] = useState(false);
  return (
    <div className="page page--tight">
      <div className="page-head">
        <Title text="Lobby-Planer" className="page-title" />
        <p className="lead">
          Wann passt es allen? Schlag ein paar Termine für den nächsten Among-Us-Abend vor, alle kreuzen an, wann sie können –
          der Termin mit den meisten Zusagen gewinnt.
        </p>
        {me?.loggedIn && !creating && (
          <div className="row">
            <button type="button" className="btn btn--primary" onClick={() => setCreating(true)}>
              <CalendarPlus className="icon icon--sm" aria-hidden="true" />
              Neue Planung
            </button>
          </div>
        )}
        {me && !me.loggedIn && (
          <p className="caption">
            {me.loginEnabled ? (
              <>Zum Abstimmen und Planen mit Discord anmelden (oben rechts).</>
            ) : (
              <>Auf dieser Website ist die Anmeldung aus – abstimmen geht hier nicht.</>
            )}
          </p>
        )}
      </div>
      {creating && <CreatePlan onCancel={() => setCreating(false)} />}
      <Async state={list}>
        {(data) =>
          data.plans.length === 0 ? (
            <Empty text="Gerade wird nichts geplant." hint={data.loggedIn ? 'Leg die erste Planung an.' : undefined} />
          ) : (
            <div className="plans">
              {data.plans.map((plan) => (
                <PlanCard key={plan.id} plan={plan} />
              ))}
            </div>
          )
        }
      </Async>
    </div>
  );
}

function PlanCard({ plan }: { plan: PlanSummary }) {
  const top = Math.max(1, ...plan.slots.map((s) => s.yes + s.maybe));
  const chosen = plan.slots.find((s) => s.id === plan.chosen);
  return (
    <Link to={`/planer/${plan.id}`} className="plan-card">
      <div className="plan-card__head">
        <div className="stack" style={{ gap: 2 }}>
          <h3>{plan.title}</h3>
          <span className="caption">von {plan.owner} · {plan.voters} {plan.voters === 1 ? 'Antwort' : 'Antworten'}</span>
        </div>
        {chosen ? (
          <span className="pill pill--gold"><CalendarCheck className="icon icon--sm" aria-hidden="true" /> {longFormat.format(new Date(chosen.at))}</span>
        ) : plan.answered ? (
          <span className="pill pill--plain">Abgestimmt</span>
        ) : (
          <span className="pill">Abstimmen</span>
        )}
      </div>
      <div className="plan-card__slots">
        {plan.slots.map((s) => (
          <div key={s.id} className={`plan-mini${s.id === plan.best ? ' plan-mini--best' : ''}${s.id === plan.chosen ? ' plan-mini--chosen' : ''}`}>
            <SlotDate at={s.at} />
            <span className="plan-mini__bar" aria-hidden="true">
              <span className="plan-mini__yes" style={{ height: `${(s.yes / top) * 100}%` }} />
              <span className="plan-mini__maybe" style={{ height: `${(s.maybe / top) * 100}%` }} />
            </span>
            <span className="caption">{s.yes} ja</span>
          </div>
        ))}
      </div>
    </Link>
  );
}

function CreatePlan({ onCancel }: { onCancel: () => void }) {
  const navigate = useNavigate();
  const [title, setTitle] = useState('Among-Us-Abend');
  const [note, setNote] = useState('');
  const [slots, setSlots] = useState<string[]>(() => nextEvenings(3));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const minimum = useMemo(() => localInput(new Date()), []);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const filled = slots.filter(Boolean).map((s) => new Date(s).toISOString());
      const plan = await postJson<PlanDetail>('/api/planer', { action: 'create', title, note, slots: filled });
      clearApiCache();
      navigate(`/planer/${plan.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel panel--box panel--pad plan-create" aria-labelledby="planung-neu">
      <div className="panel__title" id="planung-neu">
        <CalendarPlus className="icon" aria-hidden="true" />
        Neue Planung
      </div>
      <form
        className="plan-create__form"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label className="field">
          <span className="field__label">Titel</span>
          <input className="input" value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)} required />
        </label>
        <label className="field">
          <span className="field__label">Notiz (optional)</span>
          <textarea value={note} maxLength={300} rows={2} onChange={(e) => setNote(e.target.value)} placeholder="z.B. mit The Other Roles, Modded NA" />
        </label>
        <fieldset className="field">
          <legend className="field__label">Termine (deutsche Zeit)</legend>
          <div className="plan-create__slots">
            {slots.map((value, i) => (
              <div key={i} className="plan-create__slot">
                <input
                  type="datetime-local"
                  className="input"
                  value={value}
                  min={minimum}
                  step={900}
                  onChange={(e) => setSlots(slots.map((v, k) => (k === i ? e.target.value : v)))}
                  aria-label={`Termin ${i + 1}`}
                />
                {slots.length > 1 && (
                  <button type="button" className="btn btn--icon btn--ghost" onClick={() => setSlots(slots.filter((_, k) => k !== i))} aria-label={`Termin ${i + 1} entfernen`}>
                    <X className="icon icon--sm" aria-hidden="true" />
                  </button>
                )}
              </div>
            ))}
            {slots.length < 12 && (
              <button
                type="button"
                className="btn"
                onClick={() => {
                  const last = slots[slots.length - 1];
                  const d = last ? new Date(last) : new Date();
                  d.setDate(d.getDate() + 1);
                  setSlots([...slots, localInput(d)]);
                }}
              >
                <Plus className="icon icon--sm" aria-hidden="true" />
                Termin
              </button>
            )}
          </div>
        </fieldset>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="row">
          <button type="submit" className="btn btn--primary" disabled={busy || !title.trim() || !slots.some(Boolean)}>
            {busy ? 'Wird angelegt …' : 'Planung anlegen'}
          </button>
          <button type="button" className="btn btn--ghost" onClick={onCancel}>Abbrechen</button>
        </div>
      </form>
    </section>
  );
}

export function PlanPage() {
  const { id = '' } = useParams();
  const loaded = useApi<PlanDetail>(`planer/${encodeURIComponent(id)}`);
  const [plan, setPlan] = useState<PlanDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { perms } = usePermissions();
  const navigate = useNavigate();
  useEffect(() => setPlan(loaded.data), [loaded.data]);

  const act = async (body: Record<string, unknown>) => {
    setBusy(true);
    setError(null);
    try {
      const next = await postJson<PlanDetail & { ok?: boolean }>(`/api/planer/${encodeURIComponent(id)}`, body);
      clearApiCache();
      if (body.action === 'delete') navigate('/planer');
      else setPlan(next);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page page--tight">
      <Link className="text-link wiki__back" to="/planer">
        <ArrowLeft className="icon icon--sm" aria-hidden="true" />
        Alle Planungen
      </Link>
      <Async state={plan ? { data: plan, error: null, loading: false } : loaded}>
        {(p) => {
          const top = Math.max(1, ...p.slots.map((s) => s.yes));
          const chosen = p.slots.find((s) => s.id === p.chosen);
          const others = p.people.filter((person) => !person.me);
          return (
            <>
              <div className="page-head">
                <h1 className="page-title">{p.title}</h1>
                {p.note && <p className="lead plan-note">{p.note}</p>}
                <span className="caption">Angelegt von {p.owner} · {p.voters} {p.voters === 1 ? 'Antwort' : 'Antworten'}</span>
              </div>
              {chosen && (
                <div className="plan-chosen">
                  <CalendarCheck className="icon" aria-hidden="true" />
                  <div className="stack" style={{ gap: 2 }}>
                    <span className="eyebrow">Festgelegt</span>
                    <b>{longFormat.format(new Date(chosen.at))} Uhr</b>
                  </div>
                  {perms?.eventMaker && (
                    <Link className="btn" to={`/events?start=${encodeURIComponent(localInput(new Date(chosen.at)))}`}>
                      <CalendarPlus className="icon icon--sm" aria-hidden="true" />
                      Als Discord-Event posten
                    </Link>
                  )}
                </div>
              )}
              {!p.loggedIn && (
                <p className="caption">{p.loginEnabled ? 'Melde dich mit Discord an (oben rechts), um abzustimmen.' : 'Auf dieser Website ist die Anmeldung aus.'}</p>
              )}
              <div className="plan-table-wrap">
                <table className="plan-table">
                  <thead>
                    <tr>
                      <th scope="col" className="plan-table__who">Wer</th>
                      {p.slots.map((s) => (
                        <th key={s.id} scope="col" className={`${s.id === p.best ? 'is-best' : ''}${s.id === p.chosen ? ' is-chosen' : ''}`}>
                          <SlotDate at={s.at} />
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {p.loggedIn && (
                      <tr className="plan-table__mine">
                        <th scope="row" className="plan-table__who">Du</th>
                        {p.slots.map((s) => {
                          const answer = p.myAnswers[String(s.id)];
                          return (
                            <td key={s.id}>
                              <div className="answer-picker" role="group" aria-label={`Antwort für ${longFormat.format(new Date(s.at))}`}>
                                {[YES, MAYBE, NO].map((value) => (
                                  <button
                                    key={value}
                                    type="button"
                                    disabled={busy}
                                    className={`answer answer--${value}${answer === value ? ' is-on' : ''}`}
                                    aria-pressed={answer === value}
                                    title={value === YES ? 'Kann' : value === MAYBE ? 'Vielleicht' : 'Kann nicht'}
                                    onClick={() => void act({ action: 'vote', answers: { [s.id]: answer === value ? null : value } })}
                                  >
                                    <AnswerIcon value={value} />
                                  </button>
                                ))}
                              </div>
                            </td>
                          );
                        })}
                      </tr>
                    )}
                    {others.map((person, i) => (
                      <tr key={i}>
                        <th scope="row" className="plan-table__who">
                          <span className="player">
                            <Avatar colorId={person.colorId} playerKey={person.key} src={person.avatar} size="sm" />
                            {person.key ? <Link to={`/spieler/${person.key}`}>{person.name}</Link> : <span>{person.name}</span>}
                          </span>
                        </th>
                        {p.slots.map((s) => {
                          const answer = person.answers[String(s.id)];
                          return (
                            <td key={s.id}>
                              {answer === undefined ? <span className="answer answer--none" aria-label="keine Antwort">·</span> : (
                                <span className={`answer answer--${answer} is-on`} aria-label={answer === YES ? 'kann' : answer === MAYBE ? 'vielleicht' : 'kann nicht'}>
                                  <AnswerIcon value={answer} />
                                </span>
                              )}
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th scope="row" className="plan-table__who">Zusagen</th>
                      {p.slots.map((s) => (
                        <td key={s.id} className={s.id === p.best ? 'is-best' : undefined}>
                          <span className="plan-sum">
                            <span className="plan-sum__bar" style={{ ['--h' as string]: `${(s.yes / top) * 100}%` }} aria-hidden="true" />
                            <b>{s.yes}</b>
                            <span className="caption">{s.maybe > 0 ? `+${s.maybe}?` : '\u00a0'}</span>
                          </span>
                          {p.mine && (
                            <button
                              type="button"
                              className={`btn plan-choose${s.id === p.chosen ? ' is-on' : ''}`}
                              disabled={busy}
                              onClick={() => void act({ action: 'choose', slot: s.id === p.chosen ? null : s.id })}
                            >
                              {s.id === p.chosen ? 'Festgelegt' : 'Festlegen'}
                            </button>
                          )}
                        </td>
                      ))}
                    </tr>
                  </tfoot>
                </table>
              </div>
              {error && <p className="form-error" role="alert">{error}</p>}
              {p.mine && (
                <Section title="Verwalten">
                  <div className="row">
                    <button
                      type="button"
                      className="btn"
                      disabled={busy}
                      onClick={() => {
                        if (window.confirm('Planung wirklich löschen?')) void act({ action: 'delete' });
                      }}
                    >
                      <Trash2 className="icon icon--sm" aria-hidden="true" />
                      Planung löschen
                    </button>
                    <span className="caption">Link zum Teilen: {window.location.href}</span>
                  </div>
                </Section>
              )}
            </>
          );
        }}
      </Async>
    </div>
  );
}

function AnswerIcon({ value }: { value: number }) {
  if (value === YES) return <Check className="icon icon--sm" aria-hidden="true" />;
  if (value === MAYBE) return <HelpCircle className="icon icon--sm" aria-hidden="true" />;
  return <X className="icon icon--sm" aria-hidden="true" />;
}
