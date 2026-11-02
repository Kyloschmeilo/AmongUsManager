// Replay einer Runde: Laufwege (1 Punkt/s) als GSAP-Timeline. Spieler-Punkte werden zwischen den Punkten
// interpoliert, Leichen liegen bis zum nächsten Meeting, Kills blitzen kurz auf. Scrubber mit Markierungen
// für Kills, Meetings und Sabotagen, daneben die Zeitleiste – ein Klick springt zum Ereignis.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { gsap } from 'gsap';
import { Pause, Play, RotateCcw, Siren, TriangleAlert } from 'lucide-react';
import type { RoundDetail } from '../lib/api';
import { playerColor } from '../lib/colors';
import { clock } from '../lib/format';
import { system } from '../lib/labels';
import { MapView, boundsOf, layoutFromTracks, type Layout } from '../components/MapView';
import { Avatar, Segmented } from '../components/ui';
import { reducedMotion } from '../components/motion';
import { IMPORTANT, describe, nameLookup, timelineOf } from './events';

type Speed = '1' | '2' | '5' | '10' | '20';

/** Position eines Spielers zur Zeit t aus [t, x, y, …] – null, wenn er gerade nicht unterwegs ist (Meeting, tot). */
function positionAt(points: number[], t: number): [number, number] | null {
  const n = points.length / 3;
  if (n === 0 || t < points[0] - 0.5 || t > points[(n - 1) * 3] + 1.2) return null;
  let lo = 0;
  let hi = n - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (points[mid * 3] <= t) lo = mid;
    else hi = mid - 1;
  }
  const i = lo * 3;
  const t0 = points[i];
  if (lo === n - 1 || t < t0) return [points[i + 1], points[i + 2]];
  const t1 = points[i + 3];
  const gap = t1 - t0;
  if (gap > 1.6) return t - t0 < 1.2 ? [points[i + 1], points[i + 2]] : null; // Lücke = Meeting oder Vent
  const f = (t - t0) / gap;
  const dx = points[i + 4] - points[i + 1];
  const dy = points[i + 5] - points[i + 2];
  if (Math.hypot(dx, dy) > 8 * gap) return f < 0.5 ? [points[i + 1], points[i + 2]] : [points[i + 4], points[i + 5]]; // Sprung (Vent, Leiter)
  return [points[i + 1] + dx * f, points[i + 2] + dy * f];
}

export function Replay({ round, layout }: { round: RoundDetail; layout: Layout | null }) {
  const tracks = useMemo(() => round.tracks ?? [], [round]);
  const total = Math.max(1, round.durationSeconds);
  const name = useMemo(() => nameLookup(round), [round]);
  const players = round.players;
  const series = useMemo(() => new Map(tracks.map((t) => [t.player, t.points])), [tracks]);

  const meetings = useMemo(
    () => round.meetings.map((m) => ({ ...m, end: m.endedAtSeconds ?? m.atSeconds + 25 })),
    [round]
  );
  const bodies = useMemo(
    () =>
      round.kills.map((kill) => {
        let x = kill.x ?? null;
        let y = kill.y ?? null;
        if (x == null || y == null) {
          const pos = positionAt(series.get(kill.victim) ?? [], kill.atSeconds);
          if (pos) [x, y] = pos;
        }
        const nextMeeting = meetings.find((m) => m.atSeconds > kill.atSeconds);
        return { ...kill, x, y, until: nextMeeting ? nextMeeting.atSeconds : Infinity };
      }),
    [round, series, meetings]
  );
  const sabotages = useMemo(() => {
    const result: { system: string; from: number; to: number }[] = [];
    const open = new Map<string, number>();
    for (const e of round.events ?? []) {
      if (e.type === 'sabotage' && e.system) open.set(e.system, e.atSeconds);
      if (e.type === 'sabotageEnd' && e.system && open.has(e.system)) {
        result.push({ system: e.system, from: open.get(e.system)!, to: e.atSeconds });
        open.delete(e.system);
      }
    }
    open.forEach((from, s) => result.push({ system: s, from, to: total }));
    return result;
  }, [round, total]);

  const mapLayout = useMemo(() => (layout && layout.cells.length ? layout : layoutFromTracks(tracks)), [layout, tracks]);
  const bounds = useMemo(() => {
    const extra: [number, number][] = [];
    for (const b of bodies) if (b.x != null && b.y != null) extra.push([b.x, b.y]);
    return boundsOf(mapLayout, extra);
  }, [mapLayout, bodies]);
  const unit = Math.max(bounds.width, bounds.height) / 60;

  const timeline = useMemo(() => timelineOf(round), [round]);
  // Wenige wichtige Ereignisse (kurze Runde) → gleich alles zeigen, sonst bleibt die Zeitleiste fast leer
  const [showAll, setShowAll] = useState(() => timeline.filter((e) => IMPORTANT.has(e.type)).length < 8);
  const [showRoles, setShowRoles] = useState(true);
  const feed = useMemo(() => timeline.filter((e) => showAll || IMPORTANT.has(e.type)), [timeline, showAll]);

  const [now, setNow] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState<Speed>('5');
  const state = useRef({ t: 0 });
  const tl = useRef<gsap.core.Timeline | null>(null);
  const dots = useRef(new Map<number, SVGGElement>());
  const bodyEls = useRef<(SVGGElement | null)[]>([]);
  const flashLayer = useRef<SVGGElement>(null);
  const progress = useRef<HTMLDivElement>(null);
  const range = useRef<HTMLInputElement>(null);
  const feedList = useRef<HTMLOListElement>(null);
  const lastNow = useRef(-1);

  const render = useCallback(
    (t: number) => {
      dots.current.forEach((el, id) => {
        const pos = positionAt(series.get(id) ?? [], t);
        if (pos) {
          el.setAttribute('transform', `translate(${pos[0].toFixed(3)} ${(-pos[1]).toFixed(3)})`);
          el.style.display = '';
        } else {
          el.style.display = 'none';
        }
      });
      bodies.forEach((b, i) => {
        const el = bodyEls.current[i];
        if (el) el.style.display = t >= b.atSeconds && t < b.until ? '' : 'none';
      });
      if (progress.current) progress.current.style.width = `${(t / total) * 100}%`;
      if (range.current && document.activeElement !== range.current) range.current.value = String(t);
      const q = Math.floor(t * 4) / 4;
      if (q !== lastNow.current) {
        lastNow.current = q;
        setNow(q);
      }
    },
    [series, bodies, total]
  );

  const flash = useCallback(
    (x: number | null, y: number | null) => {
      const layer = flashLayer.current;
      if (!layer || x == null || y == null || reducedMotion()) return;
      const ring = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      ring.setAttribute('cx', String(x));
      ring.setAttribute('cy', String(-y));
      ring.setAttribute('r', String(0.3 * unit));
      ring.setAttribute('fill', 'none');
      ring.setAttribute('stroke', 'var(--ember)');
      ring.setAttribute('stroke-width', String(0.16 * unit));
      layer.appendChild(ring);
      gsap.fromTo(ring, { attr: { r: 0.3 * unit }, opacity: 1 }, { attr: { r: 2.6 * unit }, opacity: 0, duration: 0.9, ease: 'power2.out', onComplete: () => ring.remove() });
    },
    [unit]
  );

  useEffect(() => {
    const t = gsap.timeline({ paused: true, onUpdate: () => render(state.current.t), onComplete: () => setPlaying(false) });
    t.to(state.current, { t: total, duration: total, ease: 'none' }, 0);
    bodies.forEach((b) => t.call(() => flash(b.x, b.y), [], b.atSeconds));
    tl.current = t;
    state.current.t = 0;
    render(0);
    return () => {
      t.kill();
    };
  }, [total, bodies, render, flash]);

  useEffect(() => {
    tl.current?.timeScale(Number(speed));
  }, [speed]);

  const toggle = () => {
    const t = tl.current;
    if (!t) return;
    if (t.isActive()) {
      t.pause();
      setPlaying(false);
    } else {
      if (t.progress() >= 1) t.seek(0);
      t.timeScale(Number(speed)).play();
      setPlaying(true);
    }
  };

  const seek = (seconds: number) => {
    const t = tl.current;
    if (!t) return;
    t.seek(Math.max(0, Math.min(total, seconds)), true);
    state.current.t = Math.max(0, Math.min(total, seconds));
    render(state.current.t);
  };

  // Zeitleiste mitlaufen lassen (nur innerhalb der Liste scrollen, nicht die Seite)
  const currentIndex = useMemo(() => {
    let index = -1;
    feed.forEach((e, i) => {
      if (e.atSeconds <= now) index = i;
    });
    return index;
  }, [feed, now]);
  useEffect(() => {
    const list = feedList.current;
    if (!list || currentIndex < 0 || !playing) return;
    const item = list.children[currentIndex] as HTMLElement | undefined;
    if (!item) return;
    const top = item.offsetTop - list.offsetTop;
    if (top < list.scrollTop || top > list.scrollTop + list.clientHeight - item.offsetHeight) {
      list.scrollTop = top - list.clientHeight / 2;
    }
  }, [currentIndex, playing]);

  const meeting = meetings.find((m) => now >= m.atSeconds && now < m.end + 4);
  const activeSabotages = sabotages.filter((s) => now >= s.from && now < s.to);
  const deadAt = (id: number) => {
    const p = players.find((x) => x.playerId === id);
    return p?.diedAtSeconds ?? (p?.isDead ? Infinity : null);
  };

  return (
    <div className="replay">
      <div className="panel panel--pad replay__stage">
        <div style={{ position: 'relative' }}>
          <MapView layout={mapLayout} bounds={bounds} label={`Replay auf ${round.map}`}>
            {bodies.map((b, i) =>
              b.x == null || b.y == null ? null : (
                <g key={i} ref={(el) => { bodyEls.current[i] = el; }} style={{ display: 'none' }}>
                  <line className="body" x1={b.x - 0.35 * unit} y1={-b.y - 0.35 * unit} x2={b.x + 0.35 * unit} y2={-b.y + 0.35 * unit} strokeWidth={0.2 * unit} />
                  <line className="body" x1={b.x - 0.35 * unit} y1={-b.y + 0.35 * unit} x2={b.x + 0.35 * unit} y2={-b.y - 0.35 * unit} strokeWidth={0.2 * unit} />
                </g>
              )
            )}
            <g ref={flashLayer} />
            {players.map((p) =>
              series.has(p.playerId) ? (
                <g key={p.playerId} ref={(el) => { if (el) dots.current.set(p.playerId, el); else dots.current.delete(p.playerId); }} style={{ display: 'none' }}>
                  <circle
                    r={0.42 * unit}
                    fill={playerColor(p.colorId, p.key)}
                    stroke={showRoles && p.team === 'IMPOSTOR' ? 'var(--ember)' : showRoles && p.team === 'NEUTRAL' ? 'var(--neutral)' : 'var(--surface-0)'}
                    strokeWidth={0.14 * unit}
                  />
                  <text className="dot-name" y={-0.75 * unit} textAnchor="middle" fontSize={0.62 * unit} strokeWidth={0.18 * unit}>
                    {p.name}
                  </text>
                </g>
              ) : null
            )}
          </MapView>
          <div className="replay__overlay">
            {activeSabotages.map((s) => (
              <span key={s.system + s.from} className="pill pill--gold">
                <TriangleAlert className="icon icon--sm" aria-hidden="true" />
                {system(s.system)}
              </span>
            ))}
          </div>
          {meeting && (
            <div className="replay__banner" role="status">
              <Siren className="icon" style={{ color: 'var(--accent)' }} aria-hidden="true" />
              <span>
                {meeting.body != null ? (
                  <><b>{name(meeting.caller)}</b> meldet die Leiche von <b>{name(meeting.body)}</b></>
                ) : (
                  <><b>{name(meeting.caller)}</b> ruft ein Notfall-Meeting</>
                )}
                {now >= meeting.end && (
                  <span className="muted"> · {meeting.exiled != null ? <><b>{name(meeting.exiled)}</b> fliegt raus</> : meeting.tie ? 'Gleichstand, niemand fliegt raus' : 'niemand fliegt raus'}</span>
                )}
              </span>
            </div>
          )}
        </div>

        <div className="replay__controls">
          <button type="button" className="btn btn--primary btn--icon" onClick={toggle} aria-label={playing ? 'Pause' : 'Abspielen'}>
            {playing ? <Pause className="icon" /> : <Play className="icon" />}
          </button>
          <button type="button" className="btn btn--icon" onClick={() => seek(0)} aria-label="Zum Anfang">
            <RotateCcw className="icon" />
          </button>
          <span className="replay__time">
            {clock(now)} / {clock(total)}
          </span>
          <div className="scrub">
            <div className="scrub__track">
              {meetings.map((m, i) => (
                <div key={i} className="scrub__meeting" style={{ left: `${(m.atSeconds / total) * 100}%`, width: `${Math.max(0.4, ((m.end - m.atSeconds) / total) * 100)}%` }} />
              ))}
              <div className="scrub__progress" ref={progress} />
            </div>
            <div className="scrub__marks" aria-hidden="true">
              {bodies.map((b, i) => (
                <span key={`k${i}`} className="scrub__mark scrub__mark--kill" style={{ left: `${(b.atSeconds / total) * 100}%` }} />
              ))}
              {sabotages.map((s, i) => (
                <span key={`s${i}`} className="scrub__mark scrub__mark--sabotage" style={{ left: `${(s.from / total) * 100}%` }} />
              ))}
            </div>
            <input
              ref={range}
              type="range"
              min={0}
              max={total}
              step={0.1}
              defaultValue={0}
              aria-label="Zeit"
              onInput={(e) => {
                tl.current?.pause();
                setPlaying(false);
                seek(Number((e.target as HTMLInputElement).value));
              }}
            />
          </div>
          <Segmented<Speed>
            label="Geschwindigkeit"
            value={speed}
            onChange={setSpeed}
            options={(['1', '2', '5', '10', '20'] as Speed[]).map((v) => ({ value: v, label: `${v}×` }))}
          />
        </div>

        <div className="row" style={{ marginTop: 16, gap: 8 }}>
          {players.map((p) => {
            const died = deadAt(p.playerId);
            const dead = died != null && now >= died;
            return (
              <span key={p.playerId} className="row" style={{ gap: 6, fontSize: 13, color: dead ? 'var(--ink-faint)' : 'var(--ink)', textDecoration: dead ? 'line-through' : undefined }}>
                <Avatar colorId={p.colorId} playerKey={p.key} size="sm" dead={dead} />
                {p.name}
              </span>
            );
          })}
        </div>
        <div className="row" style={{ marginTop: 16 }}>
          <span className="legend">
            <span><i style={{ background: 'var(--ember)' }} />Kill</span>
            <span><i style={{ background: 'var(--accent)' }} />Meeting</span>
            <span><i style={{ background: 'var(--gold)' }} />Sabotage</span>
          </span>
          <span className="spacer" />
          <label className="row caption" style={{ gap: 6, cursor: 'pointer' }}>
            <input type="checkbox" checked={showRoles} onChange={(e) => setShowRoles(e.target.checked)} />
            Impostor markieren
          </label>
        </div>
      </div>

      <div className="panel feed">
        <div className="feed__inner">
          <div className="row" style={{ padding: '16px 16px 12px' }}>
            <span className="panel__title" style={{ margin: 0 }}>Zeitleiste</span>
            <span className="spacer" />
            <label className="row caption" style={{ gap: 6, cursor: 'pointer' }}>
              <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} />
              Alles
            </label>
          </div>
          <ol className="feed__list" ref={feedList} data-lenis-prevent>
            {feed.map((e, i) => (
              <li
                key={i}
                className={`feed__item${e.atSeconds > now ? ' future' : ''}${i === currentIndex ? ' now' : ''}`}
                onClick={() => {
                  tl.current?.pause();
                  setPlaying(false);
                  seek(e.atSeconds);
                }}
              >
                <span className="feed__time">{clock(e.atSeconds)}</span>
                <span>{describe(e, name)}</span>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </div>
  );
}
