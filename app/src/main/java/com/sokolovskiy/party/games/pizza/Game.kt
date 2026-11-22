package com.sokolovskiy.party.games.pizza

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure game logic for «Піца-шатл» — a shouting co-op game. Not thread-safe: the server guards it with a lock.
 *
 * Every player has a panel of odd instruments and, at any time, one command on screen with a countdown —
 * almost always about an instrument on someone else's panel. Whoever has it must hear the command and act.
 * Any action that matches any active command counts: the delivery bar fills, a new command replaces it.
 * A command that runs out cools the pizza; at 0° the game is over. A full bar delivers the pizza (a level);
 * the next delivery brings new panels, less time, more instruments and hazards.
 *
 * Hazards ([Hazard]) come every [Level.hazardMs] or so. Personal ones hit one player: cheese fog, radio noise,
 * a sauce flood to wipe off, a broken instrument to fix, a shaken-up panel. Crew crises (a gravity well, a meteor
 * shower) need everyone: each player has a few taps to make before the deadline, and command clocks stop meanwhile.
 *
 * Disconnected players get no commands and no commands point at their panel; commands that did are dropped
 * without penalty and reissued.
 *
 * Flow: LOBBY -> PLAY (countdown, then commands) -> WARP -> PLAY … -> OVER.
 */
class Game(
    private val words: Words,
    private val random: Random = Random.Default,
    private val record: Record = Record(),
) {
    /** The best number of deliveries since the app started (kept by the module across sessions). */
    class Record { var best = 0 }

    enum class Phase { LOBBY, PLAY, WARP, OVER }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
        val panel = ArrayList<Control>()
        var cmd: Cmd? = null
        var cheeseUntil = 0L
        var glitchUntil = 0L
        /** Shaken-up panel: the phone shows it shaking until then. */
        var shakeUntil = 0L
        var sauce: Sauce? = null
        /** Commands this player carried out in this game. */
        var done = 0
        /** Counters for the phone's feedback: my command got done / ran out / I did someone's command. */
        var doneSeq = 0
        var failSeq = 0
        var hitSeq = 0

        fun clearHazards() { cheeseUntil = 0; glitchUntil = 0; shakeUntil = 0; sauce = null; panel.forEach { it.fixLeft = 0 } }
    }

    class Cmd(val id: Int, val control: Control, val value: Int, val text: String, var deadline: Long, val total: Long)

    /** Splats over a panel: [blobs] of them, laid out by the phone from [id]; dries by itself at [until]. */
    class Sauce(val id: Int, val blobs: Int, val until: Long)

    /** A crew crisis: everyone online must make their [left] taps (brace / shoot) before [deadline]. */
    class Crisis(val k: Hazard, val id: Int, val deadline: Long, val total: Long, val per: Int) {
        val left = LinkedHashMap<String, Int>()
    }

    /** The latest hazard, for the note on screens and the TV. [who] is null for a crew crisis. */
    class Event(val k: Hazard, val who: String?, val until: Long)

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var difficulty = Difficulty.NORMAL; private set
    var level = 0; private set
    lateinit var lv: Level; private set
    var address = ""; private set
    var progress = 0; private set
    var temp = MAX_TEMP; private set
    var delivered = 0; private set
    var totalDone = 0; private set
    var startAt = 0L; private set
    var started = false; private set
    var crisis: Crisis? = null; private set
    var hazard: Event? = null; private set
    var nextHazardAt = 0L; private set
    var notice: String? = null; private set
    var line = ""; private set
    /** Bumped on everything that has a sound; [last] says what. */
    var seq = 0; private set
    var last = ""; private set

    private var nextOrder = 0
    /** This level's panel maker, kept for players who join mid-level (unique ids and names across the ship). */
    private lateinit var maker: PanelMaker
    private var cmdSeq = 0
    private var hazardSeq = 0

    // ---------- players ----------

    fun add(id: String, name: String): Player {
        players[id]?.let { it.name = name; return it }
        val p = Player(id, name, nextOrder++)
        players[id] = p
        if (inGame()) p.panel += maker.make(id)
        return p
    }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        dropCommandsOn(p)
        crisis?.left?.remove(pid)
        if (inGame() && players.size < MIN_PLAYERS) cancel("Замало гравців — гру перервано.")
        else checkCrisis()
    }

    /** Returns true if anything changed. */
    fun setConnected(pid: String, on: Boolean): Boolean {
        val p = players[pid] ?: return false
        if (p.connected == on) return false
        p.connected = on
        if (!on) { p.cmd = null; dropCommandsOn(p); crisis?.left?.remove(pid); checkCrisis() }
        return true
    }

    private fun dropCommandsOn(p: Player) {
        val mine = p.panel.toSet()
        players.values.forEach { o -> if (o.cmd?.control in mine) o.cmd = null }
    }

    // ---------- settings & flow ----------

    fun inGame() = phase == Phase.PLAY || phase == Phase.WARP

    fun setDifficulty(d: Difficulty): Boolean {
        if (inGame()) return false
        difficulty = d; return true
    }

    fun canStart() = players.size >= MIN_PLAYERS

    fun startGame(now: Long): Boolean {
        if (inGame() || !canStart()) return false
        level = 1; temp = MAX_TEMP; delivered = 0; totalDone = 0; notice = null
        players.values.forEach { it.done = 0 }
        beginLevel(now)
        return true
    }

    fun next(now: Long): Boolean {
        if (phase != Phase.WARP) return false
        level++
        beginLevel(now)
        return true
    }

    private fun beginLevel(now: Long) {
        // the crew that is actually on board sets the pace; players who dropped out don't inflate the goal
        lv = Level.of(level, max(MIN_PLAYERS, players.values.count { it.connected }), difficulty)
        maker = PanelMaker(words, random, lv, "L$level-")
        players.values.forEach { p ->
            p.panel.clear(); p.panel += maker.make(p.id)
            p.cmd = null; p.clearHazards()
        }
        address = "${words.places.random(random)}, ${random.nextInt(1, 100)}"
        progress = 0
        started = false
        startAt = now + COUNTDOWN_MS
        crisis = null; hazard = null; nextHazardAt = 0
        phase = Phase.PLAY
        event("level")
    }

    fun abort() = cancel(null)

    private fun cancel(msg: String?) {
        phase = Phase.LOBBY
        notice = msg
        crisis = null; hazard = null
        players.values.forEach { it.cmd = null; it.panel.clear(); it.clearHazards() }
    }

    private fun event(k: String) { seq++; last = k }

    private fun playing() = phase == Phase.PLAY && started

    // ---------- actions ----------

    /** A player touched control [cid] on their own panel, setting / pressing [v]. Broken instruments ignore it. */
    fun act(pid: String, cid: String, v: Int, now: Long): Boolean {
        if (!playing()) return false
        val p = players[pid] ?: return false
        val c = p.panel.firstOrNull { it.id == cid } ?: return false
        if (c.broken || v !in c.min..c.max) return false
        if (c.type.stateful) {
            if (c.value == v) return false
            c.value = v
        }
        val owner = players.values.firstOrNull { it.cmd?.control === c && it.cmd?.value == v }
        if (owner != null) complete(owner, p, now)
        return true
    }

    private fun complete(owner: Player, actor: Player, now: Long) {
        owner.cmd = null
        progress++; totalDone++
        actor.done++; actor.hitSeq++
        owner.doneSeq++
        event("done")
        if (progress >= lv.goal) warp() else issue(owner, now)
    }

    /** One tap on a broken instrument; the last one fixes it. */
    fun fix(pid: String, cid: String): Boolean {
        if (!playing()) return false
        val c = players[pid]?.panel?.firstOrNull { it.id == cid } ?: return false
        if (!c.broken) return false
        c.fixLeft--
        if (!c.broken) event("fixed")
        return true
    }

    /** The phone wiped every splat off. */
    fun wipe(pid: String): Boolean {
        val p = players[pid] ?: return false
        if (!playing() || p.sauce == null) return false
        p.sauce = null
        if (hazard?.k == Hazard.SAUCE && hazard?.who == pid) hazard = null
        event("wiped")
        return true
    }

    /** Holding the pizza in a gravity well. */
    fun brace(pid: String) = crisisTap(pid, Hazard.GRAVITY)

    /** Shooting down one rock of a meteor shower. */
    fun rock(pid: String) = crisisTap(pid, Hazard.ASTEROIDS)

    private fun crisisTap(pid: String, k: Hazard): Boolean {
        val c = crisis ?: return false
        if (c.k != k) return false
        val n = c.left[pid] ?: return false
        if (n <= 0) return false
        c.left[pid] = n - 1
        checkCrisis()
        return true
    }

    private fun checkCrisis() {
        val c = crisis ?: return
        if (c.left.values.all { it <= 0 }) {
            crisis = null
            if (hazard?.k == c.k) hazard = null
            event("cleared")
        }
    }

    // ---------- commands ----------

    /** Gives [p] a new command, or none if nothing is free (the next tick tries again). */
    private fun issue(p: Player, now: Long) {
        val busy = players.values.mapNotNull { it.cmd?.control }.toSet()
        val free = players.values.filter { it.connected }.flatMap { it.panel }
            .filter { it !in busy && it.targets().isNotEmpty() }
        if (free.isEmpty()) { p.cmd = null; return }
        val others = free.filter { it.owner != p.id }
        val own = free.filter { it.owner == p.id }
        val othersChance = if (players.values.count { it.connected } <= 2) 60 else 75
        val pool = when {
            others.isEmpty() -> own
            own.isEmpty() -> others
            random.nextInt(100) < othersChance -> others
            else -> own
        }
        val c = pool.random(random)
        val v = c.targets().random(random)
        p.cmd = Cmd(++cmdSeq, c, v, text(c, v), now + lv.cmdMs, lv.cmdMs)
    }

    private fun text(c: Control, v: Int): String {
        val (key, value) = when (c.type) {
            Control.Type.BUTTON -> "button" to ""
            Control.Type.TOGGLE -> (if (v == 1) "on" else "off") to ""
            Control.Type.SEG -> "seg" to c.opts[v]
            Control.Type.SLIDER -> "slider" to "$v"
            Control.Type.DIAL -> "dial" to "$v"
            Control.Type.KEYPAD -> "keypad" to "$v"
            Control.Type.HOLD -> "hold" to ""
            Control.Type.COLORS -> "colors" to c.opts[v]
            Control.Type.COVER -> "cover" to ""
            Control.Type.ROCKER -> "rocker" to c.opts[v]
            Control.Type.FADER -> "fader" to "$v"
            Control.Type.COUNTER -> "counter" to "$v"
            Control.Type.PLUG -> "plug" to "$v"
            Control.Type.JOYSTICK -> "joystick" to c.opts[v]
            Control.Type.PULL -> "pull" to ""
            Control.Type.CRANK -> "crank" to ""
        }
        return words.phrase(key, c.name, value, random)
    }

    // ---------- timers ----------

    fun tick(now: Long): Boolean {
        if (phase != Phase.PLAY || now < startAt) return false
        var changed = false
        if (!started) {
            started = true; changed = true
            if (lv.hazardMs > 0) nextHazardAt = now + jitter(lv.hazardMs)
        }
        crisis?.let { c ->
            if (now >= c.deadline) {
                val failed = c.left.values.count { it > 0 }
                crisis = null; hazard = null
                if (failed > 0) { event("fail"); cool(lv.damage * min(2, failed)) }
                changed = true
                if (phase != Phase.PLAY) return true
            }
        }
        for (p in players.values.toList()) {
            val c = p.cmd ?: continue
            if (now < c.deadline) continue
            p.cmd = null
            p.failSeq++
            event("fail")
            cool(lv.damage)
            changed = true
            if (phase != Phase.PLAY) return true
        }
        for (p in players.values) {
            if (p.cheeseUntil in 1..now) { p.cheeseUntil = 0; changed = true }
            if (p.glitchUntil in 1..now) { p.glitchUntil = 0; changed = true }
            if (p.shakeUntil in 1..now) { p.shakeUntil = 0; changed = true }
            if (p.sauce?.let { it.until <= now } == true) { p.sauce = null; changed = true }
        }
        hazard?.let { if (it.until <= now && !it.k.global) { hazard = null; changed = true } }
        for (p in players.values) if (p.connected && p.cmd == null) { issue(p, now); if (p.cmd != null) changed = true }
        if (nextHazardAt in 1..now && crisis == null) {
            spawnHazard(now)
            nextHazardAt = now + jitter(lv.hazardMs)
            changed = true
        }
        return changed
    }

    private fun jitter(ms: Long) = ms * random.nextInt(70, 131) / 100

    /** Starts a random hazard of this level; [kind] forces one (tests). */
    fun spawnHazard(now: Long, kind: Hazard? = null) {
        val online = players.values.filter { it.connected }
        if (online.isEmpty() || crisis != null) return
        val k = kind ?: lv.hazards.random(random)
        if (k.global) {
            val per = if (k == Hazard.GRAVITY) 1 else ROCKS + min(2, (level - Hazard.ASTEROIDS.unlock).coerceAtLeast(0))
            val ms = if (k == Hazard.GRAVITY) GRAVITY_MS else ROCKS_MS
            val c = Crisis(k, ++hazardSeq, now + ms, ms, per)
            online.forEach { c.left[it.id] = per }
            crisis = c
            // nobody can work the panel meanwhile: commands wait
            players.values.forEach { it.cmd?.let { cmd -> cmd.deadline += ms } }
            hazard = Event(k, null, c.deadline)
        } else {
            val p = online.random(random)
            val until = when (k) {
                Hazard.CHEESE -> now + CHEESE_MS.also { p.cheeseUntil = now + it }
                Hazard.GLITCH -> now + GLITCH_MS.also { p.glitchUntil = now + it }
                Hazard.SAUCE -> {
                    p.sauce = Sauce(++hazardSeq, SAUCE_BLOBS + min(3, level / 2), now + SAUCE_MS)
                    now + NOTE_MS
                }
                Hazard.BREAK -> {
                    val n = if (level >= 5) 2 else 1
                    p.panel.filter { !it.broken }.shuffled(random).take(n).forEach { it.fixLeft = FIX_TAPS }
                    now + NOTE_MS
                }
                Hazard.SHUFFLE -> {
                    p.panel.shuffle(random)
                    p.shakeUntil = now + SHAKE_MS
                    now + NOTE_MS
                }
                else -> now + NOTE_MS
            }
            hazard = Event(k, p.id, until)
        }
        event("hazard")
    }

    private fun cool(d: Int) {
        temp = max(0, temp - d)
        if (temp == 0) over()
    }

    private fun endLevel() {
        players.values.forEach { it.cmd = null; it.clearHazards() }
        crisis = null; hazard = null
    }

    private fun warp() {
        phase = Phase.WARP
        delivered++
        temp = min(MAX_TEMP, temp + REPAIR)
        record.best = max(record.best, delivered)
        endLevel()
        line = words.warpLines.randomOrNull(random) ?: ""
        event("warp")
    }

    private fun over() {
        phase = Phase.OVER
        record.best = max(record.best, delivered)
        endLevel()
        line = words.overLines.randomOrNull(random) ?: ""
        event("over")
    }

    // ---------- views ----------

    /** Replaces about a third of the letters with ▒ — the same way for the same command, so it doesn't flicker. */
    private fun glitch(text: String, seed: Int): String {
        val r = Random(seed)
        return text.map { if (it.isLetterOrDigit() && r.nextInt(100) < 32) '▒' else it }.joinToString("")
    }

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("difficulty", difficulty.name)
        o.put("level", level).put("address", address)
        o.put("temp", temp).put("maxTemp", MAX_TEMP)
        o.put("delivered", delivered).put("totalDone", totalDone).put("best", record.best)
        o.put("seq", seq).put("last", last)
        notice?.let { o.put("notice", it) }
        if (inGame()) o.put("progress", progress).put("goal", lv.goal)
            .put("hazards", JSONArray(lv.hazards.map { it.name }))
            .put("types", JSONArray(lv.types.map { it.name }))
        if (phase == Phase.PLAY) {
            o.put("started", started)
            if (!started) o.put("startAt", startAt)
            crisis?.let { c ->
                o.put("crisis", JSONObject().put("k", c.k.name).put("id", c.id).put("deadline", c.deadline)
                    .put("total", c.total).put("per", c.per)
                    .put("done", JSONArray(c.left.filter { it.value <= 0 }.keys.toList()))
                    .put("crew", c.left.size))
            }
            hazard?.let { h ->
                o.put("hazard", JSONObject().put("k", h.k.name).put("until", h.until).apply { h.who?.let { put("who", it) } })
            }
        }
        if (phase == Phase.WARP || phase == Phase.OVER) o.put("line", line)

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("connected", p.connected).put("done", p.done)
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val m = JSONObject().put("id", me.id)
                .put("doneSeq", me.doneSeq).put("failSeq", me.failSeq).put("hitSeq", me.hitSeq)
            if (inGame()) m.put("panel", JSONArray(me.panel.map { c ->
                JSONObject().put("id", c.id).put("type", c.type.name).put("name", c.name.nom)
                    .put("min", c.min).put("max", c.max).put("value", c.value)
                    .apply {
                        if (c.opts.isNotEmpty()) put("opts", JSONArray(c.opts))
                        if (c.type == Control.Type.COLORS) put("css", JSONArray(words.colors.map { it.css }))
                        if (c.broken) put("broken", c.fixLeft)
                    }
            }))
            if (phase == Phase.PLAY) {
                me.cmd?.let { c ->
                    val text = if (me.glitchUntil > now) glitch(c.text, c.id) else c.text
                    m.put("cmd", JSONObject().put("id", c.id).put("text", text)
                        .put("deadline", c.deadline).put("total", c.total))
                }
                if (me.cheeseUntil > now) m.put("cheese", me.cheeseUntil)
                if (me.glitchUntil > now) m.put("glitch", me.glitchUntil)
                if (me.shakeUntil > now) m.put("shake", me.shakeUntil)
                me.sauce?.let { m.put("sauce", JSONObject().put("id", it.id).put("n", it.blobs)) }
                crisis?.left?.get(me.id)?.let { m.put("crisisLeft", it) }
            }
            o.put("me", m)
        }
        return o
    }

    /** The shared TV screen: public info only — no panels, no commands. Null in the lobby, where the TV shows the room. */
    fun boardJson(now: Long): JSONObject? = if (phase == Phase.LOBBY) null else stateJson(null, false, now)

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_TEMP = 100
        const val REPAIR = 20
        const val COUNTDOWN_MS = 5_000L
        const val CHEESE_MS = 7_000L
        const val GLITCH_MS = 9_000L
        const val SAUCE_MS = 25_000L
        const val SAUCE_BLOBS = 4
        const val SHAKE_MS = 1_500L
        const val FIX_TAPS = 5
        const val GRAVITY_MS = 5_000L
        const val ROCKS = 4
        const val ROCKS_MS = 7_000L
        /** How long a personal hazard stays named in the note when it has no timer of its own. */
        const val NOTE_MS = 5_000L
    }
}
