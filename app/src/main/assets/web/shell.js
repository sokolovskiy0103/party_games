// Shell shared by every game: connection, joining, the room lobby, game picker, invite QR,
// network switch (Wi-Fi / Internet relay). Games register themselves with PG.register().
//
// Pages: /host#<token> in the host app's WebView, / for players on local Wi-Fi,
// /r/<CODE>/ for players coming through the Render relay.
(function () {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (_) { return null; } },
    set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (_) {} },
  };

  const base = location.pathname.replace(/[^/]*$/, '');
  const isHostPage = location.pathname === '/host';
  const token = isHostPage ? location.hash.slice(1) : '';
  // ids are per server: a relay room and the local server are different rooms
  const K = 'pg.' + (isHostPage ? 'host' : base) + '.';

  const games = {};
  let pid = store.get(K + 'pid');
  let st = null, prev = null;
  let joining = false;
  let clockOffset = 0;
  let menuOpen = false;
  let showInvite = false;
  let rulesOpen = false;
  let showRelayUrl = false;
  let bigQr = false;
  let armed = null;

  // Dev helper: ?autojoin=Name joins without touching the form (used by the desktop 4-pane launcher).
  const autoName = new URLSearchParams(location.search).get('autojoin');
  function autoJoin() {
    if (!autoName || pid || joining) return;
    joining = true;
    send({ t: 'join', name: autoName, pid: '' });
  }

  // ---------- socket ----------
  let ws = null, tries = 0;
  function open() {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(proto + location.host + base + 'ws' + (isHostPage ? '?host=' + encodeURIComponent(token) : ''));
    ws.onopen = () => { tries = 0; status(true); if (pid) send({ t: 'resume', pid }); else { autoJoin(); render(); } };
    ws.onmessage = e => { let m; try { m = JSON.parse(e.data); } catch (_) { return; } onMessage(m); };
    ws.onclose = () => { status(false); setTimeout(open, Math.min(4000, 400 * ++tries)); };
    ws.onerror = () => { try { ws.close(); } catch (_) {} };
  }
  function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
  function status(ok) { $('#conn').innerHTML = `<span class="dot ${ok ? '' : 'off'}"></span>${ok ? '' : ' <span class="small muted">зв\'язок…</span>'}`; }
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && ws && ws.readyState > 1) open();
    if (!document.hidden) keepAwake();
  });

  // Keep player screens on during a game (only works over HTTPS, i.e. through the relay).
  let wakeLock = null;
  async function keepAwake() {
    if (isHostPage || !('wakeLock' in navigator) || (wakeLock && !wakeLock.released)) return;
    try { wakeLock = await navigator.wakeLock.request('screen'); } catch (_) {}
  }

  function onMessage(m) {
    if (m.t === 'welcome') { pid = m.pid; store.set(K + 'pid', pid); joining = false; return; }
    if (m.t === 'unknown') { pid = null; store.set(K + 'pid', null); autoJoin(); render(); return; }
    if (m.t !== 'state') return;
    clockOffset = m.now - Date.now();
    if (m.me && m.me.id !== pid) { pid = m.me.id; store.set(K + 'pid', pid); joining = false; }
    prev = st; st = m;
    const switched = prev && prev.game !== st.game;
    if (switched) { showInvite = false; menuOpen = false; rulesOpen = false; bigQr = false; }
    const g = st.game && games[st.game];
    if (g && g.onState) g.onState(prev && prev.game === st.game ? prev.g : null, st.g, api);
    render();
    // a new screen starts at its top, not wherever the game list was scrolled to
    if (switched) window.scrollTo(0, 0);
  }

  // ---------- API for game modules ----------
  // Browsers only allow audio after a user gesture, so the context is unlocked on the first tap.
  let audioCtx = null;
  function unlockAudio() {
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!audioCtx && AC) audioCtx = new AC();
      if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
    } catch (_) {}
  }
  ['pointerdown', 'keydown', 'touchstart'].forEach(e => document.addEventListener(e, unlockAudio, { passive: true }));

  /** Three short descending beeps — "time is up". */
  function buzzer() {
    try {
      unlockAudio();
      if (!audioCtx) return;
      const t0 = audioCtx.currentTime + 0.02;
      [880, 740, 600].forEach((f, i) => {
        const o = audioCtx.createOscillator(), v = audioCtx.createGain();
        const s = t0 + i * 0.28;
        o.type = 'square'; o.frequency.value = f;
        v.gain.setValueAtTime(0.0001, s);
        v.gain.exponentialRampToValueAtTime(0.25, s + 0.02);
        v.gain.exponentialRampToValueAtTime(0.0001, s + 0.22);
        o.connect(v); v.connect(audioCtx.destination);
        o.start(s); o.stop(s + 0.24);
      });
    } catch (_) {}
  }

  const api = {
    $, esc, store, buzzer,
    get st() { return st; },
    get g() { return st && st.g; },
    get me() { return st && st.me; },
    get isHost() { return !!(st && st.isHost); },
    get isHostPage() { return isHostPage; },
    get mod() { return st && st.mods && st.game ? st.mods[st.game] : null; },
    now() { return Date.now() + clockOffset; },
    send(d) { send({ t: 'g', d }); },
    sendMod(d) { send({ t: 'mod', game: st && st.game, d }); },
    kick(id) { send({ t: 'kick', pid: id }); },
    nameOf(id) { return esc(((st && st.players.find(p => p.id === id)) || {}).name || '?'); },
    render() { render(); },
    vibrate(ms) { if (!isHostPage && navigator.vibrate) { try { navigator.vibrate(ms); } catch (_) {} } },
  };
  window.PG = { register(id, mod) { games[id] = mod; }, api };

  // ---------- views ----------
  function dice() {
    return `<svg class="logo-mark" viewBox="20 21 68 68" aria-hidden="true"><g transform="rotate(16 66 45)"><path fill="#C98A1E" d="M56.4,32.2 h19.2 a6.4,6.4 0 0 1 6.4,6.4 v19.2 a6.4,6.4 0 0 1 -6.4,6.4 h-19.2 a6.4,6.4 0 0 1 -6.4,-6.4 v-19.2 a6.4,6.4 0 0 1 6.4,-6.4 z"/><path fill="#F5B942" d="M56.4,29 h19.2 a6.4,6.4 0 0 1 6.4,6.4 v19.2 a6.4,6.4 0 0 1 -6.4,6.4 h-19.2 a6.4,6.4 0 0 1 -6.4,-6.4 v-19.2 a6.4,6.4 0 0 1 6.4,-6.4 z"/><path fill="#0E1320" d="M55.184,37 a2.816,2.816 0 1 1 5.632,0 a2.816,2.816 0 1 1 -5.632,0 z M63.184,45 a2.816,2.816 0 1 1 5.632,0 a2.816,2.816 0 1 1 -5.632,0 z M71.184,53 a2.816,2.816 0 1 1 5.632,0 a2.816,2.816 0 1 1 -5.632,0 z"/></g><g transform="rotate(-14 44 63)"><path fill="#C42F6B" d="M33.8,49.2 h20.4 a6.8,6.8 0 0 1 6.8,6.8 v20.4 a6.8,6.8 0 0 1 -6.8,6.8 h-20.4 a6.8,6.8 0 0 1 -6.8,-6.8 v-20.4 a6.8,6.8 0 0 1 6.8,-6.8 z"/><path fill="#FF4F8A" d="M33.8,46 h20.4 a6.8,6.8 0 0 1 6.8,6.8 v20.4 a6.8,6.8 0 0 1 -6.8,6.8 h-20.4 a6.8,6.8 0 0 1 -6.8,-6.8 v-20.4 a6.8,6.8 0 0 1 6.8,-6.8 z"/><path fill="#FFFFFF" d="M32.508,54.5 a2.992,2.992 0 1 1 5.984,0 a2.992,2.992 0 1 1 -5.984,0 z M49.508,54.5 a2.992,2.992 0 1 1 5.984,0 a2.992,2.992 0 1 1 -5.984,0 z M41.008,63 a2.992,2.992 0 1 1 5.984,0 a2.992,2.992 0 1 1 -5.984,0 z M32.508,71.5 a2.992,2.992 0 1 1 5.984,0 a2.992,2.992 0 1 1 -5.984,0 z M49.508,71.5 a2.992,2.992 0 1 1 5.984,0 a2.992,2.992 0 1 1 -5.984,0 z"/></g></svg>`;
  }

  /** A game is picked but not started yet (its own setup screen): leaving it loses nothing, so no "Точно?". */
  const notStarted = () => !!(st && st.game && (!st.g || st.g.phase === 'LOBBY'));

  function gameInfo(id) { return (st && st.games.find(x => x.id === id)) || null; }

  function joinView() {
    const name = store.get('pg.name') || '';
    return `<div class="stack" style="margin-top:${isHostPage ? 2 : 10}vh">
      ${isHostPage ? inviteCard() : ''}
      <h1>${isHostPage ? 'Як тебе звати?' : 'Заходь у гру'}</h1>
      ${isHostPage ? '' : '<p class="muted">Хост обирає гру, а ти граєш просто в браузері.</p>'}
      <input id="name" type="text" maxlength="20" placeholder="Твоє ім'я" value="${esc(name)}" autocomplete="nickname">
      <button class="big" data-pg="join" ${joining ? 'disabled' : ''}>Грати</button>
    </div>`;
  }

  // Each returns { qr, info }: the QR image (may be empty) and the text that goes with it.
  function localQr() {
    const urls = (st.net && st.net.local) || [];
    if (!urls.length) {
      return { qr: '', info: `<h2>Немає Wi-Fi</h2>
        <p class="muted">Підключи телефон до Wi-Fi або увімкни точку доступу — або перемкнись на «Інтернет».</p>` };
    }
    const u = urls[0];
    return {
      qr: `<img class="qr" src="${base}qr.svg?t=${encodeURIComponent(u.url)}" alt="QR">`,
      info: `<div class="url">${esc(u.url)}</div>
        <p class="muted small">${u.hotspot ? 'Гравці підключаються до точки доступу цього телефону.' : 'Гравці мають бути в тій самій Wi-Fi мережі.'}</p>
        ${urls.length > 1 ? `<div class="muted small">Інші адреси: ${urls.slice(1).map(x => esc(x.url)).join(', ')}</div>` : ''}`,
    };
  }

  function relayQr() {
    const r = st.net.relay;
    let qr = '', info;
    if (r.state === 'ONLINE' && r.join) {
      qr = `<img class="qr" src="${base}qr.svg?t=${encodeURIComponent(r.join)}" alt="QR">`;
      info = `<div class="code">${esc(r.code)}</div>
        <div class="url">${esc(r.join)}</div>
        <p class="muted small">Працює з будь-якої мережі, навіть з мобільного інтернету. Або відкрий ${esc(r.url.replace(/^https?:\/\//, ''))} і введи код.</p>`;
    } else {
      info = `<div class="center" style="padding:12px 0"><span class="spinner"></span></div>
        <p class="muted">${esc(r.message || 'Підключаюсь…')}</p>`;
    }
    info += showRelayUrl
      ? `<div class="row"><input id="relayUrl" type="text" value="${esc(r.url)}" style="flex:1;font-size:14px"><button class="mini" data-pg="saveRelay">OK</button></div>`
      : `<button class="mini ghost" data-pg="editRelay">Адреса сервера</button>`;
    return { qr, info };
  }

  /** QR + network switch. Compact puts a small QR next to the text (lobby), so the game list stays near the top. */
  function inviteCard(compact) {
    if (!st || !st.net) return '';
    const relayOn = st.net.relay.enabled;
    const { qr, info } = relayOn ? relayQr() : localQr();
    const small = compact && !bigQr;
    return `<div class="card invite ${small ? 'compact' : 'center'} stack">
      <div class="seg">
        <button class="${relayOn ? '' : 'on'}" data-pg="net" data-v="local">📶 Wi-Fi</button>
        <button class="${relayOn ? 'on' : ''}" data-pg="net" data-v="relay">🌍 Інтернет</button>
      </div>
      ${small
        ? `<div class="inv-row">${qr ? `<button class="qr-btn" data-pg="bigQr" aria-label="Збільшити QR">${qr}</button>` : ''}<div class="inv-info stack">${info}</div></div>`
        : `${compact && qr ? `<button class="qr-btn" data-pg="bigQr" aria-label="Зменшити QR">${qr}</button>` : qr}${info}`}
    </div>`;
  }

  function playersCard() {
    const ps = st.players;
    return `<div class="card">
      <div class="chips">${ps.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}${st.me && st.me.id === p.id ? ' <span class="muted small">(ти)</span>' : ''}
        ${st.isHost && !(st.me && st.me.id === p.id) ? `<button class="mini ghost x" data-pg="kick" data-v="${p.id}">${armed === 'kick' + p.id ? 'Точно?' : '✕'}</button>` : ''}</span>`).join('')
        || '<span class="muted">Чекаємо, поки хтось відсканує QR…</span>'}</div>
    </div>`;
  }

  function lobbyView() {
    let h = `<div class="stack">`;
    if (st.isHost) {
      h += inviteCard(true);
      h += `<h2>Гравці · ${st.players.length}</h2>${playersCard()}`;
      h += `<h2>Що граємо?</h2><div class="games">${st.games.map(g => {
        const enough = st.players.length >= g.minPlayers;
        return `<button class="game-card" data-pg="select" data-v="${g.id}">
          <span class="emoji">${g.emoji}</span>
          <span class="grow"><b>${esc(g.title)}</b><span class="muted small">${esc(g.subtitle)}</span>
          <span class="small ${enough ? 'muted' : 'warn'}">від ${g.minPlayers} гравців</span></span></button>`;
      }).join('')}</div>`;
    } else {
      h += `<h1>Привіт, ${esc(st.me.name)}!</h1>
        <div class="card flat center"><span class="spinner"></span> <span class="muted">Хост обирає гру…</span></div>
        <h2>Тут ${st.players.length}</h2>${playersCard()}
        <button class="ghost" data-pg="leave">Вийти</button>`;
    }
    return h + `</div>`;
  }

  /** Side drawer (☰ or the back gesture): everything that is not part of the current screen lives here. */
  function drawerView() {
    const g = st.game && games[st.game];
    const info = st.game ? gameInfo(st.game) : null;
    const host = st.isHost;
    // two taps for anything that interrupts other people
    const conf = (act, label, sure) => armed === act
      ? `<button class="danger" data-pg="${act}">${sure}</button>`
      : `<button class="soft" data-pg="arm" data-v="${act}">${label}</button>`;
    let h = `<div class="dr-head">
      <b>${info ? `${info.emoji} ${esc(info.title)}` : 'Party Games'}</b>
      <button class="mini ghost" data-pg="menu" aria-label="Закрити">✕</button>
    </div><div class="dr-body">`;

    if (info) {
      let s = '';
      if (window.PG_RULES && PG_RULES[st.game]) s += `<button class="soft" data-pg="rules">📖 Правила</button>`;
      if (host && g && g.menu) s += gameMenu(g.menu(api));
      if (host) s += notStarted()
        ? `<button class="soft" data-pg="exitGame">🏠 Головне меню</button>`
        : conf('exitGame', '🏠 Головне меню', 'Точно? Завершити гру й повернутись');
      if (s) h += `<section><h3>Гра</h3>${s}</section>`;
    }

    h += `<section><h3>Гравці · ${st.players.length}</h3>${playersCard()}`;
    if (host && st.game) {
      h += `<button class="soft" data-pg="invite">${showInvite ? 'Сховати QR' : '📲 Запросити гравців'}</button>`;
      if (showInvite) h += inviteCard(false);
    }
    if (!host) h += conf('leave', '🚪 Вийти з кімнати', 'Точно? Вийти з кімнати');
    h += `</section>`;

    if (isHostPage && window.PGApp) {
      let v = '', nv = '';
      try { v = PGApp.version(); nv = PGApp.updateAvailable(); } catch (_) {}
      h += `<section><h3>Застосунок</h3>${nv
        ? `<button data-pg="update">⬆️ Оновити до v${esc(nv)}</button>`
        : `<button class="soft" data-pg="update">🔄 Перевірити оновлення</button>`}
        ${conf('quit', '⏻ Вийти', 'Точно? Гра для всіх закінчиться')}
        ${v ? `<div class="muted small">Версія v${esc(v)}</div>` : ''}</section>`;
    }
    return h + `</div>`;
  }

  /** Shortcuts a game's menu() may list instead of spelling the item out. */
  const MENU_ITEMS = { newGame: { t: 'newGame', label: '🔁 Нова гра' } };

  /**
   * Host-only items of the current game: menu() returns [{ t, label }] or a MENU_ITEMS key. Each one asks "Точно?"
   * on the first tap and sends { t } to the game on the second, then the drawer closes.
   */
  function gameMenu(items) {
    return (items || []).map(it => (typeof it === 'string' ? MENU_ITEMS[it] : it)).filter(Boolean).map(it => {
      const key = 'g:' + it.t;
      return armed === key
        ? `<button class="danger" data-pg="gameAct" data-v="${esc(it.t)}">Точно? ${esc(it.label)}</button>`
        : `<button class="soft" data-pg="arm" data-v="${esc(key)}">${esc(it.label)}</button>`;
    }).join('');
  }

  function setDrawer(open) {
    if (menuOpen === open) return;
    menuOpen = open; armed = null;
    if (!open) showInvite = false;
    render();
  }

  function render() {
    const g = st && st.game ? games[st.game] : null;
    const info = st && st.game ? gameInfo(st.game) : null;
    document.body.dataset.game = (st && st.game) || '';
    $('#logo').innerHTML = info ? `<span class="logo-emoji">${info.emoji}</span>${esc(info.title)}` : `${dice()}Party Games`;
    $('#hostTag').hidden = !(st && st.isHost);
    const joined = !!(pid && st && st.me);
    $('#menuBtn').hidden = !joined;
    if (!joined) menuOpen = false;
    $('#infoBtn').hidden = !(info && window.PG_RULES && PG_RULES[st.game]);
    const rulesEl = $('#rules');
    rulesEl.hidden = !(rulesOpen && info && window.PG_RULES && PG_RULES[st.game]);
    if (!rulesEl.hidden) {
      const html = `<div class="rules-card" role="dialog" aria-modal="true">
        <div class="rules-head"><b>${info.emoji} ${esc(info.title)} — правила</b><button class="mini ghost" data-pg="rules" aria-label="Закрити">✕</button></div>
        <div class="rules-body">${PG_RULES[st.game]}</div></div>`;
      if (rulesEl._pgHtml !== html) { rulesEl._pgHtml = html; rulesEl.innerHTML = html; }
    }
    $('#extra').innerHTML = g && g.extra ? (g.extra(api) || '') : '';

    const drawer = $('#drawer');
    drawer.classList.toggle('open', menuOpen);
    drawer.setAttribute('aria-hidden', String(!menuOpen));
    document.body.classList.toggle('drawer-open', menuOpen);
    if (menuOpen) keepInput($('#drawerInner'), drawerView());

    let html = '', bar = '';
    const needsJoin = !pid || !st || !st.me;
    if (needsJoin) {
      if (pid && !st) return; // waiting for state after resume
      html = joinView();
    } else if (!st.game) {
      html = lobbyView();
    } else if (!g) {
      html = `<div class="card center">Ця гра потребує новішої версії сторінки. Онови її.</div>`;
    } else {
      const out = g.render(api) || '';
      if (typeof out === 'string') html = out; else { html = out.html || ''; bar = out.bar || ''; }
    }
    keepInput($('#app'), html);
    keepInput($('#barInner'), bar);
    $('#bar').hidden = !bar;
    document.body.classList.toggle('with-bar', !!bar);
  }

  /**
   * Re-render without losing what the user is typing. Identical markup leaves the DOM alone, so a state push
   * that changes nothing on this screen (someone else tapped) does not restart animations or eat a tap.
   */
  function keepInput(container, html) {
    if (container._pgHtml === html) return;
    container._pgHtml = html;
    const a = document.activeElement;
    const saved = {};
    container.querySelectorAll('input[id]').forEach(i => { saved[i.id] = i.value; });
    const focusId = a && a.id && a.tagName === 'INPUT' && container.contains(a) ? a.id : null;
    const sel = focusId ? [a.selectionStart, a.selectionEnd] : null;
    container.innerHTML = html;
    for (const id in saved) { const el = document.getElementById(id); if (el && !el.dataset.fresh) el.value = saved[id]; }
    if (focusId) {
      const el = document.getElementById(focusId);
      if (el) { el.focus(); try { el.setSelectionRange(sel[0], sel[1]); } catch (_) {} }
    }
  }
  PG.keepInput = keepInput;

  /**
   * Back gesture of the host app (called from MainActivity). Peels one layer: rules, drawer, a game's own screen;
   * a game that has not started yet goes straight back to the main menu; otherwise slides the drawer in (the edge swipe "pulls" it out; leaving the app is its «Вийти»). Returns true
   * when it handled it — false only before joining, so the app goes to the background.
   */
  PG.back = function () {
    if (rulesOpen) { rulesOpen = false; render(); return true; }
    if (menuOpen) { setDrawer(false); return true; }
    if (bigQr) { bigQr = false; render(); return true; }
    const g = st && st.game ? games[st.game] : null;
    if (g && g.back && g.back(api)) return true;
    if (st && st.isHost && notStarted()) { shellAct('exitGame'); return true; }
    if (st && st.me) { setDrawer(true); return true; }
    return false;
  };

  // Swipe the drawer back to the right to close it; the panel follows the finger.
  (function swipeToClose() {
    let x0 = null, y0 = 0, dx = 0, horiz = null;
    const panel = () => $('#drawer .drawer-panel');
    document.addEventListener('touchstart', e => {
      if (!menuOpen || !e.target.closest('#drawer')) return;
      x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; dx = 0; horiz = null;
    }, { passive: true });
    document.addEventListener('touchmove', e => {
      if (x0 == null) return;
      const t = e.touches[0];
      if (horiz == null && Math.abs(t.clientX - x0) + Math.abs(t.clientY - y0) > 8) horiz = Math.abs(t.clientX - x0) > Math.abs(t.clientY - y0);
      if (!horiz) return;
      dx = Math.max(0, t.clientX - x0);
      const p = panel(); p.style.transition = 'none'; p.style.transform = `translateX(${dx}px)`;
    }, { passive: true });
    document.addEventListener('touchend', () => {
      if (x0 == null) return;
      x0 = null;
      const p = panel(); p.style.transition = ''; p.style.transform = '';
      if (horiz && dx > 70) setDrawer(false);
    });
  })();

  // ---------- actions ----------
  function join() {
    const input = $('#name');
    const name = (input && input.value || '').trim();
    if (!name) { input && input.focus(); return; }
    store.set('pg.name', name);
    joining = true;
    send({ t: 'join', name, pid: pid || '' });
    keepAwake();
    render();
  }

  function shellAct(act, el) {
    switch (act) {
      case 'join': return join();
      case 'rules': rulesOpen = !rulesOpen; if (rulesOpen) { menuOpen = false; armed = null; } return render();
      case 'menu': return setDrawer(!menuOpen);
      case 'bigQr': bigQr = !bigQr; return render();
      case 'arm': armed = el.dataset.v; return render();
      case 'kick': {
        const key = 'kick' + el.dataset.v;
        if (armed !== key) { armed = key; render(); setTimeout(() => { if (armed === key) { armed = null; render(); } }, 3000); return; }
        armed = null; return send({ t: 'kick', pid: el.dataset.v });
      }
      case 'select': return send({ t: 'select', game: el.dataset.v });
      case 'exitGame': armed = null; menuOpen = false; return send({ t: 'exitGame' });
      case 'leave':
        armed = null; menuOpen = false;
        send({ t: 'leave' }); pid = null; store.set(K + 'pid', null);
        st = null;
        return render();
      case 'gameAct': api.send({ t: el.dataset.v }); return setDrawer(false);
      case 'quit': try { window.PGApp && PGApp.quit(); } catch (_) {} return;
      case 'update': setDrawer(false); try { window.PGApp && PGApp.checkUpdate(); } catch (_) {} return;
      case 'invite': showInvite = !showInvite; return render();
      case 'net': return send({ t: 'relay', on: el.dataset.v === 'relay' });
      case 'editRelay': showRelayUrl = true; return render();
      case 'saveRelay': {
        const v = ($('#relayUrl') || {}).value || '';
        showRelayUrl = false;
        send({ t: 'relayUrl', url: v.trim() });
        return render();
      }
    }
  }

  document.addEventListener('click', e => {
    keepAwake();
    if (rulesOpen && e.target.id === 'rules') { rulesOpen = false; return render(); }
    if (menuOpen && e.target.id === 'drawer') return setDrawer(false);
    const el = e.target.closest('[data-pg]');
    if (el) { if (!el.disabled) shellAct(el.dataset.pg, el); return; }
    const g = st && st.game ? games[st.game] : null;
    if (g && g.click) g.click(e, api);
  });

  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && rulesOpen) { rulesOpen = false; return render(); }
    if (e.key === 'Escape' && menuOpen) return setDrawer(false);
    if (e.key === 'Enter' && e.target.id === 'name') return join();
    if (e.key === 'Enter' && e.target.id === 'relayUrl') return shellAct('saveRelay');
    const g = st && st.game ? games[st.game] : null;
    if (g && g.key) g.key(e, api);
  });

  document.addEventListener('DOMContentLoaded', () => { $('#logo').innerHTML = dice() + 'Party Games'; open(); });
})();
