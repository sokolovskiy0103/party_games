// «Скажи інакше» UI: a late-night TV game show. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;
  let api = PG.api;

  const TEAM_COLORS = ['#ff8a3d', '#4cc3ff', '#b98cff', '#5fdc8f', '#ff5d8f', '#ffd34d'];
  const BATTLE_COLORS = ['#4cc3ff', '#ff5d8f'];
  const MARK = {
    GOT: { icon: '✓', cls: 'c-good', label: 'вгадали' },
    SKIP: { icon: '⏭', cls: 'c-warn', label: 'пропуск' },
    FOUL: { icon: '🚨', cls: 'c-bad', label: 'табу' },
  };
  const NEXT_MARK = { GOT: 'SKIP', SKIP: 'GOT' };
  const NEXT_MARK_TABOO = { GOT: 'SKIP', SKIP: 'FOUL', FOUL: 'GOT' };

  // ---------- helpers ----------
  const g = () => api.g;
  const me = () => g() && g().me;
  const turn = () => g() && g().turn;
  const isExplainer = () => !!(me() && turn() && turn().explainer === me().id);
  const canRun = () => api.isHost || isExplainer();
  const myTeam = () => (me() ? me().team : null);
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');
  const team = idx => (g() && g().teams.find(t => t.idx === idx)) || null;
  const teamColor = idx => (g() && g().battle ? BATTLE_COLORS : TEAM_COLORS)[idx % TEAM_COLORS.length];
  const teamTag = idx => {
    const t = team(idx);
    return t ? `<span class="team-tag" style="--tc:${teamColor(idx)}">${t.emoji} ${esc(t.name)}</span>` : '';
  };
  const explainerOnline = () => { const p = g().players.find(x => x.id === turn().explainer); return !!(p && p.connected); };
  const signed = n => (n > 0 ? '+' + n : n < 0 ? '−' + Math.abs(n) : '0');
  function plural(n, one, few, many) {
    const a = Math.abs(n) % 10, b = Math.abs(n) % 100;
    return a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many;
  }
  const points = n => `${n} ${plural(n, 'очко', 'очки', 'очок')}`;
  const words = n => `${n} ${plural(n, 'слово', 'слова', 'слів')}`;

  // ---------- pieces ----------
  function rulesCard() {
    const s = g();
    if (s.kids) return `<div class="card stack small">
      <div>🧸 <b>Дитячі картки.</b> На екрані — велика картинка зі словом. Подивіться, назвіть, що це, або вгадайте. Без команд, без таймера — можна грати й одному.</div>
      <div>✓ вгадали — рахується · ⏭ інша — просто наступна картка.</div>
    </div>`;
    return `<div class="card stack small">
      <div><b>Як грати.</b> Пояснювач бачить слово й пояснює його своїй команді, не називаючи самого слова й спільнокореневих. За хід — якомога більше слів.</div>
      <div>✓ вгадали — +1${s.skipPenalty ? ' · ⏭ пропуск — −1' : ' · ⏭ пропуск — 0'}${s.taboo ? ' · 🚨 сказав заборонене — −1' : ''}</div>
      ${s.taboo ? '<div>🚫 <b>Табу:</b> на картці ще 5 заборонених слів. Суперники бачать картку й тиснуть «Табу!», якщо почули заборонене.</div>' : ''}
      ${s.lastForAll ? '<div>⏰ Останнє слово, коли скінчився час, може вгадати будь-яка команда.</div>' : ''}
      ${s.battle ? '<div>⚔️ <b>Хлопці проти дівчат:</b> хлопці пояснюють «жіночі» слова, дівчата — «чоловічі».</div>' : ''}
      <div class="muted">Виграє команда, що першою набрала ${s.goal} — коли всі команди зіграли порівну ходів.</div>
    </div>`;
  }

  /** Teams with a bar towards the goal; the team on air glows. */
  function scoreboard() {
    const s = g();
    const ts = [...s.teams].sort((a, b) => b.score - a.score);
    const onAir = turn() ? turn().team : -1;
    return `<div class="card scores">${ts.map(t => `
      <div class="srow ${t.idx === onAir ? 'air' : ''}" data-k="team${t.idx}" style="--tc:${teamColor(t.idx)}">
        <div class="row">${teamTag(t.idx)}<span class="grow muted small">${t.members.map(nameOf).join(', ')}</span>
          <b class="pts" data-k="s${t.score}">${t.score}</b></div>
        <div class="goal"><i style="width:${Math.max(0, Math.min(100, t.score / s.goal * 100))}%"></i></div>
      </div>`).join('')}
      <div class="muted small center">🏁 Граємо до ${s.goal}</div></div>`;
  }

  function wordChips(list) {
    if (!list || !list.length) return '';
    return `<div class="chips said">${list.map((x, i) => {
      const m = MARK[x.mark];
      return `<span class="chip ${x.mark || 'open'}" data-k="w${i}">${m ? `<span class="${m.cls}">${m.icon}</span>` : '⏰'} ${esc(x.w)}</span>`;
    }).join('')}</div>`;
  }

  function lastTurnCard() {
    const l = g().lastTurn;
    if (!l) return '';
    const last = l.lastBy >= 0 ? `<div class="small">⏰ Останнє слово забрали ${teamTag(l.lastBy)}</div>` : '';
    return `<div class="card flat stack recap" data-k="recap">
      <div class="small muted">Минулий хід</div>
      <div class="row">${teamTag(l.team)}<span class="grow">${nameOf(l.explainer)}</span><b class="delta ${l.delta > 0 ? 'c-good' : l.delta < 0 ? 'c-bad' : ''}">${signed(l.delta)}</b></div>
      ${wordChips(l.words)}${last}
    </div>`;
  }

  /** Join buttons for a player without a team (the battle mode, or someone who came in late). */
  function joinCard() {
    const s = g();
    if (!me() || myTeam() != null || !s.teams.length) return '';
    return `<div class="card hl stack center"><b>Обери свою команду</b>
      <div class="grid2">${s.teams.map(t => `<button class="tint" style="--tc:${teamColor(t.idx)}" data-act="join" data-v="${t.idx}">${t.emoji} ${esc(t.name)}</button>`).join('')}</div></div>`;
  }

  /** The cue card: the word on top, the forbidden words on a red strip. Each new word flips in. */
  function cueCard(c, opts = {}) {
    const taboo = c.taboo && c.taboo.length
      ? `<div class="forbid"><div class="flabel">Не можна казати</div>${c.taboo.map((t, i) => `<div class="fw" style="--d:${120 + i * 50}ms">${esc(t)}</div>`).join('')}</div>` : '';
    return `<div class="cue ${opts.hot ? 'hot' : ''} ${c.taboo && c.taboo.length ? 'has-taboo' : ''} ${c.e ? 'has-pic' : ''}" data-k="cue-${esc(c.w)}">
      ${opts.watching ? '<div class="watch">👀 Стеж, щоб не прозвучало заборонене</div>' : ''}
      ${c.e ? `<div class="ce">${c.e}</div>` : ''}<div class="cw">${esc(c.w)}</div>${taboo}</div>`;
  }

  function timerHtml(big) {
    const t = turn();
    const o = { deadline: t.deadline, ms: t.ms, total: t.ms, low: 10000, cls: big ? 'big' : '' };
    return `<div class="clock">${ui.timer(o)}${ui.timerBar(o)}</div>`;
  }

  /** Running tally of the turn; each counter bumps when it changes. */
  function tally() {
    const t = turn();
    const s = g();
    const cell = (k, icon, n, cls) => `<div class="tc ${cls}"><span>${icon}</span><b data-k="${k}${n}">${n}</b></div>`;
    return `<div class="tally">${cell('g', '✓', t.got, 'c-good')}${cell('s', '⏭', t.skips, 'c-warn')}${s.taboo ? cell('f', '🚨', t.fouls, 'c-bad') : ''}
      <div class="tc sum"><span>разом</span><b data-k="d${t.delta}">${signed(t.delta)}</b></div></div>`;
  }

  /** The «on air» plate: whose team, who explains. */
  function onAir(big) {
    const t = turn();
    const who = isExplainer() ? 'Твій вихід!' : `Пояснює ${nameOf(t.explainer)}`;
    return `<div class="onair tint ${big ? 'big' : ''}" style="--tc:${teamColor(t.team)}" data-k="air${t.team}${t.explainer}">
      <div class="lamp">● в ефірі</div>${teamTag(t.team)}<div class="who">${who}</div></div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = `<div class="show-title" data-k="title"><div class="q">“</div><div><b>Скажи інакше</b><div class="small muted">шоу, де слово не можна казати</div></div><div class="q">”</div></div>`;
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    if (api.isHost) {
      const chips = (act, vals, cur, label) => vals.map(v =>
        `<button class="chip ${cur === v ? 'on' : ''}" data-act="${act}" data-v="${v}">${label(v)}</button>`).join('');
      const total = (s.themeList || []).filter(t => s.themes.indexOf(t.id) >= 0).reduce((a, t) => a + t.n, 0);
      h += `<div class="card stack">
        <b>Режим</b>
        <div class="seg">
          <button class="${!s.taboo && !s.kids ? 'on' : ''}" data-act="mode" data-v="classic">🗣️ Класика</button>
          <button class="${s.taboo ? 'on' : ''}" data-act="mode" data-v="taboo">🚫 Табу</button>
          <button class="${s.kids ? 'on' : ''}" data-act="mode" data-v="kids">🧸 Дитячий</button>
        </div>
        <div class="muted small">${s.kids ? 'Картки з великою картинкою для дітей, які ще не вміють читати. Без команд і таймера, грати можна й одному.' : s.taboo ? 'На картці 5 заборонених слів. Суперники бачать картку й ловлять порушення.' : 'Пояснюй як завгодно, тільки не називай слово й спільнокореневі.'}</div>
        ${s.kids ? '' : `<label class="toggle"><input type="checkbox" class="switch" data-act="set" data-k="battle" ${s.battle ? 'checked' : ''}>
          <span>⚔️ Хлопці проти дівчат <span class="muted small">(хлопці пояснюють «жіночі» слова, дівчата — «чоловічі»)</span></span></label>`}
        ${s.kids ? `<div class="muted small">🧸 ${words(s.kidsWords)} з картинками: тварини, їжа, іграшки, транспорт, природа.</div>` : s.battle ? `<div class="muted small">По ${s.battleWords} слів на кожну сторону. Теми нижче не діють.</div>` : `
        <b>Теми</b>
        <div class="chips">${(s.themeList || []).map(t => `<button class="chip ${s.themes.indexOf(t.id) >= 0 ? 'on' : ''}" data-act="theme" data-v="${t.id}" ${t.n ? '' : 'disabled'}>${t.emoji} ${esc(t.title)} <span class="small">${t.n}</span></button>`).join('')}</div>
        <div class="muted small">Обрано ${words(total)}.</div>`}
        ${s.kids ? '' : `<b>Час ходу</b>
        <div class="chips">${chips('seconds', [30, 45, 60, 90, 120], s.seconds, v => v + ' с')}</div>
        <b>Граємо до</b>
        <div class="chips">${chips('goal', [20, 30, 40, 60, 80, 100], s.goal, v => v)}</div>
        <label class="toggle"><input type="checkbox" class="switch" data-act="set" data-k="skipPenalty" ${s.skipPenalty ? 'checked' : ''}>
          <span>Пропуск — мінус очко</span></label>
        <label class="toggle"><input type="checkbox" class="switch" data-act="set" data-k="lastForAll" ${s.lastForAll ? 'checked' : ''}>
          <span>Останнє слово — для всіх <span class="muted small">(коли скінчився час, вгадати може будь-хто)</span></span></label>`}
      </div>`;
    } else {
      h += ui.wait('Хост налаштовує гру', '👑');
    }
    h += rulesCard();
    h += `<h2>Гравці · ${n}</h2><div class="card"><div class="chips">${s.players.map(p =>
      `<span class="chip" data-k="${esc(p.id)}"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('')}</div></div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="toTeams" ${enough ? '' : 'disabled'}>${s.kids ? '▶ Грати' : 'Ділимось на команди'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці (зараз ${n}).</div>`;
    }
    return h;
  }

  function teamsView() {
    const s = g();
    let h = `<div class="center"><span class="tag accent">команди</span></div>`;
    h += `<p class="muted center">${s.battle ? 'Кожен обирає свою сторону.' : 'Можна перейти в іншу команду, торкнувшись «Сюди».'} У кожній команді — щонайменше двоє.</p>`;
    h += s.teams.map(t => {
      const mine = myTeam() === t.idx;
      return `<div class="card team ${mine ? 'mine' : ''}" style="--tc:${teamColor(t.idx)}" data-k="t${t.idx}">
        <div class="row"><b class="tname grow">${t.emoji} ${esc(t.name)}</b>
          ${me() && !mine ? `<button class="mini soft" data-act="join" data-v="${t.idx}">Сюди</button>` : ''}</div>
        <div class="chips">${t.members.map(id => api.isHost
          ? `<button class="chip" data-act="move" data-pid="${id}" data-k="${id}">${nameOf(id)} ⇄</button>`
          : `<span class="chip" data-k="${id}">${nameOf(id)}${me() && me().id === id ? ' <span class="muted small">(ти)</span>' : ''}</span>`).join('') || '<span class="muted">порожньо</span>'}</div>
      </div>`;
    }).join('');
    const free = s.players.filter(p => p.team == null);
    if (free.length) h += `<div class="card flat small">Ще без команди: ${free.map(p => esc(p.name)).join(', ')}</div>`;
    if (api.isHost) {
      if (!s.battle) {
        const nums = [];
        for (let i = 2; i <= s.maxTeams; i++) nums.push(i);
        h += `<div class="card stack"><b>Кількість команд</b>
          <div class="chips">${nums.map(n => `<button class="chip ${s.teams.length === n ? 'on' : ''}" data-act="teams" data-v="${n}">${n}</button>`).join('')}</div>
          <div class="muted small">Торкнись гравця, щоб перекинути його в іншу команду.</div></div>
          <button class="soft" data-act="shuffle">🔀 Перемішати</button>`;
      }
      h += `<button class="big" data-act="start" ${s.teamsReady ? '' : 'disabled'}>Почати гру</button>`;
    } else {
      h += ui.wait('Хост почне гру, коли всі розсядуться', '👑');
    }
    return h;
  }

  function roleLine() {
    const t = turn();
    if (isExplainer() || myTeam() == null) return '';
    if (myTeam() === t.team) return `<div class="card flat center role">Ти в цій команді — <b>вгадуй!</b></div>`;
    if (g().taboo) return `<div class="card flat center role">Ти бачитимеш картку — <b>лови заборонені слова</b> 🚨</div>`;
    if (g().lastForAll) return `<div class="card flat center role">Слухай уважно: <b>останнє слово</b> може дістатися вам</div>`;
    return '';
  }

  function readyView() {
    const s = g();
    let h = joinCard();
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += onAir(true) + roleLine();
    if (isExplainer()) {
      h += `<button class="big go" data-act="begin">Почати хід · ${s.seconds} с</button>
        <div class="muted small center">Тримай телефон так, щоб твоя команда не бачила екрана.</div>`;
    } else if (api.isHost && !explainerOnline()) {
      h += `<div class="muted small center">${nameOf(turn().explainer)} не на зв'язку — хід можна пропустити в меню ☰.</div>`;
    }
    h += lastTurnCard();
    h += `<h2>Рахунок</h2>${scoreboard()}`;
    return h;
  }

  function turnView() {
    const s = g();
    const t = turn();
    let h = joinCard() + `<div class="center">${teamTag(t.team)}</div>${timerHtml(false)}`;
    let bar = '';
    if (isExplainer()) {
      if (s.card) h += cueCard(s.card);
      h += tally();
      bar = `<div class="words-bar"><button class="soft skip" data-act="skip">⏭ Пропуск</button>` +
        (s.taboo ? `<button class="bad oops" data-act="foul" title="Я сказав(ла) заборонене">🚨</button>` : '') +
        `<button class="good got" data-act="got">✓ Вгадали</button></div>`;
    } else if (s.card) {
      // the other teams in the taboo mode police the explainer
      h += cueCard(s.card, { watching: true }) + tally();
      bar = `<div class="words-bar"><button class="buzzer" data-act="foul"><span>🚨 Табу!</span><small>прозвучало заборонене</small></button></div>`;
    } else {
      h += `<div class="listen" data-k="listen"><div class="ear">${myTeam() === t.team ? '🧠' : '👂'}</div>
        <div class="headline">${myTeam() === t.team ? 'Вгадуй!' : `${nameOf(t.explainer)} пояснює`}</div></div>`;
      h += tally() + wordChips(t.words);
    }
    return { html: h, bar };
  }

  /** Kids mode: one big picture for everybody; whoever holds a phone flips it. */
  function cardsView() {
    const c = g().cards;
    if (!c) return '';
    const h = `<div class="center"><span class="tag accent">🧸 вгадано ${c.got} · карток ${c.seen}</span></div>${cueCard(c)}`;
    const bar = `<div class="words-bar"><button class="soft skip" data-act="knext">⏭ Інша</button>` +
      `<button class="good got" data-act="kgot">✓ Вгадали</button></div>`;
    return { html: h, bar };
  }

  function lastView() {
    const s = g();
    const t = turn();
    let h = `<div class="banner hotline"><span class="emoji">⏰</span><h1>Час вийшов!</h1>
      <div>${s.lastForAll ? 'Останнє слово — хто перший вгадає?' : 'Доведи останнє слово до кінця'}</div></div>`;
    if (s.card) h += cueCard(s.card, { hot: true, watching: !isExplainer() });
    if (isExplainer()) {
      h += `<h2 class="center">Хто вгадав?</h2>`;
      if (s.lastForAll) {
        h += `<div class="grid2">${s.teams.filter(x => x.members.length).map(x =>
          `<button class="tint" style="--tc:${teamColor(x.idx)}" data-act="last" data-v="${x.idx}">${x.emoji} ${esc(x.name)}</button>`).join('')}
          <button class="soft" data-act="last" data-v="-1">Ніхто</button></div>`;
      } else {
        h += `<div class="grid2"><button class="bad" data-act="last" data-v="-1">Ні</button>
          <button class="good" data-act="last" data-v="${t.team}">Вгадали ✓</button></div>`;
      }
    } else {
      h += ui.wait(`${nameOf(t.explainer)} пояснює останнє слово`, nameOf(t.explainer));
    }
    return h;
  }

  function reviewView() {
    const s = g();
    const t = turn();
    const edit = canRun();
    let h = `<div class="center">${teamTag(t.team)} · ${nameOf(t.explainer)}</div>`;
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="result ${t.delta > 0 ? 'up' : t.delta < 0 ? 'down' : ''}"><b data-k="d${t.delta}">${signed(t.delta)}</b><span>${plural(Math.abs(t.delta), 'очко', 'очки', 'очок')} за хід</span></div>`;
    h += `<h2>Слова ходу</h2>`;
    if (edit && t.words.length) h += `<div class="muted small">Торкнись слова, щоб виправити позначку.</div>`;
    h += `<div class="card wlist">${t.words.map((x, i) => {
      const m = MARK[x.mark];
      return `<div class="li wrow ${x.mark} ${edit ? 'edit' : ''}" data-k="r${i}" ${edit ? `data-act="mark" data-i="${i}" data-m="${x.mark}"` : ''}>
        <span class="name">${esc(x.w)}</span><span class="mk ${m.cls}">${m.icon} ${m.label}</span></div>`;
    }).join('') || '<div class="muted center small">Жодного слова</div>'}</div>`;
    if (t.last) {
      h += `<h2>⏰ Останнє слово: <span class="c-accent">${esc(t.last)}</span></h2>`;
      const opts = s.lastForAll ? s.teams.filter(x => x.members.length).map(x => x.idx) : [t.team];
      h += `<div class="chips">${opts.map(i => edit
        ? `<button class="chip ${t.lastBy === i ? 'on' : ''}" data-act="lastBy" data-v="${i}">${team(i).emoji} ${esc(team(i).name)}</button>`
        : (t.lastBy === i ? `<span class="chip on">${team(i).emoji} ${esc(team(i).name)}</span>` : '')).join('')}
        ${edit ? `<button class="chip ${t.lastBy === -1 ? 'on' : ''}" data-act="lastBy" data-v="-1">Ніхто</button>`
          : (t.lastBy === -1 ? '<span class="chip">Ніхто</span>' : '')}</div>`;
    }
    if (edit) h += `<button class="big" data-act="confirm">Зарахувати</button>`;
    else h += ui.wait(`${nameOf(t.explainer)} перевіряє слова`, nameOf(t.explainer));
    h += `<h2>Рахунок до ходу</h2>${scoreboard()}`;
    return h;
  }

  function finalView() {
    const s = g();
    const w = s.winner != null ? team(s.winner) : null;
    const mine = w && myTeam() === w.idx;
    let h = `<div class="banner ${w ? 'good' : ''} finale ${w ? 'tint' : ''}" ${w ? `style="--tc:${teamColor(w.idx)}"` : ''}><span class="emoji">🏆</span>
      <h1>${w ? `Перемогли ${w.emoji} ${esc(w.name)}` : 'Нічия!'}</h1>
      ${w ? `<div>${mine ? 'Це ви! ' : ''}${points(w.score)}</div>` : ''}</div>`;
    h += `<h2>Рахунок</h2>${scoreboard()}`;
    h += mvpCard();
    h += lastTurnCard();
    if (api.isHost) h += `<button class="big" data-act="newGame">Нова гра</button>`;
    else h += `<div class="muted center small">Хост може почати нову гру.</div>`;
    return h;
  }

  function mvpCard() {
    const ps = g().players.filter(p => p.explained > 0).sort((a, b) => b.explained - a.explained);
    if (!ps.length) return '';
    return `<h2>Хто скільки пояснив</h2><div class="card">${ps.map((p, i) => `<div class="li" data-k="${esc(p.id)}">
      <span class="rank">${i === 0 ? '🎤' : i + 1}</span><span class="name">${esc(p.name)}</span>${p.team != null ? teamTag(p.team) : ''}<span class="pts">${p.explained}</span></div>`).join('')}</div>`;
  }

  // ---------- TV ----------
  // The room's shared screen: never the card (the explaining team would see it), only who is on air, the clock,
  // the tally and the words already said, next to the scoreboard.
  function tvSplit(main) {
    return `<div class="tv-split"><div class="stack">${main}</div><div class="stack"><h2>Рахунок</h2>${scoreboard()}</div></div>`;
  }

  function tvCards() {
    const c = g().cards;
    return c ? `<div class="center"><span class="tag accent">🧸 вгадано ${c.got} · карток ${c.seen}</span></div>${cueCard(c)}` : '';
  }

  function tvTurn() {
    const s = g();
    const t = turn();
    if (s.phase === 'READY') {
      return tvSplit(onAir(true) + `<div class="card flat center muted">Готується · ${s.seconds} с на хід${s.taboo ? ' · режим «Табу»' : ''}</div>` + lastTurnCard());
    }
    if (s.phase === 'LAST') {
      return tvSplit(onAir(false) + `<div class="banner hotline"><span class="emoji">⏰</span><h1>Час вийшов!</h1>
        <div>${s.lastForAll ? 'Останнє слово — хто перший?' : 'Останнє слово'}</div></div>` + tally() + wordChips(t.words));
    }
    return tvSplit(onAir(false) + timerHtml(true) + tally() + wordChips(t.words));
  }

  function tvReview() {
    const t = turn();
    const main = onAir(false) + `<div class="result ${t.delta > 0 ? 'up' : t.delta < 0 ? 'down' : ''}"><b data-k="d${t.delta}">${signed(t.delta)}</b><span>за хід</span></div>`
      + wordChips(t.words.concat(t.last ? [{ w: t.last, mark: null }] : []))
      + (t.last && t.lastBy >= 0 ? `<div class="center">⏰ Останнє слово — ${teamTag(t.lastBy)}</div>` : '');
    return tvSplit(main);
  }

  function tvFinal() {
    const s = g();
    const w = s.winner != null ? team(s.winner) : null;
    const main = `<div class="banner ${w ? 'good tint' : ''} finale" ${w ? `style="--tc:${teamColor(w.idx)}"` : ''}><span class="emoji">🏆</span>
      <h1>${w ? `Перемогли ${w.emoji} ${esc(w.name)}` : 'Нічия!'}</h1>${w ? `<div>${points(w.score)}</div>` : ''}</div>` + mvpCard();
    return tvSplit(main);
  }

  // ---------- module ----------
  function view() {
    switch (g().phase) {
      case 'LOBBY': return lobbyView();
      case 'TEAMS': return teamsView();
      case 'READY': return readyView();
      case 'TURN': return turnView();
      case 'LAST': return lastView();
      case 'REVIEW': return reviewView();
      case 'FINAL': return finalView();
      case 'CARDS': return cardsView();
    }
    return '';
  }

  PG.register('words', {
    // the explainer has to be heard; people argue over the review
    music: { TEAMS: 'low', READY: 'low', TURN: 'off', LAST: 'off', REVIEW: 'low', CARDS: 'low' },
    musicLoop: 68.5714,  // seconds of music.mp3 that loop: a whole number of bars (32 bars at 112 BPM)
    bg: 'loop',  // games/words/bg.webm|mp4 (phone), bg-tv.* (TV); bg.webp is the poster
    sfx: ['bleep'],  // own effect: the TV censor bleep for a forbidden word (sfx/bleep.mp3)

    render(a) {
      api = a;
      if (!g()) return '';
      const out = view();
      const cls = `words stack is-${g().phase.toLowerCase()}`;
      if (typeof out === 'string') return `<div class="${cls}">${out}</div>`;
      return { html: `<div class="${cls}">${out.html}</div>`, bar: out.bar };
    },

    board(a) {
      api = a;
      const s = g();
      if (!s) return null;
      let out = null;
      switch (s.phase) {
        case 'READY': case 'TURN': case 'LAST': out = tvTurn(); break;
        case 'REVIEW': out = tvReview(); break;
        case 'FINAL': out = tvFinal(); break;
        case 'CARDS': out = tvCards(); break;
      }
      return out && `<div class="words is-${s.phase.toLowerCase()}">${out}</div>`;  // null before the game: the TV shows the room
    },

    onState(prev, next, a) {
      api = a;
      if (!next || !prev) return;
      const mine = !!(next.turn && next.me && next.turn.explainer === next.me.id);
      const myT = next.me ? next.me.team : null;
      if (prev.phase !== next.phase) {
        if (next.phase === 'READY' && mine) { a.vibrate([90, 60, 90]); a.sfx('your_turn'); }
        else if (next.phase === 'TURN' || next.phase === 'CARDS') a.sfx('whoosh');
        else if (next.phase === 'LAST') a.vibrate([200, 100, 200]);
        else if (next.phase === 'REVIEW') a.sfx('reveal');
        else if (next.phase === 'FINAL') {
          a.sfx(next.winner != null ? 'win' : 'reveal');
          if (next.winner != null && myT === next.winner) a.vibrate([80, 50, 80, 50, 200]);
        }
        if (next.phase === 'READY' && next.notice && next.notice !== prev.notice) a.sfx('suspense');
        return;
      }
      // kids cards: a new picture, or one guessed
      if (next.phase === 'CARDS' && prev.cards && next.cards) {
        if (next.cards.got > prev.cards.got) a.sfx('correct');
        else if (next.cards.seen > prev.cards.seen) a.sfx('card');
        return;
      }
      // within a turn: guessed, skipped, caught on a forbidden word
      if (next.phase === 'TURN' && prev.turn && next.turn) {
        if (next.turn.got > prev.turn.got) a.sfx('correct');
        if (next.turn.skips > prev.turn.skips) a.sfx('card');
        if (next.turn.fouls > prev.turn.fouls) { a.sfx('bleep'); if (mine) a.vibrate([250, 80, 250]); }
      }
    },

    menu() {
      const s = g();
      const items = [];
      if (s && ['READY', 'TURN', 'LAST'].indexOf(s.phase) >= 0) items.push({ t: 'endTurn', label: s.phase === 'READY' ? '⏭️ Пропустити хід' : '⏹️ Завершити хід' });
      if (s && ['READY', 'TURN', 'LAST', 'REVIEW'].indexOf(s.phase) >= 0) items.push({ t: 'finish', label: '🏁 Завершити гру' });
      if (s && s.phase !== 'LOBBY') items.push('newGame');
      return items;
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const v = el.dataset.v;
      switch (el.dataset.act) {
        case 'set': {
          const on = el.type === 'checkbox' ? el.checked : v === '1';
          return a.send({ t: 'settings', [el.dataset.k]: on });
        }
        case 'mode': return a.send({ t: 'settings', mode: v });
        case 'theme': return a.send({ t: 'theme', id: v });
        case 'seconds': return a.send({ t: 'settings', seconds: +v });
        case 'goal': return a.send({ t: 'settings', goal: +v });
        case 'toTeams': return a.send({ t: 'toTeams' });
        case 'teams': return a.send({ t: 'teams', n: +v });
        case 'shuffle': return a.send({ t: 'shuffle' });
        case 'move': return a.send({ t: 'move', pid: el.dataset.pid });
        case 'join': return a.send({ t: 'join', team: +v });
        case 'start': return a.send({ t: 'start' });
        case 'begin': return a.send({ t: 'begin' });
        case 'got': a.vibrate(30); return a.send({ t: 'got' });
        case 'skip': return a.send({ t: 'skip' });
        case 'foul': a.vibrate(120); return a.send({ t: 'foul' });
        case 'last': return a.send({ t: 'last', by: +v });
        case 'lastBy': return a.send({ t: 'lastBy', by: +v });
        case 'mark': {
          const m = el.dataset.m;
          return a.send({ t: 'mark', i: +el.dataset.i, m: (g().taboo ? NEXT_MARK_TABOO : NEXT_MARK)[m] || 'GOT' });
        }
        case 'confirm': return a.send({ t: 'confirm' });
        case 'kgot': a.vibrate(30); return a.send({ t: 'kidsGot' });
        case 'knext': return a.send({ t: 'kidsNext' });
        case 'newGame': return a.send({ t: 'newGame' });
      }
    },
  });
})();
