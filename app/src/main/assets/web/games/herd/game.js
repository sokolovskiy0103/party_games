// «Стадо» UI. The host (who may or may not play) gets the controls; players answer.
(function () {
  'use strict';
  const { esc, ui } = PG.api;
  const LEVELS = { light: 'Легкий', spicy: 'Пікантний', hot: '18+', custom: 'Своє' };

  let api = PG.api;
  let selected = null;     // answer picked for moving during judging
  let showCustom = false;

  const g = () => api.g;

  function cow(pink = false, cls = 'cow') {
    const face = pink ? '#ff8fb5' : '#ffffff';
    const spot = pink ? '#c23b6e' : '#2b1b3d';
    return `<svg class="${cls}" viewBox="20 18 68 66" aria-hidden="true">
      <path fill="#fff4d6" d="M36,34 C30,30 29,24 32,21 C34,26 38,29 42,30 Z M72,34 C78,30 79,24 76,21 C74,26 70,29 66,30 Z"/>
      <path fill="${face}" d="M30,42 C22,40 19,46 24,49 C28,51 33,48 35,46 Z M78,42 C86,40 89,46 84,49 C80,51 75,48 73,46 Z M54,28 C68,28 76,38 76,52 C76,66 70,80 54,80 C38,80 32,66 32,52 C32,38 40,28 54,28 Z"/>
      <path fill="${spot}" d="M38,40 C42,34 50,36 48,42 C47,47 40,48 38,44 Z M66,34 C72,36 74,42 70,45 C67,47 63,42 64,38 Z"/>
      <circle fill="#2b1b3d" cx="45" cy="52" r="3"/><circle fill="#2b1b3d" cx="63" cy="52" r="3"/>
      <path fill="#ffb3cc" d="M54,60 C64,60 69,64 69,70 C69,76 63,80 54,80 C45,80 39,76 39,70 C39,64 44,60 54,60 Z"/>
      <circle fill="#c23b6e" cx="48" cy="69" r="2"/><circle fill="#c23b6e" cx="60" cy="69" r="2"/></svg>`;
  }

  function cowWord(n) {
    const a = n % 10, b = n % 100;
    return a === 1 && b !== 11 ? 'корови' : 'корів';
  }

  const nameOf = id => esc((g().players.find(p => p.id === id) || {}).name || '?');

  function scoreboard() {
    const st = g();
    const ps = [...st.players].sort((a, b) => b.score - a.score);
    const scored = new Set(st.result ? st.result.scored : []);
    return `<div class="card">${ps.map(p => `
      <div class="li">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${st.me && st.me.id === p.id ? ' <span class="muted small">(ти)</span>' : ''}</span>
        ${st.pinkCow === p.id ? cow(true) : ''}
        ${scored.has(p.id) && (st.phase === 'RESULTS' || st.phase === 'GAMEOVER') ? '<span class="plus">+1</span>' : ''}
        <span class="pts">${p.score}</span>${cow(false)}
      </div>`).join('') || '<div class="muted">Поки нікого</div>'}
      <div class="muted small" style="margin-top:8px">Гра до ${st.target} ${cowWord(st.target)}. Рожева корова не дає перемогти.</div>
    </div>`;
  }

  function groupsHtml({ selectable = false } = {}) {
    const st = g();
    if (!st.groups) return '';
    const r = st.result;
    return st.groups.map((gr, i) => {
      const win = r && r.majorityGroup === i;
      const isTarget = selectable && selected && !gr.members.some(m => m.id === selected);
      return `<div class="group ${win ? 'win' : ''} ${isTarget ? 'target' : ''}" data-group="${i}">
        <div class="head">
          <span class="label">${esc(gr.label)}</span>
          <span class="count">${gr.members.length} ${win ? cow(false) : ''}</span>
        </div>
        <div>${gr.members.map(m => `
          <span class="member ${m.mine ? 'me' : ''} ${selected === m.id ? 'sel' : ''}" data-k="${m.id}" ${selectable ? `data-pid="${m.id}"` : ''}>
            <span>${esc(m.text)}</span>${m.name ? `<span class="who">${esc(m.name)}</span>` : (m.mine ? '<span class="who">ти</span>' : '')}
          </span>`).join('')}
        </div>
      </div>`;
    }).join('');
  }

  function resultLine() {
    const st = g();
    const r = st.result;
    if (!r) return '';
    const parts = [];
    if (r.majorityGroup >= 0) parts.push(`Стадо: ${r.scored.map(nameOf).join(', ')} отримують по корові.`);
    else if (r.tie) parts.push('Нічия між найбільшими групами — ніхто не отримує корову.');
    else parts.push('Усі відповіли по-різному — стада немає.');
    if (r.pinkTo) parts.push(`Рожеву корову отримує ${nameOf(r.pinkTo)}.`);
    return parts.join(' ');
  }

  // ---------- host views ----------
  function aiPill(ai) {
    if (!ai || !ai.enabled) return '';
    return { READY: '🧠', DOWNLOADING: `⬇ ${Math.round(ai.progress * 100)}%`, LOADING: '🧠…', ERROR: '⚠' }[ai.state] || '';
  }

  function aiCard(ai) {
    if (!ai) return '';
    let hint = 'Сам розкладає схожі відповіді по групах: «тачка» = «машина». Один раз потрібно завантажити ≈430 МБ — краще по Wi-Fi.';
    if (ai.enabled) {
      switch (ai.state) {
        case 'DOWNLOADING': hint = `<div class="bar"><i style="width:${(ai.progress * 100).toFixed(1)}%"></i></div>${esc(ai.message)}. Можна грати вже зараз — увімкнеться саме.`; break;
        case 'LOADING': hint = `<span class="spinner"></span> ${esc(ai.message)}`; break;
        case 'READY': hint = `Увімкнено.${ai.lastMillis ? ` Останній розбір: ${(ai.lastMillis / 1000).toFixed(1)} с.` : ''}`; break;
        case 'ERROR': hint = `<span style="color:var(--pink2)">${esc(ai.message)}</span> Гра працює й без цього. <button class="mini soft" data-act="autoRetry">Ще раз</button>`; break;
      }
    }
    return `<div class="card stack">
      <label class="toggle">
        <input type="checkbox" class="switch" data-act="autoGroup" ${ai.enabled ? 'checked' : ''}>
        <b class="grow">Авторозподіл відповідей</b>
      </label>
      <div class="muted small">${hint}</div>
    </div>`;
  }

  function hostLobby() {
    const st = g();
    const lv = new Set(st.levels);
    return `<div class="card stack">
        <h2>Відвертість</h2>
        <div class="chips">${['light', 'spicy', 'hot'].map(l => `<button class="chip ${lv.has(l) ? 'on' : ''}" data-level="${l}">${LEVELS[l]}</button>`).join('')}</div>
        <button class="chip ${st.anonymous ? 'on' : ''}" data-act="anon" style="align-self:flex-start">${st.anonymous ? '🙈 Анонімні відповіді: так' : '👀 Анонімні відповіді: ні'}</button>
        <div class="muted small">${st.anonymous ? 'Під час розбору видно тільки тексти, без імен.' : 'Під час розбору біля кожної відповіді видно, хто її написав.'}</div>
        <div class="row"><span class="grow">Гра до</span>
          <button class="mini soft" data-act="targetDown">−</button>
          <b style="min-width:2ch;text-align:center">${st.target}</b>
          <button class="mini soft" data-act="targetUp">+</button>
          <span>${cowWord(st.target)}</span></div>
      </div>
      ${aiCard(api.mod)}
      <h2>Гравці · ${st.players.length}</h2>${scoreboard()}`;
  }

  function customBox() {
    if (!showCustom) return `<button class="ghost" data-act="showCustom" style="width:100%">✍️ Своє питання</button>`;
    return `<div class="row"><input id="custom" type="text" maxlength="200" placeholder="Напиши своє питання" style="flex:1">
      <button data-act="custom">Задати</button></div>`;
  }

  function answerInput(st) {
    const a = st.me.answer;
    return `<input id="ans-${st.round}-${esc(st.question).length}" type="text" maxlength="80" placeholder="${a ? 'Змінити відповідь' : 'Твоя відповідь'}" enterkeyhint="send" autocomplete="off">`;
  }

  function hostQuestion() {
    const st = g();
    return `<div class="row"><span class="tag ${st.questionLevel}">${LEVELS[st.questionLevel] || ''}</span><span class="muted small">Раунд ${st.round}</span></div>
      <div class="hero question" data-k="${esc(st.question)}">${esc(st.question)}</div>
      <div class="chips">${st.players.map(p => `<span class="chip ${p.answered ? 'ok' : ''}">${p.answered ? '✓' : '…'} ${esc(p.name)}</span>`).join('')}</div>
      ${st.me ? `<div class="card flat stack">
        ${st.me.answer ? `<div><span class="muted small">Твоя відповідь:</span> <b>${esc(st.me.answer)}</b></div>` : ''}
        <div class="row">${answerInput(st).replace('<input ', '<input style="flex:1" ')}<button data-act="answer">${st.me.answer ? 'Змінити' : 'OK'}</button></div>
      </div>` : ''}
      ${customBox()}`;
  }

  function hostJudging() {
    const st = g();
    const src = { ai: '🧠 розподілено автоматично', fuzzy: '🔤 автоматичний збіг', manual: '✋ виправлено вручну' }[st.groupSource] || '';
    return `<div class="hero question" style="font-size:20px">${esc(st.question)}</div>
      ${st.judging
        ? `<div class="card flat center"><span class="spinner"></span> Групую відповіді…</div>`
        : `<div class="muted small">${src}. ${selected ? '<b class="c-warn">Тепер тапни групу, куди перенести.</b>' : 'Не згоден? Тапни відповідь, потім групу.'}</div>`}
      <div class="stack">${groupsHtml({ selectable: !st.judging })}</div>
      ${selected ? `<button class="ghost" data-act="newGroup" style="width:100%">＋ В окрему групу</button>` : ''}`;
  }

  function hostView() {
    const st = g();
    switch (st.phase) {
      case 'LOBBY':
        return { html: hostLobby(), bar: `<button class="big" data-act="start" ${st.players.length < 2 ? 'disabled' : ''}>${st.players.length < 2 ? 'Потрібно хоча б 2 гравці' : 'Почати гру'}</button>` };
      case 'QUESTION':
        return { html: hostQuestion(), bar: `<button class="soft" data-act="skip">Інше питання</button>
          <button data-act="reveal" ${st.answeredCount < 1 ? 'disabled' : ''}>Розкрити ${st.answeredCount}/${st.players.length}</button>` };
      case 'JUDGING':
        return { html: hostJudging(), bar: `<button class="soft" data-act="regroup" ${st.judging ? 'disabled' : ''}>Перегрупувати</button>
          <button data-act="confirm" ${st.judging ? 'disabled' : ''}>Зарахувати</button>` };
      case 'RESULTS':
        return { html: `${st.me ? myBanner() : ''}<div class="card flat">${resultLine()}</div><div class="stack">${groupsHtml()}</div>
          <h2>Рахунок</h2>${scoreboard()}${customBox()}`, bar: `<button class="big" data-act="next">Наступне питання</button>` };
      case 'GAMEOVER': {
        const names = (st.winners || []).map(nameOf).join(', ');
        return { html: `<div class="banner good">${cow(false)}Перемога: ${names}</div>
          ${st.pinkCow ? `<div class="card flat center">${cow(true)} Рожева корова: ${nameOf(st.pinkCow)}</div>` : ''}
          <h2>Рахунок</h2>${scoreboard()}`, bar: `<button class="big" data-act="newGame">Нова гра</button>` };
      }
    }
    return { html: '' };
  }

  // ---------- player views ----------
  function myBanner() {
    const st = g();
    const me = st.me.id, r = st.result || {};
    if (st.phase === 'GAMEOVER') {
      const won = (st.winners || []).includes(me);
      const names = (st.winners || []).map(nameOf).join(', ');
      return `<div class="banner ${won ? 'good' : 'meh'}">${cow(false)}${won ? 'Ти переміг! Справжній лідер стада' : `Перемога: ${names}`}</div>`;
    }
    if (r.pinkTo === me) return `<div class="banner bad">${cow(true)}Ти відбився від стада — тримай рожеву корову</div>`;
    if ((r.scored || []).includes(me)) return `<div class="banner good">${cow(false)}+1 корова! Ти в стаді</div>`;
    if (!st.groups || !st.groups.some(gr => gr.members.some(m => m.mine))) return `<div class="banner meh">Ти не відповів цього раунду</div>`;
    return `<div class="banner meh">Цього разу без корови</div>`;
  }

  function playerView() {
    const st = g();
    const me = st.me;
    if (!me) return '';
    switch (st.phase) {
      case 'LOBBY':
        return `<h1>Привіт, ${esc(me.name)}!</h1>
          ${ui.wait('Чекаємо, поки ведучий почне гру', '👑')}
          <h2>У грі ${st.players.length}</h2>
          <div class="chips">${st.players.map(p => `<span class="chip">${esc(p.name)}</span>`).join('')}</div>`;
      case 'QUESTION': {
        const answered = !!me.answer;
        return `<div class="row"><span class="tag ${st.questionLevel}">${LEVELS[st.questionLevel] || ''}</span><span class="muted small">Раунд ${st.round}</span></div>
          <div class="hero question" data-k="${esc(st.question)}">${esc(st.question)}</div>
          ${answered ? `<div class="card flat"><div class="muted small">Твоя відповідь</div><div style="font-size:20px;font-weight:800">${esc(me.answer)}</div><div class="muted small">Можна змінити, поки ведучий не розкрив відповіді</div></div>` : ''}
          ${answerInput(st)}
          <button class="big" data-act="answer">${answered ? 'Змінити' : 'Відповісти'}</button>
          <div class="muted small center">Відповіли ${st.answeredCount} з ${st.players.length}</div>`;
      }
      case 'JUDGING':
        return `<div class="hero question" style="font-size:20px">${esc(st.question)}</div>
          <div class="card flat center">${st.judging ? '<span class="spinner"></span> Звіряємо відповіді…' : 'Ведучий перевіряє групи. Сперечайтесь голосно!'}</div>
          ${groupsHtml()}`;
      case 'RESULTS':
      case 'GAMEOVER':
        return `${myBanner()}
          <div class="muted">${resultLine()}</div>
          <div class="hero question" style="font-size:18px">${esc(st.question)}</div>
          ${groupsHtml()}
          <h2>Рахунок</h2>${scoreboard()}
          ${st.phase === 'RESULTS' ? '<div class="muted small center">Чекаємо на наступне питання…</div>' : ''}`;
    }
    return '';
  }

  // ---------- TV ----------
  // public only: the question, who has answered, and the groups once answers are open
  function tvBoard() {
    const st = g();
    const head = `<div class="row"><span class="tag ${st.questionLevel}">${LEVELS[st.questionLevel] || ''}</span><span class="muted">Раунд ${st.round}</span></div>`;
    const question = `<div class="hero question tv-question">${esc(st.question)}</div>`;
    let main;
    switch (st.phase) {
      case 'QUESTION':
        main = `${head}${question}<h2>Відповіли ${st.answeredCount} з ${st.players.length}</h2>
          <div class="chips">${st.players.map(p => `<span class="chip ${p.answered ? 'ok' : ''}" data-k="${p.id}">${p.answered ? '✓' : '…'} ${esc(p.name)}</span>`).join('')}</div>`;
        break;
      case 'JUDGING':
        main = `${head}${question}
          <div class="card flat center">${st.judging ? '<span class="spinner"></span> Групуємо відповіді…' : 'Відповіді розкрито. Сперечайтесь голосно!'}</div>
          <div class="stack">${groupsHtml()}</div>`;
        break;
      case 'RESULTS':
        main = `${head}${question}<div class="card flat">${resultLine()}</div><div class="stack">${groupsHtml()}</div>`;
        break;
      case 'GAMEOVER': {
        const names = (st.winners || []).map(nameOf).join(', ');
        main = `${head}<div class="banner good">${cow(false)}Перемога: ${names}</div>
          ${st.pinkCow ? `<div class="card flat center">${cow(true)} Рожева корова: ${nameOf(st.pinkCow)}</div>` : ''}
          <div class="stack">${groupsHtml()}</div>`;
        break;
      }
      default: return null;
    }
    return `<div class="tv-split"><div class="stack">${main}</div><div class="stack"><h2>Рахунок</h2>${scoreboard()}</div></div>`;
  }

  // ---------- actions ----------
  function sendAnswer() {
    const input = document.querySelector('input[id^="ans-"]');
    const text = (input && input.value || '').trim();
    if (!text) { input && input.focus(); return; }
    api.send({ t: 'answer', text });
    input.value = '';
    input.blur();
  }

  function act(name) {
    const st = g();
    switch (name) {
      case 'answer': return sendAnswer();
      case 'autoGroup': return api.sendMod({ t: 'autoGroup', on: !(api.mod && api.mod.enabled) });
      case 'autoRetry': return api.sendMod({ t: 'autoGroup', on: true });
      case 'anon': return api.send({ t: 'anonymous', on: !st.anonymous });
      case 'targetUp': return api.send({ t: 'target', target: st.target + 1 });
      case 'targetDown': return api.send({ t: 'target', target: st.target - 1 });
      case 'showCustom': showCustom = true; api.render(); { const c = document.getElementById('custom'); c && c.focus(); } return;
      case 'custom': {
        const c = document.getElementById('custom');
        const text = (c && c.value || '').trim();
        if (!text) return;
        showCustom = false;
        return api.send({ t: 'custom', text });
      }
      case 'newGroup': if (selected) { api.send({ t: 'move', pid: selected, to: -1 }); selected = null; } return;
      default: return api.send({ t: name });
    }
  }

  PG.register('herd', {
    // background music per phase (shell.js): answers are typed; the groups and results get talked over
    music: { JUDGING: 'low', RESULTS: 'low', GAMEOVER: 'low' },
    musicLoop: 84,  // seconds of music.mp3 that loop: a whole number of bars
    bg: 'still',  // games/herd/bg.webp (phone), bg-tv.webp (TV)
    sfx: ['moo', 'pink'],  // own effects: sfx/<name>.mp3
    render(a) {
      api = a;
      const st = g();
      if (!st) return '';
      if (a.isHost) {
        const out = hostView();
        return { html: `<div class="herd stack">${out.html}</div>`, bar: out.bar };
      }
      return `<div class="herd stack">${playerView()}</div>`;
    },

    board(a) {
      api = a;
      const st = g();
      if (!st) return null;
      const out = tvBoard();
      return out && `<div class="herd">${out}</div>`;  // null before the game: the TV shows the room
    },

    extra(a) { api = a; return a.isHost ? esc(aiPill(a.mod)) : ''; },

    onState(prev, next, a) {
      api = a;
      if (!prev || !next || prev.phase !== next.phase || prev.round !== next.round) { selected = null; showCustom = false; }
      if (prev && next && prev.phase !== next.phase) {
        api.vibrate(100);
        if (next.phase === 'JUDGING') api.sfx('reveal');
        if (next.phase === 'RESULTS') api.sfx('moo');
        if (next.phase === 'GAMEOVER') {
          api.sfx('win');
          if (next.me && (next.winners || []).includes(next.me.id)) api.vibrate([200, 100, 200]);
        }
      }
      // the pink cow changed hands
      if (prev && next && next.pinkCow && next.pinkCow !== prev.pinkCow) {
        api.sfx('pink', 1500);
        if (next.me && next.me.id === next.pinkCow) api.vibrate([120, 60, 120]);
      }
    },

    menu(a) {
      api = a;
      const st = g();
      return st && st.phase !== 'LOBBY' && st.phase !== 'GAMEOVER' ? ['newGame'] : [];
    },

    click(e, a) {
      api = a;
      const st = g();
      if (!st) return;
      const lvl = e.target.closest('[data-level]');
      if (lvl) {
        const set = new Set(st.levels);
        set.has(lvl.dataset.level) ? set.delete(lvl.dataset.level) : set.add(lvl.dataset.level);
        if (set.size) a.send({ t: 'levels', levels: [...set] });
        return;
      }
      const el = e.target.closest('[data-act]');
      if (el) { if (!el.disabled) act(el.dataset.act); return; }
      if (a.isHost && st.phase === 'JUDGING' && !st.judging) {
        const m = e.target.closest('[data-pid]');
        if (m) { selected = selected === m.dataset.pid ? null : m.dataset.pid; a.render(); return; }
        const gr = e.target.closest('[data-group]');
        if (gr && selected) { a.send({ t: 'move', pid: selected, to: +gr.dataset.group }); selected = null; }
      }
    },

    key(e, a) {
      api = a;
      if (e.key !== 'Enter') return;
      if (e.target.id === 'custom') act('custom');
      else if (e.target.id && e.target.id.startsWith('ans-')) sendAnswer();
    },
  });
})();
