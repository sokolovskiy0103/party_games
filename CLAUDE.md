# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Android "party games" app (UI strings and README are in Ukrainian). The host's phone runs a Ktor server and all game logic; players join from a browser by scanning a QR code. See `README.md` for the user-facing overview and the "how to add a game" steps.

## Commands

No Gradle wrapper is committed; CI uses Gradle 8.14.3 + JDK 17 (use a local `gradle`, or Android Studio).

- Unit tests + release APK (what CI runs): `gradle :app:testReleaseUnitTest :app:assembleRelease`
- Single test class: `gradle :app:testReleaseUnitTest --tests "com.sokolovskiy.party.games.hat.GameTest"`
- Run the whole app on the desktop (no Android needed): `gradle :dev:run` — prints a host URL (with token) and a player URL; web assets are read straight from `app/src/main/assets`, so edits to `web/*` show on reload.
- Relay (Node ≥ 20): `cd relay && npm install && npm test`
- Web JS syntax check (CI): `for f in app/src/main/assets/web/*.js; do node --check "$f"; done`

CI (`.github/workflows`) runs on every push to `main`: relay test → web syntax check → unit tests → signed APK → GitHub release `v1.0.<run>`. The commit message becomes the release notes; the part above `---` is shown as "Що нового" in the in-app update dialog.

## Architecture

- **All game state lives on the host phone.** `core/Hub` owns the lock, state pushes and timers; `core/Room` holds players and which game is selected; `core/LocalServer` (Ktor) serves `assets/web` and the WebSocket. In "Інтернет" mode `core/RelayClient` connects the phone outward to the Node relay (`relay/server.js`, deployed on Render via `render.yaml`), which only forwards messages and proxies the web page from the phone. Root `index.js`/`server.js` just start the relay for when Render points at the repo root.
- **Game contract** (`core/GameModule.kt`): a `GameModule` is created once; a `GameSession` is created per pick. All session calls run under the room lock (no own synchronisation); `state()` is computed *per viewer* and must not leak hidden info (roles, secret words). Player identity belongs to `Room`; sessions key their data by player id. Modules are registered in `core/Hub.kt`. Optional `board(now)` is the TV view (a player page opened with `?tv`: a `Conn` with `tv = true` that never joins or sends game messages); it must contain only public info, and a null board means the TV shows the generic room screen. The web module draws it with `board(api)`. `tv/TvPresenter` shows `/?tv` on a presentation display when the host casts the phone's screen (Miracast / Smart View); the TV's QR comes from the server (`Hub.inviteUrl`), not from the TV page's own address.
- **Frontend** (`app/src/main/assets/web`): `shell.js/css` + `index.html` handle joining, lobby, QR, network toggle; each game is `<id>.js/css` registering via `PG.register('<id>', { render, click, onState, menu })`. `menu()` lists the host-only items of the shell's side drawer (☰ / back gesture) as `{ t, label }` objects or shortcut keys like `'newGame'` (`MENU_ITEMS` in `shell.js`); the shell renders them, asks "Точно?" and sends `{ t }` to the game, so games don't build menu buttons or confirmations themselves.
- **Design system** (`ui.css`, loaded before everything else): tokens on `:root`, derived shades (`--accent-soft/-line/-text`, `--good-bg`…) recomputed on `body, [data-theme], .tint`; components (`card` + `flat/hl/dashed/team`, `pick`, `chip`, `seg`, `switch`, `banner`, `notice`, `tag`, `badge`, `li`, `word`, `headline`, `hero`, `tiles`, `timer`/`bar`, `spectrum`/`pin`, `flip`) and `fx-*` animations. Selected state is `.on` everywhere; text colours are `c-good/c-bad/c-warn/c-accent`. A game's CSS is its theme `[data-theme=<id>] { --accent; --on-accent; … }` (the shell sets `data-theme` on `<body>`, the picker on each game card) plus only its own elements. Team colours: `.t0/.t1` set `--tc`, `.tint` turns a subtree's accent into it. Helpers: `PG.ui.flipCard()`, `PG.ui.timer()`/`timerBar()` (one shared ticker for every `[data-deadline]`).
- **Rendering**: `keepInput` in `shell.js` merges new markup into the DOM (match by position + tag + first class; `data-k`/`id` = identity, `data-keep` = children owned by the game) instead of replacing it, so entrance animations play only for new elements and class changes animate. Use `animation-fill-mode: backwards` for entrance animations (`both` would pin `transform` and kill the `:active` press). A phase change adds `#app.enter` briefly to slide the screen in.
- **`:dev` module** compiles `app/src/main/java` directly (excluding `MainActivity`, `PartyService`, `update/**`, `tv/**`, and `games/herd/Judge.kt`) with stubbed `android.*` classes in `dev/src/main/kotlin/android` and a stub Judge. Code in `core/` and `games/` must stay free of Android dependencies (other than what the stubs cover: `Context`, `AssetManager`, `Log`) or the dev build breaks. Android-only code goes in `MainActivity`, `PartyService`, `update/`, `tv/`, or Judge.
- **Herd AI judge** (`games/herd/Judge.kt`): optional ONNX model downloaded on the device; the dev build replaces it with a stub.
- **Self-update** (`update/`): checks GitHub `releases/latest`, downloads `PartyGames.apk`, installs via PackageInstaller — release asset name must stay `PartyGames.apk`.
