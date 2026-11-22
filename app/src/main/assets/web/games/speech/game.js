// «Три слова» UI. Rendered by the shell; game state is api.g.
(function () {
  'use strict';
  const { esc, ui } = PG.api;

  let api = PG.api;
  let saveTimer = null;

  const g = () => api.g;
  const me = () => g() && g().me;
  const isSpeaker = () => !!(me() && g().speaker === me().id);
  const canDrive = () => isSpeaker() || api.isHost;
  const name = id => api.nameOf(id);
  const pts = n => n === 1 ? 'очко' : (n >= 2 && n <= 4 ? 'очки' : 'очок');

  // ---------- voice: the speaker's phone marks the secret words it hears ----------
  // Players' browsers: Web Speech API, only on HTTPS (the relay); the host app's WebView: Android's recogniser via PGApp.
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const native = () => !!(window.PGApp && PGApp.listen);
  const canVoice = () => native() || !!(SR && window.isSecureContext);
  const voice = { on: false, rec: null, state: '', round: -1, last: '', lastAt: 0 };

  function heard(text, final) {
    text = String(text || '').trim();
    if (!text) return;
    // partial results come many times a second: send only changes, at most ~3 a second (the final one always)
    const now = Date.now();
    if (!final && (text === voice.last || now - voice.lastAt < 300)) return;
    voice.last = text; voice.lastAt = now;
    api.send({ t: 'heard', text });
  }

  function setVoice(st) {
    if (voice.state === st) return;
    voice.state = st;
    api.render();
  }

  function startVoice() {
    if (voice.on || !canVoice()) return;
    voice.on = true;
    voice.round = g().round;
    if (native()) {
      PG.onVoice = (text, final) => heard(text, final);
      PG.onVoiceState = st => {
        if (st === 'on' && voice.on) api.send({ t: 'mic' });
        if (st !== 'on') voice.on = false;
        setVoice(st);
      };
      let ok = false;
      try { ok = PGApp.listen('uk-UA'); } catch (_) {}
      if (!ok) { voice.on = false; setVoice('off'); }
      return;
    }
    const rec = new SR();
    rec.lang = 'uk-UA'; rec.continuous = true; rec.interimResults = true;
    rec.onstart = () => { api.send({ t: 'mic' }); setVoice('on'); };
    rec.onresult = e => { for (let i = e.resultIndex; i < e.results.length; i++) heard(e.results[i][0].transcript, e.results[i].isFinal); };
    rec.onerror = e => {
      if (['not-allowed', 'service-not-allowed', 'language-not-supported', 'audio-capture'].includes(e.error)) { voice.on = false; voice.rec = null; setVoice('denied'); }
    };
    // Chrome stops listening after a pause: start again until the speech is over
    rec.onend = () => {
      if (voice.on && voice.rec === rec) setTimeout(() => { if (voice.on && voice.rec === rec) try { rec.start(); } catch (_) {} }, 250);
    };
    voice.rec = rec;
    try { rec.start(); } catch (_) { voice.on = false; voice.rec = null; setVoice('off'); }
  }

  function stopVoice() {
    voice.state = '';
    if (!voice.on) return;
    voice.on = false;
    if (native()) { try { PGApp.stopListening(); } catch (_) {} }
    else if (voice.rec) { try { voice.rec.stop(); } catch (_) {} }   // stop(), not abort(): the last words still arrive
    voice.rec = null;
  }

  function micLine() {
    if (!canVoice()) return `<div class="muted small center">🎙 Автовідмітка слів працює в режимі «🌍 Інтернет» (Chrome, Safari). Тут сказані слова позначиш після промови.</div>`;
    if (voice.state === 'on') return `<div class="small center c-good">🎙 Слухаю — сказані слова відмітяться самі</div>`;
    if (voice.state === 'denied') return `<div class="small center c-warn">🎙 Немає доступу до мікрофона — слова позначиш після промови</div>`;
    return `<button class="soft" data-act="mic">🎙 Увімкнути автовідмітку слів</button>`;
  }

  // ---------- pieces ----------
  function header() {
    const s = g();
    return `<div class="row"><span class="tag">хід ${Math.min(s.turnsDone + 1, s.turnsTotal)} з ${s.turnsTotal}</span>
      <span class="grow"></span><span class="small muted">говорить <b>${name(s.speaker)}</b></span></div>`;
  }

  function topicCard() {
    return `<div class="card stack center"><div class="muted small">${g().topicAdult ? '🔥 Тема 18+' : 'Тема'}</div><div class="headline">${esc(g().topic || '')}</div></div>`;
  }

  function secretCard() {
    const ws = g().words || [];
    return `<div class="card hl stack center"><div class="muted small">Твої таємні слова — встав їх у промову</div>
      ${ws.map(w => `<div class="word sm">${esc(w.w)}${w.heard ? ' <span class="c-good">✓</span>' : ''}</div>`).join('')}</div>`;
  }

  function timerBlock() {
    const s = g();
    if (!s.deadline) return '';
    return `<div class="stack center">${ui.timer({ deadline: s.deadline, low: 10000 })}${ui.timerBar({ deadline: s.deadline, total: s.total, low: 10000 })}</div>`;
  }

  const inputId = i => `g-${g().round}-${i}`;

  function guessForm(final) {
    const mine = (me() && me().guesses) || [];
    const inputs = [0, 1, 2].map(i =>
      `<input id="${inputId(i)}" type="text" maxlength="40" placeholder="Слово ${i + 1}" value="${esc(mine[i] || '')}" autocomplete="off" enterkeyhint="${i < 2 ? 'next' : 'done'}">`).join('');
    return `<div class="card stack"><b>Які слова підкинуто?</b>
      <div class="muted small">До трьох слів. Вгадане слово, яке промовець справді сказав, — +1 тобі.</div>
      ${inputs}
      ${final
        ? `<button class="big" data-act="done">Готово</button>`
        : `<button class="soft" data-act="save">Зберегти</button>`}
    </div>`;
  }

  function readGuesses() {
    return [0, 1, 2].map(i => { const el = document.getElementById(inputId(i)); return el ? el.value.trim() : ''; });
  }

  function save(final) {
    clearTimeout(saveTimer); saveTimer = null;
    if (!document.getElementById(inputId(0))) return;
    api.send({ t: 'guess', g: readGuesses(), done: !!final });
  }

  function progress() {
    const s = g();
    const ls = s.players.filter(p => p.id !== s.speaker);
    return `<div class="card">${ls.map(p => `
      <div class="li"><span class="dot ${p.connected ? '' : 'off'}"></span><span class="name">${esc(p.name)}</span>
        <span class="small ${p.done ? 'c-good' : 'muted'}">${p.done ? 'готово ✓' : (p.saved ? 'пише…' : '—')}</span></div>`).join('')}</div>`;
  }

  function scoreboard(withGain) {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    return `<div class="card">${list.map(p => `
      <div class="li"><span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && p.id === me().id ? ' <span class="muted">(ти)</span>' : ''}${p.id === s.speaker ? ' 🎤' : ''}</span>
        ${withGain && p.gain ? `<span class="plus">+${p.gain}</span>` : ''}
        <span class="pts">${p.score}</span></div>`).join('')}</div>`;
  }

  // ---------- views ----------
  function lobbyView() {
    const s = g();
    const n = s.players.length;
    const enough = n >= s.minPlayers;
    const chips = (act, list, cur, label) => api.isHost
      ? `<div class="chips">${list.map(v => `<button class="chip ${cur === v ? 'on' : ''}" data-act="${act}" data-v="${v}">${label(v)}</button>`).join('')}</div>`
      : `<div class="chips"><span class="chip on">${label(cur)}</span></div>`;
    let h = '';
    if (s.notice) h += `<div class="notice">${esc(s.notice)}</div>`;
    h += `<div class="card stack"><b>Час на промову</b>${chips('speech', s.speechTimes, s.speechSec, v => v + ' с')}
      ${s.hasAdult ? `<b>Теми</b>${chips('adult', [false, true], s.adult, v => v ? '🔥 З 18+' : '😇 Без 18+')}` : ''}
      <b>Кожен говорить</b>${chips('cycles', s.cyclesList, s.cycles, v => v === 1 ? '1 раз' : v + ' рази')}
      <div class="muted small">Промовець говорить на тему й непомітно вставляє три таємні слова. Решта вписує, які слова, на їхню
        думку, підкинуто. Вгадав(ла) — очко тобі; не вгадав(ла) — очко промовцю.</div></div>`;
    if (!api.isHost) h += ui.wait('Чекаємо, поки хост почне', '👑');
    h += `<h2>Гравці · ${n}</h2><div class="card chips">${s.players.map(p => `<span class="chip"><span class="dot ${p.connected ? '' : 'off'}"></span>${esc(p.name)}</span>`).join('') || '<span class="muted">Поки нікого</span>'}</div>`;
    if (api.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>Почати</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${s.minPlayers} гравці.</div>`;
    }
    return h;
  }

  function prepView() {
    const s = g();
    let h = header() + topicCard();
    if (isSpeaker()) {
      h += secretCard();
      h += `<div class="muted small center">Сховай екран. Говори ${s.speechSec} с і встав усі три слова так, щоб їх не помітили.</div>`;
      if (canVoice()) h += `<div class="muted small center">🎙 Під час промови телефон слухатиме й сам відмітить сказані слова.</div>`;
      h += `<div class="grid2"><button class="soft" data-act="swapTopic">Інша тема</button><button class="soft" data-act="swapWords">Інші слова</button></div>`;
      h += `<button class="big" data-act="begin">Почати промову</button>`;
    } else {
      h += ui.wait(`<b>${name(s.speaker)}</b> готується до промови`, ((s.players.find(p => p.id === s.speaker) || {}).name) || '?');
      if (api.isHost) h += `<div class="grid2"><button class="soft" data-act="begin">Почати за нього</button><button class="ghost" data-act="skip">Пропустити</button></div>`;
    }
    return h;
  }

  function speechView() {
    const s = g();
    let h = header() + timerBlock() + topicCard();
    if (isSpeaker()) {
      h += secretCard() + micLine() + `<button class="soft" data-act="end">Завершити промову</button>`;
    } else {
      if (me()) h += guessForm(false);
      if (api.isHost) h += `<button class="ghost" data-act="end">Завершити промову</button>`;
    }
    return h;
  }

  function guessView() {
    const s = g();
    let h = header() + timerBlock();
    if (me() && !isSpeaker()) {
      if (me().done) {
        const mine = me().guesses || [];
        h += `<div class="banner meh">Здогадки надіслано ✓<div class="small muted">${mine.map(esc).join(', ') || 'без здогадок'}</div></div>
          <button class="ghost" data-act="undo">Змінити</button>`;
      } else {
        h += `<div class="muted small center">Промову закінчено — останні секунди, щоб дописати.</div>` + guessForm(true);
      }
    } else if (isSpeaker()) {
      h += ui.wait('Слухачі дописують здогадки', '✍️');
    }
    h += `<h2>Слухачі</h2>` + progress();
    if (api.isHost) h += `<button class="soft" data-act="review">Відкрити зараз</button>`;
    return h;
  }

  function reviewView() {
    const s = g();
    const judge = canDrive();
    // only the listeners who count this turn (online or with guesses)
    const ls = (s.guesses || []).map(x => s.players.find(p => p.id === x.id)).filter(Boolean);
    let h = header() + topicCard();
    h += `<h2>Таємні слова</h2>`;
    h += (s.words || []).map((w, i) => {
      const chips = ls.map(p => {
        const on = w.hits.includes(p.id);
        return judge
          ? `<button class="chip ${on ? 'on' : ''}" data-act="hit" data-pid="${esc(p.id)}" data-i="${i}" data-v="${on ? 0 : 1}">${esc(p.name)}</button>`
          : (on ? `<span class="chip on">${esc(p.name)}</span>` : '');
      }).join('');
      return `<div class="card stack ${w.used ? '' : 'flat'}" data-k="w${i}">
        <div class="row"><span class="word sm ${w.used ? '' : 'muted'}">${esc(w.w)}</span><span class="grow"></span>
          ${s.mic ? `<span class="tag ${w.heard ? 'good' : ''}">🎙 ${w.heard ? 'почуто' : 'не почуто'}</span>` : ''}
          ${judge
            ? `<label class="toggle small"><input type="checkbox" class="switch" data-act="used" data-i="${i}" ${w.used ? 'checked' : ''}><span>сказав(ла)</span></label>`
            : `<span class="small ${w.used ? 'c-good' : 'c-bad'}">${w.used ? 'прозвучало' : 'не прозвучало'}</span>`}</div>
        <div class="muted small">${w.used ? 'Вгадали:' : 'Не прозвучало — очок немає.'}</div>
        <div class="chips">${chips || (w.used ? `<span class="small c-accent">Ніхто — ${ls.length} ${pts(ls.length)} промовцю</span>` : '')}</div>
      </div>`;
    }).join('');
    if (judge && s.mic) h += `<div class="muted small center">Мікрофон сам позначив, які слова прозвучали. Перевір перемикачі «сказав(ла)».</div>`;
    if (judge) h += `<div class="muted small center">Матчер міг помилитися — торкнись імені, щоб зарахувати чи зняти.</div>`;
    h += `<h2>Здогадки</h2><div class="card">${(s.guesses || []).map(x => `
      <div class="li"><span class="name">${name(x.id)}</span><span class="small">${x.g.map(esc).join(', ') || '<span class="muted">—</span>'}</span></div>`).join('')}</div>`;
    h += `<h2>Рахунок</h2>` + scoreboard(true);
    const last = s.turnsDone + 1 >= s.turnsTotal;
    h += judge
      ? `<button class="big" data-act="next">${last ? 'Підсумки' : 'Наступний промовець'}</button>`
      : '<div class="muted center small">Далі — коли промовець чи хост натисне.</div>';
    return h;
  }

  function overView() {
    const s = g();
    const list = s.players.slice().sort((a, b) => b.score - a.score);
    const top = list.length ? list[0].score : 0;
    const winners = list.filter(p => p.score === top).map(p => esc(p.name)).join(', ');
    const sly = list.slice().sort((a, b) => b.sneaked - a.sneaked)[0];
    const ear = list.slice().sort((a, b) => b.caught - a.caught)[0];
    let h = `<div class="banner good"><span class="emoji">🏆</span><h1>${winners}</h1><div>${top} ${pts(top)}</div></div>`;
    h += scoreboard(false);
    if (sly && sly.sneaked > 0) h += `<div class="card flat center small">🤫 Найхитріший промовець: <b>${esc(sly.name)}</b> — ${sly.sneaked} непомічених слів</div>`;
    if (ear && ear.caught > 0) h += `<div class="card flat center small">👂 Найпильніше вухо: <b>${esc(ear.name)}</b> — ${ear.caught} вгаданих слів</div>`;
    h += api.isHost
      ? `<button class="big" data-act="start" ${s.players.length >= s.minPlayers ? '' : 'disabled'}>Ще раз</button>`
      : '<div class="muted center small">Хост почне заново.</div>';
    return h;
  }

  PG.register('speech', {
    render(a) {
      api = a;
      const s = g();
      if (!s) return '';
      let h;
      switch (s.phase) {
        case 'LOBBY': h = lobbyView(); break;
        case 'PREP': h = prepView(); break;
        case 'SPEECH': h = speechView(); break;
        case 'GUESS': h = guessView(); break;
        case 'REVIEW': h = reviewView(); break;
        case 'OVER': h = overView(); break;
        default: h = '';
      }
      return `<div class="speech stack">${h}</div>`;
    },

    onState(prev, next, a) {
      api = a;
      if (!next) return;
      // the mic listens only while this phone's owner is speaking
      // (PREP too: the tap on «Почати» starts it a moment before the state says SPEECH)
      const speaking = (next.phase === 'SPEECH' || next.phase === 'PREP') && next.me && next.speaker === next.me.id;
      if (voice.on && (!speaking || next.round !== voice.round)) stopVoice();
      if (!speaking && next.phase !== 'GUESS') voice.state = '';
      // the clock ran out mid-word: what's typed so far still counts
      if (prev && prev.phase === 'SPEECH' && next.phase === 'GUESS' && !isSpeaker()) save(false);
      if (prev && prev.phase !== next.phase) a.vibrate(120);
    },

    menu() { return ['newGame']; },

    key(e, a) {
      api = a;
      const t = e.target;
      if (!t || !t.id || !t.id.startsWith('g-')) return;
      if (e.key === 'Enter') {
        const i = +t.id.split('-')[2];
        const nx = document.getElementById(inputId(i + 1));
        if (nx) nx.focus(); else t.blur();
        return save(false);
      }
      // autosave while typing, so the timer can't eat the words
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => save(false), 800);
    },

    click(e, a) {
      api = a;
      const el = e.target.closest('[data-act]');
      if (!el || el.disabled) return;
      const d = el.dataset;
      switch (d.act) {
        case 'speech': return a.send({ t: 'speech', v: +d.v });
        case 'cycles': return a.send({ t: 'cycles', v: +d.v });
        case 'start': return a.send({ t: 'start' });
        case 'swapTopic': return a.send({ t: 'swapTopic' });
        case 'swapWords': return a.send({ t: 'swapWords' });
        case 'begin':
          // a tap is what lets the browser open the mic
          a.send({ t: 'begin' });
          if (isSpeaker()) startVoice();
          return;
        case 'mic': return startVoice();
        case 'adult': return a.send({ t: 'adult', v: d.v === 'true' });
        case 'skip': return a.send({ t: 'skip' });
        case 'end': return a.send({ t: 'end' });
        case 'review': return a.send({ t: 'review' });
        case 'next': return a.send({ t: 'next' });
        case 'save': return save(false);
        case 'done': return save(true);
        case 'undo': return a.send({ t: 'guess', g: (me() && me().guesses) || [], done: false });
        case 'used': return a.send({ t: 'used', i: +d.i, v: el.checked });
        case 'hit': return a.send({ t: 'hit', pid: d.pid, i: +d.i, v: d.v === '1' });
      }
    },
  });
})();
