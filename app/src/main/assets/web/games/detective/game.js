// «Слідство» UI — cooperative detective game. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;
  let api = PG.api;

  const g = () => api.g;
  const me = () => g() && g().me;
  const inRound = () => !!(me() && me().inRound);

  function timerHtml(total) {
    const t = { deadline: g().deadline, total, low: 30000 };
    return `<div class="stack">${ui.timer(t)}${ui.timerBar(t)}</div>`;
  }

  function playerList() {
    return `<div class="card">${g().players.map(p => `
      <div class="li">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && me().id === p.id ? ' <span class="muted small">(ти)</span>' : ''}</span>
        ${p.ready ? '<span class="tag good">готово</span>' : ''}
      </div>`).join('') || '<div class="muted">Поки нікого</div>'}
    </div>`;
  }

  function caseCard(compact) {
    const c = g().case;
    if (!c) return '';
    return `<div class="card case-card stack ${compact ? 'tight' : ''}">
      <div class="tag accent">Справа №${g().round}</div>
      <div class="headline">${esc(c.title)}</div>
      <div class="muted small">${esc(c.place)} · жертва: ${esc(c.victim)}</div>
      ${compact ? '' : `<p>${esc(c.intro)}</p>`}
    </div>`;
  }

  function clueRow(text, key, shareable) {
    return `<div class="li" data-k="${key}">
      <span class="name clue-text">${esc(text)}</span>
      ${shareable ? `<button class="chip" data-act="share" data-i="${shareable.i}">Показати всім</button>` : ''}
    </div>`;
  }

  function myClues() {
    const s = g();
    if (!me()) return '';
    if (!inRound()) return '';
    const cl = s.clues || [];
    if (!cl.length) return '';
    return `<div class="card stack">
      <b>Твої свідчення · ${cl.length}</b>
      <div class="muted small">Розкажи їх уголос. Або покажи всім — і вони з'являться на спільній дошці.</div>
      ${cl.map(c => clueRow(c.text, 'c' + c.i, c)).join('')}
    </div>`;
  }

  function publicClues() {
    const s = g();
    const cl = s.publicClues || [];
    return `<div class="card stack">
      <b>Дошка свідчень</b>
      <div class="muted small">${cl.length ? 'Це вже знає вся команда.' : 'Поки порожньо — поділіться своїми свідченнями.'}</div>
      ${cl.map((text, i) => `<div class="li" data-k="p${i}"><span class="name clue-text">${esc(text)}</span></div>`).join('')}
    </div>`;
  }

  function suspectCard(x, act, selected, disabled) {
    const cls = ['pick', 'suspect'];
    if (x.marked) cls.push('dim');
    if (x.cleared) cls.push('cleared');
    if (selected === x.i) cls.push('on');
    const attrs = disabled ? 'aria-disabled="true"' : `data-act="${act}" data-i="${x.i}"`;
    return `<button class="${cls.join(' ')}" ${attrs} data-k="s${x.i}" ${x.cleared ? 'disabled' : ''}>
      <b>${esc(x.name)}</b>
      <span class="small">Мотив: ${esc(x.motive)}</span>
      <span class="small">${esc(x.where)} · ${esc(x.time)}</span>
      <span class="small">При собі: ${esc(x.item)}</span>
    </button>`;
  }

  function suspectsView(act, selected, disabled) {
    return `<div class="picks two">${g().suspects.map(x => suspectCard(x, act, selected, disabled)).join('')}</div>`;
  }

  // ---------- views ----------

  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack">
      <b>Команда детективів</b>
      <div class="muted">Розкрито: <b class="c-good">${s.solved}</b> · Провалено: <b class="c-bad">${s.failed}</b></div>
    </div>`;
    if (api.isHost) {
      const mins = [3, 5, 8];
      h += `<div class="card stack">
        <b>Час на розслідування</b>
        <div class="chips">${mins.map(m => `<button class="chip ${Math.round(s.seconds / 60) === m ? 'on' : ''}" data-act="seconds" data-v="${m * 60}">${m} хв</button>`).join('')}</div>
        <div class="muted small">Після хибного звинувачення команда отримує 90 секунд на другу спробу.</div>
      </div>`;
    } else {
      h += ui.wait('Чекаємо, поки хост почне справу', '👑');
    }
    h += `<h2>Гравці · ${n}</h2>${playerList()}
      <div class="card flat"><div class="muted small">Кооператив: ви або розкриваєте справу всі разом, або програєте всі разом. У кожного — частина свідчень, яких не бачать інші.</div></div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>${s.solved + s.failed ? 'Наступна справа' : 'Почати справу'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці (зараз ${n}).</div>`;
    }
    return h;
  }

  function briefView() {
    const s = g();
    const inR = s.players.filter(p => p.inRound);
    const readyN = inR.filter(p => p.ready).length;
    const meReady = !!(me() && inR.find(p => p.id === me().id && p.ready));
    let h = caseCard(false) + myClues() + publicClues();
    if (inRound()) {
      h += meReady
        ? `<div class="card flat center">Готово. Чекаємо на решту команди (${readyN}/${inR.length}).</div>`
        : `<button class="big" data-act="ready">Я готовий(а) ✓</button>`;
    } else if (me()) {
      h += `<div class="card flat center muted">Ти приєднався(лась) після початку справи — допомагай, але голосувати не будеш.</div>`;
    }
    h += `<div class="chips">${inR.map(p => `<span class="chip ${p.ready ? 'ok' : ''}">${p.ready ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>`;
    h += `<h2>Підозрювані</h2>
      <div class="muted small">Торкнись підозрюваного, щоб викреслити його для всієї команди.</div>
      ${suspectsView('mark')}`;
    if (api.isHost) h += `<button class="ghost" data-act="begin">Почати без очікування</button>`;
    return h;
  }

  function investigationView() {
    const s = g();
    let h = `<div class="center"><span class="tag accent">розслідування</span></div>
      ${timerHtml(s.seconds * 1000)}
      <div class="row" style="justify-content:space-between">
        <span class="muted small">Залишилось спроб: <b class="${s.attemptsLeft === 1 ? 'c-warn' : ''}">${s.attemptsLeft}</b></span>
        <span class="muted small">Свідчень на дошці: ${(s.publicClues || []).length}</span>
      </div>`;
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += caseCard(true) + myClues() + publicClues();
    if (inRound() || api.isHost) {
      h += `<h2>Підозрювані</h2>
        <div class="muted small">Торкнись підозрюваного, щоб викреслити його для всієї команди.</div>
        ${suspectsView('mark')}
        <button class="big" data-act="callVote">Скликати голосування</button>`;
    } else {
      h += `<h2>Підозрювані</h2>
        <div class="muted small">Торкнись підозрюваного, щоб допомогти команді.</div>
        ${suspectsView('mark')}`;
    }
    return h;
  }

  function voteView() {
    const s = g();
    const canVote = inRound();
    const iVote = canVote && s.myVote !== undefined;
    let h = `<div class="center"><span class="tag bad">звинувачення</span></div>
      <h1 class="center">Хто вбивця?</h1>
      ${timerHtml(60000)}
      <p class="muted center">Найбільша кількість голосів вирішує долю команди. Хибне звинувачення коштує одну з двох спроб.</p>`;
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += caseCard(true) + myClues() + publicClues();
    if (canVote) h += `<h2>Обери підозрюваного</h2>`;
    else h += `<h2>Підозрювані</h2><div class="muted small">Ти не голосуєш — допомагай команді порадою.</div>`;
    h += `${suspectsView('vote', s.myVote, !canVote)}
      <div class="chips">${s.players.filter(p => p.inRound).map(p => `<span class="chip ${p.voted ? 'ok' : ''}">${p.voted ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>`;
    if (iVote) h += `<div class="muted small center">Голос прийнято. Можна змінити, поки не проголосували всі.</div>`;
    if (api.isHost) {
      const cast = s.players.filter(p => p.inRound && p.voted).length;
      h += `<button class="ghost" data-act="closeVote" ${cast ? '' : 'disabled'}>Завершити голосування</button>`;
    }
    return h;
  }

  function resultView() {
    const s = g();
    const r = s.result || {};
    const x = (s.suspects || []).find(y => y.i === s.culprit) || {};
    const won = !!r.won;
    let h = `<div class="banner ${won ? 'good' : 'bad'}">
      <div class="emoji">${won ? '🎉' : '🕯️'}</div>
      <h1>${won ? 'Справу розкрито!' : 'Справу закрито'}</h1>
      <div>${esc(r.note || '')}</div>
    </div>
    <div class="card center stack">
      <div class="muted small">${esc((s.case || {}).title || '')} · убивця</div>
      <div class="headline">${esc(x.name || '?')}</div>
      <div class="muted small">Мотив: ${esc(x.motive || '?')} · ${esc(x.where || '')} · ${esc(x.time || '')}</div>
      <div class="muted small">При собі: ${esc(x.item || '')}</div>
      <div class="muted small">Спроб використано: ${r.attemptsUsed || 0}</div>
    </div>
    <div class="card stack">
      <b>Усі свідчення</b>
      ${(s.publicClues || []).map((text, i) => `<div class="li" data-k="r${i}"><span class="name clue-text">${esc(text)}</span></div>`).join('')}
    </div>
    <div class="card flat center">Розкрито: <b class="c-good">${s.solved}</b> · Провалено: <b class="c-bad">${s.failed}</b></div>`;
    if (api.isHost) h += `<button class="big" data-act="next" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Наступна справа</button>`;
    else h += `<div class="muted center small">Хост запустить наступну справу.</div>`;
    return h;
  }

  PG.register('detective', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'BRIEF': h = briefView(); break;
        case 'INVESTIGATION': h = investigationView(); break;
        case 'VOTE': h = voteView(); break;
        case 'RESULT': h = resultView(); break;
        default: h = '';
      }
      return `<div class="detective stack">${h}</div>`;
    },

    onState(prev, next, a) { api = a; },

    menu() {
      return g() && g().phase !== 'LOBBY'
        ? [{ t: 'abort', label: 'Зупинити справу' }, 'newGame']
        : ['newGame'];
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const i = el.dataset.i === undefined ? undefined : +el.dataset.i;
      switch (el.dataset.act) {
        case 'start': case 'next': return a.send({ t: el.dataset.act });
        case 'seconds': return a.send({ t: 'seconds', seconds: +el.dataset.v });
        case 'ready': return a.send({ t: 'ready' });
        case 'begin': return a.send({ t: 'begin' });
        case 'share': return a.send({ t: 'share', i });
        case 'mark': return a.send({ t: 'mark', i });
        case 'callVote': return a.send({ t: 'callVote' });
        case 'vote': return a.send({ t: 'vote', i });
        case 'closeVote': return a.send({ t: 'closeVote' });
      }
    },
  });
})();
