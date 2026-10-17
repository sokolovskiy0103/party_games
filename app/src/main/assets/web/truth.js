// «Детектор правди» (Privacy) UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  // This round's answer being picked on this phone (not sent yet).
  let draft = { round: -1, yes: null, guess: null, editing: false, pending: false };

  const g = () => api.g;
  const me = () => g() && g().me;
  const isReader = () => !!(me() && g().reader === me().id);
  const canDrive =() => isReader() || api.isHost;   // may go next / swap

  const SPICES = [['CLEAN', '😇 Звичайні'], ['MIXED', '🎲 Усі'], ['ADULT', '🔥 18+']];
  const pts = n => n === 1 ? 'очко' : (n >= 2 && n <= 4 ? 'очки' : 'очок');

  function syncDraft() {
    const s = g();
    if (draft.round === s.round) return;
    const m = me() || {};
    draft = { round: s.round, yes: m.yes === undefined ? null : m.yes, guess: m.guess === undefined ? null : m.guess, editing: false, pending: false };
  }

  // ---------- pieces ----------
  const person = yes => `<svg class="person ${yes ? 'yes' : 'no'}" viewBox="0 0 24 32" aria-label="${yes ? 'так' : 'ні'}">
    <circle cx="12" cy="7" r="6"/><path d="M1 31v-6a11 11 0 0 1 22 0v6z"/></svg>`;

  function header() {
    const s = g();
    return `<div class="row head"><span class="tag">раунд ${s.round}</span><span class="grow"></span><span class="small muted">гра до <b>${s.target}</b> очок</span></div>`;
  }

  function questionCard() {
    const q = g().question;
    if (!q) return '';
    return `<div class="card stack center">
      ${isReader() ? `<div class="muted small">${q.adult ? '🔥 ' : ''}Прочитай уголос</div>` : ''}
      <div class="headline">${esc(q.q)}</div>
    </div>`;
  }

  function scoreboard(withGain) {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    return `<div class="card">${list.map(p => `
      <div class="li">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && p.id === me().id ? ' <span class="muted">(ти)</span>' : ''}</span>
        ${withGain && p.gain ? `<span class="plus">+${p.gain}</span>` : ''}
        <span class="pts">${p.score}</span>
      </div>`).join('')}</div>`;
  }

  function progress() {
    const s = g();
    return `<div class="card">${s.players.map(p => `
      <div class="li">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}</span>
        <span class="small ${p.answered ? 'c-good' : 'muted'}">${p.answered ? 'відповів(ла) ✓' : 'думає…'}</span>
      </div>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Питання</b>
      ${api.isHost
        ? `<div class="chips">${SPICES.map(([id, l]) => `<button class="chip ${s.spice === id ? 'on' : ''}" data-act="spice" data-v="${id}">${l}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${SPICES.find(x => x[0] === s.spice)[1]}</span></div>`}
      <b>Гра до</b>
      ${api.isHost
        ? `<div class="chips">${s.targets.map(k => `<button class="chip ${s.target === k ? 'on' : ''}" data-act="target" data-v="${k}">${k} очок</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${s.target} очок</span></div>`}
      <div class="muted small">Хтось читає питання, і всі <b>анонімно</b> відповідають «так» чи «ні». Заодно вгадай, скільки
        всього буде «так». Вгадав(ла) — +1. Хто чесно відповів, не дізнається ніхто.</div></div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function answerForm() {
    const s = g();
    const nums = Array.from({ length: s.maxGuess + 1 }, (_, i) => i);
    const ready = draft.yes !== null && draft.guess !== null;
    // Both answers look the same so nobody can tell from a glance at the screen.
    return `<div class="card stack center">
      <b>Твоя відповідь</b>
      <div class="muted small">Її не побачить ніхто — лише загальну кількість «так».</div>
      <div class="grid2">
        <button class="pick center choice ${draft.yes === true ? 'on' : ''}" data-act="yes" data-v="1">Так</button>
        <button class="pick center choice ${draft.yes === false ? 'on' : ''}" data-act="yes" data-v="0">Ні</button>
      </div>
      <b>Скільки всього буде «так»?</b>
      <div class="muted small">Рахуючи й себе: від 0 до ${s.maxGuess}.</div>
      <div class="nums">${nums.map(k => `<button class="pick sq ${draft.guess === k ? 'on' : ''}" data-act="guess" data-v="${k}">${k}</button>`).join('')}</div>
      <button class="big" data-act="send" ${ready ? '' : 'disabled'}>Надіслати</button>
    </div>`;
  }

  function roundView() {
    const s = g();
    syncDraft();
    const m = me();
    const sent = !!(m && (m.yes !== undefined || draft.pending));
    let h = header() + questionCard();
    if (m && (!sent || draft.editing)) {
      h += answerForm();
    } else if (m) {
      h += `<div class="banner meh">Відповідь надіслано ✓<div class="small muted">Твій прогноз: ${draft.guess} × «так»</div></div>
        <button class="ghost" data-act="edit">Змінити</button>`;
    }
    const done = s.players.filter(p => p.answered).length;
    h += `<h2>Відповіли · ${done} з ${s.players.length}</h2>` + progress();
    if (canDrive() && done === 0) h += `<button class="ghost" data-act="swap">Інше питання</button>`;
    if (api.isHost && done > 0) h += `<button class="soft" data-act="reveal">Відкрити зараз (${done} з ${s.players.length})</button>`;
    return h;
  }

  function revealView() {
    const s = g();
    const mine = s.players.find(p => me() && p.id === me().id);
    const gain = mine ? mine.gain : 0;
    // One figure per answer, «так» first: shows the split without saying who.
    const people = Array.from({ length: s.answered }, (_, i) => person(i < s.yes)).join('');
    let h = `<div class="banner ${gain ? 'good' : 'meh'}">
      <div class="people">${people}</div>
      <div class="tally"><span class="yes">${s.yes} так</span> · <span class="no">${s.answered - s.yes} ні</span></div>
      ${gain ? `<div class="small">Вгадав(ла)! +${gain}</div>` : ''}</div>`;
    h += questionCard();
    const scorers = s.players.filter(p => p.gain);
    h += `<div class="card flat center small">${scorers.length
      ? scorers.map(p => `<b>${esc(p.name)}</b> +${p.gain}`).join(' · ')
      : 'Ніхто не вгадав.'}</div>`;
    h += `<h2>Рахунок</h2>` + scoreboard(true);
    const ending = s.players.some(p => p.score >= s.target);
    h += canDrive()
      ? `<button class="big" data-act="next">${ending ? 'Підсумки' : 'Наступне питання'}</button>`
      : '<div class="muted center small">Далі — коли хост чи той, хто читав, натисне.</div>';
    return h;
  }

  function overView() {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    const top = list.length ? list[0].score : 0;
    const winners = list.filter(p => p.score === top).map(p => esc(p.name)).join(', ');
    const sharp = list.slice().sort((a, b) => b.exact - a.exact)[0];
    let h = `<div class="banner good"><span class="emoji">🏆</span><h1>${winners}</h1><div>${top} ${pts(top)}</div></div>`;
    h += scoreboard(false);
    if (sharp && sharp.exact > 0) h += `<div class="card flat center small">🎯 Найточніший детектор: <b>${esc(sharp.name)}</b> — ${sharp.exact} точних</div>`;
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне заново.</div>';
    return h;
  }

  PG.register('truth', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'ROUND': h = roundView(); break;
        case 'REVEAL': h = revealView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="truth stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (prev && prev.phase !== next.phase) a.vibrate(120);
    },

    menu() { return ['newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'spice': return a.send({ t: 'spice', v: el.dataset.v });
        case 'target': return a.send({ t: 'target', v: +el.dataset.v });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'swap': return a.send({ t: 'swap' });
        case 'reveal': return a.send({ t: 'reveal' });
        case 'yes': draft.yes = el.dataset.v === '1'; return a.render();
        case 'guess': draft.guess = +el.dataset.v; return a.render();
        case 'edit': draft.editing = true; draft.pending = false; return a.render();
        case 'send':
          if (draft.yes === null || draft.guess === null) return;
          draft.editing = false; draft.pending = true;
          a.send({ t: 'answer', yes: draft.yes, guess: draft.guess });
          return a.render();
      }
    },
  });
})();
