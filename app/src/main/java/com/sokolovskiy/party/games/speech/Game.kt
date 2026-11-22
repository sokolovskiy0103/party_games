package com.sokolovskiy.party.games.speech

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Три слова». Not thread-safe: the server guards it with a lock.
 *
 * Players take turns as the speaker. The speaker gets a topic (public) and three secret words, and talks on the
 * topic for a set time trying to slip all three words in unnoticed. The listeners write down up to three words they
 * think were planted. After the speech the words are revealed, a matcher checks the guesses (the speaker or the host
 * can correct it) and the speaker marks which words they actually managed to say.
 *
 * Scoring, for every word the speaker said: each listener who guessed it gets [LISTENER_PTS]; for each listener who
 * missed it the speaker gets [SPEAKER_PTS]. Words the speaker didn't say score nothing. The game ends when everybody
 * has spoken [cycles] times.
 *
 * Flow: LOBBY -> PREP -> SPEECH -> GUESS -> REVIEW -> PREP … -> OVER.
 */
class Game(
    private val topics: Deck<String>,
    private val words: Deck<String>,
    private val random: Random = Random.Default,
    /** 18+ topics, mixed in at about [ADULT_SHARE] when [adult] is on. */
    private val adultTopics: Deck<String>? = null,
) {
    enum class Phase { LOBBY, PREP, SPEECH, GUESS, REVIEW, OVER }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = true
        var score = 0
        /** Turns as the speaker. */
        var spoke = 0
        /** Planted words of theirs that slipped past everybody. */
        var sneaked = 0
        /** Words they caught as a listener. */
        var caught = 0
    }

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var speechSec = DEFAULT_SPEECH; private set
    var cycles = 1; private set
    /** Host setting: mix in 18+ topics. */
    var adult = true; private set
    /** Bumped every turn so clients can reset per-turn UI. */
    var round = 0; private set
    var speaker: String? = null; private set
    var topic: String? = null; private set
    var topicAdult = false; private set
    /** Secret until REVIEW: only the speaker sees them. */
    var secret: List<String> = emptyList(); private set
    var deadline = 0L; private set
    /** Which secret words the speaker said (REVIEW; set by the speaker or the host). */
    val used = BooleanArray(WORDS)
    var notice: String? = null; private set
    /** The speaker's phone is listening (speech recognition): [heard] then decides [used] in REVIEW. */
    var mic = false; private set
    /** Secret words the recogniser caught in the speaker's speech. Only the speaker sees it before REVIEW. */
    val heard = BooleanArray(WORDS)

    /** Listener -> up to three guesses. Each listener sees only their own until REVIEW. */
    private val guesses = HashMap<String, List<String>>()
    /** Listeners who pressed «Готово» in GUESS. */
    private val done = HashSet<String>()
    /** REVIEW: listener -> which secret words they guessed (matcher, then corrected by hand). */
    private val hits = HashMap<String, BooleanArray>()
    /** REVIEW: the listeners who count — online or with guesses; someone long gone doesn't feed the speaker points. */
    private val voters = LinkedHashSet<String>()

    private var nextOrder = 0
    private var lastSpeakerOrder = -1

    private val active get() = phase != Phase.LOBBY && phase != Phase.OVER

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    fun remove(pid: String) {
        players.remove(pid) ?: return
        guesses.remove(pid); done.remove(pid); hits.remove(pid); voters.remove(pid)
        if (!active) return
        if (players.size < MIN_PLAYERS) { cancel("Замало гравців — гру перервано."); return }
        if (pid == speaker) {
            if (phase == Phase.REVIEW) { commit(); nextTurn() }
            else { notice = "Промовець вийшов — черга переходить далі."; nextTurn() }
            return
        }
        if (phase == Phase.GUESS && allDone()) toReview()
    }

    fun listeners(): List<String> = players.keys.filter { it != speaker }

    // ---------- settings ----------

    fun setSpeech(sec: Int): Boolean {
        if (active || sec !in SPEECH_TIMES) return false
        speechSec = sec
        return true
    }

    fun setAdult(v: Boolean): Boolean {
        if (active) return false
        adult = v
        return true
    }

    fun setCycles(n: Int): Boolean {
        if (active || n !in CYCLES) return false
        cycles = n
        return true
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    /** LOBBY/OVER -> PREP of the first speaker. */
    fun startGame(): Boolean {
        if (active || !canStart()) return false
        players.values.forEach { it.score = 0; it.spoke = 0; it.sneaked = 0; it.caught = 0 }
        lastSpeakerOrder = -1
        notice = null
        nextTurn()
        return true
    }

    /** The next player round the table who hasn't had all their turns yet, or OVER. */
    private fun nextTurn() {
        val left = players.values.filter { it.spoke < cycles }.sortedBy { it.joinOrder }
        if (left.isEmpty()) { finish(); return }
        val s = left.firstOrNull { it.joinOrder > lastSpeakerOrder } ?: left.first()
        lastSpeakerOrder = s.joinOrder
        speaker = s.id
        newTopic()
        secret = words.take(WORDS, random)
        guesses.clear(); done.clear(); hits.clear(); voters.clear()
        used.fill(true)
        heard.fill(false)
        mic = false
        deadline = 0
        round++
        phase = Phase.PREP
    }

    private fun newTopic() {
        val a = adultTopics
        topicAdult = adult && a != null && random.nextDouble() < ADULT_SHARE
        topic = if (topicAdult && a != null) a.next(random) else topics.next(random)
    }

    private fun canDrive(pid: String?, isHost: Boolean) = isHost || (pid != null && pid == speaker)

    fun swapTopic(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.PREP || !canDrive(pid, isHost)) return false
        newTopic()
        return true
    }

    fun swapWords(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.PREP || !canDrive(pid, isHost)) return false
        secret = words.take(WORDS, random)
        heard.fill(false)
        return true
    }

    /** Host: this speaker is away — their turn counts as done. */
    fun skip(isHost: Boolean): Boolean {
        if (phase != Phase.PREP || !isHost) return false
        players[speaker]?.let { it.spoke++ }
        nextTurn()
        return true
    }

    /** PREP -> SPEECH: the clock starts. */
    fun beginSpeech(pid: String?, isHost: Boolean, now: Long): Boolean {
        if (phase != Phase.PREP || !canDrive(pid, isHost)) return false
        phase = Phase.SPEECH
        deadline = now + speechSec * 1000L
        return true
    }

    /** The speaker finished early. */
    fun endSpeech(pid: String?, isHost: Boolean, now: Long): Boolean {
        if (phase != Phase.SPEECH || !canDrive(pid, isHost)) return false
        toGuess(now)
        return true
    }

    private fun toGuess(now: Long) {
        phase = Phase.GUESS
        deadline = now + GUESS_MS
        if (allDone()) toReview()
    }

    /**
     * A listener's guesses (up to three; blanks are dropped). [final] — «Готово» in GUESS; sending without it again
     * takes the «Готово» back.
     */
    fun guess(pid: String, list: List<String>, final: Boolean): Boolean {
        if (phase != Phase.SPEECH && phase != Phase.GUESS) return false
        if (pid !in players || pid == speaker) return false
        guesses[pid] = list.map { it.trim().take(MAX_LEN) }.filter { it.isNotEmpty() }.take(WORDS)
        if (final && phase == Phase.GUESS) done += pid else done -= pid
        if (phase == Phase.GUESS && allDone()) toReview()
        return true
    }

    /** The speaker's phone started listening. */
    fun micOn(pid: String?): Boolean {
        if ((phase != Phase.SPEECH && phase != Phase.GUESS) || pid == null || pid != speaker) return false
        mic = true
        return true
    }

    /**
     * Recognised speech from the speaker's phone (partial or final; late results right after the speech still count).
     * Returns true when it caught a new secret word.
     */
    fun heard(pid: String?, text: String): Boolean {
        if ((phase != Phase.SPEECH && phase != Phase.GUESS) || pid == null || pid != speaker) return false
        mic = true
        val tokens = Matcher.normalize(text.take(2000)).split(' ').filter { it.isNotEmpty() }
        var changed = false
        for (i in secret.indices) {
            if (heard[i]) continue
            if (tokens.any { Matcher.spoken(it, secret[i]) }) { heard[i] = true; changed = true }
        }
        return changed
    }

    fun guessesOf(pid: String): List<String> = guesses[pid].orEmpty()

    fun isDone(pid: String) = pid in done

    /** Every online listener pressed «Готово» (offline ones aren't waited for). */
    private fun allDone() = listeners().filter { players[it]?.connected == true }.all { it in done }

    /** After the room's online flags change: maybe nobody is left to wait for. */
    fun checkDone(): Boolean {
        if (phase != Phase.GUESS || !allDone()) return false
        toReview()
        return true
    }

    /** Host: stop waiting for the guesses. */
    fun forceReview(isHost: Boolean): Boolean {
        if (phase != Phase.GUESS || !isHost) return false
        toReview()
        return true
    }

    fun tick(now: Long): Boolean {
        if (phase == Phase.SPEECH && now >= deadline) { toGuess(now); return true }
        if (phase == Phase.GUESS && now >= deadline) { toReview(); return true }
        return false
    }

    private fun toReview() {
        phase = Phase.REVIEW
        deadline = 0
        // with the mic on, what it caught is the starting point; the speaker still corrects it
        if (mic) for (i in 0 until WORDS) used[i] = heard[i]
        hits.clear()
        voters.clear()
        voters += listeners().filter { players[it]?.connected == true || guesses[it].orEmpty().isNotEmpty() }
        for (l in voters) {
            val g = guesses[l].orEmpty()
            hits[l] = BooleanArray(WORDS) { i -> g.any { Matcher.same(it, secret[i]) } }
        }
    }

    fun hit(listener: String, word: Int) = hits[listener]?.getOrNull(word) == true

    /** REVIEW: the speaker or the host says whether the word was actually said. */
    fun setUsed(pid: String?, isHost: Boolean, word: Int, v: Boolean): Boolean {
        if (phase != Phase.REVIEW || !canDrive(pid, isHost) || word !in 0 until WORDS) return false
        used[word] = v
        return true
    }

    /** REVIEW: the speaker or the host corrects the matcher. */
    fun setHit(pid: String?, isHost: Boolean, listener: String, word: Int, v: Boolean): Boolean {
        if (phase != Phase.REVIEW || !canDrive(pid, isHost) || word !in 0 until WORDS) return false
        val h = hits[listener] ?: return false
        h[word] = v
        return true
    }

    /** Points this turn would give, as it stands now (REVIEW). */
    fun gains(): Map<String, Int> {
        if (phase != Phase.REVIEW) return emptyMap()
        val m = HashMap<String, Int>()
        val s = speaker ?: return m
        for (i in 0 until WORDS) {
            if (!used[i]) continue
            for (l in voters) {
                if (hit(l, i)) m.merge(l, LISTENER_PTS, Int::plus) else m.merge(s, SPEAKER_PTS, Int::plus)
            }
        }
        return m
    }

    private fun commit() {
        for ((pid, n) in gains()) players[pid]?.let { it.score += n }
        for (i in 0 until WORDS) {
            if (!used[i]) continue
            val catchers = voters.filter { hit(it, i) }
            catchers.forEach { players[it]?.let { p -> p.caught++ } }
            if (catchers.isEmpty()) players[speaker]?.let { it.sneaked++ }
        }
        players[speaker]?.let { it.spoke++ }
    }

    /** REVIEW -> next speaker (or OVER). */
    fun next(pid: String?, isHost: Boolean): Boolean {
        if (phase != Phase.REVIEW || !canDrive(pid, isHost)) return false
        commit()
        nextTurn()
        return true
    }

    private fun finish() {
        phase = Phase.OVER
        clearTurn()
    }

    private fun clearTurn() {
        speaker = null; topic = null; topicAdult = false; secret = emptyList()
        mic = false; heard.fill(false)
        guesses.clear(); done.clear(); hits.clear(); voters.clear()
        deadline = 0
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        clearTurn()
        notice = msg
    }

    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    fun newGame() {
        cancel("")
        notice = null
        players.values.forEach { it.score = 0; it.spoke = 0; it.sneaked = 0; it.caught = 0 }
        topics.reset(); words.reset(); adultTopics?.reset()
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("speechSec", speechSec)
        o.put("speechTimes", JSONArray(SPEECH_TIMES))
        o.put("cycles", cycles)
        o.put("cyclesList", JSONArray(CYCLES))
        o.put("adult", adult)
        o.put("hasAdult", adultTopics != null)
        o.put("mic", mic)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        o.put("speaker", speaker ?: JSONObject.NULL)
        topic?.let { o.put("topic", it).put("topicAdult", topicAdult) }
        if (deadline > 0) {
            o.put("deadline", deadline)
            o.put("total", if (phase == Phase.SPEECH) speechSec * 1000L else GUESS_MS)
        }
        o.put("turnsDone", players.values.sumOf { minOf(it.spoke, cycles) })
        o.put("turnsTotal", players.size * cycles)

        val review = phase == Phase.REVIEW
        val gains = gains()
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("score", p.score).put("spoke", p.spoke)
                .put("sneaked", p.sneaked).put("caught", p.caught)
                .put("saved", guesses[p.id]?.isNotEmpty() == true)
                .put("done", p.id in done)
                .apply { if (review) put("gain", gains[p.id] ?: 0) }
        }))

        val isSpeaker = viewer != null && viewer == speaker
        if (review || (isSpeaker && active)) {
            o.put("words", JSONArray(secret.mapIndexed { i, w ->
                JSONObject().put("w", w).apply {
                    if (mic) put("heard", heard[i])
                    if (review) {
                        put("used", used[i])
                        put("hits", JSONArray(voters.filter { hit(it, i) }))
                    }
                }
            }))
        }
        if (review) {
            o.put("guesses", JSONArray(voters.map { l ->
                JSONObject().put("id", l).put("g", JSONArray(guesses[l].orEmpty()))
            }))
        }

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name)
                .put("guesses", JSONArray(guesses[me.id].orEmpty()))
                .put("done", me.id in done))
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val WORDS = 3
        const val LISTENER_PTS = 1
        const val SPEAKER_PTS = 1
        const val GUESS_MS = 30_000L
        const val MAX_LEN = 40
        const val DEFAULT_SPEECH = 90
        val SPEECH_TIMES = listOf(60, 90, 120)
        val CYCLES = listOf(1, 2, 3)
        const val ADULT_SHARE = 0.15
    }
}

/** Hands out items without repeats until the deck runs out, then reshuffles. */
class Deck<T>(items: List<T>) {
    private val all = items.distinct()
    private val left = ArrayList<T>()

    init { require(all.isNotEmpty()) { "empty deck" } }

    fun next(random: Random): T {
        if (left.isEmpty()) { left.addAll(all); left.shuffle(random) }
        return left.removeAt(left.size - 1)
    }

    /** [n] different items (fewer only if the deck itself is smaller). */
    fun take(n: Int, random: Random): List<T> {
        val out = ArrayList<T>()
        var guard = 0
        while (out.size < minOf(n, all.size) && guard++ < n * 20) {
            val x = next(random)
            if (x !in out) out += x
        }
        return out
    }

    fun reset() = left.clear()
}
