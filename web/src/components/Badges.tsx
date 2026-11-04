// Abzeichen im Spielerprofil (03.11.2026, Kilian: Erfolge/Abzeichen wie „5 Impostor-Siege in Folge“).
// Berechnet werden sie auf dem Server (web/Badges.kt); hier nur Anzeige: Plakette in der Farbe der Stufe
// (Bronze/Silber/Gold), drei Kerben für die Stufen, Fortschritt zur nächsten Stufe, wann und in welcher Runde erreicht.
import { useState, type ComponentType } from 'react';
import { Link } from 'react-router-dom';
import {
  Award, CalendarDays, Crosshair, Drama, Droplet, Eye, EyeOff, Flame, Footprints, Gavel, Globe, HeartPulse, Hourglass,
  LifeBuoy, ListChecks, Moon, Search, Shield, Skull, Snowflake, Split, Swords, Timer, User, Wind, Wrench, Zap
} from 'lucide-react';
import type { Badge } from '../lib/api';
import { date, num } from '../lib/format';
import { Section } from './ui';
import '../badges.css';

const ICONS: Record<string, ComponentType<{ className?: string }>> = {
  impostor_streak: Snowflake,
  crew_streak: Shield,
  win_streak: Flame,
  survivor_streak: HeartPulse,
  double_kill: Zap,
  massacre: Swords,
  first_blood: Droplet,
  invisible: EyeOff,
  lone_wolf: User,
  sharp_eye: Eye,
  detective: Search,
  last_hope: LifeBuoy,
  speedrun: Timer,
  vent_rat: Wind,
  neutral_win: Split,
  regular: CalendarDays,
  hunter: Crosshair,
  diligent: ListChecks,
  mechanic: Wrench,
  marathon: Footprints,
  roles: Drama,
  globetrotter: Globe,
  night_owl: Moon,
  long_night: Hourglass,
  first_victim: Skull,
  miscarriage: Gavel
};

const TIER_NAMES = ['Offen', 'Bronze', 'Silber', 'Gold'];

function rarityText(r: number | null): string | null {
  if (r == null) return null;
  const p = Math.round(r * 100);
  if (p <= 0) return 'hat noch kaum jemand';
  return `${p} % der Spieler`;
}

/** Anteil des Wegs von der aktuellen zur nächsten Stufe (1 = alle Stufen erreicht). */
function toNext(badge: Badge): number {
  if (badge.tier >= badge.tiers.length) return 1;
  const prev = badge.tier > 0 ? badge.tiers[badge.tier - 1] : 0;
  const next = badge.tiers[badge.tier];
  return Math.max(0, Math.min(1, (badge.value - prev) / Math.max(1, next - prev)));
}

function BadgeTile({ badge }: { badge: Badge }) {
  const Icon = ICONS[badge.id] ?? Award;
  const max = badge.tier >= badge.tiers.length;
  const next = max ? badge.tiers[badge.tiers.length - 1] : badge.tiers[badge.tier];
  const progress = toNext(badge);
  const reached = badge.tier > 0 ? badge.reached[badge.tier - 1] : null;
  const rarity = badge.tier > 0 ? rarityText(badge.rarity) : null;
  return (
    <li className={`badge badge--t${badge.tier}`}>
      <span className="badge__plate" aria-hidden="true">
        <Icon className="icon" />
      </span>
      <div className="badge__body">
        <div className="badge__head">
          <span className="badge__name">{badge.name}</span>
          <span className="badge__tier">{TIER_NAMES[badge.tier]}</span>
        </div>
        <p className="badge__desc">{badge.description}</p>
        <div className="badge__steps" aria-label={`Stufen: ${badge.tiers.map((t) => num(t)).join(', ')}`}>
          {badge.tiers.map((t, i) => (
            <span key={t} className={i < badge.tier ? 'on' : undefined} title={`${TIER_NAMES[i + 1]}: ${num(t)} ${badge.unit}`}>
              {num(t)}
            </span>
          ))}
        </div>
        <div className="badge__progress">
          <div className="badge__track"><div className="badge__fill" style={{ width: `${progress * 100}%` }} /></div>
          <span className="badge__value">
            {max ? `${num(badge.value)} ${badge.unit}` : `${num(badge.value)} / ${num(next)} ${badge.unit}`}
          </span>
        </div>
        {(reached || badge.current != null) && (
          <span className="caption badge__meta">
            {reached && (
              <>
                {TIER_NAMES[badge.tier]} seit <Link to={`/runden/${reached.gameId}`}>{date(reached.endedAt)}</Link>
              </>
            )}
            {reached && rarity && ' · '}
            {rarity}
            {badge.current != null && badge.current > 0 && !max && <>{reached ? ' · ' : ''}läuft gerade: {num(badge.current)}</>}
          </span>
        )}
      </div>
    </li>
  );
}

export function BadgeSection({ badges }: { badges: Badge[] }) {
  const earned = badges.filter((b) => b.tier > 0);
  const [all, setAll] = useState(earned.length === 0);
  const gold = earned.filter((b) => b.tier >= 3).length;
  // Höchste Stufe zuerst, bei gleicher Stufe die, bei denen am wenigsten bis zur nächsten fehlt.
  const shown = (all ? badges : earned).slice().sort((a, b) => b.tier - a.tier || toNext(b) - toNext(a));
  const groups = [...new Set(badges.map((b) => b.group))]
    .map((g) => ({ group: g, items: shown.filter((b) => b.group === g) }))
    .filter((g) => g.items.length > 0);
  return (
    <Section
      title="Abzeichen"
      eyebrow={`${num(earned.length)} von ${num(badges.length)}${gold ? ` · ${num(gold)} in Gold` : ''}`}
      action={
        earned.length > 0 && earned.length < badges.length ? (
          <button type="button" className="btn" onClick={() => setAll((v) => !v)} aria-pressed={all}>
            {all ? 'Nur erreichte' : `Alle ${num(badges.length)} zeigen`}
          </button>
        ) : undefined
      }
    >
      <div className="badges">
        {groups.map((g) => (
          <div key={g.group} className="badges__group">
            <h3 className="badges__label">{g.group}</h3>
            <ul className="badges__list">
              {g.items.map((b) => <BadgeTile key={b.id} badge={b} />)}
            </ul>
          </div>
        ))}
      </div>
    </Section>
  );
}
