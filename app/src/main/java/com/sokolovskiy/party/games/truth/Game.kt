package com.sokolovskiy.party.games.truth

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Детектор правди». Not thread-safe: the server guards it with a lock.
 *
 * Hot seat: each turn one player (the subject) gets a personal question. They secretly pick whether they will answer
 * truthfully or lie, then answer aloud. Everybody else votes «правда» / «брехня» (the votes stay hidden). On reveal each
 * correct voter scores a point, and the subject scores a point for every voter they fooled.
 * Everyone sits in the hot seat [turnsEach] times, in join order.
 *
 * Flow: LOBBY -> TURN -> REVEAL -> TURN … -> OVER.
 */
class Game(private val bank: QuestionBank, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, TURN, REVEAL, OVER }
    enum class Spice { CLEAN, MIXED, ADULT }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
        var score = 0
        /** Hot-seat turns finished. */
        var turns = 0
        /** Voters this player fooled / votes this player got right. */
        var fooled = 0
        var caught = 0
    }

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var spice = Spice.MIXED; private set
    var turnsEach = DEFAULT_TURNS; private set
    /** Bumped on every turn so clients can reset per-turn UI. */
    var round = 0; private set
    var subject: String? = null; private set
    var question: Question? = null; private set
    /** The subject's secret choice: true = will tell the truth. Null until they commit. */
    var plan: Boolean? = null; private set
    /** Voter id -> "believes it is the truth". */
    val votes = LinkedHashMap<String, Boolean>()
    /** Points earned in the turn just revealed. */
    val gains = HashMap<String, Int>()
    var notice: String? = null; private set

    private var nextOrder = 0

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    fun remove(pid: String) {
        players.remove(pid) ?: return
        votes.remove(pid)
        if (phase != Phase.TURN && phase != Phase.REVEAL) return
        if (players.size < MIN_PLAYERS) { cancel("Замало гравців — гру перервано."); return }
        if (pid == subject && phase == Phase.TURN) nextTurn()
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    fun setSpice(s: Spice): Boolean {
        if (phase == Phase.TURN || phase == Phase.REVEAL) return false
        spice = s
        return true
    }

    fun setTurns(n: Int): Boolean {
        if (phase == Phase.TURN || phase == Phase.REVEAL || n !in 1..MAX_TURNS) return false
        turnsEach = n
        return true
    }

    /** LOBBY/OVER -> TURN. */
    fun startGame(): Boolean {
        if (phase == Phase.TURN || phase == Phase.REVEAL || !canStart()) return false
        players.values.forEach { it.score = 0; it.turns = 0; it.fooled = 0; it.caught = 0 }
        notice = null
        nextTurn()
        return true
    }

    /** Whose turn comes next: whoever sat the least, then by join order. Null when everyone is done. */
    private fun pickSubject() = players.values.filter { it.turns < turnsEach }.minWithOrNull(compareBy({ it.turns }, { it.joinOrder }))

    private fun nextTurn() {
        val s = pickSubject()
        votes.clear(); gains.clear()
        plan = null
        if (s == null) { phase = Phase.OVER; subject = null; question = null; return }
        subject = s.id
        question = bank.next(spice, random)
        round++
        phase = Phase.TURN
    }

    /** Subject or host: another question, only before the subject has committed. */
    fun swapQuestion(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.TURN || plan != null || !(isHost || pid == subject)) return false
        question = bank.next(spice, random)
        return true
    }

    /** The subject secretly decides to tell the truth or to lie; this opens the voting. */
    fun commit(pid: String, truth: Boolean): Boolean {
        if (phase != Phase.TURN || pid != subject || plan != null) return false
        plan = truth
        return true
    }

    /** A voter's guess ([truth]: "I believe it"). Can be changed until the reveal. */
    fun vote(pid: String, truth: Boolean): Boolean {
        if (phase != Phase.TURN || plan == null || pid == subject || pid !in players) return false
        votes[pid] = truth
        return true
    }

    fun allVoted() = players.keys.all { it == subject || it in votes }

    /** Subject or host opens the verdict and scores the turn. */
    fun reveal(pid: String?, isHost: Boolean): Boolean {
        val p = plan ?: return false
        if (phase != Phase.TURN || !(isHost || pid == subject)) return false
        val sub = players[subject] ?: return false
        for ((voter, guess) in votes) {
            val v = players[voter] ?: continue
            if (guess == p) { v.score++; v.caught++; gains.merge(v.id, 1, Int::plus) }
            else { sub.score++; sub.fooled++; gains.merge(sub.id, 1, Int::plus) }
        }
        sub.turns++
        phase = Phase.REVEAL
        return true
    }

    /** REVEAL -> next turn (or OVER). */
    fun next(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.REVEAL || !(isHost || pid == subject)) return false
        nextTurn()
        return true
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        subject = null; question = null; plan = null
        votes.clear(); gains.clear()
        notice = msg
    }

    /** Host gives up on the current game. */
    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    /** Back to a clean lobby (scores and questions start over). */
    fun newGame() {
        cancel("")
        notice = null
        players.values.forEach { it.score = 0; it.turns = 0; it.fooled = 0; it.caught = 0 }
        bank.reset()
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("spice", spice.name)
        o.put("turnsEach", turnsEach)
        o.put("maxTurns", MAX_TURNS)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        o.put("subject", subject ?: JSONObject.NULL)
        question?.let { o.put("question", JSONObject().put("q", it.q).put("adult", it.adult)) }
        o.put("committed", plan != null)
        o.put("allVoted", phase == Phase.TURN && plan != null && allVoted())
        val total = players.size * turnsEach
        o.put("turnNo", minOf(players.values.sumOf { it.turns } + if (phase == Phase.TURN) 1 else 0, maxOf(total, 1)))
        o.put("turnTotal", total)

        // The plan is secret from everyone but the subject until the verdict.
        val seePlan = phase == Phase.REVEAL || phase == Phase.OVER || viewer == subject
        if (seePlan) plan?.let { o.put("plan", it) }
        val reveal = phase == Phase.REVEAL
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("score", p.score).put("turns", p.turns).put("fooled", p.fooled).put("caught", p.caught)
                .put("voted", p.id in votes)
                .apply {
                    if (reveal) {
                        votes[p.id]?.let { put("vote", it) }
                        put("gain", gains[p.id] ?: 0)
                    }
                }
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name)
                .apply { votes[me.id]?.let { put("vote", it) } })
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val DEFAULT_TURNS = 2
        const val MAX_TURNS = 5
    }
}
