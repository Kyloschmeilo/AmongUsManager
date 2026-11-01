import { Fragment, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { Info, Siren } from 'lucide-react';
import { useApi, type MapDetail, type RoundDetail, type RoundPlayer } from '../lib/api';
import { clock, date, distance, duration, num, time } from '../lib/format';
import { PLATFORMS, TEAM_LABEL, deathCause, endReason, humanize, modLabel, room, teamPillClass } from '../lib/labels';
import { Async, PlayerLink, Section, TeamPill } from '../components/ui';
import { Title } from '../components/motion';
import { Replay } from '../replay/Replay';
import { describe, nameLookup, timelineOf } from '../replay/events';

export function RoundPage() {
  const { gameId } = useParams();
  const round = useApi<RoundDetail>(gameId ? `rounds/${encodeURIComponent(gameId)}` : null);
  return (
    <div className="page page--tight">
      <Async state={round}>{(r) => <RoundView round={r} />}</Async>
    </div>
  );
}

function RoundView({ round }: { round: RoundDetail }) {
  const mapId = round.mapId ?? -1;
  const hasTracks = (round.tracks ?? []).some((t) => t.points.length >= 6);
  const layout = useApi<MapDetail>(hasTracks && mapId >= 0 ? `maps/${mapId}` : null);
  const name = useMemo(() => nameLookup(round), [round]);
  const ended = round.endedAt;
  return (
    <>
      <section className="stack" style={{ gap: 16 }}>
        <span className="eyebrow">Runde{ended ? ` · ${date(ended)}, ${time(ended)} Uhr` : ''}</span>
        <Title text={round.map} className="page-title" />
        <div className="row" style={{ gap: 8 }}>
          <TeamPill team={round.winningTeam} />
          <span className="pill pill--plain">{endReason(round.endReason)}</span>
          <span className="pill pill--mono">{duration(round.durationSeconds)}</span>
          {round.mod && <span className="pill pill--sky pill--case">{modLabel(round.mod)}</span>}
          {round.modGameMode && round.modGameMode !== 'Classic' && <span className="pill pill--plain">{humanize(round.modGameMode)}</span>}
          {round.server && <span className="caption">{round.server}</span>}
        </div>
      </section>

      {hasTracks ? (
        layout.loading ? null : (
          <Replay round={round} layout={layout.data ? { cellSize: layout.data.cellSize, cells: layout.data.cells, rooms: layout.data.rooms, vents: layout.data.vents } : null} />
        )
      ) : (
        <div className="note">
          <Info className="icon" aria-hidden="true" />
          <span>
            {round.web.legacy || round.version < 2
              ? 'Diese Runde stammt von vor CrewStats 1.5.0 – dafür gibt es noch keine Laufwege und kein Replay, nur Ergebnis, Kills und Abstimmungen.'
              : 'Für diese Runde fehlen die Laufwege (zu groß zum Verschicken), deshalb gibt es kein Replay.'}
          </span>
        </div>
      )}

      <Section eyebrow="Spieler" title="Wer was war">
        <PlayersTable round={round} name={name} />
      </Section>

      {round.meetings.length > 0 && (
        <Section eyebrow="Meetings" title={`${round.meetings.length} ${round.meetings.length === 1 ? 'Meeting' : 'Meetings'}`}>
          <div className="stack" style={{ gap: 12 }}>
            {round.meetings.map((m, i) => (
              <div key={i} className="panel meeting">
                <div className="meeting__head">
                  <span className="mono faint">{clock(m.atSeconds)}</span>
                  <Siren className="icon icon--sm" style={{ color: 'var(--accent)' }} aria-hidden="true" />
                  <span className="ink">
                    {m.body != null ? <><b>{name(m.caller)}</b> meldet die Leiche von <b>{name(m.body)}</b>{m.bodyRoom ? ` (${room(m.bodyRoom)})` : ''}</> : <><b>{name(m.caller)}</b> ruft ein Notfall-Meeting{m.callerRoom ? ` (${room(m.callerRoom)})` : ''}</>}
                  </span>
                  <span className="spacer" />
                  {m.exiled != null ? (
                    <span className="pill pill--ember">{name(m.exiled)} fliegt raus</span>
                  ) : (
                    <span className="pill pill--plain">{m.tie ? 'Gleichstand' : 'Niemand fliegt raus'}</span>
                  )}
                </div>
                <div className="votes">
                  {m.votes.map((v, j) => {
                    const voter = round.players.find((p) => p.playerId === v.voter);
                    const target = round.players.find((p) => p.playerId === v.target);
                    return (
                      <span key={j} className="vote">
                        {voter ? <PlayerLink player={voter} /> : name(v.voter)}
                        <span className="arrow">→</span>
                        {target ? <PlayerLink player={target} /> : <span className="faint">{v.skipped === false ? 'nicht abgestimmt' : 'übersprungen'}</span>}
                      </span>
                    );
                  })}
                  {m.votes.length === 0 && <span className="caption">Keine Stimmen aufgezeichnet.</span>}
                </div>
              </div>
            ))}
          </div>
        </Section>
      )}

      {!hasTracks && (
        <Section eyebrow="Zeitleiste" title="Was passiert ist">
          <ol className="panel feed__list" style={{ maxHeight: 'none' }}>
            {timelineOf(round).filter((e) => e.type !== 'vote').map((e, i) => (
              <li key={i} className="feed__item" style={{ cursor: 'default' }}>
                <span className="feed__time">{clock(e.atSeconds)}</span>
                <span>{describe(e, name)}</span>
              </li>
            ))}
          </ol>
        </Section>
      )}

      {round.settings && <Settings round={round} />}
    </>
  );
}

function PlayersTable({ round, name }: { round: RoundDetail; name: (id: number | null | undefined) => string }) {
  const order: Record<string, number> = { IMPOSTOR: 0, NEUTRAL: 1, CREW: 2, UNKNOWN: 3 };
  const players = [...round.players].sort((a, b) => order[a.team] - order[b.team] || Number(b.won) - Number(a.won) || a.name.localeCompare(b.name, 'de'));
  const detail = players.some((p) => p.summary);
  return (
    <div className="panel table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Spieler</th>
            <th>Rolle</th>
            <th>Ergebnis</th>
            <th className="num">Kills</th>
            <th className="num">Aufgaben</th>
            <th>Tod</th>
            {detail && <th className="num">Strecke</th>}
          </tr>
        </thead>
        <tbody>
          {players.map((p) => (
            <tr key={p.playerId}>
              <td><PlayerLink player={p} dead={p.isDead} />{p.isHost && <span className="caption" style={{ marginLeft: 8 }}>Host</span>}</td>
              <td>
                <div className="stack" style={{ gap: 2 }}>
                  <span className="row" style={{ gap: 8 }}>
                    <span className={teamPillClass(p.team)}>{TEAM_LABEL[p.team]}</span>
                    <span className="ink">{roleText(p)}</span>
                  </span>
                  {p.modifiers && p.modifiers.length > 0 && <span className="caption">{p.modifiers.map(humanize).join(', ')}</span>}
                </div>
              </td>
              <td style={{ color: p.won ? 'var(--accent)' : 'var(--ink-faint)', fontWeight: 600 }}>{p.won ? 'Sieg' : 'Niederlage'}</td>
              <td className="num">{num(p.kills)}</td>
              <td className="num">{p.tasksTotal > 0 ? `${p.tasksCompleted} / ${p.tasksTotal}` : '–'}</td>
              <td>
                {p.isDead || p.disconnected ? (
                  <span className={p.deathCause === 'disconnect' ? 'faint' : 'ember'}>
                    {deathCause(p.deathCause ?? (p.disconnected ? 'disconnect' : 'kill'))}
                    {p.diedAtSeconds != null && <span className="faint mono" style={{ fontSize: 12 }}> {clock(p.diedAtSeconds)}</span>}
                    {p.killedBy != null && p.killedBy !== p.playerId && <span className="faint"> von {name(p.killedBy)}</span>}
                  </span>
                ) : (
                  <span className="faint">Überlebt</span>
                )}
              </td>
              {detail && <td className="num">{p.summary ? distance(p.summary.distance) : '–'}</td>}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="caption" style={{ padding: '12px 16px' }}>
        {[...new Set(round.players.map((p) => p.platform).filter(Boolean))].map((x) => PLATFORMS[x!] ?? x).join(', ')}
      </p>
    </div>
  );
}

function roleText(p: RoundPlayer): string {
  const start = p.roleName ?? humanize(p.role);
  if (p.modRoleName && p.modRoleName !== start) return `${start} → ${p.modRoleName}`;
  return start;
}

const SETTINGS: [string, string, (v: number | boolean) => string][] = [
  ['KillCooldown', 'Kill-Cooldown', (v) => `${v} s`],
  ['KillDistance', 'Kill-Reichweite', (v) => ['kurz', 'mittel', 'lang'][Number(v)] ?? String(v)],
  ['PlayerSpeedMod', 'Laufgeschwindigkeit', (v) => `${v}×`],
  ['CrewLightMod', 'Sicht Crew', (v) => `${v}×`],
  ['ImpostorLightMod', 'Sicht Impostor', (v) => `${v}×`],
  ['NumEmergencyMeetings', 'Notfall-Meetings', (v) => String(v)],
  ['DiscussionTime', 'Diskussion', (v) => `${v} s`],
  ['VotingTime', 'Abstimmung', (v) => `${v} s`],
  ['NumCommonTasks', 'Gemeinsame Aufgaben', (v) => String(v)],
  ['NumLongTasks', 'Lange Aufgaben', (v) => String(v)],
  ['NumShortTasks', 'Kurze Aufgaben', (v) => String(v)],
  ['ConfirmImpostor', 'Rauswurf bestätigen', (v) => (v ? 'an' : 'aus')],
  ['AnonymousVotes', 'Anonyme Stimmen', (v) => (v ? 'an' : 'aus')],
  ['VisualTasks', 'Sichtbare Aufgaben', (v) => (v ? 'an' : 'aus')]
];

function Settings({ round }: { round: RoundDetail }) {
  const s = round.settings!;
  const all: Record<string, number | boolean> = { ...(s.ints ?? {}), ...(s.floats ?? {}), ...(s.bools ?? {}) };
  const rows = SETTINGS.filter(([key]) => all[key] !== undefined);
  if (!rows.length && s.numImpostors == null) return null;
  return (
    <Section eyebrow="Einstellungen" title="Lobby-Einstellungen">
      <div className="panel panel--pad">
        <dl className="kv">
          {s.numImpostors != null && (<><dt>Impostor</dt><dd>{s.numImpostors}</dd></>)}
          {s.maxPlayers != null && (<><dt>Spieler höchstens</dt><dd>{s.maxPlayers}</dd></>)}
          {rows.map(([key, label, format]) => (
            <Fragment key={key}><dt>{label}</dt><dd>{format(all[key])}</dd></Fragment>
          ))}
        </dl>
        {(round.gameVersion || round.crewStatsVersion) && (
          <p className="caption" style={{ marginTop: 16 }}>
            Among Us {round.gameVersion ?? '?'} · CrewStats {round.crewStatsVersion ?? '?'}
          </p>
        )}
      </div>
    </Section>
  );
}
