package com.sokolovskiy.party.games.wavelength

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/**
 * Pure game logic for «Хвиля» (Wavelength). Not thread-safe: the server guards it with a lock.
 *
 * Round flow:
 *  CLUE   – the Psychic sees a hidden target on a scale between two concepts and writes a clue.
 *  GUESS  – everyone else puts a marker where they think the target is and locks it in.
 *  RESULT – the target is revealed; the closer the marker, the more points. The Psychic earns
 *           the average of the guessers' points.
 */
class Game(
    private val spectra: SpectrumBank,
    private val random: Random = Random.Default,
) {
    enum class Phase { LOBBY, CLUE, GUESS, RESULT }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var score = 0
        var connected = false
    }

    class Result(
        val target: Int,
        val guesses: Map<String, Int>,
        /** Points earned this round, Psychic included. */
        val points: Map<String, Int>,
    )

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var round = 0; private set

    // ---- current round ----
    val inRound = LinkedHashSet<String>()
    var psychicId: String? = null; private set
    var spectrum: Spectrum? = null; private set
    var target = 0; private set
    var clue: String? = null; private set
    val guesses = LinkedHashMap<String, Int>()
    var result: Result? = null; private set
    /** One-off notice for everyone (e.g. "round cancelled"). */
    var notice: String? = null; private set

    private var psychicCursor = -1
    private var nextOrder = 0

    // ---------- players ----------

    /** Adds a player whose id and (unique) name come from the room. */
    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    /** Removes a player. The Psychic leaving cancels the round; a guesser leaving may complete it. */
    fun remove(pid: String) {
        players.remove(pid) ?: return
        if (phase == Phase.LOBBY || phase == Phase.RESULT) { inRound -= pid; return }
        if (pid !in inRound) return
        inRound -= pid
        guesses.remove(pid)
        if (pid == psychicId || guessers().isEmpty()) cancelRound("Гравець вийшов — раунд скасовано.")
        else if (phase == Phase.GUESS && guessers().all { it in guesses }) reveal()
    }

    fun guessers(): List<String> = inRound.filter { it != psychicId }

    // ---------- round flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    /** LOBBY/RESULT -> CLUE. The Psychic rotates in join order. */
    fun startRound(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.RESULT) return false
        if (!canStart()) return false
        val order = players.values.sortedBy { it.joinOrder }.map { it.id }
        inRound.clear(); inRound += order
        psychicCursor = (psychicCursor + 1) % order.size
        psychicId = order[psychicCursor]
        pickSpectrum()
        clue = null
        guesses.clear()
        result = null; notice = null
        round++
        phase = Phase.CLUE
        return true
    }

    private fun pickSpectrum() {
        spectrum = spectra.next(random)
        target = random.nextInt(TARGET_MIN, TARGET_MAX + 1)
    }

    /** The Psychic dislikes the scale or target and asks for another. */
    fun swapSpectrum(): Boolean {
        if (phase != Phase.CLUE) return false
        pickSpectrum()
        return true
    }

    fun submitClue(text: String): Boolean {
        if (phase != Phase.CLUE) return false
        val c = text.trim().replace(Regex("\\s+"), " ").take(MAX_CLUE)
        if (c.isEmpty()) return false
        clue = c
        phase = Phase.GUESS
        return true
    }

    /** Puts (or moves) [pid]'s locked marker at [value] percent. */
    fun lock(pid: String, value: Int): Boolean {
        if (phase != Phase.GUESS || pid !in guessers()) return false
        guesses[pid] = value.coerceIn(0, 100)
        if (guessers().all { it in guesses }) reveal()
        return true
    }

    /** Shows the target and scores whoever locked a guess (also lets the host close the round early). */
    fun reveal(): Boolean {
        if (phase != Phase.GUESS) return false
        val pts = LinkedHashMap<String, Int>()
        for ((id, v) in guesses) pts[id] = pointsFor(abs(v - target))
        psychicId?.let { pid ->
            pts[pid] = if (guesses.isEmpty()) 0 else Math.round(pts.filterKeys { it != pid }.values.average()).toInt()
        }
        for ((id, n) in pts) players[id]?.let { it.score += n }
        result = Result(target, LinkedHashMap(guesses), pts)
        phase = Phase.RESULT
        return true
    }

    private fun cancelRound(msg: String) {
        phase = Phase.LOBBY
        inRound.clear(); guesses.clear()
        psychicId = null; spectrum = null; clue = null; result = null
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
        psychicCursor = -1
        players.values.forEach { it.score = 0 }
        spectra.reset()
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("isHost", isHost)
        o.put("psychic", psychicId ?: JSONObject.NULL)
        o.put("zones", JSONArray(ZONES.map { JSONArray(listOf(it.first, it.second)) }))
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("score", p.score)
                .put("connected", p.connected).put("inRound", p.id in inRound)
                .put("locked", p.id in guesses)
        }))

        spectrum?.let { o.put("spectrum", JSONObject().put("left", it.left).put("right", it.right)) }
        clue?.let { o.put("clue", it) }

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name)
                .put("inRound", me.id in inRound).put("psychic", me.id == psychicId))
            // The target is a secret of the Psychic until the reveal.
            if (me.id == psychicId && (phase == Phase.CLUE || phase == Phase.GUESS)) o.put("target", target)
            if (phase == Phase.GUESS) guesses[me.id]?.let { o.put("myGuess", it) }
        }
        if (phase == Phase.RESULT) {
            result?.let { r ->
                o.put("result", JSONObject()
                    .put("target", r.target)
                    .put("guesses", JSONObject(r.guesses as Map<*, *>))
                    .put("points", JSONObject(r.points as Map<*, *>)))
            }
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val MAX_CLUE = 60
        const val TARGET_MIN = 5
        const val TARGET_MAX = 95

        /** (max distance in percent, points) — the first zone that fits wins. */
        val ZONES = listOf(3 to 4, 8 to 3, 14 to 2, 22 to 1)

        fun pointsFor(distance: Int): Int = ZONES.firstOrNull { distance <= it.first }?.second ?: 0
    }
}
