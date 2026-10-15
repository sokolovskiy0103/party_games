package com.sokolovskiy.party.games.truth

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Детектор правди» — Privacy by Reinhard Staupe. Not thread-safe: the server guards it with a lock.
 *
 * Each round one player (the reader, in turn) reads a yes/no question aloud. Everybody, the reader included, secretly
 * answers «так» / «ні» and guesses how many «так» there will be in total. On reveal only the number of «так» is shown —
 * individual answers never leave the server. An exact guess scores a point, anything else nothing (simpler than the
 * original's 3 / 1 for off-by-one). The first to reach [target] points ends the game after that round; the highest score wins.
 *
 * Flow: LOBBY -> ROUND -> REVEAL -> ROUND … -> OVER.
 */
class Game(private val bank: QuestionBank, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, ROUND, REVEAL, OVER }
    enum class Spice { CLEAN, MIXED, ADULT }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
        var score = 0
        /** Exact guesses over the game. */
        var exact = 0
    }

    private class Ballot(var yes: Boolean, var guess: Int)

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var spice = Spice.MIXED; private set
    var target = DEFAULT_TARGET; private set
    /** Bumped on every round so clients can reset per-round UI. */
    var round = 0; private set
    var reader: String? = null; private set
    var question: Question? = null; private set
    /** Secret: never sent to clients, except a player's own ballot to themselves. */
    private val ballots = LinkedHashMap<String, Ballot>()
    /** Number of «так» in the round just revealed, and how many answered. */
    var yesCount = 0; private set
    var answered = 0; private set
    /** Points earned in the round just revealed. */
    val gains = HashMap<String, Int>()
    var notice: String? = null; private set

    private var nextOrder = 0
    /** Join order of the last reader, to go round the table. */
    private var lastReaderOrder = -1

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    fun remove(pid: String) {
        players.remove(pid) ?: return
        ballots.remove(pid)
        if (phase != Phase.ROUND && phase != Phase.REVEAL) return
        if (players.size < MIN_PLAYERS) { cancel("Замало гравців — гру перервано."); return }
        if (phase == Phase.ROUND && allAnswered()) score()
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    fun setSpice(s: Spice): Boolean {
        if (phase == Phase.ROUND || phase == Phase.REVEAL) return false
        spice = s
        return true
    }

    fun setTarget(n: Int): Boolean {
        if (phase == Phase.ROUND || phase == Phase.REVEAL || n !in TARGETS) return false
        target = n
        return true
    }

    /** LOBBY/OVER -> ROUND. */
    fun startGame(): Boolean {
        if (phase == Phase.ROUND || phase == Phase.REVEAL || !canStart()) return false
        players.values.forEach { it.score = 0; it.exact = 0 }
        notice = null
        nextRound()
        return true
    }

    private fun nextRound() {
        val order = players.values.sortedBy { it.joinOrder }
        val r = order.firstOrNull { it.joinOrder > lastReaderOrder } ?: order.first()
        lastReaderOrder = r.joinOrder
        reader = r.id
        question = bank.next(spice, random)
        ballots.clear(); gains.clear()
        yesCount = 0; answered = 0
        round++
        phase = Phase.ROUND
    }

    /** Reader or host: another question, only before anyone has answered. */
    fun swapQuestion(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.ROUND || ballots.isNotEmpty() || !(isHost || pid == reader)) return false
        question = bank.next(spice, random)
        return true
    }

    /** A player's secret answer and guess of the «так» count. Can be changed until everyone has answered. */
    fun answer(pid: String, yes: Boolean, guess: Int): Boolean {
        if (phase != Phase.ROUND || pid !in players || guess !in 0..players.size) return false
        ballots.getOrPut(pid) { Ballot(yes, guess) }.also { it.yes = yes; it.guess = guess }
        if (allAnswered()) score()
        return true
    }

    fun hasAnswered(pid: String) = pid in ballots

    fun allAnswered() = players.keys.all { it in ballots }

    /** Host: reveal now with whoever has answered (someone is stuck or gone). */
    fun forceReveal(isHost: Boolean): Boolean {
        if (phase != Phase.ROUND || !isHost || ballots.isEmpty()) return false
        score()
        return true
    }

    private fun score() {
        val yes = ballots.values.count { it.yes }
        for ((pid, b) in ballots) {
            val p = players[pid] ?: continue
            if (b.guess != yes) continue
            p.score++; p.exact++
            gains[pid] = 1
        }
        yesCount = yes
        answered = ballots.size
        ballots.clear()   // nothing left to leak
        phase = Phase.REVEAL
    }

    /** REVEAL -> next round, or OVER once someone has reached the target. */
    fun next(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.REVEAL || !(isHost || pid == reader)) return false
        if (players.values.any { it.score >= target }) {
            phase = Phase.OVER
            reader = null; question = null
            gains.clear()
        } else {
            nextRound()
        }
        return true
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        reader = null; question = null
        ballots.clear(); gains.clear()
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
        players.values.forEach { it.score = 0; it.exact = 0 }
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
        o.put("target", target)
        o.put("targets", JSONArray(TARGETS.toList()))
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        o.put("reader", reader ?: JSONObject.NULL)
        question?.let { o.put("question", JSONObject().put("q", it.q).put("adult", it.adult)) }
        o.put("maxGuess", players.size)
        val reveal = phase == Phase.REVEAL
        if (reveal) o.put("yes", yesCount).put("answered", answered)

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("score", p.score).put("exact", p.exact)
                .put("answered", p.id in ballots)
                .apply { if (reveal) put("gain", gains[p.id] ?: 0) }
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name).apply {
                ballots[me.id]?.let { put("yes", it.yes).put("guess", it.guess) }
            })
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 4
        const val DEFAULT_TARGET = 5
        val TARGETS = listOf(3, 5, 7, 10)
    }
}
