// Party-games relay. Runs on Render (free tier is fine).
//
// It holds no game logic: the host phone connects to /host, players open /r/<CODE>/.
// The relay fetches the web client from the host phone (so page and app versions always match)
// and pipes every player's WebSocket to the host as numbered virtual connections.
//
// Relay → host:  hello{code,key} · open{c} · m{c,d} · close{c} · asset{id,name}
// Host → relay:  m{c,d} · asset{id,status,type,b64}
'use strict';

// Loud startup so deploy logs always show how far we got.
console.log(`relay booting: node ${process.version}, cwd ${process.cwd()}, PORT=${process.env.PORT}`);
process.on('uncaughtException', e => { console.error('uncaught:', e && e.stack || e); process.exit(1); });
process.on('unhandledRejection', e => { console.error('unhandled rejection:', e && e.stack || e); });
process.on('exit', code => console.log(`relay exiting with code ${code}`));

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 10000;
const ASSET_TIMEOUT_MS = 15_000;
const ROOM_TTL_MS = 6 * 60 * 60 * 1000;   // forget rooms whose host has been gone this long
const MAX_ROOMS = 500;
const MAX_MSG = 256 * 1024;
const LETTERS = 'ABCDEFGHJKLMNPQRSTUVWXYZ';   // no I/O: easy to read out loud

/** code -> { key, host, players: Map<c, ws>, nextC, assets: Map<name, {status,type,body}>, pending: Map<id, fn>, nextId, lastSeen } */
const rooms = new Map();

function newCode() {
  for (let i = 0; i < 1000; i++) {
    let c = '';
    for (let j = 0; j < 4; j++) c += LETTERS[crypto.randomInt(LETTERS.length)];
    if (!rooms.has(c)) return c;
  }
  throw new Error('no free room codes');
}

function hostSend(room, obj) {
  if (room.host && room.host.readyState === 1) room.host.send(JSON.stringify(obj));
}

function sweep() {
  const now = Date.now();
  for (const [code, r] of rooms) {
    if (!r.host && now - r.lastSeen > ROOM_TTL_MS) {
      for (const p of r.players.values()) p.close(4000, 'room closed');
      rooms.delete(code);
    }
  }
}
setInterval(sweep, 60_000).unref();

// ---------- HTTP ----------

const LANDING = `<!doctype html><html lang="uk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Ігри</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0e1320;color:#f3efe6;font:18px system-ui,sans-serif}
form{display:grid;gap:12px;width:min(90vw,320px);text-align:center}
input{font:inherit;font-size:32px;text-align:center;letter-spacing:8px;text-transform:uppercase;padding:14px;border-radius:14px;border:2px solid #2c3854;background:#161d2e;color:inherit}
button{font:inherit;font-weight:700;padding:14px;border:0;border-radius:14px;background:#f5b942;color:#1a1408}</style></head>
<body><form onsubmit="location.href='/r/'+this.c.value.trim().toUpperCase()+'/';return false">
<h1>🎲 Ігри</h1><div>Введи код кімнати з телефону хоста</div>
<input name="c" maxlength="4" autocomplete="off" autofocus required><button>Увійти</button></form></body></html>`;

function page(res, status, text) {
  res.writeHead(status, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
  res.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<body style="margin:0;min-height:100vh;display:grid;place-items:center;background:#0e1320;color:#f3efe6;font:18px system-ui,sans-serif;text-align:center;padding:16px">
<div>${text}<br><br><a style="color:#f5b942" href="/">Ввести інший код</a></div></body>`);
}

function requestAsset(room, name) {
  const cached = room.assets.get(name);
  if (cached) return Promise.resolve(cached);
  return new Promise(resolve => {
    const id = room.nextId++;
    const timer = setTimeout(() => { room.pending.delete(id); resolve(null); }, ASSET_TIMEOUT_MS);
    room.pending.set(id, { name, done: a => { clearTimeout(timer); resolve(a); } });
    hostSend(room, { t: 'asset', id, name });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const path = url.pathname;
  if (path === '/health') { res.writeHead(200, { 'content-type': 'text/plain' }); return res.end('ok'); }
  if (path === '/') { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); return res.end(LANDING); }

  const m = path.match(/^\/r\/([A-Za-z]{4})(\/.*)?$/);
  if (!m) { res.writeHead(404); return res.end('not found'); }
  const code = m[1].toUpperCase();
  const rest = m[2];
  if (!rest || code !== m[1]) { res.writeHead(302, { location: `/r/${code}/` }); return res.end(); }

  const room = rooms.get(code);
  if (!room) return page(res, 404, `Кімнати <b>${code}</b> немає. Перевір код на телефоні хоста.`);
  if (!room.host) return page(res, 503, 'Телефон хоста зараз не на зв\'язку. Спробуй за хвилину.');

  let name;
  if (rest === '/') name = 'index.html';
  else {
    const s = rest.match(/^\/static\/([A-Za-z0-9._-]+)$/);
    if (!s) { res.writeHead(404); return res.end('not found'); }
    name = s[1];
  }
  const a = await requestAsset(room, name);
  if (!a) return page(res, 504, 'Телефон хоста не відповів. Спробуй ще раз.');
  if (a.status !== 200) { res.writeHead(a.status); return res.end(); }
  res.writeHead(200, { 'content-type': a.type, 'cache-control': 'no-cache' });
  res.end(a.body);
});

// ---------- WebSockets ----------

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_MSG });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/host') {
    wss.handleUpgrade(req, socket, head, ws => onHost(ws, url));
    return;
  }
  const m = url.pathname.match(/^\/r\/([A-Z]{4})\/ws$/);
  const room = m && rooms.get(m[1]);
  if (!room) { socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, ws => onPlayer(ws, room));
});

function onHost(ws, url) {
  let code = (url.searchParams.get('code') || '').toUpperCase();
  const key = url.searchParams.get('key') || '';
  let room = rooms.get(code);
  if (!room || !key || room.key !== key) {
    if (rooms.size >= MAX_ROOMS) sweep();
    if (rooms.size >= MAX_ROOMS) { ws.close(1013, 'busy'); return; }
    code = newCode();
    room = { key: crypto.randomBytes(16).toString('hex'), host: null, players: new Map(), nextC: 1, assets: new Map(), pending: new Map(), nextId: 1, lastSeen: Date.now() };
    rooms.set(code, room);
  }
  // A reconnecting host takes over: players reconnect too, so they re-introduce themselves.
  if (room.host) try { room.host.close(4001, 'replaced'); } catch (_) {}
  for (const p of room.players.values()) p.close(4002, 'host reconnected');
  room.players.clear();
  room.assets.clear();
  room.host = ws;
  room.lastSeen = Date.now();
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.send(JSON.stringify({ t: 'hello', code, key: room.key }));
  console.log(`room ${code}: host connected (${rooms.size} rooms)`);

  ws.on('message', raw => {
    let m; try { m = JSON.parse(raw); } catch (_) { return; }
    room.lastSeen = Date.now();
    if (m.t === 'm') {
      const p = room.players.get(m.c);
      if (p && p.readyState === 1 && typeof m.d === 'string') p.send(m.d);
    } else if (m.t === 'asset') {
      const a = { status: m.status || 500, type: m.type || 'application/octet-stream', body: m.b64 ? Buffer.from(m.b64, 'base64') : Buffer.alloc(0) };
      const req = room.pending.get(m.id);
      if (!req) return;
      room.pending.delete(m.id);
      if (a.status === 200) room.assets.set(req.name, a);
      req.done(a);
    }
  });
  ws.on('close', () => {
    if (room.host !== ws) return;
    room.host = null;
    room.lastSeen = Date.now();
    for (const p of room.players.values()) p.close(4003, 'host left');
    room.players.clear();
    console.log(`room ${code}: host disconnected`);
  });
}

function onPlayer(ws, room) {
  if (!room.host) { ws.close(4003, 'host offline'); return; }
  const c = room.nextC++;
  room.players.set(c, ws);
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  hostSend(room, { t: 'open', c });
  ws.on('message', raw => hostSend(room, { t: 'm', c, d: raw.toString() }));
  ws.on('close', () => {
    if (room.players.get(c) === ws) {
      room.players.delete(c);
      hostSend(room, { t: 'close', c });
    }
  });
}

// Drop dead sockets and keep Render's proxy from closing idle ones.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 25_000).unref();

server.listen(PORT, '0.0.0.0', () => console.log(`relay listening on 0.0.0.0:${server.address().port}`));
module.exports = { server, rooms };
