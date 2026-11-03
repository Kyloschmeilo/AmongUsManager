// Map als Block-Grafik: jede Rasterzelle, in der je jemand gelaufen ist, wird ein Quadrat. So entsteht der Grundriss
// aus den echten Laufwegen (keine Bilder aus dem Spiel). Optional als Heatmap mit 4 Stufen, darüber Räume, Vents, Kills.
import { memo, useMemo, type ReactNode } from 'react';
import type { MapDetail } from '../lib/api';
import { room as roomLabel } from '../lib/labels';

export interface Layout {
  cellSize: number;
  cells: number[]; // [cx, cy, heat, …]
  rooms: MapDetail['rooms'];
  vents: MapDetail['vents'];
}

export interface Bounds {
  minX: number;
  minY: number;
  width: number;
  height: number;
}

/** Ausschnitt in SVG-Koordinaten (y nach unten, also Spiel-y gespiegelt). */
export function boundsOf(layout: Layout, extra: [number, number][] = []): Bounds {
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  const s = layout.cellSize;
  for (let i = 0; i + 2 < layout.cells.length; i += 3) {
    const x = layout.cells[i] * s;
    const y = -(layout.cells[i + 1] + 1) * s;
    if (x < minX) minX = x;
    if (x + s > maxX) maxX = x + s;
    if (y < minY) minY = y;
    if (y + s > maxY) maxY = y + s;
  }
  for (const [x, y] of extra) {
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (-y < minY) minY = -y;
    if (-y > maxY) maxY = -y;
  }
  if (!isFinite(minX)) return { minX: -10, minY: -10, width: 20, height: 20 };
  const pad = 1.5;
  return { minX: minX - pad, minY: minY - pad, width: maxX - minX + 2 * pad, height: maxY - minY + 2 * pad };
}

/** Raster aus Laufwegen einer einzelnen Runde (wenn es noch keinen Grundriss aus allen Runden gibt). */
export function layoutFromTracks(tracks: { points: number[] }[], cellSize = 0.5): Layout {
  const counts = new Map<string, number>();
  for (const track of tracks) {
    for (let i = 0; i + 2 < track.points.length; i += 3) {
      const key = `${Math.floor(track.points[i + 1] / cellSize)},${Math.floor(track.points[i + 2] / cellSize)}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
  }
  const cells: number[] = [];
  counts.forEach((count, key) => {
    const [cx, cy] = key.split(',').map(Number);
    cells.push(cx, cy, count);
  });
  return { cellSize, cells, rooms: [], vents: [] };
}

const HEAT = ['var(--surface-2)', 'var(--accent-soft)', 'var(--moss)', 'var(--accent)'];

/** Stufe 0–3 einer Zelle (logarithmisch, damit auch wenig begangene Stellen sichtbar bleiben). */
function heatLevels(cells: number[]): number[] {
  let max = 1;
  for (let i = 2; i < cells.length; i += 3) max = Math.max(max, cells[i]);
  const logMax = Math.log(max + 1);
  const levels: number[] = [];
  for (let i = 2; i < cells.length; i += 3) {
    const v = Math.log(cells[i] + 1) / logMax;
    levels.push(v > 0.82 ? 3 : v > 0.6 ? 2 : v > 0.35 ? 1 : 0);
  }
  return levels;
}

const Cells = memo(function Cells({ layout, heat }: { layout: Layout; heat: boolean }) {
  const s = layout.cellSize;
  const levels = useMemo(() => (heat ? heatLevels(layout.cells) : null), [layout, heat]);
  const rects: ReactNode[] = [];
  for (let i = 0, n = 0; i + 2 < layout.cells.length; i += 3, n++) {
    const x = layout.cells[i] * s;
    const y = -(layout.cells[i + 1] + 1) * s;
    rects.push(
      <rect
        key={n}
        x={x + s * 0.06}
        y={y + s * 0.06}
        width={s * 0.88}
        height={s * 0.88}
        rx={s * 0.12}
        fill={levels ? HEAT[levels[n]] : 'var(--surface-2)'}
      />
    );
  }
  return <g>{rects}</g>;
});

/**
 * Dichte-Heatmap aus einzelnen Orten (Kills, Meetings): jeder Ort verteilt sich weich (Gauß, σ ≈ 1 Spieleinheit)
 * auf ein Raster, das stärkste Feld ist voll gefärbt. So werden Brennpunkte sichtbar, auch wenn Kreuze sich stapeln.
 */
export const Density = memo(function Density({ points, color, cellSize = 0.5 }: { points: { x: number; y: number }[]; color: string; cellSize?: number }) {
  const cells = useMemo(() => {
    const sigma = 1.0;
    const reach = Math.ceil((2.5 * sigma) / cellSize);
    const grid = new Map<number, number>();
    const pack = (cx: number, cy: number) => (cx + 2048) * 4096 + (cy + 2048);
    for (const p of points) {
      const px = Math.floor(p.x / cellSize);
      const py = Math.floor(p.y / cellSize);
      for (let dx = -reach; dx <= reach; dx++) {
        for (let dy = -reach; dy <= reach; dy++) {
          const ox = (px + dx + 0.5) * cellSize - p.x;
          const oy = (py + dy + 0.5) * cellSize - p.y;
          const w = Math.exp(-(ox * ox + oy * oy) / (2 * sigma * sigma));
          if (w < 0.02) continue;
          const key = pack(px + dx, py + dy);
          grid.set(key, (grid.get(key) ?? 0) + w);
        }
      }
    }
    let max = 0;
    grid.forEach((v) => (max = Math.max(max, v)));
    const out: { x: number; y: number; v: number }[] = [];
    grid.forEach((v, key) => {
      const share = v / max;
      if (share < 0.05) return;
      out.push({ x: Math.floor(key / 4096) - 2048, y: (key % 4096) - 2048, v: share });
    });
    return out;
  }, [points, cellSize]);
  return (
    <g className="density" aria-hidden="true">
      {cells.map((c) => (
        <rect
          key={`${c.x},${c.y}`}
          x={c.x * cellSize}
          y={-(c.y + 1) * cellSize}
          width={cellSize}
          height={cellSize}
          fill={color}
          fillOpacity={0.12 + 0.83 * Math.pow(c.v, 0.75)}
        />
      ))}
    </g>
  );
});

export function DensityLegend({ color }: { color: string }) {
  return (
    <span className="heat-legend caption">
      wenige
      {[0.2, 0.45, 0.7, 0.95].map((o) => (
        <i key={o} style={{ background: color, opacity: o }} />
      ))}
      viele
    </span>
  );
}

export function MapView({ layout, bounds, heat = false, showRooms = true, showVents = true, children, label }: { layout: Layout; bounds?: Bounds; heat?: boolean; showRooms?: boolean; showVents?: boolean; children?: ReactNode; label: string }) {
  const box = bounds ?? boundsOf(layout);
  const unit = Math.max(box.width, box.height) / 60; // Schriftgröße passend zur Map
  return (
    <div className="mapview">
      <svg viewBox={`${box.minX} ${box.minY} ${box.width} ${box.height}`} role="img" aria-label={label} preserveAspectRatio="xMidYMid meet">
        <Cells layout={layout} heat={heat} />
        {showVents &&
          layout.vents.map((vent) => (
            <rect key={vent.id} className="vent" x={vent.x - 0.35} y={-vent.y - 0.25} width={0.7} height={0.5} rx={0.08} />
          ))}
        {showRooms &&
          layout.rooms.map((r) => (
            <text key={r.room} className="room-label" x={r.x} y={-r.y} textAnchor="middle" fontSize={0.62 * unit * 1.4}>
              {roomLabel(r.room)}
            </text>
          ))}
        {children}
      </svg>
    </div>
  );
}

export function HeatLegend() {
  return (
    <span className="heat-legend caption">
      selten
      {HEAT.map((color, i) => (
        <i key={i} style={{ background: color }} />
      ))}
      oft
    </span>
  );
}
