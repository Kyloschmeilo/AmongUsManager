// Bilder des Rollen-Wikis: die Fähigkeiten-Knöpfe aus The Other Roles GMIA (GPL-3.0, web/public/role-icons/, Zuordnung in
// web/RoleArt.kt → /api/lexicon „images“). Rollen ohne eigenen Knopf bekommen den leeren Knopf mit ihren Anfangsbuchstaben.
import type { LexiconRole, LexiconTeam } from './guides';

export interface RoleImage {
  file: string;
  label: string;
}

export const roleImageUrl = (file: string): string => `${import.meta.env.BASE_URL}role-icons/${file}.webp`;

/** „Security Guard“ → „SG“, „Mayor“ → „MA“ */
export function roleInitials(name: string): string {
  const words = name.replace(/[^A-Za-zÄÖÜäöüß ]/g, ' ').split(/\s+/).filter(Boolean);
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase();
  return (words[0] ?? '?').slice(0, 2).toUpperCase();
}

export const TEAM_TONE: Record<LexiconTeam, string> = {
  CREW: 'var(--accent)',
  IMPOSTOR: 'var(--ember)',
  NEUTRAL: 'var(--neutral)',
  BOTH: 'var(--sky)',
  MODIFIER: 'var(--gold)'
};

export function RoleIcon({ role, size = 'md', image }: { role: Pick<LexiconRole, 'name' | 'team'> & { images?: RoleImage[] }; size?: 'sm' | 'md' | 'lg'; image?: RoleImage }) {
  const art = image ?? role.images?.[0];
  return (
    <span className={`role-icon role-icon--${size}${art ? '' : ' role-icon--blank'}`} style={{ ['--tone' as string]: TEAM_TONE[role.team] }} aria-hidden="true">
      <img src={roleImageUrl(art ? art.file : 'blank')} alt="" loading="lazy" decoding="async" draggable={false} />
      {!art && <b>{roleInitials(role.name)}</b>}
    </span>
  );
}

/** Adresse der Wiki-Seite einer Rolle. */
export const rolePath = (role: { slug?: string; id: string }): string => `/rollen/${role.slug ?? role.id}`;
