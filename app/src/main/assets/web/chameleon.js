// «Хамелеон» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  let api = PG.api;
  let revealed = false;   // role card flipped open
  let armed = null;       // two-tap confirmations in the menu
  let animatedRound = -1; // round whose result animation has already played

  const g = () => api.g;
  const me = () => g() && g().me;
  // In «Схожі слова» the server never says who the Chameleon is, so this is only true in the classic mode.
  const isCham = () => !!(me() && me().chameleon);
  const similar = () => !!(g() && g().mode === 'SIMILAR');
  const inRound = () => !!(me() && me().inRound);
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');

  // ---------- pieces ----------
  function scoreboard() {
    const s = g();
    const pts = (s.result && s.result.points) || {};
    const ps = [...s.players].sort((a, b) => b.score - a.score);
    return `<div class="card">${ps.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && me().id === p.id ? ' <span class="muted small">(ти)</span>' : ''}</span>
        ${pts[p.id] ? `<span class="plus">+${pts[p.id]}</span>` : ''}
        <span class="pts">${p.score}</span>
      </div>`).join('') || '<div class="muted">Поки нікого</div>'}
    </div>`;
  }

  /** The 16-word card. mark: the word to highlight; pick: words are buttons for the guess. */
  function wordGrid(opts = {}) {
    const t = g().topic;
    if (!t) return '';
    return `<div class="card center stack">
      <div class="muted small">Тема</div><div class="topic">${esc(t.name)}</div>
      <div class="cards">${t.words.map(w => opts.pick
        ? `<button class="wcard pickable" data-act="guess" data-w="${esc(w)}">${esc(w)}</button>`
        : `<div class="wcard ${opts.mark === w ? 'mark' : ''} ${opts.dim && opts.dim !== w ? 'dim' : ''}">${esc(w)}</div>`).join('')}
      </div></div>`;
  }

  function roleCard() {
    const s = g();
    if (!me()) return '';
    if (!inRound()) return `<div class="card flat center muted">Ти приєднався під час раунду — зіграєш у наступному.</div>`;
    if (!revealed) {
      return `<div class="role hidden" data-act="flip">
        <div style="font-size:40px">🂠</div>
        <div class="name">Твоя роль</div>
        <div class="what">Торкнись, щоб подивитися. Сховай екран від інших.</div>
      </div>`;
    }
    if (similar()) {
      return `<div class="role PLAYER pop" data-act="flip">
          <div class="muted small">Твоє слово</div>
          <div class="word">${esc(s.word || '')}</div>
          <div class="what">Описуй, що в тебе є, але не називай слова. У когось із вас слово інше, схоже на це.</div>
          <div class="muted small">Торкнись, щоб сховати</div></div>`;
    }
    return isCham()
      ? `<div class="role CHAM pop" data-act="flip">
          <div style="font-size:40px">🦎</div>
          <div class="name c-cham">Ти — Хамелеон</div>
          <div class="what">Ти не знаєш слова. Слухай інші підказки й вдавай, що знаєш. Якщо тебе викриють — спробуй вгадати слово.</div>
          <div class="muted small">Торкнись, щоб сховати</div></div>`
      : `<div class="role PLAYER pop" data-act="flip">
          <div class="muted small">Секретне слово</div>
          <div class="word">${esc(s.word || '')}</div>
          <div class="what">Дай підказку одним словом: щоб свої зрозуміли, а Хамелеон — ні.</div>
          <div class="muted small">Торкнись, щоб сховати</div></div>`;
  }

  function cluesList() {
    const s = g();
    const turn = s.turn;
    return `<div class="card">${s.order.map(id => {
      const c = (s.clues || []).find(x => x.pid === id);
      const now = id === turn;
      return `<div class="score ${now ? 'now' : ''}">
        <span class="name">${nameOf(id)}</span>
        ${c ? `<span class="clue">${esc(c.text)}</span>` : now ? '<span class="spinner"></span>' : '<span class="muted small">чекає</span>'}
      </div>`;
    }).join('')}</div>`;
  }

  function votedChips() {
    const s = g();
    return `<div class="chips">${s.players.filter(p => p.inRound).map(p =>
      `<span class="chip ${p.voted ? 'ok' : ''}">${p.voted ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    const modes = [['CLASSIC', 'Класичний'], ['SIMILAR', 'Схожі слова']];
    h += `<div class="card stack"><b>Режим</b>
      ${api.isHost
        ? `<div class="chips">${modes.map(([id, label]) => `<button class="chip ${s.mode === id ? 'on' : ''}" data-act="mode" data-v="${id.toLowerCase()}">${label}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${modes.find(m => m[0] === s.mode)[1]}</span></div>`}
      <div class="muted small">${similar()
        ? 'Усі отримують одне слово, а Хамелеон — інше, схоже. Він про це не знає. По черзі описуйте, що у вас є, не називаючи слова, і вирахуйте, хто має інше.'
        : 'Усі бачать тему й 16 слів. Одне з них — секретне, його знають усі, крім Хамелеона. По черзі кожен дає підказку одним словом. Якщо Хамелеона викрили, він ще може вгадати слово.'}</div></div>`;
    if (!api.isHost) h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне раунд</span></div>`;
    h += `<h2>Гравці · ${n}</h2>${scoreboard()}`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>${s.round ? 'Наступний раунд' : 'Почати гру'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці (зараз ${n}).</div>`;
    }
    return h;
  }

  function rolesView() {
    const s = g();
    const inR = s.players.filter(p => p.inRound);
    const readyN = inR.filter(p => p.ready).length;
    const meReady = me() && (s.players.find(p => p.id === me().id) || {}).ready;
    let h = `<div class="muted center">Раунд ${s.round}</div>${similar() ? '' : wordGrid({ mark: revealed && !isCham() ? s.word : null })}${roleCard()}`;
    if (inRound() && !meReady) h += `<button class="soft big" data-act="ready" ${revealed ? '' : 'disabled'}>Запам'ятав(ла) ✓</button>`;
    h += `<div class="chips">${inR.map(p => `<span class="chip ${p.ready ? 'ok' : ''}">${p.ready ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>
      <div class="muted small center">Готові ${readyN} з ${inR.length}. Починаємо, коли всі будуть готові.</div>`;
    if (api.isHost) h += `<button class="ghost" data-act="begin">Почати без очікування</button>`;
    return h;
  }

  function cluesView() {
    const s = g();
    const myTurn = me() && s.turn === me().id;
    let h = `<div class="center"><span class="tag">підказки по колу</span></div>`;
    if (myTurn) {
      h += `<div class="card stack center"><b>Твоя черга!</b>
        <div class="muted small">${similar()
          ? 'Коротко опиши, що в тебе є. Не називай самого слова.'
          : `Одне слово. Не називай секретного слова${isCham() ? ' (ти його не знаєш — вдавай)' : ''}.`}</div>
        <input id="clue" type="text" maxlength="${s.maxClue || 24}" placeholder="${similar() ? 'Опис' : 'Підказка'}" autocomplete="off">
        <button class="big" data-act="clue">Дати підказку</button></div>`;
    } else {
      h += `<div class="card center">Зараз підказує <b class="c-cham">${nameOf(s.turn)}</b></div>`;
    }
    h += cluesList() + roleCard();
    if (api.isHost) h += `<button class="ghost" data-act="skipTurn">Пропустити хід</button>`;
    return h;
  }

  function voteView() {
    const s = g();
    let h = `<div class="center"><span class="tag">голосування</span></div>
      <h1 class="center">Хто Хамелеон?</h1>
      ${cluesList()}`;
    if (inRound()) {
      h += `<div class="grid2">${s.players.filter(p => p.inRound && p.id !== me().id).map(p =>
        `<button class="pick ${s.myVote === p.id ? 'on' : ''}" data-act="vote" data-pid="${p.id}">${esc(p.name)}</button>`).join('')}</div>`;
      if (s.myVote) h += `<div class="muted small center">Голос прийнято. Можна змінити, поки не проголосували всі.</div>`;
    }
    h += votedChips();
    if (api.isHost) h += `<button class="ghost" data-act="closeVote">Завершити голосування</button>`;
    return h;
  }

  function guessView() {
    const s = g();
    let h = `<div class="banner meh pop"><h1>Хамелеона викрито!</h1><div>Це <b class="c-cham">${nameOf(s.accused)}</b></div></div>`;
    if (isCham()) {
      h += `<div class="card center">Вгадай секретне слово — і отримаєш очко.</div>${wordGrid({ pick: true })}`;
    } else {
      h += `<div class="card center"><span class="spinner"></span> <b>${nameOf(s.accused)}</b> пробує вгадати слово…</div>${wordGrid({ mark: s.word })}`;
    }
    if (api.isHost) h += `<button class="ghost" data-act="skipGuess">Пропустити здогадку</button>`;
    return h;
  }

  function resultView() {
    const s = g();
    const r = s.result || { points: {}, tally: {}, votes: {} };
    const fresh = animatedRound !== s.round;
    if (fresh) setTimeout(() => { animatedRound = s.round; }, 0);
    const myId = me() && me().id;
    const iAmCham = myId && myId === s.chameleon;
    const o = r.outcome;
    // The headline is about the viewer's side.
    let verdict, cls;
    if (!inRound()) { verdict = o === 'CAUGHT' ? 'Хамелеона викрито' : 'Хамелеон переміг'; cls = 'meh'; }
    else if (iAmCham) {
      verdict = o === 'ESCAPED' ? 'Ти втік(ла)! 🦎' : o === 'GUESSED' ? 'Вгадав(ла) слово!' : 'Тебе викрили';
      cls = o === 'CAUGHT' ? 'bad' : 'good';
    } else {
      verdict = o === 'CAUGHT' ? 'Хамелеона викрито! 🎉' : o === 'GUESSED' ? 'Хамелеон вгадав слово' : 'Хамелеон втік';
      cls = o === 'CAUGHT' ? 'good' : 'bad';
    }
    const t = r.tally || {};
    const votes = Object.keys(t).sort((a, b) => t[b] - t[a]).map(id => `${nameOf(id)} — ${t[id]}`).join(', ');
    return `<div class="banner ${cls} pop"><h1>${verdict}</h1><div>${esc(r.note || '')}</div></div>
      <div class="card center stack ${fresh ? 'reveal' : ''}">
        <div>Хамелеон: <b class="c-cham">${nameOf(s.chameleon)}</b></div>
        <div>${similar() ? 'Слово більшості' : 'Слово'}: <span class="word small-word">${esc(s.word || '')}</span></div>
        ${similar() && s.chameleonWord ? `<div>У Хамелеона було: <span class="word small-word">${esc(s.chameleonWord)}</span></div>` : ''}
        ${r.guess ? `<div class="muted small">Хамелеон назвав: ${esc(r.guess)}</div>` : ''}
        <div class="muted small">Голоси: ${votes || 'ніхто не проголосував'}</div>
      </div>
      ${cluesList()}
      <h2>Рахунок</h2>${scoreboard()}
      <div class="muted small">${similar()
        ? 'Хамелеон не викритий: +3 йому. Викритий: +2 кожному з решти.'
        : 'Хамелеон не викритий: +3 йому. Викритий, але вгадав слово: +1 йому. Викритий і не вгадав: +2 кожному з решти.'}</div>
      ${api.isHost
        ? `<button class="big" data-act="next" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Наступний раунд</button>`
        : '<div class="muted center small">Хост запустить наступний раунд.</div>'}`;
  }

  PG.register('chameleon', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'ROLES': h = rolesView(); break;
        case 'CLUES': h = cluesView(); break;
        case 'VOTE': h = voteView(); break;
        case 'GUESS': h = guessView(); break;
        case 'RESULT': h = resultView(); break;
        default: h = '';
      }
      return `<div class="chameleon stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.round !== next.round) { if (next.phase === 'ROLES') revealed = false; }
      if (!prev || prev.phase !== next.phase) {
        armed = null;
        if (prev) a.vibrate(120);
      }
      // vibrate when it becomes my turn
      if (prev && next.phase === 'CLUES' && next.me && next.turn === next.me.id && prev.turn !== next.turn) a.vibrate([80, 60, 80]);
    },

    menu(a) {
      api = a;
      const s = g();
      if (!s) return '';
      const conf = (act, label) => armed === act
        ? `<button class="danger" data-act="${act}">Точно? ${label}</button>`
        : `<button class="soft" data-act="arm" data-v="${act}">${label}</button>`;
      return conf('newGame', '🔁 Нова гра');
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'flip': revealed = !revealed; return a.render();
        case 'mode': return a.send({ t: 'mode', v: el.dataset.v });
        case 'start': case 'next': revealed = false; return a.send({ t: 'start' });
        case 'ready': return a.send({ t: 'ready' });
        case 'begin': return a.send({ t: 'begin' });
        case 'clue': {
          const input = document.getElementById('clue');
          const text = (input && input.value || '').trim();
          if (!text) { input && input.focus(); return; }
          return a.send({ t: 'clue', text });
        }
        case 'skipTurn': return a.send({ t: 'skipTurn' });
        case 'vote': return a.send({ t: 'vote', target: el.dataset.pid });
        case 'closeVote': return a.send({ t: 'closeVote' });
        case 'guess': return a.send({ t: 'guess', word: el.dataset.w });
        case 'skipGuess': return a.send({ t: 'skipGuess' });
        case 'arm': armed = el.dataset.v; return a.render();
        case 'newGame': armed = null; a.send({ t: el.dataset.act }); return a.render();
      }
    },

    key(e) {
      if (e.key === 'Enter' && e.target.id === 'clue') {
        const text = (e.target.value || '').trim();
        if (text) api.send({ t: 'clue', text });
      }
    },
  });
})();
