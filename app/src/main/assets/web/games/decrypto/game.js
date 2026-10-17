// «Шифр» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  let armed = null;        // two-tap confirmations
  let showBoards = true;   // the clue boards are long; let people fold them

  const TEAMS = [{ name: 'Сині', gen: 'синіх', icon: '🔵' }, { name: 'Червоні', gen: 'червоних', icon: '🔴' }];
  const SPICES = [['CLEAN', '😇 Звичайні'], ['MIXED', '🎲 Навпіл'], ['ADULT', '🔥 18+']];

  const g = () => api.g;
  const me = () => g() && g().me;
  const myTeam = () => (me() ? me().team : -1);
  const isEnc = () => !!(me() && me().encryptor);
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');
  const teamLabel = t => `<span class="tname t${t}">${TEAMS[t].icon} ${TEAMS[t].name}</span>`;
  const code = c => (c || []).map(d => d || '·').join(' · ');

  // ---------- pieces ----------
  function tokens(t) {
    const s = g().teams[t];
    const w = '⚪'.repeat(s.whites) + '<span class="faint">' + '⚪'.repeat(Math.max(0, 2 - s.whites)) + '</span>';
    const b = '⚫'.repeat(s.blacks) + '<span class="faint">' + '⚫'.repeat(Math.max(0, 2 - s.blacks)) + '</span>';
    return `<span class="tokens" title="Перехоплення / Провали">${w} ${b}</span>`;
  }

  function scoreHead() {
    const s = g();
    return `<div class="card flat score-head">
      <div class="row">${teamLabel(0)}<span class="grow"></span>${tokens(0)}</div>
      <div class="row">${teamLabel(1)}<span class="grow"></span>${tokens(1)}</div>
      <div class="muted small">Раунд ${s.round} з ${s.maxRounds}. ⚪ — перехопили код суперників (2 = перемога), ⚫ — не розгадали свій (2 = поразка).</div>
    </div>`;
  }

  function myWords() {
    const s = g();
    if (!s.words || myTeam() < 0) return '';
    return `<div class="card stack">
      <div class="row"><b>Ваші слова</b><span class="grow"></span>${teamLabel(myTeam())}</div>
      <div class="kw">${s.words.map((w, i) => `<div class="kword"><span class="badge">${i + 1}</span>${esc(w)}</div>`).join('')}</div>
    </div>`;
  }

  /** All clues a team has given so far, by keyword position. Own team sees the words as column titles. */
  function board(t) {
    const s = g();
    const tm = s.teams[t];
    const words = t === myTeam() ? s.words : (tm.words || null);
    return `<div class="card stack">
      <div class="row"><b>Дошка</b>${teamLabel(t)}</div>
      <div class="board">${tm.board.map((col, i) => `
        <div class="col">
          <div class="col-head"><span class="badge sm">${i + 1}</span>${words ? esc(words[i]) : '?'}</div>
          ${col.map(c => `<div class="bclue">${esc(c.clue)}</div>`).join('') || '<div class="muted small">—</div>'}
        </div>`).join('')}</div>
    </div>`;
  }

  function boards() {
    const s = g();
    if (!s.teams.some(t => t.board.some(c => c.length))) return '';
    const order = myTeam() === 1 ? [1, 0] : [0, 1];
    return `<button class="ghost" data-act="boards">${showBoards ? 'Сховати дошки' : 'Показати дошки підказок'}</button>
      ${showBoards ? order.map(board).join('') : ''}`;
  }

  /** Clue rows with a shared 1–4 picker per row. */
  function picker(target, clues, draft) {
    const locked = draft.locked;
    const rows = clues.map((c, slot) => `
      <div class="li">
        <span class="pclue">${esc(c)}</span>
        <span class="digits">${[1, 2, 3, 4].map(d => `<button class="pick sq ${draft.digits[slot] === d ? 'on' : ''}" data-act="digit" data-target="${target}" data-slot="${slot}" data-d="${d}" ${locked ? 'disabled' : ''}>${d}</button>`).join('')}</span>
      </div>`).join('');
    const complete = draft.digits.every(d => d);
    const key = 'lock-' + target;
    let btn;
    if (locked) btn = `<div class="notice">Відповідь зафіксовано: <b>${code(draft.digits)}</b></div>`;
    else if (armed === key) btn = `<button class="big danger" data-act="lock" data-target="${target}">Точно ${code(draft.digits)}?</button>`;
    else btn = `<button class="big" data-act="arm" data-v="${key}" ${complete ? '' : 'disabled'}>Фіксуємо ${complete ? code(draft.digits) : ''}</button>`;
    return `${rows}${draft.by && !locked ? `<div class="muted small">Востаннє змінив(ла): ${esc(draft.by)}. Відповідь одна на команду — домовтеся.</div>` : ''}${btn}`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Слова</b>
      ${api.isHost
        ? `<div class="chips">${SPICES.map(([id, label]) => `<button class="chip ${s.spice === id ? 'on' : ''}" data-act="spice" data-v="${id}">${label}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${SPICES.find(x => x[0] === s.spice)[1]}</span></div>`}
      <div class="muted small">У кожної команди чотири таємні слова під номерами 1–4. Щораунду шифрувальник отримує код,
        наприклад 3·1·4, і пише три підказки — по одній на кожне слово з коду. Свої мають розгадати код, а суперники —
        перехопити його (з другого раунду). Усі підказки лишаються на дошці, тож з кожним раундом шифрувати складніше.</div></div>`;
    h += [0, 1].map(t => {
      const tm = s.teams[t];
      return `<div class="card stack team t${t}">
        <div class="row">${teamLabel(t)}<span class="grow"></span><span class="muted small">${tm.members.length}</span></div>
        <div class="chips">${tm.members.map(id => api.isHost && !(me() && me().id === id)
          ? `<button class="chip" data-act="move" data-pid="${id}">${nameOf(id)} ⇄</button>`
          : `<span class="chip">${nameOf(id)}${me() && me().id === id ? ' <span class="muted small">(ти)</span>' : ''}</span>`).join('') || '<span class="muted small">Порожньо</span>'}</div>
      </div>`;
    }).join('');
    if (me()) h += `<button class="soft" data-act="switch">Перейти до ${TEAMS[1 - myTeam()].gen}</button>`;
    if (api.isHost) {
      h += `<button class="ghost" data-act="shuffle">Перемішати команди</button>
        <button class="big" data-act="start" ${s.canStart ? '' : 'disabled'}>Почати</button>`;
      if (!s.canStart) h += `<div class="muted small center">У кожній команді має бути щонайменше ${s.minPerTeam} гравці.</div>`;
    } else {
      h += ui.wait('Чекаємо, поки хост почне', '👑');
    }
    return h;
  }

  function cluesView() {
    const s = g();
    let h = scoreHead();
    if (isEnc()) {
      h += `<div class="card hl stack center">
        <div class="muted small">Ти шифрувальник. Твій код</div>
        <div class="bigcode">${code(s.code)}</div>
        <div class="muted small">Напиши по підказці на кожне слово з коду, у цьому порядку. Свої мають зрозуміти, а суперники — ні. Не повторюй старі підказки з дошки.</div>
      </div>
      <div class="card stack">${s.code.map((d, i) => `
        <div class="stack"><div class="small"><span class="badge sm">${d}</span> ${esc(s.words[d - 1])}</div>
          <input id="clue-${i}" type="text" maxlength="${s.maxClue}" placeholder="Підказка до «${esc(s.words[d - 1])}»" autocomplete="off"></div>`).join('')}
        ${s.teams[myTeam()].cluesDone
          ? '<div class="notice">Підказки надіслано. Чекаємо суперників.</div>'
          : '<button class="big" data-act="clues">Надіслати підказки</button>'}
      </div>`;
    } else {
      h += `<div class="card stack">${[0, 1].map(t => {
        const tm = s.teams[t];
        return `<div class="row">${teamLabel(t)}<span class="grow"></span>
          ${tm.cluesDone ? '<span class="chip ok">✓ готово</span>' : `<span class="small muted">${ui.typing('sm')} ${nameOf(tm.encryptor)} шифрує</span>`}</div>`;
      }).join('')}</div>`;
    }
    h += myWords() + boards();
    return h;
  }

  function guessView() {
    const s = g();
    const t = myTeam();
    let h = scoreHead();
    if (t < 0) {
      h += `<div class="card stack">${[0, 1].map(i => `<div>${teamLabel(i)}: ${s.teams[i].clues.map(esc).join(' · ')}</div>`).join('')}</div>`;
    } else {
      const own = s.teams[t];
      const opp = s.teams[1 - t];
      if (isEnc()) {
        h += `<div class="card hl stack"><b>Твої підказки</b>
          ${own.clues.map((c, i) => `<div class="li"><span class="pclue">${esc(c)}</span><span class="badge">${s.code[i]}</span></div>`).join('')}
          <div class="muted small">Мовчи й не підказуй жестами — команда розгадує сама.</div>
          ${own.ownLocked ? '<div class="notice">Команда зафіксувала відповідь.</div>' : ''}</div>`;
      } else {
        h += `<div class="card stack"><b>Розгадайте свій код</b>
          <div class="muted small">Шифрував(ла) ${nameOf(own.encryptor)}. Для кожної підказки оберіть номер слова.</div>
          ${picker('own', own.clues, s.drafts.own)}</div>`;
      }
      if (s.intercept) {
        h += `<div class="card dashed stack"><b>Перехопіть код: ${teamLabel(1 - t)}</b>
          <div class="muted small">Їхніх слів ви не знаєте, але бачите їхню дошку нижче.</div>
          ${picker('opp', opp.clues, s.drafts.opp)}</div>`;
      } else {
        h += `<div class="card flat stack"><div>Підказки суперників: <b>${opp.clues.map(esc).join(' · ')}</b></div>
          <div class="muted small">У першому раунді перехоплення немає — просто запам'ятовуйте.</div></div>`;
      }
      h += myWords();
    }
    h += `<div class="chips">${[0, 1].map(i => `
      <span class="chip ${s.teams[i].ownLocked ? 'ok' : ''}">${TEAMS[i].icon} свій код ${s.teams[i].ownLocked ? '✓' : '…'}</span>
      ${s.intercept ? `<span class="chip ${s.teams[i].oppLocked ? 'ok' : ''}">${TEAMS[i].icon} перехоплення ${s.teams[i].oppLocked ? '✓' : '…'}</span>` : ''}`).join('')}</div>`;
    if (api.isHost) h += `<button class="ghost" data-act="resolve">Підбити підсумки зараз</button>`;
    h += boards();
    return h;
  }

  function roundCard(r) {
    return `<div class="card stack">
      <div class="muted small">Раунд ${r.round}</div>
      ${r.teams.map((e, t) => `
        <div class="stack rteam">
          <div class="row">${teamLabel(t)}<span class="muted small">шифрував(ла) ${esc(e.encryptor)}</span></div>
          ${e.clues.map((c, i) => `<div class="li"><span class="pclue">${esc(c)}</span><span class="badge">${e.code[i]}</span></div>`).join('')}
          <div class="small">Свої: <b>${code(e.own)}</b> ${e.ok ? '<b class="c-good">✓ розгадали</b>' : '<b class="c-bad">✗ провал ⚫</b>'}</div>
          ${e.intercept ? `<div class="small">${TEAMS[1 - t].name}: <b>${code(e.intercept)}</b> ${e.intercepted ? '<b class="c-good">✓ перехопили ⚪</b>' : '<span class="muted">не вгадали</span>'}</div>` : ''}
        </div>`).join('')}
    </div>`;
  }

  function revealView() {
    const s = g();
    const r = s.log[s.log.length - 1];
    let h = scoreHead();
    if (r) h += roundCard(r);
    h += api.isHost
      ? `<button class="big" data-act="next">Раунд ${s.round + 1}</button>`
      : '<div class="muted center small">Хост запустить наступний раунд.</div>';
    h += myWords() + boards();
    return h;
  }

  function overView() {
    const s = g();
    const w = s.winner;
    let verdict, cls;
    if (w === -1) { verdict = 'Нічия'; cls = 'meh'; }
    else if (myTeam() < 0) { verdict = `Перемогли ${TEAMS[w].name.toLowerCase()}`; cls = 'meh'; }
    else if (w === myTeam()) { verdict = 'Перемога! 🎉'; cls = 'good'; }
    else { verdict = 'Поразка'; cls = 'bad'; }
    let h = `<div class="banner ${cls}"><h1>${verdict}</h1>${w >= 0 ? `<div>${teamLabel(w)}</div>` : ''}</div>`;
    h += scoreHead();
    h += [0, 1].map(t => `<div class="card stack">${teamLabel(t)}
      <div class="kw">${(s.teams[t].words || []).map((x, i) => `<div class="kword"><span class="badge">${i + 1}</span>${esc(x)}</div>`).join('')}</div></div>`).join('');
    h += boards();
    h += s.log.slice().reverse().map(roundCard).join('');
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.canStart ? '' : 'disabled'}>Нова гра</button>`
      : '<div class="muted center small">Хост може почати нову гру.</div>';
    return h;
  }

  PG.register('decrypto', {
    // background music per phase (shell.js): encryptors think in silence; teams talk over the guesses
    music: { GUESS: 'low', REVEAL: 'low', OVER: 'low' },
    musicLoop: 81.6,  // seconds of music.mp3 that loop: a whole number of bars
    sfx: ['radio'],  // own effects: sfx/<name>.mp3
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'CLUES': h = cluesView(); break;
        case 'GUESS': h = guessView(); break;
        case 'REVEAL': h = revealView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="decrypto stack ${myTeam() >= 0 ? 'tint t' + myTeam() : ''}">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (prev && prev.phase !== next.phase) {
        armed = null; a.vibrate(120);
        if (next.phase === 'GUESS') a.sfx('radio');
        if (next.phase === 'REVEAL') a.sfx('reveal');
        if (next.phase === 'OVER') a.sfx('win', 1200);
      }
      if (prev && next.phase === 'CLUES' && prev.round !== next.round) {
        // fresh clue inputs every round
        for (let i = 0; i < 3; i++) { const el = document.getElementById('clue-' + i); if (el) el.value = ''; }
        if (next.me && next.me.encryptor) a.vibrate([80, 60, 80]);
      }
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'spice': return a.send({ t: 'spice', v: el.dataset.v });
        case 'switch': return a.send({ t: 'switch' });
        case 'move': return a.send({ t: 'move', pid: el.dataset.pid });
        case 'shuffle': return a.send({ t: 'shuffle' });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'resolve': return a.send({ t: 'resolve' });
        case 'boards': showBoards = !showBoards; return a.render();
        case 'clues': {
          const inputs = [0, 1, 2].map(i => document.getElementById('clue-' + i));
          const empty = inputs.find(i => !i || !i.value.trim());
          if (empty) { empty && empty.focus(); return; }
          return a.send({ t: 'clues', clues: inputs.map(i => i.value.trim()) });
        }
        case 'digit': {
          const s = g();
          const target = el.dataset.target;
          const slot = +el.dataset.slot, d = +el.dataset.d;
          const cur = s.drafts && s.drafts[target] && s.drafts[target].digits[slot];
          armed = null;
          return a.send({ t: 'digit', target, slot, d: cur === d ? 0 : d });
        }
        case 'arm': armed = el.dataset.v; a.render(); setTimeout(() => { if (armed === el.dataset.v) { armed = null; a.render(); } }, 4000); return;
        case 'lock': armed = null; return a.send({ t: 'lock', target: el.dataset.target });
      }
    },

    key(e) {
      const m = /^clue-(\d)$/.exec(e.target.id || '');
      if (e.key !== 'Enter' || !m) return;
      const next = document.getElementById('clue-' + (+m[1] + 1));
      if (next) next.focus(); else e.target.blur();
    },
  });
})();
