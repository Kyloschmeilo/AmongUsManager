// Monatsrückblick (/rueckblick, /rueckblick/2026-11): Runde des Monats mit Begründung, Auszeichnungen, Momente,
// Rollen und Maps des Monats. Server: web/MonthlyRecap.kt (live aus den Runden berechnet).
import { Link, useParams } from 'react-router-dom';
import { Award, CalendarDays, Crosshair, Film, Flame, Hourglass, Megaphone, Sparkles, Trophy, UserPlus, Users } from 'lucide-react';
import { useApi, type PlayerRef, type RoundItem, type Team } from '../lib/api';
import { date, duration, nightLabel, num, pct } from '../lib/format';
import { TEAM_COLOR, TEAM_LABEL, mapShort } from '../lib/labels';
import { Async, Avatar, BarList, Empty, PlayerLink, Roster, RoundRow, Section, StatCard, TeamPill } from '../components/ui';
import { Title } from '../components/motion';
import '../extras.css';

type ScoredRound = RoundItem & { score: number; reasons: string[] };

interface AwardRow {
  id: string;
  title: string;
  text: string;
  player: PlayerRef;
  value: string;
  unit: string;
  runnersUp: (PlayerRef & { value: string })[];
}

interface Moment {
  id: string;
  title: string;
  value: number;
  unit: string;
  player: { key: string; name: string; colorId: number | null } | null;
  round: RoundItem;
}

interface RecapResponse {
  months: { month: string; label: string; rounds: number }[];
  month: string | null;
  label?: string;
  totals?: {
    rounds: number;
    previousRounds: number;
    nights: number;
    players: number;
    newcomers: number;
    kills: number;
    meetings: number;
    ejections: number;
    playSeconds: number;
  };
  teams?: Record<Team, number>;
  bestNight?: { date: string; rounds: number } | null;
  roundOfMonth?: ScoredRound | null;
  candidates?: ScoredRound[];
  awards?: AwardRow[];
  moments?: Moment[];
  newcomers?: PlayerRef[];
  roles?: { id: string; name: string; games: number; wins: number }[];
  maps?: { mapId: number; name: string; rounds: number }[];
}

const AWARD_ICON: Record<string, typeof Trophy> = {
  mvp: Trophy,
  killer: Crosshair,
  impostor: Flame,
  detective: Megaphone,
  survivor: Sparkles,
  tasks: Award,
  regular: Users,
  unlucky: Hourglass
};

export function RecapPage() {
  const { month } = useParams();
  const recap = useApi<RecapResponse>(month ? `recap/${encodeURIComponent(month)}` : 'recap');
  return (
    <div className="page page--tight recap">
      <Async state={recap}>
        {(data) =>
          !data.month || !data.totals ? (
            <>
              <div className="page-head">
                <Title text="Rückblick" className="page-title" />
              </div>
              <Empty text="Noch keine Runden für einen Rückblick." />
            </>
          ) : (
            <RecapBody data={data} />
          )
        }
      </Async>
    </div>
  );
}

function RecapBody({ data }: { data: RecapResponse }) {
  const t = data.totals!;
  const teams = data.teams!;
  const decided = teams.CREW + teams.IMPOSTOR + teams.NEUTRAL;
  const delta = t.rounds - t.previousRounds;
  const round = data.roundOfMonth;
  return (
    <>
      <div className="page-head">
        <span className="eyebrow">Monatsrückblick</span>
        <h1 className="page-title">{data.label}</h1>
        <nav className="recap__months" aria-label="Monat wählen">
          {data.months.map((m) => (
            <Link key={m.month} to={`/rueckblick/${m.month}`} className={m.month === data.month ? 'is-on' : undefined} aria-current={m.month === data.month ? 'page' : undefined}>
              {m.label}
              <span className="caption"> {m.rounds}</span>
            </Link>
          ))}
        </nav>
      </div>

      <div className="grid grid--stats">
        <StatCard icon={<Film className="icon icon--sm" aria-hidden="true" />} label="Runden" value={t.rounds} caption={t.previousRounds ? `${delta >= 0 ? '+' : ''}${delta} zum Vormonat` : undefined} />
        <StatCard icon={<CalendarDays className="icon icon--sm" aria-hidden="true" />} label="Spieleabende" value={t.nights} caption={data.bestNight ? `meiste Runden am ${nightLabel(data.bestNight.date)} (${data.bestNight.rounds})` : undefined} />
        <StatCard icon={<Users className="icon icon--sm" aria-hidden="true" />} label="Spieler" value={t.players} caption={t.newcomers ? `${t.newcomers} zum ersten Mal dabei` : undefined} />
        <StatCard icon={<Crosshair className="icon icon--sm" aria-hidden="true" />} label="Kills" value={t.kills} caption={`${num(t.meetings)} Meetings, ${num(t.ejections)} rausgewählt`} />
        <StatCard icon={<Hourglass className="icon icon--sm" aria-hidden="true" />} label="Spielzeit" value={0} raw={duration(t.playSeconds)} />
      </div>

      {decided > 0 && (
        <div className="recap__tally" role="img" aria-label={`Crew ${teams.CREW}, Impostor ${teams.IMPOSTOR}, Neutral ${teams.NEUTRAL} Siege`}>
          {(['CREW', 'IMPOSTOR', 'NEUTRAL'] as Team[]).filter((k) => teams[k] > 0).map((k) => (
            <span key={k} style={{ flexGrow: teams[k], background: TEAM_COLOR[k] }}>
              <b>{teams[k]}</b> {TEAM_LABEL[k]}
            </span>
          ))}
        </div>
      )}

      {round && (
        <section className="rotm" aria-labelledby="rotm-title">
          <div className="rotm__badge">
            <Trophy className="icon" aria-hidden="true" />
            <span id="rotm-title">Runde des Monats</span>
          </div>
          <div className="rotm__body">
            <div className="rotm__main">
              <h2 className="rotm__title">
                {mapShort(round.mapId, round.map)}
                <span>{date(round.endedAt)}</span>
              </h2>
              <div className="row">
                <TeamPill team={round.winningTeam} />
                <span className="caption">{duration(round.duration)} · {num(round.kills)} Kills · {num(round.meetings)} Meetings</span>
              </div>
              <Roster roster={round.roster} />
              <div className="row">
                <Link className="btn btn--primary" to={`/runden/${round.gameId}`}>
                  {round.replay ? <Film className="icon icon--sm" aria-hidden="true" /> : null}
                  {round.replay ? 'Replay ansehen' : 'Zur Runde'}
                </Link>
              </div>
            </div>
            <ul className="rotm__reasons" aria-label="Warum diese Runde">
              {round.reasons.map((r, i) => (
                <li key={r} style={{ ['--i' as string]: i }}>{r}</li>
              ))}
            </ul>
          </div>
        </section>
      )}

      {data.candidates && data.candidates.length > 0 && (
        <Section title="Knapp dahinter" eyebrow="auch sehenswert">
          <div className="panel rounds">
            {data.candidates.map((c) => (
              <RoundRow key={c.gameId} round={c} extra={c.reasons.slice(0, 2).join(' · ')} />
            ))}
          </div>
        </Section>
      )}

      {data.awards && data.awards.length > 0 && (
        <Section title="Auszeichnungen">
          <div className="awards">
            {data.awards.map((a, i) => {
              const Icon = AWARD_ICON[a.id] ?? Award;
              return (
                <article key={a.id} className={`award award--${a.id}`} style={{ ['--i' as string]: i }}>
                  <div className="award__head">
                    <Icon className="icon" aria-hidden="true" />
                    <span>{a.title}</span>
                  </div>
                  <Link className="award__winner" to={`/spieler/${a.player.key}`}>
                    <Avatar colorId={a.player.colorId} playerKey={a.player.key} src={a.player.avatar} size="lg" />
                    <span className="award__name">{a.player.name}</span>
                  </Link>
                  <div className="award__value">
                    <b>{a.value}</b> <span>{a.unit}</span>
                  </div>
                  <span className="caption">{a.text}</span>
                  {a.runnersUp.length > 0 && (
                    <span className="caption award__runners">
                      Danach: {a.runnersUp.map((r) => `${r.name} (${r.value})`).join(', ')}
                    </span>
                  )}
                </article>
              );
            })}
          </div>
        </Section>
      )}

      {data.moments && data.moments.length > 0 && (
        <Section title="Momente">
          <div className="moments">
            {data.moments.map((m) => (
              <Link key={m.id} to={`/runden/${m.round.gameId}`} className="moment">
                <span className="eyebrow">{m.title}</span>
                <b className="moment__value">{m.unit === 's' ? duration(m.value) : `${num(m.value)} ${m.unit}`}</b>
                <span className="caption">
                  {m.player ? `${m.player.name} · ` : ''}
                  {mapShort(m.round.mapId, m.round.map)}, {date(m.round.endedAt)}
                </span>
              </Link>
            ))}
          </div>
        </Section>
      )}

      <div className="grid grid--2">
        {data.roles && data.roles.length > 0 && (
          <div className="panel">
            <h3 className="panel__title">Rollen des Monats</h3>
            <BarList items={data.roles.map((r) => ({ label: r.name, value: r.games, text: `${num(r.games)}× · ${pct(r.wins, r.games)} Siege`, tone: 'neutral' as const }))} />
          </div>
        )}
        {data.maps && data.maps.length > 0 && (
          <div className="panel">
            <h3 className="panel__title">Maps</h3>
            <BarList items={data.maps.map((m) => ({ label: mapShort(m.mapId, m.name), value: m.rounds, text: `${num(m.rounds)} Runden` }))} />
          </div>
        )}
      </div>

      {data.newcomers && data.newcomers.length > 0 && (
        <Section title="Neu dabei" eyebrow={`${data.newcomers.length} zum ersten Mal`}>
          <div className="newcomers">
            <UserPlus className="icon" aria-hidden="true" />
            {data.newcomers.map((p) => (
              <PlayerLink key={p.key} player={p} />
            ))}
          </div>
        </Section>
      )}
    </>
  );
}
