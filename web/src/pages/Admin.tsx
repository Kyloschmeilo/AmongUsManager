// Admin-Panel (nur mit der Admin-Rolle im Discord): alles, was sonst Slash-Commands und Panels im Discord machen –
// Spieler-XP/Level (/admin-panel), Discord-Rollen, Rollen für die nächste Runde (/rolle-zuweisen), Voice-Steuerung
// (/panel), Runde beenden (/crew-win, /impostor-win, /runde-abbrechen), MVP-Voting, Panels/Changelog/Tutorial posten,
// Spieleabend-Recap, Event-Channel und /mod-setup. Der Bot prüft jede Aktion selbst noch einmal.
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ArrowRightLeft, Copy, Crown, Drama, Headphones, LogIn, Megaphone, MicOff, RotateCcw, Search, Settings, ShieldAlert, Trophy, UserCog, Users, Volume2
} from 'lucide-react';
import { loginUrl } from '../lib/api';
import { useMe } from '../lib/me';
import { useAction, useBot, usePermissions, type ActionResult, type Person } from '../lib/control';
import { num } from '../lib/format';
import { Avatar, Empty, Loading } from '../components/ui';
import { Title } from '../components/motion';
import { BotProblem } from './Events';

interface Channel { id: string; name: string; category?: string | null }
interface VoiceMember extends Person { muted: boolean; deafened: boolean }
interface VoiceChannel extends Channel { amongUs: boolean; members: VoiceMember[] }
interface Wish extends Person { role: string; label: string; admin: boolean; by: string | null; cost: number; reserved: boolean }
interface ManagedRole { id: string; name: string; emoji: string; manageable: boolean }
interface Overview {
  server: string;
  textChannels: Channel[];
  voiceChannels: VoiceChannel[];
  eventChannel: string | null;
  eventChannelSet: boolean;
  rolesChannel: string | null;
  tutorials: { key: string; label: string }[];
  changelogVersion: string;
  wishRoles: { id: string; label: string; team: string | null; special?: boolean }[];
  wishes: Wish[];
  bets: (Person & { team: string; amount: number })[];
  betsLocked: boolean;
  pendingRecaps: number;
  modChannel: string | null;
  roleGroups: { title: string; roles: ManagedRole[] }[];
}
interface Member extends Person {
  key: string;
  username: string;
  level: number;
  xp: number;
  wins: number;
  participations: number;
  rank: string;
  linked: boolean;
  admin: boolean;
  roles: string[];
  wish: { role: string; label: string; admin: boolean } | null;
  voice: string | null;
}

const TABS = [
  { id: 'spieler', label: 'Spieler', icon: <UserCog className="icon icon--sm" aria-hidden="true" /> },
  { id: 'rollen', label: 'Rollen nächste Runde', icon: <Drama className="icon icon--sm" aria-hidden="true" /> },
  { id: 'voice', label: 'Voice & Runde', icon: <Headphones className="icon icon--sm" aria-hidden="true" /> },
  { id: 'posten', label: 'Posten', icon: <Megaphone className="icon icon--sm" aria-hidden="true" /> },
  { id: 'einstellungen', label: 'Einstellungen', icon: <Settings className="icon icon--sm" aria-hidden="true" /> }
] as const;
type TabId = (typeof TABS)[number]['id'];

export function AdminPage() {
  const { me } = useMe();
  const { perms } = usePermissions();
  const [params, setParams] = useSearchParams();
  const tab = (TABS.find((t) => t.id === params.get('tab'))?.id ?? 'spieler') as TabId;
  const allowed = !!perms?.admin;
  const overview = useBot<Overview>(allowed ? 'admin.overview' : null, undefined, tab === 'voice' ? 8_000 : 60_000);
  const members = useBot<{ members: Member[]; xpPerLevel: number }>(allowed ? 'admin.members' : null);
  const reloadAll = () => {
    overview.reload();
    members.reload();
  };

  const head = (
    <div className="stack" style={{ gap: 16 }}>
      <span className="eyebrow">Admin-Panel</span>
      <Title text="Bot steuern" className="page-title" />
      <p className="lead">Alles aus den Discord-Panels an einem Ort. Jede Aktion führt der Bot aus und schreibt sie in den Log-Channel.</p>
    </div>
  );

  if (!me) return <div className="page page--tight">{head}<Loading /></div>;
  if (!me.loggedIn) {
    return (
      <div className="page page--tight">
        {head}
        <div className="note">
          <LogIn className="icon" aria-hidden="true" />
          <span style={{ flex: 1 }}>Melde dich mit Discord an. Das Admin-Panel sehen nur Mitglieder mit der Admin-Rolle.</span>
          {me.loginEnabled && <a className="btn btn--primary" href={loginUrl()}>Mit Discord anmelden</a>}
        </div>
      </div>
    );
  }
  if (perms && !perms.admin) {
    return (
      <div className="page page--tight">
        {head}
        <div className="note note--warn">
          <ShieldAlert className="icon" aria-hidden="true" />
          <span>Dafür brauchst du im Discord die Admin-Rolle.</span>
        </div>
      </div>
    );
  }

  return (
    <div className="page page--tight">
      {head}
      <div className="panel admin">
        <div className="tabs" role="tablist" aria-label="Bereich">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              className="tab"
              aria-selected={t.id === tab}
              onClick={() => setParams({ tab: t.id }, { replace: true, preventScrollReset: true })}
            >
              {t.icon}
              {t.label}
            </button>
          ))}
        </div>
        <div className="admin__body">
          {(overview.error ?? members.error) && !(overview.data && members.data) && <BotProblem message={(overview.error ?? members.error)!.message} />}
          {(!overview.data || !members.data) && !(overview.error ?? members.error) && <Loading />}
          {overview.data && members.data && (
            <>
              {tab === 'spieler' && <PlayersTab members={members.data.members} overview={overview.data} onChange={reloadAll} />}
              {tab === 'rollen' && <WishesTab members={members.data.members} overview={overview.data} onChange={reloadAll} />}
              {tab === 'voice' && <VoiceTab overview={overview.data} onChange={overview.reload} />}
              {tab === 'posten' && <PostTab overview={overview.data} onChange={overview.reload} />}
              {tab === 'einstellungen' && <SettingsTab overview={overview.data} onChange={overview.reload} />}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------- Bausteine ----------

function Notice({ notice }: { notice: { text: string; error: boolean } | null }) {
  if (!notice) return null;
  return <p className={notice.error ? 'form-error' : 'caption accent'} role="status">{notice.text}</p>;
}

function Who({ person, sub }: { person: Person; sub?: ReactNode }) {
  return (
    <span className="player">
      <Avatar colorId={person.colorId ?? null} playerKey={person.key ?? person.name} src={person.avatar} />
      <span className="stack" style={{ gap: 0 }}>
        <span className="player__name">{person.name}</span>
        {sub && <span className="caption">{sub}</span>}
      </span>
    </span>
  );
}

function ChannelSelect({ channels, value, onChange, label, empty }: { channels: Channel[]; value: string; onChange: (id: string) => void; label: string; empty?: string }) {
  return (
    <label className="field">
      <span className="field__label">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {empty !== undefined && <option value="">{empty}</option>}
        {channels.map((c) => (
          <option key={c.id} value={c.id}>#{c.name}{c.category ? ` · ${c.category}` : ''}</option>
        ))}
      </select>
    </label>
  );
}

function AdminCard({ icon, title, caption, children }: { icon: ReactNode; title: string; caption?: string; children: ReactNode }) {
  return (
    <section className="admin-card">
      <div className="panel__title">
        {icon}
        {title}
        {caption && <span className="caption">{caption}</span>}
      </div>
      {children}
    </section>
  );
}

/** Among-Us-Rollen, dahinter die Special-Rollen von The Other Roles. */
function RoleOptions({ roles }: { roles: Overview['wishRoles'] }) {
  const normal = roles.filter((r) => !r.special);
  const special = roles.filter((r) => r.special);
  return (
    <>
      <optgroup label="Among Us">
        {normal.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
      </optgroup>
      {special.length > 0 && (
        <optgroup label="The Other Roles">
          {special.map((r) => <option key={r.id} value={r.id}>{r.label}{r.team ? ` (${r.team})` : ''}</option>)}
        </optgroup>
      )}
    </>
  );
}

// ---------- Spieler ----------

function PlayersTab({ members, overview, onChange }: { members: Member[]; overview: Overview; onChange: () => void }) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? members.filter((m) => m.name.toLowerCase().includes(q) || m.username.toLowerCase().includes(q)) : members;
  }, [members, query]);
  const current = members.find((m) => m.key === selected) ?? null;

  return (
    <div className="admin-split">
      <div className="stack">
        <div className="row">
          <label className="search" style={{ flex: 1 }}>
            <Search className="icon icon--sm" aria-hidden="true" />
            <input type="search" placeholder={`${members.length} Mitglieder durchsuchen`} value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
        </div>
        <div className="table-wrap admin-list">
          <table className="table">
            <thead>
              <tr>
                <th>Mitglied</th>
                <th className="num">Level</th>
                <th className="num">XP</th>
                <th className="num">Siege</th>
              </tr>
            </thead>
            <tbody>
              {shown.slice(0, 200).map((m) => (
                <tr key={m.key} className={m.key === selected ? 'me' : undefined} onClick={() => setSelected(m.key)} style={{ cursor: 'pointer' }}>
                  <td className="main">
                    <button type="button" className="admin-row-button" onClick={() => setSelected(m.key)} aria-label={`${m.name} bearbeiten`}>
                      <Who person={m} sub={[m.rank, m.linked ? 'verknüpft' : null, m.admin ? 'Admin' : null].filter(Boolean).join(' · ')} />
                    </button>
                  </td>
                  <td className="num">{m.level}</td>
                  <td className="num">{num(m.xp)}</td>
                  <td className="num">{m.wins}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {shown.length === 0 && <Empty text="Niemand gefunden." />}
        </div>
      </div>
      <div>
        {current ? (
          <PlayerEditor key={current.key} member={current} overview={overview} onChange={onChange} />
        ) : (
          <div className="admin-card"><Empty text="Wähle links ein Mitglied aus." hint="Dann kannst du XP, Level, Rollen und die Rolle für die nächste Runde ändern." /></div>
        )}
      </div>
    </div>
  );
}

function PlayerEditor({ member, overview, onChange }: { member: Member; overview: Overview; onChange: () => void }) {
  const { busy, notice, run } = useAction(onChange);
  const [xp, setXp] = useState(String(member.xp));
  const [level, setLevel] = useState(String(member.level));
  // Nach dem Speichern kommen frische Werte vom Bot
  useEffect(() => setXp(String(member.xp)), [member.xp]);
  useEffect(() => setLevel(String(member.level)), [member.level]);
  const [confirmReset, setConfirmReset] = useState(false);
  const [wishRole, setWishRole] = useState(member.wish?.role ?? overview.wishRoles[0]?.id ?? '');
  const player = { player: member.key };

  return (
    <div className="stack" style={{ gap: 16 }}>
      <AdminCard icon={<UserCog className="icon" aria-hidden="true" />} title={member.name} caption={`@${member.username}${member.voice ? ` · im Voice #${member.voice}` : ''}`}>
        <div className="admin-stats">
          <div><span className="caption">Level</span><b className="mono">{member.level}</b></div>
          <div><span className="caption">XP</span><b className="mono">{num(member.xp)}</b></div>
          <div><span className="caption">Siege</span><b className="mono">{member.wins}</b></div>
          <div><span className="caption">Teilnahmen</span><b className="mono">{member.participations}</b></div>
          <div><span className="caption">Rang</span><b>{member.rank}</b></div>
        </div>
        <div className="admin-form">
          <label className="field">
            <span className="field__label">XP</span>
            <div className="row" style={{ gap: 6 }}>
              <input className="input" type="number" min={0} value={xp} onChange={(e) => setXp(e.target.value)} />
              <button type="button" className="btn btn--primary" disabled={busy !== null || xp === String(member.xp)} onClick={() => void run('xp', 'admin.player.set', { ...player, xp: Number(xp) })}>Setzen</button>
            </div>
            <div className="segmented">
              {[-10, -5, 5, 10, 50].map((d) => (
                <button key={d} type="button" disabled={busy !== null} onClick={() => void run('xp', 'admin.player.set', { ...player, xp: Math.max(0, member.xp + d) })}>
                  {d > 0 ? `+${d}` : `−${-d}`}
                </button>
              ))}
            </div>
          </label>
          <label className="field">
            <span className="field__label">Level</span>
            <div className="row" style={{ gap: 6 }}>
              <input className="input" type="number" min={1} value={level} onChange={(e) => setLevel(e.target.value)} />
              <button type="button" className="btn btn--primary" disabled={busy !== null || level === String(member.level)} onClick={() => void run('level', 'admin.player.set', { ...player, level: Number(level) })}>Setzen</button>
            </div>
            <div className="segmented">
              {[-1, 1, 5].map((d) => (
                <button key={d} type="button" disabled={busy !== null} onClick={() => void run('level', 'admin.player.set', { ...player, level: Math.max(1, member.level + d) })}>
                  {d > 0 ? `+${d}` : `−${-d}`}
                </button>
              ))}
            </div>
          </label>
        </div>
        <div className="row">
          {confirmReset ? (
            <>
              <span className="ember" style={{ fontSize: 14 }}>Wirklich alle Daten von {member.name} löschen?</span>
              <button type="button" className="btn btn--danger" disabled={busy !== null} onClick={() => void run('reset', 'admin.player.reset', player).then(() => setConfirmReset(false))}>Ja, alles löschen</button>
              <button type="button" className="btn btn--ghost" onClick={() => setConfirmReset(false)}>Abbrechen</button>
            </>
          ) : (
            <button type="button" className="btn btn--ghost ember" onClick={() => setConfirmReset(true)}>
              <RotateCcw className="icon icon--sm" aria-hidden="true" /> Zurücksetzen
            </button>
          )}
        </div>
        <Notice notice={notice} />
      </AdminCard>

      <AdminCard icon={<Drama className="icon" aria-hidden="true" />} title="Rolle in der nächsten Runde" caption="/rolle-zuweisen">
        <p className="caption">
          {member.wish ? `Aktuell: ${member.wish.label} (${member.wish.admin ? 'zugewiesen' : 'eigener Wunsch'})` : 'Kein Wunsch und keine Zuweisung.'}
          {!member.linked && ' · Nicht verknüpft – ohne /verknuepfen kann die Mod die Rolle nicht zuordnen.'}
        </p>
        <div className="row">
          <label className="field" style={{ flex: 1, minWidth: 180 }}>
            <span className="sr-only">Rolle</span>
            <select value={wishRole} onChange={(e) => setWishRole(e.target.value)}>
              <RoleOptions roles={overview.wishRoles} />
            </select>
          </label>
          <button type="button" className="btn btn--primary" disabled={busy !== null} onClick={() => void run('wish', 'admin.wish', { ...player, role: wishRole })}>Zuweisen</button>
          {member.wish && (
            <button type="button" className="btn" disabled={busy !== null} onClick={() => void run('wish', 'admin.wish', { ...player, role: null })}>Entfernen</button>
          )}
        </div>
      </AdminCard>

      <AdminCard icon={<Users className="icon" aria-hidden="true" />} title="Discord-Rollen">
        {overview.roleGroups.map((group) => (
          <div key={group.title} className="stack" style={{ gap: 8 }}>
            <span className="eyebrow">{group.title}</span>
            <div className="role-chips">
              {group.roles.map((role) => {
                const has = member.roles.includes(role.id);
                return (
                  <button
                    key={role.id}
                    type="button"
                    className="role-chip"
                    aria-pressed={has}
                    disabled={busy !== null || !role.manageable}
                    title={role.manageable ? (has ? 'Klicken zum Entfernen' : 'Klicken zum Vergeben') : 'Die Bot-Rolle steht unter dieser Rolle'}
                    onClick={() => void run(`role${role.id}`, 'admin.player.role', { ...player, role: role.id, give: !has })}
                  >
                    <span aria-hidden="true">{role.emoji}</span> {role.name}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </AdminCard>
    </div>
  );
}

// ---------- Rollen für die nächste Runde ----------

function WishesTab({ members, overview, onChange }: { members: Member[]; overview: Overview; onChange: () => void }) {
  const { busy, notice, run } = useAction(onChange);
  const [player, setPlayer] = useState('');
  const [role, setRole] = useState(overview.wishRoles[0]?.id ?? '');
  const sorted = useMemo(() => [...members].sort((a, b) => Number(b.linked) - Number(a.linked) || a.name.localeCompare(b.name, 'de')), [members]);

  return (
    <div className="stack" style={{ gap: 24 }}>
      <AdminCard icon={<Drama className="icon" aria-hidden="true" />} title="Rolle festlegen" caption="gilt für die nächste Runde, wenn der Host CrewStats hat">
        <div className="admin-form">
          <label className="field">
            <span className="field__label">Spieler</span>
            <select value={player} onChange={(e) => setPlayer(e.target.value)}>
              <option value="">Bitte wählen …</option>
              {sorted.map((m) => (
                <option key={m.key} value={m.key}>{m.name}{m.linked ? '' : ' (nicht verknüpft)'}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field__label">Rolle</span>
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              <RoleOptions roles={overview.wishRoles} />
            </select>
          </label>
        </div>
        <div className="row">
          <button type="button" className="btn btn--primary" disabled={busy !== null || !player} onClick={() => void run('assign', 'admin.wish', { player, role })}>Zuweisen</button>
        </div>
        <Notice notice={notice} />
      </AdminCard>

      <AdminCard icon={<Users className="icon" aria-hidden="true" />} title="Offene Wünsche und Zuweisungen" caption={`${overview.wishes.length}`}>
        {overview.wishes.length === 0 ? (
          <Empty text="Für die nächste Runde ist nichts offen." />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Spieler</th><th>Rolle</th><th>Art</th><th /></tr></thead>
              <tbody>
                {overview.wishes.map((w) => (
                  <tr key={`${w.key}-${w.role}`}>
                    <td className="main"><Who person={w} /></td>
                    <td>{w.label}</td>
                    <td className="caption">
                      {w.admin ? `zugewiesen von ${w.by ?? '?'}` : `Wunsch (${w.cost} XP)`}
                      {w.reserved ? ' · gerade bei der Mod' : ''}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button type="button" className="btn btn--ghost" disabled={busy !== null} onClick={() => void run(`rm${w.key}`, 'admin.wish', { player: w.key, role: null })}>Entfernen</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </AdminCard>
    </div>
  );
}

// ---------- Voice & Runde ----------

function VoiceTab({ overview, onChange }: { overview: Overview; onChange: () => void }) {
  const active = overview.voiceChannels.filter((c) => c.members.length > 0);
  const empty = overview.voiceChannels.length - active.length;
  return (
    <div className="stack" style={{ gap: 24 }}>
      {overview.bets.length > 0 && (
        <div className="note">
          <Trophy className="icon" aria-hidden="true" />
          <span>
            {overview.bets.length} offene Wette(n){overview.betsLocked ? ' · Wettbüro gesperrt (Runde läuft)' : ''}:{' '}
            {overview.bets.map((b) => `${b.name} ${b.amount} XP auf ${b.team}`).join(', ')}
          </span>
        </div>
      )}
      {active.length === 0 ? (
        <Empty text="Gerade ist niemand im Voice." hint={`${empty} Voice-Channels sind leer.`} />
      ) : (
        <div className="grid grid--2">
          {active.map((c) => <VoiceCard key={c.id} channel={c} hasBets={overview.bets.length > 0} onChange={onChange} />)}
        </div>
      )}
      {active.length > 0 && empty > 0 && <span className="caption">{empty} weitere Voice-Channels sind leer.</span>}
    </div>
  );
}

function VoiceCard({ channel, hasBets, onChange }: { channel: VoiceChannel; hasBets: boolean; onChange: () => void }) {
  const { busy, notice, run } = useAction(onChange);
  const [finishing, setFinishing] = useState(false);
  const [impostors, setImpostors] = useState<string[]>([]);
  const body = { channel: channel.id };
  const toggle = (key: string) => setImpostors((list) => (list.includes(key) ? list.filter((k) => k !== key) : [...list, key]));
  const finish = (team: 'crew' | 'impostor') =>
    void run('finish', 'admin.round', { ...body, team, impostors }).then((r) => {
      if (r) {
        setFinishing(false);
        setImpostors([]);
      }
    });

  return (
    <section className="admin-card">
      <div className="panel__title">
        <Volume2 className="icon" aria-hidden="true" />
        #{channel.name}
        {channel.amongUs && <span className="pill pill--case">Among Us</span>}
        <span className="caption">{channel.members.length} drin</span>
      </div>
      <ul className="voice-members">
        {channel.members.map((m) => (
          <li key={m.key ?? m.name}>
            {finishing ? (
              <label className="toggle">
                <input type="checkbox" checked={!!m.key && impostors.includes(m.key)} onChange={() => m.key && toggle(m.key)} />
                <span>{m.name}</span>
              </label>
            ) : (
              <Who person={m} />
            )}
            {!finishing && (m.muted || m.deafened) && (
              <span className="caption voice-members__state">
                {m.muted && <MicOff className="icon icon--sm" aria-label="stumm" />}
                {m.deafened && <Headphones className="icon icon--sm" aria-label="taub" />}
              </span>
            )}
          </li>
        ))}
      </ul>
      {finishing ? (
        <div className="stack">
          <span className="caption">Impostor ankreuzen. Crew-Sieg: alle anderen im Channel gewinnen. Impostor-Sieg: nur die Angekreuzten.</span>
          <div className="row">
            <button type="button" className="btn btn--primary" disabled={busy !== null} onClick={() => finish('crew')}>Crew-Sieg</button>
            <button type="button" className="btn btn--danger" disabled={busy !== null || impostors.length === 0} onClick={() => finish('impostor')}>Impostor-Sieg</button>
            <button type="button" className="btn btn--ghost" onClick={() => setFinishing(false)}>Zurück</button>
          </div>
        </div>
      ) : (
        <div className="row">
          <button type="button" className="btn btn--danger" disabled={busy !== null} onClick={() => void run('mute', 'admin.voice', { ...body, op: 'mute' })}>
            <MicOff className="icon icon--sm" aria-hidden="true" /> Runde (Mute)
          </button>
          <button type="button" className="btn btn--primary" disabled={busy !== null} onClick={() => void run('unmute', 'admin.voice', { ...body, op: 'unmute' })}>
            <Volume2 className="icon icon--sm" aria-hidden="true" /> Meeting (Unmute)
          </button>
          {!channel.amongUs && (
            <button type="button" className="btn" disabled={busy !== null} onClick={() => void run('move', 'admin.voice', { ...body, op: 'move' })}>
              <ArrowRightLeft className="icon icon--sm" aria-hidden="true" /> In Among Us
            </button>
          )}
          <button type="button" className="btn" disabled={busy !== null} onClick={() => setFinishing(true)}>
            <Trophy className="icon icon--sm" aria-hidden="true" /> Runde beenden
          </button>
          <button type="button" className="btn" disabled={busy !== null || channel.members.length < 2} onClick={() => void run('mvp', 'admin.mvp', body)}>
            <Crown className="icon icon--sm" aria-hidden="true" /> MVP-Voting
          </button>
          {hasBets && (
            <button type="button" className="btn btn--ghost" disabled={busy !== null} onClick={() => void run('cancel', 'admin.round.cancel', body)}>
              Runde abbrechen
            </button>
          )}
        </div>
      )}
      {busy && <span className="caption">Der Bot arbeitet …</span>}
      <Notice notice={notice} />
    </section>
  );
}

// ---------- Posten ----------

function PostTab({ overview, onChange }: { overview: Overview; onChange: () => void }) {
  const { busy, notice, run } = useAction(onChange);
  const first = overview.textChannels[0]?.id ?? '';
  const [panelChannel, setPanelChannel] = useState(first);
  const [changelogChannel, setChangelogChannel] = useState(first);
  const [tutorialChannel, setTutorialChannel] = useState(first);
  const [topic, setTopic] = useState(overview.tutorials[0]?.key ?? '');

  return (
    <div className="stack" style={{ gap: 16 }}>
      <Notice notice={notice} />
      <div className="grid grid--2">
        <AdminCard icon={<Headphones className="icon" aria-hidden="true" />} title="Among-Us-Steuerpult" caption="/panel">
          <ChannelSelect channels={overview.textChannels} value={panelChannel} onChange={setPanelChannel} label="Channel" />
          <div className="row"><button type="button" className="btn btn--primary" disabled={busy !== null || !panelChannel} onClick={() => void run('panel', 'admin.post', { what: 'panel', channel: panelChannel })}>Posten</button></div>
        </AdminCard>
        <AdminCard icon={<Users className="icon" aria-hidden="true" />} title="Rollen-Panel" caption="/rollen-panel">
          <p className="caption">Postet die Rollen-Auswahl in {overview.rolesChannel ? `#${overview.rolesChannel}` : 'den Rollen-Channel'}. Ältere Panels funktionieren weiter.</p>
          <div className="row"><button type="button" className="btn btn--primary" disabled={busy !== null} onClick={() => void run('rolepanel', 'admin.post', { what: 'rolepanel' })}>Posten</button></div>
        </AdminCard>
        <AdminCard icon={<Megaphone className="icon" aria-hidden="true" />} title={`Changelog ${overview.changelogVersion}`} caption="/changelog">
          <ChannelSelect channels={overview.textChannels} value={changelogChannel} onChange={setChangelogChannel} label="Channel" />
          <div className="row"><button type="button" className="btn btn--primary" disabled={busy !== null || !changelogChannel} onClick={() => void run('changelog', 'admin.post', { what: 'changelog', channel: changelogChannel })}>Posten</button></div>
        </AdminCard>
        <AdminCard icon={<Megaphone className="icon" aria-hidden="true" />} title="Tutorial" caption="/tutorial">
          <label className="field">
            <span className="field__label">Thema</span>
            <select value={topic} onChange={(e) => setTopic(e.target.value)}>
              {overview.tutorials.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
            </select>
          </label>
          <ChannelSelect channels={overview.textChannels} value={tutorialChannel} onChange={setTutorialChannel} label="Channel" />
          <div className="row"><button type="button" className="btn btn--primary" disabled={busy !== null || !tutorialChannel || !topic} onClick={() => void run('tutorial', 'admin.post', { what: 'tutorial', channel: tutorialChannel, topic })}>Posten</button></div>
        </AdminCard>
        <AdminCard icon={<Trophy className="icon" aria-hidden="true" />} title="Spieleabend-Recap" caption="/spieleabend-recap">
          <p className="caption">
            {overview.pendingRecaps > 0
              ? `${overview.pendingRecaps} Recap(s) offen. Sonst kommt er automatisch 5 Stunden nach Event-Start.`
              : 'Gerade ist kein Recap offen (kein gestartetes Event oder schon gepostet).'}
          </p>
          <div className="row"><button type="button" className="btn btn--primary" disabled={busy !== null || overview.pendingRecaps === 0} onClick={() => void run('recap', 'admin.post', { what: 'recap' })}>Jetzt posten</button></div>
        </AdminCard>
      </div>
      {busy && <span className="caption">Der Bot postet …</span>}
    </div>
  );
}

// ---------- Einstellungen ----------

function SettingsTab({ overview, onChange }: { overview: Overview; onChange: () => void }) {
  const events = useAction(onChange);
  const mod = useAction(onChange);
  const [eventChannel, setEventChannel] = useState(overview.eventChannelSet ? overview.eventChannel ?? '' : '');
  const [modChannel, setModChannel] = useState(overview.textChannels[0]?.id ?? '');
  const [confirmMod, setConfirmMod] = useState(false);
  const [webhook, setWebhook] = useState<string | null>(null);

  return (
    <div className="grid grid--2">
      <AdminCard icon={<Megaphone className="icon" aria-hidden="true" />} title="Event-Channel" caption="für Events von der Website">
        <ChannelSelect channels={overview.textChannels} value={eventChannel} onChange={setEventChannel} label="Channel" empty="Automatisch (Channel des letzten Events)" />
        <div className="row">
          <button type="button" className="btn btn--primary" disabled={events.busy !== null} onClick={() => void events.run('event', 'admin.settings', { eventChannel: eventChannel || null })}>Speichern</button>
        </div>
        <Notice notice={events.notice} />
      </AdminCard>
      <AdminCard icon={<Settings className="icon" aria-hidden="true" />} title="CrewStats-Mod einrichten" caption="/mod-setup">
        <p className="caption">
          {overview.modChannel ? `Rundenergebnisse landen gerade in #${overview.modChannel}.` : 'Die Mod ist noch nicht eingerichtet.'} Neu einrichten macht die alte
          Webhook-URL ungültig – sie muss dann bei allen Hosts neu in die Mod-Config.
        </p>
        <ChannelSelect channels={overview.textChannels} value={modChannel} onChange={setModChannel} label="Ergebnis-Channel" />
        <div className="row">
          {confirmMod ? (
            <>
              <button
                type="button"
                className="btn btn--danger"
                disabled={mod.busy !== null}
                onClick={() =>
                  void mod.run<ActionResult>('mod', 'admin.modsetup', { channel: modChannel }).then((r) => {
                    setConfirmMod(false);
                    if (r?.webhookUrl) setWebhook(r.webhookUrl);
                  })
                }
              >
                Ja, neue URL erzeugen
              </button>
              <button type="button" className="btn btn--ghost" onClick={() => setConfirmMod(false)}>Abbrechen</button>
            </>
          ) : (
            <button type="button" className="btn" disabled={!modChannel} onClick={() => setConfirmMod(true)}>Einrichten</button>
          )}
        </div>
        <Notice notice={mod.notice} />
        {webhook && (
          <div className="stack" style={{ gap: 8 }}>
            <span className="caption ember">Geheim! Wer die URL hat, kann Runden einschicken. Sie wird nur jetzt angezeigt.</span>
            <div className="row" style={{ gap: 6 }}>
              <input className="input mono" readOnly value={webhook} onFocus={(e) => e.currentTarget.select()} style={{ flex: 1 }} />
              <button type="button" className="btn btn--icon" aria-label="URL kopieren" onClick={() => void navigator.clipboard?.writeText(webhook)}>
                <Copy className="icon icon--sm" aria-hidden="true" />
              </button>
            </div>
            <span className="caption">In <span className="mono">BepInEx\config\de.amongusmanager.crewstats.cfg</span> bei <span className="mono">WebhookUrl =</span> eintragen (Spiel vorher schließen).</span>
          </div>
        )}
      </AdminCard>
    </div>
  );
}
