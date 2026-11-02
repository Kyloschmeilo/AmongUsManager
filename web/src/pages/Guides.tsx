// Hilfe & Mods: Tutorials (dieselben wie /tutorial im Discord + Host-Anleitung) und Mod-Downloads.
// Das Rollen-Lexikon ist die Rollen-Seite (/rollen), hier nur verlinkt.
import { useEffect, useRef, type ReactNode } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { BookOpen, ChartColumn, Download, Drama, ExternalLink, FileDown, Info, Package, ScrollText, TriangleAlert } from 'lucide-react';
import { useApi } from '../lib/api';
import { date } from '../lib/format';
import { Notes, RichText, fileSize, stepOf, type DownloadItem, type LexiconRole, type TutorialData } from '../lib/guides';
import { Async, Empty } from '../components/ui';
import { Reveal, Title, useStagger } from '../components/motion';
import '../guides.css';

type HelpTab = 'tutorials' | 'downloads' | 'rollen';

function HelpHeader({ active }: { active: HelpTab }) {
  const lexicon = useApi<{ roles: LexiconRole[] }>('lexicon');
  const tabs: { id: HelpTab; to: string; icon: ReactNode; label: string; hint: string }[] = [
    { id: 'tutorials', to: '/hilfe', icon: <BookOpen className="icon" aria-hidden="true" />, label: 'Tutorials', hint: 'So geht’s' },
    { id: 'downloads', to: '/hilfe/downloads', icon: <Download className="icon" aria-hidden="true" />, label: 'Downloads', hint: 'Mods & Installer' },
    {
      id: 'rollen',
      to: '/rollen',
      icon: <ScrollText className="icon" aria-hidden="true" />,
      label: 'Alle Rollen',
      hint: lexicon.data ? `${lexicon.data.roles.length} Rollen erklärt` : 'Jede Rolle erklärt'
    }
  ];
  return (
    <div className="stack" style={{ gap: 24 }}>
      <div className="stack" style={{ gap: 16 }}>
        <span className="eyebrow">Hilfe & Mods</span>
        <Title text={active === 'downloads' ? 'Downloads' : 'Tutorials'} className="page-title" />
        <p className="lead">
          {active === 'downloads'
            ? 'Alle Mods für den Spieleabend: The Other Roles für alle, CrewStats für Hosts und Auto-Join – mit Anleitung.'
            : 'Alles, was du zum Mitspielen brauchst – Schritt für Schritt erklärt.'}
        </p>
      </div>
      <nav className="help-tabs" aria-label="Hilfe & Mods">
        {tabs.map((tab) => (
          <Link key={tab.id} to={tab.to} className="help-tab" aria-current={tab.id === active ? 'page' : undefined}>
            {tab.icon}
            <span>
              {tab.label}
              <small>{tab.hint}</small>
            </span>
          </Link>
        ))}
      </nav>
    </div>
  );
}

// ---------- Tutorials ----------

export function TutorialsPage() {
  const tutorials = useApi<{ tutorials: TutorialData[] }>('tutorials');
  const [params, setParams] = useSearchParams();
  return (
    <div className="page page--tight">
      <HelpHeader active="tutorials" />
      <Async state={tutorials}>
        {(data) => {
          if (!data.tutorials.length) return <Empty text="Noch keine Tutorials." />;
          const current = data.tutorials.find((t) => t.key === params.get('t')) ?? data.tutorials[0];
          return (
            <Reveal>
              <div className="panel guide">
                <TutorialTabs
                  tutorials={data.tutorials}
                  current={current.key}
                  onSelect={(key) => setParams(key === data.tutorials[0].key ? {} : { t: key }, { replace: true })}
                />
                <TutorialView key={current.key} tutorial={current} />
              </div>
            </Reveal>
          );
        }}
      </Async>
      <div className="note">
        <Info className="icon icon--sm" aria-hidden="true" />
        <span>
          Befehle wie <span className="cmd">/verknuepfen</span> gibst du im Discord ein: <span className="kbd">/</span> tippen und den Befehl aus der Liste wählen.
        </span>
      </div>
    </div>
  );
}

/** Reiter der Tutorials; der gewählte wird auch auf dem Handy sichtbar gescrollt (Links wie /hilfe?t=autojoin). */
function TutorialTabs({ tutorials, current, onSelect }: { tutorials: TutorialData[]; current: string; onSelect: (key: string) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const list = ref.current;
    const tab = list?.querySelector<HTMLElement>('[aria-selected="true"]');
    if (!list || !tab) return;
    const left = tab.offsetLeft - list.offsetLeft;
    if (left < list.scrollLeft || left + tab.offsetWidth > list.scrollLeft + list.clientWidth) {
      list.scrollLeft = Math.max(0, left - 16);
    }
  }, [current]);
  return (
    <div className="tabs" role="tablist" aria-label="Tutorial" ref={ref}>
      {tutorials.map((t) => (
        <button key={t.key} type="button" role="tab" className="tab" aria-selected={t.key === current} onClick={() => onSelect(t.key)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

function TutorialView({ tutorial }: { tutorial: TutorialData }) {
  const ref = useRef<HTMLDivElement>(null);
  useStagger(ref, [tutorial.key]);
  return (
    <div className="guide__body" ref={ref}>
      <div className="stack" style={{ gap: 12 }} data-stagger>
        <h2 className="section__title">{tutorial.title}</h2>
        <p className="guide__intro"><RichText text={tutorial.intro} /></p>
        <TutorialActions tutorialKey={tutorial.key} />
      </div>
      <div className="guide__sections">
        {tutorial.sections.map((section) => (
          <section key={section.title} className="guide__section" data-stagger>
            <h3 className="eyebrow">{section.title}</h3>
            <GuideLines lines={section.lines} />
          </section>
        ))}
      </div>
      <p className="caption" data-stagger>{tutorial.footer}</p>
    </div>
  );
}

/** Passende Knöpfe unter der Einleitung (Download, Rollen …). */
function TutorialActions({ tutorialKey }: { tutorialKey: string }) {
  if (tutorialKey === 'special-rollen') {
    return (
      <div className="row">
        <Link className="btn btn--primary" to="/hilfe/downloads#theotherroles">
          <Download className="icon icon--sm" aria-hidden="true" />
          Installer
        </Link>
        <Link className="btn" to="/rollen">Alle Rollen</Link>
      </div>
    );
  }
  if (tutorialKey === 'crewstats-host' || tutorialKey === 'autojoin') {
    return (
      <div className="row">
        <Link className="btn btn--primary" to="/hilfe/downloads#crewstats">
          <Download className="icon icon--sm" aria-hidden="true" />
          CrewStats
        </Link>
        {tutorialKey === 'autojoin' && (
          <Link className="btn" to="/hilfe/downloads#theotherroles">The Other Roles</Link>
        )}
      </div>
    );
  }
  return null;
}

/** Zeilen eines Abschnitts: „1. …“ als nummerierte Schritte, alles andere als Liste. */
function GuideLines({ lines }: { lines: string[] }) {
  const groups: { steps: boolean; lines: string[] }[] = [];
  for (const line of lines) {
    const steps = stepOf(line) !== null;
    const last = groups[groups.length - 1];
    if (last && last.steps === steps) last.lines.push(line);
    else groups.push({ steps, lines: [line] });
  }
  return (
    <>
      {groups.map((group, i) =>
        group.steps ? (
          <ol key={i} className="guide-steps">
            {group.lines.map((line) => {
              const [n, text] = stepOf(line)!;
              return (
                <li key={line}>
                  <span className="guide-steps__n">{n}</span>
                  <span><RichText text={text} /></span>
                </li>
              );
            })}
          </ol>
        ) : (
          <ul key={i} className="guide-list">
            {group.lines.map((line) => (
              <li key={line}><RichText text={line} /></li>
            ))}
          </ul>
        )
      )}
    </>
  );
}

// ---------- Downloads ----------

export function DownloadsPage() {
  const downloads = useApi<{ items: DownloadItem[] }>('downloads');
  const { hash } = useLocation();
  // /hilfe/downloads#crewstats: zur Karte springen, sobald sie da ist
  useEffect(() => {
    if (!hash || !downloads.data) return;
    const id = window.setTimeout(() => document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView({ block: 'start' }), 350);
    return () => window.clearTimeout(id);
  }, [hash, downloads.data]);
  return (
    <div className="page page--tight">
      <HelpHeader active="downloads" />
      <Async state={downloads}>
        {(data) =>
          data.items.length ? (
            <div className="downloads">
              {data.items.map((item, index) => (
                <Reveal key={item.id} delay={Math.min(index, 3) * 0.05}>
                  <DownloadCard item={item} />
                </Reveal>
              ))}
            </div>
          ) : (
            <Empty text="Noch keine Downloads." />
          )
        }
      </Async>
      <div className="note">
        <Info className="icon icon--sm" aria-hidden="true" />
        <span>
          Die Installer sind <span className="kbd">.bat</span>-Dateien für Windows. Browser und Windows warnen bei solchen Dateien – das ist normal.
          Sie installieren nur Among-Us-Mods und lassen sich mit <span className="kbd">2</span> wieder entfernen.
        </span>
      </div>
    </div>
  );
}

function DownloadIcon({ id }: { id: string }) {
  if (id === 'theotherroles') return <Drama className="icon" />;
  if (id === 'crewstats') return <ChartColumn className="icon" />;
  if (id === 'weitere') return <FileDown className="icon" />;
  return <Package className="icon" />;
}

function DownloadCard({ item }: { item: DownloadItem }) {
  const primary = item.files.filter((f) => f.primary);
  const secondary = item.files.filter((f) => !f.primary);
  return (
    <article className="panel dl-card" id={item.id}>
      <div className="dl-card__head">
        <span className="dl-card__icon" aria-hidden="true">
          <DownloadIcon id={item.id} />
        </span>
        <div className="stack" style={{ gap: 4, minWidth: 0 }}>
          {(item.game || item.audience) && <span className="eyebrow">{[item.game, item.audience].filter(Boolean).join(' · ')}</span>}
          <h2 className="dl-card__title">{item.title}</h2>
        </div>
        {item.version && (
          <span className="dl-card__version">
            <span className="pill pill--mono">{item.version}</span>
            {item.publishedAt && <span className="caption">{date(item.publishedAt)}</span>}
          </span>
        )}
      </div>

      <p className="dl-card__summary"><RichText text={item.summary} /></p>

      {item.files.length > 0 && (
        <div className="dl-card__files">
          {primary.map((file) => (
            <a
              key={file.url}
              className="btn btn--primary"
              href={file.url}
              download={file.external ? undefined : file.name}
              rel={file.external ? 'noopener noreferrer' : undefined}
            >
              <Download className="icon icon--sm" aria-hidden="true" />
              {file.label}
            </a>
          ))}
          {secondary.map((file) => (
            <a
              key={file.url}
              className="btn"
              href={file.url}
              download={file.external ? undefined : file.name}
              rel={file.external ? 'noopener noreferrer' : undefined}
            >
              {file.label}
            </a>
          ))}
          <span className="caption dl-card__meta">
            {[primary[0]?.name, fileSize(primary[0]?.size), primary[0]?.external ? 'von GitHub' : null].filter(Boolean).join(' · ')}
          </span>
        </div>
      )}
      {item.missing && (
        <div className="note note--warn">
          <TriangleAlert className="icon icon--sm" aria-hidden="true" />
          <span>{item.missing}</span>
        </div>
      )}
      {item.versionNote && <p className="caption">{item.versionNote}</p>}

      {(item.steps.length > 0 || item.points.length > 0) && (
        <div className="dl-card__how">
          {item.steps.length > 0 && (
            <div className="stack" style={{ gap: 8 }}>
              <h3 className="eyebrow">So geht’s</h3>
              <ol className="guide-steps">
                {item.steps.map((step, i) => (
                  <li key={i}>
                    <span className="guide-steps__n">{i + 1}</span>
                    <span><RichText text={step} /></span>
                  </li>
                ))}
              </ol>
            </div>
          )}
          {item.points.length > 0 && (
            <div className="stack" style={{ gap: 8 }}>
              <h3 className="eyebrow">Gut zu wissen</h3>
              <ul className="guide-list">
                {item.points.map((point, i) => (
                  <li key={i}><RichText text={point} /></li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {item.notes && (
        <details className="dl-card__notes">
          <summary>Neu in {item.version ?? 'dieser Version'}</summary>
          <Notes text={item.notes} />
        </details>
      )}

      {item.links.length > 0 && (
        <div className="dl-card__links">
          {item.links.map((link) =>
            link.to ? (
              <Link key={link.label} className="btn btn--ghost" to={link.to}>
                {link.label}
              </Link>
            ) : (
              <a key={link.label} className="btn btn--ghost" href={link.href} target="_blank" rel="noopener noreferrer">
                {link.label}
                <ExternalLink className="icon icon--sm" aria-hidden="true" />
              </a>
            )
          )}
        </div>
      )}
    </article>
  );
}
