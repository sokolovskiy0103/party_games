# AGENTS.md

Compact agent guidance for this repository. Deeper notes live in `CLAUDE.md` (architecture, design system, sound, backgrounds) and `README.md` (user-facing overview, how to add a game).

## Commands

- CI parity: `./gradlew :app:testReleaseUnitTest :app:assembleRelease` - Gradle wrapper is committed (8.14.3; CI: Gradle 8.14.3 + Temurin JDK 17).
- Single test: `./gradlew :app:testReleaseUnitTest --tests "com.sokolovskiy.party.games.<id>.GameTest"`.
- Desktop loop without Android: `./gradlew :dev:run` - prints host/player URLs and opens a browser grid (host + N players; default 4 panes, `--args="6"` for up to 9). Dev compiles `app/src/main/java` with Android stubs (Context/AssetManager/Log, Herd Judge) and excludes `MainActivity`, `PartyService`, `update/**`, `tv/**`, `games/herd/Judge.kt`.
- Relay: `cd relay && npm install && npm test` (Node >= 20; root `package.json` start/test also point at the relay for Render).
- Web JS syntax check: `find app/src/main/assets/web -name "*.js" -print0 | xargs -0 -n1 node --check`.

No lint/format/typecheck tooling is configured for Kotlin or JS - verify with the commands above, not by inventing new ones.

## CI / release

Push to `main`: relay test -> web syntax check -> unit tests -> signed APK -> GitHub release `v1.0.<run>`. Commit message becomes release notes; the part above `---` is shown as "ўо нового" in the in-app update dialog.

## Architecture essentials

- All game state lives on the host phone; the host runs the Ktor server and all logic. `core/Hub` owns the lock, state pushes and timers; `core/Room` holds players and the game pick; `LocalServer`/`RelayClient` are the transports. Game contract in `core/GameModule.kt`.
- Game contract: module created once, session per pick; all session calls run under the room lock; `state()` is computed per viewer and must not leak hidden info; identity lives in `Room`, session data is keyed by player id; optional TV `board(now)` (null = generic TV screen).
- Frontend (`app/src/main/assets/web`): the shell loads `games/<id>/game.css` + `game.js` itself for every game in `st.games`; games register via `PG.register('<id>', { render, click, onState, menu })`; `menu()` returns host-only drawer items and the shell handles the host confirmation and sends `{ t }` to the game, so games do not build menu buttons or confirmations themselves.

## Web gotchas

- Rendering uses `keepInput` DOM merging (position + tag + first class; `data-k`/`id` identity, `data-keep` game-owned children). Entrance animations need `animation-fill-mode: backwards`; a phase change adds `#app.enter`.
- A game render returns a string or `{ html, bar }`; host-only screens can hang off `g.extra(api)`. TV board: `g.board(api)`, tick via the shell ticker.
- Relay refuses assets over ~180 KB (base64 must fit the 256 KB WS message limit) - TV via relay gets effects but no music.

## New games

Default to an MVP; production polish (`game-polish` skill) only when asked. MVP: Kotlin module + session (full rules, per-viewer `state()`, timers in `tick()`, reconnect/leave, hidden-info tests); web `game.js` built only from existing `ui.css` components and `PG.ui`, theme-only CSS, `rules.html` fragment in Ukrainian, content lists in `assets/data/`.

## Testing quirks

- Unit tests: JUnit 4 (`:app:testReleaseUnitTest`); `unitTests.isReturnDefaultValues = true`, so Android framework calls return defaults - fake/stub what you test.
- Game tests live at `app/src/test/java/com/sokolovskiy/party/games/<id>/GameTest.kt`.

## Reference

- `CLAUDE.md` - deep design system, sound, backgrounds, MVP checklist.
- `README.md` - user-facing overview, relay deploy, how to add a game.
