// «Малювач» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  // Same order and count as Art.COLORS / Art.SIZES on the server: strokes carry indexes, not values.
  const COLORS = ['#111111', '#ffffff', '#e53935', '#fb8c00', '#fdd835', '#43a047', '#1e88e5', '#8e24aa', '#6d4c41', '#f48fb1', '#9e9e9e', '#ffcc99'];
  const ERASER = 1;
  const SIZES = [8, 20, 44];
  const SIZE = 1000;                       // coordinates are 0..SIZE on both axes
  const MAX_POINTS = 9500, MAX_STROKES = 780;   // a bit under the server's limits
  const SPICES = [['CLEAN', '😇 Звичайні'], ['MIXED', '🎲 Усі'], ['ADULT', '🔥 18+']];

  let api = PG.api;
  let armed = null;          // two-tap confirmations
  // This phone's drawing for the current DRAW phase. key=null until it is (re)loaded from the server.
  let pad = { key: null, strokes: [], dirty: false };
  let color = 0, size = 1;
  let cur = null, curId = null;   // stroke under the finger
  let syncTimer = null;
  let lastDrawHtml = '';
  let cv = null, ctx = null;

  const g = () => api.g;
  const me = () => g() && g().me;
  const isArtist = () => !!(me() && g().artist === me().id);
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');
  const pts = n => (n % 10 === 1 && n % 100 !== 11) ? 'очко' : ([2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100) ? 'очки' : 'очок');
  const times = n => ([2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100)) ? 'рази' : 'разів';

  // ---------- timer ----------
  function remaining() {
    const s = g();
    return s && s.deadline ? Math.max(0, s.deadline - api.now()) : 0;
  }
  function fmt(ms) {
    const s = Math.ceil(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }
  // The markup stays the same while the clock runs (so a re-render never resets the canvas); the numbers are filled in here.
  function updateTimer() {
    const s = g();
    const t = document.getElementById('drTimer');
    if (t && s) {
      const ms = remaining();
      t.textContent = '⏱ ' + fmt(ms);
      t.classList.toggle('low', ms < 10000);
      const b = document.getElementById('drBar');
      if (b && s.limit) b.style.width = (ms / s.limit * 100) + '%';
    }
    // The server hands in whatever it has when time is up: make sure it has the last strokes.
    if (s && s.phase === 'DRAW' && pad.dirty && remaining() < 2000) flush();
  }
  setInterval(updateTimer, 250);

  // ---------- canvas ----------
  function canvas() {
    if (cv) return cv;
    cv = document.createElement('canvas');
    cv.className = 'pad';
    ctx = cv.getContext('2d');
    cv.addEventListener('pointerdown', down);
    cv.addEventListener('pointermove', move);
    cv.addEventListener('pointerup', up);
    cv.addEventListener('pointercancel', up);
    cv.addEventListener('lostpointercapture', up);
    return cv;
  }

  /** Puts the one long-lived canvas into the freshly rendered slot. */
  function mount() {
    updateTimer();
    const slot = document.getElementById('padSlot');
    if (!slot) return;
    const c = canvas();
    if (c.parentNode !== slot) slot.appendChild(c);
    fit();
  }

  function fit() {
    if (!cv || !cv.isConnected) return;
    const w = cv.clientWidth;
    if (!w) return;
    const px = Math.round(w * (window.devicePixelRatio || 1));
    if (cv.width !== px) { cv.width = px; cv.height = px; }
    redraw();
  }
  window.addEventListener('resize', fit);

  function paint(s) {
    const k = cv.width / SIZE;
    const c = COLORS[s[0]] || COLORS[0], w = (SIZES[s[1]] || SIZES[0]) * k;
    ctx.strokeStyle = ctx.fillStyle = c;
    ctx.lineWidth = w;
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.beginPath();
    if (s.length === 4) { ctx.arc(s[2] * k, s[3] * k, w / 2, 0, Math.PI * 2); ctx.fill(); return; }
    ctx.moveTo(s[2] * k, s[3] * k);
    for (let i = 4; i < s.length; i += 2) ctx.lineTo(s[i] * k, s[i + 1] * k);
    ctx.stroke();
  }

  function redraw() {
    if (!ctx) return;
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, cv.width, cv.height);
    pad.strokes.forEach(paint);
    if (cur) paint(cur);
  }

  const points = () => pad.strokes.reduce((n, s) => n + (s.length - 2) / 2, 0) + (cur ? (cur.length - 2) / 2 : 0);
  const full = () => points() >= MAX_POINTS || pad.strokes.length >= MAX_STROKES;

  function at(e) {
    const r = cv.getBoundingClientRect();
    const c = v => Math.max(0, Math.min(SIZE, Math.round(v)));
    return [c((e.clientX - r.left) / r.width * SIZE), c((e.clientY - r.top) / r.height * SIZE)];
  }

  function canDraw() {
    const s = g(), m = me();
    return !!(s && m && s.phase === 'DRAW' && !m.done && pad.key !== null);
  }

  function down(e) {
    if (cur || !canDraw()) return;
    if (full()) return api.render();
    e.preventDefault();
    try { cv.setPointerCapture(e.pointerId); } catch (_) {}
    const [x, y] = at(e);
    cur = [color, size, x, y];
    curId = e.pointerId;
    paint(cur);
  }

  function move(e) {
    if (!cur || e.pointerId !== curId) return;
    const evs = (e.getCoalescedEvents && e.getCoalescedEvents()) || [];
    for (const ev of evs.length ? evs : [e]) {
      if (points() >= MAX_POINTS) break;
      const [x, y] = at(ev);
      const lx = cur[cur.length - 2], ly = cur[cur.length - 1];
      if (Math.hypot(x - lx, y - ly) < 4) continue;
      cur.push(x, y);
      paint([cur[0], cur[1], lx, ly, x, y]);
    }
  }

  function up(e) {
    if (!cur || (e && e.pointerId !== curId)) return;
    pad.strokes.push(cur);
    cur = null; curId = null;
    pad.dirty = true;
    scheduleSync();
    api.render();
  }

  // Drafts only guard against a reload or the timer, and each one makes the host push state to everybody:
  // send at most every few seconds, and right away when the page is about to go to the background.
  function scheduleSync() {
    if (!syncTimer) syncTimer = setTimeout(flush, 2500);
  }
  document.addEventListener('visibilitychange', () => { if (document.hidden) flush(); });
  function flush() {
    clearTimeout(syncTimer); syncTimer = null;
    if (!pad.dirty) return;
    pad.dirty = false;
    api.send({ t: 'art', art: pad.strokes });
  }

  /** Loads the drawing from the server when a DRAW phase starts or the page was reloaded. */
  function syncPad() {
    const s = g(), m = me();
    if (!s || s.phase !== 'DRAW' || !m || pad.key !== null) return;
    pad = { key: s.round, strokes: (m.art || []).map(x => x.slice()), dirty: false };
    cur = null;
  }

  // ---------- pieces ----------
  /** A finished drawing as SVG: crisp at any size and no canvas to keep alive. */
  function artSvg(art, cls = '') {
    const parts = (art || []).map(s => {
      const c = COLORS[s[0]] || COLORS[0], w = SIZES[s[1]] || SIZES[0];
      if (s.length === 4) return `<circle cx="${+s[2]}" cy="${+s[3]}" r="${w / 2}" fill="${c}"/>`;
      let p = '';
      for (let i = 2; i < s.length; i += 2) p += `${+s[i]},${+s[i + 1]} `;
      return `<polyline points="${p}" stroke="${c}" stroke-width="${w}"/>`;
    }).join('');
    return `<svg class="art ${cls}" viewBox="0 0 ${SIZE} ${SIZE}" role="img" aria-label="малюнок"><rect width="${SIZE}" height="${SIZE}" fill="#fff"/>
      <g fill="none" stroke-linecap="round" stroke-linejoin="round">${parts}</g></svg>`;
  }

  function header() {
    const s = g();
    return `<div class="row head">
      <span class="tag">раунд ${s.round} з ${s.rounds}</span>
      ${s.index ? `<span class="tag">малюнок ${s.index} з ${s.total}</span>` : ''}
      <span class="grow"></span>
      ${s.deadline ? '<span class="clock" id="drTimer"></span>' : ''}
    </div>
    ${s.deadline ? '<div class="bar"><i id="drBar"></i></div>' : ''}`;
  }

  function progress(word, doneWord) {
    const s = g();
    const list = s.players.filter(p => p.id !== s.artist);
    return `<div class="card">${list.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && p.id === me().id ? ' <span class="muted">(ти)</span>' : ''}</span>
        <span class="small ${p.done ? 'ok' : 'muted'}">${p.done ? doneWord + ' ✓' : word + '…'}</span>
      </div>`).join('')}</div>`;
  }

  function hurry() {
    const s = g();
    if (!api.isHost) return '';
    const list = s.players.filter(p => p.id !== s.artist);
    const done = list.filter(p => p.done).length;
    return `<button class="soft" data-act="hurry">Не чекати (${done} з ${list.length})</button>`;
  }

  function scoreboard(withGain) {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    return `<div class="card">${list.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && p.id === me().id ? ' <span class="muted">(ти)</span>' : ''}</span>
        ${withGain && p.gain ? `<span class="plus">+${p.gain}</span>` : ''}
        <span class="pts">${p.score}</span>
      </div>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    const pick = (act, list, value, label) => api.isHost
      ? `<div class="chips">${list.map(([v, l]) => `<button class="chip ${value === v ? 'on' : ''}" data-act="${act}" data-v="${v}">${l}</button>`).join('')}</div>`
      : `<div class="chips"><span class="chip on">${label}</span></div>`;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack">
      <b>Теми</b>${pick('spice', SPICES, s.spice, (SPICES.find(x => x[0] === s.spice) || [])[1])}
      <b>Раундів</b>${pick('rounds', s.roundOptions.map(k => [k, String(k)]), s.rounds, String(s.rounds))}
      <b>Час на малюнок</b>${pick('drawSeconds', s.drawOptions.map(k => [k, k + ' с']), s.drawSeconds, s.drawSeconds + ' с')}
      <div class="muted small">Кожен таємно малює свою дивну тему. Потім малюнки показують по черзі: інші вигадують
        фальшиві назви, а тоді всі шукають справжню. Вгадав(ла) — +2, хтось повівся на твою вигадку — +1 за кожного, художнику — +1 за кожного, хто вгадав.</div></div>`;
    if (!api.isHost) h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне</span></div>`;
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function tools() {
    const sw = COLORS.map((c, i) => i === ERASER ? '' :
      `<button class="sw ${color === i ? 'on' : ''}" style="background:${c}" data-act="color" data-v="${i}" aria-label="колір"></button>`).join('');
    const sizes = SIZES.map((w, i) =>
      `<button class="size ${size === i ? 'on' : ''}" data-act="size" data-v="${i}" aria-label="товщина"><i style="width:${6 + i * 7}px;height:${6 + i * 7}px"></i></button>`).join('');
    const empty = !pad.strokes.length;
    return `<div class="tools">
      <div class="swatches">${sw}<button class="sw eraser ${color === ERASER ? 'on' : ''}" data-act="color" data-v="${ERASER}" aria-label="гумка">🧽</button></div>
      <div class="row">${sizes}<span class="grow"></span>
        <button class="mini ghost" data-act="undo" ${empty ? 'disabled' : ''}>↶ Назад</button>
        ${armed === 'clear'
          ? '<button class="mini danger" data-act="clear">Стерти все?</button>'
          : `<button class="mini ghost" data-act="arm" data-v="clear" ${empty ? 'disabled' : ''}>🗑</button>`}
      </div></div>`;
  }

  function drawView() {
    const s = g(), m = me();
    syncPad();
    let h = header();
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    if (!m) {
      return h + `<div class="card flat center"><span class="spinner"></span> <span class="muted">Усі малюють…</span></div>` + progress('малює', 'готово') + hurry();
    }
    if (m.done) {
      h += `<div class="banner meh">Готово ✓<div class="small muted">Тема: «${esc(m.prompt)}». Чекаємо інших.</div></div>`;
      h += artSvg(pad.strokes, 'small');
      h += `<button class="ghost" data-act="unsubmit">Домалювати</button>`;
    } else {
      h += `<div class="card prompt-card">
        <div class="muted small">${m.adult ? '🔥 ' : ''}Намалюй — тільки ти знаєш, що це:</div>
        <div class="prompt">${esc(m.prompt)}</div>
        <div class="row"><span class="muted small grow">Без букв і цифр!</span>
          ${m.canSwap ? '<button class="mini ghost" data-act="swap">Інша тема</button>' : ''}</div>
      </div>`;
      h += `<div id="padSlot" class="padslot"></div>`;
      if (full()) h += `<div class="notice small">Місце на малюнку скінчилось — можна лише відмінити штрихи.</div>`;
      h += tools();
      h += `<button class="big" data-act="submit" ${pad.strokes.length ? '' : 'disabled'}>Готово</button>`;
    }
    h += `<h2>Малюють</h2>` + progress('малює', 'готово') + hurry();
    return h;
  }

  function fakeView() {
    const s = g(), m = me();
    let h = header() + artSvg(s.art);
    if (!m) {
      h += `<div class="card flat center muted">Гравці вигадують назви…</div>`;
    } else if (isArtist()) {
      h += `<div class="banner meh">Це твій малюнок 🎨<div class="small muted">«${esc(m.prompt)}». Інші вигадують фальшиві назви — не підказуй!</div></div>`;
    } else {
      h += `<div class="card stack">
        <b>Що це? Вигадай назву, яка обдурить інших</b>
        ${m.tooTrue ? '<div class="notice small">Ого, це і є справжня назва! Тримай її в секреті й вигадай іншу — за вгадування отримаєш очки в голосуванні.</div>' : ''}
        <input id="fake${s.step}" type="text" maxlength="60" placeholder="Наприклад: «Кіт на ковзанах»" value="${esc(m.fake || '')}" autocomplete="off">
        <button class="big" data-act="fake">${m.fake ? 'Змінити' : 'Надіслати'}</button>
        ${m.fake ? `<div class="muted small center">Твоя вигадка: «${esc(m.fake)}»</div>` : ''}
      </div>`;
    }
    h += `<h2>Вигадують</h2>` + progress('вигадує', 'є') + hurry();
    return h;
  }

  function voteView() {
    const s = g(), m = me();
    const voting = m && !isArtist();
    let h = header() + artSvg(s.art);
    if (m && isArtist()) h += `<div class="banner meh">Твій малюнок: «${esc(m.prompt)}»<div class="small muted">Дивись, хто вгадає.</div></div>`;
    h += `<h2>${voting ? 'Що тут насправді намальовано?' : 'Варіанти'}</h2><div class="options">${s.options.map((o, i) => voting
      ? `<button class="opt ${m.vote === i ? 'picked' : ''}" data-act="vote" data-v="${i}" ${o.mine ? 'disabled' : ''}>${esc(o.text)}${o.mine ? ' <span class="small muted">(твоя)</span>' : ''}</button>`
      : `<div class="opt">${esc(o.text)}</div>`).join('')}</div>`;
    h += `<h2>Голосують</h2>` + progress('думає', 'обрав(ла)') + hurry();
    return h;
  }

  function resultView() {
    const s = g(), m = me();
    const mine = m && s.players.find(p => p.id === m.id);
    const gain = mine ? mine.gain : 0;
    let h = header() + artSvg(s.art);
    h += `<div class="banner good pop"><div class="small">Насправді це</div><div class="truth">«${esc(s.truth)}»</div>
      <div class="small muted">малював(ла) ${nameOf(s.artist)}</div></div>`;
    if (m) h += `<div class="center small">${gain ? `<b class="plus">+${gain} ${pts(gain)}</b> тобі за цей малюнок` : '<span class="muted">Цього разу без очок</span>'}</div>`;
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    const opts = s.options.slice().sort((a, b) => (b.real - a.real) || (b.voters.length - a.voters.length));
    h += `<div class="options">${opts.map(o => `
      <div class="opt result ${o.real ? 'real' : ''}">
        <div class="row"><b class="grow">${esc(o.text)}</b>${o.real ? '<span class="tag ok">правда</span>' : '<span class="tag">вигадка</span>'}</div>
        ${o.real ? '' : `<div class="small muted">вигадав(ла): ${o.authors.map(nameOf).join(', ') || '—'}</div>`}
        <div class="small">${o.voters.length ? '🗳 ' + o.voters.map(nameOf).join(', ') : '<span class="muted">ніхто не обрав</span>'}</div>
      </div>`).join('')}</div>`;
    h += `<h2>Рахунок</h2>` + scoreboard(true);
    const label = !s.lastInRound ? 'Наступний малюнок' : (s.round >= s.rounds ? 'Підсумки' : 'Наступний раунд');
    h += (api.isHost || isArtist())
      ? `<button class="big" data-act="next">${label}</button>`
      : '<div class="muted center small">Далі — коли хост чи художник натисне.</div>';
    return h;
  }

  function overView() {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    const top = list.length ? list[0].score : 0;
    const winners = list.filter(p => p.score === top).map(p => esc(p.name)).join(', ');
    const liar = list.slice().sort((a, b) => b.fooled - a.fooled)[0];
    const eye = list.slice().sort((a, b) => b.found - a.found)[0];
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="banner good pop"><h1>🏆 ${winners}</h1><div>${top} ${pts(top)}</div></div>`;
    h += scoreboard(false);
    if (liar && liar.fooled > 0) h += `<div class="card flat center small">🤥 Найкращий брехун: <b>${esc(liar.name)}</b> — обдурив(ла) ${liar.fooled} ${times(liar.fooled)}</div>`;
    if (eye && eye.found > 0) h += `<div class="card flat center small">🔍 Найзіркіше око: <b>${esc(eye.name)}</b> — вгадав(ла) ${eye.found} ${times(eye.found)}</div>`;
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне заново.</div>';
    return h;
  }

  function sendFake() {
    const s = g();
    const input = document.getElementById('fake' + s.step);
    const v = (input && input.value || '').trim();
    if (!v) { input && input.focus(); return; }
    api.send({ t: 'fake', text: v });
    if (input) input.blur();
  }

  PG.register('draw', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      // Mid-stroke, keep the screen as it is: replacing the markup would pull the canvas out from under the finger.
      if (s.phase === 'DRAW' && cur && lastDrawHtml) return lastDrawHtml;
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'DRAW': h = drawView(); break;
        case 'FAKE': h = fakeView(); break;
        case 'VOTE': h = voteView(); break;
        case 'RESULT': h = resultView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      const html = `<div class="draw stack">${h}</div>`;
      lastDrawHtml = s.phase === 'DRAW' ? html : '';
      queueMicrotask(mount);
      return html;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.phase !== next.phase || prev.step !== next.step || prev.round !== next.round) armed = null;
      // A fresh canvas for every DRAW phase (re-read from the server, so a reload keeps the drawing).
      if (!(prev && prev.phase === 'DRAW' && next.phase === 'DRAW' && prev.round === next.round)) {
        pad = { key: null, strokes: [], dirty: false };
        cur = null;
      }
      if (prev && prev.phase !== next.phase) a.vibrate(120);
    },

    menu(a) {
      api = a;
      const s = g();
      if (!s) return '';
      const conf = (act, l) => armed === act
        ? `<button class="danger" data-act="${act}">Точно? ${l}</button>`
        : `<button class="soft" data-act="arm" data-v="${act}">${l}</button>`;
      return (s.phase !== 'LOBBY' ? conf('abort', 'Перервати гру') : '') + conf('newGame', 'Скинути рахунок');
    },

    key(e, a) {
      api = a;
      if (e.key === 'Enter' && e.target.id && e.target.id.startsWith('fake')) { e.preventDefault(); sendFake(); }
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const v = el.dataset.v;
      switch (el.dataset.act) {
        case 'spice': return a.send({ t: 'spice', v });
        case 'rounds': return a.send({ t: 'rounds', v: +v });
        case 'drawSeconds': return a.send({ t: 'drawSeconds', v: +v });
        case 'start': return a.send({ t: 'start' });
        case 'hurry': return a.send({ t: 'hurry' });
        case 'next': return a.send({ t: 'next' });
        case 'swap': return a.send({ t: 'swap' });
        case 'color': color = +v; return a.render();
        case 'size': size = +v; return a.render();
        case 'undo':
          if (!pad.strokes.length) return;
          pad.strokes.pop(); pad.dirty = true; scheduleSync(); redraw();
          return a.render();
        case 'clear':
          armed = null;
          pad.strokes = []; pad.dirty = true; flush(); redraw();
          return a.render();
        case 'submit':
          if (!pad.strokes.length) return;
          clearTimeout(syncTimer); syncTimer = null; pad.dirty = false;
          a.send({ t: 'submit', art: pad.strokes });
          return;
        case 'unsubmit': return a.send({ t: 'unsubmit' });
        case 'fake': return sendFake();
        case 'vote': return a.send({ t: 'vote', i: +v });
        case 'arm': armed = v; a.render(); setTimeout(() => { if (armed === v) { armed = null; a.render(); } }, 4000); return;
        case 'abort': case 'newGame': armed = null; a.send({ t: el.dataset.act }); return a.render();
      }
    },
  });
})();
