package com.sokolovskiy.party.games.bomb

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Сапери», a co-op wire-cutting deduction game in the spirit of Bomb Busters.
 * Not thread-safe: the server guards it with a lock.
 *
 * All wires of the bomb are dealt out; every player holds theirs sorted ascending and hidden from the others.
 * Blue wires are numbered 1–12 (four of each, fewer numbers on the training level). Yellow wires sit between
 * blues (3.1 lies between 3 and 4) and all count as one value, «жовтий». Red wires (3.5) are the bomb: cutting one
 * is an instant loss. Cut wires stay in place face up, so positions are part of the puzzle.
 *
 * Before play each player shows one of their blue wires to everyone. Then on your turn you do one thing:
 *  - dual cut: point at a teammate's wire and name a value you hold yourself. Right — both wires are cut.
 *    Wrong — the detonator ticks and the wire gets an info token with its real value. A red wire explodes.
 *  - solo cut: if every uncut wire of a value is in your hand (the public cut counts tell you), cut them all.
 *  - reveal reds: if only red wires are left in your hand, show them.
 * Everyone wins when every non-red wire is cut; the bomb goes off on a red wire or on one mistake too many.
 *
 * Flow: LOBBY -> SETUP (everyone shows a wire) -> PLAY -> WIN | LOSE -> (next level / retry) SETUP …
 */
class Game(private val random: Random = Random.Default) {
    enum class Phase { LOBBY, SETUP, PLAY, WIN, LOSE }
    enum class Kind(val code: String) { BLUE("b"), YELLOW("y"), RED("r") }

    /**
     * [maxBlue]: blue values 1..maxBlue (four of each). [yellows] of [yellowShown] candidate yellow values are in
     * the bomb (the candidates are public, which of them are real is not). [reds]: hidden red wires.
     */
    class Level(val name: String, val desc: String, val maxBlue: Int, val yellows: Int, val yellowShown: Int, val reds: Int)

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
    }

    /** [key] is the value ×10: blue 7 = 70, yellow 7.1 = 71, red 7.5 = 75 — so sorting by key sorts the hand. */
    class Wire(val key: Int, val kind: Kind) {
        var cut = false
        /** An info token in front of it shows its value (yellow: just «жовтий»). */
        var info = false
        val value get() = key / 10
        val label get() = if (kind == Kind.BLUE) "$value" else "$value.${key % 10}"
        fun matches(guess: Int) = if (guess == YELLOW) kind == Kind.YELLOW else kind == Kind.BLUE && value == guess
    }

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var level = 1; private set
    /** Highest level cleared in this session. */
    var best = 0; private set
    /** Bumped on every deal so clients can reset per-game UI. */
    var round = 0; private set
    var mistakes = 0; private set
    /** Mistakes the bomb survives; the next one explodes it. */
    var maxMistakes = 0; private set
    var turn: String? = null; private set
    /** Each dealt player's wires, sorted. Players who joined mid-game have no entry. */
    val hands = LinkedHashMap<String, MutableList<Wire>>()
    /** Players who already showed their starting wire. */
    val ready = HashSet<String>()
    /** Public candidate yellow values (keys), and how many reds are in the bomb. */
    var yellowShown: List<Int> = emptyList(); private set
    var yellowsInPlay = 0; private set
    var redsInPlay = 0; private set
    var notice: String? = null; private set
    val log = ArrayList<String>()
    /** Last action, for client effects: bumped [seq] + kind ("hit", "miss", "solo", "reds", "boom", "win"). */
    var seq = 0; private set
    var lastKind = ""; private set
    var lastTarget: String? = null; private set
    /** Index of the wire hit by the last dual cut in [lastTarget]'s hand. */
    var lastIndex = -1; private set

    private var nextOrder = 0

    val current get() = LEVELS[level - 1]

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    /** Their wires leave with them, so the bomb can't be finished: the game in progress is called off. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        ready.remove(pid)
        if (hands.remove(pid) == null) return
        if (phase == Phase.SETUP || phase == Phase.PLAY) cancel("${p.name} вийшов(ла) — гру скасовано.")
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    fun setLevel(n: Int): Boolean {
        if (phase == Phase.SETUP || phase == Phase.PLAY || n !in 1..LEVELS.size) return false
        level = n
        return true
    }

    /** LOBBY/WIN/LOSE -> SETUP on the selected level. */
    fun start(): Boolean {
        if (phase == Phase.SETUP || phase == Phase.PLAY || !canStart()) return false
        deal()
        return true
    }

    /** WIN -> SETUP on the next level (or the same one again after the last). */
    fun nextLevel(): Boolean {
        if (phase != Phase.WIN || !canStart()) return false
        if (level < LEVELS.size) level++
        deal()
        return true
    }

    private fun deal() {
        val lv = current
        val ids = players.values.sortedBy { it.joinOrder }.map { it.id }
        val wires = ArrayList<Wire>()
        for (v in 1..lv.maxBlue) repeat(4) { wires += Wire(v * 10, Kind.BLUE) }
        // Yellow and red wires sit between two blues, so their numbers run up to maxBlue - 1.
        val between = (1 until lv.maxBlue).toList()
        val shown = between.shuffled(random).take(lv.yellowShown).map { it * 10 + 1 }
        shown.shuffled(random).take(lv.yellows).forEach { wires += Wire(it, Kind.YELLOW) }
        between.shuffled(random).take(lv.reds).forEach { wires += Wire(it * 10 + 5, Kind.RED) }
        yellowShown = shown.sorted()
        yellowsInPlay = lv.yellows
        redsInPlay = lv.reds

        wires.shuffle(random)
        hands.clear()
        ids.forEach { hands[it] = ArrayList() }
        wires.forEachIndexed { i, w -> hands[ids[i % ids.size]]!! += w }
        hands.values.forEach { h -> h.sortBy { it.key } }

        ready.clear()
        // A player can't show a blue wire if they hold none (possible only with tiny hands): skip them.
        hands.filterValues { h -> h.none { it.kind == Kind.BLUE } }.keys.forEach { ready += it }
        mistakes = 0
        maxMistakes = maxOf(2, ids.size - 1)
        turn = null
        log.clear()
        notice = null
        lastKind = ""; lastTarget = null; lastIndex = -1
        round++
        phase = Phase.SETUP
        if (ready.size == hands.size) beginPlay()
    }

    /** SETUP: the player shows one of their blue wires to everyone. */
    fun showWire(pid: String, index: Int): Boolean {
        if (phase != Phase.SETUP || pid in ready) return false
        val w = hands[pid]?.getOrNull(index)?.takeIf { it.kind == Kind.BLUE } ?: return false
        w.info = true
        ready += pid
        if (ready.containsAll(hands.keys)) beginPlay()
        return true
    }

    private fun beginPlay() {
        phase = Phase.PLAY
        turn = hands.keys.toList().random(random)
        log += "🎲 Першим ходить ${name(turn)}"
    }

    // ---------- actions ----------

    private fun myTurn(pid: String) = phase == Phase.PLAY && turn == pid && hands.containsKey(pid)

    fun holds(pid: String, guess: Int) = hands[pid].orEmpty().any { !it.cut && it.matches(guess) }

    /** Can [pid] solo-cut [guess]: they hold every uncut wire of it. */
    fun canSolo(pid: String, guess: Int) =
        holds(pid, guess) && hands.all { (id, h) -> id == pid || h.none { !it.cut && it.matches(guess) } }

    /** Dual cut: [pid] says wire [index] of [target] is [guess] (1..12, or [YELLOW]). */
    fun dual(pid: String, target: String, index: Int, guess: Int): Boolean {
        if (!myTurn(pid) || target == pid || !holds(pid, guess)) return false
        val w = hands[target]?.getOrNull(index)?.takeIf { !it.cut } ?: return false
        val what = guessName(guess)
        lastTarget = target; lastIndex = index
        when {
            w.kind == Kind.RED -> {
                w.cut = true
                log += "💥 ${name(pid)} → ${name(target)}: «$what» — це був червоний дріт!"
                lose()
            }
            w.matches(guess) -> {
                w.cut = true
                hands[pid]!!.first { !it.cut && it.matches(guess) }.cut = true
                log += "✂️ ${name(pid)} → ${name(target)}: «$what» — влучно"
                event("hit")
                if (!checkWin()) passTurn()
            }
            else -> {
                w.info = true
                mistakes++
                log += "❌ ${name(pid)} → ${name(target)}: «$what» — ні, там ${if (w.kind == Kind.YELLOW) "жовтий" else w.label}"
                if (mistakes > maxMistakes) { log += "💥 Детонатор спрацював"; lose() } else { event("miss"); passTurn() }
            }
        }
        return true
    }

    fun solo(pid: String, guess: Int): Boolean {
        if (!myTurn(pid) || !canSolo(pid, guess)) return false
        val cut = hands[pid]!!.filter { !it.cut && it.matches(guess) }
        cut.forEach { it.cut = true }
        lastTarget = pid; lastIndex = -1
        log += "✂️ ${name(pid)} сам(а) перерізає «${guessName(guess)}» ×${cut.size}"
        event("solo")
        if (!checkWin()) passTurn()
        return true
    }

    /** Allowed only when every uncut wire in the hand is red. */
    fun revealReds(pid: String): Boolean {
        if (!myTurn(pid)) return false
        val left = hands[pid]!!.filter { !it.cut }
        if (left.isEmpty() || left.any { it.kind != Kind.RED }) return false
        left.forEach { it.info = true }
        lastTarget = pid; lastIndex = -1
        log += "🔴 ${name(pid)} показує червоні дроти (${left.size})"
        event("reds")
        passTurn()
        return true
    }

    /** True if [pid] still has to act at some point: anything uncut that isn't an already shown red. */
    private fun active(pid: String) = hands[pid].orEmpty().any { !it.cut && !(it.kind == Kind.RED && it.info) }

    private fun passTurn() {
        val order = hands.keys.toList()
        val from = order.indexOf(turn)
        turn = (1..order.size).map { order[(from + it) % order.size] }.firstOrNull { active(it) }
    }

    private fun checkWin(): Boolean {
        if (hands.values.flatten().any { !it.cut && it.kind != Kind.RED }) return false
        best = maxOf(best, level)
        phase = Phase.WIN
        turn = null
        log += "🎉 Бомбу знешкоджено!"
        event("win")
        return true
    }

    private fun lose() {
        phase = Phase.LOSE
        turn = null
        event("boom")
    }

    private fun event(kind: String) { lastKind = kind; seq++ }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        hands.clear(); ready.clear(); log.clear()
        turn = null
        notice = msg
    }

    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    fun newGame() {
        cancel("")
        notice = null
        level = 1; best = 0
    }

    private fun name(pid: String?) = players[pid]?.name ?: "?"

    private fun guessName(g: Int) = if (g == YELLOW) "жовтий" else "$g"

    // ---------- state for clients ----------

    private fun wireJson(w: Wire, full: Boolean): JSONObject {
        val o = JSONObject()
        if (w.cut) o.put("c", true)
        if (w.info) o.put("i", true)
        when {
            full || w.cut || (w.info && w.kind != Kind.YELLOW) -> o.put("k", w.kind.code).put("v", w.label)
            w.info -> o.put("k", w.kind.code) // a yellow info token says «yellow», not which one
        }
        return o
    }

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val lv = current
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("level", level)
        o.put("best", best)
        o.put("levels", JSONArray(LEVELS.mapIndexed { i, l -> JSONObject().put("n", i + 1).put("name", l.name).put("desc", l.desc) }))
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        val dealt = phase != Phase.LOBBY
        if (dealt) {
            o.put("mistakes", mistakes).put("maxMistakes", maxMistakes)
            turn?.let { o.put("turn", it) }
            o.put("maxBlue", lv.maxBlue)
            o.put("yellowShown", JSONArray(yellowShown.map { "${it / 10}.1" }))
            o.put("yellows", yellowsInPlay).put("reds", redsInPlay)
            val all = hands.values.flatten()
            o.put("cutBlue", JSONArray((1..lv.maxBlue).map { v -> all.count { it.cut && it.kind == Kind.BLUE && it.value == v } }))
            o.put("cutYellow", all.count { it.cut && it.kind == Kind.YELLOW })
            o.put("log", JSONArray(log.takeLast(LOG_SIZE).reversed()))
            o.put("seq", seq).put("last", lastKind)
            lastTarget?.let { o.put("lastTarget", it).put("lastIndex", lastIndex) }
        }

        val reveal = phase == Phase.WIN || phase == Phase.LOSE
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            val hand = hands[p.id]
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("inGame", hand != null && dealt)
                .put("ready", p.id in ready)
                .put("left", hand.orEmpty().count { !it.cut })
                .put("wires", JSONArray(hand.orEmpty().map { wireJson(it, reveal) }))
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val hand = hands[me.id]
            val m = JSONObject().put("id", me.id).put("name", me.name)
                .put("inGame", hand != null && dealt)
                .put("ready", me.id in ready)
                .put("wires", JSONArray(hand.orEmpty().map { wireJson(it, true) }))
            if (phase == Phase.PLAY && hand != null) {
                // What the player may name: every value they still hold (derived from their own hand only).
                val values = hand.filter { !it.cut && it.kind != Kind.RED }
                    .map { if (it.kind == Kind.YELLOW) YELLOW else it.value }.distinct()
                m.put("guesses", JSONArray(values))
                m.put("solo", JSONArray(values.filter { canSolo(me.id, it) }))
                m.put("canReveal", hand.any { !it.cut } && hand.filter { !it.cut }.all { it.kind == Kind.RED && !it.info })
            }
            o.put("me", m)
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 2
        /** Guess code for «yellow». */
        const val YELLOW = 0
        const val LOG_SIZE = 14

        val LEVELS = listOf(
            Level("Тренування", "Лише сині дроти 1–6", maxBlue = 6, yellows = 0, yellowShown = 0, reds = 0),
            Level("Повний набір", "Сині дроти 1–12", maxBlue = 12, yellows = 0, yellowShown = 0, reds = 0),
            Level("Жовта тривога", "Сині 1–12 і 2 жовті", maxBlue = 12, yellows = 2, yellowShown = 2, reds = 0),
            Level("Червоний дріт", "Сині 1–12 і 1 червоний", maxBlue = 12, yellows = 0, yellowShown = 0, reds = 1),
            Level("Мінне поле", "2 жовті й 1 червоний", maxBlue = 12, yellows = 2, yellowShown = 2, reds = 1),
            Level("Туман", "2 жовті з 3 можливих і 2 червоні", maxBlue = 12, yellows = 2, yellowShown = 3, reds = 2),
        )
    }
}
