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
- **Game contract** (`core/GameModule.kt`): a `GameModule` is created once; a `GameSession` is created per pick. All session calls run under the room lock (no own synchronisation); `state()` is computed *per viewer* and must not leak hidden info (roles, secret words). Player identity belongs to `Room`; sessions key their data by player id. Modules are registered in `core/Hub.kt`.
- **Frontend** (`app/src/main/assets/web`): `shell.js/css` + `index.html` handle joining, lobby, QR, network toggle; each game is `<id>.js/css` registering via `PG.register('<id>', { render, click, onState, menu })`. `menu()` returns host-only buttons shown in the shell's side drawer (☰, or the back gesture inside a game); clicking a `data-act` there other than `arm` closes the drawer.
- **`:dev` module** compiles `app/src/main/java` directly (excluding `MainActivity`, `PartyService`, `update/**`, and `games/herd/Judge.kt`) with stubbed `android.*` classes in `dev/src/main/kotlin/android` and a stub Judge. Code in `core/` and `games/` must stay free of Android dependencies (other than what the stubs cover: `Context`, `AssetManager`, `Log`) or the dev build breaks. Android-only code goes in `MainActivity`, `PartyService`, `update/`, or Judge.
- **Herd AI judge** (`games/herd/Judge.kt`): optional ONNX model downloaded on the device; the dev build replaces it with a stub.
- **Self-update** (`update/`): checks GitHub `releases/latest`, downloads `PartyGames.apk`, installs via PackageInstaller — release asset name must stay `PartyGames.apk`.
