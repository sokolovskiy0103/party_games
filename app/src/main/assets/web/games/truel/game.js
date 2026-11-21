// «Труель» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  // This turn's move being put together on this phone.
  let draft = { key: '', act: null, target: null, card: null, cardTarget: null, editing: false };

  const g = () => api.g;
  const me = () => g() && g().me;
  const player = id => (g().players.find(p => p.id === id) || {});
  const nm = id => esc(player(id).name || '?');
  const mine = () => me() && player(me().id);

  const CARDS = {
    VEST: ['🦺', 'Бронежилет', 'Поглинає перший постріл у тебе цього ходу. Снайпера не зупиняє.'],
    ANGEL: ['😇', 'Ангел-охоронець', 'Обраний гравець (не ти) цього ходу невразливий — навіть для снайпера.'],
    SNIPER: ['🎯', 'Снайпер', 'До пострілу: його не зупинить ні ухилення, ні бронежилет.'],
    FAN: ['🌪️', 'Віяло', 'До пострілу: одна куля летить у кожного суперника.'],
    MIRROR: ['🪞', 'Рикошет', 'Постріли в тебе цього ходу летять назад у стрільця. Вбивство зараховується тобі.'],
    JAM: ['🔧', 'Осічка', 'Постріл обраного гравця цього ходу не вилетить, а куля згорить.'],
    MEDKIT: ['💊', 'Аптечка', '+1 ❤️ (до 3) ще до пострілів.'],
    AMMO: ['📦', 'Патронташ', '+2 кулі наприкінці ходу (до 3).'],
  };
  const ACTS = {
    SHOOT: ['🔫', 'Стріляти'],
    DODGE: ['💨', 'Ухилитися'],
    RELOAD: ['🔄', 'Перезарядка'],
    IDLE: ['😐', 'Стоїть'],
  };
  const needsTarget = c => c === 'ANGEL' || c === 'JAM';
  const needsShot = c => c === 'SNIPER' || c === 'FAN';
  const pts = n => n === 1 ? 'очко' : (n >= 2 && n <= 4 ? 'очки' : 'очок');
  const hearts = n => n > 0 ? '❤️'.repeat(n) : '💀';

  function syncDraft() {
    const s = g();
    const key = s.showdown + ':' + s.turn;
    if (draft.key === key) return;
    const m = (me() && me().move) || {};
    draft = { key, act: m.act || null, target: m.target || null, card: m.card || null, cardTarget: m.cardTarget || null, editing: false };
  }

  // ---------- pieces ----------
  function header() {
    const s = g();
    return `<div class="row"><span class="tag accent">перестрілка ${s.showdown}</span>
      <span class="tag">хід ${s.turn} з ${s.maxTurns}</span><span class="grow"></span>
      <span class="small muted">гра до <b>${s.target}</b></span></div>`;
  }

  function table(showStatus) {
    const s = g();
    return `<div class="card">${s.players.map(p => {
      let status = '';
      if (showStatus && p.alive) status = p.moved ? '<span class="small c-good">✓</span>' : ui.typing('sm');
      return `<div class="li ${me() && p.id === me().id ? 'now' : ''} ${p.alive ? '' : 'dead'}">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}<div class="small muted stats">${p.alive
          ? `${hearts(p.hp)} · 🔫${p.ammo}${s.cards ? ` · 🃏${p.cards}` : ''}${p.tired ? ' · 😮‍💨' : ''}`
          : 'вибув(ла)'}</div></span>
        ${status}<span class="pts">${p.score}</span></div>`;
    }).join('')}</div>`;
  }

  function cardBox(c, on) {
    const [ico, title, what] = CARDS[c];
    return `<span class="ico">${ico}</span><span><b>${title}</b><div class="small muted">${what}</div></span>`;
  }

  function moveText(m) {
    if (!m) return '';
    let t = `${ACTS[m.act][0]} ${m.act === 'SHOOT' ? (m.card === 'FAN' ? 'у всіх' : '→ ' + nm(m.target)) : ACTS[m.act][1].toLowerCase()}`;
    if (m.card) t += ` · ${CARDS[m.card][0]} ${CARDS[m.card][1]}${m.cardTarget ? ' → ' + nm(m.cardTarget) : ''}`;
    return t;
  }

  function eventText(e) {
    switch (e.k) {
      case 'heal': return `💊 ${nm(e.a)} лікується${e.n ? '' : ' (і так повне здоров’я)'}`;
      case 'jam': return `🔧 У ${nm(e.a)} осічка — куля згоріла`;
      case 'mirror': return `🪞 Постріл ${nm(e.b)} рикошетить від ${nm(e.a)}`;
      case 'angel': return `😇 Ангел береже ${nm(e.a)} від кулі ${nm(e.b)}`;
      case 'dodge': return `💨 ${nm(e.a)} ухиляється від кулі ${nm(e.b)}`;
      case 'vest': return `🦺 Бронежилет ${nm(e.a)} зупиняє кулю ${nm(e.b)}`;
      case 'hit': return e.a === e.b ? `🔫 ${nm(e.a)} дістає власна куля` : `🔫 ${nm(e.a)} влучає в ${nm(e.b)}`;
      case 'snipe': return e.a === e.b ? `🎯 ${nm(e.a)} дістає власна снайперська куля` : `🎯 ${nm(e.a)} знімає ${nm(e.b)}`;
      case 'dead': return `<b class="c-bad">💀 ${nm(e.a)} вибуває</b>`;
      case 'ammo': return `📦 ${nm(e.a)} +${e.n} ${e.n === 1 ? 'куля' : 'кулі'}`;
      default: return '';
    }
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    const chips = (act, list, cur, label) => api.isHost
      ? `<div class="chips">${list.map(v => `<button class="chip ${cur === v ? 'on' : ''}" data-act="${act}" data-v="${v}">${label(v)}</button>`).join('')}</div>`
      : `<div class="chips"><span class="chip on">${label(cur)}</span></div>`;
    h += `<div class="card stack"><b>Гра до</b>${chips('target', s.targets, s.target, v => `${v} очок`)}
      <b>Час на хід</b>${chips('seconds', s.secondsList, s.seconds, v => v ? `${v} с` : 'без таймера')}
      ${api.isHost
        ? `<label class="toggle"><input type="checkbox" class="switch" data-act="cards" ${s.cards ? 'checked' : ''}> Карти-трюки</label>`
        : `<div class="small">Карти-трюки: <b>${s.cards ? 'так' : 'ні'}</b></div>`}
      <div class="muted small">Усі <b>одночасно й таємно</b> стріляють, ухиляються чи перезаряджаються. Поки йде таймер — домовляйтеся
        вголос, але ніхто не зобов’язаний тримати слово. Вбивство +1, хто вистояв — +3.</div></div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${n >= s.minPlayers ? '' : 'disabled'}>Почати</button>`;
      h += `<div class="muted small center">${n < s.minPlayers ? `Потрібно щонайменше ${s.minPlayers} гравці.` : 'Найкраще — саме втрьох.'}</div>`;
    }
    return h;
  }

  function moveForm() {
    const s = g();
    const p = mine();
    const foes = s.players.filter(x => x.alive && x.id !== p.id);
    const others = foes;  // targets for Angel / Jam: any other living player
    const hand = me().hand || [];
    let h = `<div class="card stack"><b>Твій хід</b><div class="grid2">${['SHOOT', 'DODGE', 'RELOAD'].map(a => {
      const off = (a === 'SHOOT' && p.ammo <= 0) || (a === 'DODGE' && p.tired);
      const why = a === 'SHOOT' && p.ammo <= 0 ? 'немає куль' : a === 'DODGE' && p.tired ? 'двічі поспіль не можна' : a === 'RELOAD' ? (s.cards ? '+1 куля і карта' : '+1 куля') : a === 'SHOOT' ? `куль: ${p.ammo}` : 'кулі пролетять повз';
      return `<button class="pick center ${draft.act === a ? 'on' : ''}" data-act="act" data-v="${a}" ${off ? 'disabled' : ''}>
        ${ACTS[a][0]} ${ACTS[a][1]}<div class="small muted">${why}</div></button>`;
    }).join('')}</div>`;
    if (draft.act === 'SHOOT' && draft.card !== 'FAN') {
      h += `<b>У кого?</b><div class="grid2">${foes.map(f => `<button class="pick center ${draft.target === f.id ? 'on' : ''}" data-act="target" data-v="${f.id}">
        ${esc(f.name)}<div class="small muted">${hearts(f.hp)} · 🔫${f.ammo}</div></button>`).join('')}</div>`;
    }
    h += '</div>';
    if (s.cards) {
      h += `<div class="card stack"><b>Карта <span class="muted small">(необов’язково, одна за хід)</span></b>`;
      h += hand.length ? `<div class="picks">${hand.map(c => {
        const off = needsShot(c) && draft.act !== 'SHOOT';
        return `<button class="pick hand ${draft.card === c ? 'on' : ''}" data-act="card" data-v="${c}" ${off ? 'disabled' : ''}>${cardBox(c)}</button>`;
      }).join('')}</div>` : '<div class="muted small">Карт немає — перезарядка дає нову.</div>';
      if (draft.card && needsTarget(draft.card)) {
        h += `<b>${draft.card === 'ANGEL' ? 'Кого береже ангел?' : 'Чий пістолет заклинить?'}</b>
          <div class="grid2">${others.map(f => `<button class="pick center ${draft.cardTarget === f.id ? 'on' : ''}" data-act="cardTarget" data-v="${f.id}">${esc(f.name)}</button>`).join('')}</div>`;
      }
      h += '</div>';
    }
    h += `<button class="big" data-act="send" ${ready() ? '' : 'disabled'}>Підтвердити</button>`;
    return h;
  }

  function ready() {
    if (!draft.act) return false;
    if (draft.act === 'SHOOT' && draft.card !== 'FAN' && !draft.target) return false;
    if (draft.card && needsShot(draft.card) && draft.act !== 'SHOOT') return false;
    if (draft.card && needsTarget(draft.card) && !draft.cardTarget) return false;
    return true;
  }

  function planView() {
    const s = g();
    syncDraft();
    const p = mine();
    let h = header();
    if (s.deadline) h += ui.timer({ deadline: s.deadline, low: 10000, fmt: 'sec' }) + ui.timerBar({ deadline: s.deadline, total: s.total, low: 10000 });
    if (s.turn === s.maxTurns) h += '<div class="notice">Останній хід перестрілки! Хто вистоїть — +1.</div>';
    if (p && p.alive) {
      const sent = me().move;
      if (sent && !draft.editing) {
        h += `<div class="banner meh">Хід прийнято ✓<div class="small">${moveText(sent)}</div></div>
          <button class="ghost" data-act="edit">Змінити</button>`;
      } else {
        h += moveForm();
      }
    } else if (p) {
      h += '<div class="card flat center">💀 Ти вибув(ла) з цієї перестрілки. Дивись, підказуй і чекай наступної.</div>';
    }
    const done = s.players.filter(x => x.alive && x.moved).length;
    const all = s.players.filter(x => x.alive).length;
    h += `<h2>Обрали · ${done} з ${all}</h2>` + table(true);
    if (api.isHost && done < all) h += `<button class="soft" data-act="resolve">Зіграти хід зараз (хто не обрав — стоїть)</button>`;
    return h;
  }

  function revealView() {
    const s = g();
    let h = header();
    if (s.showdownOver) {
      const winners = s.players.filter(p => p.alive);
      const title = winners.length === 1 ? `🤠 ${esc(winners[0].name)} вистояв(ла)!`
        : winners.length ? '🌅 Перестрілку зупинено' : '💀 Не вижив ніхто';
      const gains = s.players.filter(p => p.gain).map(p => `<b>${esc(p.name)}</b> +${p.gain}`).join(' · ');
      h += `<div class="banner ${winners.length === 1 ? 'good' : 'meh'}"><h1>${title}</h1><div>${gains || 'Очок ніхто не отримав.'}</div></div>`;
    }
    h += `<h2>Хто що зробив</h2><div class="card">${s.players.filter(p => s.moves[p.id]).map(p =>
      `<div class="li"><span class="name">${esc(p.name)}</span><span class="small">${moveText(s.moves[p.id])}</span></div>`).join('')}</div>`;
    const ev = s.events.map(eventText).filter(Boolean);
    h += `<div class="card stack tight">${ev.length ? ev.map(t => `<div>${t}</div>`).join('') : '<div class="muted">Тиша. Ніхто не постраждав.</div>'}</div>`;
    if (!s.showdownOver) {
      const kills = s.players.filter(p => p.gain).map(p => `<b>${esc(p.name)}</b> +${p.gain}`).join(' · ');
      if (kills) h += `<div class="card flat center small">${kills}</div>`;
    }
    h += `<h2>Рахунок</h2>` + table(false);
    const p = mine();
    const need = p && p.connected !== false && (p.alive || s.showdownOver);
    const label = !s.showdownOver ? 'Наступний хід' : s.ending ? 'Підсумки' : 'Нова перестрілка';
    if (need && !p.ready) h += `<button class="big" data-act="ready">${label}</button>`;
    else if (need) h += '<div class="muted center small">Чекаємо на інших…</div>';
    if (api.isHost) h += `<button class="soft" data-act="next">Далі без очікування</button>`;
    return h;
  }

  function overView() {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    const top = list[0];
    let h = `<div class="banner good"><span class="emoji">🏆</span><h1>${esc(top.name)}</h1><div>${top.score} ${pts(top.score)}</div></div>`;
    h += `<div class="card">${list.map(p => `<div class="li"><span class="name">${esc(p.name)}
      <div class="small muted">💀 вбивств: ${p.kills} · 🤠 перестрілок виграно: ${p.wins}</div></span><span class="pts">${p.score}</span></div>`).join('')}</div>`;
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне заново.</div>';
    return h;
  }

  PG.register('truel', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'PLAN': h = planView(); break;
        case 'REVEAL': h = revealView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="truel stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (prev && prev.phase !== next.phase) {
        a.vibrate(next.phase === 'REVEAL' && next.events && next.events.some(e => e.k === 'hit' || e.k === 'snipe') ? [80, 60, 80] : 120);
      }
    },

    menu() { return ['newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const v = el.dataset.v;
      switch (el.dataset.act) {
        case 'target':
          if (g().phase === 'LOBBY') return a.send({ t: 'target', v: +v });
          draft.target = v; return a.render();
        case 'seconds': return a.send({ t: 'seconds', v: +v });
        case 'cards': return a.send({ t: 'cards', v: el.checked });
        case 'start': return a.send({ t: 'start' });
        case 'resolve': return a.send({ t: 'resolve' });
        case 'next': return a.send({ t: 'next' });
        case 'ready': return a.send({ t: 'ready' });
        case 'act':
          draft.act = v;
          if (v !== 'SHOOT') { draft.target = null; if (needsShot(draft.card)) draft.card = null; }
          return a.render();
        case 'card':
          draft.card = draft.card === v ? null : v;
          draft.cardTarget = null;
          return a.render();
        case 'cardTarget': draft.cardTarget = v; return a.render();
        case 'edit': draft.editing = true; return a.render();
        case 'send': {
          if (!ready()) return;
          draft.editing = false;
          a.send({ t: 'move', act: draft.act, target: draft.target || '', card: draft.card || '', cardTarget: draft.cardTarget || '' });
          return a.render();
        }
      }
    },
  });
})();
