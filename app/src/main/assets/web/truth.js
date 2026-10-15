// «Детектор правди» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  let api = PG.api;
  let armed = null;       // two-tap confirmations for menu actions

  const g = () => api.g;
  const me = () => g() && g().me;
  const isSubject = () => !!(me() && g().subject === me().id);
  const subjectName = () => { const p = g().players.find(x => x.id === g().subject); return p ? p.name : '?'; };
  const canDrive = () => isSubject() || api.isHost;   // may reveal / go next / swap

  const SPICES = [['CLEAN', '😇 Звичайні'], ['MIXED', '🎲 Усі'], ['ADULT', '🔥 18+']];

  const label = truth => truth ? 'Правда' : 'Брехня';
  const icon = truth => truth ? '✅' : '🤥';

  // ---------- pieces ----------
  function header() {
    const s = g();
    return `<div class="row head"><span class="tag">хід ${s.turnNo} з ${s.turnTotal}</span><span class="grow"></span><span class="small muted">гаряче крісло: <b>${esc(subjectName())}</b></span></div>`;
  }

  function questionCard() {
    const q = g().question;
    if (!q) return '';
    return `<div class="card stack center">
      <div class="muted small">${q.adult ? '🔥 ' : ''}Питання для ${isSubject() ? 'тебе' : esc(subjectName())}</div>
      <div class="question">${esc(q.q)}</div>
    </div>`;
  }

  function scoreboard(withGain) {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    return `<div class="card">${list.map((p, i) => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && p.id === me().id ? ' <span class="muted">(ти)</span>' : ''}</span>
        ${withGain && p.gain ? `<span class="gain">+${p.gain}</span>` : ''}
        <span class="pts">${p.score}</span>
      </div>`).join('')}</div>`;
  }

  function voteList() {
    const s = g();
    const voters = s.players.filter(p => p.id !== s.subject);
    return `<div class="card">${voters.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}</span>
        <span class="small ${p.voted ? 'ok' : 'muted'}">${p.voted ? 'проголосував(ла) ✓' : 'думає…'}</span>
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
      <b>Кіл на гравця</b>
      ${api.isHost
        ? `<div class="chips">${Array.from({ length: s.maxTurns }, (_, i) => i + 1).map(k => `<button class="chip ${s.turnsEach === k ? 'on' : ''}" data-act="turns" data-v="${k}">${k}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${s.turnsEach}</span></div>`}
      <div class="muted small">Кожен по черзі сідає в гаряче крісло й отримує особисте питання. Таємно обери: відповісти чесно чи збрехати,
        — і відповідай вголос. Решта голосує «правда» чи «брехня». Хто вгадав — +1. Ти отримуєш +1 за кожного, кого обдурив(ла).</div></div>`;
    if (!api.isHost) h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне</span></div>`;
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function subjectControls() {
    const s = g();
    if (!s.committed) {
      return `<div class="card stack center">
        <b>Що зробиш?</b>
        <div class="muted small">Вибір бачиш тільки ти. Голосування почнеться після нього.</div>
        <div class="row two">
          <button class="big choice truth" data-act="plan" data-v="1">✅ Скажу правду</button>
          <button class="big choice lie" data-act="plan" data-v="0">🤥 Збрешу</button>
        </div>
        <button class="ghost" data-act="swap">Інше питання</button>
      </div>` ;
    }
    return `<div class="banner meh">Ти обрав(ла): ${s.plan ? '✅ говорити правду' : '🤥 брехати'}<div class="small muted">Відповідай вголос — і тримай обличчя!</div></div>
      ${voteList()}
      <button class="big" data-act="reveal">${s.allVoted ? 'Показати вердикт' : 'Показати вердикт (не всі проголосували)'}</button>`;
  }

  function voterControls() {
    const s = g();
    if (!s.committed) {
      return `<div class="card flat center"><span class="spinner"></span> <span class="muted">${esc(subjectName())} вирішує, чесно відповідати чи ні…</span></div>`;
    }
    const mine = me() && me().vote;
    const canVote = !!me();
    let h = `<div class="card stack center">
      <b>${esc(subjectName())} відповідає. Віриш?</b>
      <div class="row two">
        <button class="big choice truth ${mine === true ? 'picked' : ''}" data-act="vote" data-v="1" ${canVote ? '' : 'disabled'}>✅ Правда</button>
        <button class="big choice lie ${mine === false ? 'picked' : ''}" data-act="vote" data-v="0" ${canVote ? '' : 'disabled'}>🤥 Брехня</button>
      </div>
      <div class="muted small">${mine === undefined ? 'Вибери, поки тобі не кажуть інакше.' : 'Можна передумати до вердикту.'}</div>
    </div>` + voteList();
    if (api.isHost) h += `<button class="big" data-act="reveal">Показати вердикт</button>`;
    return h;
  }

  function turnView() {
    const s = g();
    let h = header() + questionCard();
    h += isSubject() ? subjectControls() : voterControls();
    if (api.isHost && !isSubject() && !s.committed) h += `<button class="ghost" data-act="swap">Інше питання</button>`;
    h += `<h2>Рахунок</h2>` + scoreboard(false);
    return h;
  }

  function revealView() {
    const s = g();
    const voters = s.players.filter(p => p.id !== s.subject);
    const cast = voters.filter(p => p.vote !== undefined);
    const fooled = cast.filter(p => p.vote !== s.plan).length;
    let h = `<div class="banner ${s.plan ? 'good' : 'bad'} pop"><h1>${icon(s.plan)} ${esc(subjectName())}: ${s.plan ? 'сказав(ла) правду' : 'збрехав(ла)'}</h1>
      <div>${cast.length ? (fooled ? `Обдурено: ${fooled} з ${cast.length}` : 'Ніхто не повівся!') : 'Ніхто не голосував.'}</div></div>`;
    h += questionCard();
    h += `<div class="card">${voters.map(p => {
      const voted = p.vote !== undefined;
      const right = voted && p.vote === s.plan;
      return `<div class="score ${voted ? (right ? 'right' : 'wrong') : ''}">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}</span>
        <span class="small muted">${voted ? icon(p.vote) + ' ' + label(p.vote) : 'не голосував(ла)'}</span>
        <span class="verdict">${voted ? (right ? '✓' : '✗') : '–'}</span>
      </div>`;
    }).join('') || '<div class="muted center small">Крім гравця в кріслі нікого немає.</div>'}</div>`;
    h += `<h2>Рахунок</h2>` + scoreboard(true);
    h += canDrive()
      ? `<button class="big" data-act="next">${s.turnNo >= s.turnTotal ? 'Підсумки' : 'Далі'}</button>`
      : '<div class="muted center small">Далі — коли хост чи гравець у кріслі натисне.</div>';
    return h;
  }

  function overView() {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    const top = list.length ? list[0].score : 0;
    const winners = list.filter(p => p.score === top).map(p => esc(p.name)).join(', ');
    const liar = list.slice().sort((a, b) => b.fooled - a.fooled)[0];
    const sleuth = list.slice().sort((a, b) => b.caught - a.caught)[0];
    let h = `<div class="banner good pop"><h1>🏆 ${winners}</h1><div>${top} ${top === 1 ? 'очко' : 'очок'}</div></div>`;
    h += scoreboard(false);
    if (liar && liar.fooled > 0) h += `<div class="card flat center small">🤥 Найкращий брехун: <b>${esc(liar.name)}</b> — обдурив(ла) ${liar.fooled}</div>`;
    if (sleuth && sleuth.caught > 0) h += `<div class="card flat center small">🕵️ Найкращий детектор: <b>${esc(sleuth.name)}</b> — вгадав(ла) ${sleuth.caught}</div>`;
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
        case 'TURN': h = turnView(); break;
        case 'REVEAL': h = revealView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="truth stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.round !== next.round) armed = null;
      if (prev && prev.phase !== next.phase) { armed = null; a.vibrate(120); }
      // The subject picked: voters get a nudge that voting is open.
      if (prev && prev.round === next.round && !prev.committed && next.committed && next.subject !== (next.me && next.me.id)) a.vibrate(80);
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

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'spice': return a.send({ t: 'spice', v: el.dataset.v });
        case 'turns': return a.send({ t: 'turns', v: +el.dataset.v });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'swap': return a.send({ t: 'swap' });
        case 'reveal': return a.send({ t: 'reveal' });
        case 'plan': return a.send({ t: 'plan', truth: el.dataset.v === '1' });
        case 'vote': return a.send({ t: 'vote', truth: el.dataset.v === '1' });
        case 'arm': armed = el.dataset.v; a.render(); setTimeout(() => { if (armed === el.dataset.v) { armed = null; a.render(); } }, 4000); return;
        case 'abort': case 'newGame': armed = null; a.send({ t: el.dataset.act }); return a.render();
      }
    },
  });
})();
