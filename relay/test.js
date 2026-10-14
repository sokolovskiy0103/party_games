// End-to-end check of the relay with a fake host phone. Run: node test.js
'use strict';
const assert = require('assert');
const http = require('http');
const WebSocket = require('ws');

process.env.PORT = '0';
const { server } = require('./server');

const wait = (ms) => new Promise(r => setTimeout(r, ms));
function next(ws, pred = () => true) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), 3000);
    const on = raw => {
      const m = JSON.parse(raw);
      if (pred(m)) { clearTimeout(t); ws.off('message', on); resolve(m); }
    };
    ws.on('message', on);
  });
}
function get(port, path) {
  return new Promise((resolve, reject) => {
    http.get({ port, path }, res => {
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => resolve({ status: res.statusCode, type: res.headers['content-type'], body }));
    }).on('error', reject);
  });
}

/** A host that serves one page and echoes every player message back as "echo:<msg>". */
function fakeHost(port, q = '') {
  const ws = new WebSocket(`ws://localhost:${port}/host${q}`);
  ws.on('message', raw => {
    const m = JSON.parse(raw);
    if (m.t === 'asset') {
      if (m.name === 'index.html') ws.send(JSON.stringify({ t: 'asset', id: m.id, status: 200, type: 'text/html; charset=utf-8', b64: Buffer.from('<h1>гра</h1>').toString('base64') }));
      else ws.send(JSON.stringify({ t: 'asset', id: m.id, status: 404 }));
    }
    if (m.t === 'm') ws.send(JSON.stringify({ t: 'm', c: m.c, d: 'echo:' + m.d }));
  });
  return ws;
}

(async () => {
  await new Promise(r => server.listening ? r() : server.once('listening', r));
  const port = server.address().port;

  assert.strictEqual((await get(port, '/health')).body, 'ok');

  let host = fakeHost(port);
  const hello = await next(host, m => m.t === 'hello');
  assert.match(hello.code, /^[A-Z]{4}$/);

  // page comes from the host phone
  const pageRes = await get(port, `/r/${hello.code}/`);
  assert.strictEqual(pageRes.status, 200);
  assert.ok(pageRes.body.includes('гра'));
  assert.strictEqual((await get(port, `/r/${hello.code}/static/nope.js`)).status, 404);
  assert.strictEqual((await get(port, `/r/${hello.code.toLowerCase()}`)).status, 302);
  assert.strictEqual((await get(port, `/r/ZZZZ/`)).status, 404);

  // player ↔ host
  const opened = next(host, m => m.t === 'open');
  const player = new WebSocket(`ws://localhost:${port}/r/${hello.code}/ws`);
  await new Promise(r => player.on('open', r));
  const { c } = await opened;
  const echoed = new Promise(r => player.once('message', d => r(d.toString())));
  player.send('{"t":"join"}');
  assert.strictEqual(await echoed, 'echo:{"t":"join"}');

  const closed = next(host, m => m.t === 'close');
  player.close();
  assert.strictEqual((await closed).c, c);

  // host reconnects with its key: same code, players are told to reconnect
  const p2 = new WebSocket(`ws://localhost:${port}/r/${hello.code}/ws`);
  await new Promise(r => p2.on('open', r));
  const p2closed = new Promise(r => p2.on('close', code => r(code)));
  host.close();
  assert.strictEqual(await p2closed, 4003);
  host = fakeHost(port, `?code=${hello.code}&key=${hello.key}`);
  const hello2 = await next(host, m => m.t === 'hello');
  assert.strictEqual(hello2.code, hello.code);

  // wrong key gets a fresh room
  const intruder = fakeHost(port, `?code=${hello.code}&key=wrong`);
  const hello3 = await next(intruder, m => m.t === 'hello');
  assert.notStrictEqual(hello3.code, hello.code);

  host.close(); intruder.close();
  await wait(50);
  console.log('relay test: ok');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
