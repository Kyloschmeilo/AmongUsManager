// Zahlen, Zeiten und Daten im deutschen Format (Punkt als Tausender-Trenner, 12m 30s).

const integer = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const dateFormat = new Intl.DateTimeFormat('de-DE', { timeZone: 'Europe/Berlin', day: '2-digit', month: '2-digit', year: 'numeric' });
const shortDate = new Intl.DateTimeFormat('de-DE', { timeZone: 'Europe/Berlin', day: '2-digit', month: '2-digit' });
const timeFormat = new Intl.DateTimeFormat('de-DE', { timeZone: 'Europe/Berlin', hour: '2-digit', minute: '2-digit' });
const weekday = new Intl.DateTimeFormat('de-DE', { timeZone: 'Europe/Berlin', weekday: 'short' });

export const num = (value: number | null | undefined): string => (value == null ? '–' : integer.format(value));

export const dec = (value: number | null | undefined): string => (value == null ? '–' : oneDecimal.format(value));

/** 0.5432 -> "54 %" */
export const pct = (part: number, total: number): string => (total > 0 ? `${integer.format((part / total) * 100)} %` : '–');

export const ratio = (part: number, total: number): number => (total > 0 ? part / total : 0);

/** Sekunden -> "4m 05s" bzw. "1h 12m" bzw. "3d 4h 10m". */
export function duration(seconds: number | null | undefined): string {
  if (seconds == null || !isFinite(seconds)) return '–';
  const s = Math.max(0, Math.round(seconds));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
  if (m > 0) return `${m}m ${String(sec).padStart(2, '0')}s`;
  return `${sec}s`;
}

/** Spielzeit im Replay: "03:27". */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

export const date = (iso: string | null | undefined): string => (iso ? dateFormat.format(new Date(iso)) : '–');
export const dayMonth = (iso: string): string => shortDate.format(new Date(iso));
export const time = (iso: string): string => timeFormat.format(new Date(iso));
export const day = (iso: string): string => weekday.format(new Date(iso));

/** Spielabend-Datum "2026-10-24" -> "24.10." */
export function nightLabel(isoDate: string): string {
  const [, m, d] = isoDate.split('-');
  return `${d}.${m}.`;
}

/** "vor 3 Tagen" usw. */
export function ago(iso: string | null | undefined): string {
  if (!iso) return '–';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 90) return 'gerade eben';
  if (diff < 3600) return `vor ${Math.round(diff / 60)} Minuten`;
  if (diff < 86400 * 1.5) return `vor ${Math.round(diff / 3600)} Stunden`;
  if (diff < 86400 * 60) return `vor ${Math.round(diff / 86400)} Tagen`;
  return `am ${date(iso)}`;
}

/** Spiel-Einheiten -> "1.234 m" (eine Einheit ≈ ein Meter, so zeigt es auch das Spiel nirgends – reine Vergleichsgröße). */
export const distance = (units: number): string => `${integer.format(units)} m`;
