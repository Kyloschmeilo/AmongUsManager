import type { ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeftRight, Clock, Crosshair, Eye, EyeOff, Footprints, HeartPulse, PenLine, ShieldCheck, Skull, Swords, Trophy, UserX, Vote, Wind, Wrench } from 'lucide-react';
import { useApi, type PlayerSummary, type Profile, type PlayerRef } from '../lib/api';
import { ago, date, distance, duration, num, pct } from '../lib/format';
import { TEAM_COLOR, TEAM_LABEL, deathCause, teamPillClass } from '../lib/labels';
import { PLAYER_COLORS } from '../lib/colors';
import { metric, type Metric } from '../lib/metrics';
import { useMe } from '../lib/me';
import { Async, Avatar, BarList, Form, PlayerLink, RoundList, Section, StatCard } from '../components/ui';
import { Title } from '../components/motion';
import { ProfileEditor } from '../components/ProfileEditor';
import { BadgeSection } from '../components/Badges';

const LOOK_LABEL = { hat: 'Hut', visor: 'Visier', skin: 'Skin', pet: 'Haustier' } as const;

/** Der Crewmate so, wie der Spieler meistens aussieht (meistgetragene Farbe und Kosmetik). */
function LookFigure({ src, look }: { src: string; look: NonNullable<Profile['look']> }) {
  const parts = (Object.keys(LOOK_LABEL) as (keyof typeof LOOK_LABEL)[]).filter((k) => look[k]);
  return (
    <figure className="profile-look">
      <img className="profile-look__img" src={src} alt="Crewmate mit meistgetragener Kosmetik" width={360} height={340} />
      <figcaption>
        <span className="eyebrow">Meistens unterwegs mit</span>
        {parts.length ? (
          <dl>
            {parts.map((k) => (
              <div key={k} style={{ display: 'contents' }}>
                <dt>{LOOK_LABEL[k]}</dt>
                <dd>{look[k]!.name}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <p className="caption" style={{ margin: 0 }}>nichts außer der Farbe</p>
        )}
      </figcaption>
    </figure>
  );
}

const SHOWCASE_ICON: Record<Metric['group'], ReactNode> = {
  Allgemein: <Trophy className="icon" />,
  Crew: <ShieldCheck className="icon" />,
  Impostor: <Crosshair className="icon" />,
  Details: <Footprints className="icon" />
};

/** Kennzahl-Karten oben im Profil: erst die selbst gewählten, dann die üblichen (zusammen höchstens vier). */
function statCards(p: PlayerSummary, showcase: string[]): ReactNode[] {
  const chosen = showcase.map((id) => metric(id)).filter((m, i, all) => all.findIndex((o) => o.id === m.id) === i);
  const cards: { id: string; node: ReactNode }[] = chosen.map((m) => ({
    id: m.id,
    node: <StatCard key={m.id} icon={SHOWCASE_ICON[m.group]} label={m.label} value={0} raw={m.format(p)} chip="Favorit" caption={m.detail(p)} />
  }));
  const defaults: { id: string; node: ReactNode }[] = [
    { id: 'wins', node: <StatCard key="wins" icon={<Trophy className="icon" />} label="Siege" value={p.wins} chip={`${num(p.games)} Runden`} caption={`Siegquote ${pct(p.wins, p.games)}`} /> },
    { id: 'kills', node: <StatCard key="kills" icon={<Crosshair className="icon" />} label="Kills" value={p.kills} chip="gesamt" caption={`${num(p.firstKills)} × der erste Kill der Runde`} /> },
    { id: 'survival', node: <StatCard key="survival" icon={<HeartPulse className="icon" />} label="Überlebt" value={0} raw={pct(p.survived, p.games)} chip="der Runden" caption={`${num(p.killed)} × getötet, ${num(p.ejected)} × rausgewählt`} /> },
    { id: 'votes', node: <StatCard key="votes" icon={<Vote className="icon" />} label="Richtige Stimmen" value={0} raw={pct(p.correctVotes, p.crewVotes)} chip="als Crew" caption={`${num(p.correctVotes)} von ${num(p.crewVotes)} Stimmen trafen einen Impostor`} /> }
  ];
  for (const d of defaults) if (cards.length < 4 && !cards.some((c) => c.id === d.id)) cards.push(d);
  return cards.map((c) => c.node);
}

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
  const custom = profile.custom;
  const otherNames = profile.names.filter((n) => n !== p.name && n !== profile.ingameName);
  const { me } = useMe();
  const [params, setParams] = useSearchParams();
  const isMe = !!me?.player && me.player.key === p.key;
  const editing = isMe && params.has('bearbeiten') && !!me?.options && !!me?.profile;
  const setEditing = (on: boolean) => {
    const next = new URLSearchParams(params);
    if (on) next.set('bearbeiten', '1');
    else next.delete('bearbeiten');
    setParams(next, { replace: true, preventScrollReset: true });
  };
  const profileColor = custom?.colorId != null ? PLAYER_COLORS[custom.colorId] : null;
  return (
    <>
      <section className={`stack profile-hero${profileColor ? ' profile-hero--color' : ''}`} style={{ gap: 20, ['--profile' as string]: profileColor ?? undefined }}>
        <div className="profile-hero__main">
          <Avatar colorId={p.colorId} playerKey={p.key} src={p.avatar} size="xl" />
          <div className="stack" style={{ gap: 8, minWidth: 0, flex: 1 }}>
            <Title text={p.name} className="page-title" />
            {p.name !== profile.ingameName && <span className="caption">Im Spiel: {profile.ingameName}</span>}
            {custom?.bio && <p className="profile-bio">{custom.bio}</p>}
            <div className="row" style={{ gap: 8 }}>
              {p.linked ? <span className="pill">Discord verknüpft</span> : <span className="pill pill--plain">Nicht verknüpft</span>}
              {p.level != null && <span className="pill pill--mono">Level {p.level}</span>}
              {p.favoriteRole && <span className="pill pill--plain">Lieblingsrolle {p.favoriteRole}</span>}
              {custom?.favoriteMap && (
                <Link className="pill pill--plain" to={`/maps/${custom.favoriteMap.mapId}`}>Lieblingsmap {custom.favoriteMap.map}</Link>
              )}
              <span className="caption">Zuletzt {ago(p.lastSeen)} · dabei seit {date(p.firstSeen)}</span>
            </div>
            {otherNames.length > 0 && <span className="caption">Auch gespielt als {otherNames.slice(0, 6).join(', ')}</span>}
          </div>
          {p.crewmate && profile.look && <LookFigure src={p.crewmate} look={profile.look} />}
          <div className="row profile-hero__edit" style={{ gap: 8 }}>
            {!editing && (
              <Link className="btn" to={`/vergleich?a=${p.key}${me?.player && !isMe ? `&b=${me.player.key}` : ''}`}>
                <ArrowLeftRight className="icon icon--sm" aria-hidden="true" />
                {me?.player && !isMe ? 'Mit mir vergleichen' : 'Vergleichen'}
              </Link>
            )}
            {isMe && !editing && (
              <button type="button" className="btn" onClick={() => setEditing(true)}>
                <PenLine className="icon icon--sm" aria-hidden="true" />
                Profil bearbeiten
              </button>
            )}
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

      {editing && me?.options && me.profile && (
        <ProfileEditor
          settings={me.profile}
          options={me.options}
          ingameName={profile.ingameName}
          hasDiscordAvatar={!!me.user?.hasDiscordAvatar}
          onDone={() => setEditing(false)}
        />
      )}

      <div className="grid grid--stats">{statCards(p, custom?.showcase ?? [])}</div>

      {profile.badges && profile.badges.length > 0 && <BadgeSection badges={profile.badges} />}

      <Section title="Nach Team">
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

      <Section title="Rollen">
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

      <Section title="Rivalen" eyebrow="wer wen am häufigsten erwischt">
        <div className="grid grid--3">
          <RivalList icon={<Swords className="icon" />} title="Tötet am liebsten" empty="Noch kein Kill." rows={profile.victims.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
          <RivalList icon={<Skull className="icon" />} title="Wird getötet von" empty="Noch nie getötet worden." rows={profile.killers.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
          <RivalList icon={<Crosshair className="icon" />} title="Impostor-Partner" empty="Noch nie zu zweit Impostor." rows={profile.partners.map((v) => ({ player: v.player, text: `${num(v.wins)} / ${num(v.games)} gewonnen` }))} />
        </div>
      </Section>

      <VoteSection profile={profile} />

      <div className="grid grid--2">
        <Section title="Maps">
          <div className="panel panel--pad">
            <BarList items={profile.maps.map((m) => ({ label: <Link to={`/maps/${m.mapId}`}>{m.map}</Link>, value: m.games, text: `${num(m.games)} · ${pct(m.wins, m.games)} Siege` }))} />
          </div>
        </Section>
        <Section title="Tode">
          <div className="panel panel--pad">
            <BarList items={profile.deathCauses.map((d) => ({ label: deathCause(d.cause), value: d.count, text: num(d.count), tone: 'ember' as const }))} />
          </div>
        </Section>
      </div>

      {p.detailGames > 0 && (
        <Section title="Unterwegs" eyebrow="aufgezeichnet ab CrewStats 1.5.0">
          <div className="grid grid--stats">
            <StatCard icon={<Footprints className="icon" />} label="Strecke" value={0} raw={distance(p.distance)} chip={`${num(p.detailGames)} Runden`} caption={`im Schnitt ${distance(p.distance / p.detailGames)} pro Runde`} />
            <StatCard icon={<Wrench className="icon" />} label="Reparaturen" value={p.sabotagesFixed} chip="Sabotagen" caption={`${num(p.sabotages)} selbst sabotiert`} />
            <StatCard icon={<Wind className="icon" />} label="Vents" value={p.ventUses} chip="Einstiege" caption={`${num(p.tasksDone)} Aufgaben erledigt`} />
            <StatCard icon={<Clock className="icon" />} label="Spielzeit" value={0} raw={duration(p.playSeconds)} chip="gesamt" caption={`${num(p.meetingsCalled)} Notfall-Meetings, ${num(p.bodiesReported)} Leichen gemeldet`} />
          </div>
        </Section>
      )}

      <Section title="Letzte Runden" action={<Link className="btn" to={`/runden?spieler=${p.key}`}>Alle Runden</Link>}>
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

/** Verdachts-Statistik: wie oft man zu Recht oder zu Unrecht gestimmt hat und wer wen verdächtigt. */
function VoteSection({ profile }: { profile: Profile }) {
  const p = profile.player;
  const wrong = p.crewVotes - p.correctVotes;
  const crewTotal = p.crewVotes + p.crewSkips;
  const ejections = p.rightEjections + p.wrongEjections;
  if (crewTotal === 0 && p.impostorVotes === 0 && p.suspectedAsCrew + p.suspectedAsImpostor === 0) return null;
  const share = (n: number) => `${crewTotal ? (n / crewTotal) * 100 : 0}%`;
  return (
    <Section title="Abstimmungen" eyebrow="zu Recht oder zu Unrecht verdächtigt">
      {crewTotal > 0 && (
        <div className="panel panel--pad stack" style={{ gap: 12 }}>
          <div className="vote-legend">
            <span><i style={{ background: TEAM_COLOR.IMPOSTOR }} />Impostor getroffen <b className="mono">{num(p.correctVotes)}</b></span>
            <span><i style={{ background: TEAM_COLOR.CREW }} />Crewmate verdächtigt <b className="mono">{num(wrong)}</b></span>
            <span><i className="vote-skip" />Übersprungen <b className="mono">{num(p.crewSkips)}</b></span>
          </div>
          <div className="split" aria-label={`Als Crew: ${p.correctVotes} richtig, ${wrong} falsch, ${p.crewSkips} übersprungen`}>
            {p.correctVotes > 0 && <div style={{ width: share(p.correctVotes), background: TEAM_COLOR.IMPOSTOR }} />}
            {wrong > 0 && <div style={{ width: share(wrong), background: TEAM_COLOR.CREW }} />}
            {p.crewSkips > 0 && <div className="vote-skip" style={{ width: share(p.crewSkips) }} />}
          </div>
          <span className="caption">Alle Stimmen als Crewmate. Stimmen gegen Neutrale zählen weder richtig noch falsch.</span>
        </div>
      )}
      <div className="grid grid--stats">
        <StatCard icon={<Vote className="icon" />} label="Richtige Stimmen" value={0} raw={pct(p.correctVotes, p.crewVotes)} chip="als Crew" caption={`${num(p.correctVotes)} von ${num(p.crewVotes)} Stimmen auf Spieler`} />
        <StatCard icon={<UserX className="icon" />} label="Richtige Rauswürfe" value={0} raw={pct(p.rightEjections, ejections)} chip="mitgestimmt" caption={`${num(p.rightEjections)} × Impostor, ${num(p.wrongEjections)} × Crewmate rausgewählt`} />
        <StatCard icon={<EyeOff className="icon" />} label="Zu Unrecht verdächtigt" value={p.suspectedAsCrew} chip="als Crew" caption={`Stimmen von Crewmates in ${num(p.crewGames)} Crew-Runden`} />
        <StatCard icon={<Eye className="icon" />} label="Durchschaut" value={p.suspectedAsImpostor} chip="als Impostor" caption={`Stimmen von Crewmates · ${num(p.deceptions)} × selbst einen Crewmate mit rausgewählt`} />
      </div>
      <div className="grid grid--3">
        <RivalList icon={<EyeOff className="icon" />} title="Verdächtigst du zu Unrecht" empty="Noch nie einen Crewmate verdächtigt." rows={profile.wrongSuspects.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
        <RivalList icon={<Vote className="icon" />} title="Verdächtigt dich zu Unrecht" empty="Noch nie zu Unrecht verdächtigt." rows={profile.suspectedBy.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
        <RivalList icon={<Eye className="icon" />} title="Durchschaut dich als Impostor" empty="Als Impostor noch nie erwischt." rows={profile.caughtBy.map((v) => ({ player: v.player, text: `${num(v.count)} ×` }))} />
      </div>
    </Section>
  );
}
