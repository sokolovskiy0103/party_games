// «Піца-шатл» UI. Rendered by the shell; game state is api.g.
//
// Rendering note: the shell merges markup into the DOM by position + first class + data-k, and a node that has to
// move is re-inserted, which restarts its CSS animations. So the play screen is a fixed list of keyed slots
// (head, route, command, note, panel, crisis) that are always present: a new command or a hazard note changes what
// is *inside* a slot and never moves the panel. Every element keeps a stable first class.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  /** Optimistic values of stateful controls: id -> { v, t, dragging }, so a push in flight doesn't snap them back. */
  const pending = {};
  /** The control being dragged: { kind, c, el, … }. */
  let drag = null;
  /** The hold button being pressed: { c, timer }. */
  let hold = null;
  /** Safety covers flipped open: control id -> time they close again. */
  const coverOpen = {};
  /** One-shot instruments flash after a press: 'id' (the plate) and 'id:v' (the key) -> time it goes dark. */
  const lit = {};
  const LIT_MS = 550;
  const isLit = k => (lit[k] || 0) > Date.now();
  /** Crank turned so far (degrees, signed), joystick offset, cord pulled: per control while it is being worked. */
  const crank = {}, joy = {}, pull = {};
  /** Sauce being wiped: { id, blobs }; meteors shot: { id, hit: Set }. */
  let wipe = null, rocks = null;
  let raf = 0;

  const HOLD_MS = 1400;
  const PENDING_MS = 1500;
  const COVER_MS = 3500;

  const g = () => api.g;
  const me = () => g() && g().me;
  const nameOf = id => ((g().players.find(p => p.id === id) || {}).name || '?');
  const ctl = id => me() && me().panel && me().panel.find(x => x.id === id);
  const val = c => (pending[c.id] ? pending[c.id].v : c.value);
  const schedule = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; api.render(); }); };

  // deterministic layout from an id (sauce splats, meteors): the same on every render
  function rng(seed) {
    let a = (seed * 2654435761) >>> 0;
    return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  }

  // ---------- art ----------
  const SVG = {
    ship: `<svg class="pz-svg ship" viewBox="0 0 120 48" aria-hidden="true">
      <defs><linearGradient id="pzFl" x1="0" x2="1"><stop offset="0" stop-color="#ff4fa3" stop-opacity="0"/><stop offset=".5" stop-color="#ff4fa3"/><stop offset="1" stop-color="#ffcf3d"/></linearGradient>
      <linearGradient id="pzHull" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#b9b2e6"/></linearGradient></defs>
      <path class="flame" d="M0 24 C12 15 20 18 26 24 C20 30 12 33 0 24Z" fill="url(#pzFl)"/>
      <path d="M40 14 L30 2 H48 L62 14Z M40 34 L30 46 H48 L62 34Z" fill="#ff4fa3"/>
      <path d="M24 14 H78 C98 14 114 19 118 24 C114 29 98 34 78 34 H24 Z" fill="url(#pzHull)" stroke="#1b1238" stroke-width="2"/>
      <path d="M24 29 H98 C93 32 86 34 78 34 H24Z" fill="#ff4fa3"/>
      <path d="M101 17.5 C108 18.5 113 21 115.5 24 H101Z" fill="#3de0ff"/>
      <circle cx="72" cy="22" r="3.2" fill="#3de0ff"/><circle cx="84" cy="22" r="3.2" fill="#3de0ff"/>
      <path d="M38 17.5 L54 24 L38 27.5Z" fill="#ffcf3d"/><circle cx="43" cy="22.5" r="1.6" fill="#e8333f"/><circle cx="47" cy="24.5" r="1.3" fill="#e8333f"/></svg>`,
    pizza: `<svg class="pz-svg pizza" viewBox="0 0 32 32" aria-hidden="true"><path d="M3 7 Q16 0 29 7 L16 31Z" fill="#ffcf3d"/>
      <path d="M3 7 Q16 0 29 7 L27.6 10 Q16 4 4.4 10Z" fill="#c9852f"/><circle cx="12" cy="13" r="2.6" fill="#e8333f"/><circle cx="19.5" cy="14.5" r="2.2" fill="#e8333f"/><circle cx="15.5" cy="21" r="2" fill="#e8333f"/></svg>`,
    frozen: `<svg class="pz-svg pizza frozen" viewBox="0 0 32 32" aria-hidden="true"><path d="M3 7 Q16 0 29 7 L16 31Z" fill="#bfeaff"/>
      <path d="M3 7 Q16 0 29 7 L27.6 10 Q16 4 4.4 10Z" fill="#8fb8d8"/><circle cx="12" cy="13" r="2.6" fill="#7aa7d6"/><circle cx="19.5" cy="14.5" r="2.2" fill="#7aa7d6"/>
      <path d="M8 10 L9 15 L10 10 M14 11 L15 18 L16 11 M21 11 L22 16 L23 11" stroke="#e8fbff" stroke-width="1.4" fill="none"/></svg>`,
    planet: `<svg class="pz-svg planet" viewBox="0 0 32 32" aria-hidden="true"><defs><radialGradient id="pzPl" cx=".35" cy=".35"><stop offset="0" stop-color="#9ff3ff"/><stop offset="1" stop-color="#2b6fd6"/></radialGradient></defs>
      <circle cx="16" cy="16" r="9" fill="url(#pzPl)"/><ellipse cx="16" cy="17" rx="15" ry="4.2" fill="none" stroke="#ffcf3d" stroke-width="1.8" transform="rotate(-18 16 17)"/>
      <path d="M13 10 h6 v4 h-6z" fill="#ff4fa3" opacity=".9"/></svg>`,
    thermo: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 4a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0Z" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="17" r="2" fill="currentColor"/></svg>`,
    CHEESE: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 15a4 4 0 0 1 1-7.9A5 5 0 0 1 16.6 6 3.5 3.5 0 0 1 18 15Z" fill="currentColor"/><path d="M8 15v3M12 15v5M16 15v2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>`,
    GLITCH: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21V11M8 21h8M12 11l-4-7M12 11l4-7" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"/><path d="M17 9l2 2-2 2 2 2M7 9 5 11l2 2-2 2" stroke="currentColor" stroke-width="1.6" fill="none"/></svg>`,
    SAUCE: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3c3 4.5 6 7.6 6 11a6 6 0 0 1-12 0c0-3.4 3-6.5 6-11Z" fill="currentColor"/><path d="M9.5 14.5a2.5 2.5 0 0 0 2.5 2.5" stroke="#fff" stroke-opacity=".6" stroke-width="1.5" fill="none"/></svg>`,
    BREAK: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 5.5a4 4 0 0 0 5 5L11 19a2.1 2.1 0 0 1-3-3Z" fill="currentColor"/><path d="M4 4l3 1-1 2 3 1" stroke="currentColor" stroke-width="1.6" fill="none"/></svg>`,
    SHUFFLE: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h4l10 10h4M3 17h4l3-3M14 10l3-3h4M18 4l3 3-3 3M18 14l3 3-3 3" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
    GRAVITY: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 12a1.5 1.5 0 1 1 1.5 1.5A3 3 0 0 1 10.5 10.5 4.5 4.5 0 0 1 15 6a6 6 0 0 1 6 6 7.5 7.5 0 0 1-7.5 7.5A9 9 0 0 1 3 10.5" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"/></svg>`,
    ASTEROIDS: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M2 4l7 5M5 2l6 6M2 9l5 2" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" opacity=".7"/><path d="M11 9l5-2 5 4 0 6-4 4-6-1-2-6Z" fill="currentColor"/></svg>`,
    rock: `<svg viewBox="0 0 40 40" aria-hidden="true"><path d="M8 14 18 5l12 3 6 11-4 12-12 5-11-6-3-10Z" fill="#7c6a8f" stroke="#2a1f3d" stroke-width="2"/>
      <circle cx="16" cy="16" r="3.4" fill="#5a4a6e"/><circle cx="25" cy="25" r="4.4" fill="#5a4a6e"/><circle cx="26" cy="13" r="2" fill="#5a4a6e"/><path d="M12 10 18 7" stroke="#b9a9cc" stroke-width="2" stroke-linecap="round"/></svg>`,
    wrench: `<svg class="pz-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 5.5a4 4 0 0 0 5 5L11 19a2.1 2.1 0 0 1-3-3Z" fill="currentColor"/></svg>`,
  };

  const DIFF = { EASY: 'Легко', NORMAL: 'Норм', CHAOS: 'Хаос' };
  /** Instrument names for «what's new», and the level they appear on (Control.Type in Kotlin). */
  const TYPES = {
    BUTTON: ['Кнопки', 1], TOGGLE: ['Тумблери', 1], SEG: ['Перемикачі', 1], COVER: ['Кнопки під кришкою', 1],
    SLIDER: ['Повзунки', 2], DIAL: ['Ручки', 2], ROCKER: ['Клавіші на три положення', 2], FADER: ['Фейдери', 2],
    KEYPAD: ['Кнопкові пульти', 3], HOLD: ['Кнопки «тримай»', 3], COUNTER: ['Лічильники', 3], PLUG: ['Штекери', 3],
    COLORS: ['Кольорові кнопки', 4], JOYSTICK: ['Джойстики', 4], PULL: ['Шнури', 4], CRANK: ['Рукоятки', 4],
  };
  /** Hazards: name, what it does, first level (Hazard in Kotlin; one level earlier on chaos). */
  const HAZARDS = {
    CHEESE: ['Сирний туман', 'заліпив пульт', 2],
    GLITCH: ['Перешкоди в ефірі', 'глушать команди', 2],
    SAUCE: ['Соусний потоп', 'залив пульт — витирай пальцем!', 2],
    BREAK: ['Поломка', 'прилад іскрить — тисни, щоб полагодити', 3],
    SHUFFLE: ['Трясовина', 'прилади злетіли з місць', 3],
    GRAVITY: ['Гравітаційна яма', 'усі тримайте піцу!', 3],
    ASTEROIDS: ['Метеоритний дощ', 'збийте всі камені!', 4],
  };
  const TYPE_STATEFUL = { TOGGLE: 1, SEG: 1, SLIDER: 1, DIAL: 1, ROCKER: 1, FADER: 1, COUNTER: 1, PLUG: 1 };
  const WIDE = { SEG: 1, SLIDER: 1, KEYPAD: 1, COLORS: 1, PLUG: 1 };
  const shift = s => (s.difficulty === 'CHAOS' ? 1 : 0);
  const hazardIcon = k => `<span class="pz-hz-ico k-${k}">${SVG[k]}</span>`;

  // ---------- pieces ----------
  function header() {
    const s = g();
    const h = s.hazard;
    let mid = `<span class="pz-addr">${esc(s.address)}</span>`;
    if (h && s.phase === 'PLAY') {
      const mine = me() && h.who === me().id;
      mid = `<span class="pz-addr hz ${mine ? 'mine' : ''}" data-k="hz-${h.k}-${h.until}">${hazardIcon(h.k)}<b>${HAZARDS[h.k][0]}</b>${h.who ? ` · ${mine ? 'у тебе!' : esc(nameOf(h.who))}` : ''}</span>`;
    }
    return `<div class="pz-head" data-k="head">
      <span class="pz-lvl">Доставка ${s.level}</span>
      ${mid}
      <span class="pz-temp ${s.temp <= 30 ? 'cold' : ''}">${SVG.thermo}<b>${s.temp}°</b></span></div>`;
  }

  /** The delivery bar: the shuttle flies from the pizzeria to the client's planet. */
  function route(big) {
    const s = g();
    const p = Math.min(1, s.progress / s.goal);
    return `<div class="pz-route ${big ? 'big' : ''}" data-k="route">
      <span class="pz-end">${SVG.pizza}</span>
      <div class="pz-track"><i style="width:${p * 100}%"></i><span class="pz-ship" style="--p:${p}">${SVG.ship}</span></div>
      <span class="pz-end">${SVG.planet}</span>
      <span class="pz-count">${s.progress}<small>/${s.goal}</small></span></div>`;
  }

  function thermo(big) {
    const s = g();
    const pct = Math.max(0, Math.min(100, s.temp / s.maxTemp * 100));
    return `<div class="pz-thermo ${big ? 'big' : ''} ${s.temp <= 30 ? 'cold' : ''}">
      <div class="pz-tube"><i style="height:${pct}%"></i></div><b>${s.temp}°</b></div>`;
  }

  /** What [level] brings: new instruments and hazards. */
  function novelties(level, s) {
    const types = Object.keys(TYPES).filter(t => TYPES[t][1] === level);
    const sh = shift(s);
    const haz = Object.keys(HAZARDS).filter(h => (level === 1 ? HAZARDS[h][2] <= 1 + sh : HAZARDS[h][2] === level + sh));
    if (!types.length && !haz.length) return '';
    return `<div class="pz-new">${types.map(t => `<span class="pz-chip">${TYPES[t][0]}</span>`).join('')}${haz.map(h => `<span class="pz-chip hz">${hazardIcon(h)}${HAZARDS[h][0]}</span>`).join('')}</div>`;
  }

  function command() {
    const s = g();
    const m = me();
    let inner;
    if (!s.started) {
      inner = `<div class="card pz-cmd intro" data-k="intro-${s.level}">
        <div class="pz-intro-row"><span class="pz-cmd-sub">Знайомтеся з пультами · старт за</span>${ui.timer({ deadline: s.startAt, low: 4000, fmt: 'sec', cls: 'pz-countdown' })}</div>
        ${novelties(s.level, s)}</div>`;
    } else if (m && m.cmd) {
      const c = m.cmd;
      inner = `<div class="card pz-cmd live ${m.glitch ? 'glitchy' : ''} ${c.text.length > 44 ? 'long' : ''}" data-k="cmd-${c.id}">
        <div class="pz-cmd-text">${esc(c.text)}</div>
        ${ui.timerBar({ deadline: c.deadline, total: c.total, low: Math.min(4000, c.total / 3) })}</div>`;
    } else inner = `<div class="card pz-cmd idle" data-k="idle"><div class="pz-cmd-sub">Зачекай на команду…</div></div>`;
    return `<div class="pz-slot cmd" data-k="cmd">${inner}</div>`;
  }

  // ---------- instruments ----------
  function range(c) { const r = []; for (let i = c.min; i <= c.max; i++) r.push(i); return r; }

  function body(c) {
    const v = val(c);
    const id = c.id;
    const press = (vv, cls, inner, extra = '') => `<button class="${cls}" data-act="press" data-c="${id}" data-v="${vv}" ${extra}>${inner}</button>`;
    switch (c.type) {
      case 'BUTTON':
        return `<div class="pz-bezel ${isLit(id) ? 'lit' : ''}">${press(0, 'pz-dome red', '', `aria-label="${esc(c.name)}"`)}</div>`;
      case 'COVER': {
        const open = (coverOpen[id] || 0) > Date.now();
        return `<div class="pz-coverbox ${open ? 'open' : ''}"><div class="pz-bezel ${isLit(id) ? 'lit' : ''}">${press(0, 'pz-dome red', '', `aria-label="${esc(c.name)}"`)}</div>
          <button class="pz-lid" data-act="lid" data-c="${id}" aria-label="Відкинути кришку"><i></i></button></div>`;
      }
      case 'TOGGLE':
        return press(v ? 0 : 1, `pz-toggle ${v ? 'on' : ''}`,
          `<span class="pz-led"></span><span class="pz-tg-lbl t-on">ВКЛ</span><span class="pz-tg-base"><span class="pz-tg-bat"></span></span><span class="pz-tg-lbl t-off">ВИКЛ</span>`);
      case 'ROCKER':
        return `<div class="pz-rocker" style="--r:${v}"><span class="pz-rk-paddle"></span>
          ${press(2, 'pz-rk-zone up', '<i></i>', 'aria-label="вгору"')}${press(1, 'pz-rk-zone mid', '<i></i>', 'aria-label="в центр"')}${press(0, 'pz-rk-zone down', '<i></i>', 'aria-label="вниз"')}</div>`;
      case 'SEG':
        return `<div class="pz-seg">${c.opts.map((o, i) => press(i, `pz-key ${i === v ? 'on' : ''}`, `<span class="pz-led"></span><span class="pz-key-t">${esc(o)}</span>`)).join('')}</div>`;
      case 'SLIDER': {
        const p = (v - c.min) / (c.max - c.min);
        return `<div class="pz-slider" data-drag="slider" data-c="${id}" style="--p:${p}">
          <div class="pz-rail"><i></i></div><span class="pz-cap"></span>
          <div class="pz-marks">${range(c).map(i => `<span class="pz-mk ${i === v ? 'on' : ''}">${i}</span>`).join('')}</div></div>`;
      }
      case 'FADER': {
        const p = (v - c.min) / (c.max - c.min);
        return `<div class="pz-fader" data-drag="fader" data-c="${id}" style="--p:${p}">
          <span class="pz-fd-slot"></span><span class="pz-fd-cap"></span>
          <div class="pz-fd-scale">${range(c).reverse().map(i => `<span class="pz-mk ${i === v ? 'on' : ''}">${i}</span>`).join('')}</div></div>`;
      }
      case 'DIAL': {
        const n = c.max - c.min;
        const ang = -135 + 270 * (v - c.min) / n;
        return `<div class="pz-dial" data-drag="dial" data-c="${id}">
          ${range(c).map(i => { const a = (-135 + 270 * (i - c.min) / n) * Math.PI / 180;
            return `<span class="pz-num ${i === v ? 'on' : ''}" style="left:${50 + 45 * Math.sin(a)}%;top:${50 - 45 * Math.cos(a)}%">${i}</span>`; }).join('')}
          <span class="pz-knob" style="--a:${ang}deg"><i></i></span></div>`;
      }
      case 'COUNTER':
        return `<div class="pz-counter">${press(Math.max(c.min, v - 1), 'pz-cn-btn', '<i class="pz-minus"></i>', 'aria-label="менше"')}
          <div class="pz-odo"><div class="pz-odo-strip" style="--v:${v}">${range(c).map(i => `<span class="pz-odo-d">${i}</span>`).join('')}</div></div>
          ${press(Math.min(c.max, v + 1), 'pz-cn-btn', '<i class="pz-plus"></i>', 'aria-label="більше"')}</div>`;
      case 'PLUG': {
        const x = (v - 0.5) * 25;
        return `<div class="pz-plug">
          <svg class="pz-cable" viewBox="0 0 100 44" preserveAspectRatio="none" aria-hidden="true"><path d="M-2 43 C ${x * 0.4} 43, ${x} 38, ${x} 22"/></svg>
          <div class="pz-sockets">${range(c).map(i => press(i, `pz-socket ${i === v ? 'on' : ''}`, `<span class="pz-hole"></span><span class="pz-sock-n">${i}</span>`)).join('')}</div>
          <span class="pz-jack" style="left:${x}%"></span></div>`;
      }
      case 'KEYPAD':
        return `<div class="pz-keys">${range(c).map(i => press(i, `pz-pad ${isLit(id + ':' + i) ? 'lit' : ''}`, `${i}`)).join('')}</div>`;
      case 'HOLD':
        return `<button class="pz-hold ${hold && hold.c === id ? 'holding' : ''} ${isLit(id) ? 'lit' : ''}" data-hold="${id}" style="--hold:${HOLD_MS}ms">
          <span class="pz-hold-ring"></span><span class="pz-hold-t">ТРИМАЙ</span></button>`;
      case 'COLORS':
        return `<div class="pz-colors">${c.opts.map((o, i) => `<span class="pz-bezel sm ${isLit(id + ':' + i) ? 'lit' : ''}">${press(i, 'pz-dome', '', `style="--c:${c.css[i]}" aria-label="${esc(o)}"`)}</span>`).join('')}</div>`;
      case 'JOYSTICK': {
        const j = joy[id] || { x: 0, y: 0 };
        return `<div class="pz-joy" data-drag="joy" data-c="${id}" style="--x:${j.x};--y:${j.y}">
          <span class="pz-joy-gate"></span><i class="pz-ar l"></i><i class="pz-ar u"></i><i class="pz-ar r"></i><i class="pz-ar d"></i>
          <span class="pz-joy-stick ${drag && drag.c === id ? 'held' : ''}"></span></div>`;
      }
      case 'PULL': {
        const p = pull[id] || 0;
        return `<div class="pz-pull" data-drag="pull" data-c="${id}" style="--p:${p}">
          <span class="pz-pull-slot"></span><span class="pz-pull-cord"></span><span class="pz-pull-handle ${drag && drag.c === id ? 'held' : ''}"></span></div>`;
      }
      case 'CRANK': {
        const a = crank[id] || 0;
        return `<div class="pz-crank" data-drag="crank" data-c="${id}" style="--a:${a}deg;--f:${Math.min(1, Math.abs(a) / 360)}">
          <span class="pz-crank-ring"></span><span class="pz-crank-arm"><i></i></span><span class="pz-crank-hub"></span></div>`;
      }
    }
    return '';
  }

  function control(c) {
    const broken = c.broken ? `<button class="pz-broken" data-act="fix" data-c="${c.id}" aria-label="Полагодити">
      <span class="pz-sparks"><i></i><i></i><i></i></span>${SVG.wrench}<b>Лагодь</b><span class="pz-fix-n">${c.broken}</span></button>` : '';
    return `<div class="pz-ctl t-${c.type.toLowerCase()} ${WIDE[c.type] ? 'wide' : ''} ${c.broken ? 'is-broken' : ''} ${isLit(c.id) ? 'lit' : ''}" data-k="${c.id}">
      <div class="pz-name">${esc(c.name)}</div><div class="pz-body">${body(c)}</div>${broken}</div>`;
  }

  function sauceLayer(m) {
    const sc = m.sauce;
    if (!sc) return '';
    if (!wipe || wipe.id !== sc.id) {
      const r = rng(sc.id);
      wipe = { id: sc.id, sent: false, blobs: Array.from({ length: sc.n }, () => ({ x: 12 + r() * 76, y: 10 + r() * 80, s: 24 + r() * 16, rot: r() * 360, dirt: 1 })) };
    }
    return `<div class="pz-sauce" data-k="sauce-${sc.id}">${wipe.blobs.map((b, i) =>
      `<i class="pz-blob ${b.dirt <= 0 ? 'gone' : ''}" data-blob="${i}" style="left:${b.x}%;top:${b.y}%;--s:${b.s}%;--r:${b.rot}deg;opacity:${Math.max(0, b.dirt).toFixed(2)}"></i>`).join('')}
      <span class="pz-sauce-hint">Витирай пальцем!</span></div>`;
  }

  function rockLayer(m) {
    const cr = g().crisis;
    if (!cr || cr.k !== 'ASTEROIDS' || m.crisisLeft == null) return '';
    if (!rocks || rocks.id !== cr.id) {
      const r = rng(cr.id * 7 + 3);
      rocks = { id: cr.id, hit: new Set(), list: Array.from({ length: cr.per }, () => ({ x: 8 + r() * 76, y: 6 + r() * 78, d: r() * 2, dx: (r() - 0.5) * 30, s: 56 + r() * 20 })) };
    }
    const done = m.crisisLeft === 0;
    const hud = `<div class="pz-rocks-hud">${hazardIcon('ASTEROIDS')}<b>${done ? `Чисто! Чекаємо інших · ${cr.done.length}/${cr.crew}` : `Збий метеорити: ${m.crisisLeft}`}</b>
      ${ui.timerBar({ deadline: cr.deadline, total: cr.total, low: 2500 })}</div>`;
    return `<div class="pz-rocks" data-k="rocks-${cr.id}">${hud}${rocks.list.map((k, i) => {
      const hit = done || rocks.hit.has(i);
      return `<button class="pz-rock ${hit ? 'boom' : ''}" data-act="rock" data-i="${i}" style="left:${k.x}%;top:${k.y}%;--d:${k.d}s;--dx:${k.dx}px;--s:${k.s}px" aria-label="Метеорит">${SVG.rock}</button>`;
    }).join('')}</div>`;
  }

  function panel() {
    const m = me();
    const s = g();
    if (!m || !m.panel) return '';
    const fog = m.cheese ? `<div class="pz-fog" data-k="fog"><i></i><i></i><i></i></div>` : '';
    return `<div class="pz-panel ${s.started ? '' : 'locked'} ${m.cheese ? 'foggy' : ''} ${m.shake ? 'shaking' : ''}" data-k="panel-${s.level}">${m.panel.map(control).join('')}${fog}${sauceLayer(m)}${rockLayer(m)}</div>`;
  }

  function crisisSlot() {
    const s = g();
    const cr = s.crisis;
    let inner = '';
    if (cr && cr.k === 'GRAVITY' && me() && me().crisisLeft != null) {
      const held = me().crisisLeft === 0;
      inner = `<div class="pz-gravity" data-k="grav-${cr.id}"><div class="pz-grav-box">
        <div class="pz-grav-title">${SVG.GRAVITY}<span>Гравітаційна яма!</span></div>
        ${held
          ? `<div class="pz-grav-held">Тримаєш! Чекаємо інших · ${cr.done.length}/${cr.crew}</div>`
          : `<button class="pz-brace" data-act="brace"><span>ТРИМАЙ<br>ПІЦУ!</span></button>`}
        ${ui.timerBar({ deadline: cr.deadline, total: cr.total, low: 2000 })}</div></div>`;
    }
    return `<div class="pz-slot crisis" data-k="crisis">${inner}</div>`;
  }

  // ---------- views ----------
  function deliveries(n) {
    const d = n % 10, dd = n % 100;
    if (d === 1 && dd !== 11) return 'доставка';
    if (d >= 2 && d <= 4 && (dd < 12 || dd > 14)) return 'доставки';
    return 'доставок';
  }

  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card hero pz-intro"><div class="pz-logo">${SVG.ship}${SVG.pizza}</div>
      <div>Ви — екіпаж піца-шатла. У кожного свій пульт, а <b>команди з’являються на чужих екранах</b>.
      Кричи, що бачиш, і крути, що чуєш, поки піца не охолола!</div></div>`;
    h += `<div class="card stack"><b>Складність</b>
      ${api.isHost
        ? `<div class="seg">${Object.keys(DIFF).map(k => `<button class="pz-diff ${s.difficulty === k ? 'on' : ''}" data-act="difficulty" data-v="${k}">${DIFF[k]}</button>`).join('')}</div>`
        : `<div class="chips"><span class="chip on">${DIFF[s.difficulty]}</span></div>`}
      <div class="muted small">${s.difficulty === 'CHAOS' ? 'Менше часу на команду, негаразди з першої доставки.' : s.difficulty === 'EASY' ? 'Більше часу на команду і рідші негаразди.' : 'Негаразди — з другої доставки.'}</div>
      ${s.best ? `<div class="muted small">Рекорд: <b>${s.best}</b> ${deliveries(s.best)}</div>` : ''}</div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Екіпаж · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Злітаємо!</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function playView() {
    const m = me();
    const h = header() + route(false);
    if (!m) return h + `<div class="card flat center muted" data-k="watch">Ти дивишся збоку. Підказуй екіпажу!</div>`;
    return h + command() + panel() + crisisSlot();
  }

  function crew(sorted) {
    const s = g();
    const ps = sorted ? [...s.players].sort((a, b) => b.done - a.done) : s.players;
    return `<div class="card">${ps.map(p => `<div class="li ${me() && p.id === me().id ? 'now' : ''}" data-k="${esc(p.id)}">
      <span class="dot ${p.connected ? '' : 'off'}"></span><span class="name">${esc(p.name)}</span>
      <span class="small muted">виконано</span><span class="pts">${p.done}</span></div>`).join('')}</div>`;
  }

  function nextNews(s) {
    const n = novelties(s.level + 1, s);
    return n ? `<div class="stack"><div class="small muted">Далі на вас чекає:</div>${n}</div>` : '<div class="small c-accent">Далі — швидше й хаотичніше!</div>';
  }

  function warpView() {
    const s = g();
    let h = `<div class="pz-warp" aria-hidden="true">${'<i></i>'.repeat(14)}</div>`;
    h += `<div class="card pz-banner good"><div class="pz-banner-art">${SVG.pizza}</div><h1>Доставлено!</h1><div class="muted">${esc(s.address)}</div></div>`;
    if (s.line) h += `<div class="card flat center pz-line">«${esc(s.line)}»</div>`;
    h += `<div class="card pz-stats">${thermo(false)}<div class="stack grow">
      <div><b>${s.delivered}</b> ${deliveries(s.delivered)} поспіль</div>
      <div class="muted small">Піцу трохи підігріли: +20°</div>${nextNews(s)}</div></div>`;
    h += crew(true);
    h += api.isHost
      ? `<button class="big" data-act="next">Наступна доставка</button>`
      : '<div class="muted center small">Хост запустить наступну доставку.</div>';
    return h;
  }

  function overView() {
    const s = g();
    let h = `<div class="card pz-banner bad"><div class="pz-banner-art">${SVG.frozen}</div><h1>Піца охолола!</h1>${s.line ? `<div class="muted">${esc(s.line)}</div>` : ''}</div>`;
    const rec = s.delivered > 0 && s.delivered >= s.best;
    h += `<div class="card center stack"><div class="pz-big">${s.delivered}</div><div>${deliveries(s.delivered)} · ${s.totalDone} команд виконано</div>
      ${rec ? '<div class="c-good"><b>Новий рекорд!</b></div>' : s.best ? `<div class="muted small">Рекорд: ${s.best}</div>` : ''}</div>`;
    h += crew(true);
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне нову гру.</div>';
    return h;
  }

  // ---------- TV ----------
  function tvHazard() {
    const s = g();
    const h = s.hazard;
    let inner = '';
    if (h) {
      const cr = s.crisis;
      inner = `<div class="pz-note big ${h.who ? '' : 'crisis'}" data-k="tvhz-${h.k}-${h.until}">${hazardIcon(h.k)}
        <div class="grow"><b>${HAZARDS[h.k][0]}</b>${h.who ? ` у <b>${esc(nameOf(h.who))}</b>` : ''}: ${HAZARDS[h.k][1]}
        ${cr ? `<div class="small">${cr.done.length}/${cr.crew} готові</div>${ui.timerBar({ deadline: cr.deadline, total: cr.total, low: 2000 })}` : ''}</div></div>`;
    }
    return `<div class="pz-slot tvhz" data-k="tvhz">${inner}</div>`;
  }

  function tvPlay() {
    const s = g();
    const pct = Math.min(100, Math.round(s.progress / s.goal * 100));
    let main = `<div class="pz-tv-head" data-k="tvhead"><span class="pz-lvl">Доставка ${s.level}</span><h1>${esc(s.address)}</h1></div>`;
    main += route(true);
    main += s.started
      ? `<div class="pz-stage" data-k="stage"><div class="pz-stage-ship">${SVG.ship}</div><div>
          <div class="pz-stage-pct">${pct}%</div><div class="muted">шляху до клієнта · кричіть команди!</div></div></div>`
      : `<div class="pz-stage intro" data-k="stage-intro"><div class="stack"><div class="muted">Старт за</div>${ui.timer({ deadline: s.startAt, low: 4000, fmt: 'sec', cls: 'pz-countdown' })}</div>${novelties(s.level, s)}</div>`;
    main += tvHazard();
    return `<div class="tv-split"><div class="stack">${main}</div>
      <div class="stack"><div class="pz-tv-side">${thermo(true)}<div class="stack grow"><h2>Екіпаж</h2>${crew(false)}</div></div></div></div>`;
  }

  function tvWarp() {
    const s = g();
    return `<div class="pz-warp" aria-hidden="true">${'<i></i>'.repeat(20)}</div>
      <div class="tv-split"><div class="stack">
        <div class="card pz-banner good"><div class="pz-banner-art">${SVG.pizza}</div><h1>Доставлено!</h1><div class="muted">${esc(s.address)}</div></div>
        ${s.line ? `<div class="card flat pz-line center">«${esc(s.line)}»</div>` : ''}${nextNews(s)}</div>
      <div class="stack"><div class="pz-tv-side">${thermo(true)}<div class="stack grow"><h2>Найкращі руки</h2>${crew(true)}</div></div></div></div>`;
  }

  function tvOver() {
    const s = g();
    return `<div class="tv-split"><div class="stack center">
        <div class="card pz-banner bad"><div class="pz-banner-art">${SVG.frozen}</div><h1>Піца охолола!</h1>${s.line ? `<div class="muted">${esc(s.line)}</div>` : ''}</div>
        <div class="pz-big">${s.delivered}</div><div>${deliveries(s.delivered)} · ${s.totalDone} команд</div>
        ${s.delivered > 0 && s.delivered >= s.best ? '<div class="c-good"><b>Новий рекорд!</b></div>' : ''}</div>
      <div class="stack"><h2>Екіпаж</h2>${crew(true)}</div></div>`;
  }

  // ---------- input ----------
  function send(cid, v) {
    const c = ctl(cid);
    if (!c || c.broken) return;
    if (TYPE_STATEFUL[c.type]) {
      if (val(c) === v && c.value === v) return;
      pending[cid] = { v, t: Date.now() };
    } else {
      // one-shot: the plate and the pressed key light up for a moment, so a press is obvious
      lit[cid] = lit[cid + ':' + v] = Date.now() + LIT_MS;
      setTimeout(schedule, LIT_MS + 20);
    }
    api.vibrate(12);
    api.send({ t: 'set', c: cid, v });
    schedule();
  }

  const live = () => document.body.dataset.game === 'pizza' && g() && g().phase === 'PLAY' && g().started;

  function angleOf(e, r) { return Math.atan2(e.clientX - (r.left + r.width / 2), -(e.clientY - (r.top + r.height / 2))) * 180 / Math.PI; }

  /** A dragged value control (slider, fader, dial): the value under the finger. */
  function dragValue(e) {
    const c = ctl(drag.c);
    if (!c) return null;
    let f;
    if (drag.kind === 'slider') { const r = drag.el.querySelector('.pz-rail').getBoundingClientRect(); f = (e.clientX - r.left) / r.width; }
    else if (drag.kind === 'fader') { const r = drag.el.querySelector('.pz-fd-slot').getBoundingClientRect(); f = 1 - (e.clientY - r.top) / r.height; }
    else f = (Math.max(-135, Math.min(135, angleOf(e, drag.el.getBoundingClientRect()))) + 135) / 270;
    f = Math.max(0, Math.min(1, f));
    return c.min + Math.round(f * (c.max - c.min));
  }

  function startDrag(e, d) {
    const c = ctl(d.dataset.c);
    if (!c || c.broken) return;
    drag = { kind: d.dataset.drag, c: c.id, el: d, v: null, x0: e.clientX, y0: e.clientY };
    try { d.setPointerCapture(e.pointerId); } catch (_) { /* old browsers */ }
    if (drag.kind === 'crank') drag.last = angleOf(e, d.getBoundingClientRect());
    moveDrag(e);
  }

  function moveDrag(e) {
    const d = drag;
    if (!d) return;
    if (d.kind === 'slider' || d.kind === 'fader' || d.kind === 'dial') {
      const v = dragValue(e);
      if (v == null || v === d.v) return;
      d.v = v;
      pending[d.c] = { v, t: Date.now(), dragging: true };
      api.vibrate(6);
    } else if (d.kind === 'joy') {
      const lim = d.el.getBoundingClientRect().width * 0.32;
      let x = e.clientX - d.x0, y = e.clientY - d.y0;
      const len = Math.hypot(x, y);
      if (len > lim) { x = x / len * lim; y = y / len * lim; }
      joy[d.c] = { x: +(x / lim).toFixed(2), y: +(y / lim).toFixed(2) };
    } else if (d.kind === 'pull') {
      const h = d.el.getBoundingClientRect().height;
      const p = Math.max(0, Math.min(1, (e.clientY - d.y0) / (h * 0.55)));
      if (p >= 1 && (pull[d.c] || 0) < 1) api.vibrate(20);
      pull[d.c] = +p.toFixed(3);
    } else if (d.kind === 'crank') {
      const a = angleOf(e, d.el.getBoundingClientRect());
      let delta = a - d.last;
      if (delta > 180) delta -= 360;
      if (delta < -180) delta += 360;
      d.last = a;
      const before = crank[d.c] || 0;
      const after = before + delta;
      crank[d.c] = after;
      if (Math.floor(Math.abs(after) / 90) !== Math.floor(Math.abs(before) / 90)) api.vibrate(8);
      if (Math.abs(after) >= 360) {
        delete crank[d.c];
        drag = null;
        api.vibrate([30, 30, 60]);
        send(d.c, 0);
      }
    }
    schedule();
  }

  function endDrag() {
    const d = drag;
    if (!d) return;
    drag = null;
    if (d.kind === 'slider' || d.kind === 'fader' || d.kind === 'dial') {
      const c = ctl(d.c);
      if (d.v != null && c) {
        pending[d.c] = { v: d.v, t: Date.now() };
        if (c.value !== d.v) api.send({ t: 'set', c: d.c, v: d.v });
      }
    } else if (d.kind === 'joy') {
      const j = joy[d.c] || { x: 0, y: 0 };
      delete joy[d.c];
      if (Math.hypot(j.x, j.y) > 0.6) {
        // 0 left, 1 up, 2 right, 3 down (Control.JOY_OPTS)
        send(d.c, Math.abs(j.x) > Math.abs(j.y) ? (j.x < 0 ? 0 : 2) : (j.y < 0 ? 1 : 3));
      }
    } else if (d.kind === 'pull') {
      const p = pull[d.c] || 0;
      delete pull[d.c];
      if (p >= 0.85) send(d.c, 0);
    } else if (d.kind === 'crank') {
      delete crank[d.c];
    }
    schedule();
  }

  function cancelHold() {
    if (!hold) return;
    clearTimeout(hold.timer);
    hold = null;
    schedule();
  }

  /** Rubbing sauce off: every splat under the finger loses some of its dirt. */
  function rub(e) {
    const layer = document.querySelector('.pizza .pz-sauce');
    if (!layer) return;
    const r = layer.getBoundingClientRect();
    const dist = Math.hypot(e.clientX - wipe.px, e.clientY - wipe.py);
    wipe.px = e.clientX; wipe.py = e.clientY;
    let cleaned = false;
    wipe.blobs.forEach((b, i) => {
      if (b.dirt <= 0) return;
      const bx = r.left + r.width * b.x / 100, by = r.top + r.height * b.y / 100, rad = r.width * b.s / 200;
      if (Math.hypot(e.clientX - bx, e.clientY - by) > rad) return;
      b.dirt -= dist / (rad * 4);
      if (b.dirt <= 0) { cleaned = true; api.vibrate(25); }
      const el = layer.querySelector(`[data-blob="${i}"]`);
      if (el) el.style.opacity = Math.max(0, b.dirt).toFixed(2);
    });
    if (cleaned) {
      schedule();
      if (wipe.blobs.every(b => b.dirt <= 0) && !wipe.sent) { wipe.sent = true; api.vibrate([30, 30, 60]); api.send({ t: 'wipe' }); }
    }
  }

  document.addEventListener('pointerdown', e => {
    if (!live()) return;
    const blob = e.target.closest('.pizza .pz-blob');
    if (blob && wipe) {
      wipe.active = true; wipe.px = e.clientX; wipe.py = e.clientY;
      try { blob.parentNode.setPointerCapture(e.pointerId); } catch (_) { /* old browsers */ }
      e.preventDefault();
      return;
    }
    if (e.target.closest('.pz-broken, .pz-rock')) return;
    const d = e.target.closest('.pizza [data-drag]');
    if (d) { startDrag(e, d); e.preventDefault(); return; }
    const hb = e.target.closest('.pizza [data-hold]');
    if (hb) {
      const cid = hb.dataset.hold;
      const c = ctl(cid);
      if (!c || c.broken) return;
      cancelHold();
      hold = { c: cid, timer: setTimeout(() => { hold = null; api.vibrate([30, 30, 60]); send(cid, 0); }, HOLD_MS) };
      hb.classList.add('holding');
      api.vibrate(10);
      e.preventDefault();
    }
  });
  document.addEventListener('pointermove', e => { if (drag) moveDrag(e); else if (wipe && wipe.active) rub(e); });
  const up = () => { endDrag(); cancelHold(); if (wipe) wipe.active = false; };
  document.addEventListener('pointerup', up);
  document.addEventListener('pointercancel', up);
  // long presses must not open the context menu over the instruments
  document.addEventListener('contextmenu', e => { if (e.target.closest && e.target.closest('.pizza .pz-panel')) e.preventDefault(); });

  // ---------- one screen ----------
  /**
   * The flight must fit one screen: the panel takes exactly the height left below it and its rows share it equally
   * (CSS grid). The room an instrument gets (--pz-row × --pz-col, px of a plate's body) goes to CSS, which scales each
   * kind against its own natural size (--nh / --nw in game.css), so a slider shrinks less than a pull cord.
   * Measured after every render and on resize; written on <html>, which the shell's merge doesn't touch.
   */
  function fit() {
    const root = document.documentElement;
    const panel = document.querySelector('.pizza.is-play .pz-panel');
    if (!panel) { root.style.removeProperty('--pz-h'); return; }
    const vh = window.visualViewport ? window.visualViewport.height : window.innerHeight;
    const top = panel.getBoundingClientRect().top + window.scrollY;
    const h = Math.max(220, Math.floor(vh - top - 12));
    if (root.style.getPropertyValue('--pz-h') !== h + 'px') root.style.setProperty('--pz-h', h + 'px');
    const body = panel.querySelector('.pz-ctl .pz-body');
    if (!body) return;
    const narrow = panel.querySelector('.pz-ctl:not(.wide) .pz-body');
    const row = String(body.clientHeight), col = String(narrow ? narrow.clientWidth : 999);
    if (root.style.getPropertyValue('--pz-row') !== row) root.style.setProperty('--pz-row', row);
    if (root.style.getPropertyValue('--pz-col') !== col) root.style.setProperty('--pz-col', col);
  }
  let fitRaf = 0;
  const refit = () => { if (!fitRaf) fitRaf = requestAnimationFrame(() => { fitRaf = 0; fit(); }); };
  window.addEventListener('resize', refit);
  if (window.visualViewport) window.visualViewport.addEventListener('resize', refit);

  // ---------- registration ----------
  PG.register('pizza', {
    // background music per phase (shell.js): full while flying, low while people catch their breath
    music: { WARP: 'low', OVER: 'low' },
    musicLoop: 69.818,  // seconds of music.mp3 that loop: 32 bars at 110 BPM
    sfx: ['done', 'fail', 'alarm', 'warp', 'cold'],  // own effects: sfx/<name>.mp3
    bg: 'loop',  // games/pizza/bg.webm|mp4 (phone), bg-tv.* (TV); bg.webp is the poster

    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'PLAY': h = playView(); break;
        case 'WARP': h = warpView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      refit();
      return `<div class="pizza is-${s.phase.toLowerCase()} stack">${h}</div>`;
    },

    board(a) {
      api = a;
      const s = g();
      if (!s) return null;
      let out = null;
      switch (s.phase) {
        case 'PLAY': out = tvPlay(); break;
        case 'WARP': out = tvWarp(); break;
        case 'OVER': out = tvOver(); break;
      }
      return out && `<div class="pizza tv is-${s.phase.toLowerCase()}">${out}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      // forget optimistic values the server has caught up with (or ignored)
      const panelNow = (next.me && next.me.panel) || [];
      Object.keys(pending).forEach(id => {
        const c = panelNow.find(x => x.id === id);
        const p = pending[id];
        if (!c || (!p.dragging && (c.value === p.v || Date.now() - p.t > PENDING_MS))) delete pending[id];
      });
      if (!(next.me && next.me.sauce)) wipe = null;
      if (!next.crisis) rocks = null;
      if (!prev) return;
      if (next.phase !== prev.phase || next.level !== prev.level) { drag = null; cancelHold(); }

      if (next.seq !== prev.seq) {
        switch (next.last) {
          case 'done': a.sfx('done'); break;
          case 'fail': a.sfx('fail'); break;
          case 'hazard': a.sfx('alarm'); break;
          case 'cleared': case 'fixed': case 'wiped': a.sfx('correct'); break;
          case 'warp': a.sfx('warp'); a.sfx('win', 900); break;
          case 'over': a.sfx('cold'); a.sfx('lose', 1100); break;
          case 'level': a.sfx('whoosh'); break;
        }
      }

      // haptics instead of flashy animations: this phone feels what concerns this player
      if (next.phase !== prev.phase) a.vibrate(next.phase === 'OVER' ? [300, 100, 600] : 120);
      if (next.phase === 'PLAY' && next.started && !prev.started) a.vibrate([60, 40, 120]);
      if (next.crisis && !prev.crisis) a.vibrate([400, 100, 400]);
      const pm = prev.me, nm = next.me;
      if (!pm || !nm) return;
      if (nm.failSeq > pm.failSeq) a.vibrate([180, 60, 180]);
      else if (nm.doneSeq > pm.doneSeq) a.vibrate([30, 40, 30]);
      else if (nm.hitSeq > pm.hitSeq) a.vibrate(40);
      else if (nm.cmd && pm.cmd && nm.cmd.id !== pm.cmd.id) a.vibrate(20);
      const broken = p => (p.panel || []).filter(c => c.broken).length;
      if ((nm.cheese && !pm.cheese) || (nm.glitch && !pm.glitch) || (nm.sauce && !pm.sauce) || (nm.shake && !pm.shake) || broken(nm) > broken(pm)) {
        a.vibrate([60, 40, 60, 40, 60]);
      }
      if (broken(nm) < broken(pm)) a.vibrate([40, 30, 80]);
    },

    menu() { return ['newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      switch (el.dataset.act) {
        case 'difficulty': return a.send({ t: 'difficulty', v: el.dataset.v });
        case 'start': return a.send({ t: 'start' });
        case 'next': return a.send({ t: 'next' });
        case 'brace': a.vibrate(40); return a.send({ t: 'brace' });
        case 'fix': a.vibrate(18); return a.send({ t: 'fix', c: el.dataset.c });
        case 'rock': {
          const i = +el.dataset.i;
          if (!rocks || rocks.hit.has(i)) return;
          rocks.hit.add(i);
          a.vibrate(35);
          a.send({ t: 'rock' });
          return schedule();
        }
        case 'lid': {
          coverOpen[el.dataset.c] = Date.now() + COVER_MS;
          a.vibrate(15);
          setTimeout(schedule, COVER_MS + 30);
          return schedule();
        }
        case 'press': {
          if (!live()) return;
          const c = ctl(el.dataset.c);
          if (c && c.type === 'COVER') delete coverOpen[c.id];
          return send(el.dataset.c, +el.dataset.v);
        }
      }
    },
  });
})();
