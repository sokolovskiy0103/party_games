// «Кодові імена» UI (classic teams and co-op Duet). Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  let armed = null;     // two-tap confirmations
  let clueN = 1;        // number picked for the clue being written
  let flip = null;      // card that has just been opened (animated once)
  let viewPick = null;  // Duet: board view the player picked by hand ('table' | 'key'); reset every turn

  const TEAMS = [{ name: 'Сині', gen: 'синіх', icon: '🔵' }, { name: 'Червоні', gen: 'червоних', icon: '🔴' }];
  const SPICES = [['CLEAN', '😇 Звичайні'], ['MIXED', '🎲 Трохи 18+'], ['ADULT', '🔥 18+']];
  const MODES = [['CLASSIC', '⚔️ Дві команди'], ['DUET', '🤝 Дует']];
  const TURN_LABELS = { 11: 'Легко · 11', 9: 'Звично · 9', 7: 'Складно · 7' };

  const g = () => api.g;
  const me = () => g() && g().me;
  const myTeam = () => (me() ? me().team : -1);
  const duet = () => g().mode === 'DUET';
  const player = id => (g().players.find(p => p.id === id) || {});
  const nameOf = id => esc(player(id).name || '?');
  const initial = id => esc(((player(id).name || '?').trim()[0] || '?').toUpperCase());
  const numLabel = n => (n === -1 ? '∞' : String(n));

  /** Classic: team badge. Duet: the side named after its players. */
  function sideLabel(t) {
    const s = g();
    if (!duet()) return `<span class="tname t${t}">${TEAMS[t].icon} ${TEAMS[t].name}</span>`;
    const names = (s.teams[t] || []).map(nameOf).join(', ') || 'порожньо';
    return `<span class="tname side">${names}</span>`;
  }

  /**
   * Duet board view. 'table' (guesser) shows only what is on the table — opened or not; 'key' (clue giver) shows my
   * key and greys out opened cards, so a key tint is never mistaken for an opened agent. Follows the role by default.
   */
  function duetView() {
    const s = g(), m = me();
    if (!duet() || s.phase !== 'PLAY' || !m || myTeam() < 0) return null;
    if (viewPick) return viewPick;
    if (s.step === 'CLUE') return m.giver ? 'key' : 'table';
    if (s.step === 'GUESS') return m.guesser ? 'table' : 'key';
    return 'table';
  }

  // ---------- board ----------
  function cardHtml(c, i) {
    const s = g();
    const over = s.phase === 'OVER';
    const view = duetView();
    const cls = ['cn-card'];
    let marks = '';
    if (view === 'key') {
      if (c.open) cls.push('gone');
      else if (c.key) cls.push('k-' + c.key);
    } else if (c.open) cls.push('open', 'c-' + c.open);
    else if (c.key && view !== 'table') cls.push('k-' + c.key);
    if (over && duet()) {
      // both keys: left half — first side, right half — second side
      marks += `<span class="kk kk0 k-${c.k0}"></span><span class="kk kk1 k-${c.k1}"></span>`;
    }
    if (duet() && c.neutral && c.neutral.length) {
      const mine = myTeam();
      // a bystander on the partner's key can't be opened by us any more
      if (mine >= 0 && c.neutral.includes(1 - mine) && !c.open) cls.push('dead');
      marks += `<span class="cn-bys">${c.neutral.map(t => `<i title="перехожий">${t === mine ? '🚶' : '🧍'}</i>`).join('')}</span>`;
    }
    if (c.open === 'ASSASSIN') marks += '<span class="cn-skull">☠</span>';
    if (c.ptr) marks += `<span class="cn-ptr">${c.ptr.map(id => `<b class="${me() && id === me().id ? 'mine' : ''}">${initial(id)}</b>`).join('')}</span>`;
    if (c.canOpen) cls.push('can');
    if (me() && c.ptr && c.ptr.includes(me().id)) cls.push('sel');
    if (flip === i) cls.push('last');
    return `<button class="${cls.join(' ')}" data-act="${c.canOpen ? 'point' : ''}" data-i="${i}" ${c.canOpen ? '' : 'tabindex="-1"'}>
      <span class="w ${c.w.length > 8 ? 'long' : ''}">${esc(c.w)}</span>${marks}</button>`;
  }

  function board() {
    const view = duetView();
    const grid = `<div class="cn-grid">${(g().cards || []).map(cardHtml).join('')}</div>`;
    if (!view) return grid;
    return `<div class="seg">
        <button class="${view === 'table' ? 'on' : ''}" data-act="view" data-v="table">🃏 Стіл</button>
        <button class="${view === 'key' ? 'on' : ''}" data-act="view" data-v="key">🗝️ Мій ключ</button>
      </div>
      <div class="cn-board v-${view}">${grid}</div>`;
  }

  function legend() {
    const s = g();
    const view = duetView();
    if (s.phase === 'OVER' && duet()) return `<div class="muted small">Ліва половина карти — ключ: ${sideLabel(0)}, права — ${sideLabel(1)}. 🟩 агент, ⬛ вбивця.</div>`;
    if (view === 'key') return `<div class="muted small"><b>Твій ключ</b> — партнер його не бачить. 🟩 агенти, ⬛ вбивці, світлі — перехожі. Уже відкриті карти погашені.</div>`;
    if (view === 'table') return `<div class="muted small"><b>Стіл</b> — лише відкриті карти. Відкриваєте за ключем партнера. 🚶 — перехожий на твоєму ключі, 🧍 — на ключі партнера (такої карти вже не відкрити).</div>`;
    if (me() && me().captain && s.phase === 'PLAY') return '<div class="muted small">Ти капітан — бачиш увесь ключ. Мовчи й не підказуй жестами.</div>';
    return '';
  }

  // ---------- status ----------
  function head() {
    const s = g();
    if (duet()) {
      const sudden = s.step === 'SUDDEN';
      return `<div class="card flat cn-head">
        <div class="row"><b>🟩 ${s.found} з ${s.agents}</b><span class="grow"></span>
          ${sudden ? '<b class="c-bad">☠ раптова смерть</b>' : `<span>⏳ ходів: <b>${s.turnsLeft}</b></span>`}</div>
      </div>`;
    }
    return `<div class="card flat cn-head">${[0, 1].map(t => `
      <div class="row ${s.turn === t && s.phase === 'PLAY' ? 'turn' : ''}">${sideLabel(t)}
        <span class="muted small">капітан ${nameOf(s.captains[t])}</span><span class="grow"></span>
        <span>лишилось <b>${s.agentsLeft[t]}</b></span></div>`).join('')}
    </div>`;
  }

  function cluePanel() {
    const s = g();
    const m = me();
    if (s.step === 'SUDDEN') {
      return `<div class="card hl bad stack">
        <b>☠ Ходи закінчились — раптова смерть</b>
        <div class="muted small">Підказок більше немає. Будь-хто може відкрити карту, яка, на вашу думку, є агентом на ключі <b>партнера</b>. Будь-який промах — поразка.</div>
        ${openBar()}</div>`;
    }
    if (s.step === 'CLUE') {
      if (m && m.giver) {
        const nums = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1];
        return `<div class="card hl stack">
          <b>Твоя підказка</b>
          <div class="muted small">Одне слово і число — скільки карт ${duet() ? 'на твоєму ключі' : 'вашої команди'} воно пов'язує. Слова зі столу називати не можна.</div>
          <input id="cnClue" type="text" maxlength="${s.maxClue}" placeholder="Слово-підказка" autocomplete="off">
          <div class="chips cn-nums">${nums.map(n => `<button class="chip ${clueN === n ? 'on' : ''}" data-act="num" data-v="${n}">${numLabel(n)}</button>`).join('')}</div>
          <div id="cnWarn" class="c-bad small" hidden>Це слово лежить на столі.</div>
          <button class="big" data-act="clue">Дати підказку</button>
        </div>`;
      }
      const who = duet() ? sideLabel(s.turn) : `капітан ${nameOf(s.captains[s.turn])} (${sideLabel(s.turn)})`;
      return ui.wait(`Підказку придумує ${who}`, duet() ? '' : player(s.captains[s.turn]).name);
    }
    // GUESS
    const c = s.clue || { word: '', n: 0 };
    let h = `<div class="card center">
      <div class="muted small">Підказка від ${esc(c.by || '')}${duet() ? '' : ` · ${sideLabel(c.team)}`}</div>
      <div class="headline xl">${esc(c.word)} <span class="badge">${numLabel(c.n)}</span></div>
      ${s.guessesLeft != null ? `<div class="muted small">Можна відкрити ще ${s.guessesLeft}</div>` : '<div class="muted small">Відкривати можна скільки завгодно</div>'}
    </div>`;
    if (m && m.guesser) h += `<div class="card stack">${openBar()}</div>`;
    else {
      const who = duet() ? sideLabel(1 - s.turn) : sideLabel(s.turn);
      h += ui.wait(`Вгадують: ${who}`);
    }
    return h;
  }

  /** Guesser controls: open the card I point at, or stop. */
  function openBar() {
    const s = g();
    const m = me();
    if (!m || !m.guesser) return '';
    const i = (s.cards || []).findIndex(c => c.ptr && c.ptr.includes(m.id));
    let h = '';
    if (i < 0) h += '<div class="muted small center">Торкнись карти, щоб показати на неї іншим, а тоді відкрий.</div>';
    else h += `<button class="big" data-act="open" data-i="${i}">Відкрити «${esc(s.cards[i].w)}»</button>`;
    if (m.canEnd) {
      h += armed === 'end'
        ? '<button class="danger" data-act="endTurn">Точно закінчити хід?</button>'
        : '<button class="ghost" data-act="arm" data-v="end">Закінчити хід</button>';
    }
    return h;
  }

  function logCard() {
    const s = g();
    if (!s.log || !s.log.length) return '';
    return `<div class="card stack"><b>Підказки</b>
      <div class="cn-log">${s.log.slice().reverse().map(c => `<div class="row">
        ${duet() ? `<span class="muted small">${esc(c.by)}</span>` : sideLabel(c.team)}
        <b>${esc(c.word)}</b><span class="badge sm">${numLabel(c.n)}</span></div>`).join('')}</div></div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const forced = n < s.classicMin;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;

    h += `<div class="card stack"><b>Режим</b>`;
    if (forced) {
      h += `<div class="chips"><span class="chip on">🤝 Дует</span></div>
        <div class="muted small">Менше ${s.classicMin} гравців — граєте кооперативний Дует. Від ${s.classicMin} можна обрати дві команди.</div>`;
    } else if (api.isHost) {
      h += `<div class="chips">${MODES.map(([id, l]) => `<button class="chip ${s.chosenMode === id ? 'on' : ''}" data-act="mode" data-v="${id}">${l}</button>`).join('')}</div>`;
    } else {
      h += `<div class="chips"><span class="chip on">${MODES.find(m => m[0] === s.mode)[1]}</span></div>`;
    }
    h += s.mode === 'DUET'
      ? `<div class="muted small">Кооператив. У кожної сторони свій ключ: 9 агентів і 3 вбивці. По черзі даєте підказку з одного слова й числа — партнер відкриває карти. Разом треба знайти всіх ${15} агентів за обмежену кількість ходів.</div>`
      : `<div class="muted small">Дві команди, у кожній капітан бачить ключ. Капітан дає підказку з одного слова й числа, команда відкриває карти. Хто першим знайде всіх своїх агентів — перемагає. Вбивця — миттєва поразка.</div>`;
    h += '</div>';

    h += `<div class="card stack"><b>Слова</b>${api.isHost
      ? `<div class="chips">${SPICES.map(([id, l]) => `<button class="chip ${s.spice === id ? 'on' : ''}" data-act="spice" data-v="${id}">${l}</button>`).join('')}</div>`
      : `<div class="chips"><span class="chip on">${SPICES.find(x => x[0] === s.spice)[1]}</span></div>`}`;
    if (s.mode === 'DUET') {
      h += `<b>Ходів</b>${api.isHost
        ? `<div class="chips">${s.duetTurnOptions.map(t => `<button class="chip ${s.duetTurns === t ? 'on' : ''}" data-act="turns" data-v="${t}">${TURN_LABELS[t] || t}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${TURN_LABELS[s.duetTurns] || s.duetTurns}</span></div>`}`;
    }
    h += '</div>';

    h += [0, 1].map(t => `<div class="card stack team t${s.mode === 'DUET' ? 'd' : t}">
      <div class="row">${s.mode === 'DUET' ? `<b>Сторона ${t + 1}</b>` : sideLabel(t)}<span class="grow"></span><span class="muted small">${s.teams[t].length}</span></div>
      <div class="chips">${s.teams[t].map(id => api.isHost && !(me() && me().id === id)
        ? `<button class="chip" data-act="move" data-pid="${id}">${nameOf(id)} ⇄</button>`
        : `<span class="chip">${nameOf(id)}${me() && me().id === id ? ' <span class="muted small">(ти)</span>' : ''}</span>`).join('') || '<span class="muted small">Порожньо</span>'}</div>
    </div>`).join('');
    if (me()) h += `<button class="soft" data-act="switch">Перейти ${s.mode === 'DUET' ? 'на іншу сторону' : 'до ' + TEAMS[1 - myTeam()].gen}</button>`;
    if (api.isHost) {
      h += `<button class="ghost" data-act="shuffle">Перемішати</button>
        <button class="big" data-act="start" ${s.canStart ? '' : 'disabled'}>Почати</button>`;
      if (!s.canStart) h += `<div class="muted small center">${s.mode === 'DUET' ? 'На кожній стороні має бути хоча б один гравець.' : `У кожній команді має бути щонайменше ${s.minPerTeam} гравці.`}</div>`;
    } else {
      h += ui.wait('Чекаємо, поки хост почне', '👑');
    }
    return h;
  }

  function playView() {
    return head() + cluePanel() + board() + legend() + logCard();
  }

  function overBanner() {
    const s = g();
    let verdict, cls;
    if (duet()) {
      verdict = s.winner === 1 ? 'Перемога! 🎉' : 'Поразка';
      cls = s.winner === 1 ? 'good' : 'bad';
    } else if (myTeam() < 0) { verdict = `Перемогли ${TEAMS[s.winner].name.toLowerCase()}`; cls = 'meh'; }
    else if (s.winner === myTeam()) { verdict = 'Перемога! 🎉'; cls = 'good'; }
    else { verdict = 'Поразка'; cls = 'bad'; }
    return `<div class="banner ${cls}"><h1>${verdict}</h1><div>${esc(s.reason || '')}</div>
      ${duet() ? `<div class="muted small">Знайдено ${s.found} з ${s.agents}</div>` : `<div>${sideLabel(s.winner)}</div>`}</div>`;
  }

  function overView() {
    const s = g();
    let h = overBanner() + board() + legend() + logCard();
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.canStart ? '' : 'disabled'}>Ще раз</button>
         <button class="ghost" data-act="lobby">Змінити команди чи режим</button>`
      : '<div class="muted center small">Хост може почати нову гру.</div>';
    return h;
  }

  function sendClue(a) {
    const input = document.getElementById('cnClue');
    const word = (input && input.value || '').trim();
    if (!word) { input && input.focus(); return; }
    const onBoard = (g().cards || []).some(c => !c.open && c.w.toLowerCase() === word.toLowerCase());
    const warn = document.getElementById('cnWarn');
    if (onBoard) { if (warn) warn.hidden = false; return; }
    a.send({ t: 'clue', word, n: clueN });
  }

  /** TV: the table (never the key) next to the clue and the score; the lobby is the players' one. */
  function tvView() {
    const s = g();
    if (s.phase === 'LOBBY') return lobbyView();
    return `${s.phase === 'OVER' ? overBanner() : ''}<div class="tv-split"><div>${board()}</div>
      <div class="stack">${s.phase === 'OVER' ? '' : head() + cluePanel()}${logCard()}</div></div>`;
  }

  PG.register('codenames', {
    // background music per phase (shell.js): teams discuss their guesses
    music: { PLAY: 'low', OVER: 'low' },
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'PLAY': h = playView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      const tint = !duet() && myTeam() >= 0 ? 'tint t' + myTeam() : '';
      return `<div class="codenames stack ${tint}">${h}</div>`;
    },

    board(a) {
      api = a;
      return g() ? `<div class="codenames stack">${tvView()}</div>` : '';
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      const opened = st => ((st && st.cards) || []).filter(c => c.open).length;
      if (prev && prev.game === next.game && opened(next) > opened(prev)) {
        flip = next.lastOpened;
        setTimeout(() => { flip = null; }, 600);
        // whose agent it was decides the sound: the guessing team's (or green in Duet), a bystander, the other team's, the assassin
        const c = (next.cards || [])[next.lastOpened] || {};
        const mine = c.open === 'GREEN' || c.open === (prev.turn === 0 ? 'BLUE' : 'RED');
        a.sfx(c.open === 'ASSASSIN' ? 'codenames_assassin' : mine ? 'correct' : 'wrong');
      }
      const last = ((next.cards || [])[next.lastOpened] || {}).open;
      if (prev && prev.phase !== 'OVER' && next.phase === 'OVER') a.sfx(last === 'ASSASSIN' ? 'lose' : 'win', 1800);
      const turnChanged = !prev || prev.phase !== next.phase || prev.step !== next.step || prev.turn !== next.turn;
      if (turnChanged) {
        armed = null;
        viewPick = null;
        if (next.step === 'CLUE') {
          clueN = 1;
          const el = document.getElementById('cnClue');
          if (el) el.value = '';
        }
        if (prev && next.me && (next.me.giver || next.me.guesser)) a.vibrate([80, 60, 80]);
      }
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled || !el.dataset.act) return;
      switch (el.dataset.act) {
        case 'mode': return a.send({ t: 'mode', v: el.dataset.v });
        case 'spice': return a.send({ t: 'spice', v: el.dataset.v });
        case 'turns': return a.send({ t: 'turns', v: +el.dataset.v });
        case 'switch': return a.send({ t: 'switch' });
        case 'move': return a.send({ t: 'move', pid: el.dataset.pid });
        case 'shuffle': return a.send({ t: 'shuffle' });
        case 'start': return a.send({ t: 'start' });
        case 'lobby': return a.send({ t: 'lobby' });
        case 'num': clueN = +el.dataset.v; return a.render();
        case 'view': viewPick = el.dataset.v; return a.render();
        case 'clue': return sendClue(a);
        case 'point': armed = null; return a.send({ t: 'point', i: +el.dataset.i });
        case 'open': armed = null; return a.send({ t: 'open', i: +el.dataset.i });
        case 'endTurn': armed = null; return a.send({ t: 'endTurn' });
        case 'arm': armed = el.dataset.v; a.render(); setTimeout(() => { if (armed === el.dataset.v) { armed = null; a.render(); } }, 4000); return;
      }
    },

    key(e) {
      if (e.target.id !== 'cnClue') return;
      const warn = document.getElementById('cnWarn');
      if (warn) warn.hidden = true;
      if (e.key === 'Enter') sendClue(api);
    },
  });
})();
