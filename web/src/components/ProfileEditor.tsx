// Eigenes Profil bearbeiten (nur angemeldet, nur das eigene): Anzeigename, Über mich, Farbe,
// Lieblingsrolle/-map, bis zu drei Kennzahlen oben im Profil, Discord-Profilbild an/aus.
// Der Server prüft alles noch einmal (WebAuth/ProfileRules) – hier geht es nur um eine angenehme Eingabe.
import { useState } from 'react';
import { Check } from 'lucide-react';
import type { ProfileOptions, ProfileSettings } from '../lib/api';
import { COLOR_NAMES, PLAYER_COLORS } from '../lib/colors';
import { metric } from '../lib/metrics';
import { useMe } from '../lib/me';

export function ProfileEditor({
  settings,
  options,
  ingameName,
  hasDiscordAvatar,
  onDone
}: {
  settings: ProfileSettings;
  options: ProfileOptions;
  ingameName: string;
  hasDiscordAvatar: boolean;
  onDone: () => void;
}) {
  const { saveProfile } = useMe();
  const [draft, setDraft] = useState<ProfileSettings>(settings);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof ProfileSettings>(key: K, value: ProfileSettings[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const bioLength = [...draft.bio].length;

  const toggleMetric = (id: string) =>
    setDraft((d) => ({
      ...d,
      showcase: d.showcase.includes(id) ? d.showcase.filter((m) => m !== id) : d.showcase.length < options.showcaseMax ? [...d.showcase, id] : d.showcase
    }));

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await saveProfile(draft);
      onDone();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="panel panel--pad editor" aria-labelledby="profil-bearbeiten">
      <div className="stack" style={{ gap: 4 }}>
        <h2 className="section__title" id="profil-bearbeiten">Profil bearbeiten</h2>
        <span className="caption">Nur du kannst das ändern. Alle anderen sehen es auf deinem Profil und in den Listen.</span>
      </div>

      <form
        className="editor__grid"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label className="field">
          <span className="field__label">Anzeigename</span>
          <select value={draft.displayName ?? ''} onChange={(e) => set('displayName', e.target.value || null)}>
            <option value="">Name im Spiel ({ingameName})</option>
            {options.names.filter((n) => n !== ingameName).map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
          <span className="caption">Nur deine Ingame- oder Discord-Namen.</span>
        </label>

        <label className="field">
          <span className="field__label">Lieblingsrolle</span>
          <select value={draft.favoriteRole ?? ''} onChange={(e) => set('favoriteRole', e.target.value || null)}>
            <option value="">Automatisch (meistgespielte Spezialrolle)</option>
            {options.roles.map((r) => (
              <option key={r.id} value={r.id}>{r.name}</option>
            ))}
          </select>
        </label>

        <label className="field">
          <span className="field__label">Lieblingsmap</span>
          <select value={draft.favoriteMap ?? ''} onChange={(e) => set('favoriteMap', e.target.value === '' ? null : Number(e.target.value))}>
            <option value="">Keine</option>
            {options.maps.map((m) => (
              <option key={m.mapId} value={m.mapId}>{m.map}</option>
            ))}
          </select>
        </label>

        <label className="field field--wide">
          <span className="field__label">Über mich</span>
          <textarea
            rows={3}
            maxLength={options.bioMax}
            value={draft.bio}
            placeholder="Ein, zwei Sätze über dich – z.B. dein Lieblings-Trick als Impostor."
            onChange={(e) => set('bio', e.target.value)}
          />
          <span className="caption">{bioLength} / {options.bioMax} Zeichen, höchstens 3 Zeilen</span>
        </label>

        <fieldset className="field field--wide">
          <legend className="field__label">Profilfarbe</legend>
          <div className="swatches">
            <button type="button" className="swatch swatch--auto" aria-pressed={draft.colorId == null} onClick={() => set('colorId', null)}>
              Auto
            </button>
            {PLAYER_COLORS.slice(0, options.colors).map((color, id) => (
              <button
                key={id}
                type="button"
                className="swatch"
                style={{ background: color }}
                aria-pressed={draft.colorId === id}
                aria-label={COLOR_NAMES[id]}
                title={COLOR_NAMES[id]}
                onClick={() => set('colorId', id)}
              >
                {draft.colorId === id && <Check className="icon icon--sm" aria-hidden="true" />}
              </button>
            ))}
          </div>
          <span className="caption">Ein Rahmen um dein Profilbild. „Auto“ = kein Rahmen.</span>
        </fieldset>

        <fieldset className="field field--wide">
          <legend className="field__label">Kennzahlen oben im Profil</legend>
          <div className="segmented">
            {options.metrics.map((id) => {
              const selected = draft.showcase.includes(id);
              const full = !selected && draft.showcase.length >= options.showcaseMax;
              return (
                <button key={id} type="button" aria-pressed={selected} disabled={full} onClick={() => toggleMetric(id)}>
                  {metric(id).label}
                </button>
              );
            })}
          </div>
          <span className="caption">Bis zu {options.showcaseMax} – sie stehen dann groß ganz oben.</span>
        </fieldset>

        <label className="toggle field--wide">
          <input type="checkbox" checked={draft.showAvatar} disabled={!hasDiscordAvatar} onChange={(e) => set('showAvatar', e.target.checked)} />
          <span>
            <span className="field__label">Discord-Profilbild zeigen</span>
            <span className="caption" style={{ display: 'block' }}>
              {hasDiscordAvatar ? 'Aus = farbiger Crewmate in deiner Spielfarbe.' : 'Dein Discord-Konto hat kein eigenes Profilbild (oder es ist noch nicht geladen).'}
            </span>
          </span>
        </label>

        {error && (
          <p className="form-error field--wide" role="alert">
            {error}
          </p>
        )}

        <div className="row field--wide">
          <button type="submit" className="btn btn--primary" disabled={saving}>
            {saving ? 'Speichert …' : 'Speichern'}
          </button>
          <button type="button" className="btn btn--ghost" onClick={onDone} disabled={saving}>
            Abbrechen
          </button>
        </div>
      </form>
    </section>
  );
}
