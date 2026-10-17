// «Інсайдер» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  const ROLE = {
    MASTER: { tone: 'var(--violet)', name: 'Майстер', what: 'Ти знаєш слово. Відповідай на питання лише «так», «ні» або «не знаю». Коли хтось назве слово — натисни, хто вгадав.' },
    INSIDER: { tone: 'var(--bad)', name: 'Інсайдер', what: 'Ти теж знаєш слово. Непомітно підведи всіх до відповіді — і не видай себе.' },
    COMMON: { tone: 'var(--good)', name: 'Гравець', what: 'Ставте Майстру питання «так/ні» і вгадайте слово. А потім знайдіть Інсайдера.' },
  };

  let revealed = false;      // role card flipped open
  let pickedGuesser = null;  // Master's two-step "who guessed"
  let api = PG.api;

  function eye(cls = 'eye') {
    return `<svg class="${cls}" viewBox="22 30 64 48" aria-hidden="true">
      <path fill="#f5b942" d="M26,54 C34,40 44,34 54,34 C64,34 74,40 82,54 C74,68 64,74 54,74 C44,74 34,68 26,54 Z"/>
      <path fill="#0e1320" d="M32,54 C39,44 46,40 54,40 C62,40 69,44 76,54 C69,64 62,68 54,68 C46,68 39,64 32,54 Z"/>
      <circle fill="#f5b942" cx="54" cy="54" r="12"/>
      <path fill="#0e1320" d="M54,47 C57,47 58.5,49 58.5,51.5 C58.5,53.2 57.6,54.4 56.4,55.1 L57.6,61 L50.4,61 L51.6,55.1 C50.4,54.4 49.5,53.2 49.5,51.5 C49.5,49 51,47 54,47 Z"/></svg>`;
  }

  // ---------- helpers ----------
  const g = () => api.g;
  const me = () => g() && g().me;
  const myRole = () => me() && me().role;
  const isMaster = () => g() && me() && g().master === me().id;
  const canRun = () => g() && (api.isHost || isMaster());
  const nameOf = id => esc(((g() && g().players.find(p => p.id === id)) || {}).name || '?');

  function fmt(ms) {
    const s = Math.ceil(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }
  function timerHtml(total) {
    const t = { deadline: g().deadline, total, low: 30000 };
    return `<div class="stack">${ui.timer(t)}${ui.timerBar(t)}</div>`;
  }

  // ---------- views ----------
  function scoreboard() {
    const s = g();
    const pts = (s.result && s.result.points) || {};
    const ps = [...s.players].sort((a, b) => b.score - a.score);
    return `<div class="card">${ps.map(p => `
      <div class="li">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && me().id === p.id ? ' <span class="muted small">(ти)</span>' : ''}
          ${s.master === p.id && s.phase !== 'LOBBY' ? ' <span class="small c-MASTER">майстер</span>' : ''}</span>
        ${pts[p.id] ? `<span class="plus">+${pts[p.id]}</span>` : ''}
        <span class="pts">${p.score}</span>
      </div>`).join('') || '<div class="muted">Поки нікого</div>'}
    </div>`;
  }

  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    if (api.isHost) {
      const mins = [3, 4, 5, 7, 10];
      h += `<div class="card stack">
        <b>Час на вгадування</b>
        <div class="chips">${mins.map(m => `<button class="chip ${s.questionSeconds === m * 60 ? 'on' : ''}" data-act="seconds" data-v="${m * 60}">${m} хв</button>`).join('')}</div>
        <div class="muted small">Класика — 5 хв. Обговорення триває стільки ж, скільки йшло вгадування.</div>
      </div>`;
    } else {
      h += ui.wait('Чекаємо, поки хост почне раунд', '👑');
    }
    h += `<h2>Гравці · ${n}</h2>${scoreboard()}`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>${s.round ? 'Наступний раунд' : 'Почати гру'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці (зараз ${n}).</div>`;
    }
    return h;
  }

  function roleCard(compact = false) {
    const r = myRole();
    if (!me()) return '';
    if (!r) return `<div class="card flat center muted">Ти приєднався під час раунду — зіграєш у наступному.</div>`;
    const info = ROLE[r];
    return ui.flipCard({ open: revealed, tone: info.tone, back: `
      <div class="title c-${r}">${info.name}</div>
      ${g().word && r !== 'COMMON' ? `<div class="muted small">Слово</div><div class="word">${esc(g().word)}</div>` : ''}
      ${compact ? '' : `<div class="what">${info.what}</div>`}` });
  }

  function rolesView() {
    const s = g();
    const inRound = s.players.filter(p => p.inRound);
    const readyN = inRound.filter(p => p.ready).length;
    const meReady = me() && (s.players.find(p => p.id === me().id) || {}).ready;
    let h = `<div class="muted center">Раунд ${s.round} · Майстер — <b class="c-MASTER">${nameOf(s.master)}</b></div>
      ${roleCard()}`;
    if (myRole() && !meReady) h += `<button class="soft big" data-act="ready" ${revealed ? '' : 'disabled'}>Запам'ятав(ла) ✓</button>`;
    h += `<div class="chips">${inRound.map(p => `<span class="chip ${p.ready ? 'ok' : ''}">${p.ready ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>
      <div class="muted small">Готові ${readyN} з ${inRound.length}</div>`;
    if (canRun()) {
      h += `<div class="row">
        ${isMaster() ? `<button class="ghost grow" data-act="swapWord">Інше слово</button>` : ''}
        <button class="grow" data-act="begin">Старт ⏱</button>
      </div>`;
      if (isMaster()) h += `<div class="muted small">Слово складне? Заміни його до старту — тоді Інсайдеру треба буде подивитися ще раз.</div>`;
    } else {
      h += `<div class="muted small center">Майстер ${nameOf(s.master)} запустить таймер, коли всі будуть готові.</div>`;
    }
    return h;
  }

  function guessPicker(label) {
    const s = g();
    return `<div class="picks two">${s.players.filter(p => p.inRound && p.id !== s.master).map(p =>
        `<button class="pick ${pickedGuesser === p.id ? 'on' : ''}" data-act="pickGuesser" data-pid="${p.id}">${esc(p.name)}</button>`).join('')}</div>
      <button class="big" data-act="guessed" ${pickedGuesser ? '' : 'disabled'}>${pickedGuesser ? `${nameOf(pickedGuesser)} вгадав(ла)!` : label}</button>`;
  }

  function questionsView() {
    const s = g();
    let h = `<div class="center"><span class="tag">вгадуйте слово</span></div>
      ${timerHtml(s.questionSeconds * 1000)}`;
    if (isMaster()) {
      h += `<div class="card center"><div class="muted small">Твоє слово</div><div class="word">${esc(s.word || '')}</div>
        <div class="muted small">Відповідай: так · ні · не знаю</div></div>
        <h2>Хто назвав слово?</h2>${guessPicker('Обери, хто вгадав')}`;
    } else {
      h += `<div class="card center">Ставте питання Майстру <b class="c-MASTER">${nameOf(s.master)}</b>.<br><span class="muted small">Відповіді лише «так», «ні» або «не знаю».</span></div>`;
      h += roleCard(true);
      const master = s.players.find(p => p.id === s.master);
      // Host fallback if the Master's phone is offline.
      if (api.isHost && !(master && master.connected)) {
        h += `<div class="card stack"><b>Майстер не на зв'язку — познач, хто вгадав</b>${guessPicker('Вгадали')}</div>`;
      }
    }
    return h;
  }

  function discussionView() {
    const s = g();
    let h = `<div class="card center stack">
        <div><b>${nameOf(s.guesser)}</b> вгадав(ла) слово</div>
        <div class="word">${esc(s.word || '')}</div>
        <div class="muted small">за ${fmt(s.elapsed)}</div>
      </div>
      <div class="center"><span class="tag">обговорення</span></div>
      ${timerHtml(Math.max(s.elapsed, 30000))}
      <div class="muted center">Хто з вас знав слово з самого початку? Обговоріть, хто міг вести всіх до відповіді.</div>`;
    if (canRun()) h += `<button class="big soft" data-act="endDiscussion">До голосування</button>`;
    h += roleCard(true);
    return h;
  }

  function votedChips() {
    const s = g();
    const voters = new Set(s.voters || []);
    return `<div class="chips">${s.players.filter(p => voters.has(p.id)).map(p =>
      `<span class="chip ${p.voted ? 'ok' : ''}">${p.voted ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>`;
  }

  function vote1View() {
    const s = g();
    const iVote = me() && (s.voters || []).includes(me().id);
    let h = `<div class="center"><span class="tag">голосування 1 з 2</span></div>
      <h1 class="center">${nameOf(s.guesser)} — Інсайдер?</h1>
      <p class="muted center">Якщо більшість скаже «так» — розкриваємо. Якщо «ні» — шукатимемо серед усіх.</p>`;
    if (me() && me().id === s.guesser) {
      h += `<div class="card flat center">Ти вгадав(ла) слово — зараз голосують про тебе. Переконай їх 😉</div>`;
    } else if (iVote) {
      const v = s.myVote;
      h += `<div class="grid2">
        <button class="bad ${v === true ? 'on' : ''}" data-act="vote1" data-yes="1">Так, це Інсайдер</button>
        <button class="good ${v === false ? 'on' : ''}" data-act="vote1" data-yes="0">Ні, не він(вона)</button>
      </div>
      ${v !== undefined ? '<div class="muted small center">Голос прийнято. Можна змінити, поки не проголосували всі.</div>' : ''}`;
    }
    h += votedChips();
    if (api.isHost) h += `<button class="ghost" data-act="closeVote1">Завершити голосування</button>`;
    return h;
  }

  function vote2View() {
    const s = g();
    const cands = s.candidates || [];
    let h = `<div class="center"><span class="tag">голосування 2 з 2</span></div>
      <h1 class="center">Хто Інсайдер?</h1>
      <p class="muted center">Кожен вказує на одного. За рівних голосів вирішує вибір Майстра.</p>`;
    if (me() && (s.voters || []).includes(me().id)) {
      h += `<div class="picks two">${cands.filter(id => id !== me().id).map(id =>
        `<button class="pick ${s.myVote === id ? 'on' : ''}" data-act="vote2" data-pid="${id}">${nameOf(id)}${id === s.guesser ? ' <span class="muted small">вгадав</span>' : ''}</button>`).join('')}</div>`;
      if (s.myVote) h += `<div class="muted small center">Голос прийнято. Можна змінити, поки не проголосували всі.</div>`;
    }
    h += votedChips();
    if (api.isHost) h += `<button class="ghost" data-act="closeVote2">Завершити голосування</button>`;
    return h;
  }

  function voteSummary(r) {
    if (!r || r.outcome === 'NOT_GUESSED') return '';
    const v1 = r.vote1 || {};
    let out = `<div class="muted small">Голосування 1: «так» ${v1.yes || 0}, «ні» ${v1.no || 0}</div>`;
    if (r.decidedBy === 'vote2') {
      const t = r.vote2 || {};
      const parts = Object.keys(t).sort((a, b) => t[b] - t[a]).map(id => `${nameOf(id)} — ${t[id]}`);
      out += `<div class="muted small">Голосування 2: ${parts.join(', ') || 'ніхто не проголосував'}</div>`;
    }
    return out;
  }

  function resultView() {
    const s = g();
    const r = s.result || {};
    const role = myRole();
    let cls = 'meh', title = 'Ніхто не переміг';
    if (r.outcome === 'CAUGHT') { title = 'Інсайдера спіймано!'; cls = role === 'INSIDER' ? 'bad' : 'good'; }
    if (r.outcome === 'INSIDER_WINS') { title = 'Інсайдер переміг'; cls = role === 'INSIDER' ? 'good' : 'bad'; }
    if (!role) cls = 'meh';
    let h = `<div class="banner ${cls}">${eye()}<h1>${title}</h1><div>${esc(r.note || '')}</div></div>
      <div class="card center stack">
        <div>Слово: <span class="word sm">${esc(s.word || '')}</span></div>
        <div>Інсайдер: <b class="c-INSIDER">${nameOf(s.insider)}</b> · Майстер: <b class="c-MASTER">${nameOf(s.master)}</b></div>
        ${voteSummary(r)}
      </div>
      <h2>Рахунок</h2>${scoreboard()}
      <div class="muted small">Перемога гравців: +1 кожному (і Майстру). Перемога Інсайдера: +3 йому. Не вгадали слово — нікому.</div>`;
    if (api.isHost) h += `<button class="big" data-act="next" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Наступний раунд</button>`;
    else h += `<div class="muted center small">Хост запустить наступний раунд.</div>`;
    return h;
  }

  PG.register('insider', {
    // background music per phase (shell.js): questions to the master need silence; the discussion goes over quiet music
    music: { QUESTIONS: 'off', DISCUSSION: 'low', RESULT: 'low' },
    musicLoop: 74.2857,  // seconds of music.mp3 that loop: a whole number of bars
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'ROLES': h = rolesView(); break;
        case 'QUESTIONS': h = questionsView(); break;
        case 'DISCUSSION': h = discussionView(); break;
        case 'VOTE1': h = vote1View(); break;
        case 'VOTE2': h = vote2View(); break;
        case 'RESULT': h = resultView(); break;
        default: h = '';
      }
      return `<div class="insider stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      if (!prev || prev.round !== next.round || (next.phase === 'ROLES' && prev.word !== next.word && next.me && next.me.role === 'INSIDER')) {
        if (next.phase === 'ROLES') revealed = false;
      }
      if (!prev || prev.phase !== next.phase) {
        pickedGuesser = null;
        if (prev) {
          a.vibrate(120);
          if (prev.phase === 'QUESTIONS' && next.phase === 'DISCUSSION') a.sfx('correct');
          if (next.phase === 'VOTE1') a.sfx('suspense');
          if (next.phase === 'RESULT') {
            const o = next.result && next.result.outcome;
            if (o === 'CAUGHT') { a.sfx('reveal'); a.sfx('win', 1900); }
            else if (o === 'INSIDER_WINS') { a.sfx('reveal'); a.sfx('lose', 1900); }
            else a.sfx('lose');
          }
        }
      }
    },

    menu() { return ['newGame']; },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const act = el.dataset.act;
      switch (act) {
        case 'flip': revealed = !revealed; return a.render();
        case 'seconds': return a.send({ t: 'seconds', seconds: +el.dataset.v });
        case 'start': case 'next': revealed = false; return a.send({ t: 'start' });
        case 'ready': return a.send({ t: 'ready' });
        case 'swapWord': revealed = true; return a.send({ t: 'swapWord' });
        case 'begin': return a.send({ t: 'begin' });
        case 'pickGuesser': pickedGuesser = el.dataset.pid; return a.render();
        case 'guessed': return a.send({ t: 'guessed', pid: pickedGuesser });
        case 'endDiscussion': return a.send({ t: 'endDiscussion' });
        case 'vote1': return a.send({ t: 'vote1', yes: el.dataset.yes === '1' });
        case 'vote2': return a.send({ t: 'vote2', target: el.dataset.pid });
        case 'closeVote1': return a.send({ t: 'closeVote1' });
        case 'closeVote2': return a.send({ t: 'closeVote2' });
      }
    },
  });
})();
