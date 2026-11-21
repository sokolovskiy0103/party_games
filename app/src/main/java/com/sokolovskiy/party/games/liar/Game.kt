package com.sokolovskiy.party.games.liar

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Брехун», a bluffing dice game (the classic "liar's dice"). Not thread-safe: the server
 * guards it with a lock.
 *
 * Everyone has a few dice that only they can see. Each round all dice are rolled; then, in turn, a player either
 * raises the bid ("on the table there are at least Q dice showing F": a bigger Q, or the same Q with a bigger F)
 * or calls the previous bid: «Брешеш!» (fewer than Q) or, if enabled, «Рівно!» (exactly Q). All dice are revealed.
 * With [wild] ones count as any face, and nobody can bid on ones.
 *
 * «Брешеш!»: if the bid holds, the caller loses a die, otherwise the bidder does.
 * «Рівно!»: if right, the caller gets a die back (up to [startDice]); if wrong, the caller loses one.
 * A player without dice is out; the last one with dice wins. Whoever lost a die starts the next round.
 *
 * Flow: LOBBY -> BID -> REVEAL -> BID … -> OVER.
 */
class Game(private val random: Random = Random.Default) {
    enum class Phase { LOBBY, BID, REVEAL, OVER }
    enum class Call { LIAR, EXACT }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
        /** Dealt into the current game (late joiners sit out until the next one). */
        var inGame = false
        var count = 0
        var dice: List<Int> = emptyList()
        val alive get() = inGame && count > 0
    }

    data class Bid(val q: Int, val f: Int, val by: String)

    /** How a round ended. [loser] lost a die (null: nobody), [gainer] got one back; [out]: the loser is out. */
    class Result(
        val call: Call, val caller: String, val bid: Bid, val actual: Int,
        val loser: String?, val gainer: String?, val out: Boolean,
    )

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var startDice = 5; private set
    var wild = true; private set
    var exactCall = true; private set

    /** Bumped on every roll so clients can reset per-round UI. */
    var round = 0; private set
    var turn: String? = null; private set
    val bids = ArrayList<Bid>()
    val bid get() = bids.lastOrNull()
    var result: Result? = null; private set
    /** Everyone's dice of the round just called, shown in REVEAL/OVER. */
    var revealed: Map<String, List<Int>> = emptyMap(); private set
    var winner: String? = null; private set
    var notice: String? = null; private set

    private var starter: String? = null
    private var nextOrder = 0

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    private fun seated() = players.values.filter { it.alive }.sortedBy { it.joinOrder }

    /** The next player with dice after the seat [order] (cyclic), or null if none. */
    private fun aliveAfter(order: Int): Player? {
        val s = seated()
        return s.firstOrNull { it.joinOrder > order } ?: s.firstOrNull()
    }

    /** A player leaving mid-round takes their dice with them, so the round is re-rolled. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (!p.alive || phase == Phase.LOBBY || phase == Phase.OVER) return
        val left = seated()
        if (left.size < 2) {
            if (left.size == 1) finish(left[0].id)
            else cancel("${p.name} вийшов(ла) — гру зупинено.")
            return
        }
        if (starter == pid) starter = aliveAfter(p.joinOrder)?.id
        if (phase == Phase.BID) {
            starter = if (turn == pid) aliveAfter(p.joinOrder)?.id else turn
            roll()
            notice = "${p.name} вийшов(ла) — кості перекинуто."
        }
    }

    // ---------- settings ----------

    fun canStart() = players.size >= MIN_PLAYERS
    private fun playing() = phase == Phase.BID || phase == Phase.REVEAL

    fun setDice(n: Int): Boolean {
        if (playing() || n !in MIN_DICE..MAX_DICE) return false
        startDice = n; return true
    }

    fun setWild(v: Boolean): Boolean {
        if (playing()) return false
        wild = v; return true
    }

    fun setExact(v: Boolean): Boolean {
        if (playing()) return false
        exactCall = v; return true
    }

    // ---------- flow ----------

    /** LOBBY/OVER -> BID: everyone present gets [startDice] dice; a random player starts. */
    fun startGame(): Boolean {
        if (playing() || !canStart()) return false
        for (p in players.values) { p.inGame = true; p.count = startDice; p.dice = emptyList() }
        winner = null
        result = null
        revealed = emptyMap()
        starter = players.keys.random(random)
        roll()
        return true
    }

    private fun roll() {
        for (p in players.values) p.dice = if (p.alive) List(p.count) { random.nextInt(1, 7) }.sorted() else emptyList()
        bids.clear()
        result = null
        revealed = emptyMap()
        notice = null
        turn = starter?.takeIf { players[it]?.alive == true } ?: seated().firstOrNull()?.id
        round++
        phase = Phase.BID
    }

    fun totalDice() = seated().sumOf { it.count }

    fun minFace() = if (wild) 2 else 1

    /** Whether [q]×[f] is a legal bid now: a legal face, no more dice than on the table, and above the last bid. */
    fun canBid(q: Int, f: Int): Boolean {
        if (f !in minFace()..6 || q !in 1..totalDice()) return false
        val b = bid ?: return true
        return q > b.q || (q == b.q && f > b.f)
    }

    fun bid(pid: String, q: Int, f: Int): Boolean {
        if (phase != Phase.BID || turn != pid || !canBid(q, f)) return false
        bids += Bid(q, f, pid)
        turn = aliveAfter(players[pid]!!.joinOrder)?.id
        notice = null
        return true
    }

    /** How many dice on the table show [f] (ones included when wild). */
    fun countOf(f: Int) = seated().sumOf { p -> p.dice.count { it == f || (wild && it == 1) } }

    /** The player on turn calls the last bid. */
    fun call(pid: String, call: Call): Boolean {
        if (phase != Phase.BID || turn != pid) return false
        if (call == Call.EXACT && !exactCall) return false
        val b = bid ?: return false
        val actual = countOf(b.f)
        var loser: String? = null
        var gainer: String? = null
        when (call) {
            Call.LIAR -> loser = if (actual >= b.q) pid else b.by
            Call.EXACT -> if (actual == b.q) gainer = pid else loser = pid
        }
        revealed = seated().associate { it.id to it.dice }
        val lp = loser?.let { players[it] }
        if (lp != null) lp.count--
        val gp = gainer?.let { players[it] }
        if (gp != null && gp.count < startDice) gp.count++ else gainer = null
        val out = lp != null && lp.count == 0
        result = Result(call, pid, b, actual, loser, gainer, out)
        starter = when {
            lp != null && !out -> lp.id
            lp != null -> aliveAfter(lp.joinOrder)?.id
            else -> pid
        }
        turn = null
        val left = seated()
        if (left.size == 1) finish(left[0].id) else phase = Phase.REVEAL
        return true
    }

    /** REVEAL -> BID: the next round. */
    fun next(): Boolean {
        if (phase != Phase.REVEAL) return false
        roll()
        return true
    }

    private fun finish(id: String) {
        winner = id
        turn = null
        phase = Phase.OVER
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        for (p in players.values) { p.inGame = false; p.count = 0; p.dice = emptyList() }
        bids.clear()
        turn = null; result = null; winner = null
        revealed = emptyMap()
        notice = msg
    }

    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    fun newGame() {
        cancel("")
        notice = null
    }

    // ---------- state for clients ----------

    private fun name(id: String?) = id?.let { players[it]?.name } ?: "?"

    private fun bidJson(b: Bid) = JSONObject().put("q", b.q).put("f", b.f).put("by", b.by).put("name", name(b.by))

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("startDice", startDice)
        o.put("minDice", MIN_DICE)
        o.put("maxDice", MAX_DICE)
        o.put("wild", wild)
        o.put("exactCall", exactCall)
        o.put("total", totalDice())
        o.put("minFace", minFace())
        turn?.let { o.put("turn", it) }
        winner?.let { o.put("winner", it).put("winnerName", name(it)) }
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        o.put("bids", JSONArray(bids.map { bidJson(it) }))
        result?.let { r ->
            o.put("result", JSONObject()
                .put("call", r.call.name).put("caller", r.caller).put("callerName", name(r.caller))
                .put("bid", bidJson(r.bid)).put("actual", r.actual).put("out", r.out)
                .apply {
                    r.loser?.let { put("loser", it).put("loserName", name(it)) }
                    r.gainer?.let { put("gainer", it).put("gainerName", name(it)) }
                })
        }
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("inGame", p.inGame).put("count", p.count).put("out", p.inGame && p.count == 0)
                // Dice are secret until a call reveals the round.
                .apply { revealed[p.id]?.let { put("dice", JSONArray(it)) } }
        }))
        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name)
                .put("inGame", me.inGame).put("alive", me.alive)
                .put("dice", JSONArray(me.dice)))
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MIN_DICE = 3
        const val MAX_DICE = 6
    }
}
