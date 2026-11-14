/* Geheimfunk – Wort-Ratespiel der Stats-Website. Server: web/WordGame.kt (/api/geheimfunk, Stand per SSE, Züge per POST). */
(() => {
  'use strict';

  const API = '/api/geheimfunk';
  const AVATARS = ['🦊', '🐸', '🐙', '🦄', '🐼', '🐧', '🦖', '🐝', '🦥', '🐷', '🐵', '🦉', '🐢', '🦈', '🐔', '🦝', '🐌', '🦀', '👽', '🤖', '👻', '🎃', '🧙', '🥷'];
  const REACTIONS = ['😂', '💀', '🤡', '🧠', '🔥', '👀', '🫡', '😱'];
  const TEAM = {
    a: { name: 'Flamingo', icon: '🦩' },
    b: { name: 'Lagune', icon: '🐬' },
  };
  const STAMP = { a: '🦩', b: '🐬', n: '🤷', x: '💣' };

  // Sprüche zu dem, was gerade passiert ist (zufällig, nur Deko)
  const LINES = {
    own: ['Sauber.', 'Big Brain. 🧠', 'Der Funk steht.', 'Läuft bei euch.', 'Gedankenübertragung!', 'Und noch einer!'],
    enemy: ['Danke für das Geschenk. 🎁', 'Das war für die anderen.', 'Team-Work. Nur fürs falsche Team.', 'Autsch.'],
    neutral: ['Der wollte nur einkaufen. 🤷', 'Unbeteiligter Passant.', 'Knapp daneben ist auch vorbei.', 'Kein Anschluss unter diesem Wort.'],
    bomb: ['💥 BUMM.', 'Das war die Bombe. Wortwörtlich.', 'Wer hat DAS denn gefunkt?!', 'Schuld ist immer der Funker.'],
    win: ['GG!', 'Funkstille bei den anderen.', 'Meisterspione.', 'So sehen Sieger aus.'],
  };

  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  };
  const pick = (list) => list[Math.floor(Math.random() * list.length)];
  const other = (team) => (team === 'a' ? 'b' : 'a');
  const store = {
    get(key) { try { return localStorage.getItem(key); } catch (e) { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* privat */ } },
    del(key) { try { localStorage.removeItem(key); } catch (e) { /* privat */ } },
  };

  let room = '';
  let token = '';
  let state = null;
  let source = null;
  let boardKey = '';
  let seenLog = -1; // -1 = noch kein Stand dieser Runde gesehen (keine Sprüche für Altes)
  let seenRound = -1;
  let avatar = Number(store.get('gf:avatar') ?? Math.floor(Math.random() * AVATARS.length)) || 0;
  let clueCount = 1;
  let kickArmed = '';

  // ---------- Server ----------

  async function post(path, body) {
    let response;
    try {
      response = await fetch(API + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    } catch (e) {
      return { error: 'Keine Verbindung zum Server.' };
    }
    let data = null;
    try { data = await response.json(); } catch (e) { /* kein JSON */ }
    if (!response.ok) return { error: (data && data.error) || 'Fehler ' + response.status, gone: data && data.gone };
    return data || {};
  }

  async function act(what, more) {
    const result = await post('/act', Object.assign({ room, token, do: what }, more || {}));
    if (result.error) {
      toast(result.error, 'bad');
      if (result.gone) leaveLocal();
    }
    return !result.error;
  }

  function connect() {
    if (source) source.close();
    source = new EventSource(`${API}/stream?room=${encodeURIComponent(room)}&token=${encodeURIComponent(token)}`);
    source.addEventListener('state', (event) => {
      document.body.classList.remove('offline');
      render(JSON.parse(event.data));
    });
    source.addEventListener('fx', (event) => reaction(JSON.parse(event.data)));
    source.addEventListener('bye', () => {
      toast('Du bist nicht mehr in diesem Raum.', 'bad');
      leaveLocal();
    });
    source.onerror = async () => {
      document.body.classList.add('offline');
      if (source && source.readyState === EventSource.CLOSED) {
        // endgültig zu: gibt es den Raum/Platz noch?
        const check = await post('/join', { room, token });
        if (check.gone || check.error) {
          toast(check.gone ? 'Diesen Raum gibt es nicht mehr.' : check.error, 'bad');
          leaveLocal();
        } else {
          setTimeout(connect, 1500);
        }
      }
    };
  }

  function enter(code, playerToken) {
    room = code;
    token = playerToken;
    store.set('gf:' + room, token);
    history.replaceState(null, '', '#' + room);
    $('start').hidden = true;
    $('game').hidden = false;
    $('room-code').textContent = room;
    boardKey = '';
    seenLog = -1;
    connect();
  }

  function leaveLocal() {
    if (source) source.close();
    source = null;
    if (room) store.del('gf:' + room);
    room = '';
    token = '';
    state = null;
    history.replaceState(null, '', location.pathname);
    $('game').hidden = true;
    showStart('');
  }

  // ---------- Start ----------

  function showStart(code) {
    $('start').hidden = false;
    $('name').value = store.get('gf:name') || '';
    $('code').value = code || '';
    const box = $('avatars');
    box.textContent = '';
    AVATARS.forEach((emoji, index) => {
      const button = el('button', 'avatar' + (index === avatar ? ' on' : ''), emoji);
      button.type = 'button';
      button.setAttribute('role', 'radio');
      button.setAttribute('aria-checked', index === avatar);
      button.addEventListener('click', () => {
        avatar = index;
        store.set('gf:avatar', String(index));
        showStartAvatars();
      });
      box.append(button);
    });
    if (code) $('name').focus();
  }

  function showStartAvatars() {
    [...$('avatars').children].forEach((button, index) => {
      button.classList.toggle('on', index === avatar);
      button.setAttribute('aria-checked', index === avatar);
    });
  }

  async function begin(join) {
    const name = $('name').value.trim();
    const code = $('code').value.trim().toUpperCase();
    $('start-error').textContent = '';
    if (!name) return ($('start-error').textContent = 'Erst einen Namen, Agent.');
    if (join && code.length !== 4) return ($('start-error').textContent = 'Der Raum-Code hat vier Buchstaben.');
    store.set('gf:name', name);
    const result = join ? await post('/join', { room: code, name, avatar }) : await post('/create', { name, avatar });
    if (result.error) return ($('start-error').textContent = result.error);
    enter(result.room, result.token);
  }

  $('start-form').addEventListener('submit', (event) => {
    event.preventDefault();
    begin($('code').value.trim().length > 0);
  });
  $('join').addEventListener('click', () => begin(true));
  $('code').addEventListener('input', () => {
    $('code').value = $('code').value.toUpperCase().replace(/[^A-Z]/g, '');
    $('create').textContent = $('code').value ? 'Beitreten' : 'Raum aufmachen';
    $('join').hidden = !!$('code').value;
  });

  // ---------- Darstellung ----------

  function render(next) {
    const first = state == null;
    state = next;
    const me = state.players.find((p) => p.id === state.you) || null;
    const playing = state.phase === 'clue' || state.phase === 'guess';
    document.body.dataset.phase = state.phase;
    document.body.dataset.turn = playing ? state.turn : state.phase === 'over' ? state.winner : '';
    $('abort').hidden = !(playing && state.host === state.you);

    renderTeam('a', me);
    renderTeam('b', me);
    renderWatchers(me);
    renderStatus(me);
    renderLobby(me);
    renderBoard(me);
    renderClueBar(me);
    renderLog(first);
  }

  function playerChip(p, me) {
    const chip = el('span', 'player' + (p.online ? '' : ' off') + (p.id === state.you ? ' me' : ''));
    chip.append(el('span', 'face', AVATARS[p.avatar] || '🙂'), el('span', 'pname', p.name));
    if (p.id === state.host) chip.append(el('span', 'crown', '👑'));
    if (me && state.host === me.id && p.id !== me.id) {
      const kick = el('button', 'kick', kickArmed === p.id ? 'sicher?' : '×');
      kick.title = 'Rauswerfen';
      kick.addEventListener('click', () => {
        if (kickArmed === p.id) {
          kickArmed = '';
          act('kick', { id: p.id });
        } else {
          kickArmed = p.id;
          render(state);
          setTimeout(() => { if (kickArmed === p.id) { kickArmed = ''; if (state) render(state); } }, 2500);
        }
      });
      chip.append(kick);
    }
    return chip;
  }

  function renderTeam(team, me) {
    const box = $('team-' + team);
    const info = TEAM[team];
    const playing = state.phase === 'clue' || state.phase === 'guess';
    box.textContent = '';
    box.classList.toggle('active', playing && state.turn === team);
    box.classList.toggle('won', state.phase === 'over' && state.winner === team);

    const head = el('div', 'team-head');
    head.append(el('span', 'team-icon', info.icon), el('span', 'team-name', 'Team ' + info.name));
    if (state.wins[team] > 0) head.append(el('span', 'wins', '🏆 ' + state.wins[team]));
    box.append(head);

    if (state.phase !== 'lobby') {
      const count = el('div', 'count');
      const number = el('b', null, String(state.left[team]));
      number.dataset.n = state.left[team];
      count.append(number, el('span', null, state.left[team] === 1 ? 'Wort fehlt' : 'Wörter fehlen'));
      box.append(count);
    }

    for (const [role, label, icon] of [['chief', 'Funker', '📡'], ['agent', 'Spürnasen', '🔍']]) {
      const group = el('div', 'group');
      group.append(el('h3', null, icon + ' ' + label));
      const list = el('div', 'players');
      const members = state.players.filter((p) => p.team === team && p.role === role);
      members.forEach((p) => list.append(playerChip(p, me)));
      if (!members.length) list.append(el('span', 'empty', role === 'chief' ? 'niemand am Funkgerät' : 'noch keiner'));
      group.append(list);
      const mine = me && me.team === team && me.role === role;
      if (!mine) {
        const sit = el('button', 'sit', role === 'chief' ? 'Ich funke' : 'Ich rate');
        sit.addEventListener('click', () => act('seat', { team, role }));
        group.append(sit);
      }
      box.append(group);
    }
  }

  function renderWatchers(me) {
    const box = $('watchers');
    box.textContent = '';
    const list = state.players.filter((p) => !p.team);
    if (!list.length && (!me || !me.team)) return;
    box.append(el('h3', null, '🍿 Zuschauer'));
    const row = el('div', 'players');
    list.forEach((p) => row.append(playerChip(p, me)));
    box.append(row);
    if (me && me.team) {
      const sit = el('button', 'sit', 'Nur zuschauen');
      sit.addEventListener('click', () => act('seat', { team: '', role: 'agent' }));
      box.append(sit);
    }
  }

  function renderStatus(me) {
    const box = $('status');
    const turn = TEAM[state.turn];
    let text = '';
    let mine = false;
    if (state.phase === 'lobby') {
      text = 'Sucht euch ein Team aus.';
    } else if (state.phase === 'clue') {
      mine = !!me && me.team === state.turn && me.role === 'chief';
      text = mine ? 'Du bist dran: Funk ein Wort und eine Zahl.' : `${turn.icon} Team ${turn.name}: der Funker grübelt`;
    } else if (state.phase === 'guess') {
      mine = !!me && me.team === state.turn && me.role === 'agent';
      text = mine ? 'Ihr seid dran: Karte antippen, dann mit ✓ aufdecken.' : `${turn.icon} Team ${turn.name} rät`;
    } else {
      const winner = TEAM[state.winner];
      const why = { bomb: 'Die anderen haben die Bombe erwischt.', all: 'Alle Wörter gefunden.', gift: 'Das letzte Wort kam von den Gegnern.' }[state.reason] || '';
      text = `${winner.icon} Team ${winner.name} gewinnt! ${why}`;
    }
    box.textContent = text;
    box.classList.toggle('mine', mine);
    box.classList.toggle('dots', state.phase === 'clue' && !mine);
  }

  // ----- Lobby -----

  let packInfo = null;
  fetch(API).then((r) => r.json()).then((data) => { packInfo = data.packs; if (state) render(state); }).catch(() => {});

  function renderLobby(me) {
    const box = $('lobby');
    const show = state.phase === 'lobby';
    box.hidden = !show;
    if (!show) return;
    const host = me && state.host === me.id;
    // Eingaben des Hosts nicht bei jedem Stand neu bauen (Cursor im Textfeld)
    const key = `${host}|${state.packs.join()}|${state.customCount}|${state.pool}|${state.problem}|${packInfo ? 1 : 0}`;
    if (box.dataset.key === key) return;
    const typing = document.activeElement && document.activeElement.id === 'custom';
    if (typing && box.dataset.host === String(host)) {
      box.dataset.key = key;
      updateLobbyFacts();
      return;
    }
    box.dataset.key = key;
    box.dataset.host = String(host);
    box.textContent = '';

    box.append(el('h2', null, 'Raum ' + state.room));
    box.append(el('p', 'muted', 'Schick den Link rum (Klick oben auf den Raum-Code). Jedes Team braucht einen Funker und mindestens eine Spürnase.'));

    const packs = el('div', 'packs');
    (packInfo || []).forEach((pack) => {
      const label = el('label', 'pack' + (state.packs.includes(pack.id) ? ' on' : ''));
      const input = el('input');
      input.type = 'checkbox';
      input.checked = state.packs.includes(pack.id);
      input.disabled = !host;
      input.addEventListener('change', () => {
        const chosen = (packInfo || []).map((p) => p.id).filter((id) => (id === pack.id ? input.checked : state.packs.includes(id)));
        act('settings', { packs: chosen });
      });
      const text = el('span');
      text.append(el('b', null, pack.name), el('small', null, `${pack.about} · ${pack.count} Wörter`));
      label.append(input, text);
      packs.append(label);
    });
    box.append(packs);

    if (host) {
      const field = el('label', 'field');
      field.append(el('span', null, 'Eigene Wörter (Insider, Namen, Server-Memes), mit Komma getrennt'));
      const area = el('textarea');
      area.id = 'custom';
      area.rows = 2;
      area.maxLength = 6000;
      area.placeholder = 'z.B. Kilian, Reaktor, Montagsrunde …';
      area.value = state.custom || '';
      let timer = 0;
      area.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => act('settings', { packs: state.packs, custom: area.value }), 600);
      });
      field.append(area);
      box.append(field);
    }

    const facts = el('p', 'facts');
    facts.id = 'lobby-facts';
    box.append(facts);

    const row = el('div', 'lobby-actions');
    if (host) {
      const shuffle = el('button', 'btn btn-white', '🎲 Teams mischen');
      shuffle.addEventListener('click', () => act('shuffle'));
      const start = el('button', 'btn btn-yellow', 'Runde starten');
      start.id = 'start-round';
      start.addEventListener('click', () => act('start'));
      row.append(shuffle, start);
    } else {
      row.append(el('p', 'muted', 'Der Host startet die Runde.'));
    }
    box.append(row);
    updateLobbyFacts();
  }

  function updateLobbyFacts() {
    const facts = $('lobby-facts');
    if (!facts) return;
    const own = state.customCount ? ` (davon ${state.customCount} eigene)` : '';
    facts.textContent = `${state.pool} Wörter im Topf${own}. ` + (state.problem || 'Alles bereit.');
    facts.classList.toggle('ready', !state.problem);
    const start = $('start-round');
    if (start) start.disabled = !!state.problem;
  }

  // ----- Brett -----

  function renderBoard(me) {
    const board = $('board');
    const show = state.phase !== 'lobby';
    board.hidden = !show;
    if (!show) {
      boardKey = '';
      board.textContent = '';
      return;
    }
    const key = state.round + ':' + state.cards.map((c) => c.w).join('|');
    const fresh = key !== boardKey;
    if (fresh) {
      boardKey = key;
      board.textContent = '';
      state.cards.forEach((card, index) => {
        const node = el('div', 'card');
        node.tabIndex = 0;
        node.setAttribute('role', 'button');
        node.style.setProperty('--i', index);
        node.style.setProperty('--tilt', ((index * 37) % 7 - 3) * 0.35 + 'deg');
        const inner = el('div', 'card-in');
        const front = el('div', 'side front');
        const word = el('span', 'word' + (card.w.length > 12 ? ' xl' : card.w.length > 8 ? ' l' : ''), card.w);
        front.append(el('div', 'tips'), word);
        const back = el('div', 'side back');
        back.append(el('span', 'stamp'), el('span', 'word small', card.w));
        inner.append(front, back);
        const go = el('button', 'go', '✓');
        go.title = 'Aufdecken';
        go.addEventListener('click', (event) => {
          event.stopPropagation();
          act('reveal', { i: index });
        });
        node.append(inner, go);
        const tap = () => {
          if (node.classList.contains('can')) act('tip', { i: index });
        };
        node.addEventListener('click', tap);
        node.addEventListener('keydown', (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            if (node.classList.contains('tipped-me')) act('reveal', { i: index });
            else tap();
          }
        });
        board.append(node);
      });
    }
    const guessing = state.phase === 'guess' && me && me.team === state.turn && me.role === 'agent';
    state.cards.forEach((card, index) => {
      const node = board.children[index];
      const wasRevealed = node.classList.contains('revealed');
      node.classList.toggle('revealed', card.r);
      node.classList.toggle('can', !!guessing && !card.r);
      for (const color of ['a', 'b', 'n', 'x']) {
        node.classList.toggle('is-' + color, card.r && card.c === color);
        node.classList.toggle('key-' + color, !card.r && card.c === color);
      }
      if (card.r) node.querySelector('.stamp').textContent = STAMP[card.c] || '';
      if (card.r && !wasRevealed && !fresh) {
        node.classList.add('pop');
        setTimeout(() => node.classList.remove('pop'), 900);
      }
      node.classList.toggle('tipped-me', !card.r && card.tips.includes(state.you));
      node.setAttribute('aria-label', card.w + (card.r ? ', aufgedeckt' : ''));
      const tips = node.querySelector('.tips');
      const tipKey = card.tips.join();
      if (tips.dataset.key !== tipKey) {
        tips.dataset.key = tipKey;
        tips.textContent = '';
        card.tips.forEach((id) => {
          const p = state.players.find((x) => x.id === id);
          if (!p) return;
          const tip = el('span', 'tip', AVATARS[p.avatar] || '🙂');
          tip.title = p.name;
          tips.append(tip);
        });
      }
    });
  }

  // ----- Hinweis-Leiste -----

  function renderClueBar(me) {
    const bar = $('cluebar');
    const show = state.phase !== 'lobby';
    bar.hidden = !show;
    if (!show) return;
    const mode = state.phase === 'clue' && me && me.team === state.turn && me.role === 'chief' ? 'form' : state.phase;
    const key = mode + '|' + (state.clue ? state.clue.word + state.clue.n : '') + '|' + state.guesses + '|' + (me ? me.team + me.role : '') +
      '|' + (state.host === state.you) + '|' + state.problem;
    if (bar.dataset.key === key) return;
    bar.dataset.key = key;
    bar.dataset.mode = mode;
    bar.textContent = '';

    if (mode === 'form') {
      const form = el('form', 'clue-form');
      const input = el('input', 'clue-input');
      input.maxLength = 24;
      input.placeholder = 'DEIN HINWEIS';
      input.autocomplete = 'off';
      input.spellcheck = false;
      const numbers = el('div', 'numbers');
      numbers.setAttribute('role', 'radiogroup');
      numbers.setAttribute('aria-label', 'Anzahl der Wörter');
      [1, 2, 3, 4, 5, 6, 7, 8, 9, 0, -1].forEach((n) => {
        const button = el('button', 'num' + (n === clueCount ? ' on' : ''), n === -1 ? '∞' : String(n));
        button.type = 'button';
        button.addEventListener('click', () => {
          clueCount = n;
          [...numbers.children].forEach((b) => b.classList.toggle('on', b === button));
        });
        numbers.append(button);
      });
      const send = el('button', 'btn btn-yellow', 'Funken 📡');
      send.type = 'submit';
      form.append(input, numbers, send);
      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const word = input.value.trim();
        if (!word) return input.focus();
        if (await act('clue', { word, n: clueCount })) clueCount = 1;
      });
      bar.append(form);
      setTimeout(() => input.focus(), 50);
      return;
    }

    if (state.phase === 'clue') {
      bar.append(el('div', 'clue-wait', 'Funkstille … gleich kommt der Hinweis'));
      return;
    }

    if (state.phase === 'guess' && state.clue) {
      const show = el('div', 'clue-show');
      show.append(el('span', 'clue-word', state.clue.word), el('span', 'clue-n', state.clue.n === -1 ? '∞' : String(state.clue.n)));
      bar.append(show);
      const info = el('div', 'clue-info');
      info.append(el('span', null, state.guesses === -1 ? 'beliebig viele Versuche' : state.guesses === 1 ? 'noch 1 Versuch' : `noch ${state.guesses} Versuche`));
      if (me && me.team === state.turn && me.role === 'agent') {
        const pass = el('button', 'btn btn-white small', 'Zug beenden');
        pass.addEventListener('click', () => act('pass'));
        info.append(pass);
      }
      bar.append(info);
      return;
    }

    if (state.phase === 'over') {
      const row = el('div', 'over-row');
      if (state.host === state.you) {
        const again = el('button', 'btn btn-yellow', 'Nochmal!');
        again.disabled = !!state.problem;
        again.title = state.problem || '';
        again.addEventListener('click', () => act('start'));
        const lobby = el('button', 'btn btn-white', 'Teams neu aufstellen');
        lobby.addEventListener('click', () => act('lobby'));
        row.append(again, lobby);
      } else {
        row.append(el('div', 'clue-wait', 'Der Host startet die nächste Runde.'));
      }
      bar.append(row);
    }
  }

  // ----- Protokoll + Sprüche -----

  function logLine(entry) {
    const line = el('li', 'log-' + entry.k + (entry.team ? ' t-' + entry.team : ''));
    const team = entry.team ? TEAM[entry.team] : null;
    const strong = (text, cls) => el('b', cls, text);
    switch (entry.k) {
      case 'start':
        line.append(`Runde ${entry.round}: `, strong(`Team ${team.name}`), ' fängt an und hat ein Wort mehr.');
        break;
      case 'clue':
        line.append('📡 ', strong(entry.name), ' funkt ', strong(entry.word, 'tag'), ' ', strong(entry.n === -1 ? '∞' : String(entry.n), 'tag n'));
        break;
      case 'guess': {
        const result = entry.color === 'x' ? '💣 Bombe!' : entry.color === 'n' ? '🤷 Passant' : entry.color === entry.team ? '✔ richtig' :
          `${TEAM[entry.color].icon} für die anderen`;
        line.append(strong(entry.name), ' deckt ', strong(entry.word, 'tag c-' + entry.color), ' auf: ', result);
        break;
      }
      case 'pass':
        line.append(strong(entry.name), ' beendet den Zug.');
        break;
      case 'turn':
        line.append(`${team.icon} `, strong(`Team ${team.name}`), ' ist dran.');
        break;
      case 'chief':
        line.append(strong(entry.name), ' übernimmt das Funkgerät.');
        break;
      case 'end':
        line.append('🏆 ', strong(`Team ${team.name}`), ' gewinnt!');
        break;
      default:
        return null;
    }
    return line;
  }

  function renderLog(first) {
    const list = $('log');
    if (state.round !== seenRound) {
      seenRound = state.round;
      seenLog = first ? -1 : 0;
    }
    list.textContent = '';
    state.log.forEach((entry) => {
      const line = logLine(entry);
      if (line) list.append(line);
    });
    list.scrollTop = list.scrollHeight;

    if (seenLog >= 0 && state.log.length > seenLog) state.log.slice(seenLog).forEach(announce);
    seenLog = state.phase === 'lobby' ? 0 : state.log.length;
  }

  function announce(entry) {
    if (entry.k === 'guess') {
      if (entry.color === 'x') {
        toast(pick(LINES.bomb), 'bomb');
        document.body.classList.add('shake');
        setTimeout(() => document.body.classList.remove('shake'), 700);
      } else if (entry.color === 'n') toast(pick(LINES.neutral), 'n');
      else if (entry.color === entry.team) toast(pick(LINES.own), entry.team);
      else toast(pick(LINES.enemy), entry.color);
    } else if (entry.k === 'clue') {
      toast(`📡 ${entry.word} · ${entry.n === -1 ? '∞' : entry.n}`, entry.team);
    } else if (entry.k === 'end') {
      toast(`🏆 Team ${TEAM[entry.team].name}: ${pick(LINES.win)}`, entry.team);
      confetti(entry.team);
    }
  }

  function toast(text, tone) {
    const box = $('toasts');
    const node = el('div', 'toast t-' + (tone || 'n'), text);
    box.append(node);
    while (box.children.length > 3) box.firstChild.remove();
    setTimeout(() => node.classList.add('out'), 2600);
    setTimeout(() => node.remove(), 3100);
  }

  // ----- Effekte -----

  function reaction(data) {
    const layer = $('fx');
    const node = el('div', 'emote');
    node.append(el('span', 'emote-e', data.emoji), el('span', 'emote-n t-' + (data.team || 'n'), data.name));
    node.style.left = 8 + Math.random() * 84 + '%';
    node.style.setProperty('--sway', (Math.random() * 80 - 40).toFixed(0) + 'px');
    layer.append(node);
    setTimeout(() => node.remove(), 2600);
  }

  function confetti(team) {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const layer = $('fx');
    const colors = team === 'a' ? ['#ff4d8d', '#ffd23f', '#ffffff', '#ff9ec2'] : ['#19c8c8', '#ffd23f', '#ffffff', '#9af0ea'];
    for (let i = 0; i < 90; i++) {
      const bit = el('i', 'confetti');
      bit.style.left = Math.random() * 100 + '%';
      bit.style.background = pick(colors);
      bit.style.setProperty('--fall', (1.6 + Math.random() * 1.8).toFixed(2) + 's');
      bit.style.setProperty('--drift', (Math.random() * 240 - 120).toFixed(0) + 'px');
      bit.style.setProperty('--spin', (Math.random() * 900 - 450).toFixed(0) + 'deg');
      bit.style.animationDelay = (Math.random() * 0.5).toFixed(2) + 's';
      layer.append(bit);
      setTimeout(() => bit.remove(), 4200);
    }
  }

  // ---------- Kopfzeile ----------

  REACTIONS.forEach((emoji) => {
    const button = el('button', 'react', emoji);
    button.addEventListener('click', () => act('react', { emoji }));
    $('reactions').append(button);
  });

  $('share').addEventListener('click', async () => {
    const link = location.origin + location.pathname + '#' + room;
    try {
      await navigator.clipboard.writeText(link);
      toast('Link kopiert. Ab in den Chat damit.', 'n');
    } catch (e) {
      toast(link, 'n');
    }
  });
  $('leave').addEventListener('click', async () => {
    await post('/act', { room, token, do: 'leave' });
    leaveLocal();
  });
  $('abort').addEventListener('click', () => act('lobby'));

  // ---------- Los ----------

  (async () => {
    const code = (location.hash || '').replace('#', '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 4);
    const saved = code ? store.get('gf:' + code) : null;
    if (code && saved) {
      const back = await post('/join', { room: code, token: saved });
      if (!back.error && back.token === saved) return enter(code, saved);
      store.del('gf:' + code);
    }
    showStart(code);
    $('code').dispatchEvent(new Event('input'));
  })();
})();
