// «Труель» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  // This turn's move being put together on this phone.
  // The card is picked by its slot in the hand (cardIdx), since a hand can hold two of the same kind.
  let draft = { key: '', act: null, target: null, card: null, cardIdx: -1, cardTarget: null, editing: false };

  const g = () => api.g;
  const me = () => g() && g().me;
  const player = id => (g().players.find(p => p.id === id) || {});
  const nm = id => esc(player(id).name || '?');
  const mine = () => me() && player(me().id);

  const CARDS = {
    VEST: ['🦺', 'Бронежилет', 'Поглинає перший постріл у тебе цього ходу. Снайпера не зупиняє.'],
    ANGEL: ['😇', 'Ангел-охоронець', 'Обраний гравець цього ходу невразливий, навіть для снайпера. Відвів кулю — тобі +1 очко.'],
    SNIPER: ['🎯', 'Снайпер', 'До пострілу: його не зупинить ні ухилення, ні бронежилет.'],
    FAN: ['🌪️', 'Віяло', 'До пострілу: одна куля летить у кожного суперника.'],
    MIRROR: ['🪞', 'Рикошет', 'Постріли в тебе цього ходу летять назад у стрільця. Вбивство зараховується тобі.'],
    JAM: ['🔧', 'Осічка', 'Постріл обраного гравця цього ходу не вилетить, а куля згорить.'],
    MEDKIT: ['💊', 'Аптечка', '+1 ❤️ (до 3) ще до пострілів.'],
    AMMO: ['📦', 'Патронташ', '+2 кулі наприкінці ходу (до 3).'],
    ROD: ['⚡', 'Громовідвід', 'Кулі, випущені в тебе, летять в обраного гравця, вбивство — стрільцю. Ніхто не стрельнув — сам втрачаєш ❤️.'],
    BOUNTY: ['💰', 'Нагорода', 'За голову обраного гравця: хто в нього влучить — +1 очко. Діє до кінця перестрілки.'],
    TRUCE: ['🤝', 'Перемир’я', 'Ви з обраним гравцем цього ходу не стріляєте одне в одного — обом +1 куля і карта. Хто стрельне — отримає нагороду за свою голову.'],
  };
  // who a targeted card asks for
  const CARD_ASK = {
    ANGEL: 'Кого береже ангел?', JAM: 'Чий пістолет заклинить?', ROD: 'Куди відвести кулі?',
    BOUNTY: 'За чию голову нагорода?', TRUCE: 'Перемир’я з ким?',
  };
  const ACTS = {
    SHOOT: ['🔫', 'Стріляти'],
    DODGE: ['💨', 'Ухилитися'],
    RELOAD: ['🔄', 'Перезарядка'],
    IDLE: ['😐', 'Стоїть'],
  };
  const needsTarget = c => !!CARD_ASK[c];
  const needsShot = c => c === 'SNIPER' || c === 'FAN';
  const pts = n => n === 1 ? 'очко' : (n >= 2 && n <= 4 ? 'очки' : 'очок');
  // What each revealed event sounds like on the room's speaker.
  const EVENT_SFX = {
    hit: 'shot', snipe: 'shot', dodge: 'shot', vest: 'shot', angel: 'shot', mirror: 'ricochet', rod: 'ricochet', rodmiss: 'shot',
    jam: 'reload', dead: 'death', bounty: 'card', reward: 'correct', guard: 'correct', truce: 'correct', betray: 'wrong',
  };
  // this player lost a life to it
  const hurts = (e, id) => ((e.k === 'hit' || e.k === 'snipe') && e.b === id) || (e.k === 'rodmiss' && e.a === id);
  const STEP = 520;  // ms between revealed events: sounds and lines go together

  function syncDraft() {
    const s = g();
    const key = s.showdown + ':' + s.turn;
    if (draft.key === key) return;
    const m = (me() && me().move) || {};
    const idx = m.card ? ((me() && me().hand) || []).indexOf(m.card) : -1;
    draft = { key, act: m.act || null, target: m.target || null, card: m.card || null, cardIdx: idx, cardTarget: m.cardTarget || null, editing: false };
  }

  // ---------- pieces ----------
  const pips = (n, max, cls) => `<span class="pips ${cls}">${Array.from({ length: max }, (_, i) => `<i class="${i < n ? 'on' : ''}"></i>`).join('')}</span>`;

  function stats(p) {
    const s = g();
    if (!p.alive) return '<span class="muted">вибув(ла)</span>';
    return `${pips(p.hp, s.maxHp, 'hp')}${pips(p.ammo, s.maxAmmo, 'ammo')}${s.cards ? `<span class="cnt">🃏${p.cards}</span>` : ''}${p.tired ? '<span class="cnt" title="ухилявся — зараз не може">😮‍💨</span>' : ''}${p.bounty ? `<span class="tr-bounty" title="нагорода за голову">💰${p.bounty > 1 ? '×' + p.bounty : ''}</span>` : ''}`;
  }

  function header() {
    const s = g();
    return `<div class="row tr-head"><span class="tag accent">перестрілка ${s.showdown}</span>
      <span class="tag ${s.turn === s.maxTurns ? 'last' : ''}">хід ${s.turn} з ${s.maxTurns}</span><span class="grow"></span>
      <span class="small muted">гра до <b>${s.target}</b></span></div>`;
  }

  function table(showStatus) {
    const s = g();
    return `<div class="card tr-table">${s.players.map(p => {
      let status = '';
      if (showStatus && p.alive) status = p.moved ? '<span class="tr-ok">✓</span>' : ui.typing('sm');
      return `<div class="li ${me() && p.id === me().id ? 'now' : ''} ${p.alive ? '' : 'dead'} ${p.gain ? 'gain' : ''}" data-k="${p.id}">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${p.alive ? '' : '💀 '}${esc(p.name)}<div class="stats">${stats(p)}</div></span>
        ${status}${p.gain ? `<span class="plus">+${p.gain}</span>` : ''}<span class="pts">${p.score}</span></div>`;
    }).join('')}</div>`;
  }

  function cardBox(c) {
    const [ico, title, what] = CARDS[c];
    return `<span class="ico">${ico}</span><span class="txt"><b>${title}</b><span class="what">${what}</span></span>`;
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
      case 'dead': return `💀 ${nm(e.a)} вибуває`;
      case 'ammo': return e.n ? `📦 ${nm(e.a)} +${e.n} ${e.n === 1 ? 'куля' : 'кулі'}` : `📦 ${nm(e.a)}: барабан і так повний`;
      case 'rod': return `⚡ Громовідвід ${nm(e.a)} відводить кулю ${nm(e.b)}`;
      case 'rodmiss': return `⚡ У ${nm(e.a)} ніхто не стрельнув — громовідвід б’є по власнику`;
      case 'bounty': return `💰 ${nm(e.a)} оголошує нагороду за голову ${nm(e.b)}`;
      case 'reward': return `💰 ${nm(e.a)} забирає нагороду за ${nm(e.b)}: +${e.n} ${pts(e.n)}`;
      case 'guard': return `😇 Ангел ${nm(e.a)} врятував ${nm(e.b)}: +${e.n} ${pts(e.n)}`;
      case 'truce': return `🤝 ${nm(e.a)} і ${nm(e.b)} дотримали перемир’я: обом куля й карта`;
      case 'betray': return `🗡️ ${nm(e.a)} зраджує перемир’я з ${nm(e.b)} — за голову зрадника нагорода`;
      default: return '';
    }
  }
  const eventTone = e => e.k === 'dead' ? 'dead'
    : (e.k === 'hit' || e.k === 'snipe' || e.k === 'rodmiss' || e.k === 'betray') ? 'hit'
    : (e.k === 'dodge' || e.k === 'vest' || e.k === 'angel' || e.k === 'reward' || e.k === 'guard' || e.k === 'truce') ? 'saved' : '';

  // The turn's events, one after another (same rhythm as the sounds).
  function eventLog(big) {
    const s = g();
    const ev = s.events.filter(e => eventText(e));
    return `<div class="card tr-log ${big ? 'big' : ''}" data-k="log-${s.showdown}-${s.turn}">${ev.length
      ? ev.map((e, i) => `<div class="ev ${eventTone(e)}" style="--d:${i * STEP}ms">${eventText(e)}</div>`).join('')
      : '<div class="ev muted">Тиша. Ніхто не постраждав.</div>'}</div>`;
  }

  function showdownBanner() {
    const s = g();
    const winners = s.players.filter(p => p.alive);
    const solo = winners.length === 1;
    const title = solo ? `${esc(winners[0].name)} вистояв(ла)!` : winners.length ? 'Перестрілку зупинено' : 'Не вижив ніхто';
    const gains = s.players.filter(p => p.gain).map(p => `<b>${esc(p.name)}</b> +${p.gain}`).join(' · ');
    const delay = s.events.length * STEP;
    return `<div class="banner tr-result ${solo ? 'good' : 'meh'}" style="--d:${delay}ms" data-k="res-${s.showdown}">
      <span class="emoji">${solo ? '🤠' : winners.length ? '🌅' : '💀'}</span><h1>${title}</h1><div>${gains || 'Очок ніхто не отримав.'}</div></div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    let h = `<div class="hero center tr-hero"><div class="tr-title">🤠 Труель</div>
      <div class="muted">Троє. Таємні ходи. Усі стріляють одночасно.</div></div>`;
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
        вголос, але ніхто не зобов’язаний тримати слово. Вбивство +1, хто вистояв — +3. Деякі карти платять за допомогу іншим.</div></div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip" data-k="${p.id}"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
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
    const hand = me().hand || [];
    let h = `<div class="card stack"><b>Твій хід</b><div class="tr-acts">${['SHOOT', 'DODGE', 'RELOAD'].map(a => {
      const off = (a === 'SHOOT' && p.ammo <= 0) || (a === 'DODGE' && p.tired);
      const why = a === 'SHOOT' && p.ammo <= 0 ? 'немає куль' : a === 'DODGE' && p.tired ? 'не двічі поспіль' : a === 'RELOAD' ? (s.cards ? '+1 куля і карта' : '+1 куля') : a === 'SHOOT' ? `куль: ${p.ammo}` : 'кулі повз';
      return `<button class="pick center tr-act ${draft.act === a ? 'on' : ''}" data-act="act" data-v="${a}" ${off ? 'disabled' : ''}>
        <span class="ico">${ACTS[a][0]}</span>${ACTS[a][1]}<span class="why">${why}</span></button>`;
    }).join('')}</div>`;
    if (draft.act === 'SHOOT' && draft.card !== 'FAN') {
      h += `<b>У кого?</b><div class="grid2">${foes.map(f => `<button class="pick center tr-foe ${draft.target === f.id ? 'on' : ''}" data-act="target" data-v="${f.id}">
        <span class="nm">${esc(f.name)}</span><div class="stats">${stats(f)}</div></button>`).join('')}</div>`;
    }
    h += '</div>';
    if (s.cards) {
      h += `<div class="card stack"><b>Карта <span class="muted small">(необов’язково, одна за хід)</span></b>`;
      h += hand.length ? `<div class="picks">${hand.map((c, i) => {
        const off = needsShot(c) && draft.act !== 'SHOOT';
        return `<button class="pick tr-card ${draft.cardIdx === i ? 'on' : ''}" data-act="card" data-v="${c}" data-i="${i}" data-k="${c}${i}" ${off ? 'disabled' : ''}>${cardBox(c)}</button>`;
      }).join('')}</div>` : '<div class="muted small">Карт немає — перезарядка дає нову.</div>';
      if (draft.card && needsTarget(draft.card)) {
        h += `<b>${CARD_ASK[draft.card]}</b>
          <div class="grid2">${foes.map(f => `<button class="pick center ${draft.cardTarget === f.id ? 'on' : ''}" data-act="cardTarget" data-v="${f.id}">${esc(f.name)}</button>`).join('')}</div>`;
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

  function clock(cls) {
    const s = g();
    return s.deadline ? `<div class="tr-clock ${cls || ''}">${ui.timer({ deadline: s.deadline, low: 10000, fmt: 'sec' })}${ui.timerBar({ deadline: s.deadline, total: s.total, low: 10000 })}</div>` : '';
  }

  function planView() {
    const s = g();
    syncDraft();
    const p = mine();
    let h = header() + clock();
    if (s.turn === s.maxTurns) h += '<div class="notice">Останній хід перестрілки! Хто вистоїть — +1.</div>';
    if (p && p.alive) {
      const sent = me().move;
      if (sent && !draft.editing) {
        h += `<div class="banner meh tr-sent"><span class="emoji">🤫</span>Хід прийнято<div class="small">${moveText(sent)}</div></div>
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

  function movesCard() {
    const s = g();
    return `<div class="card tr-moves">${s.players.filter(p => s.moves[p.id]).map(p =>
      `<div class="li" data-k="mv-${p.id}"><span class="name">${esc(p.name)}</span><span class="mv">${moveText(s.moves[p.id])}</span></div>`).join('')}</div>`;
  }

  function revealView() {
    const s = g();
    const at = me() ? s.events.findIndex(e => hurts(e, me().id)) : -1;
    let h = header();
    if (at >= 0) h += `<div class="banner bad tr-ouch" style="--d:${at * STEP}ms">${!mine().alive ? '💀 Ти вибув(ла)' : s.events[at].k === 'rodmiss' ? '⚡ Громовідвід ударив по тобі' : '🩸 У тебе влучили!'}</div>`;
    h += `<h2>Хто що зробив</h2>` + movesCard() + eventLog(false);
    if (s.showdownOver) h += showdownBanner();
    h += `<h2>Рахунок</h2>` + table(false);
    const p = mine();
    const need = p && p.connected !== false && (p.alive || s.showdownOver);
    const label = !s.showdownOver ? 'Наступний хід' : s.ending ? 'Підсумки' : 'Нова перестрілка';
    if (need && !p.ready) h += `<button class="big" data-act="ready">${label}</button>`;
    else if (need) h += '<div class="muted center small">Чекаємо на інших…</div>';
    if (api.isHost) h += `<button class="soft" data-act="next">Далі без очікування</button>`;
    return h;
  }

  function podium() {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    const top = list[0];
    return `<div class="banner good"><span class="emoji">🏆</span><h1>${esc(top.name)}</h1><div>${top.score} ${pts(top.score)}</div></div>
      <div class="card">${list.map((p, i) => `<div class="li" data-k="${p.id}"><span class="place">${['🥇', '🥈', '🥉'][i] || i + 1}</span><span class="name">${esc(p.name)}
      <div class="small muted">💀 вбивств: ${p.kills} · 🤠 перестрілок виграно: ${p.wins}</div></span><span class="pts">${p.score}</span></div>`).join('')}</div>`;
  }

  function overView() {
    const s = g();
    let h = podium();
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне заново.</div>';
    return h;
  }

  // ---------- TV ----------
  function tvPlan() {
    const s = g();
    const waiting = s.players.filter(p => p.alive && !p.moved).map(p => esc(p.name));
    const main = `${header()}${clock('tv')}
      <div class="tr-tvcall">${waiting.length ? `Обирають: <b>${waiting.join(', ')}</b>` : 'Усі обрали…'}</div>
      <div class="muted center">Домовляйтеся вголос — але ніхто не зобов’язаний тримати слово.</div>`;
    return `<div class="tv-split"><div class="stack">${main}</div><div class="stack"><h2>Стрільці</h2>${table(true)}</div></div>`;
  }

  function tvReveal() {
    const s = g();
    const main = header() + movesCard() + eventLog(true) + (s.showdownOver ? showdownBanner() : '');
    return `<div class="tv-split"><div class="stack">${main}</div><div class="stack"><h2>Рахунок</h2>${table(false)}</div></div>`;
  }

  PG.register('truel', {
    // background music per phase (shell.js): people bargain while choosing and argue over the result
    music: { PLAN: 'low', REVEAL: 'low' },
    musicLoop: 78,  // seconds of music.mp3 that loop: 26 bars at 80 BPM
    sfx: ['shot', 'ricochet', 'reload', 'death', 'standoff'],  // own effects: sfx/<name>.mp3
    bg: 'loop',  // games/truel/bg.webm|mp4 (phone), bg-tv.* (TV); bg.webp is the poster

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
      return `<div class="truel is-${s.phase.toLowerCase()} stack">${h}</div>`;
    },

    board(a) {
      api = a;
      const s = g();
      if (!s) return null;
      let out = null;
      switch (s.phase) {
        case 'PLAN': out = tvPlan(); break;
        case 'REVEAL': out = tvReveal(); break;
        case 'OVER': out = `<div class="tr-tvover">${podium()}</div>`; break;
      }
      return out && `<div class="truel is-${s.phase.toLowerCase()}">${out}</div>`;  // null in the lobby: the TV shows the room
    },

    onState(prev, next, a) {
      api = a;
      if (!next || !prev) return;
      const myId = next.me && next.me.id;
      if (prev.phase !== next.phase || prev.turn !== next.turn || prev.showdown !== next.showdown) {
        if (next.phase === 'PLAN') {
          if (next.showdown !== prev.showdown) { a.sfx('standoff'); a.vibrate([60, 40, 60]); } else a.vibrate(40);
        }
        if (next.phase === 'REVEAL') {
          // the shots ring out one by one, as the lines appear
          (next.events || []).forEach((e, i) => { const n = EVENT_SFX[e.k]; if (n) a.sfx(n, i * STEP); });
          const hitMe = (next.events || []).findIndex(e => hurts(e, myId));
          const died = (next.events || []).some(e => e.k === 'dead' && e.a === myId);
          if (hitMe >= 0) setTimeout(() => a.vibrate(died ? [300, 100, 500] : [200, 80, 200]), hitMe * STEP);
          // the speaker serves the whole room: a lone survivor gets a fanfare, a draw or a massacre a plain sting
          if (next.showdownOver) a.sfx(next.players.filter(p => p.alive).length === 1 ? 'win' : 'reveal', (next.events || []).length * STEP + 300);
        }
        if (next.phase === 'OVER') a.sfx('win');
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
          if (v !== 'SHOOT') { draft.target = null; if (needsShot(draft.card)) { draft.card = null; draft.cardIdx = -1; } }
          return a.render();
        case 'card':
          if (draft.cardIdx === +el.dataset.i) { draft.card = null; draft.cardIdx = -1; }
          else { draft.card = v; draft.cardIdx = +el.dataset.i; }
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
