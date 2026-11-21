/*
 * Discord-Name in den Browserspielen (21.11.2026).
 * Wer auf der Website mit Discord angemeldet ist, spielt unter seinem Discord-Namen (das Namensfeld wird ausgefüllt
 * und gesperrt), Raum eröffnen geht nur angemeldet. Der Server prüft beides selbst (GameLogin.kt) – das hier ist
 * nur die Anzeige. Fehlt die Datei (alte JAR), laufen die Seiten wie vorher.
 *
 *   DcName.ready        Promise → {loginEnabled, loggedIn, name}, spätestens nach 1,5 s
 *   DcName.bind(input, {host, after})   Feld ausfüllen/sperren + Hinweis darunter (nach `after`, sonst nach dem Feld)
 *   DcName.isLoginError(antwort)        true, wenn der Server „bitte anmelden“ sagt
 *   DcName.loginUrl()                   Anmelden mit Rücksprung auf genau diese Seite
 */
(function () {
  'use strict';
  var state = { loginEnabled: false, loggedIn: false, name: null };
  var finish;
  var ready = new Promise(function (resolve) { finish = function () { resolve(state); }; });
  setTimeout(finish, 1500);
  try {
    fetch('/api/spielname', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (d && typeof d === 'object') {
          state.loginEnabled = d.loginEnabled === true;
          state.loggedIn = d.loggedIn === true && typeof d.name === 'string' && d.name.length > 0;
          state.name = state.loggedIn ? d.name : null;
        }
      })
      .catch(function () {})
      .then(finish);
  } catch (e) { finish(); }

  function loginUrl() {
    var back = location.pathname + location.search + location.hash;
    return '/auth/login?back=' + encodeURIComponent(back.replace(/([?&])login=[^&#]*&?/, '$1').replace(/[?&]$/, ''));
  }

  function note(input, after, text, linkText) {
    var box = document.createElement('div');
    box.className = 'dcname-note';
    box.style.fontSize = '.82em';
    box.style.lineHeight = '1.35';
    box.style.margin = '.4em 0 0';
    box.style.opacity = '.9';
    box.style.textAlign = 'inherit';
    var span = document.createElement('span');
    span.textContent = text;
    box.appendChild(span);
    if (linkText) {
      var a = document.createElement('a');
      a.href = loginUrl();
      a.textContent = linkText;
      a.style.fontWeight = '700';
      a.style.color = 'inherit';
      a.style.textDecoration = 'underline';
      a.style.marginLeft = '.35em';
      box.appendChild(a);
    }
    var anchor = after || input;
    if (anchor && anchor.parentNode) anchor.parentNode.insertBefore(box, anchor.nextSibling);
    // verschwindet das Feld (z.B. im Raum), verschwindet der Hinweis mit
    var sync = function () { box.hidden = !!anchor.hidden; };
    sync();
    if (window.MutationObserver) new MutationObserver(sync).observe(anchor, { attributes: true, attributeFilter: ['hidden'] });
    return box;
  }

  function bind(input, opts) {
    opts = opts || {};
    return ready.then(function (s) {
      if (!input) return s;
      if (s.loggedIn) {
        input.value = s.name;
        input.readOnly = true;
        input.setAttribute('aria-readonly', 'true');
        input.title = 'Name aus deinem Discord-Konto';
        input.style.cursor = 'default';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        note(input, opts.after, 'Name kommt von deinem Discord-Konto.', null);
      } else if (s.loginEnabled) {
        note(input, opts.after,
          opts.host ? 'Raum eröffnen geht nur mit Discord-Anmeldung, beitreten auch so.' : 'Angemeldet wird dein Discord-Name genommen.',
          'Mit Discord anmelden');
      }
      return s;
    });
  }

  window.DcName = {
    ready: ready,
    state: state,
    bind: bind,
    loginUrl: loginUrl,
    isLoginError: function (data) { return !!(data && data.login === true); },
  };
})();
