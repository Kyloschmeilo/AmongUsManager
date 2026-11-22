// Ende des Einflugs der Startseite: Abschnitte, die ihre Daten selbst laden (Community), springen erst danach herein.
let resolveEntered: () => void = () => {};

/** Erfüllt, sobald der Einflug der Startseite vorbei ist (oder bei „weniger Bewegung“ sofort nach dem ersten Anzeigen). */
export const whenEntered = new Promise<void>((resolve) => { resolveEntered = resolve; });

export function markEntered() { resolveEntered(); }
