# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Android "party games" app (UI strings and README are in Ukrainian). The host's phone runs a Ktor server and all game logic; players join from a browser by scanning a QR code. See `README.md` for the user-facing overview and the "how to add a game" steps.

## Commands

No Gradle wrapper is committed; CI uses Gradle 8.14.3 + JDK 17 (use a local `gradle`, or Android Studio).

- Unit tests + release APK (what CI runs): `gradle :app:testReleaseUnitTest :app:assembleRelease`
- Single test class: `gradle :app:testReleaseUnitTest --tests "com.sokolovskiy.party.games.hat.GameTest"`
- Run the whole app on the desktop (no Android needed): `gradle :dev:run` — prints a host URL (with token) and a player URL; web assets are read straight from `app/src/main/assets`, so edits under `web/` show on reload.
- Relay (Node ≥ 20): `cd relay && npm install && npm test`
- Web JS syntax check (CI): `find app/src/main/assets/web -name "*.js" -print0 | xargs -0 -n1 node --check`

CI (`.github/workflows`) runs on every push to `main`: relay test → web syntax check → unit tests → signed APK → GitHub release `v1.0.<run>`. The commit message becomes the release notes; the part above `---` is shown as "Що нового" in the in-app update dialog.

## Files

```
app/src/main/assets/
  web/                     everything the phone serves at /static/<path> (also through the relay)
    index.html             page skeleton; loads only core/ — the shell loads the games itself
    core/                  ui.css, shell.css, shell.js; lobby music.mp3 and shared sfx/<name>.mp3
    games/<id>/            one folder per game: game.js, game.css, rules.html
                           + optional music.mp3, sfx/<name>.mp3, bg.webp|webm|mp4, bg-tv.*
  data/                    word banks etc. read by Kotlin via GameEnv.asset(); never served
```

Kotlin code of a game is in `app/src/main/java/com/sokolovskiy/party/games/<id>/`, its tests in `app/src/test/.../games/<id>/`. Paths under `web/` are segments of `[A-Za-z0-9._-]` that don't start with a dot (`LocalServer.readAsset` and the relay both enforce it).

## New games: MVP first

When asked to add a new game, build an **MVP** by default: the goal is a game that plays correctly from start to finish, not a finished look. Polishing it into a production game is a separate step, done only when the user asks, with the `game-polish` skill (`.claude/skills/game-polish`).

An MVP includes:
- Kotlin `games/<id>/`: `GameModule` + `GameSession` with the full rules, per-viewer `state()` that leaks nothing, timers in `tick()`, reconnect/leave handling, registered in `core/Hub.kt`. Unit tests in `app/src/test/.../games/<id>/` cover the rules, win/lose conditions and that hidden info stays hidden.
- `web/games/<id>/game.js`: `PG.register('<id>', { render, click, menu })` built **only** from existing `ui.css` components and `PG.ui` helpers (`card`, `pick`, `chip`, `banner`, `li`, `headline`, `tiles`, `flipCard`, `timer`…). Nothing to add to `index.html`: the shell loads every game the server lists. Content lists (words, topics) go in `assets/data/<id>-*.json`.
- `web/games/<id>/game.css`: only the theme `[data-theme=<id>] { --accent; --on-accent }`, and a few layout rules if the components really don't cover it.
- `web/games/<id>/rules.html`: the rules (an HTML fragment: `<h3>`, `<p>`, `<ol>`), in Ukrainian like the rest of the UI.
- Checked by playing it in `gradle :dev:run` with enough windows for `minPlayers`.

An MVP leaves out: custom components and animations, `music`/`api.sfx`, `bg`, a TV `board()`, haptics beyond the defaults, and new audio or image assets. Don't add these unasked; mention at the end that `game-polish` is the next step.

## Architecture

- **All game state lives on the host phone.** `core/Hub` owns the lock, state pushes and timers; `core/Room` holds players and which game is selected; `core/LocalServer` (Ktor) serves `assets/web` at `/static/<path>` and the WebSocket. In "Інтернет" mode `core/RelayClient` connects the phone outward to the Node relay (`relay/server.js`, deployed on Render via `render.yaml`), which only forwards messages and proxies the web page from the phone. Root `index.js`/`server.js` just start the relay for when Render points at the repo root.
- **Game contract** (`core/GameModule.kt`): a `GameModule` is created once; a `GameSession` is created per pick. All session calls run under the room lock (no own synchronisation); `state()` is computed *per viewer* and must not leak hidden info (roles, secret words). Player identity belongs to `Room`; sessions key their data by player id. Modules are registered in `core/Hub.kt`. Optional `board(now)` is the TV view (a player page opened with `?tv`: a `Conn` with `tv = true` that never joins or sends game messages); it must contain only public info, and a null board means the TV shows the generic room screen. The web module draws it with `board(api)`. `tv/TvPresenter` shows `/?tv` on a presentation display when the host casts the phone's screen (Miracast / Smart View); the TV's QR comes from the server (`Hub.inviteUrl`), not from the TV page's own address.
- **Frontend** (`app/src/main/assets/web`): `core/shell.js/css` + `index.html` handle joining, lobby, QR, network toggle. On the first state the shell injects `games/<id>/game.css` + `game.js` for every game in `st.games` (section "games" in `shell.js`; until a game's code arrives its screen shows a spinner) and fetches `rules.html` when the rules are opened. Each game registers via `PG.register('<id>', { render, click, onState, menu })`. `menu()` lists the host-only items of the shell's side drawer (☰ / back gesture) as `{ t, label }` objects or shortcut keys like `'newGame'` (`MENU_ITEMS` in `shell.js`); the shell renders them, asks "Точно?" and sends `{ t }` to the game, so games don't build menu buttons or confirmations themselves.
- **Design system** (`ui.css`, loaded before everything else): tokens on `:root`, derived shades (`--accent-soft/-line/-text`, `--good-bg`…) recomputed on `body, [data-theme], .tint`; components (`card` + `flat/hl/dashed/team`, `pick`, `chip`, `seg`, `switch`, `banner`, `notice`, `tag`, `badge`, `li`, `word`, `headline`, `hero`, `tiles`, `timer`/`bar`, `spectrum`/`pin`, `flip`) and `fx-*` animations. Selected state is `.on` everywhere; text colours are `c-good/c-bad/c-warn/c-accent`. A game's CSS is its theme `[data-theme=<id>] { --accent; --on-accent; … }` (the shell sets `data-theme` on `<body>`, the picker on each game card) plus only its own elements. Team colours: `.t0/.t1` set `--tc`, `.tint` turns a subtree's accent into it. Helpers: `PG.ui.flipCard()`, `PG.ui.timer()`/`timerBar()` (one shared ticker for every `[data-deadline]`).
- **Sound** (`shell.js`, section "sound"): one speaker per room — the TV page if one is connected, else the host page; players' phones only vibrate. Music `games/<id>/music.mp3` (`core/music.mp3` in the lobby) loops via Web Audio over `[lead-in, lead-in + loop seconds]`, the loop being the game's `musicLoop` (`LOBBY_LOOP` in `shell.js`; no `musicLoop`, no music; the file carries 0.25 s past the loop point); a game's `music: { PHASE: 'low' | 'off' }` ducks it while people talk. The TV learns the phase from `st.phase` (`Room.stateJson` adds it for every game, even without a board). Effects: games call `api.sfx(name, delayMs)` from `onState`; a name in the game's `sfx: [...]` is `games/<id>/sfx/<name>.mp3`, otherwise one of the shared `CORE_SFX` in `core/sfx/` (a game's name wins over a shared one). Only the host page acts — plays it itself, or sends `{t:'fx'}` and `Hub` numbers the last few into the TVs' state (`st.fx`), since outboxes are conflated. Countdown ticks come from the shell's ticker (TV for board games, host otherwise, per `st.tvBoard`). Host toggles music/effects in the drawer → `Settings.music/sfx` → `st.snd`. Relay refuses assets over 180 KB (base64 must fit its 256 KB message limit), so a TV browser through the relay gets effects but no music. The audio is generated with ComfyUI (ACE-Step 1.5 for music, Stable Audio 3 for effects); the workflows and steps are in the `game-polish` skill (`.claude/skills/game-polish/comfy`).
- **Backgrounds** (`shell.js`, section "background"; `#bg` in `ui.css`): a game's `bg: 'still' | 'loop'` shows `games/<id>/bg.webp`, or `bg.webm`/`bg.mp4` with that still as the poster, in a fixed `#bg` under `body::before`, which turns into a veil (`--veil`); the TV takes `bg-tv.*` (landscape). Reduced motion gets the still. The shell sets `body[data-phase]` so a game's CSS can restyle its background per phase (TV included). Every file must stay under the relay's 180 KB. Made with ComfyUI: Z-Image Turbo still (portrait 1024×1792, landscape 1792×1024), MiniMax H3 FL2V with the same image as first and last frame, then the tail crossfaded into the head (~16 frames) and encoded VP9/H.264 at 540×960 / 960×540; workflows in the `game-polish` skill.
- **Rendering**: `keepInput` in `shell.js` merges new markup into the DOM (match by position + tag + first class; `data-k`/`id` = identity, `data-keep` = children owned by the game) instead of replacing it, so entrance animations play only for new elements and class changes animate. Use `animation-fill-mode: backwards` for entrance animations (`both` would pin `transform` and kill the `:active` press). A phase change adds `#app.enter` briefly to slide the screen in.
- **`:dev` module** compiles `app/src/main/java` directly (excluding `MainActivity`, `PartyService`, `update/**`, `tv/**`, and `games/herd/Judge.kt`) with stubbed `android.*` classes in `dev/src/main/kotlin/android` and a stub Judge. Code in `core/` and `games/` must stay free of Android dependencies (other than what the stubs cover: `Context`, `AssetManager`, `Log`) or the dev build breaks. Android-only code goes in `MainActivity`, `PartyService`, `update/`, `tv/`, or Judge.
- **Herd AI judge** (`games/herd/Judge.kt`): optional ONNX model downloaded on the device; the dev build replaces it with a stub.
- **Self-update** (`update/`): checks GitHub `releases/latest`, downloads `PartyGames.apk`, installs via PackageInstaller — release asset name must stay `PartyGames.apk`.
