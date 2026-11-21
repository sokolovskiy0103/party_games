package com.sokolovskiy.party.games.truel

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Труель» — a shootout of three (or more) with simultaneous secret moves and trick cards.
 * Not thread-safe: the server guards it with a lock.
 *
 * A match is a series of showdowns. In a showdown everyone starts with [START_HP] lives, [START_AMMO] bullet and
 * [START_HAND] cards (the lowest score gets one more). Each turn, while the clock runs and people talk, every living
 * player secretly picks an action — shoot someone (costs a bullet), dodge (not two turns in a row) or reload
 * (+1 bullet and a card) — and may add one card. When everyone has chosen, all moves are revealed and resolved at once,
 * so two players can shoot each other dead in the same turn.
 *
 * Resolution order: heal → jams → shots (each shot: ricochet → angel → dodge → vest → hit) → deaths → ammo boxes.
 *
 * Points: every kill (a hit on a player who dies that turn) +1; the last one standing +[SURVIVOR_POINTS]; if the
 * showdown lasts [MAX_TURNS] turns, everyone still alive gets +1. First to reach the target ends the match after that
 * showdown; ties on the target go to another showdown.
 *
 * Flow: LOBBY -> PLAN <-> REVEAL … -> OVER.
 */
class Game(private val random: Random = Random.Default) {
    enum class Phase { LOBBY, PLAN, REVEAL, OVER }
    enum class Act { SHOOT, DODGE, RELOAD, IDLE }

    /** Trick cards. [target]: the card names another player. [copies]: how many are in the deck. */
    enum class Card(val target: Boolean, val copies: Int) {
        VEST(false, 3),     // 🦺 absorbs the first hit this turn (not a sniper's)
        ANGEL(true, 2),     // 😇 another player can't be hurt this turn
        SNIPER(false, 2),   // 🎯 your shot ignores dodge and vest
        FAN(false, 2),      // 🌪️ your shot goes at every opponent, one bullet
        MIRROR(false, 2),   // 🪞 shots at you go back to the shooter
        JAM(true, 2),       // 🔧 the target's shot doesn't fire (the bullet is lost)
        MEDKIT(false, 2),   // 💊 +1 life (up to MAX_HP), before the shots
        AMMO(false, 2),     // 📦 +2 bullets at the end of the turn
    }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
        var score = 0
        var kills = 0
        var wins = 0
        /** In the current showdown. */
        var alive = false
        var hp = 0
        var ammo = 0
        val hand = ArrayList<Card>()
        /** Dodged last turn: may not dodge again now. */
        var tired = false
    }

    /** A secret move. [target] is for SHOOT, [cardTarget] for a targeted card. */
    class Move(val act: Act, val target: String?, val card: Card?, val cardTarget: String?)

    /** One line of a turn's report; a, b are player ids. */
    class Event(val k: String, val a: String, val b: String? = null, val n: Int = 0) {
        fun json(): JSONObject = JSONObject().put("k", k).put("a", a).apply {
            b?.let { put("b", it) }
            if (n != 0) put("n", n)
        }
    }

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var target = DEFAULT_TARGET; private set
    var planSeconds = DEFAULT_SECONDS; private set
    var cardsOn = true; private set

    var showdown = 0; private set
    var turn = 0; private set
    /** 0 = start the clock on the next tick. */
    var deadline = 0L; private set

    /** Secret until the reveal. */
    private val moves = LinkedHashMap<String, Move>()
    /** Moves just revealed (public). */
    private val shown = LinkedHashMap<String, Move>()
    val events = ArrayList<Event>()
    val gains = LinkedHashMap<String, Int>()
    var showdownOver = false; private set
    val ready = HashSet<String>()
    var notice: String? = null; private set

    private val deck = ArrayList<Card>()
    private val discard = ArrayList<Card>()
    private var nextOrder = 0

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        moves.remove(pid); ready.remove(pid)
        discard += p.hand
        if (phase != Phase.PLAN && phase != Phase.REVEAL) return
        if (players.size < MIN_PLAYERS) { cancel("Замало гравців — гру перервано."); return }
        if (phase == Phase.PLAN) {
            if (living().size <= 1) { endShowdown(); phase = Phase.REVEAL; shown.clear(); events.clear() }
            else if (allMoved()) resolve()
        } else if (allReady()) next(null, true)
    }

    private fun living() = players.values.filter { it.alive }

    // ---------- settings ----------

    private fun inGame() = phase == Phase.PLAN || phase == Phase.REVEAL

    fun setTarget(n: Int): Boolean {
        if (inGame() || n !in TARGETS) return false
        target = n; return true
    }

    fun setSeconds(n: Int): Boolean {
        if (inGame() || n !in SECONDS) return false
        planSeconds = n; return true
    }

    fun setCards(on: Boolean): Boolean {
        if (inGame()) return false
        cardsOn = on; return true
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    fun startGame(): Boolean {
        if (inGame() || !canStart()) return false
        players.values.forEach { it.score = 0; it.kills = 0; it.wins = 0 }
        showdown = 0
        notice = null
        newShowdown()
        return true
    }

    private fun newShowdown() {
        showdown++
        turn = 0
        deck.clear(); discard.clear()
        Card.entries.forEach { c -> repeat(c.copies) { deck += c } }
        deck.shuffle(random)
        val low = players.values.minOf { it.score }
        val behind = players.values.any { it.score != low }
        for (p in players.values) {
            p.alive = true; p.hp = START_HP; p.ammo = START_AMMO; p.tired = false
            p.hand.clear()
            if (cardsOn) repeat(START_HAND + if (behind && p.score == low) 1 else 0) { draw(p) }
        }
        showdownOver = false
        nextTurn()
    }

    private fun nextTurn() {
        turn++
        moves.clear(); shown.clear(); events.clear(); gains.clear(); ready.clear()
        deadline = 0
        phase = Phase.PLAN
    }

    private fun draw(p: Player) {
        if (!cardsOn || p.hand.size >= MAX_HAND) return
        if (deck.isEmpty()) { deck += discard; discard.clear(); deck.shuffle(random) }
        deck.removeLastOrNull()?.let { p.hand += it }
    }

    /** Checks a move against the rules; null if it's fine, else why not (for tests and the client). */
    fun check(pid: String, m: Move): String? {
        val p = players[pid] ?: return "no player"
        if (phase != Phase.PLAN || !p.alive) return "not now"
        when (m.act) {
            Act.SHOOT -> {
                if (p.ammo <= 0) return "no ammo"
                if (m.card != Card.FAN) {
                    val t = m.target?.let { players[it] }
                    if (t == null || !t.alive || t.id == pid) return "bad target"
                }
            }
            Act.DODGE -> if (p.tired) return "tired"
            Act.RELOAD, Act.IDLE -> {}
        }
        val c = m.card ?: return null
        if (c !in p.hand) return "no card"
        if ((c == Card.SNIPER || c == Card.FAN) && m.act != Act.SHOOT) return "card needs a shot"
        if (c.target) {
            val t = m.cardTarget?.let { players[it] }
            if (t == null || !t.alive || t.id == pid) return "bad card target"
        }
        return null
    }

    /** A player's secret move; can be changed until everyone has moved. */
    fun move(pid: String, m: Move): Boolean {
        if (check(pid, m) != null) return false
        moves[pid] = Move(m.act, if (m.act == Act.SHOOT && m.card != Card.FAN) m.target else null,
            m.card, if (m.card?.target == true) m.cardTarget else null)
        if (allMoved()) resolve()
        return true
    }

    fun hasMoved(pid: String) = pid in moves

    private fun allMoved() = living().all { it.id in moves }

    /** Starts the turn clock lazily and plays the turn when it runs out (anyone silent stands still). */
    fun tick(now: Long): Boolean {
        if (phase != Phase.PLAN || planSeconds <= 0) return false
        if (deadline == 0L) { deadline = now + planSeconds * 1000L; return true }
        if (now < deadline) return false
        resolve()
        return true
    }

    /** Host: play the turn now; whoever hasn't chosen stands still. */
    fun forceResolve(isHost: Boolean): Boolean {
        if (!isHost || phase != Phase.PLAN) return false
        resolve()
        return true
    }

    private fun ev(k: String, a: String, b: String? = null, n: Int = 0) { events += Event(k, a, b, n) }

    private fun resolve() {
        val alive = living()
        val mv = alive.associate { it.id to (moves[it.id] ?: Move(Act.IDLE, null, null, null)) }
        shown.clear(); shown.putAll(mv)
        moves.clear()
        events.clear(); gains.clear()

        // cards leave the hands
        for ((id, m) in mv) m.card?.let { players[id]!!.hand.remove(it); discard += it }

        // heal
        for ((id, m) in mv) if (m.card == Card.MEDKIT) {
            val p = players[id]!!
            val before = p.hp
            p.hp = minOf(MAX_HP, p.hp + 1)
            ev("heal", id, n = p.hp - before)
        }

        // jams
        val jammed = mv.filter { it.value.card == Card.JAM }.mapNotNull { it.value.cardTarget }.toSet()
        val angels = mv.filter { it.value.card == Card.ANGEL }.mapNotNull { it.value.cardTarget }.toSet()
        val vests = mv.filter { it.value.card == Card.VEST }.keys.toMutableSet()
        val mirrors = mv.filter { it.value.card == Card.MIRROR }.keys

        // shots
        val dmg = HashMap<String, Int>()
        val hitBy = HashMap<String, MutableSet<String>>()
        for ((id, m) in mv) {
            if (m.act != Act.SHOOT) continue
            val p = players[id]!!
            p.ammo--
            if (id in jammed) { ev("jam", id); continue }
            val sniper = m.card == Card.SNIPER
            val targets = if (m.card == Card.FAN) alive.map { it.id }.filter { it != id } else listOfNotNull(m.target)
            for (t0 in targets) {
                var t = t0
                // a ricochet turns the shot on its shooter; the kill, if any, belongs to the mirror's owner
                if (t in mirrors) { ev("mirror", t, id); t = id }
                val credit = if (t == id) t0 else id
                when {
                    t in angels -> ev("angel", t, id)
                    !sniper && mv[t]?.act == Act.DODGE -> ev("dodge", t, id)
                    !sniper && t in vests -> { vests -= t; ev("vest", t, id) }
                    else -> {
                        dmg[t] = (dmg[t] ?: 0) + 1
                        hitBy.getOrPut(t) { LinkedHashSet() } += credit
                        ev(if (sniper) "snipe" else "hit", id, t)
                    }
                }
            }
        }

        // damage and deaths, all at once
        val died = ArrayList<Player>()
        for ((t, d) in dmg) {
            val p = players[t] ?: continue
            p.hp -= d
            if (p.hp <= 0) { p.hp = 0; p.alive = false; died += p }
        }
        for (p in died) {
            ev("dead", p.id)
            for (k in hitBy[p.id].orEmpty()) {
                players[k]?.let { it.kills++; it.score++; gains[k] = (gains[k] ?: 0) + 1 }
            }
            discard += p.hand; p.hand.clear()
        }

        // reload, ammo boxes, tiredness
        for ((id, m) in mv) {
            val p = players[id] ?: continue
            p.tired = m.act == Act.DODGE
            if (!p.alive) continue
            if (m.act == Act.RELOAD) { p.ammo = minOf(MAX_AMMO, p.ammo + 1); draw(p) }
            if (m.card == Card.AMMO) { val b = p.ammo; p.ammo = minOf(MAX_AMMO, p.ammo + 2); ev("ammo", id, n = p.ammo - b) }
        }

        val left = living()
        if (left.size <= 1 || turn >= MAX_TURNS) endShowdown()
        deadline = 0
        phase = Phase.REVEAL
    }

    private fun endShowdown() {
        val left = living()
        when {
            left.size == 1 -> left[0].let { it.score += SURVIVOR_POINTS; it.wins++; gains[it.id] = (gains[it.id] ?: 0) + SURVIVOR_POINTS }
            left.size > 1 -> left.forEach { it.score += 1; gains[it.id] = (gains[it.id] ?: 0) + 1 }
        }
        showdownOver = true
    }

    /** True when the match is over: someone reached the target and is alone on top. */
    private fun matchWon(): Boolean {
        val top = players.values.maxOfOrNull { it.score } ?: return false
        return top >= target && players.values.count { it.score == top } == 1
    }

    /** REVEAL: a player is done reading; once all living (connected) players are, go on. */
    fun setReady(pid: String): Boolean {
        if (phase != Phase.REVEAL || pid !in players) return false
        ready += pid
        if (allReady()) next(null, true)
        return true
    }

    private fun allReady(): Boolean {
        val need = players.values.filter { it.connected && (it.alive || showdownOver) }
        return need.isNotEmpty() && need.all { it.id in ready }
    }

    /** REVEAL -> next turn / next showdown / OVER. The host may push it. */
    fun next(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.REVEAL || !isHost) return false
        when {
            !showdownOver -> nextTurn()
            matchWon() -> { phase = Phase.OVER; shown.clear(); events.clear(); gains.clear() }
            else -> newShowdown()
        }
        return true
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        moves.clear(); shown.clear(); events.clear(); gains.clear(); ready.clear()
        players.values.forEach { it.alive = false; it.hand.clear() }
        deadline = 0
        notice = msg
    }

    fun abort() { if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.") }

    fun newGame() {
        cancel("")
        notice = null
        players.values.forEach { it.score = 0; it.kills = 0; it.wins = 0 }
        showdown = 0
    }

    // ---------- state for clients ----------

    private fun Move.json() = JSONObject().put("act", act.name).apply {
        target?.let { put("target", it) }
        card?.let { put("card", it.name) }
        cardTarget?.let { put("cardTarget", it) }
    }

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("target", target).put("targets", JSONArray(TARGETS))
        o.put("seconds", planSeconds).put("secondsList", JSONArray(SECONDS))
        o.put("cards", cardsOn)
        o.put("showdown", showdown).put("turn", turn).put("maxTurns", MAX_TURNS)
        o.put("maxHp", MAX_HP).put("maxAmmo", MAX_AMMO)
        if (phase == Phase.PLAN && deadline > 0) o.put("deadline", deadline).put("total", planSeconds * 1000L)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        if (phase == Phase.REVEAL) {
            o.put("showdownOver", showdownOver)
            o.put("events", JSONArray(events.map { it.json() }))
            o.put("moves", JSONObject().apply { shown.forEach { (id, m) -> put(id, m.json()) } })
            o.put("ending", showdownOver && matchWon())
        }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("score", p.score).put("kills", p.kills).put("wins", p.wins)
                .put("alive", p.alive).put("hp", p.hp).put("ammo", p.ammo)
                .put("cards", p.hand.size).put("tired", p.tired)
                .put("moved", p.id in moves).put("ready", p.id in ready)
                .put("gain", gains[p.id] ?: 0)
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name)
                .put("hand", JSONArray(me.hand.map { it.name }))
                .apply { moves[me.id]?.let { put("move", it.json()) } })
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val START_HP = 2
        const val MAX_HP = 3
        const val START_AMMO = 1
        const val MAX_AMMO = 3
        const val START_HAND = 2
        const val MAX_HAND = 3
        const val MAX_TURNS = 8
        const val SURVIVOR_POINTS = 3
        const val DEFAULT_TARGET = 7
        const val DEFAULT_SECONDS = 45
        val TARGETS = listOf(5, 7, 10)
        val SECONDS = listOf(0, 30, 45, 60)
    }
}
