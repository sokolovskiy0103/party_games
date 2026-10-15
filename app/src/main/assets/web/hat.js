// «Шляпа» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, store } = PG.api;
  let api = PG.api;

  const ROUND = {
    1: { name: 'Пояснення', icon: '🗣️', rule: 'Пояснюй словами, як в «Еліасі». Не називай імені й спільнокореневих слів.' },
    2: { name: 'Одне слово', icon: '☝️', rule: 'Лише одне слово на персонажа. Можна повторювати його скільки завгодно.' },
    3: { name: 'Крокодил', icon: '🐊', rule: 'Без слів і звуків: жести, міміка, пантоміма.' },
  };
  const TEAM_COLORS = ['#ff8a3d', '#4cc3ff', '#b98cff', '#5fdc8f', '#ff5d8f', '#ffd34d'];

  let peek = false;        // opponents tap to see the card
  let armed = null;        // two-tap confirmations in the menu
  let showAll = false;     // full character list on the final screen
  let timerTotal = 0;
  let lastCardId = null;

  // ---------- helpers ----------
  const g = () => api.g;
  const me = () => g() && g().me;
  const turn = () => g() && g().turn;
  const isExplainer = () => me() && turn() && turn().explainer === me().id;
  const canRun = () => api.isHost || isExplainer();
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');
  const team = idx => (g() && g().teams.find(t => t.idx === idx)) || null;
  const teamColor = idx => TEAM_COLORS[idx % TEAM_COLORS.length];
  const teamTag = idx => {
    const t = team(idx);
    return t ? `<span class="team-tag" style="--tc:${teamColor(idx)}">${t.emoji} ${esc(t.name)}</span>` : '';
  };
  const secs = ms => Math.round(ms / 1000);
  function fmt(ms) {
    const s = Math.max(0, Math.ceil(ms / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }
  function dur(ms) {
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s} с` : `${Math.floor(s / 60)} хв ${s % 60} с`;
  }
  function plural(n, one, few, many) {
    const a = n % 10, b = n % 100;
    return a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many;
  }

  // ---------- pictures from Wikipedia ----------
  // Looked up in the browser, so the host phone needs no internet; failures just mean no picture.
  const pics = {};
  const pending = {};
  function picUrl(name) {
    if (!g() || !g().images || !name) return null;
    const key = name.toLowerCase();
    if (key in pics) return pics[key];
    const cached = store.get('hat.pic.' + key);
    if (cached !== null) { pics[key] = cached || null; return pics[key]; }
    if (!pending[key]) {
      pending[key] = true;
      lookup(name).then(url => {
        pics[key] = url;
        store.set('hat.pic.' + key, url || '');
        api.render();
      }).catch(() => { pics[key] = null; });
    }
    return null;
  }
  async function lookup(name) {
    for (const lang of ['uk', 'en']) {
      const u = `https://${lang}.wikipedia.org/w/api.php?action=query&format=json&origin=*` +
        `&generator=search&gsrlimit=1&gsrsearch=${encodeURIComponent(name)}` +
        `&prop=pageimages&piprop=thumbnail&pithumbsize=480`;
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 6000);
      try {
        const r = await fetch(u, { signal: ctl.signal });
        const j = await r.json();
        const pages = j.query && j.query.pages;
        const p = pages && Object.values(pages)[0];
        if (p && p.thumbnail) return p.thumbnail.source;
      } finally { clearTimeout(timer); }
    }
    return null;
  }
  function pic(name, cls = 'pic') {
    const u = picUrl(name);
    return u ? `<img class="${cls}" src="${esc(u)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '';
  }

  // ---------- timer ----------
  function remainingMs() {
    const t = turn();
    if (!t || !t.deadline) return t ? t.ms : 0;
    return Math.max(0, t.deadline - api.now());
  }
  function timerHtml() {
    timerTotal = turn() ? turn().ms : 0;
    const ms = remainingMs();
    return `<div class="timer ${ms < 10000 ? 'low' : ''}" id="hatTimer">${fmt(ms)}</div>
      <div class="bar"><i id="hatBar" style="width:${timerTotal ? ms / timerTotal * 100 : 0}%"></i></div>`;
  }
  setInterval(() => {
    const t = document.getElementById('hatTimer'); if (!t || !g() || g().phase !== 'TURN') return;
    const ms = remainingMs();
    t.textContent = fmt(ms);
    t.classList.toggle('low', ms < 10000);
    const b = document.getElementById('hatBar'); if (b && timerTotal) b.style.width = (ms / timerTotal * 100) + '%';
  }, 200);

  // ---------- pieces ----------
  function roundBanner(r) {
    const R = ROUND[r]; if (!R) return '';
    return `<div class="round-banner"><div class="ri">${R.icon}</div>
      <div><div class="small muted">Раунд ${r} з ${g().rounds}</div><b>${R.name}</b><div class="small">${R.rule}</div></div></div>`;
  }

  function scoreTable(showRounds) {
    const s = g();
    const ts = [...s.teams].sort((a, b) => b.total - a.total);
    const upto = s.phase === 'FINAL' ? (s.stats ? s.stats.roundsPlayed : s.round) : s.round;
    return `<div class="card">${ts.map(t => `
      <div class="score">
        <span class="name">${teamTag(t.idx)} <span class="muted small">${t.members.map(nameOf).join(', ')}</span></span>
        ${showRounds ? t.score.slice(0, upto).map((n, i) => `<span class="rs" title="Раунд ${i + 1}">${n}</span>`).join('') : ''}
        <span class="pts">${t.total}</span>
      </div>`).join('')}</div>`;
  }

  function guessedList(list) {
    if (!list || !list.length) return '';
    return `<div class="chips">${list.map(c => `<span class="chip">${pic(c.name, 'pic-xs')}${esc(c.name)}</span>`).join('')}</div>`;
  }

  function lastTurnCard() {
    const l = g().lastTurn;
    if (!l) return '';
    return `<div class="card flat stack">
      <div class="small muted">Минулий хід${l.bonus ? ' (бонусний час)' : ''}</div>
      <div>${teamTag(l.team)} · ${nameOf(l.explainer)} — <b>+${l.guessed.length}</b>${l.skips ? ` <span class="muted small">· пропусків ${l.skips}</span>` : ''}</div>
      ${guessedList(l.guessed)}
    </div>`;
  }

  // ---------- views ----------
  function rulesCard() {
    return `<div class="card stack small">
      <div><b>Як грати.</b> Кожен кидає в шляпу персонажів — реальних чи вигаданих. Гравці діляться на команди.</div>
      <div>Пояснювач тягне персонажів по одному й за час ходу пояснює своїй команді якомога більше. Вгадані виходять із шляпи; раунд триває, поки шляпа не спорожніє.</div>
      <div>🗣️ Раунд 1 — словами · ☝️ Раунд 2 — одним словом · 🐊 Раунд 3 — без слів.</div>
      <div class="muted">Персонажі ті самі в кожному раунді. Якщо шляпа спорожніла до кінця ходу — залишок часу переходить цій команді на початок наступного раунду.</div>
    </div>`;
  }

  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    if (api.isHost) {
      const chips = (act, vals, cur, label) => vals.map(v =>
        `<button class="chip ${cur === v ? 'on' : ''}" data-act="${act}" data-v="${v}">${label(v)}</button>`).join('');
      h += `<div class="card stack">
        <b>Хто наповнює шляпу</b>
        <div class="seg">
          <button class="${s.autoPool ? '' : 'on'}" data-act="auto" data-v="0">✍️ Гравці</button>
          <button class="${s.autoPool ? 'on' : ''}" data-act="auto" data-v="1">🎲 Автоматично</button>
        </div>
        <b>Персонажів від кожного</b>
        <div class="chips">${chips('per', [3, 4, 5, 6, 8], s.perPlayer, v => v)}</div>
        <div class="muted small">У шляпі буде ${s.perPlayer * n} ${plural(s.perPlayer * n, 'персонаж', 'персонажі', 'персонажів')}.</div>
        <b>Час ходу</b>
        <div class="chips">${chips('seconds', [30, 45, 60, 90], s.turnSeconds, v => v + ' с')}</div>
        <label class="toggle"><input type="checkbox" data-act="images" ${s.images ? 'checked' : ''}>
          <span>Картинки персонажів з Вікіпедії <span class="muted small">(потрібен інтернет на телефонах гравців)</span></span></label>
      </div>`;
    } else {
      h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Хост налаштовує гру</span></div>`;
    }
    h += rulesCard();
    h += `<h2>Гравці · ${n}</h2><div class="card"><div class="chips">${s.players.map(p =>
      `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('')}</div></div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="collect" ${enough ? '' : 'disabled'}>${s.autoPool ? 'Наповнити шляпу й поділити на команди' : 'Наповнювати шляпу'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці (зараз ${n}).</div>`;
    }
    return h;
  }

  function collectView() {
    const s = g();
    let h = `<div class="center"><span class="tag">наповнюємо шляпу</span></div>`;
    if (me()) {
      const mine = s.myCards || [];
      const left = s.perPlayer - mine.length;
      h += `<h1>${left > 0 ? `Додай ще ${left}` : 'Готово! 🎩'}</h1>
        <p class="muted">${left > 0
          ? 'Реальні люди чи вигадані персонажі — головне, щоб їх знали всі за столом. Інші не бачать твій список.'
          : 'Чекаємо, поки інші доповнять свої списки. Ще можна когось прибрати й замінити.'}</p>`;
      if (left > 0) {
        h += `<div class="row"><input id="hatName" type="text" maxlength="40" placeholder="Напр. Шерлок Холмс" autocomplete="off" style="flex:1">
          <button data-act="add">Додати</button></div>`;
      }
      if (s.err) h += `<div class="notice">${esc(s.err)}</div>`;
      if (mine.length) {
        h += `<div class="card mine">${mine.map(c => `<div class="mine-row">${pic(c.name, 'pic-sm')}<span class="grow">${esc(c.name)}</span>
          <button class="mini ghost" data-act="remove" data-id="${c.id}">✕</button></div>`).join('')}</div>`;
      }
      if (left > 0) h += `<button class="soft" data-act="autofill">🎲 Доповнити випадковими (${left})</button>`;
    }
    const done = s.players.filter(p => p.n >= s.perPlayer).length;
    h += `<h2>Готові · ${done} з ${s.players.length}</h2>
      <div class="chips">${s.players.map(p => `<span class="chip ${p.n >= s.perPlayer ? 'ok' : ''}">${p.n >= s.perPlayer ? '✓ ' : ''}${esc(p.name)} <span class="muted small">${p.n}/${s.perPlayer}</span></span>`).join('')}</div>`;
    if (api.isHost) {
      const all = done === s.players.length;
      h += `<div class="row">
        ${all ? '' : `<button class="soft grow" data-act="autoAll">🎲 Доповнити за всіх</button>`}
        <button class="grow" data-act="toTeams" ${all ? '' : 'disabled'}>Ділимось на команди</button></div>`;
    }
    return h;
  }

  function teamsView() {
    const s = g();
    let h = `<div class="center"><span class="tag">команди</span></div>
      <p class="muted center">У шляпі ${s.totalCards} ${plural(s.totalCards, 'персонаж', 'персонажі', 'персонажів')}. Сідайте поруч зі своєю командою.</p>`;
    h += s.teams.map(t => `<div class="card team" style="--tc:${teamColor(t.idx)}">
      <div class="team-name">${t.emoji} ${esc(t.name)}</div>
      <div class="chips">${t.members.map(id => api.isHost
        ? `<button class="chip" data-act="move" data-pid="${id}">${nameOf(id)} ⇄</button>`
        : `<span class="chip">${nameOf(id)}${me() && me().id === id ? ' <span class="muted small">(ти)</span>' : ''}</span>`).join('') || '<span class="muted">порожньо</span>'}</div>
    </div>`).join('');
    if (api.isHost) {
      const counts = [];
      for (let i = 2; i <= s.maxTeams; i++) counts.push(i);
      h += `<div class="card stack"><b>Кількість команд</b>
        <div class="chips">${counts.map(n => `<button class="chip ${s.teams.length === n ? 'on' : ''}" data-act="teams" data-v="${n}">${n}</button>`).join('')}</div>
        <div class="muted small">Торкнись гравця, щоб перекинути його в іншу команду.</div></div>
        <div class="row"><button class="soft grow" data-act="shuffle">🔀 Перемішати</button>
        <button class="grow" data-act="start">Почати гру</button></div>`;
    } else {
      h += `<div class="muted center small">Хост почне гру, коли всі розсядуться.</div>`;
    }
    return h;
  }

  function whoLine() {
    const t = turn();
    return `<div class="who" style="--tc:${teamColor(t.team)}">
      <div>${teamTag(t.team)}</div>
      <div class="explainer">${isExplainer() ? 'Пояснюєш ти' : `Пояснює ${nameOf(t.explainer)}`}</div>
      ${t.bonus ? `<div class="small warn">⏱ бонусний час з минулого раунду: ${secs(t.ms)} с</div>` : ''}
    </div>`;
  }

  function myRoleLine() {
    const t = turn();
    if (!me() || isExplainer()) return '';
    if (me().team === t.team) return `<div class="card flat center">Ти в цій команді — <b>вгадуй!</b></div>`;
    return `<div class="card flat center muted small">Ви суперники: стежте, щоб не порушували правила раунду.</div>`;
  }

  function readyView() {
    const s = g();
    const t = turn();
    const ex = s.players.find(p => p.id === t.explainer);
    let h = roundBanner(s.round) + whoLine();
    if (isExplainer()) {
      h += `<button class="big go" data-act="begin">Почати хід · ${secs(t.ms)} с</button>
        <div class="muted small center">Тримай телефон так, щоб твоя команда не бачила екрана.</div>`;
    } else {
      h += myRoleLine();
      if (api.isHost && !(ex && ex.connected)) {
        h += `<button class="soft" data-act="begin">${nameOf(t.explainer)} не на зв'язку — почати хід за нього</button>`;
      }
    }
    h += `<div class="muted center small">🎩 У шляпі: ${s.poolLeft} з ${s.totalCards}</div>`;
    h += lastTurnCard();
    h += `<h2>Рахунок</h2>${scoreTable(s.round > 1)}`;
    return h;
  }

  function cardView(c, big) {
    return `<div class="hat-card ${big ? 'big' : ''} pop">${pic(c.name)}<div class="cname">${esc(c.name)}</div></div>`;
  }

  function turnView() {
    const s = g();
    const t = turn();
    let h = `<div class="row turn-head">${teamTag(t.team)}<span class="grow"></span><span class="tag">${ROUND[s.round].icon} ${ROUND[s.round].name}</span></div>
      ${timerHtml()}`;
    let bar = '';
    if (isExplainer()) {
      h += s.card ? cardView(s.card, true) : '';
      h += `<div class="row small muted"><span class="grow">Вгадано: <b>${t.guessed.length}</b> · у шляпі ${s.poolLeft}</span>
        ${t.guessed.length ? `<button class="mini ghost" data-act="undo">↶ Скасувати</button>` : ''}</div>`;
      bar = `<button class="soft skip" data-act="skip">Пропустити</button><button class="got" data-act="got">✓ Вгадали</button>`;
    } else {
      h += `<div class="center explainer">${nameOf(t.explainer)} пояснює</div>`;
      if (s.card) {
        h += peek ? `<div data-act="peek">${cardView(s.card, false)}</div>`
          : `<button class="soft big" data-act="peek">👀 Підглянути персонажа</button>`;
      }
      h += myRoleLine();
      h += `<div class="center"><b>Вгадано: ${t.guessed.length}</b></div>${guessedList(t.guessed)}`;
      if (api.isHost) {
        const ex = s.players.find(p => p.id === t.explainer);
        if (!(ex && ex.connected)) h += `<div class="muted small center">Пояснювач не на зв'язку — хід можна завершити в меню ☰.</div>`;
      }
    }
    return { html: h, bar };
  }

  function turnEndView() {
    const s = g();
    const t = turn();
    let h = `<div class="banner meh pop"><div style="font-size:40px">⏰</div><h1>Час вийшов!</h1></div>`;
    const ex = s.players.find(p => p.id === t.explainer);
    if (isExplainer() || (api.isHost && !(ex && ex.connected))) {
      h += s.card ? cardView(s.card, false) : '';
      h += `<h2 class="center">Останнього встигли вгадати?</h2>
        <div class="grid2"><button class="no" data-act="last" data-v="0">Ні</button><button class="yes" data-act="last" data-v="1">Так ✓</button></div>`;
    } else {
      h += `<div class="card flat center muted">${nameOf(t.explainer)} відмічає, чи вгадали останнього…</div>`;
    }
    h += `<div class="center"><b>${teamTag(t.team)} за хід: ${t.guessed.length}</b></div>${guessedList(t.guessed)}`;
    return h;
  }

  function roundHighlights(r) {
    const st = g().stats; if (!st) return '';
    const cs = st.cards.filter(c => c.rounds[r - 1] > 0).sort((a, b) => b.rounds[r - 1] - a.rounds[r - 1]);
    if (!cs.length) return '';
    const hard = cs[0], easy = cs[cs.length - 1];
    return `<div class="card stack small">
      <div>🧱 Найдовше в раунді: <b>${esc(hard.name)}</b> — ${dur(hard.rounds[r - 1])}</div>
      <div>🪶 Найшвидше: <b>${esc(easy.name)}</b> — ${dur(easy.rounds[r - 1])}</div>
    </div>`;
  }

  function roundEndView() {
    const s = g();
    let h = `<div class="banner good pop"><div style="font-size:40px">🎩</div><h1>Шляпа порожня!</h1>
      <div>Раунд ${s.round} · ${ROUND[s.round].name} — завершено</div></div>`;
    h += `<h2>Рахунок</h2>${scoreTable(s.round > 1)}`;
    if (s.carry) {
      h += `<div class="notice">⏱ ${teamTag(s.carry.team)} почне наступний раунд: ${nameOf(s.carry.explainer)} має ще ${secs(s.carry.ms)} с.</div>`;
    }
    h += roundHighlights(s.round);
    h += `<h2>Далі</h2>${roundBanner(s.round + 1)}`;
    if (api.isHost) h += `<button class="big" data-act="nextRound">Раунд ${s.round + 1}</button>`;
    else h += `<div class="muted center small">Хост запустить наступний раунд.</div>`;
    return h;
  }

  function finalView() {
    const s = g();
    const st = s.stats || { cards: [], players: [] };
    const ts = [...s.teams].sort((a, b) => b.total - a.total);
    const top = ts.length ? ts[0].total : 0;
    const winners = ts.filter(t => t.total === top);
    const mine = me() && winners.some(t => t.idx === me().team);
    let h = `<div class="banner ${mine ? 'good' : 'meh'} pop"><div style="font-size:44px">🏆</div>
      <h1>${winners.length > 1 ? 'Нічия!' : `Перемогли ${esc(winners[0] ? winners[0].emoji + ' ' + winners[0].name : '')}`}</h1>
      <div>${top} ${plural(top, 'персонаж', 'персонажі', 'персонажів')}</div></div>`;
    h += `<h2>Рахунок по раундах</h2>${scoreTable(true)}`;

    const shown = st.cards.filter(c => c.ms > 0);
    if (shown.length) {
      const hardest = shown.slice(0, 3);
      const easiest = [...shown].sort((a, b) => a.ms - b.ms).slice(0, 3);
      const row = c => `<div class="stat-card">${pic(c.name, 'pic-sm')}
        <div class="grow"><b>${esc(c.name)}</b><div class="muted small">${dur(c.ms)} на екрані${c.skips ? ` · пропусків ${c.skips}` : ''}${c.author ? ` · від ${nameOf(c.author)}` : ''}</div></div></div>`;
      h += `<h2>🧱 Найважчі персонажі</h2><div class="card">${hardest.map(row).join('')}</div>`;
      h += `<h2>🪶 Найлегші</h2><div class="card">${easiest.map(row).join('')}</div>`;
    }

    // nominations
    const ps = st.players.filter(p => s.players.some(x => x.id === p.id));
    const best = (arr, f) => arr.length ? arr.reduce((a, b) => (f(b) > f(a) ? b : a)) : null;
    const noms = [];
    const bestEx = best(ps.filter(p => p.explained > 0), p => p.explained);
    if (bestEx) noms.push(['🎤', 'Найкращий пояснювач', bestEx.id, `${bestEx.explained} ${plural(bestEx.explained, 'персонаж', 'персонажі', 'персонажів')}`]);
    const fast = best(ps.filter(p => p.explained >= 3), p => -p.ms / p.explained);
    if (fast) noms.push(['⚡', 'Найшвидший', fast.id, `${(fast.ms / fast.explained / 1000).toFixed(1)} с на персонажа`]);
    const hot = best(ps, p => p.bestTurn);
    if (hot && hot.bestTurn) noms.push(['🔥', 'Найкращий хід', hot.id, `${hot.bestTurn} за один хід`]);
    const sly = best(ps.filter(p => p.authoredMs != null && p.authored > 0), p => p.authoredMs);
    if (sly && !s.autoPool) noms.push(['🦊', 'Найхитріший автор', sly.id, `персонажів вгадували в середньому ${dur(sly.authoredMs)}`]);
    const sk = best(ps, p => p.skips);
    if (sk && sk.skips >= 2) noms.push(['🙈', 'Король пропусків', sk.id, `${sk.skips} ${plural(sk.skips, 'пропуск', 'пропуски', 'пропусків')}`]);
    if (noms.length) {
      h += `<h2>Номінації</h2><div class="card">${noms.map(([e, t, id, d]) =>
        `<div class="stat-card"><span class="nom">${e}</span><div class="grow"><div class="muted small">${t}</div><b>${nameOf(id)}</b> <span class="muted small">· ${d}</span></div></div>`).join('')}</div>`;
    }

    // individual
    h += `<h2>Особиста статистика</h2><div class="card">${ps.sort((a, b) => b.explained - a.explained).map(p => {
      const avg = p.explained ? (p.ms / p.explained / 1000).toFixed(1) + ' с' : '—';
      const pl = s.players.find(x => x.id === p.id);
      return `<div class="pstat ${me() && me().id === p.id ? 'mine' : ''}">
        <div class="row"><b class="grow">${nameOf(p.id)}</b>${pl && pl.team != null ? teamTag(pl.team) : ''}</div>
        <div class="kv"><span><b>${p.explained}</b> пояснив(ла)</span><span><b>${avg}</b> на персонажа</span>
          <span><b>${p.bestTurn}</b> за найкращий хід</span><span><b>${p.skips}</b> ${plural(p.skips, 'пропуск', 'пропуски', 'пропусків')}</span>
          ${p.authored && !s.autoPool ? `<span>свої персонажі: <b>${dur(p.authoredMs || 0)}</b> у середньому</span>` : ''}</div>
      </div>`;
    }).join('')}</div>`;

    if (st.cards.length) {
      h += showAll
        ? `<h2>Усі персонажі</h2><div class="card">${st.cards.map(c => `<div class="all-row"><span class="grow">${esc(c.name)}</span>
            <span class="muted small">${c.rounds.map(ms => secs(ms) + 'с').join(' · ')}</span><b>${dur(c.ms)}</b></div>`).join('')}</div>`
        : `<button class="ghost" data-act="showAll">Усі персонажі за складністю</button>`;
    }
    if (api.isHost) h += `<button class="big" data-act="newGame">Нова гра</button>`;
    return h;
  }

  PG.register('hat', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let out;
      switch (s.phase) {
        case 'LOBBY': out = lobbyView(); break;
        case 'COLLECT': out = collectView(); break;
        case 'TEAMS': out = teamsView(); break;
        case 'READY': out = readyView(); break;
        case 'TURN': out = turnView(); break;
        case 'TURN_END': out = turnEndView(); break;
        case 'ROUND_END': out = roundEndView(); break;
        case 'FINAL': out = finalView(); break;
        default: out = '';
      }
      if (typeof out === 'string') return `<div class="hat stack">${out}</div>`;
      return { html: `<div class="hat stack">${out.html}</div>`, bar: out.bar ? `<div class="hat-bar">${out.bar}</div>` : '' };
    },

    extra(a) {
      api = a;
      const s = g();
      if (!s || !['READY', 'TURN', 'TURN_END'].includes(s.phase)) return '';
      return `🎩 ${s.poolLeft}`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      const cid = next.card ? next.card.id : null;
      if (cid !== lastCardId) { peek = false; lastCardId = cid; }
      if (!prev || prev.phase !== next.phase) {
        armed = null;
        if (next.phase !== 'FINAL') showAll = false;
        if (prev) {
          const mineTurn = next.turn && next.me && next.turn.explainer === next.me.id;
          if (next.phase === 'TURN_END') a.vibrate([200, 100, 200]);
          else if (next.phase === 'READY' && mineTurn) a.vibrate([80, 60, 80]);
          else a.vibrate(60);
        }
      }
    },

    menu(a) {
      api = a;
      const s = g();
      if (!s) return '';
      const conf = (act, label) => armed === act
        ? `<button class="danger" data-act="${act}">Точно? ${label}</button>`
        : `<button class="soft" data-act="arm" data-v="${act}">${label}</button>`;
      let h = '';
      if (['READY', 'TURN', 'TURN_END'].includes(s.phase)) h += conf('endTurn', s.phase === 'READY' ? 'Пропустити цей хід' : 'Завершити хід');
      if (['READY', 'TURN', 'TURN_END', 'ROUND_END'].includes(s.phase)) h += conf('finish', 'Завершити гру й показати статистику');
      if (s.phase !== 'LOBBY') h += conf('newGame', 'Нова гра (очистити шляпу)');
      return h;
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const act = el.dataset.act;
      switch (act) {
        case 'auto': return a.send({ t: 'settings', auto: el.dataset.v === '1' });
        case 'per': return a.send({ t: 'settings', perPlayer: +el.dataset.v });
        case 'seconds': return a.send({ t: 'settings', seconds: +el.dataset.v });
        case 'images': return a.send({ t: 'settings', images: el.checked });
        case 'collect': return a.send({ t: 'collect' });
        case 'add': return addName(a);
        case 'remove': return a.send({ t: 'remove', id: +el.dataset.id });
        case 'autofill': return a.send({ t: 'autofill' });
        case 'autoAll': return a.send({ t: 'autoAll' });
        case 'toTeams': return a.send({ t: 'toTeams' });
        case 'teams': return a.send({ t: 'teams', n: +el.dataset.v });
        case 'shuffle': return a.send({ t: 'shuffle' });
        case 'move': return a.send({ t: 'move', pid: el.dataset.pid });
        case 'start': return a.send({ t: 'start' });
        case 'begin': return a.send({ t: 'begin' });
        case 'got': a.vibrate(30); return a.send({ t: 'got' });
        case 'skip': return a.send({ t: 'skip' });
        case 'undo': return a.send({ t: 'undo' });
        case 'last': return a.send({ t: 'last', yes: el.dataset.v === '1' });
        case 'peek': peek = !peek; return a.render();
        case 'nextRound': return a.send({ t: 'nextRound' });
        case 'showAll': showAll = true; return a.render();
        case 'arm': armed = el.dataset.v; return a.render();
        case 'endTurn': case 'finish': case 'newGame': armed = null; a.send({ t: act }); return a.render();
      }
    },

    key(e, a) {
      api = a;
      if (e.key === 'Enter' && e.target.id === 'hatName') addName(a);
    },
  });

  function addName(a) {
    const input = document.getElementById('hatName');
    const name = (input && input.value || '').trim();
    if (!name) { input && input.focus(); return; }
    input.value = '';
    a.send({ t: 'add', name });
    input.focus();
  }
})();
