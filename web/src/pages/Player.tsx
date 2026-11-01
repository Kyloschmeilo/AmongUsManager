import { Link, useParams } from 'react-router-dom';
import { Clock, Crosshair, Footprints, HeartPulse, Skull, Swords, Trophy, Vote, Wind, Wrench } from 'lucide-react';
import { useApi, type Profile, type PlayerRef } from '../lib/api';
import { ago, date, distance, duration, num, pct } from '../lib/format';
import { TEAM_COLOR, TEAM_LABEL, deathCause, teamPillClass } from '../lib/labels';
import { Async, Avatar, BarList, Form, PlayerLink, RoundList, Section, StatCard } from '../components/ui';
import { Title } from '../components/motion';

export function PlayerPage() {
  const { key } = useParams();
  const profile = useApi<Profile>(key ? `players/${encodeURIComponent(key)}` : null);
  return (
    <div className="page">
      <Async state={profile}>{(p) => <ProfileView profile={p} />}</Async>
    </div>
  );
}

function ProfileView({ profile }: { profile: Profile }) {
  const p = profile.player;
  const otherNames = profile.names.filter((n) => n !== p.name);
  return (
    <>
      <section className="stack" style={{ gap: 20 }}>
        <span className="eyebrow">Spieler</span>
        <div className="row" style={{ gap: 20, alignItems: 'center' }}>
          <Avatar colorId={p.colorId} playerKey={p.key} size="lg" />
          <div className="stack" style={{ gap: 8, minWidth: 0 }}>
            <Title text={p.name} className="page-title" />
            <div className="row" style={{ gap: 8 }}>
              {p.linked ? <span className="pill">Discord verknüpft</span> : <span className="pill pill--plain">Nicht verknüpft</span>}
              {p.level != null && <span className="pill pill--mono">Level {p.level}</span>}
              {p.favoriteRole && <span className="pill pill--plain">Lieblingsrolle {p.favoriteRole}</span>}
              <span className="caption">Zuletzt {ago(p.lastSeen)} · dabei seit {date(p.firstSeen)}</span>
            </div>
            {otherNames.length > 0 && <span className="caption">Auch gespielt als {otherNames.slice(0, 6).join(', ')}</span>}
          </div>
        </div>
        {profile.form.length > 0 && (
          <div className="row" style={{ gap: 12 }}>
            <span className="eyebrow">Form</span>
            <Form results={[...profile.form].reverse()} />
            <span className="caption">letzte {profile.form.length} Runden, rechts die neueste</span>
          </div>
        )}
      </section>

      <div className="grid grid--stats">
        <StatCard icon={<Trophy className="icon" />} label="Siege" value={p.wins} chip={`${num(p.games)} Runden`} caption={`Siegquote ${pct(p.wins, p.games)}`} />
        <StatCard icon={<Crosshair className="icon" />} label="Kills" value={p.kills} chip="gesamt" caption={`${num(p.firstKills)} × der erste Kill der Runde`} />
        <StatCard icon={<HeartPulse className="icon" />} label="Überlebt" value={0} raw={pct(p.survived, p.games)} chip="der Runden" caption={`${num(p.killed)} × getötet, ${num(p.ejected)} × rausgewählt`} />
        <StatCard icon={<Vote className="icon" />} label="Richtige Stimmen" value={0} raw={pct(p.correctVotes, p.crewVotes)} chip="als Crew" caption={`${num(p.correctVotes)} von ${num(p.crewVotes)} Stimmen trafen einen Impostor`} />
      </div>

      <Section eyebrow="Teams" title="Als Crew, Impostor und Neutral">
        <div className="grid grid--3">
          {([
            ['CREW', p.crewGames, p.crewWins],
            ['IMPOSTOR', p.impostorGames, p.impostorWins],
            ['NEUTRAL', p.neutralGames, p.neutralWins]
          ] as const).map(([team, games, wins]) => (
            <div key={team} className="panel panel--pad stack" style={{ gap: 12 }}>
              <span className={teamPillClass(team)} style={{ alignSelf: 'flex-start' }}><span className="pill__dot" />{TEAM_LABEL[team]}</span>
              <span className="mono" style={{ fontSize: 28, lineHeight: '32px', color: TEAM_COLOR[team] }}>{pct(wins, games)}</span>
              <span className="caption">{num(wins)} Siege in {num(games)} Runden</span>
              <div className="bar__track"><div className="bar__fill" style={{ width: `${games ? (wins / games) * 100 : 0}%`, background: TEAM_COLOR[team] }} /></div>
            </div>
          ))}
        </div>
      </Section>

      <Section eyebrow="Rollen" title="Gespielte Rollen">
        <div className="panel table-wrap">
          <table className="table">
            <thead>
              <tr><th>Rolle</th><th>Team</th><th className="num">Runden</th><th className="num">Siege</th><th className="num">Quote</th><th className="num">Kills</th></tr>
            </thead>
            <tbody>
              {profile.roles.map((r) => (
                <tr key={r.id}>
                  <td className="main">
                    {r.name}
                    {r.mod && <span className="caption" style={{ marginLeft: 8 }}>The Other Roles</span>}
                  </td>
                  <td>{r.team && <span className={teamPillClass(r.team)}>{TEAM_LABEL[r.team]}</span>}</td>
                  <td className="num">{num(r.games)}</td>
                  <td className="num">{num(r.wins)}</td>
                  <td className="num hi">{pct(r.wins, r.games)}</td>
                  <td className="num">{num(r.kills)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      <Section eyebrow="Rivalen" title="Wer wen erwischt">
        <div className="grid grid--3">
          <RivalList icon={<Swords className="icon" />} title="Tötet am liebsten" empty="Noch kein Kill." rows={profile.victims.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
          <RivalList icon={<Skull className="icon" />} title="Wird getötet von" empty="Noch nie getötet worden." rows={profile.killers.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
          <RivalList icon={<Crosshair className="icon" />} title="Impostor-Partner" empty="Noch nie zu zweit Impostor." rows={profile.partners.map((v) => ({ player: v.player, text: `${num(v.wins)} / ${num(v.games)} gewonnen` }))} />
        </div>
      </Section>

      <div className="grid grid--2">
        <Section eyebrow="Maps" title="Wo gespielt">
          <div className="panel panel--pad">
            <BarList items={profile.maps.map((m) => ({ label: <Link to={`/maps/${m.mapId}`}>{m.map}</Link>, value: m.games, text: `${num(m.games)} · ${pct(m.wins, m.games)} Siege` }))} />
          </div>
        </Section>
        <Section eyebrow="Tode" title="Wie es endet">
          <div className="panel panel--pad">
            <BarList items={profile.deathCauses.map((d) => ({ label: deathCause(d.cause), value: d.count, text: num(d.count), tone: 'ember' as const }))} />
          </div>
        </Section>
      </div>

      {p.detailGames > 0 && (
        <Section eyebrow="Seit CrewStats 1.5.0" title="Unterwegs">
          <div className="grid grid--stats">
            <StatCard icon={<Footprints className="icon" />} label="Strecke" value={0} raw={distance(p.distance)} chip={`${num(p.detailGames)} Runden`} caption={`im Schnitt ${distance(p.distance / p.detailGames)} pro Runde`} />
            <StatCard icon={<Wrench className="icon" />} label="Reparaturen" value={p.sabotagesFixed} chip="Sabotagen" caption={`${num(p.sabotages)} selbst sabotiert`} />
            <StatCard icon={<Wind className="icon" />} label="Vents" value={p.ventUses} chip="Einstiege" caption={`${num(p.tasksDone)} Aufgaben erledigt`} />
            <StatCard icon={<Clock className="icon" />} label="Spielzeit" value={0} raw={duration(p.playSeconds)} chip="gesamt" caption={`${num(p.meetingsCalled)} Notfall-Meetings, ${num(p.bodiesReported)} Leichen gemeldet`} />
          </div>
        </Section>
      )}

      <Section eyebrow="Verlauf" title="Letzte Runden" action={<Link className="btn" to={`/runden?spieler=${p.key}`}>Alle Runden</Link>}>
        <RoundList
          rounds={profile.rounds.map((r) => r.round)}
          extra={(round) => {
            const me = profile.rounds.find((r) => r.round.gameId === round.gameId)?.me;
            if (!me) return null;
            return (
              <span style={{ color: me.won ? 'var(--accent)' : 'var(--ink-faint)' }}>
                {me.role} · {me.won ? 'Sieg' : 'Niederlage'}
                {me.kills > 0 ? ` · ${me.kills} Kills` : ''}
                {me.dead ? ` · ${deathCause(me.deathCause)}` : ''}
              </span>
            );
          }}
        />
      </Section>
    </>
  );
}

function RivalList({ icon, title, rows, empty }: { icon: React.ReactNode; title: string; rows: { player: PlayerRef; text: string }[]; empty: string }) {
  return (
    <div className="panel board">
      <h3 className="panel__title">{icon}{title}</h3>
      {rows.length === 0 ? (
        <p className="caption">{empty}</p>
      ) : (
        <ol>
          {rows.map((row, i) => (
            <li key={row.player.key} style={{ gridTemplateColumns: '24px minmax(0,1fr) auto' }}>
              <span className={`rank${i === 0 ? ' rank--1' : ''}`}>{i + 1}</span>
              <PlayerLink player={row.player} />
              <span className="val">{row.text}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
