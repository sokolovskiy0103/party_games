package com.sokolovskiy.party.games.chameleon

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Хамелеон». Not thread-safe: the server guards it with a lock.
 *
 * Two modes:
 *  CLASSIC – everyone sees the topic and its 16 words; all but the Chameleon also see the secret word.
 *            The Chameleon knows their role and, if caught, gets one try at the word.
 *  SIMILAR – nobody sees a topic. Everyone gets the same word, except the Chameleon, who gets a similar
 *            one and does not know it. Clues are short descriptions; there is no guess at the end.
 *
 * Round flow:
 *  ROLES  – everyone looks at their card.
 *  CLUES  – in turn, everyone gives a clue.
 *  VOTE   – everyone points at the suspected Chameleon.
 *  GUESS  – (CLASSIC only) if the Chameleon was caught, they get one try at the secret word.
 *  RESULT – roles revealed, points awarded.
 */
class Game(
    private val topics: TopicBank,
    private val pairs: PairBank,
    private val random: Random = Random.Default,
) {
    enum class Phase { LOBBY, ROLES, CLUES, VOTE, GUESS, RESULT }
    enum class Outcome { ESCAPED, GUESSED, CAUGHT }
    enum class Mode { CLASSIC, SIMILAR }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var score = 0
        var connected = false
    }

    class Clue(val pid: String, val name: String, val text: String)

    class Result(
        val outcome: Outcome,
        /** Player the group accused (null if the votes were split or nobody voted). */
        val accused: String?,
        val guess: String?,
        val points: Map<String, Int>,
        val note: String,
    )

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var round = 0; private set
    var mode = Mode.CLASSIC; private set

    // ---- current round ----
    val inRound = LinkedHashSet<String>()
    /** Turn order for clues. */
    val order = ArrayList<String>()
    var turn = 0; private set
    var chameleonId: String? = null; private set
    var topic: Topic? = null; private set
    var word: String? = null; private set
    /** SIMILAR mode: the Chameleon's own, different word. */
    var chameleonWord: String? = null; private set
    val clues = ArrayList<Clue>()
    val ready = HashSet<String>()
    val votes = LinkedHashMap<String, String>()
    var tally: Map<String, Int> = emptyMap(); private set
    var accusedId: String? = null; private set
    var guess: String? = null; private set
    var result: Result? = null; private set
    /** One-off notice for everyone (e.g. "round cancelled"). */
    var notice: String? = null; private set

    private var nextOrder = 0

    // ---------- players ----------

    /** Adds a player whose id and (unique) name come from the room. */
    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    /** Removes a player; the Chameleon leaving (or too few players) cancels the round. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (phase == Phase.LOBBY || phase == Phase.RESULT) { inRound -= pid; return }
        if (pid !in inRound) return
        val idx = order.indexOf(pid)
        inRound -= pid; ready -= pid
        votes.remove(pid)
        votes.entries.removeAll { it.value == pid }
        if (idx >= 0) { order.removeAt(idx); if (idx < turn) turn-- }
        if (pid == chameleonId || inRound.size < MIN_PLAYERS) {
            cancelRound("${p.name} вийшов(ла) — раунд скасовано.")
            return
        }
        when (phase) {
            Phase.ROLES -> if (ready.containsAll(inRound)) beginClues()
            Phase.CLUES -> if (turn >= order.size) phase = Phase.VOTE
            Phase.VOTE -> if (inRound.all { it in votes }) closeVote()
            else -> Unit
        }
    }

    fun roleIsChameleon(pid: String?) = pid != null && pid in inRound && pid == chameleonId

    // ---------- round flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    /** LOBBY/RESULT -> ROLES. */
    fun startRound(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.RESULT) return false
        if (!canStart()) return false
        inRound.clear(); inRound += players.values.sortedBy { it.joinOrder }.map { it.id }
        order.clear(); order += inRound.shuffled(random)
        turn = 0
        chameleonId = order.random(random)
        if (mode == Mode.CLASSIC) {
            topic = topics.next(random).also { word = it.words.random(random) }
            chameleonWord = null
        } else {
            topic = null
            pairs.next(random).let { word = it.common; chameleonWord = it.odd }
        }
        clues.clear(); ready.clear(); votes.clear()
        tally = emptyMap(); accusedId = null; guess = null
        result = null; notice = null
        round++
        phase = Phase.ROLES
        return true
    }

    /** Switches the variant between rounds. */
    fun setMode(m: Mode): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.RESULT) return false
        mode = m
        return true
    }

    fun markReady(pid: String) {
        if (phase != Phase.ROLES || pid !in inRound) return
        ready += pid
        if (ready.containsAll(inRound)) beginClues()
    }

    /** Starts the clue round (also lets the host go on without waiting for everyone). */
    fun beginClues(): Boolean {
        if (phase != Phase.ROLES) return false
        phase = Phase.CLUES
        turn = 0
        return true
    }

    fun maxClue() = if (mode == Mode.SIMILAR) MAX_CLUE_PHRASE else MAX_CLUE

    fun currentClueGiver(): String? = if (phase == Phase.CLUES) order.getOrNull(turn) else null

    fun submitClue(pid: String, text: String): Boolean {
        if (phase != Phase.CLUES || currentClueGiver() != pid) return false
        val c = text.trim().replace(Regex("\\s+"), " ").take(maxClue())
        if (c.isEmpty()) return false
        clues += Clue(pid, players[pid]?.name ?: "?", c)
        advanceTurn()
        return true
    }

    /** The host skips a player who cannot give their clue (e.g. went offline). */
    fun skipTurn(): Boolean {
        if (phase != Phase.CLUES) return false
        advanceTurn()
        return true
    }

    private fun advanceTurn() {
        turn++
        if (turn >= order.size) phase = Phase.VOTE
    }

    fun castVote(pid: String, target: String): Boolean {
        if (phase != Phase.VOTE || pid !in inRound || target !in inRound || target == pid) return false
        votes[pid] = target
        if (inRound.all { it in votes }) closeVote()
        return true
    }

    /** Resolves the vote (also used by the host to close it early). */
    fun closeVote(): Boolean {
        if (phase != Phase.VOTE) return false
        tally = votes.values.groupingBy { it }.eachCount()
        val max = tally.values.maxOrNull() ?: 0
        val top = tally.filterValues { it == max }.keys
        when {
            max == 0 || top.size > 1 -> finish(Outcome.ESCAPED, null, "Голоси розділились — Хамелеон лишився непоміченим.")
            top.first() == chameleonId && mode == Mode.SIMILAR -> {
                accusedId = top.first()
                finish(Outcome.CAUGHT, top.first(), "Хамелеона викрито — у нього було інше слово!")
            }
            top.first() == chameleonId -> { accusedId = top.first(); phase = Phase.GUESS }
            else -> finish(Outcome.ESCAPED, top.first(), "Звинуватили не того — Хамелеон втік.")
        }
        return true
    }

    fun submitGuess(pid: String, text: String): Boolean {
        val t = topic ?: return false
        if (phase != Phase.GUESS || pid != chameleonId) return false
        val g = t.words.firstOrNull { it.equals(text.trim(), ignoreCase = true) } ?: return false
        guess = g
        if (g == word) finish(Outcome.GUESSED, accusedId, "Хамелеона викрито, але він вгадав слово!")
        else finish(Outcome.CAUGHT, accusedId, "Хамелеона викрито, і слово він не вгадав.")
        return true
    }

    /** The host gives up waiting for the Chameleon's guess. */
    fun skipGuess(): Boolean {
        if (phase != Phase.GUESS) return false
        finish(Outcome.CAUGHT, accusedId, "Хамелеона викрито, і слово він не вгадав.")
        return true
    }

    private fun finish(outcome: Outcome, accused: String?, note: String) {
        val pts = LinkedHashMap<String, Int>()
        val c = chameleonId
        when (outcome) {
            Outcome.ESCAPED -> c?.let { pts[it] = ESCAPED_POINTS }
            Outcome.GUESSED -> c?.let { pts[it] = GUESSED_POINTS }
            Outcome.CAUGHT -> inRound.filter { it != c }.forEach { pts[it] = CAUGHT_POINTS }
        }
        for ((id, n) in pts) players[id]?.let { it.score += n }
        result = Result(outcome, accused, guess, pts, note)
        phase = Phase.RESULT
    }

    private fun cancelRound(msg: String) {
        phase = Phase.LOBBY
        inRound.clear(); order.clear(); clues.clear(); ready.clear(); votes.clear()
        turn = 0; tally = emptyMap(); accusedId = null; guess = null
        chameleonId = null; topic = null; word = null; chameleonWord = null; result = null
        notice = msg
    }

    /** Host aborts the current round without scoring. */
    fun abortRound() {
        if (phase != Phase.LOBBY) cancelRound("Ведучий перервав раунд.")
    }

    fun newGame() {
        cancelRound("")
        notice = null
        round = 0
        players.values.forEach { it.score = 0 }
        topics.reset()
        pairs.reset()
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        val reveal = phase == Phase.RESULT
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("isHost", isHost)
        o.put("mode", mode.name)
        o.put("maxClue", maxClue())
        o.put("turn", currentClueGiver() ?: JSONObject.NULL)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("score", p.score)
                .put("connected", p.connected).put("inRound", p.id in inRound)
                .put("ready", p.id in ready).put("voted", p.id in votes)
        }))

        topic?.let { o.put("topic", JSONObject().put("name", it.name).put("words", JSONArray(it.words))) }
        if (phase != Phase.LOBBY) {
            o.put("order", JSONArray(order))
            o.put("clues", JSONArray(clues.map { JSONObject().put("pid", it.pid).put("name", it.name).put("text", it.text) }))
        }

        val me = viewer?.let { players[it] }
        if (me != null) {
            val inR = me.id in inRound && phase != Phase.LOBBY
            val cham = roleIsChameleon(me.id)
            val mine = JSONObject().put("id", me.id).put("name", me.name).put("inRound", inR)
            // In SIMILAR mode the Chameleon does not know their role, so it is not sent either.
            if (mode == Mode.CLASSIC) mine.put("chameleon", cham)
            o.put("me", mine)
            if (inR) {
                if (mode == Mode.SIMILAR) (if (cham) chameleonWord else word)?.let { o.put("word", it) }
                // In CLASSIC mode the Chameleon never gets the word before the reveal.
                else if (!cham) word?.let { o.put("word", it) }
            }
            if (phase == Phase.VOTE) votes[me.id]?.let { o.put("myVote", it) }
        }
        if (phase == Phase.GUESS || reveal) accusedId?.let { o.put("accused", it) }
        if (reveal) {
            word?.let { o.put("word", it) }
            chameleonWord?.let { o.put("chameleonWord", it) }
            o.put("chameleon", chameleonId ?: JSONObject.NULL)
            result?.let { r ->
                o.put("result", JSONObject()
                    .put("outcome", r.outcome.name)
                    .put("accused", r.accused ?: JSONObject.NULL)
                    .put("guess", r.guess ?: JSONObject.NULL)
                    .put("note", r.note)
                    .put("points", JSONObject(r.points as Map<*, *>))
                    .put("tally", JSONObject(tally as Map<*, *>))
                    .put("votes", JSONObject(votes as Map<*, *>)))
            }
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val MAX_CLUE = 24
        const val MAX_CLUE_PHRASE = 40
        const val ESCAPED_POINTS = 3
        const val GUESSED_POINTS = 1
        const val CAUGHT_POINTS = 2
    }
}
