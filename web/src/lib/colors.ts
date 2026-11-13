// Spielfarben von Among Us (ColorId 0–17) und die Zusatzfarben von The Other Roles GMIA (18–41, ab 19 Spielern nötig).
// Die Farbe zeigt, wer welcher Spieler ist – reine Daten, kein Schmuck.
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
  '#d76464', // Coral
  '#efbfc0', // Salmon
  '#6d071a', // Bordeaux
  '#9a8c3d', // Olive
  '#1684b0', // Turquoise
  '#6fc09c', // Mint
  '#ad7ec9', // Lavender
  '#a06538', // Nougat
  '#ffa477', // Peach
  '#708f2e', // Wasabi
  '#ff3366', // Hot Pink
  '#006369', // Petrol
  '#dbfd2f', // Lemon
  '#f74417', // Signal Orange
  '#25b8bf', // Teal
  '#593cd6', // Blurple
  '#ffca19', // Sunrise
  '#a8dfff', // Ice
  '#a41181', // Fuchsia
  '#095221', // Royal Green
  '#f4ffbc', // Slime
  '#092b77', // Navy
  '#242728', // Darkness
  '#379fda', // Ocean
  '#fcc264' // Sundown
];

export const COLOR_NAMES = [
  'Rot', 'Blau', 'Grün', 'Pink', 'Orange', 'Gelb', 'Schwarz', 'Weiß', 'Lila', 'Braun',
  'Cyan', 'Limette', 'Weinrot', 'Rosa', 'Banane', 'Grau', 'Beige', 'Koralle',
  // The Other Roles
  'Lachs', 'Bordeaux', 'Oliv', 'Türkis', 'Mint', 'Lavendel', 'Nougat', 'Pfirsich', 'Wasabi', 'Hot Pink', 'Petrol', 'Zitrone',
  'Signalorange', 'Blaugrün', 'Blurple', 'Sonnenaufgang', 'Eis', 'Fuchsie', 'Königsgrün', 'Schleim', 'Marine', 'Dunkel', 'Ozean', 'Abendrot'
];

/** Farben des Spiels selbst – Ersatzfarben für Spieler ohne Farbdaten kommen nur hieraus. */
const BASE_COLORS = 18;

/** Farbe eines Spielers; ohne bekannte Farbe (alte Runden) eine feste aus dem Schlüssel. */
export function playerColor(colorId: number | null | undefined, key?: string | null): string {
  if (colorId != null && colorId >= 0 && colorId < PLAYER_COLORS.length) return PLAYER_COLORS[colorId];
  if (!key) return '#3f474e';
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) | 0;
  return PLAYER_COLORS[Math.abs(hash) % BASE_COLORS];
}
