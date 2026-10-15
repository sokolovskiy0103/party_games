// «Кривава вежа» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc } = PG.api;

  let api = PG.api;
  let revealed = false;   // role card flipped open
  let picks = [];         // night targets being chosen
  let mode = null;        // day: 'nominate' | 'shoot' while picking a target
  let target = null;      // day: picked target awaiting confirmation
  let showScript = false;
  let showGrim = false;
  let showLog = false;
  let armed = null;
  let popRole = false;    // play the card animation once, right after a flip
  let poppedOver = false; // the end banner animates once per game

  const KINDS = { TOWNSFOLK: 'Містянин', OUTSIDER: 'Чужинець', MINION: 'Поплічник', DEMON: 'Демон' };
  const KINDS_PL = { TOWNSFOLK: 'Містяни', OUTSIDER: 'Чужинці', MINION: 'Поплічники', DEMON: 'Демон' };

  const g = () => api.g;
  const me = () => g() && g().me;
  const seated = () => !!(me() && me().seated);
  const playing = () => g() && (g().phase === 'NIGHT' || g().phase === 'DAY');
  const seat = id => (g().seats || []).find(s => s.id === id);
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || seat(id) || {}).name || '?');
  const role = id => (g().script || []).find(r => r.id === id);

  // ---------- pieces ----------
  function roleCard() {
    const m = me();
    if (!m || !m.role) return '';
    const r = role(m.role);
    if (!revealed) {
      return `<div class="role hidden" data-act="flip">
        <div style="font-size:36px">🂠</div><div class="rname">Твоя роль</div>
        <div class="what">Торкнись, щоб подивитися. Сховай екран від інших.</div></div>`;
    }
    const pop = popRole ? 'pop' : '';
    popRole = false;
    return `<div class="role ${m.evil ? 'evil' : 'good'} ${pop}" data-act="flip">
      <div style="font-size:40px">${r.emoji}</div>
      <div class="rname">${esc(r.title)}</div>
      <div class="kind">${KINDS[r.kind]} · ${m.evil ? '<b class="c-evil">ти на боці зла</b>' : '<b class="c-good">ти на боці добра</b>'}</div>
      <div class="what">${esc(r.ability)}</div>
      ${m.master ? `<div class="small">Твій господар: <b>${nameOf(m.master)}</b></div>` : ''}
      <div class="muted small">Торкнись, щоб сховати</div></div>`;
  }

  function notesCard(onlyNight) {
    const m = me();
    if (!m || !m.notes) return '';
    const ns = onlyNight == null ? m.notes : m.notes.filter(n => n.night === onlyNight);
    if (!ns.length) return onlyNight == null ? '' : `<div class="card flat center muted">Нічого нового цієї ночі.</div>`;
    return `<div class="card stack notes">
      ${onlyNight == null ? '<b>📓 Записник</b>' : '<b>🌙 Що ти дізнався(лась)</b>'}
      ${ns.slice().reverse().map(n => `<div class="note"><span class="muted small">${n.night ? 'Ніч ' + n.night : 'День'}</span><div>${esc(n.text)}</div></div>`).join('')}
    </div>`;
  }

  function scriptCard() {
    const s = g();
    if (!showScript) return `<button class="ghost" data-act="script">📜 Усі ролі цієї гри</button>`;
    const kinds = ['TOWNSFOLK', 'OUTSIDER', 'MINION', 'DEMON'];
    return `<button class="ghost" data-act="script">Сховати ролі</button>
      <div class="card stack script">${kinds.map(k => `
        <div class="stack"><b class="${k === 'MINION' || k === 'DEMON' ? 'c-evil' : 'c-good'}">${KINDS_PL[k]}</b>
        ${s.script.filter(r => r.kind === k).map(r => `<div class="srole"><span class="em">${r.emoji}</span><div><b>${esc(r.title)}</b><div class="muted small">${esc(r.ability)}</div></div></div>`).join('')}</div>`).join('')}
      </div>`;
  }

  function composition() {
    const c = g().composition;
    if (!c) return '';
    return `<div class="chips">${['TOWNSFOLK', 'OUTSIDER', 'MINION', 'DEMON'].map(k =>
      `<span class="chip ${k === 'MINION' || k === 'DEMON' ? 'evilchip' : ''}">${KINDS_PL[k]}: ${c[k]}</span>`).join('')}</div>
      <div class="muted small">Барон, якщо він у грі, додає двох Чужинців замість двох Містян.</div>`;
  }

  /** The circle of seats as buttons (when picking) or rows. */
  function seatGrid(opts) {
    const s = g();
    return `<div class="seats">${s.seats.map((x, i) => {
      const can = opts && opts.can ? opts.can(x) : false;
      const on = opts && opts.on && opts.on(x);
      const marks = [
        !x.alive ? '💀' : '',
        !x.alive && x.ghostVote ? '👻' : '',
        s.block === x.id ? '⚖️' : '',
        x.nominated ? '🎯' : '',
        // never in a grid you pick from: it would change under your finger as others tap
        x.pending && !(opts && opts.act) ? '⏳' : '',
      ].join('');
      const label = `<span class="sn">${i + 1}</span><span class="nm">${esc(x.name)}${me() && me().id === x.id ? ' <span class="muted small">(ти)</span>' : ''}</span><span class="mk">${marks}</span>`;
      return can
        ? `<button class="seat ${x.alive ? '' : 'dead'} ${on ? 'on' : ''}" data-act="${opts.act}" data-pid="${x.id}">${label}</button>`
        : `<div class="seat ${x.alive ? '' : 'dead'} ${on ? 'on' : ''}">${label}</div>`;
    }).join('')}</div>`;
  }

  function eventsCard(n) {
    const ev = g().events || [];
    if (!ev.length) return '';
    return `<div class="card stack events">${ev.slice(-n).reverse().map(e => `<div class="small">${esc(e)}</div>`).join('')}</div>`;
  }

  function grimoire() {
    const s = g();
    if (!s.grimoire) return '';
    if (!showGrim) return `<button class="ghost" data-act="grim">👁 Гримуар (лише для ведучого)</button>`;
    return `<button class="ghost" data-act="grim">Сховати гримуар</button>
      <div class="card stack">${s.grimoire.map(l => `<div class="small">${esc(l)}</div>`).join('')}</div>
      <div class="card stack">${(s.log || []).map(l => `<div class="small muted">${esc(l)}</div>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.seating.length;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Як грати</b>
      <div class="muted small">Добро проти зла. Кожен отримує таємну роль зі здібністю. Уночі телефони роздають завдання
        й інформацію (отруєним і П'яниці — можливо, хибну). Удень говоріть, блефуйте, висувайте й голосуйте: хто набере
        найбільше голосів (не менше половини живих), того страчують. Мертві не виходять з гри — сперечаються далі
        і мають один голос до кінця гри. Добро перемагає, коли гине Демон; зло — коли живих лишається двоє.</div></div>`;
    h += `<h2>Місця за столом · ${n}</h2>
      <div class="muted small">Сядьте по колу саме в такому порядку — для Кухаря й Емпата це важливо.</div>
      <div class="card">${s.seating.map((id, i) => `
        <div class="score"><span class="sn">${i + 1}</span><span class="name">${nameOf(id)}</span>
          ${api.isHost ? `<button class="mini ghost" data-act="seat" data-pid="${id}" data-d="-1" ${i ? '' : 'disabled'}>↑</button><button class="mini ghost" data-act="seat" data-pid="${id}" data-d="1" ${i < n - 1 ? '' : 'disabled'}>↓</button>` : ''}
        </div>`).join('') || '<div class="muted">Поки нікого</div>'}</div>`;
    if (api.isHost) h += `<button class="ghost" data-act="shuffle">Перемішати місця</button>`;
    if (s.composition) h += `<div class="card stack"><b>Склад на ${n} гравців</b>${composition()}</div>`;
    h += scriptCard();
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${s.canStart ? '' : 'disabled'}>Роздати ролі</button>`;
      if (!s.canStart) h += `<div class="muted small center">Потрібно від ${s.minPlayers} до ${s.maxPlayers} гравців.</div>`;
    } else {
      h += `<div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост роздасть ролі</span></div>`;
    }
    return h;
  }

  function spectatorView() {
    const s = g();
    return `<div class="card flat center">Гра вже йде — ти дивишся збоку й зіграєш у наступній.</div>
      <div class="center"><span class="tag">${s.phase === 'NIGHT' ? '🌙 ніч ' + s.night : '☀️ день ' + s.night}</span></div>
      ${seatGrid()}${eventsCard(8)}${grimoire()}`;
  }

  function picker(task) {
    const s = g();
    const myId = me().id;
    const can = x => !(task.notSelf && x.id === myId) && !(task.aliveOnly && !x.alive);
    let h = `<div class="card stack task"><div>${esc(task.prompt)}</div>
      ${task.picks > 1 ? `<div class="muted small">Обери ${task.picks}.</div>` : ''}</div>`;
    h += seatGrid({ act: 'pick', can, on: x => picks.includes(x.id) });
    h += `<button class="big" data-act="confirmPick" ${picks.length === task.picks ? '' : 'disabled'}>Підтвердити${picks.length ? ': ' + picks.map(nameOf).join(', ') : ''}</button>`;
    return h;
  }

  function waiting(text) {
    const s = g();
    const left = s.seats.filter(x => x.pending).length;
    return `<div class="card flat center stack"><div><span class="spinner"></span> ${text}</div>
      ${api.isHost && left ? `<div class="muted small">Ще не закінчили: ${left}</div>` : ''}</div>`;
  }

  function nightView() {
    const s = g();
    const m = me();
    if (!seated()) return spectatorView();
    let h = `<div class="center"><span class="tag night">🌙 Ніч ${s.night}</span></div>`;
    const task = m.task;
    if (s.step === 'CHOOSE') {
      if (!m.alive) h += `<div class="card flat center">💀 Ти мертвий. Спи — вночі для тебе нічого немає.</div>`;
      else if (task && !task.done) h += picker(task);
      else h += waiting('Місто спить. Чекаємо, поки всі зроблять вибір…');
    } else {
      if (task && !task.done) h += picker(task);
      else if (m.mustRead) {
        h += notesCard(s.night);
        h += m.read ? waiting('Чекаємо, поки всі прочитають…') : `<button class="big" data-act="read">Прочитав(ла) ✓</button>`;
      } else if (!m.alive) h += `<div class="card flat center">💀 Ти мертвий. Спи.</div>`;
    }
    h += roleCard();
    if (api.isHost) h += `<button class="ghost" data-act="force">${s.step === 'CHOOSE' ? 'Не чекати — далі' : 'Не чекати — ранок'}</button>`;
    h += grimoire();
    return h;
  }

  function voteCard() {
    const s = g();
    const v = s.vote;
    if (!v) return '';
    const myId = me() && me().id;
    const eligible = v.eligible.includes(myId);
    const mine = v.votes[myId];
    const yes = Object.keys(v.votes).filter(k => v.votes[k]);
    const iAmDead = me() && me().seated && seat(myId) && !seat(myId).alive;
    let h = `<div class="card stack vote">
      <div class="center"><span class="tag">голосування</span></div>
      <div class="center big-line"><b>${nameOf(v.nominator)}</b> висуває <b class="c-evil">${nameOf(v.nominee)}</b></div>
      <div class="center">За: <b>${yes.length}</b> · потрібно щонайменше <b>${s.threshold}</b>${s.blockVotes ? ` і більше, ніж ${s.blockVotes}` : ''}</div>`;
    if (eligible) {
      h += `<div class="grid2">
        <button class="${mine === true ? '' : 'soft'}" data-act="vote" data-v="1">✋ За страту</button>
        <button class="${mine === false ? '' : 'soft'}" data-act="vote" data-v="0">Проти</button></div>
        ${iAmDead ? '<div class="muted small center">Ти мертвий: «за» витратить твій єдиний голос.</div>' : ''}`;
    } else if (me() && me().seated) {
      h += `<div class="muted small center">Ти вже витратив(ла) свій голос.</div>`;
    }
    h += `<div class="chips">${v.eligible.map(id => `<span class="chip ${v.votes[id] === true ? 'on' : v.votes[id] === false ? 'ok' : ''}">${v.votes[id] === true ? '✋ ' : ''}${nameOf(id)}</span>`).join('')}</div>`;
    if (api.isHost) h += `<button class="ghost" data-act="closeVote">Підрахувати зараз</button>`;
    return h + '</div>';
  }

  function dayActions() {
    const s = g();
    const m = me();
    if (!seated() || !m.alive || s.vote) return '';
    if (mode) {
      const can = x => x.alive && (mode === 'shoot' ? x.id !== m.id : !x.nominated);
      let h = `<div class="card stack task"><b>${mode === 'nominate' ? '🎯 Кого висуваєш на страту?' : '🏹 У кого стріляєш?'}</b>
        <div class="muted small">${mode === 'shoot' ? 'Постріл бачать усі. Якщо ти справжній Мисливець і влучиш у Демона — він помре. Раз за гру.' : 'Висуваєш раз на день, і кожного можна висунути лише раз на день.'}</div></div>`;
      h += seatGrid({ act: 'target', can, on: x => x.id === target });
      h += `<div class="grid2"><button class="ghost" data-act="cancelMode">Скасувати</button>
        <button data-act="confirmMode" ${target ? '' : 'disabled'}>${mode === 'nominate' ? 'Висунути' : 'Вистрілити'}${target ? ' ' + nameOf(target) : ''}</button></div>`;
      return h;
    }
    return `<div class="grid2">
      <button data-act="mode" data-v="nominate" ${m.nominated ? 'disabled' : ''}>🎯 Висунути</button>
      <button class="soft" data-act="mode" data-v="shoot" ${m.shot ? 'disabled' : ''}>🏹 Вистрілити</button></div>
      ${m.nominated ? '<div class="muted small center">Сьогодні ти вже висував(ла).</div>' : ''}`;
  }

  function dayView() {
    const s = g();
    if (!seated()) return spectatorView();
    let h = `<div class="center"><span class="tag day">☀️ День ${s.night}</span></div>`;
    const dawn = s.dawn || [];
    h += `<div class="banner ${dawn.length ? 'bad' : 'meh'}">${dawn.length
      ? `Цієї ночі ${dawn.length === 1 ? 'загинув(ла)' : 'загинули'}: ${dawn.map(nameOf).join(', ')}`
      : 'Ніч минула спокійно'}</div>`;
    h += voteCard();
    if (!s.vote) h += seatGrid();
    h += dayActions();
    h += `<div class="muted small center">Живих: ${s.aliveCount}. Для страти потрібно ${s.threshold}+ голосів.
      ${s.block ? `На пласі: <b>${nameOf(s.block)}</b> (${s.blockVotes}).` : 'На пласі нікого.'}</div>`;
    h += eventsCard(10);
    h += roleCard() + notesCard();
    if (api.isHost) {
      h += armed === 'endDay'
        ? `<button class="big danger" data-act="endDay">Точно? ${s.block ? 'Стратити ' + nameOf(s.block) + ' і' : 'Без страти —'} ніч</button>`
        : `<button class="big soft" data-act="arm" data-v="endDay">🌙 Завершити день</button>`;
    }
    h += scriptCard() + grimoire();
    return h;
  }

  function overView() {
    const s = g();
    const good = s.winner === 'GOOD';
    const mine = me() && me().seated ? (me().evil ? 'EVIL' : 'GOOD') : null;
    const cls = mine ? (mine === s.winner ? 'good' : 'bad') : 'meh';
    const pop = poppedOver ? '' : 'pop';
    poppedOver = true;
    let h = `<div class="banner ${cls} ${pop}"><h1>${good ? 'Перемогло добро 😇' : 'Перемогло зло 😈'}</h1><div>${esc(s.winReason || '')}</div></div>`;
    h += `<div class="card">${s.seats.map((x, i) => {
      const r = role(x.role), sh = role(x.shown);
      return `<div class="score"><span class="sn">${i + 1}</span>
        <span class="name">${esc(x.name)} ${x.alive ? '' : '💀'}<div class="small ${x.evil ? 'c-evil' : 'c-good'}">${r.emoji} ${esc(r.title)}${x.role !== x.shown ? ` <span class="muted">(думав(ла), що ${esc(sh.title)})</span>` : ''}</div></span></div>`;
    }).join('')}</div>`;
    h += showLog
      ? `<button class="ghost" data-act="log">Сховати хроніку</button><div class="card stack">${(s.log || []).map(l => `<div class="small">${esc(l)}</div>`).join('')}</div>`
      : `<button class="ghost" data-act="log">📜 Що відбувалося насправді</button>`;
    h += eventsCard(30);
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.canStart ? '' : 'disabled'}>Нова гра</button>`
      : '<div class="muted center small">Хост може роздати нові ролі.</div>';
    return h;
  }

  PG.register('clocktower', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'NIGHT': h = nightView(); break;
        case 'DAY': h = dayView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="clocktower stack ${s.phase === 'NIGHT' ? 'is-night' : ''}">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      const stepKey = s => s && `${s.phase}/${s.night}/${s.step}`;
      if (!prev || stepKey(prev) !== stepKey(next)) {
        picks = []; mode = null; target = null; armed = null;
        if (prev) a.vibrate(next.phase === 'NIGHT' ? [60, 60, 60] : 150);
      }
      if (!prev || prev.phase === 'LOBBY' || prev.phase === 'OVER') { if (next.phase === 'NIGHT') revealed = false; }
      if (next.phase !== 'OVER') poppedOver = false;
      // a fresh night task, or my role changed under me (Scarlet Woman, star-pass)
      const role = s => s && s.me && s.me.role;
      if (prev && role(prev) && role(prev) !== role(next)) { revealed = true; popRole = true; a.vibrate([200, 100, 200]); }
      if (prev && !prev.vote && next.vote) a.vibrate(100);
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'flip': revealed = !revealed; popRole = revealed; return a.render();
        case 'script': showScript = !showScript; return a.render();
        case 'grim': showGrim = !showGrim; return a.render();
        case 'log': showLog = !showLog; return a.render();
        case 'seat': return a.send({ t: 'seat', pid: el.dataset.pid, d: +el.dataset.d });
        case 'shuffle': return a.send({ t: 'shuffle' });
        case 'start': revealed = false; return a.send({ t: 'start' });
        case 'pick': {
          const id = el.dataset.pid;
          const n = (me().task || {}).picks || 1;
          if (picks.includes(id)) picks = picks.filter(x => x !== id);
          else picks = n === 1 ? [id] : [...picks, id].slice(-n);
          return a.render();
        }
        case 'confirmPick': { const ts = picks; picks = []; return a.send({ t: 'choose', targets: ts }); }
        case 'read': return a.send({ t: 'read' });
        case 'force': return a.send({ t: 'force' });
        case 'mode': mode = el.dataset.v; target = null; return a.render();
        case 'cancelMode': mode = null; target = null; return a.render();
        case 'target': target = target === el.dataset.pid ? null : el.dataset.pid; return a.render();
        case 'confirmMode': {
          const m = mode, t = target;
          mode = null; target = null;
          if (!t) return a.render();
          return a.send({ t: m === 'shoot' ? 'shoot' : 'nominate', target: t });
        }
        case 'vote': return a.send({ t: 'vote', yes: el.dataset.v === '1' });
        case 'closeVote': return a.send({ t: 'closeVote' });
        case 'arm': armed = el.dataset.v; a.render(); setTimeout(() => { if (armed === el.dataset.v) { armed = null; a.render(); } }, 4000); return;
        case 'endDay': armed = null; return a.send({ t: 'endDay' });
      }
    },
  });
})();
