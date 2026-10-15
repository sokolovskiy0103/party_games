// «Хвиля» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  let api = PG.api;
  let myVal = 50;        // marker position while the player is still choosing
  let dragging = false;
  let animatedRound = -1; // round whose reveal animation has already played

  const g = () => api.g;
  const me = () => g() && g().me;
  const isPsychic = () => !!(me() && g() && g().psychic === me().id);
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');

  // ---------- the scale ----------
  function zonesHtml(target) {
    // widest first so the narrow, valuable zones paint on top
    const zones = (g().zones || []).slice().reverse();
    return zones.map(([d, pts], i) => {
      const lo = Math.max(0, target - d), hi = Math.min(100, target + d);
      return `<div class="wl-zone z${pts}" style="left:${lo}%;width:${hi - lo}%"><b>${i === zones.length - 1 ? pts : ''}</b></div>`;
    }).join('');
  }

  /** opts: target (shows scoring zones), live (draggable marker), marks ([{v, label, mine}]) */
  function scale(opts = {}) {
    const s = g().spectrum;
    if (!s) return '';
    let inner = '';
    if (opts.target != null) inner += zonesHtml(opts.target);
    (opts.marks || []).forEach((m, i) => {
      inner += `<div class="wl-pin ${m.mine ? 'mine' : ''}" style="left:${m.v}%;--i:${i}"><span>${esc(m.label)}</span></div>`;
    });
    if (opts.live) inner += `<div class="wl-pin mine live" id="wlPin" style="left:${myVal}%"><span>▲</span></div>`;
    if (opts.target != null) inner += `<div class="wl-target" style="left:${opts.target}%"></div>`;
    return `<div class="wl-scale">
      <div class="wl-ends"><span>${esc(s.left)}</span><span>${esc(s.right)}</span></div>
      <div class="wl-track ${opts.live ? 'live' : ''} ${opts.fresh ? 'fresh' : ''}" id="wlTrack">${inner}</div>
    </div>`;
  }

  function track() { return document.getElementById('wlTrack'); }
  function setFromEvent(e) {
    const t = track(); if (!t) return;
    const r = t.getBoundingClientRect();
    myVal = Math.round(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * 100);
    const pin = document.getElementById('wlPin');
    if (pin) pin.style.left = myVal + '%';
  }
  document.addEventListener('pointerdown', e => {
    const t = e.target.closest('.wl-track.live');
    if (!t) return;
    dragging = true;
    setFromEvent(e);
    e.preventDefault();
  });
  document.addEventListener('pointermove', e => { if (dragging) setFromEvent(e); });
  const stop = () => { dragging = false; };
  document.addEventListener('pointerup', stop);
  document.addEventListener('pointercancel', stop);

  // ---------- helpers ----------
  function scoreboard() {
    const s = g();
    const pts = (s.result && s.result.points) || {};
    const ps = [...s.players].sort((a, b) => b.score - a.score);
    return `<div class="card">${ps.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && me().id === p.id ? ' <span class="muted small">(ти)</span>' : ''}
          ${s.psychic === p.id && s.phase !== 'LOBBY' ? ' <span class="small c-psy">екстрасенс</span>' : ''}</span>
        ${pts[p.id] ? `<span class="plus">+${pts[p.id]}</span>` : ''}
        <span class="pts">${p.score}</span>
      </div>`).join('') || '<div class="muted">Поки нікого</div>'}
    </div>`;
  }

  // ---------- co-op (two players) ----------
  const series = () => g().series || null;
  /** The round on screen is a co-op one. */
  const inCoop = () => !!(series() && series().active);

  function rating(score, played) {
    // scaled to the default series length, so bonus rounds still help
    const k = played ? score / Math.max(played, (series() || {}).total || 7) : 0;
    if (k >= 3.4) return 'Телепатія! 🧠';
    if (k >= 2.6) return 'На одній хвилі 📡';
    if (k >= 1.8) return 'Добре чуєте одне одного';
    if (k >= 1) return 'Є контакт';
    return 'Перешкоди на лінії 📻';
  }

  function teamCard() {
    const se = series();
    if (!se) return '';
    const status = !se.active || !se.played ? `Серія з ${se.total} раундів · яблучко дає бонусний раунд`
      : se.over ? `Серію завершено: ${se.played} раундів — нова почнеться з нуля`
      : `Зіграно ${se.played}, лишилось ${se.left} · яблучко дає бонусний раунд`;
    return `<div class="card wl-team">
      <div class="score"><span class="name">Спільний рахунок</span><span class="pts">${se.active ? se.score : 0}</span></div>
      <div class="muted small">${status}</div>
      <div class="wl-names">${g().players.map(p => `<span><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}${me() && me().id === p.id ? ' <span class="muted small">(ти)</span>' : ''}</span>`).join('')}</div>
    </div>`;
  }

  function lockedChips() {
    const s = g();
    return `<div class="chips">${s.players.filter(p => p.inRound && p.id !== s.psychic).map(p =>
      `<span class="chip ${p.locked ? 'ok' : ''}">${p.locked ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += s.coop ? `<div class="card flat stack">
      <b>Удвох — кооператив</b>
      <div class="muted small">Граєте разом проти шкали: по черзі один — екстрасенс, другий ставить маркер. Очки йдуть у спільний рахунок, серія — ${(series() || {}).total || 7} раундів, а влучання в яблучко дає бонусний раунд.</div>
    </div>` : `<div class="card flat stack">
      <b>Як грати</b>
      <div class="muted small">Екстрасенс бачить, де на шкалі між двома поняттями прихована ціль, і дає підказку. Решта ставить маркер там, де, на їхню думку, ціль. Що ближче — то більше очок.</div>
      <div class="muted small">Удвох гра сама вмикає кооперативний режим.</div>
    </div>`;
    if (!api.isHost) h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне раунд</span></div>`;
    h += `<h2>Гравці · ${n}</h2>${s.coop ? teamCard() : scoreboard()}`;
    if (api.isHost) {
      const cont = s.coop ? inCoop() && series().played > 0 && !series().over : s.round;
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>${cont ? 'Наступний раунд' : 'Почати гру'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці (зараз ${n}).</div>`;
    }
    return h;
  }

  function clueView() {
    const s = g();
    const inRound = me() && me().inRound;
    const se = series();
    const num = inCoop() ? `Раунд ${se.played + 1} з ${se.played + se.left}` : `Раунд ${s.round}`;
    let h = `<div class="muted center">${num} · екстрасенс — <b class="c-psy">${nameOf(s.psychic)}</b>${inCoop() ? ` · рахунок <b>${se.score}</b>` : ''}</div>`;
    if (isPsychic()) {
      h += `<div class="center"><span class="tag">ти екстрасенс</span></div>
        ${scale({ target: s.target })}
        <div class="muted small center">Ціль бачиш лише ти. Придумай підказку, яка вказує саме на неї — не називай цифр і самих понять.</div>
        <input id="clue" type="text" maxlength="60" placeholder="Підказка" autocomplete="off">
        <div class="row">
          <button class="ghost grow" data-act="swap">Інша шкала</button>
          <button class="grow" data-act="clue">Дати підказку</button>
        </div>`;
    } else {
      h += `<div class="card center stack"><span class="spinner"></span>
        <div><b>${nameOf(s.psychic)}</b> придумує підказку…</div>
        ${scale()}</div>`;
      if (!inRound && me()) h += `<div class="card flat center muted">Ти приєднався під час раунду — зіграєш у наступному.</div>`;
    }
    return h;
  }

  function guessView() {
    const s = g();
    const iGuess = me() && me().inRound && !isPsychic();
    let h = `<div class="card center"><div class="muted small">Підказка від ${nameOf(s.psychic)}</div>
      <div class="clue">${esc(s.clue || '')}</div></div>`;
    if (isPsychic()) {
      h += `<div class="center"><span class="tag">ти екстрасенс</span></div>${scale({ target: s.target })}
        <div class="muted center small">Чекаємо, поки інші поставлять маркери.</div>`;
    } else if (iGuess) {
      const locked = s.myGuess !== undefined;
      h += `${scale({ live: true })}
        <div class="muted small center">Торкнись шкали або тягни маркер.</div>
        <button class="big" data-act="lock">${locked ? 'Змінити вибір ✓' : 'Зафіксувати'}</button>`;
    } else {
      h += scale();
    }
    h += lockedChips();
    if (api.isHost) h += `<button class="ghost" data-act="reveal">Показати результат</button>`;
    return h;
  }

  function coopResultView() {
    const s = g();
    const se = series();
    const r = s.result || { guesses: {}, points: {}, target: 50 };
    const guesser = Object.keys(r.guesses)[0];
    const pts = guesser ? (r.points[guesser] || 0) : 0;
    const marks = guesser ? [{ v: r.guesses[guesser], label: nameOf(guesser).slice(0, 1).toUpperCase(), mine: me() && me().id === guesser }] : [];
    const fresh = animatedRound !== s.round;
    if (fresh) setTimeout(() => { animatedRound = s.round; }, 0);
    const verdict = !guesser ? 'Маркер не поставили' : pts >= 4 ? 'Точно в ціль! 🎯' : pts === 3 ? 'Дуже близько!' : pts === 2 ? 'Непогано' : pts === 1 ? 'Ледь зачепили' : 'Мимо цілі';
    const detail = guesser ? `Відхилення ${Math.abs(r.guesses[guesser] - r.target)}% — +${pts} до спільного рахунку` : 'Раунд закрили раніше, тож без очок';
    let h = `<div class="banner ${pts >= 3 ? 'good' : 'meh'} pop"><h1>${verdict}</h1>
        <div>${detail}</div>
        ${se.bonus ? '<div><b>Яблучко — бонусний раунд!</b></div>' : ''}
        <div class="muted small">Підказка від ${nameOf(s.psychic)}: «${esc(s.clue || '')}»</div></div>
      ${scale({ target: r.target, marks, fresh })}`;
    if (se.over) {
      h += `<div class="banner good pop"><div class="muted small">Серію завершено · ${se.played} раундів</div>
        <h1>${se.score} очок</h1><div><b>${rating(se.score, se.played)}</b></div></div>`;
    } else {
      h += teamCard();
    }
    h += `<div class="muted small">Очки за відстань: ${(s.zones || []).map(([d, p]) => `±${d}% — ${p}`).join(', ')}.</div>`;
    if (!s.coop) h += `<div class="notice">Гравців уже ${s.players.length} — наступний раунд буде у звичайному режимі.</div>`;
    if (api.isHost) {
      const label = !s.coop ? 'Наступний раунд' : se.over ? 'Нова серія' : `Наступний раунд (лишилось ${se.left})`;
      h += `<button class="big" data-act="next" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>${label}</button>`;
    } else h += `<div class="muted center small">Хост запустить ${se.over && s.coop ? 'нову серію' : 'наступний раунд'}.</div>`;
    return h;
  }

  function resultView() {
    const s = g();
    if (inCoop()) return coopResultView();
    const r = s.result || { guesses: {}, points: {}, target: 50 };
    const marks = Object.keys(r.guesses).map(id => ({ v: r.guesses[id], label: (((s.players.find(p => p.id === id) || {}).name) || '?').slice(0, 1).toUpperCase(), mine: me() && me().id === id }));
    const rows = Object.keys(r.guesses).sort((a, b) => Math.abs(r.guesses[a] - r.target) - Math.abs(r.guesses[b] - r.target)).map(id =>
      `<div class="score"><span class="name">${nameOf(id)}</span>
        <span class="muted small">${Math.abs(r.guesses[id] - r.target)}%</span>
        <span class="plus">+${r.points[id] || 0}</span></div>`).join('');
    const fresh = animatedRound !== s.round;
    if (fresh) setTimeout(() => { animatedRound = s.round; }, 0);
    const ids = Object.keys(r.guesses);
    const closest = ids.sort((x, y) => Math.abs(r.guesses[x] - r.target) - Math.abs(r.guesses[y] - r.target))[0];
    const dist = id => Math.abs(r.guesses[id] - r.target);
    const byPts = pts => pts >= 4 ? 'Точно в ціль! 🎯' : pts === 3 ? 'Дуже близько!' : pts === 2 ? 'Непогано' : pts === 1 ? 'Ледь зачепив(ла)' : 'Мимо цілі';
    // The headline is about the viewer, not about the best player.
    const myId = me() && me().id;
    let pts, verdict, detail;
    if (myId && myId === s.psychic) {
      pts = r.points[myId] || 0;
      verdict = pts >= 3 ? 'Підказка спрацювала!' : pts >= 1 ? 'Підказку зрозуміли частково' : 'Підказка не спрацювала';
      detail = `Ти отримуєш середнє від очок гравців: +${pts}`;
    } else if (myId && myId in r.guesses) {
      pts = r.points[myId] || 0;
      verdict = byPts(pts);
      detail = `Твоє відхилення — ${dist(myId)}%, це +${pts}`;
    } else if (myId && s.me && s.me.inRound) {
      pts = 0; verdict = 'Ти не поставив(ла) маркер'; detail = 'Раунд закрили раніше, тож без очок';
    } else {
      pts = closest ? (r.points[closest] || 0) : 0;
      verdict = closest ? byPts(pts) : 'Ніхто не відповів';
      detail = closest ? 'Найкращий результат раунду' : 'Ніхто не встиг поставити маркер';
    }
    const bestLine = closest && closest !== myId ? `<div class="muted small">Найближче — ${nameOf(closest)}, відхилення ${dist(closest)}%</div>` : '';
    let h = `<div class="banner ${pts >= 3 ? 'good' : 'meh'} pop"><h1>${verdict}</h1>
        <div>${detail}</div>${bestLine}<div class="muted small">Підказка: «${esc(s.clue || '')}»</div></div>
      ${scale({ target: r.target, marks, fresh })}
      <div class="card">${rows || '<div class="muted">Ніхто не поставив маркер</div>'}
        <div class="score"><span class="name">${nameOf(s.psychic)} <span class="small c-psy">екстрасенс</span></span><span class="plus">+${r.points[s.psychic] || 0}</span></div>
      </div>
      <h2>Рахунок</h2>${scoreboard()}
      <div class="muted small">Очки за відстань: ${(s.zones || []).map(([d, p]) => `±${d}% — ${p}`).join(', ')}. Екстрасенс отримує середнє від очок решти.</div>`;
    if (api.isHost) h += `<button class="big" data-act="next" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Наступний раунд</button>`;
    else h += `<div class="muted center small">Хост запустить наступний раунд.</div>`;
    return h;
  }

  PG.register('wavelength', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'CLUE': h = clueView(); break;
        case 'GUESS': h = guessView(); break;
        case 'RESULT': h = resultView(); break;
        default: h = '';
      }
      return `<div class="wavelength stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.round !== next.round || prev.phase !== next.phase) {
        // continue from where the player had already locked, otherwise start in the middle
        myVal = next.myGuess !== undefined ? next.myGuess : 50;
        if (prev && next.phase !== 'LOBBY') a.vibrate(120);
      }
    },

    menu() { return ['newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'start': case 'next': return a.send({ t: 'start' });
        case 'swap': return a.send({ t: 'swap' });
        case 'clue': {
          const input = document.getElementById('clue');
          const text = (input && input.value || '').trim();
          if (!text) { input && input.focus(); return; }
          return a.send({ t: 'clue', text });
        }
        case 'lock': return a.send({ t: 'lock', v: myVal });
        case 'reveal': return a.send({ t: 'reveal' });
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
