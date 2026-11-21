// «Брехун» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  let armed = null;          // two-tap confirmation of a call: 'liar' | 'exact'
  let selQ = 1, selF = 2;    // the bid being composed
  let selKey = '';           // round + bids count the selection belongs to

  const FACES = ['', '⚀', '⚁', '⚂', '⚃', '⚄', '⚅'];
  const g = () => api.g;
  const me = () => g() && g().me;
  const lastBid = () => { const b = g().bids; return b[b.length - 1] || null; };
  const myTurn = () => !!(me() && g().turn === me().id);

  // ---------- pieces ----------
  /** One die; [hit]: it counts for the face in question (wild ones included). */
  function die(v, hit) {
    const wild = g().wild && v === 1;
    return `<span class="die ${hit ? 'hit' : ''} ${wild ? 'wild' : ''}">${FACES[v]}</span>`;
  }

  const counts = (v, f) => v === f || (g().wild && v === 1);

  function bidText(b) {
    return `<b>${b.q}</b> × <span class="die sm">${FACES[b.f]}</span>`;
  }

  function header() {
    const s = g();
    return `<div class="row"><span class="tag">раунд ${s.round}</span><span class="grow"></span>
      <span class="tag accent">🎲 на столі: ${s.total}</span></div>`;
  }

  function playersList(showDice) {
    const s = g();
    const res = s.result;
    const f = res && res.bid.f;
    return `<div class="card">${s.players.filter(p => p.inGame).map(p => {
      const now = s.phase === 'BID' && s.turn === p.id;
      let right;
      if (showDice && p.dice) right = `<span class="dice-row">${p.dice.map(v => die(v, f && counts(v, f))).join('')}</span>`;
      else if (p.out) right = '<span class="small muted">вибув(ла)</span>';
      else right = `<span class="pts">${p.count}</span><span class="small muted">🎲</span>`;
      let mark = '';
      if (res && res.loser === p.id) mark = ' <span class="small c-bad">−1</span>';
      if (res && res.gainer === p.id) mark = ' <span class="small c-good">+1</span>';
      return `<div class="li ${now ? 'now' : ''}" data-k="${esc(p.id)}">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${mark}</span>${right}</div>`;
    }).join('')}</div>`;
  }

  function myDice() {
    const m = me();
    if (!m || !m.inGame) return `<div class="card flat center muted">Ти приєднався під час гри — зіграєш у наступній.</div>`;
    if (!m.alive) return `<div class="card flat center muted">Кості скінчились — ти вибув(ла). Дивись і підказуй… або ні 😏</div>`;
    const b = lastBid();
    const f = b ? b.f : null;
    return `<div class="card center stack tight">
      <div class="muted small">Твої кості — бачиш лише ти</div>
      <div class="dice-row big">${m.dice.map(v => die(v, f && counts(v, f))).join('')}</div></div>`;
  }

  function currentBid() {
    const s = g();
    const b = s.bids[s.bids.length - 1];
    if (!b) return `<div class="card flat center muted">Ставок ще немає.</div>`;
    return `<div class="hero center stack tight" data-k="bid-${s.bids.length}">
      <div class="muted small">${esc(b.name)} каже: на столі щонайменше</div>
      <div class="headline fx-pop">${bidText(b)}</div></div>`;
  }

  /** Expected count of face [f] given my dice: a little arithmetic help for the bid. */
  function hint(f) {
    const s = g();
    const mine = (me().dice || []).filter(v => counts(v, f)).length;
    const others = s.total - (me().dice || []).length;
    const exp = others * (s.wild ? 1 / 3 : 1 / 6);
    return `У тебе ${FACES[f]}: <b>${mine}</b>. У ${others} чужих кістках у середньому ≈ <b>${exp.toFixed(1)}</b>.`;
  }

  function minQ(f) {
    const b = lastBid();
    if (!b) return 1;
    return f > b.f ? b.q : b.q + 1;
  }

  function syncSelection() {
    const s = g();
    const key = s.round + ':' + s.bids.length;
    if (key === selKey) return;
    selKey = key;
    armed = null;
    const b = lastBid();
    selF = b ? b.f : s.minFace;
    selQ = minQ(selF);
    if (selQ > s.total) {   // nothing higher on this face: try the next
      for (let f = selF + 1; f <= 6; f++) if (minQ(f) <= s.total) { selF = f; selQ = minQ(f); break; }
    }
  }

  function bidControls() {
    const s = g();
    syncSelection();
    const lo = minQ(selF);
    const can = selQ >= lo && selQ <= s.total;
    let h = `<div class="card hl stack"><b>Твій хід</b>
      <div class="row faces">${[1, 2, 3, 4, 5, 6].filter(f => f >= s.minFace).map(f =>
        `<button class="pick sq ${f === selF ? 'on' : ''}" data-act="face" data-v="${f}" ${minQ(f) > s.total ? 'disabled' : ''}>${FACES[f]}</button>`).join('')}</div>
      <div class="row qty">
        <button class="pick sq" data-act="q" data-v="-1" ${selQ <= lo ? 'disabled' : ''}>−</button>
        <span class="grow center word sm">${selQ} × ${FACES[selF]}</span>
        <button class="pick sq" data-act="q" data-v="1" ${selQ >= s.total ? 'disabled' : ''}>+</button>
      </div>
      <div class="muted small">${hint(selF)}</div>
      <button class="big" data-act="bid" ${can ? '' : 'disabled'}>Ставлю: ${selQ} × ${FACES[selF]}</button>`;
    if (lastBid()) {
      h += `<div class="row">
        ${armed === 'liar'
          ? `<button class="grow danger" data-act="liar">Точно «Брешеш!»?</button>`
          : `<button class="grow soft" data-act="arm" data-v="liar">🤥 Брешеш!</button>`}
        ${s.exactCall ? (armed === 'exact'
          ? `<button class="grow danger" data-act="exact">Точно «Рівно!»?</button>`
          : `<button class="grow soft" data-act="arm" data-v="exact">🎯 Рівно!</button>`) : ''}
      </div>`;
    }
    return h + '</div>';
  }

  function history() {
    const s = g();
    if (s.bids.length < 2) return '';
    return `<h2>Ставки раунду</h2><div class="card">${s.bids.slice(0, -1).reverse().map(b =>
      `<div class="li"><span class="name">${esc(b.name)}</span><span>${bidText(b)}</span></div>`).join('')}</div>`;
  }

  function resultBanner() {
    const s = g();
    const r = s.result;
    if (!r) return '';
    const what = r.call === 'LIAR' ? '🤥 «Брешеш!»' : '🎯 «Рівно!»';
    let verdict;
    if (r.call === 'LIAR') verdict = r.loser === r.caller
      ? `Ставка чесна — ${esc(r.callerName)} втрачає кість.`
      : `Таки брехня — ${esc(r.loserName)} втрачає кість.`;
    else verdict = r.gainer ? `Рівно! ${esc(r.gainerName)} повертає кість.`
      : r.loser ? `Не вгадав(ла) — ${esc(r.loserName)} втрачає кість.` : `Рівно! Але кісток уже максимум.`;
    if (r.out) verdict += ` ${esc(r.loserName)} вибуває.`;
    const good = me() && (r.gainer === me().id || (r.loser && r.loser !== me().id));
    return `<div class="banner ${me() && r.loser === me().id ? 'bad' : good ? 'good' : ''}">
      <div class="small muted">${esc(r.callerName)}: ${what} на ${r.bid.q} × ${FACES[r.bid.f]}</div>
      <h1>Насправді: ${r.actual} × ${FACES[r.bid.f]}</h1><div>${verdict}</div></div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    const dice = [];
    for (let d = s.minDice; d <= s.maxDice; d++) dice.push(d);
    h += `<div class="card stack"><b>Кісток у кожного</b>
      ${api.isHost
        ? `<div class="chips">${dice.map(d => `<button class="chip ${s.startDice === d ? 'on' : ''}" data-act="dice" data-v="${d}">${d}</button>`).join('')}</div>
           <label class="toggle"><input type="checkbox" class="switch" data-act="wild" ${s.wild ? 'checked' : ''}> ⚀ Одиниці — джокери</label>
           <label class="toggle"><input type="checkbox" class="switch" data-act="exactCall" ${s.exactCall ? 'checked' : ''}> 🎯 Можна казати «Рівно!»</label>`
        : `<div class="chips"><span class="chip on">${s.startDice} 🎲</span>
           ${s.wild ? '<span class="chip">⚀ джокери</span>' : ''}${s.exactCall ? '<span class="chip">🎯 «Рівно!»</span>' : ''}</div>`}
      <div class="muted small">Кожен бачить лише свої кості. По черзі підвищуйте ставку «на столі щонайменше N кісток з гранню X»
        або викривайте попередню: «Брешеш!». Хто помилився — втрачає кість. Останній із костями перемагає.</div></div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function bidView() {
    const s = g();
    let h = header();
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += currentBid() + myDice();
    if (myTurn()) h += bidControls();
    else {
      const t = s.players.find(p => p.id === s.turn);
      if (t) h += ui.wait(`Хід: <b>${esc(t.name)}</b>`, t.name);
    }
    h += playersList(false) + history();
    return h;
  }

  function revealView() {
    const s = g();
    let h = header() + resultBanner() + playersList(true);
    h += api.isHost
      ? `<button class="big" data-act="next">Наступний раунд</button>`
      : '<div class="muted center small">Хост почне наступний раунд.</div>';
    return h;
  }

  function overView() {
    const s = g();
    const won = me() && s.winner === me().id;
    let h = resultBanner();
    h += `<div class="banner ${won ? 'good' : ''}"><span class="emoji">🏆</span><h1>${won ? 'Ти переміг(ла)!' : esc(s.winnerName || '?') + ' перемагає!'}</h1></div>`;
    h += playersList(true);
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне нову гру.</div>';
    return h;
  }

  PG.register('liar', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'BID': h = bidView(); break;
        case 'REVEAL': h = revealView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="liar stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (prev && prev.phase !== next.phase) a.vibrate(120);
      const mine = st => st && st.me && st.turn === st.me.id && st.phase === 'BID';
      if (mine(next) && !mine(prev)) a.vibrate([60, 60, 60]);
    },

    menu() { return ['newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const s = g();
      switch (el.dataset.act) {
        case 'dice': return a.send({ t: 'dice', v: +el.dataset.v });
        case 'wild': return a.send({ t: 'wild', v: el.checked });
        case 'exactCall': return a.send({ t: 'exactCall', v: el.checked });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'face':
          selF = +el.dataset.v;
          selQ = Math.min(Math.max(selQ, minQ(selF)), s.total);
          return a.render();
        case 'q':
          selQ = Math.min(Math.max(selQ + +el.dataset.v, minQ(selF)), s.total);
          return a.render();
        case 'bid': return a.send({ t: 'bid', q: selQ, f: selF });
        case 'arm': {
          const v = armed = el.dataset.v;
          a.render();
          setTimeout(() => { if (armed === v) { armed = null; a.render(); } }, 4000);
          return;
        }
        case 'liar': armed = null; return a.send({ t: 'liar' });
        case 'exact': armed = null; return a.send({ t: 'exact' });
      }
    },
  });
})();
