// «Сапери» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  // What I'm doing on my turn: tool = null (a plain cut) or { src: 'char'|'equip', e, needs, name, emoji }.
  let tool = null;
  let sel = [];        // [{ to, i }] picked teammate wires
  let target = null;   // picked teammate (Сканер)
  let guess = null;    // picked value (0 = yellow)

  const YELLOW = 0;
  const g = () => api.g;
  const me = () => g() && g().me;
  const myTurn = () => !!(g() && me() && g().phase === 'PLAY' && g().turn === me().id);
  const player = id => g().players.find(p => p.id === id) || {};
  const nameOf = id => player(id).name || '?';
  const guessLabel = v => (v === YELLOW ? '🟡' : String(v));
  const lastIdx = id => (g().lastTarget === id ? g().lastIndex || [] : []);

  function reset() { tool = null; sel = []; target = null; guess = null; }

  // ---------- the wire ----------
  const COLORS = { b: '#3d8bff', y: '#ffd23f', r: '#ff4d5e' };
  const WHOLE = 'M14 12 C 5 28, 23 50, 14 70';
  const TOP = 'M14 12 C 8 22, 16 30, 13 36';
  const BOTTOM = 'M15 46 C 20 54, 20 62, 14 70';

  /** A wire between two terminals: colour = kind (grey if unknown), a gap with copper ends once cut. */
  function wire(w, idx, o) {
    o = o || {};
    const known = !!w.k;
    const color = known ? COLORS[w.k] : '#56607a';
    const face = w.v || (w.k === 'y' ? 'Ж' : w.k === 'r' ? '!' : '?');
    const cable = d => `<path class="cable" d="${d}" stroke="${color}"/>${known ? '' : `<path class="mystery" d="${d}"/>`}<path class="sheen" d="${d}"/>`;
    let body = w.c
      ? cable(TOP) + cable(BOTTOM)
        + '<path class="copper" d="M13 36 L10 41 M13 36 L13 42 M13 36 L16 40 M15 46 L12 41 M15 46 L15 40 M15 46 L18 42"/>'
      : cable(WHOLE);
    if (w.i && !w.c) {
      body += `<circle class="token" cx="14" cy="41" r="8.5"/><text class="token-t" x="14" y="44.5">${esc(w.v || (w.k === 'y' ? 'Ж' : '!'))}</text>`;
    }
    const svg = `<svg viewBox="0 0 28 92" aria-hidden="true">
      <rect class="term" x="5" y="1" width="18" height="12" rx="3"/><circle class="screw" cx="14" cy="7" r="2.6"/>
      ${body}
      <rect class="plate ${known ? 'k-' + w.k : ''}" x="2" y="70" width="24" height="20" rx="4"/>
      <text class="plate-t" x="14" y="${(w.v || '').length > 2 ? 84 : 85}">${esc(face)}</text></svg>`;
    const cls = ['wire', known ? 'k-' + w.k : 'unknown', w.c ? 'cut' : '', w.i && !w.c ? 'info' : '',
      o.sel ? 'sel' : '', o.last ? 'last' : '', o.pick || o.show ? 'tap' : ''].filter(Boolean).join(' ');
    const attrs = o.pick ? ` data-act="pick" data-to="${esc(o.to)}" data-i="${idx}"` : o.show ? ` data-act="show" data-i="${idx}"` : '';
    return `<span class="${cls}"${attrs}>${svg}<i>${idx + 1}</i></span>`;
  }

  /** Wires in one row when they fit a phone, two rows otherwise. */
  const handOpen = n => `<div class="hand" style="--cols:${Math.max(n <= 14 ? n : Math.ceil(n / 2), 6)}">`;

  // ---------- pieces ----------
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
    let h = `<div class="row head"><span class="tag">місія ${s.level} · ${esc(lv.name)}</span><span class="grow"></span>${fuse()}</div>`;
    if (s.phase === 'PLAY' && s.deadline) {
      const t = { deadline: s.deadline, total: s.turnSeconds * 1000, low: 15000, fmt: 'sec', cls: 'sm' };
      h += `<div class="row clock">${ui.timer(t)}${ui.timerBar(t)}</div>`;
    }
    const flags = [];
    if (s.shield) flags.push('🛡️ щит увімкнено');
    if (s.extra) flags.push('🔁 ще один хід');
    if (flags.length) h += `<div class="chips">${flags.map(f => `<span class="chip on">${f}</span>`).join('')}</div>`;
    return h;
  }

  function charBadge(c) {
    if (!c) return '';
    return `<span class="char ${c.used ? 'used' : ''}" title="${esc(c.desc)}">${c.emoji} ${esc(c.name)}</span>`;
  }

  /** Public board: cut counts per blue value with their equipment, yellows, reds. */
  function board() {
    const s = g();
    const eqBy = {};
    (s.equipment || []).forEach((e, k) => { eqBy[e.n] = Object.assign({ k }, e); });
    const cells = s.cutBlue.map((n, i) => {
      let pips = '';
      for (let k = 0; k < 4; k++) pips += `<i class="${k < n ? 'on' : ''}"></i>`;
      const e = eqBy[i + 1];
      const eq = e ? `<span class="eq ${e.used ? 'used' : e.unlocked ? 'open' : ''}" title="${esc(e.name + ': ' + e.desc)}">${e.emoji}</span>` : '';
      return `<span class="cell ${n === 4 ? 'done' : ''}"><b>${i + 1}</b><span class="pips">${pips}</span>${eq}</span>`;
    }).join('');
    let extra = '';
    if (s.yellows) {
      const cand = s.yellowShown.length > s.yellows ? ` з ${s.yellowShown.join(', ')}` : `: ${s.yellowShown.join(', ')}`;
      extra += `<span class="chip">🟡 ${s.cutYellow}/${s.yellows}${esc(cand)}</span>`;
    }
    if (s.reds) extra += `<span class="chip">🔴 у бомбі: ${s.reds}</span>`;
    const eqList = (s.equipment || []).length
      ? `<div class="eqlist">${s.equipment.map(e => `<div class="eqrow ${e.used ? 'used' : e.unlocked ? 'open' : ''}">
          <span class="eqn">${e.n}</span><span>${e.emoji} <b>${esc(e.name)}</b> <span class="muted small">— ${esc(e.desc)}</span></span>
          <span class="small">${e.used ? 'використано' : e.unlocked ? '✅' : '🔒'}</span></div>`).join('')}</div>
         <div class="muted small">Спорядження відмикається, коли перерізано два дроти його номера.</div>`
      : '';
    return `<h2>Пульт</h2><div class="card stack"><div class="board">${cells}</div>${extra ? `<div class="chips">${extra}</div>` : ''}${eqList}</div>`;
  }

  function myHand(mode) {
    const m = me();
    if (!m || !m.inGame) return `<div class="card flat center muted">Ти приєднався під час гри — зіграєш у наступній.</div>`;
    const show = mode === 'setup' && !m.ready;
    const last = lastIdx(m.id);
    const mine = player(m.id);
    return `<h2>Твоя панель ${charBadge(mine.char)}</h2><div class="card panel mine">
      ${handOpen(m.wires.length)}${m.wires.map((w, i) => wire(w, i, { show: show && w.k === 'b', last: last.includes(i) })).join('')}</div>
      <div class="muted small">${show ? 'Торкнись синього дроту, номер якого побачать усі.' : 'Жетон на дроті бачать усі. Номери вголос не називай!'}</div></div>`;
  }

  /** Wires I can point at right now (depends on the tool). */
  function wantsWires() {
    if (!myTurn()) return 0;
    if (!tool) return 1;
    return { WIRE: 1, WIRE_VALUE: 1, TWO_WIRES: 2 }[tool.needs] || 0;
  }

  function others() {
    const s = g();
    const list = s.players.filter(p => p.inGame && !(me() && p.id === me().id));
    if (!list.length) return '';
    const want = wantsWires();
    return `<h2>Напарники</h2><div class="stack">${list.map(p => {
      const last = lastIdx(p.id);
      const lockTo = want === 2 && sel.length ? sel[0].to : null;
      return `<div class="card panel mate ${s.turn === p.id ? 'turn' : ''}">
        <div class="row"><span class="dot ${p.connected ? '' : 'off'}"></span><b>${esc(p.name)}</b>${charBadge(p.char)}<span class="grow"></span>
          ${s.turn === p.id ? '<span class="tag">хід</span>' : ''}${s.phase === 'SETUP' ? (p.ready ? '<span class="small c-good">✓</span>' : '<span class="small muted">обирає…</span>') : ''}</div>
        ${handOpen(p.wires.length)}${p.wires.map((w, i) => wire(w, i, {
          pick: want && !w.c && !(w.k === 'r' && w.i) && (!lockTo || lockTo === p.id), to: p.id,
          sel: sel.some(x => x.to === p.id && x.i === i),
          last: last.includes(i),
        })).join('')}</div>
      </div>`;
    }).join('')}</div>`;
  }

  function valueChips(values) {
    return `<div class="chips guesses">${values.map(v =>
      `<button class="chip ${guess === v ? 'on' : ''}" data-act="guess" data-v="${v}">${guessLabel(v)}</button>`).join('')}</div>`;
  }

  function anyValues(withYellow) {
    const s = g();
    const vs = [];
    for (let v = 1; v <= s.maxBlue; v++) vs.push(v);
    if (withYellow && s.yellows) vs.push(YELLOW);
    return vs;
  }
  const myValues = () => (me().guesses || []).slice().sort((a, b) => (a || 99) - (b || 99));

  /** The tools I may use now: my character (once) and unlocked equipment. */
  function toolButtons() {
    const s = g();
    const mine = player(me().id).char;
    const btns = [];
    if (mine && !mine.used) btns.push({ src: 'char', e: -1, needs: mine.needs, name: mine.name, emoji: mine.emoji, desc: mine.desc });
    (s.equipment || []).forEach((e, k) => { if (e.unlocked && !e.used) btns.push({ src: 'equip', e: k, needs: e.needs, name: e.name + ' · ' + e.n, emoji: e.emoji, desc: e.desc }); });
    if (!btns.length) return '';
    return `<div class="tools"><div class="muted small">Інструменти (не завершують хід${mine && mine.id === 'DETECTOR' ? ', крім Детектора' : ''}):</div>
      <div class="chips">${btns.map(b => `<button class="chip tool ${tool && tool.src === b.src && tool.e === b.e ? 'on' : ''}"
        data-act="tool" data-src="${b.src}" data-e="${b.e}" data-needs="${b.needs}" data-name="${esc(b.name)}" data-emoji="${b.emoji}" title="${esc(b.desc)}">${b.emoji} ${esc(b.name)}</button>`).join('')}</div></div>`;
  }

  function confirmRow(label, ready) {
    return `<div class="row"><button class="ghost" data-act="cancel">Скасувати</button>
      <button class="big grow" data-act="go" ${ready ? '' : 'disabled'}>${label}</button></div>`;
  }

  function toolPanel() {
    const t = tool;
    let h = `<div class="toolhead">${t.emoji} <b>${esc(t.name)}</b></div>`;
    const s1 = sel[0];
    switch (t.needs) {
      case 'NONE':
        return h + confirmRow('Застосувати', true);
      case 'WIRE':
        h += s1 ? `<div>Дріт <b>№${s1.i + 1}</b> у <b>${esc(nameOf(s1.to))}</b></div>` : '<div class="muted small">Торкнись дроту напарника.</div>';
        return h + confirmRow('Застосувати', !!s1);
      case 'WIRE_VALUE':
        h += s1 ? `<div>Дріт <b>№${s1.i + 1}</b> у <b>${esc(nameOf(s1.to))}</b> порівняти з…</div>${valueChips(anyValues(false))}` : '<div class="muted small">Торкнись дроту напарника.</div>';
        return h + confirmRow('Порівняти', !!s1 && guess !== null);
      case 'TWO_WIRES':
        h += sel.length < 2
          ? `<div class="muted small">Торкнись ${sel.length ? 'ще одного дроту того ж напарника' : 'двох дротів одного напарника'}.</div>`
          : `<div>№${sel[0].i + 1} чи №${sel[1].i + 1} у <b>${esc(nameOf(sel[0].to))}</b> — це…</div>${valueChips(myValues())}`;
        return h + confirmRow(guess === null ? '📟 Різати' : `📟 Різати: ${guessLabel(guess)}`, sel.length === 2 && guess !== null);
      case 'PLAYER_VALUE': {
        const mates = g().players.filter(p => p.inGame && p.id !== me().id);
        h += `<div class="chips">${mates.map(p => `<button class="chip ${target === p.id ? 'on' : ''}" data-act="target" data-to="${esc(p.id)}">${esc(p.name)}</button>`).join('')}</div>`;
        if (target) h += `<div>Чи є в <b>${esc(nameOf(target))}</b>…</div>${valueChips(anyValues(true))}`;
        return h + confirmRow('Сканувати', !!target && guess !== null);
      }
      case 'VALUE':
        h += `<div>Який номер шукати?</div>${valueChips(anyValues(true))}`;
        return h + confirmRow('Шукати', guess !== null);
    }
    return h;
  }

  function actions() {
    const s = g();
    const m = me();
    if (!m || !m.inGame) return '';
    if (!myTurn()) return ui.wait(`Ходить <b>${esc(nameOf(s.turn))}</b>`, nameOf(s.turn));
    let h = `<div class="card hl stack"><div class="your-turn">Твій хід ✂️</div>`;
    if (m.canReveal) return h + `<div class="muted small">У тебе лишились тільки червоні дроти.</div><button class="big" data-act="reds">Показати червоні</button></div>`;
    if (tool) return h + toolPanel() + '</div>';
    const s1 = sel[0];
    if (!s1) {
      h += `<div class="muted small">Торкнись дроту напарника, номер якого вгадуєш. Назвати можна лише номер, що є і в тебе.</div>`;
    } else {
      h += `<div>Дріт <b>№${s1.i + 1}</b> у <b>${esc(nameOf(s1.to))}</b> — це…</div>${valueChips(myValues())}`;
      h += confirmRow(guess === null ? '✂️ Різати' : `✂️ Різати: ${guessLabel(guess)}`, guess !== null);
    }
    if (m.solo && m.solo.length) {
      h += `<div class="muted small">Усі неперерізані дроти цих номерів — у тебе:</div><div class="chips">${m.solo.map(v =>
        `<button class="chip ok" data-act="solo" data-v="${v}">✂️ Сам: ${guessLabel(v)}</button>`).join('')}</div>`;
    }
    return h + toolButtons() + '</div>';
  }

  function logList() {
    const s = g();
    if (!s.log || !s.log.length) return '';
    return `<h2>Журнал</h2><div class="card log">${s.log.map((l, i) => `<div class="${i === 0 ? 'fresh' : 'muted'}">${esc(l)}</div>`).join('')}</div>`;
  }

  function rulesChips(lv) {
    return lv.rules && lv.rules.length ? `<div class="chips">${lv.rules.map(r => `<span class="chip rule">${esc(r)}</span>`).join('')}</div>` : '';
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Місія</b>
      <div class="stack levels">${s.levels.map(l => {
        const on = l.n === s.level;
        const locked = l.n > s.best + 1;
        const body = `<b>${l.n}. ${esc(l.name)}</b> <span class="muted small">${esc(l.desc)}</span>${on ? rulesChips(l) : ''}`;
        return api.isHost
          ? `<button class="soft lvl ${on ? 'on' : ''} ${locked ? 'later' : ''}" data-act="level" data-v="${l.n}">${body}</button>`
          : (on ? `<div class="lvl on">${body}</div>` : '');
      }).join('')}</div>
      <div class="muted small">Граєте всі разом проти бомби. Кожен бачить лише свої дроти, відсортовані за номером.
        На своєму ході вкажи на дріт напарника й назви його номер — але тільки такий, що є і в тебе.
        Влучив — обидва дроти перерізано. Помилився — детонатор цокає. Червоний дріт — вибух.
        З другої місії в кожного є персонаж з одноразовою здібністю, а на пульті — спорядження.</div></div>`;
    if (s.best) h += `<div class="card flat center">Пройдено місій за вечір: <b>${s.best}</b></div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function briefing() {
    const s = g();
    const lv = s.levels[s.level - 1];
    const mine = me() && player(me().id).char;
    return `<div class="card stack brief"><div><b>${esc(lv.name)}</b> <span class="muted small">${esc(lv.desc)}</span></div>${rulesChips(lv)}
      ${mine ? `<div class="mychar"><span class="big-emoji">${mine.emoji}</span><div><b>Ти — ${esc(mine.name)}</b><div class="muted small">${esc(mine.desc)}. Раз за місію.</div></div></div>` : ''}</div>`;
  }

  function setupView() {
    const s = g();
    const waiting = s.players.filter(p => p.inGame && !p.ready).map(p => p.name);
    return header() + briefing() + myHand('setup')
      + (waiting.length ? `<div class="card flat center muted small">Ще обирають: ${waiting.map(esc).join(', ')}</div>` : '')
      + others() + board();
  }

  function playView() {
    return header() + actions() + others() + myHand('play') + board() + logList();
  }

  function endView(won) {
    const s = g();
    const lv = s.levels[s.level - 1];
    let h = won
      ? `<div class="banner good"><h1>Бомбу знешкоджено! 🎉</h1><div>Місію ${s.level} «${esc(lv.name)}» пройдено${s.mistakes ? `, помилок: ${s.mistakes}` : ' без жодної помилки'}.</div></div>`
      : `<div class="banner bad"><h1>БАХ! 💥</h1><div>${s.mistakes > s.maxMistakes ? 'Детонатор не витримав ще однієї помилки.' : 'Перерізано червоний дріт.'}</div></div>`;
    h += header();
    h += `<h2>Усі дроти</h2><div class="stack">${s.players.filter(p => p.inGame).map(p => `
      <div class="card panel mate"><div class="row"><b>${esc(p.name)}</b>${charBadge(p.char)}</div>${handOpen(p.wires.length)}${p.wires.map((w, i) =>
        wire(w, i, { last: lastIdx(p.id).includes(i) })).join('')}</div></div>`).join('')}</div>`;
    h += logList();
    const enough = s.players.length >= s.minPlayers;
    if (api.isHost) {
      h += won
        ? `<button class="big" data-act="next" ${enough ? '' : 'disabled'}>${s.level < s.levels.length ? 'Місія ' + (s.level + 1) : 'Ще раз'}</button>`
        : `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Спробувати ще</button>`;
    } else h += '<div class="muted center small">Хост вирішить, що далі.</div>';
    return h;
  }

  function send(a) {
    const s1 = sel[0] || {};
    if (!tool) {
      if (!sel.length || guess === null) return;
      a.send({ t: 'dual', to: s1.to, i: s1.i, v: guess });
    } else {
      const msg = { t: tool.src, e: tool.e, to: target || s1.to || '', i: s1.i == null ? -1 : s1.i, j: sel[1] ? sel[1].i : -1, v: guess == null ? -1 : guess };
      a.send(msg);
    }
    reset();
  }

  PG.register('bomb', {
    // background music per phase (shell.js): silent concentration over the wires; quieter over the result
    music: { WIN: 'low', LOSE: 'low' },
    musicLoop: 80.64,  // seconds of music.mp3 that loop: a whole number of bars
    sfx: ['cut', 'defused', 'explosion', 'fuse', 'tool'],  // own effects: sfx/<name>.mp3
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
      if (!prev || prev.round !== next.round || prev.turn !== next.turn || prev.phase !== next.phase) reset();
      if (prev && prev.phase !== next.phase) a.vibrate(120);
      if (prev && prev.seq !== next.seq) {
        if (next.last === 'boom') { a.vibrate([300, 100, 300, 100, 600]); a.sfx('explosion'); a.sfx('lose', 2500); }
        else if (next.last === 'miss') { a.vibrate([200, 80, 200]); a.sfx('wrong'); }
        else if (next.last === 'hit' || next.last === 'solo' || next.last === 'reds') { a.vibrate(60); a.sfx('cut'); }
        else if (next.last === 'tool') { a.vibrate(30); a.sfx('tool'); }
      }
      if (prev && prev.phase !== 'WIN' && next.phase === 'WIN') { a.sfx('defused'); a.sfx('win', 1600); }
      const mine = s => s && s.me && s.phase === 'PLAY' && s.turn === s.me.id;
      if (mine(next) && !mine(prev)) a.vibrate([80, 60, 80]);
      // A wire I pointed at got cut: drop it.
      sel = sel.filter(x => { const p = next.players.find(q => q.id === x.to); const w = p && p.wires[x.i]; return w && !w.c; });
      // The tool I picked is gone (used, or the game moved on).
      if (tool && tool.src === 'equip') { const e = (next.equipment || [])[tool.e]; if (!e || e.used || !e.unlocked) reset(); }
      if (tool && tool.src === 'char') { const c = (next.players.find(p => p.id === (next.me || {}).id) || {}).char; if (!c || c.used) reset(); }
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
        case 'pick': {
          const p = { to: el.dataset.to, i: +el.dataset.i };
          const want = wantsWires();
          const at = sel.findIndex(x => x.to === p.to && x.i === p.i);
          if (at >= 0) sel.splice(at, 1);
          else if (want === 2) { sel = sel.filter(x => x.to === p.to); if (sel.length < 2) sel.push(p); }
          else { sel = [p]; if (!tool) guess = null; }
          return a.render();
        }
        case 'target': target = el.dataset.to; return a.render();
        case 'guess': guess = v; return a.render();
        case 'tool': {
          const same = tool && tool.src === el.dataset.src && tool.e === +el.dataset.e;
          reset();
          if (!same) tool = { src: el.dataset.src, e: +el.dataset.e, needs: el.dataset.needs, name: el.dataset.name, emoji: el.dataset.emoji };
          return a.render();
        }
        case 'cancel': reset(); return a.render();
        case 'go': return send(a);
        case 'solo': return a.send({ t: 'solo', v });
        case 'reds': return a.send({ t: 'reds' });
      }
    },
  });
})();
