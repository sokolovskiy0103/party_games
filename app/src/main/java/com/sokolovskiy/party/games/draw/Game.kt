package com.sokolovskiy.party.games.draw

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Малювач» — a bluffing drawing game in the spirit of Drawful. Not thread-safe: the server
 * guards it with a lock.
 *
 * Every round each player secretly gets an odd prompt and draws it on their phone. Then the drawings are shown one
 * by one: everybody but the artist writes a fake title meant to look real, then everybody but the artist picks which
 * of the titles is the real prompt. Finding the truth scores [GUESS] and earns the artist [ARTIST]; every vote a fake
 * draws scores [FOOL] for its author(s). Identical fakes merge into one option with several authors.
 *
 * Flow: LOBBY -> DRAW -> (FAKE -> VOTE -> RESULT) per drawing -> DRAW … -> OVER after [rounds] rounds.
 */
class Game(private val bank: PromptBank, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, DRAW, FAKE, VOTE, RESULT, OVER }
    enum class Spice { CLEAN, MIXED, ADULT }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
        var score = 0
        /** Votes this player's fakes drew over the game. */
        var fooled = 0
        /** Times this player found the real title. */
        var found = 0
    }

    /** A title on the ballot: the real prompt (no authors) or a fake, possibly written by several players at once. */
    class Option(val text: String, val real: Boolean) {
        val authors = ArrayList<String>()
    }

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var spice = Spice.CLEAN; private set
    var rounds = DEFAULT_ROUNDS; private set
    var drawSeconds = DEFAULT_DRAW_SECONDS; private set
    var round = 0; private set
    /** When the current phase runs out (server clock), or null when it waits for a tap. */
    var deadline: Long? = null; private set
    var notice: String? = null; private set

    // ----- drawing -----
    /** Secret: each prompt goes only to its own artist (and to everyone once the drawing is revealed). */
    private val prompts = HashMap<String, Prompt>()
    private val arts = HashMap<String, Art>()
    private val submitted = HashSet<String>()
    private val swapped = HashSet<String>()

    // ----- showing one drawing -----
    /** Artists whose drawings are still to be shown this round. */
    private val queue = ArrayList<String>()
    var artist: String? = null; private set
    private var shown: Art? = null
    var truth: Prompt? = null; private set
    /** Bumped for every drawing shown, so clients can reset per-drawing UI. */
    var step = 0; private set
    /** Position of the current drawing in this round, 1-based, and how many there are. */
    var shownIndex = 0; private set
    var shownTotal = 0; private set
    private val fakes = LinkedHashMap<String, String>()
    /** Players whose last fake was the real title (they have to come up with another one). */
    private val tooTrue = HashSet<String>()
    val options = ArrayList<Option>()
    private val votes = HashMap<String, Int>()
    /** Points earned on the drawing just revealed. */
    val gains = HashMap<String, Int>()

    private var nextOrder = 0

    // ---------- players ----------

    fun add(id: String, name: String): Player {
        val p = players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }
        if (phase == Phase.DRAW && id !in prompts) prompts[id] = bank.next(spice, random)
        return p
    }

    fun remove(pid: String, now: Long) {
        players.remove(pid) ?: return
        prompts.remove(pid); arts.remove(pid); submitted.remove(pid); swapped.remove(pid)
        fakes.remove(pid); tooTrue.remove(pid); votes.remove(pid)
        if (!busy()) return
        if (players.size < MIN_PLAYERS) { cancel("Замало гравців — гру перервано."); return }
        when (phase) {
            Phase.DRAW -> if (allSubmitted()) endDrawing(now)
            Phase.FAKE, Phase.VOTE, Phase.RESULT -> when {
                pid == artist -> { notice = "Художник вийшов — його малюнок пропускаємо."; showNext(now) }
                phase == Phase.FAKE && allFaked() -> startVote(now)
                phase == Phase.VOTE && allVoted() -> reveal()
            }
            else -> {}
        }
    }

    // ---------- settings ----------

    private fun busy() = phase != Phase.LOBBY && phase != Phase.OVER

    fun canStart() = players.size >= MIN_PLAYERS

    fun setSpice(s: Spice): Boolean {
        if (busy()) return false
        spice = s
        return true
    }

    fun setRounds(n: Int): Boolean {
        if (busy() || n !in ROUND_OPTIONS) return false
        rounds = n
        return true
    }

    fun setDrawSeconds(n: Int): Boolean {
        if (busy() || n !in DRAW_OPTIONS) return false
        drawSeconds = n
        return true
    }

    // ---------- flow ----------

    /** LOBBY/OVER -> DRAW. */
    fun startGame(now: Long): Boolean {
        if (busy() || !canStart()) return false
        players.values.forEach { it.score = 0; it.fooled = 0; it.found = 0 }
        notice = null
        round = 0
        nextRound(now)
        return true
    }

    private fun nextRound(now: Long) {
        round++
        prompts.clear(); arts.clear(); submitted.clear(); swapped.clear()
        for (id in players.keys) prompts[id] = bank.next(spice, random)
        clearShown()
        shownIndex = 0; shownTotal = 0
        phase = Phase.DRAW
        deadline = now + drawSeconds * 1000L
    }

    fun promptOf(pid: String) = prompts[pid]

    /** Work in progress, so a reload or the timer does not lose it. */
    fun draft(pid: String, art: Art): Boolean {
        if (phase != Phase.DRAW || pid !in players || pid in submitted) return false
        arts[pid] = art
        return true
    }

    /** Done drawing. An empty drawing can't be handed in. */
    fun submit(pid: String, art: Art?, now: Long): Boolean {
        if (phase != Phase.DRAW || pid !in players) return false
        val a = art ?: arts[pid]
        if (a == null || a.isEmpty) return false
        arts[pid] = a
        submitted += pid
        if (allSubmitted()) endDrawing(now)
        return true
    }

    /** Back to the canvas while the others are still drawing. */
    fun unsubmit(pid: String?): Boolean {
        if (phase != Phase.DRAW || pid == null) return false
        return submitted.remove(pid)
    }

    /** One other prompt per round, only before drawing anything. */
    fun swapPrompt(pid: String?): Boolean {
        if (phase != Phase.DRAW || pid == null || pid !in players || pid in swapped || pid in submitted) return false
        if (arts[pid]?.isEmpty == false) return false
        prompts[pid] = bank.next(spice, random)
        swapped += pid
        return true
    }

    fun canSwap(pid: String) = phase == Phase.DRAW && pid !in swapped && pid !in submitted && arts[pid]?.isEmpty != false

    private fun allSubmitted() = players.keys.all { it in submitted }

    private fun endDrawing(now: Long) {
        notice = null
        queue.clear()
        queue += players.keys.filter { arts[it]?.isEmpty == false }.shuffled(random)
        shownTotal = queue.size
        shownIndex = 0
        if (queue.isEmpty()) notice = "Ніхто нічого не намалював."
        showNext(now)
    }

    /** The next drawing of the round, or the next round, or the end. */
    private fun showNext(now: Long) {
        while (queue.isNotEmpty()) {
            val a = queue.removeAt(0)
            val art = arts[a]
            val prompt = prompts[a]
            if (a !in players || art == null || art.isEmpty || prompt == null) continue
            clearShown()
            artist = a; shown = art; truth = prompt
            shownIndex++
            step++
            phase = Phase.FAKE
            deadline = now + FAKE_MS
            return
        }
        if (round >= rounds) {
            clearShown()
            phase = Phase.OVER
            deadline = null
        } else {
            nextRound(now)
        }
    }

    private fun clearShown() {
        artist = null; shown = null; truth = null
        fakes.clear(); tooTrue.clear(); options.clear(); votes.clear(); gains.clear()
    }

    /** A fake title for the drawing on screen. Can be changed until everyone has written one. */
    fun writeFake(pid: String?, raw: String, now: Long): Boolean {
        if (phase != Phase.FAKE || pid == null || pid !in players || pid == artist) return false
        val text = raw.trim().replace(Regex("\\s+"), " ").take(MAX_FAKE)
        if (text.isEmpty()) return false
        if (norm(text) == norm(truth!!.text)) {
            tooTrue += pid
            fakes.remove(pid)
            return false
        }
        tooTrue -= pid
        fakes[pid] = text
        if (allFaked()) startVote(now)
        return true
    }

    private fun voters() = players.keys.filter { it != artist }

    private fun allFaked() = voters().all { it in fakes }

    private fun startVote(now: Long) {
        options.clear(); votes.clear()
        options += Option(truth!!.text, real = true)
        for ((pid, text) in fakes) {
            val same = options.firstOrNull { !it.real && norm(it.text) == norm(text) }
            (same ?: Option(text, real = false).also { options += it }).authors += pid
        }
        if (options.size == 1) {   // nobody wrote anything: nothing to vote on
            notice = "Ніхто не вигадав назви — голосування немає."
            reveal()
            return
        }
        options.shuffle(random)
        phase = Phase.VOTE
        deadline = now + VOTE_MS
    }

    /** A vote for the title the player thinks is real. Can be changed until everyone has voted. */
    fun vote(pid: String?, index: Int, now: Long): Boolean {
        if (phase != Phase.VOTE || pid == null || pid !in players || pid == artist) return false
        val o = options.getOrNull(index) ?: return false
        if (pid in o.authors) return false
        votes[pid] = index
        if (allVoted()) reveal()
        return true
    }

    private fun allVoted() = voters().all { it in votes }

    private fun reveal() {
        gains.clear()
        fun give(pid: String, n: Int) {
            val p = players[pid] ?: return
            p.score += n
            gains[pid] = (gains[pid] ?: 0) + n
        }
        for ((pid, i) in votes) {
            val o = options[i]
            if (o.real) {
                give(pid, GUESS)
                players[pid]?.let { it.found++ }
                artist?.let { give(it, ARTIST) }
            } else {
                for (a in o.authors) {
                    give(a, FOOL)
                    players[a]?.let { it.fooled++ }
                }
            }
        }
        phase = Phase.RESULT
        deadline = null
    }

    /** RESULT -> next drawing (host or the artist). */
    fun next(pid: String?, isHost: Boolean, now: Long): Boolean {
        if (phase != Phase.RESULT || !(isHost || pid == artist)) return false
        notice = null
        showNext(now)
        return true
    }

    /** Host: stop waiting for the stragglers. */
    fun hurry(isHost: Boolean, now: Long): Boolean {
        if (!isHost) return false
        return advance(now)
    }

    fun tick(now: Long): Boolean {
        val d = deadline ?: return false
        if (now < d) return false
        return advance(now)
    }

    private fun advance(now: Long): Boolean {
        when (phase) {
            Phase.DRAW -> endDrawing(now)
            Phase.FAKE -> startVote(now)
            Phase.VOTE -> reveal()
            else -> return false
        }
        return true
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        deadline = null
        queue.clear()
        prompts.clear(); arts.clear(); submitted.clear(); swapped.clear()
        clearShown()
        notice = msg
    }

    /** Host gives up on the current game. */
    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    /** Back to a clean lobby (scores and prompts start over). */
    fun newGame() {
        cancel("")
        notice = null
        players.values.forEach { it.score = 0; it.fooled = 0; it.found = 0 }
        bank.reset()
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round).put("rounds", rounds).put("roundOptions", JSONArray(ROUND_OPTIONS))
        o.put("drawSeconds", drawSeconds).put("drawOptions", JSONArray(DRAW_OPTIONS))
        o.put("spice", spice.name)
        o.put("now", now)
        o.put("deadline", deadline ?: JSONObject.NULL)
        o.put("limit", when (phase) {
            Phase.DRAW -> drawSeconds * 1000L
            Phase.FAKE -> FAKE_MS
            Phase.VOTE -> VOTE_MS
            else -> 0L
        })
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        val showing = phase == Phase.FAKE || phase == Phase.VOTE || phase == Phase.RESULT
        if (showing) {
            o.put("step", step).put("index", shownIndex).put("total", shownTotal)
            o.put("artist", artist ?: JSONObject.NULL)
            o.put("art", shown!!.json)
            o.put("lastInRound", queue.none { it in players && arts[it]?.isEmpty == false })
        }
        if (phase == Phase.VOTE) {
            o.put("options", JSONArray(options.map { JSONObject().put("text", it.text).put("mine", viewer != null && viewer in it.authors) }))
        }
        if (phase == Phase.RESULT) {
            o.put("truth", truth!!.text)
            o.put("options", JSONArray(options.mapIndexed { i, opt ->
                JSONObject().put("text", opt.text).put("real", opt.real)
                    .put("authors", JSONArray(opt.authors))
                    .put("voters", JSONArray(votes.filterValues { it == i }.keys.toList()))
            }))
        }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            val done = when (phase) {
                Phase.DRAW -> p.id in submitted
                Phase.FAKE -> p.id in fakes
                Phase.VOTE -> p.id in votes
                else -> false
            }
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("score", p.score).put("fooled", p.fooled).put("found", p.found)
                .put("done", done)
                .apply { if (phase == Phase.RESULT) put("gain", gains[p.id] ?: 0) }
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val m = JSONObject().put("id", me.id).put("name", me.name)
            when (phase) {
                Phase.DRAW -> {
                    prompts[me.id]?.let { m.put("prompt", it.text).put("adult", it.adult) }
                    arts[me.id]?.let { m.put("art", it.json) }
                    m.put("done", me.id in submitted).put("canSwap", canSwap(me.id))
                }
                Phase.FAKE -> {
                    if (me.id == artist) m.put("prompt", truth!!.text)
                    fakes[me.id]?.let { m.put("fake", it) }
                    if (me.id in tooTrue) m.put("tooTrue", true)
                }
                Phase.VOTE -> {
                    if (me.id == artist) m.put("prompt", truth!!.text)
                    votes[me.id]?.let { m.put("vote", it) }
                }
                else -> {}
            }
            o.put("me", m)
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val GUESS = 2
        const val ARTIST = 1
        const val FOOL = 1
        const val MAX_FAKE = 60
        const val FAKE_MS = 75_000L
        const val VOTE_MS = 40_000L
        const val DEFAULT_ROUNDS = 2
        const val DEFAULT_DRAW_SECONDS = 90
        val ROUND_OPTIONS = listOf(1, 2, 3)
        val DRAW_OPTIONS = listOf(60, 90, 120, 180)

        /** Compare titles ignoring case, spaces and punctuation. */
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
