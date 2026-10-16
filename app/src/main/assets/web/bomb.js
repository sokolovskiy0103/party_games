// «Сапери» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  let api = PG.api;
  let sel = null;    // { to, i } — the teammate's wire I'm pointing at
  let guess = null;  // value I'm about to name (0 = yellow)

  const YELLOW = 0;
  const g = () => api.g;
  const me = () => g() && g().me;
  const myTurn = () => !!(g() && me() && g().phase === 'PLAY' && g().turn === me().id);
  const nameOf = id => ((g().players.find(p => p.id === id) || {}).name) || '?';
  const guessLabel = v => (v === YELLOW ? '🟡 жовтий' : String(v));

  // ---------- pieces ----------
  /** One wire tile. [w]: { k, v, c, i } (missing k/v = hidden). */
  function tile(w, idx, extra) {
    const o = extra || {};
    const cls = ['wire', w.k ? 'k-' + w.k : 'hidden', w.c ? 'cut' : '', w.i && !w.c ? 'info' : '', o.sel ? 'sel' : '', o.last ? 'last' : '', o.pick ? 'pick' : '']
      .filter(Boolean).join(' ');
    const face = w.v || (w.k === 'y' ? '🟡' : w.k === 'r' ? '🔴' : '?');
    const attrs = o.pick ? ` data-act="pick" data-to="${esc(o.to)}" data-i="${idx}"` : o.show ? ` data-act="show" data-i="${idx}"` : '';
    return `<span class="${cls}"${attrs}><b>${esc(face)}</b><i>${idx + 1}</i></span>`;
  }

  function fuse() {
    const s = g();
    let h = '';
    for (let i = 0; i < s.maxMistakes; i++) h += `<span class="seg-f ${i < s.mistakes ? 'on' : ''}"></span>`;
    h += `<span class="seg-f boom ${s.mistakes > s.maxMistakes ? 'on' : ''}">💥</span>`;
    return `<span class="fuse" title="Помилки: ${s.mistakes} з ${s.maxMistakes} допустимих">${h}</span>`;
  }

  function header() {
    const s = g();
    const lv = s.levels[s.level - 1];
    return `<div class="row head"><span class="tag">рівень ${s.level} · ${esc(lv.name)}</span><span class="grow"></span>${fuse()}</div>`;
  }

  /** Public board: how many of each blue value are cut, which yellows may be in, how many reds. */
  function board() {
    const s = g();
    const cells = s.cutBlue.map((n, i) => {
      let pips = '';
      for (let k = 0; k < 4; k++) pips += `<i class="${k < n ? 'on' : ''}"></i>`;
      return `<span class="cell ${n === 4 ? 'done' : ''}"><b>${i + 1}</b><span class="pips">${pips}</span></span>`;
    }).join('');
    let extra = '';
    if (s.yellows) {
      const cand = s.yellowShown.length > s.yellows ? ` з ${s.yellowShown.join(', ')}` : `: ${s.yellowShown.join(', ')}`;
      extra += `<span class="chip">🟡 ${s.cutYellow}/${s.yellows}${esc(cand)}</span>`;
    }
    if (s.reds) extra += `<span class="chip">🔴 у бомбі: ${s.reds}</span>`;
    return `<div class="card stack"><div class="board">${cells}</div>${extra ? `<div class="chips">${extra}</div>` : ''}</div>`;
  }

  function myHand(mode) {
    const m = me();
    if (!m || !m.inGame) return `<div class="card flat center muted">Ти приєднався під час гри — зіграєш у наступній.</div>`;
    const s = g();
    const show = mode === 'setup' && !m.ready;
    const last = s.lastTarget === m.id ? s.lastIndex : -1;
    return `<h2>Твої дроти</h2><div class="card"><div class="hand mine">${m.wires.map((w, i) =>
      tile(w, i, { show: show && w.k === 'b', last: i === last })).join('')}</div>
      <div class="muted small">${show ? 'Торкнись синього дроту, який побачать усі.' : 'Дроти з рамкою бачать усі. Номери вголос не називай!'}</div></div>`;
  }

  function others() {
    const s = g();
    const list = s.players.filter(p => p.inGame && !(me() && p.id === me().id));
    if (!list.length) return '';
    const canPick = myTurn();
    return `<h2>Напарники</h2><div class="stack">${list.map(p => `
      <div class="card mate ${s.turn === p.id ? 'turn' : ''}">
        <div class="row"><span class="dot ${p.connected ? '' : 'off'}"></span><b class="grow">${esc(p.name)}</b>
          ${s.turn === p.id ? '<span class="tag">хід</span>' : ''}${s.phase === 'SETUP' ? (p.ready ? '<span class="small good">✓</span>' : '<span class="small muted">обирає…</span>') : ''}</div>
        <div class="hand">${p.wires.map((w, i) => tile(w, i, {
          pick: canPick && !w.c && !(w.k === 'r' && w.i), to: p.id,
          sel: sel && sel.to === p.id && sel.i === i,
          last: s.lastTarget === p.id && s.lastIndex === i,
        })).join('')}</div>
      </div>`).join('')}</div>`;
  }

  function actions() {
    const s = g();
    const m = me();
    if (!m || !m.inGame) return '';
    if (!myTurn()) return `<div class="card flat center"><span class="spinner"></span> <span class="muted">Ходить <b>${esc(nameOf(s.turn))}</b></span></div>`;
    let h = `<div class="card stack act"><div class="your-turn">Твій хід ✂️</div>`;
    if (m.canReveal) {
      h += `<div class="muted small">У тебе лишились тільки червоні дроти.</div><button class="big" data-act="reds">Показати червоні</button></div>`;
      return h;
    }
    if (!sel) {
      h += `<div class="muted small">Торкнись дроту напарника, номер якого вгадуєш. Назвати можна лише номер, що є у тебе.</div>`;
    } else {
      h += `<div>Дріт <b>№${sel.i + 1}</b> у <b>${esc(nameOf(sel.to))}</b> — це…</div>
        <div class="chips guesses">${m.guesses.slice().sort((a, b) => (a || 99) - (b || 99)).map(v =>
          `<button class="chip ${guess === v ? 'on' : ''}" data-act="guess" data-v="${v}">${guessLabel(v)}</button>`).join('')}</div>`;
      h += guess === null
        ? `<button class="ghost" data-act="cancel">Інший дріт</button>`
        : `<div class="row"><button class="ghost" data-act="cancel">Скасувати</button><button class="big grow" data-act="cut">✂️ Різати: ${guessLabel(guess)}</button></div>`;
    }
    if (m.solo && m.solo.length) {
      h += `<div class="muted small">Усі неперерізані дроти цих номерів — у тебе:</div><div class="chips">${m.solo.map(v =>
        `<button class="chip ok" data-act="solo" data-v="${v}">✂️ Сам: ${guessLabel(v)}</button>`).join('')}</div>`;
    }
    return h + '</div>';
  }

  function logList() {
    const s = g();
    if (!s.log || !s.log.length) return '';
    return `<h2>Журнал</h2><div class="card log">${s.log.map((l, i) => `<div class="${i === 0 ? 'fresh' : 'muted'}">${esc(l)}</div>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Рівень</b>
      <div class="stack levels">${s.levels.map(l => {
        const on = l.n === s.level;
        const body = `<b>${l.n}. ${esc(l.name)}</b> <span class="muted small">${esc(l.desc)}</span>`;
        return api.isHost
          ? `<button class="soft lvl ${on ? 'on' : ''}" data-act="level" data-v="${l.n}">${body}</button>`
          : (on ? `<div class="lvl on">${body}</div>` : '');
      }).join('')}</div>
      <div class="muted small">Граєте всі разом проти бомби. Кожен бачить лише свої дроти, відсортовані за номером.
        На своєму ході вкажи на дріт напарника й назви його номер — але тільки такий, що є і в тебе.
        Влучив — обидва дроти перерізано. Помилився — детонатор цокає. Червоний дріт — вибух.</div></div>`;
    if (s.best) h += `<div class="card flat center">Рекорд вечора: <b>рівень ${s.best}</b></div>`;
    if (!api.isHost) h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне</span></div>`;
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function setupView() {
    const s = g();
    const waiting = s.players.filter(p => p.inGame && !p.ready).map(p => p.name);
    return header() + board() + myHand('setup')
      + (waiting.length ? `<div class="card flat center muted small">Ще обирають: ${waiting.map(esc).join(', ')}</div>` : '')
      + others();
  }

  function playView() {
    return header() + actions() + others() + myHand('play') + board() + logList();
  }

  function endView(won) {
    const s = g();
    const lv = s.levels[s.level - 1];
    let h = won
      ? `<div class="banner good pop"><h1>Бомбу знешкоджено! 🎉</h1><div>Рівень ${s.level} «${esc(lv.name)}» пройдено${s.mistakes ? `, помилок: ${s.mistakes}` : ' без жодної помилки'}.</div></div>`
      : `<div class="banner bad pop"><h1>БАХ! 💥</h1><div>${s.mistakes > s.maxMistakes ? 'Детонатор не витримав ще однієї помилки.' : 'Перерізано червоний дріт.'}</div></div>`;
    h += header();
    h += `<h2>Усі дроти</h2><div class="stack">${s.players.filter(p => p.inGame).map(p => `
      <div class="card mate"><b>${esc(p.name)}</b><div class="hand">${p.wires.map((w, i) =>
        tile(w, i, { last: s.lastTarget === p.id && s.lastIndex === i })).join('')}</div></div>`).join('')}</div>`;
    h += logList();
    const enough = s.players.length >= s.minPlayers;
    if (api.isHost) {
      h += won
        ? `<button class="big" data-act="next" ${enough ? '' : 'disabled'}>${s.level < s.levels.length ? 'Рівень ' + (s.level + 1) : 'Ще раз'}</button>`
        : `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Спробувати ще</button>`;
    } else h += '<div class="muted center small">Хост вирішить, що далі.</div>';
    return h;
  }

  PG.register('bomb', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'SETUP': h = setupView(); break;
        case 'PLAY': h = playView(); break;
        case 'WIN': h = endView(true); break;
        case 'LOSE': h = endView(false); break;
        default: h = '';
      }
      return `<div class="bomb stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.round !== next.round || prev.turn !== next.turn || prev.phase !== next.phase) { sel = null; guess = null; }
      if (prev && prev.phase !== next.phase) a.vibrate(120);
      if (prev && prev.seq !== next.seq) {
        if (next.last === 'boom') { a.vibrate([300, 100, 300, 100, 600]); a.buzzer(); }
        else if (next.last === 'miss') a.vibrate([200, 80, 200]);
        else if (next.last === 'hit' || next.last === 'solo') a.vibrate(60);
      }
      const mine = s => s && s.me && s.phase === 'PLAY' && s.turn === s.me.id;
      if (mine(next) && !mine(prev)) a.vibrate([80, 60, 80]);
      // The wire I pointed at got cut or its owner left: drop the selection.
      if (sel) {
        const p = next.players.find(x => x.id === sel.to);
        const w = p && p.wires[sel.i];
        if (!w || w.c) { sel = null; guess = null; }
      }
    },

    menu() { return [{ t: 'abort', label: '⏹ Перервати гру' }, 'newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const v = +el.dataset.v;
      switch (el.dataset.act) {
        case 'level': return a.send({ t: 'level', v });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'show': return a.send({ t: 'show', i: +el.dataset.i });
        case 'pick': sel = { to: el.dataset.to, i: +el.dataset.i }; guess = null; return a.render();
        case 'guess': guess = v; return a.render();
        case 'cancel': sel = null; guess = null; return a.render();
        case 'cut': {
          if (!sel || guess === null) return;
          const msg = { t: 'dual', to: sel.to, i: sel.i, v: guess };
          sel = null; guess = null;
          return a.send(msg);
        }
        case 'solo': return a.send({ t: 'solo', v });
        case 'reds': return a.send({ t: 'reds' });
      }
    },
  });
})();
