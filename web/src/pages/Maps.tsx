import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Crosshair, Info, Megaphone, ShieldCheck, Skull, Timer, TriangleAlert } from 'lucide-react';
import { useApi, type MapDetail, type MapSummary } from '../lib/api';
import { duration, num, pct } from '../lib/format';
import { TEAM_COLOR, room, system } from '../lib/labels';
import { Async, BarList, Empty, RoundList, Section, Segmented, StatCard } from '../components/ui';
import { Reveal, Title } from '../components/motion';
import { Density, DensityLegend, HeatLegend, MapView } from '../components/MapView';
import type { RoundItem } from '../lib/api';

export function MapsPage() {
  const maps = useApi<{ maps: MapSummary[] }>('maps');
  return (
    <div className="page page--tight">
      <div className="page-head">
        <Title text="Maps" className="page-title" />
        <p className="lead">Siege, Kills, Meetings und Laufwege je Map. Der Grundriss entsteht aus den Laufwegen aller Runden, er wird mit jeder Runde genauer.</p>
      </div>
      <Async state={maps}>
        {(data) =>
          data.maps.length === 0 ? (
            <Empty text="Noch keine Runden." />
          ) : (
            <Reveal>
              <div className="grid grid--3">
                {data.maps.map((m) => (
                  <Link key={m.mapId} to={`/maps/${m.mapId}`} className="panel mapcard">
                    <span className="eyebrow">{num(m.rounds)} Runden · {num(m.kills)} Kills · {num(m.meetings ?? 0)} Meetings</span>
                    <h3>{m.map}</h3>
                    <div className="split">
                      {m.crewWins > 0 && <div style={{ flex: m.crewWins, background: TEAM_COLOR.CREW }} />}
                      {m.impostorWins > 0 && <div style={{ flex: m.impostorWins, background: TEAM_COLOR.IMPOSTOR }} />}
                      {m.neutralWins > 0 && <div style={{ flex: m.neutralWins, background: TEAM_COLOR.NEUTRAL }} />}
                    </div>
                    <span className="caption">Crew {pct(m.crewWins, m.rounds)} · Impostor {pct(m.impostorWins, m.rounds)} · im Schnitt {duration(m.avgDuration)}</span>
                  </Link>
                ))}
              </div>
            </Reveal>
          )
        }
      </Async>
    </div>
  );
}

type Layer = 'heat' | 'kills' | 'meetings' | 'plain';
type MeetingFilter = 'all' | 'report' | 'emergency';

const KILL_COLOR = 'var(--ember)';
const MEETING_COLOR = 'var(--gold)';

export function MapPage() {
  const { mapId } = useParams();
  const map = useApi<MapDetail>(mapId ? `maps/${mapId}` : null);
  const rounds = useApi<{ total: number; items: RoundItem[] }>(mapId ? `rounds?map=${mapId}&limit=6` : null);
  const [layer, setLayer] = useState<Layer>('heat');
  const [meetingFilter, setMeetingFilter] = useState<MeetingFilter>('all');
  const meetingPoints = useMemo(() => {
    const all = map.data?.meetingPoints ?? [];
    return meetingFilter === 'all' ? all : all.filter((p) => p.report === (meetingFilter === 'report'));
  }, [map.data, meetingFilter]);
  return (
    <div className="page page--tight">
      <Async state={map}>
        {(m) => (
          <>
            <div className="page-head">
              <Title text={m.map.map} className="page-title" />
            </div>
            <div className="grid grid--stats">
              <StatCard icon={<Timer className="icon" />} label="Runden" value={m.map.rounds} chip={`im Schnitt ${duration(m.map.avgDuration)}`} />
              <StatCard icon={<ShieldCheck className="icon" />} label="Crew gewinnt" value={0} raw={pct(m.map.crewWins, m.map.rounds)} chip={`${num(m.map.crewWins)} Runden`} />
              <StatCard icon={<Crosshair className="icon" />} label="Impostor gewinnen" value={0} raw={pct(m.map.impostorWins, m.map.rounds)} chip={`${num(m.map.impostorWins)} Runden`} />
              <StatCard icon={<Skull className="icon" />} label="Kills" value={m.map.kills} chip="gesamt" />
              <StatCard icon={<Megaphone className="icon" />} label="Meetings" value={m.map.meetings} chip={`${num(m.meetingPoints.filter((p) => p.report).length)} gemeldete Leichen`} />
            </div>

            <Section
              title="Heatmaps"
              action={
                <Segmented<Layer>
                  label="Ansicht"
                  value={layer}
                  onChange={setLayer}
                  options={[
                    { value: 'heat', label: 'Laufwege' },
                    { value: 'kills', label: 'Kills' },
                    { value: 'meetings', label: 'Meetings' },
                    { value: 'plain', label: 'Räume' }
                  ]}
                />
              }
            >
              {m.cells.length === 0 ? (
                <div className="note">
                  <Info className="icon" aria-hidden="true" />
                  <span>Für diese Map gibt es noch keine Laufwege – die kommen mit Runden ab CrewStats 1.5.0.</span>
                </div>
              ) : (
                <div className="stack">
                  <MapView layout={m} heat={layer === 'heat'} showRooms={layer !== 'heat'} label={`Grundriss von ${m.map.map}`}>
                    {layer === 'kills' && <Density points={m.kills} color={KILL_COLOR} cellSize={m.cellSize} />}
                    {layer === 'kills' &&
                      m.kills.map((k, i) => (
                        <g key={i} className="kill-mark">
                          <line className="kill" x1={k.x - 0.2} y1={-k.y - 0.2} x2={k.x + 0.2} y2={-k.y + 0.2} />
                          <line className="kill" x1={k.x - 0.2} y1={-k.y + 0.2} x2={k.x + 0.2} y2={-k.y - 0.2} />
                        </g>
                      ))}
                    {layer === 'meetings' && <Density points={meetingPoints} color={MEETING_COLOR} cellSize={m.cellSize} />}
                    {layer === 'meetings' &&
                      meetingPoints.map((p, i) =>
                        p.report ? (
                          <circle key={i} className="meeting-mark meeting-mark--report" cx={p.x} cy={-p.y} r={0.2} />
                        ) : (
                          <rect key={i} className="meeting-mark" x={p.x - 0.18} y={-p.y - 0.18} width={0.36} height={0.36} />
                        )
                      )}
                  </MapView>
                  <div className="row">
                    {layer === 'heat' && <HeatLegend />}
                    {layer === 'kills' && (
                      <>
                        <DensityLegend color={KILL_COLOR} />
                        <span className="caption">
                          Je wärmer, desto mehr Kills. Kreuz = ein Kill ({num(m.kills.length)} mit Ort)
                          {m.roomKills[0] && <>, die meisten in {room(m.roomKills[0].room)} ({num(m.roomKills[0].count)})</>}.
                        </span>
                      </>
                    )}
                    {layer === 'meetings' && (
                      <>
                        <Segmented<MeetingFilter>
                          label="Meetings"
                          value={meetingFilter}
                          onChange={setMeetingFilter}
                          options={[
                            { value: 'all', label: 'Alle' },
                            { value: 'report', label: 'Leiche gemeldet' },
                            { value: 'emergency', label: 'Notfall-Knopf' }
                          ]}
                        />
                        <DensityLegend color={MEETING_COLOR} />
                        <span className="caption">
                          Ort, an dem der Auslöser stand. Kreis = Leiche gemeldet, Quadrat = Notfall-Knopf ({num(meetingPoints.length)} mit Ort).
                        </span>
                      </>
                    )}
                    {layer === 'plain' && <span className="caption">Räume aus den Orten von Aufgaben, Kills und Meetings, Rahmen = Vents.</span>}
                  </div>
                </div>
              )}
            </Section>

            <div className="grid grid--3">
              <Section title="Kill-Orte">
                <div className="panel panel--pad">
                  <BarList items={m.roomKills.slice(0, 10).map((r) => ({ label: room(r.room), value: r.count, text: num(r.count), tone: 'ember' as const }))} />
                </div>
              </Section>
              <Section title="Meeting-Orte">
                <div className="panel panel--pad">
                  <BarList items={(m.roomMeetings ?? []).slice(0, 10).map((r) => ({ label: room(r.room), value: r.count, text: num(r.count), tone: 'gold' as const }))} />
                </div>
              </Section>
              <Section title="Sabotagen">
                <div className="panel panel--pad">
                  <BarList
                    items={m.sabotages.map((s) => ({
                      label: <span className="row" style={{ gap: 6 }}><TriangleAlert className="icon icon--sm" style={{ color: 'var(--gold)' }} />{system(s.system)}</span>,
                      value: s.count,
                      text: `${num(s.count)}${s.avgDuration != null ? ` · Ø ${duration(s.avgDuration)}` : ''}`,
                      tone: 'gold' as const
                    }))}
                  />
                </div>
              </Section>
            </div>

            {rounds.data && rounds.data.items.length > 0 && (
              <Section title="Letzte Runden" action={<Link className="btn" to={`/runden?map=${m.map.mapId}`}>Alle {num(rounds.data.total)}</Link>}>
                <RoundList rounds={rounds.data.items} />
              </Section>
            )}
          </>
        )}
      </Async>
    </div>
  );
}
