// «Сотня» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  let api = PG.api;
  let armed = null;       // two-tap confirmations: 'play' or a menu action
  let animatedTable = 0;  // table length whose newest cards have already animated in

  const g = () => api.g;
  const me = () => g() && g().me;
  const inRound = () => !!(me() && me().inRound);
  const myCards = () => (me() && me().cards) || [];

  const SPICES = [['CLEAN', '😇 Звичайні'], ['MIXED', '🎲 Усі'], ['ADULT', '🔥 18+']];

  // ---------- pieces ----------
  function hearts() {
    const s = g();
    let h = '';
    for (let i = 0; i < Math.max(s.lives, 0); i++) h += '<span class="heart">❤️</span>';
    return `<span class="hearts" title="Життя">${h || '💀'}</span>`;
  }

  function topicCard() {
    const t = g().topic;
    if (!t) return '';
    return `<div class="card stack center">
      <div class="muted small">${t.adult ? '🔥 ' : ''}Тема</div>
      <div class="topic">${esc(t.q)}</div>
      <div class="ends"><span><b>1</b> ${esc(t.lo)}</span><span>${esc(t.hi)} <b>100</b></span></div>
    </div>`;
  }

  /** The 1–100 strip with every card on the table. */
  function strip() {
    const s = g();
    const fresh = s.table.length > animatedTable;
    const pins = s.table.map((c, i) => `<i class="pin ${c.missed ? 'missed' : ''} ${fresh && i >= animatedTable ? 'fresh' : ''}" style="left:${c.v}%"><b>${c.v}</b></i>`).join('');
    if (fresh) setTimeout(() => { animatedTable = s.table.length; }, 0);
    return `<div class="strip">${pins}</div>`;
  }

  function tableList() {
    const s = g();
    if (!s.table.length) return `<div class="card flat center muted small">Стіл порожній. Перша карта — за тим, хто вважає своє число найменшим.</div>`;
    return `<div class="card">${s.table.slice().reverse().map(c => `
      <div class="score ${c.missed ? 'missed' : ''}">
        <span class="num">${c.v}</span>
        <span class="name">${esc(c.name)}${c.clue ? ` <span class="muted">— «${esc(c.clue)}»</span>` : ''}</span>
        ${c.missed ? '<span class="small bad">пропущено</span>' : ''}
      </div>`).join('')}</div>`;
  }

  function myHand() {
    const s = g();
    const cards = myCards();
    if (!inRound()) return `<div class="card flat center muted">Ти приєднався під час рівня — зіграєш у наступному.</div>`;
    if (!cards.length) return `<div class="card flat center muted">Твої карти вже на столі. Підказуй іншим!</div>`;
    const low = cards[0];
    let h = `<h2>Твої карти</h2><div class="stack">${cards.map(c => `
      <div class="mycard">
        <div class="big-num">${c.v}</div>
        <div class="grow stack">
          <input id="clue-${c.v}" type="text" data-v="${c.v}" maxlength="${s.maxClue}" placeholder="Приклад на тему для ${c.v}" value="${esc(c.clue)}" autocomplete="off">
          <div class="muted small">${c.clue ? 'Бачать усі ✓' : 'Напиши й натисни Enter — або просто скажи вголос'}</div>
        </div>
      </div>`).join('')}</div>`;
    h += armed === 'play'
      ? `<button class="big danger" data-act="play" data-v="${low.v}">Точно кладу ${low.v}?</button>`
      : `<button class="big" data-act="arm" data-v="play">Кладу ${low.v} на стіл</button>`;
    return h;
  }

  function othersList() {
    const s = g();
    const others = s.players.filter(p => p.inRound && !(me() && p.id === me().id));
    if (!others.length) return '';
    return `<h2>У інших</h2><div class="card">${others.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}
          <span class="clues">${p.clues.map(c => `<span class="chip ${c ? '' : 'empty'}">${c ? '«' + esc(c) + '»' : '?'}</span>`).join('')}</span></span>
        <span class="pts">${p.left}</span>
      </div>`).join('')}</div>
      <div class="muted small">Число праворуч — скільки карт ще в руці.</div>`;
  }

  function revealList() {
    const s = g();
    const left = s.players.filter(p => p.cards && p.cards.length);
    if (!left.length) return '';
    return `<h2>Лишилось у руках</h2><div class="card">${left.map(p => `
      <div class="score"><span class="name">${esc(p.name)}</span>
        <span>${p.cards.map(c => `<span class="chip">${c.v}${c.clue ? ' · ' + esc(c.clue) : ''}</span>`).join(' ')}</span></div>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Теми</b>
      ${api.isHost
        ? `<div class="chips">${SPICES.map(([id, label]) => `<button class="chip ${s.spice === id ? 'on' : ''}" data-act="spice" data-v="${id}">${label}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${SPICES.find(x => x[0] === s.spice)[1]}</span></div>`}
      <div class="muted small">Граєте всі разом. Кожен отримує таємне число від 1 до 100. Є тема-шкала, наприклад
        «Наскільки це страшно: 1 — зовсім ні, 100 — жах». Опиши своє число прикладом на цій шкалі (не називаючи числа!),
        а потім викладайте карти по одній від найменшої. Хто вважає, що в нього найменше, — кладе.
        Помилка коштує життя. На рівні N у кожного N карт (до трьох).</div></div>`;
    if (s.best) h += `<div class="card flat center">Рекорд вечора: <b>рівень ${s.best}</b></div>`;
    if (!api.isHost) h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне</span></div>`;
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function header() {
    const s = g();
    return `<div class="row head"><span class="tag">рівень ${s.level} · ${s.cardsEach} ${s.cardsEach === 1 ? 'карта' : 'карти'}</span><span class="grow"></span>${hearts()}</div>`;
  }

  function playView() {
    const s = g();
    let h = header() + topicCard() + strip() + myHand() + othersList() + `<h2>На столі</h2>` + tableList();
    if (api.isHost && !s.table.length) h += `<button class="ghost" data-act="swap">Інша тема</button>`;
    return h;
  }

  function resultView() {
    const s = g();
    const clean = s.table.every(c => !c.missed);
    let h = `<div class="banner good pop"><h1>Рівень ${s.level} пройдено! 🎉</h1>
      <div>${clean ? (s.lives < s.maxLives ? 'Без жодної помилки — +1 життя ❤️' : 'Без жодної помилки!') : 'Помилки були, але ви впорались.'}</div></div>`;
    h += header() + topicCard() + strip() + tableList();
    h += api.isHost
      ? `<button class="big" data-act="next" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Рівень ${s.level + 1}</button>`
      : '<div class="muted center small">Хост запустить наступний рівень.</div>';
    return h;
  }

  function overView() {
    const s = g();
    let h = `<div class="banner bad pop"><h1>Життя скінчились 💀</h1>
      <div>${s.level > 1 ? `Ви дійшли до рівня ${s.level}. Рекорд вечора — ${s.best || 0}.` : 'Перший рівень не здався. Ще раз?'}</div></div>`;
    h += topicCard() + strip() + revealList() + `<h2>На столі</h2>` + tableList();
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Спочатку</button>`
      : '<div class="muted center small">Хост почне заново.</div>';
    return h;
  }

  function sendClue(input) {
    if (!input || !input.dataset.v) return;
    api.send({ t: 'clue', v: +input.dataset.v, text: (input.value || '').trim() });
  }

  // Clues are saved when an input loses focus too, not only on Enter.
  document.addEventListener('change', e => {
    if (document.body.dataset.game === 'ito' && e.target.matches && e.target.matches('input[data-v]')) sendClue(e.target);
  });

  PG.register('ito', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'PLAY': h = playView(); break;
        case 'RESULT': h = resultView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="ito stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.round !== next.round) { armed = null; animatedTable = 0; }
      if (prev && prev.phase !== next.phase) { armed = null; a.vibrate(120); }
      // Somebody jumped the queue: everyone feels it.
      if (prev && prev.round === next.round && next.lives < prev.lives) { a.vibrate([200, 80, 200]); a.buzzer(); }
      // My lowest card changed under the armed button (e.g. it got discarded): disarm.
      const low = c => (c && c.me && c.me.cards && c.me.cards[0] || {}).v;
      if (armed === 'play' && low(prev) !== low(next)) armed = null;
    },

    menu(a) {
      api = a;
      const s = g();
      if (!s) return '';
      const conf = (act, label) => armed === act
        ? `<button class="danger" data-act="${act}">Точно? ${label}</button>`
        : `<button class="soft" data-act="arm" data-v="${act}">${label}</button>`;
      return (s.phase !== 'LOBBY' ? conf('abort', 'Перервати гру') : '') + conf('newGame', 'Скинути рекорд');
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'spice': return a.send({ t: 'spice', v: el.dataset.v });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'swap': return a.send({ t: 'swap' });
        case 'arm': armed = el.dataset.v; a.render(); setTimeout(() => { if (armed === el.dataset.v) { armed = null; a.render(); } }, 4000); return;
        case 'play': armed = null; return a.send({ t: 'play', v: +el.dataset.v });
        case 'abort': case 'newGame': armed = null; a.send({ t: el.dataset.act }); return a.render();
      }
    },

    key(e) {
      if (e.key === 'Enter' && e.target.dataset && e.target.dataset.v) { sendClue(e.target); e.target.blur(); }
    },
  });
})();
