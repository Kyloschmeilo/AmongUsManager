// Spielfarben von Among Us (ColorId 0–17). Die Farbe zeigt, wer welcher Spieler ist – reine Daten, kein Schmuck.
export const PLAYER_COLORS = [
  '#c51111', // Red
  '#132ed1', // Blue
  '#117f2d', // Green
  '#ed54ba', // Pink
  '#ef7d0d', // Orange
  '#f5f557', // Yellow
  '#3f474e', // Black
  '#d6e0f0', // White
  '#6b2fbb', // Purple
  '#71491e', // Brown
  '#38fedc', // Cyan
  '#50ef39', // Lime
  '#5f1d2e', // Maroon
  '#ecc0d3', // Rose
  '#f0e7a8', // Banana
  '#758593', // Gray
  '#918877', // Tan
  '#d76464' // Coral
];

export const COLOR_NAMES = [
  'Rot', 'Blau', 'Grün', 'Pink', 'Orange', 'Gelb', 'Schwarz', 'Weiß', 'Lila', 'Braun',
  'Cyan', 'Limette', 'Weinrot', 'Rosa', 'Banane', 'Grau', 'Beige', 'Koralle'
];

/** Farbe eines Spielers; ohne bekannte Farbe (alte Runden) eine feste aus dem Schlüssel. */
export function playerColor(colorId: number | null | undefined, key?: string | null): string {
  if (colorId != null && colorId >= 0 && colorId < PLAYER_COLORS.length) return PLAYER_COLORS[colorId];
  if (!key) return '#3f474e';
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) | 0;
  return PLAYER_COLORS[Math.abs(hash) % PLAYER_COLORS.length];
}
