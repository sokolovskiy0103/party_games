---
name: game-polish
description: Take a working (MVP) party game to production quality. Adds its own visual style, animations, haptics, sound design (music + effects), a TV board view and an animated background. Use when the user asks to polish, finish, "довести до продакшену", or add design/sound/TV/background to a game. Args: the game id, and optionally which parts (style, motion, sound, tv, bg).
argument-hint: <game-id> [style|motion|sound|tv|bg ...]
---

# Polishing a game for production

The game is expected to already work as an MVP (see "New games: MVP first" in `CLAUDE.md`): rules are done and tested, and the UI uses only stock `ui.css` components. This skill changes the presentation. Don't change the rules here. If you find a rules bug, fix it separately and say so.

If the args name parts, do only those. Otherwise do every part, in the order below.

## 0. Learn the game first

- Read the Kotlin `games/<id>/` (all phases, what each viewer sees) and the web folder `assets/web/games/<id>/` (`game.js`, `game.css`, `rules.html`). Everything this skill adds goes into that folder.
- Play it in `gradle :dev:run --args="<n>"` with enough windows to reach every phase, and open one extra window with `?tv`.
- Write a short **phase map** for yourself: each phase, what happens, who acts, whether people are talking, and what moments deserve a flourish (secret reveal, last seconds, round result, win/loss).
- For reference, look at games that are already polished: `clocktower` (theme per phase, background, sounds), `bomb` (custom elements, many effects), `hat` and `codenames` (TV board).

## 1. Style

- Give the game its own identity in `[data-theme=<id>]`: `--accent`, `--on-accent`, and `--bg`/`--card`/`--bg2` if the mood calls for it. Derived shades are recomputed automatically, so don't hardcode them. Phase moods go through `body[data-theme=<id>]:has(.<id>.is-<phase>)` or `body[data-phase=…]`.
- Build custom elements only where a stock component can't express the game (a dial, a board, a wire). Scope them under `.<id>` and build them from tokens, never raw colours, so light/dark and team tint (`.t0/.t1`, `.tint`) keep working.
- Keep `.on` for selected state and `c-good/c-bad/c-warn/c-accent` for text colours. Check the layout at a 360 px phone width and on the TV.

## 2. Motion and haptics

- Use the `fx-*` classes from `ui.css` first. Entrance animations use `animation-fill-mode: backwards`, never `both`.
- `keepInput` merges the DOM, so entrances only play for new nodes. Give list items stable `data-k` so they animate on change rather than on every push, and toggle classes so `transition`s run. Use `data-keep` for subtrees the game draws itself.
- Use `PG.ui.flipCard()` for secret reveals and `PG.ui.timer()`/`timerBar()` for deadlines. The phase change already slides in through `#app.enter`.
- Respect `prefers-reduced-motion`: no large or looping motion there.
- For haptics, call `api.vibrate(ms)` from `onState` at moments that concern *this* player (your turn, you were picked, you lost). It is ignored on the host page.

## 3. Sound

Only one device makes sound: the TV, or the host's phone if there is no TV. Read the "Sound" bullet in `CLAUDE.md`.

- **Music:** `games/<id>/music.mp3`, and its loop length in seconds as `musicLoop` in `game.js` (no `musicLoop`, no music). The file carries ~0.25 s past the loop point, and the shell detects the lead-in itself. In `game.js`, set `music: { <PHASE>: 'low' | 'off' }`: `'off'` while one person has to be heard (explaining, asking), `'low'` during discussion, full volume otherwise.
- **Effects:** call `api.sfx('<name>', delayMs)` from `onState` when the state *changes*, by comparing with the previous state you kept. Never fire on every push. Reuse the shared effects first (`tick`, `tick_urgent`, `time_up`, `win`, `lose`, `correct`, `wrong`, `your_turn`, `reveal`, `whoosh`, `join`, `vote`, `card`, `suspense`). Game-specific ones are `games/<id>/sfx/<what>.mp3`, listed in `sfx: ['<what>', …]` in `game.js` and called by the short name. Don't reuse a shared name unless you mean to replace that sound in this game: the game's name wins. Countdown ticks come from the shell, so don't add your own.
- Effects must stay **under 180 KB** (relay limit). Music is ~0.9 MB and that's accepted: a TV going through the relay just gets no music.
- Generate them yourself with ComfyUI. See **Generating assets** below: `music` and `sfx`.

## 4. TV board

- Kotlin: `override fun board(now: Long): JSONObject` with **public info only**. It is usually `state(null, false, now)` if that is already spoiler-free, otherwise a dedicated snapshot. Add a test like `RoomTest.tvSeesTheBoardOnly` that asserts no role or secret word reaches the board.
- JS: `board(api)` draws a full-screen landscape view, usually `tv-split` (main stage left, players/score right). The TV is read from across the room, so use big type, few words, and timers through `PG.ui.timer()`. The TV never sends messages and has no buttons.
- Show what the room watches together: whose turn, timer, score, revealed results, the moment of reveal. Return null in phases where there is nothing to show, and the generic room screen takes over.

## 5. Background

- In `game.js`, set `bg: 'still'` (`games/<id>/bg.webp`) or `'loop'` (`bg.webm` + `bg.mp4`, with the webp as poster). TV versions are `bg-tv.*` (landscape). Tune the veil with `--veil` in the theme if the content is hard to read, and restyle per phase with `body[data-theme=<id>][data-phase=…] #bg > *` (see `games/clocktower/game.css`).
- Every file must stay **under 180 KB**.
- Generate it yourself with ComfyUI. See **Generating assets** below: `still` and `loop`.

## 6. Verify and finish

- `gradle :app:testReleaseUnitTest`, and `node --check` on the changed JS.
- Replay the whole game in `:dev:run` (host, players, `?tv`) and check that sounds fire once, music ducks in the right phases, nothing leaks onto the TV, and it is readable over the background. Also check reduced motion.
- Report what was done, which variant of each asset you picked and why, and anything the user still has to listen to or choose.

## Generating assets (ComfyUI)

ComfyUI Desktop runs locally at `http://127.0.0.1:8188` (RTX 5060 Ti, 16 GB). It is driven by `comfy` (comfy-cli) on PATH. Check it with `comfy --json env`: `data.server.running` must be true. If it isn't running, ask the user to start ComfyUI Desktop. Don't launch or install anything yourself.

**Templates** live in `comfy/` next to this file. They are API-format graphs taken from the files that are already in the game, so they carry the exact models and settings: `sfx.json`, `music.json`, `still.json`, `loop.json`. Run them with the helper, which patches fields, waits for the job and downloads the results:

```
python -I .claude/skills/game-polish/comfy/gen.py <sfx|music|still|loop> --out <scratchpad>/gen [--timeout 1800] NODE.FIELD=VALUE ...
```

It prints the downloaded paths. Always set a new `filename_prefix` (`pg/<id>_<name>` for audio, `pgbg/<id>_<p|l|pv|lv>` for backgrounds). Raw outputs also stay in ComfyUI's `…/Comfy-Desktop/ComfyUI-Shared/output/`. An unchanged graph is served from ComfyUI's cache, so change the seed for new variants. If a job fails with out-of-memory after switching model families, run `comfy free` and retry. Work in the scratchpad, and copy only final files into `assets/web/games/<id>/`.

ffmpeg with mp3/VP9/H.264/WebP is at `C:/Users/sokol/ttsenv/Scripts/ffmpeg.exe` (not on PATH; there is no ffprobe, so use `ffmpeg -i`).

You can't listen to audio, but you can look at images (Read the PNG) and video (extract a few frames with ffmpeg and Read them). So you pick images and videos yourself, and for audio you list the variant paths and ask the user which one to keep, unless they told you to choose.

### sfx: Stable Audio 3 (~5 s per batch)
- Fields: `3.text` is the prompt and must end with `Length: N seconds`. Also `5.seconds` (= N), `5.batch_size` (3 variants), `6.seed`, `8.filename_prefix`. Leave 8 steps, cfg 1, `lcm` as they are.
- Prompt: one short, dry one-shot, concrete source and material, e.g. `Single deep church bell toll at midnight, large bronze bell, long resonant decay echoing over a medieval town, eerie Length: 6 seconds`. Usually 1–6 s.
- Finish: trim the silence, fade the tail, normalise, mono 64 kbps:
  `ffmpeg -i in.flac -af "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,afade=t=out:st=<len-0.3>:d=0.3,loudnorm=I=-16:TP=-1.5" -ac 1 -ar 44100 -c:a libmp3lame -b:a 64k games/<id>/sfx/<name>.mp3`
  Compare its loudness with a neighbour such as `core/sfx/reveal.mp3` (`-af volumedetect`), and add the name to the game's `sfx` list.

### music: ACE-Step 1.5 XL turbo (90 s, batch 2)
- Fields on node 4: `tags` (style list), `lyrics` (`[Intro]\n[Instrumental]…`; wordless vocals only, written as `ooh`/`aah` under `[Choir]`), `bpm`, `keyscale`, `timesignature`, `duration`, `seed`. Keep `6.seconds` equal to `duration`, and set `8.seed` and `10.filename_prefix`.
- Tags: genre, instruments, mood, "cinematic background, no lyrics". Example: `dark gothic medieval, pipe organ, low strings, wordless choir humming ooh aah, slow tolling bells, mysterious and ominous, cinematic background, no lyrics`. It sits *under* talk, so keep it unobtrusive with no lead melody that grabs attention. Match the family sound of the existing tracks (the template carries clocktower's settings as an example).
- Loop: the loop length is a whole number of bars: `bars × 4 × 60 / bpm` (clocktower: 22 bars at 70 BPM = 75.4286 s). Pick a start on a bar line after any intro, cut `[start, start + loop + 0.25 s]`, encode stereo 96 kbps: `ffmpeg -ss <start> -t <loop+0.25> -i in.flac -c:a libmp3lame -b:a 96k games/<id>/music.mp3`. Write the loop length (not the file length) as `musicLoop` in `game.js`. The shell finds the lead-in itself. Render a seam check (the last 4 s of the loop followed by the first 4 s) and give it to the user to listen to.

### still: Z-Image Turbo (seconds)
- Fields: `4.text`, `6.width`/`6.height` (portrait 1024×1792, TV 1792×1024), `8.seed`, `10.filename_prefix`. Leave 8 steps, cfg 1, `res_multistep`, shift 3.
- Prompt: `Atmospheric background art for a mobile party game.` + scene + palette that fits the theme's `--accent`/`--bg`, + `painterly digital illustration, cinematic, soft focus, large calm empty area, no text, no letters`. For TV: `Wide cinematic panorama, full bleed …, no border, no frame`. The middle sits under the cards, so keep it calm. Generate 2–3 seeds, look at them, and keep one.
- Poster: `ffmpeg -i in.png -vf scale=540:960:flags=lanczos -c:v libwebp -quality 80 games/<id>/bg.webp` (TV: 960:540 → `bg-tv.webp`).

### loop: MiniMax H3 FL2V (a few minutes per clip)
- First `comfy upload <still.png>`, then set `6.image` to its file name. The template feeds that image as both the first and the last frame. Other fields: `7.prompt`, `7.width`/`7.height` (576×1024 portrait, 1024×576 TV), `7.length` (124 frames at 24 fps), `8.noise_seed`, `16.filename_prefix`. Use `--timeout 1800`.
- Prompt: `Seamless looping ambient background animation. The camera is completely static.` + 2–4 subtle motions already present in the picture (fog drifts, windows flicker, birds circle, clouds pass) + `The colours stay the same. Calm, subtle, slow motion. The last frame matches the first frame exactly. No text, no new objects.`
- Seam and encode: crossfade the tail into the first 16 frames (the result is 108 frames = 4.5 s), then encode both codecs:
  ```
  ffmpeg -i raw.mp4 -filter_complex "[0:v]split[a][b];[a]trim=start_frame=16,setpts=PTS-STARTPTS,fps=24[body];[b]trim=end_frame=16,setpts=PTS-STARTPTS,fps=24[head];[body][head]xfade=transition=fade:duration=0.6667:offset=3.8333,scale=540:960:flags=lanczos,format=yuv420p[v]" -map "[v]" -an -c:v libx264 -crf 18 seam.mp4
  ffmpeg -i seam.mp4 -c:v libvpx-vp9 -b:v 0 -crf 40 -row-mt 1 -an games/<id>/bg.webm
  ffmpeg -i seam.mp4 -c:v libx264 -profile:v main -crf 28 -preset veryslow -movflags +faststart -an games/<id>/bg.mp4
  ```
  (TV: `scale=960:540` and `bg-tv.*`.) Raise the crf if a file goes over 180 KB. Lower it while the file stays well under, aiming for ~150 KB. Check a frame at the seam for a visible jump.
- Commit message (on request) follows the repo style: a Ukrainian title with an emoji, player-facing notes, `---`, then technical notes.
