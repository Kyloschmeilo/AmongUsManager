import { useMemo, useRef, type PointerEvent } from 'react';
import { Link } from 'react-router-dom';
import type { PlayerSummary } from '../lib/api';
import { playerColor } from '../lib/colors';
import { num } from '../lib/format';

/** Crewmate als SVG: Körper, Rucksack, Visier – in der Spielfarbe. */
export function CrewmateSvg({ color, className }: { color: string; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 44 50" aria-hidden="true">
      <rect x="2" y="17" width="10" height="19" rx="3.5" fill={color} stroke="#0b0d12" strokeWidth="2.2" />
      <rect x="2" y="17" width="10" height="19" rx="3.5" fill="#000" opacity=".22" />
      <path
        d="M9 18C9 8 15.5 3 23 3s14 5 14 15v24c0 3-1.8 5-4.2 5h-4.6c-2.3 0-3.7-1.6-3.7-4v-4h-5v4c0 2.4-1.4 4-3.7 4h-2.6C10.8 47 9 45 9 42Z"
        fill={color}
        stroke="#0b0d12"
        strokeWidth="2.2"
        strokeLinejoin="round"
      />
      <path d="M33 22c2 0 4-1 4-3v23c0 3-1.8 5-4.2 5h-1Z" fill="#000" opacity=".2" />
      <rect x="21" y="11" width="20" height="11.5" rx="5.75" fill="#9ccfe2" stroke="#0b0d12" strokeWidth="2.2" />
      <rect x="29" y="13.3" width="8" height="3.4" rx="1.7" fill="#fff" opacity=".75" />
    </svg>
  );
}

/** Pseudo-Zufall aus dem Spieler-Schlüssel, damit jeder Crewmate bei jedem Besuch gleich schwebt. */
function seed(key: string, salt: number): number {
  let h = 2166136261 ^ salt;
  for (let i = 0; i < key.length; i++) h = Math.imul(h ^ key.charCodeAt(i), 16777619);
  return ((h >>> 0) % 10000) / 10000;
}

/**
 * Band mit allen Spielern als Crewmates, die durchs All treiben (wie nach dem Rauswerfen).
 * Maus drüber hält einen an und zeigt Name + Siege, Klick öffnet das Profil. Der Sternenhimmel
 * verschiebt sich leicht mit dem Zeiger.
 */
export function CrewDrift({ players }: { players: PlayerSummary[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const crew = useMemo(
    () =>
      players.slice(0, 18).map((p, i, all) => {
        const r1 = seed(p.key, 1);
        const r2 = seed(p.key, 2);
        const r3 = seed(p.key, 3);
        return {
          p,
          top: 5 + ((i * 37) % 58) + r1 * 8,
          size: 30 + r2 * 22,
          speed: 38 + r3 * 34,
          delay: -((i / all.length) * (38 + r3 * 34)) - r1 * 6,
          spin: 14 + r1 * 22,
          dir: r2 > 0.5 ? 'normal' : 'reverse',
          bob: 4 + r3 * 7,
          x: 3 + (i / all.length) * 90
        };
      }),
    [players]
  );
  const move = (e: PointerEvent<HTMLDivElement>) => {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty('--px', `${((e.clientX - r.left) / r.width - 0.5).toFixed(3)}`);
    el.style.setProperty('--py', `${((e.clientY - r.top) / r.height - 0.5).toFixed(3)}`);
  };
  if (!crew.length) return null;
  return (
    <div className="drift" ref={ref} onPointerMove={move}>
      <div className="drift__stars" aria-hidden="true" />
      <div className="drift__stars drift__stars--near" aria-hidden="true" />
      <div className="drift__label">
        <b>{num(players.length)}</b> an Bord
        <span>Fahr über einen Crewmate</span>
      </div>
      {crew.map(({ p, top, size, speed, delay, spin, dir, bob, x }) => (
        <Link
          key={p.key}
          to={`/spieler/${p.key}`}
          className="drift__mate"
          style={{
            top: `${top}%`,
            ['--x' as string]: `${x}%`,
            ['--size' as string]: `${size}px`,
            ['--speed' as string]: `${speed}s`,
            ['--delay' as string]: `${delay}s`,
            ['--spin' as string]: `${spin}s`,
            ['--bob' as string]: `${bob}px`,
            ['--dir' as string]: dir
          }}
          aria-label={`${p.name}: ${p.wins} Siege`}
        >
          <span className="drift__body">
            <CrewmateSvg color={playerColor(p.colorId, p.key)} />
          </span>
          <span className="drift__tag">
            {p.name}
            <small>{num(p.wins)} Siege · {num(p.games)} Runden</small>
          </span>
        </Link>
      ))}
    </div>
  );
}
