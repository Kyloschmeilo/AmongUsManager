/*
 * Spiele-Ton (21.11.2026, Komfort-Ideen, Kilian: Punkt 5 „Sounds und Musik-Schalter in den Spielen“).
 * Ein Skript für alle Browserspiele (/seiten/spiele-ton.js, liegt im Website-JAR):
 *  - Knopf „Ton“: schaltet ALLE Töne der Seite stumm. Dafür merkt sich das Skript jeden AudioContext, den die Seite anlegt
 *    (es ersetzt window.AudioContext durch eine Unterklasse), und hält ihn bei „aus“ an – die Spiele brauchen dafür keinen
 *    eigenen Code. Ihre eigenen Ton-Knöpfe bleiben und gelten zusätzlich.
 *  - Knopf „Musik“: ruhige Hintergrundmusik, live per Web Audio erzeugt (keine Dateien, kein CDN), Standard aus.
 * Beide Einstellungen gelten für alle Spiele (localStorage `spiele.ton`, `spiele.musik`, `spiele.musik.lautstaerke`).
 *
 * Einbinden im <head> VOR den Skripten des Spiels (sonst sind schon angelegte AudioContexte nicht erfasst):
 *   <script src="/seiten/spiele-ton.js" data-stimmung="flott" data-ecke="unten-links"></script>
 * data-stimmung: locker (Standard) | flott | ruhig – Tempo und Klang der Musik.
 * data-ecke: unten-links (Standard) | unten-rechts | oben-links | oben-rechts – wo die Knöpfe sitzen.
 * data-abstand: Abstand zum oberen/unteren Rand in px (Standard 12), wenn dort schon etwas vom Spiel sitzt.
 * data-ohne-ton: nur der Musik-Knopf (für Spiele ohne eigene Töne).
 * Schnittstelle: window.SpieleTon = { ton, musik, setTon(b), setMusik(b), onChange(fn) }.
 */
(function () {
  'use strict';
  if (window.SpieleTon) return;

  var script = document.currentScript;
  var opt = function (name, fallback) { return (script && script.getAttribute('data-' + name)) || fallback; };
  var MOOD = opt('stimmung', 'locker');
  var CORNER = opt('ecke', 'unten-links');
  var OFFSET = (parseInt(opt('abstand', '12'), 10) || 12) + 'px';
  var WITH_TON = !(script && script.hasAttribute('data-ohne-ton'));

  var store = {
    get: function (key, fallback) { try { var v = localStorage.getItem(key); return v == null ? fallback : v; } catch (e) { return fallback; } },
    set: function (key, value) { try { localStorage.setItem(key, value); } catch (e) { /* privater Modus: gilt nur jetzt */ } },
  };
  var ton = store.get('spiele.ton', '1') !== '0';
  var musik = store.get('spiele.musik', '0') === '1';
  var volume = Math.min(1, Math.max(0, parseFloat(store.get('spiele.musik.lautstaerke', '0.6')) || 0.6));
  var listeners = [];

  // ---------- Alle AudioContexte der Seite erfassen ----------
  var Native = window.AudioContext || window.webkitAudioContext;
  var contexts = [];
  if (Native) {
    var Tracked = function () {
      var ctx = Reflect.construct(Native, arguments, Tracked);
      contexts.push(ctx);
      if (!ton) Native.prototype.suspend.call(ctx);
      return ctx;
    };
    Tracked.prototype = Object.create(Native.prototype);
    Tracked.prototype.constructor = Tracked;
    Object.setPrototypeOf(Tracked, Native);
    // Spiele rufen resume() selbst auf (z.B. beim ersten Klick) – bei „Ton aus“ bleibt es still
    Tracked.prototype.resume = function () { return ton ? Native.prototype.resume.call(this) : Promise.resolve(); };
    try {
      window.AudioContext = Tracked;
      if (window.webkitAudioContext) window.webkitAudioContext = Tracked;
    } catch (e) { /* schreibgeschützt: dann wirkt nur der eigene Schalter des Spiels */ }
  }
  function applyTon() {
    contexts.forEach(function (ctx) {
      if (ctx.state === 'closed') return;
      (ton ? Native.prototype.resume : Native.prototype.suspend).call(ctx).catch(function () {});
    });
  }

  // ---------- Musik ----------
  // Akkordfolgen (Halbtöne über dem Grundton) und Tonleiter für die Melodie; je Stimmung Tempo + Klang.
  var MOODS = {
    locker: { bpm: 96, root: 57, chords: [[0, 4, 7], [-3, 0, 4], [5, 9, 12], [7, 11, 14]], lead: 'triangle', hat: 0.5, swing: 0.08 },
    flott: { bpm: 126, root: 60, chords: [[0, 4, 7], [7, 11, 14], [-3, 0, 4], [5, 9, 12]], lead: 'square', hat: 1, swing: 0 },
    ruhig: { bpm: 70, root: 55, chords: [[0, 3, 7], [-4, 0, 3], [-2, 2, 5], [-5, -1, 2]], lead: 'sine', hat: 0, swing: 0.1 },
  };
  var mood = MOODS[MOOD] || MOODS.locker;
  var PENTA = mood === MOODS.ruhig ? [0, 3, 5, 7, 10] : [0, 2, 4, 7, 9];
  var music = null; // { ctx, out, timer, next, step, seed }

  function freq(note) { return 440 * Math.pow(2, (note - 69) / 12); }
  function rand(m) { m.seed = (m.seed * 1103515245 + 12345) & 0x7fffffff; return m.seed / 0x7fffffff; }

  function voice(m, type, note, at, length, gain, cutoff) {
    var ctx = m.ctx, osc = ctx.createOscillator(), amp = ctx.createGain(), filter = ctx.createBiquadFilter();
    osc.type = type; osc.frequency.value = freq(note);
    filter.type = 'lowpass'; filter.frequency.value = cutoff;
    amp.gain.setValueAtTime(0.0001, at);
    amp.gain.exponentialRampToValueAtTime(gain, at + Math.min(0.08, length / 3));
    amp.gain.exponentialRampToValueAtTime(0.0001, at + length);
    osc.connect(filter).connect(amp).connect(m.out);
    osc.start(at); osc.stop(at + length + 0.05);
  }
  function hat(m, at) {
    var ctx = m.ctx, len = Math.floor(ctx.sampleRate * 0.04), buffer = ctx.createBuffer(1, len, ctx.sampleRate), data = buffer.getChannelData(0);
    for (var i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len);
    var src = ctx.createBufferSource(), filter = ctx.createBiquadFilter(), amp = ctx.createGain();
    src.buffer = buffer; filter.type = 'highpass'; filter.frequency.value = 7000; amp.gain.value = 0.05 * mood.hat;
    src.connect(filter).connect(amp).connect(m.out); src.start(at);
  }

  // Ein Schritt = Achtelnote. 8 Schritte je Takt, ein Akkord je Takt, 4 Takte, dann neue Melodie.
  function schedule(m) {
    var beat = 60 / mood.bpm / 2;
    while (m.next < m.ctx.currentTime + 0.25) {
      var step = m.step, bar = Math.floor(step / 8) % mood.chords.length, inBar = step % 8;
      var chord = mood.chords[bar], at = m.next + (inBar % 2 ? beat * mood.swing : 0);
      if (step % 32 === 0) m.melody = makeMelody(m);
      if (inBar === 0) chord.forEach(function (n, i) { voice(m, 'triangle', mood.root + n, at, beat * 8, 0.035 - i * 0.006, 900); });
      if (inBar === 0 || inBar === 4 || (mood === MOODS.flott && inBar === 6)) voice(m, 'sine', mood.root - 12 + chord[0], at, beat * 1.6, 0.11, 400);
      var note = m.melody[step % 32];
      if (note != null) voice(m, mood.lead, mood.root + 12 + note, at, beat * (mood === MOODS.ruhig ? 2.4 : 1.1), mood.lead === 'square' ? 0.018 : 0.04, 2400);
      if (mood.hat && inBar % 2 === 1) hat(m, at);
      m.next += beat; m.step++;
    }
  }
  function makeMelody(m) {
    var notes = [], last = 2;
    for (var i = 0; i < 32; i++) {
      var rest = rand(m) < (mood === MOODS.ruhig ? 0.55 : 0.35) || (i % 8 === 7);
      if (rest) { notes.push(null); continue; }
      last = Math.max(0, Math.min(PENTA.length * 2 - 1, last + Math.round((rand(m) - 0.5) * 3)));
      notes.push(PENTA[last % PENTA.length] + 12 * Math.floor(last / PENTA.length) - 12);
    }
    return notes;
  }

  function startMusic() {
    if (!Native || music || !musik || !ton || document.hidden) return;
    var ctx;
    try { ctx = new Native(); } catch (e) { return; }
    var out = ctx.createGain(), comp = ctx.createDynamicsCompressor();
    out.gain.value = 0;
    out.connect(comp).connect(ctx.destination);
    out.gain.linearRampToValueAtTime(volume * 0.5, ctx.currentTime + 1.5); // sanft einblenden
    music = { ctx: ctx, out: out, next: ctx.currentTime + 0.1, step: 0, seed: (Date.now() & 0xffff) + 1, melody: [] };
    var m = music;
    m.timer = setInterval(function () { if (m.ctx.state === 'running') schedule(m); }, 60);
    if (ctx.state === 'suspended') ctx.resume().catch(function () {});
  }
  function stopMusic() {
    if (!music) return;
    var m = music; music = null;
    clearInterval(m.timer);
    try {
      m.out.gain.cancelScheduledValues(m.ctx.currentTime);
      m.out.gain.setValueAtTime(m.out.gain.value, m.ctx.currentTime);
      m.out.gain.linearRampToValueAtTime(0, m.ctx.currentTime + 0.3);
    } catch (e) { /* schon zu */ }
    setTimeout(function () { m.ctx.close().catch(function () {}); }, 400);
  }
  function syncMusic() { if (musik && ton && !document.hidden) startMusic(); else stopMusic(); }

  // Browser erlauben Ton erst nach einer Eingabe → beim ersten Klick/Tastendruck starten
  ['pointerdown', 'keydown', 'touchstart'].forEach(function (type) {
    window.addEventListener(type, function () {
      if (music && music.ctx.state === 'suspended') music.ctx.resume().catch(function () {});
      else syncMusic();
    }, { capture: true, passive: true });
  });
  document.addEventListener('visibilitychange', syncMusic);

  // ---------- Knöpfe (Shadow DOM, damit sich Spiel und Knöpfe nicht gegenseitig umstylen) ----------
  var ICONS = {
    tonAn: '<path d="M4 9v6h4l5 4V5L8 9z"/><path class="w" d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12"/>',
    tonAus: '<path d="M4 9v6h4l5 4V5L8 9z"/><path class="w" d="M16.5 9.5l5 5M21.5 9.5l-5 5"/>',
    musik: '<path d="M9 18V6l11-2.5v12"/><circle cx="6.5" cy="18" r="2.8"/><circle cx="17.5" cy="15.5" r="2.8"/>',
  };
  var host, buttons = {};
  function mount() {
    host = document.createElement('div');
    host.id = 'spiele-ton';
    var pos = CORNER.split('-');
    host.style.cssText = 'position:fixed;z-index:2147483000;' + (pos[0] === 'oben' ? 'top' : 'bottom') + ':' + OFFSET + ';' + (pos[1] === 'rechts' ? 'right' : 'left') + ':12px;';
    var root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;
    root.innerHTML = '<style>' +
      ':host{all:initial}' +
      '.bar{display:flex;gap:8px;font:700 13px/1 system-ui,sans-serif}' +
      'button{all:unset;box-sizing:border-box;width:40px;height:40px;display:grid;place-items:center;cursor:pointer;border-radius:50%;' +
      'background:#fff7ea;color:#120f24;border:3px solid #120f24;box-shadow:3px 3px 0 #120f24;transition:transform .12s,box-shadow .12s,background .15s;-webkit-tap-highlight-color:transparent}' +
      'button:hover{transform:translate(-1px,-1px);box-shadow:4px 4px 0 #120f24}' +
      'button:active{transform:translate(2px,2px);box-shadow:1px 1px 0 #120f24}' +
      'button:focus-visible{outline:3px solid #3ad0ff;outline-offset:2px}' +
      'button[aria-pressed="true"]{background:#ffd23f}' +
      'button.musik[aria-pressed="true"]{background:#2fd27a}' +
      'button.musik[aria-pressed="true"] svg{animation:wipp 1s ease-in-out infinite}' +
      'button[disabled]{opacity:.45;cursor:default}' +
      'svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round}' +
      '@keyframes wipp{0%,100%{transform:rotate(-8deg)}50%{transform:rotate(8deg) translateY(-1px)}}' +
      '@media (prefers-reduced-motion:reduce){button,button svg{animation:none!important;transition:none!important}}' +
      '@media (max-width:600px){button{width:34px;height:34px;border-width:2.5px}svg{width:17px;height:17px}}' +
      '</style><div class="bar" role="group" aria-label="Ton und Musik"></div>';
    var bar = root.querySelector('.bar');
    if (WITH_TON) {
      buttons.ton = make('ton', function () { setTon(!ton); });
      bar.appendChild(buttons.ton);
    }
    buttons.musik = make('musik', function () { setMusik(!musik); });
    bar.appendChild(buttons.musik);
    (document.body || document.documentElement).appendChild(host);
    render();
  }
  function make(cls, onClick) {
    var b = document.createElement('button');
    b.type = 'button'; b.className = cls;
    b.addEventListener('click', function (e) { e.stopPropagation(); onClick(); b.blur(); });
    // Klicks/Tasten auf den Knöpfen sollen im Spiel nichts auslösen (z.B. Schießen, Zeichnen)
    ['pointerdown', 'mousedown', 'touchstart', 'keydown'].forEach(function (t) { b.addEventListener(t, function (e) { e.stopPropagation(); }); });
    return b;
  }
  function render() {
    if (buttons.ton) {
      buttons.ton.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + (ton ? ICONS.tonAn : ICONS.tonAus) + '</svg>';
      buttons.ton.setAttribute('aria-pressed', String(ton));
      buttons.ton.title = ton ? 'Ton aus (alle Töne der Seite)' : 'Ton an';
      buttons.ton.setAttribute('aria-label', buttons.ton.title);
    }
    var m = buttons.musik;
    m.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + ICONS.musik + '</svg>';
    m.setAttribute('aria-pressed', String(musik && ton));
    m.disabled = !ton;
    m.title = !ton ? 'Musik (erst Ton anschalten)' : musik ? 'Musik aus' : 'Musik an';
    m.setAttribute('aria-label', m.title);
  }

  function emit() { render(); listeners.forEach(function (fn) { try { fn({ ton: ton, musik: musik }); } catch (e) { /* egal */ } }); }
  function setTon(on) { ton = !!on; store.set('spiele.ton', ton ? '1' : '0'); applyTon(); syncMusic(); emit(); }
  function setMusik(on) { musik = !!on; store.set('spiele.musik', musik ? '1' : '0'); syncMusic(); emit(); }
  // Anderer Tab ändert die Einstellung → hier übernehmen
  window.addEventListener('storage', function (e) {
    if (e.key === 'spiele.ton') { ton = e.newValue !== '0'; applyTon(); syncMusic(); emit(); }
    if (e.key === 'spiele.musik') { musik = e.newValue === '1'; syncMusic(); emit(); }
  });

  window.SpieleTon = {
    get ton() { return ton; },
    get musik() { return musik; },
    setTon: setTon,
    setMusik: setMusik,
    onChange: function (fn) { listeners.push(fn); },
  };
  if (document.body) mount(); else document.addEventListener('DOMContentLoaded', mount);
})();
