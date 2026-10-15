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
    if (prev && prev.game !== st.game) { showInvite = false; menuOpen = false; rulesOpen = false; }
    const g = st.game && games[st.game];
    if (g && g.onState) g.onState(prev && prev.game === st.game ? prev.g : null, st.g, api);
    render();
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
    return `<svg class="logo-mark" viewBox="22 26 66 62" aria-hidden="true">
      <g transform="rotate(-12 44 58)"><rect x="24" y="44" width="40" height="40" rx="6" fill="#ff4f8a"/>
        <circle cx="35" cy="55" r="3.5" fill="#fff"/><circle cx="44" cy="64" r="3.5" fill="#fff"/><circle cx="53" cy="73" r="3.5" fill="#fff"/></g>
      <g transform="rotate(14 66 46)"><rect x="47" y="30" width="38" height="38" rx="6" fill="#f5b942"/>
        <circle cx="58" cy="40" r="3.2" fill="#0e1320"/><circle cx="74" cy="40" r="3.2" fill="#0e1320"/>
        <circle cx="58" cy="58" r="3.2" fill="#0e1320"/><circle cx="74" cy="58" r="3.2" fill="#0e1320"/></g></svg>`;
  }

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

  function localQr() {
    const urls = (st.net && st.net.local) || [];
    if (!urls.length) {
      return `<h2>Немає Wi-Fi</h2>
        <p class="muted">Підключи телефон до Wi-Fi або увімкни точку доступу — або перемкнись на «Інтернет».</p>`;
    }
    const u = urls[0];
    return `<img class="qr" src="${base}qr.svg?t=${encodeURIComponent(u.url)}" alt="QR">
      <div class="url">${esc(u.url)}</div>
      <p class="muted small">${u.hotspot ? 'Гравці підключаються до точки доступу цього телефону.' : 'Гравці мають бути в тій самій Wi-Fi мережі.'}</p>
      ${urls.length > 1 ? `<div class="muted small">Інші адреси: ${urls.slice(1).map(x => esc(x.url)).join(', ')}</div>` : ''}`;
  }

  function relayQr() {
    const r = st.net.relay;
    let h;
    if (r.state === 'ONLINE' && r.join) {
      h = `<img class="qr" src="${base}qr.svg?t=${encodeURIComponent(r.join)}" alt="QR">
        <div class="code">${esc(r.code)}</div>
        <div class="url">${esc(r.join)}</div>
        <p class="muted small">Працює з будь-якої мережі, навіть з мобільного інтернету. Або відкрий ${esc(r.url.replace(/^https?:\/\//, ''))} і введи код.</p>`;
    } else {
      h = `<div class="center" style="padding:24px 0"><span class="spinner"></span></div>
        <p class="muted">${esc(r.message || 'Підключаюсь…')}</p>`;
    }
    h += showRelayUrl
      ? `<div class="row"><input id="relayUrl" type="text" value="${esc(r.url)}" style="flex:1;font-size:14px"><button class="mini" data-pg="saveRelay">OK</button></div>`
      : `<button class="mini ghost" data-pg="editRelay">Адреса сервера</button>`;
    return h;
  }

  function inviteCard() {
    if (!st || !st.net) return '';
    const relayOn = st.net.relay.enabled;
    return `<div class="card center stack">
      <div class="seg">
        <button class="${relayOn ? '' : 'on'}" data-pg="net" data-v="local">📶 Wi-Fi</button>
        <button class="${relayOn ? 'on' : ''}" data-pg="net" data-v="relay">🌍 Інтернет</button>
      </div>
      ${relayOn ? relayQr() : localQr()}
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
      h += inviteCard();
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

  function menuView() {
    const g = st.game && games[st.game];
    const inGame = !!st.game;
    const conf = (act, label) => armed === act
      ? `<button class="danger" data-pg="${act}">Точно? ${label}</button>`
      : `<button class="soft" data-pg="arm" data-v="${act}">${label}</button>`;
    let h = '';
    if (inGame) h += `<button class="soft" data-pg="invite">${showInvite ? 'Сховати QR' : 'Запросити гравців (QR)'}</button>`;
    if (inGame && g && g.menu) h += g.menu(api) || '';
    if (inGame) h += conf('exitGame', 'До вибору гри');
    if (isHostPage && !inGame && window.PGApp) {
      let v = '', nv = '';
      try { v = PGApp.version(); nv = PGApp.updateAvailable(); } catch (_) {}
      h += nv
        ? `<button data-pg="update">⬆️ Оновити до v${esc(nv)}</button>`
        : `<button class="soft" data-pg="update">Перевірити оновлення</button>`;
      if (v) h += `<div class="muted small">Версія v${esc(v)}</div>`;
    }
    h += `<div class="muted small">Повністю зупинити сервер — «Завершити гру» у сповіщенні.</div>`;
    return h;
  }

  function render() {
    const g = st && st.game ? games[st.game] : null;
    const info = st && st.game ? gameInfo(st.game) : null;
    document.body.dataset.game = (st && st.game) || '';
    $('#logo').innerHTML = info ? `<span class="logo-emoji">${info.emoji}</span>${esc(info.title)}` : `${dice()}Ігри`;
    $('#hostTag').hidden = !(st && st.isHost);
    $('#menuBtn').hidden = !(st && st.isHost);
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

    const menu = $('#menu');
    menu.hidden = !(menuOpen && st && st.isHost);
    if (!menu.hidden) menu.innerHTML = menuView();

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
      if (showInvite && st.isHost) html = inviteCard() + '<div style="height:14px"></div>' + html;
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
   * Back gesture of the host app (called from MainActivity). Peels one layer: dialog, menu, QR, then a game's own
   * screen, then opens the menu inside a game. Returns true when it handled the gesture.
   */
  PG.back = function () {
    if (rulesOpen) { rulesOpen = false; render(); return true; }
    if (menuOpen) { menuOpen = false; armed = null; render(); return true; }
    if (showInvite) { showInvite = false; render(); return true; }
    const g = st && st.game ? games[st.game] : null;
    if (g && g.back && g.back(api)) return true;
    if (st && st.isHost && st.game) { menuOpen = true; armed = null; render(); return true; }
    return false;
  };

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
      case 'rules': rulesOpen = !rulesOpen; return render();
      case 'menu': menuOpen = !menuOpen; armed = null; return render();
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
      case 'update': menuOpen = false; render(); try { window.PGApp && PGApp.checkUpdate(); } catch (_) {} return;
      case 'invite': showInvite = !showInvite; menuOpen = false; return render();
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
    const el = e.target.closest('[data-pg]');
    if (el) { if (!el.disabled) shellAct(el.dataset.pg, el); return; }
    const g = st && st.game ? games[st.game] : null;
    if (g && g.click) g.click(e, api);
  });

  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && rulesOpen) { rulesOpen = false; return render(); }
    if (e.key === 'Enter' && e.target.id === 'name') return join();
    if (e.key === 'Enter' && e.target.id === 'relayUrl') return shellAct('saveRelay');
    const g = st && st.game ? games[st.game] : null;
    if (g && g.key) g.key(e, api);
  });

  document.addEventListener('DOMContentLoaded', () => { $('#logo').innerHTML = dice() + 'Ігри'; open(); });
})();
