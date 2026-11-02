// Hilfe & Mods: Typen von /api/tutorials, /api/lexicon, /api/downloads (web/Guides.kt, web/Downloads.kt) und ein kleiner,
// sicherer Text-Renderer für die Tutorial-Texte aus dem Bot (**fett**, `code`, [Link](https://…), /befehl).
import { Fragment, type ReactNode } from 'react';

export interface TutorialSection {
  title: string;
  lines: string[];
}

export interface TutorialData {
  key: string;
  label: string;
  title: string;
  intro: string;
  footer: string;
  sections: TutorialSection[];
}

export type LexiconTeam = 'CREW' | 'IMPOSTOR' | 'NEUTRAL' | 'BOTH' | 'MODIFIER';

export interface LexiconRole {
  id: string;
  name: string;
  team: LexiconTeam;
  source: 'game' | 'tor';
  ghost: boolean;
  description: string;
  /** Namen im Spiel, wenn die Rolle mehrere Varianten hat (Nice/Evil Guesser …). */
  variants: string[];
  /** Ids in /api/roles (Rollen) bzw. "tor:" + id (Modifikatoren). */
  statIds: string[];
}

export interface DownloadFile {
  name: string;
  label: string;
  url: string;
  size: number | null;
  /** Datei liegt auf GitHub (nicht auf dieser Website). */
  external: boolean;
  primary: boolean;
}

export interface DownloadItem {
  id: string;
  title: string;
  game: string | null;
  audience: string | null;
  summary: string;
  version: string | null;
  versionNote: string | null;
  publishedAt: string | null;
  files: DownloadFile[];
  missing: string | null;
  steps: string[];
  points: string[];
  notes: string | null;
  links: { label: string; to?: string; href?: string }[];
}

export const LEXICON_TEAM_LABEL: Record<LexiconTeam, string> = {
  CREW: 'Crew',
  IMPOSTOR: 'Impostor',
  NEUTRAL: 'Neutral',
  BOTH: 'Crew oder Impostor',
  MODIFIER: 'Modifikator'
};

export const lexiconPillClass = (team: LexiconTeam): string =>
  team === 'CREW'
    ? 'pill'
    : team === 'IMPOSTOR'
      ? 'pill pill--ember'
      : team === 'NEUTRAL'
        ? 'pill pill--neutral'
        : team === 'BOTH'
          ? 'pill pill--sky'
          : 'pill pill--plain';

/** 11392 -> "11 KB", 1048576 -> "1,0 MB" */
export function fileSize(bytes: number | null | undefined): string | null {
  if (bytes == null || bytes <= 0) return null;
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} MB`;
}

/** Nummerierter Schritt ("1. Lade …") → [Nummer, Text]. */
export function stepOf(line: string): [string, string] | null {
  const match = /^(\d+)\.\s+(.*)$/s.exec(line);
  return match ? [match[1], match[2]] : null;
}

// **fett** | `code` | [Text](https://…) | /befehl (nicht mitten in Wörtern oder Pfaden)
const TOKEN = /\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)|(?<![\w<\/:.\\])\/([a-z0-9-]+)/g;

/** Text mit dem bisschen Markdown aus den Bot-Texten – ohne HTML, alles wird als Text eingesetzt. */
export function RichText({ text }: { text: string }) {
  return <>{renderInline(text, 0)}</>;
}

function renderInline(text: string, depth: number): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let index = 0;
  for (const match of text.matchAll(TOKEN)) {
    const start = match.index ?? 0;
    if (start > last) out.push(<Fragment key={`t${index++}`}>{text.slice(last, start)}</Fragment>);
    const [whole, bold, code, linkText, linkUrl, command] = match;
    if (bold != null) {
      out.push(<strong key={`b${index++}`} className="ink">{depth < 2 ? renderInline(bold, depth + 1) : bold}</strong>);
    } else if (code != null) {
      out.push(<code key={`c${index++}`} className="kbd">{code}</code>);
    } else if (linkText != null && linkUrl != null) {
      out.push(
        <a key={`l${index++}`} href={linkUrl} rel="noopener noreferrer" target="_blank">
          {linkText}
        </a>
      );
    } else if (command != null) {
      out.push(
        <span key={`m${index++}`} className="cmd" title="Befehl im Discord">
          /{command}
        </span>
      );
    } else {
      out.push(<Fragment key={`t${index++}`}>{whole}</Fragment>);
    }
    last = start + whole.length;
  }
  if (last < text.length) out.push(<Fragment key={`t${index++}`}>{text.slice(last)}</Fragment>);
  return out;
}

/** Release-Notizen von GitHub: Absätze und „- “-Listen, Rest wie [RichText]. */
export function Notes({ text }: { text: string }) {
  const blocks: ReactNode[] = [];
  let list: string[] = [];
  let paragraph: string[] = [];
  const flush = () => {
    if (paragraph.length) blocks.push(<p key={`p${blocks.length}`}><RichText text={paragraph.join(' ')} /></p>);
    if (list.length) {
      blocks.push(
        <ul key={`u${blocks.length}`} className="guide-list">
          {list.map((item, i) => (
            <li key={i}><RichText text={item} /></li>
          ))}
        </ul>
      );
    }
    paragraph = [];
    list = [];
  };
  for (const raw of text.split('\n')) {
    const line = raw.trim().replace(/^#+\s*/, '');
    if (!line) {
      flush();
      continue;
    }
    const item = /^[-*]\s+(.*)$/.exec(line);
    if (item) {
      if (paragraph.length) {
        const keep = list;
        list = [];
        flush();
        list = keep;
      }
      list.push(item[1]);
    } else {
      if (list.length) flush();
      paragraph.push(line);
    }
  }
  flush();
  return <div className="notes">{blocks}</div>;
}
