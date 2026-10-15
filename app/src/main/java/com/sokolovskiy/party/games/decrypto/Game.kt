package com.sokolovskiy.party.games.decrypto

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Шифр» (after Decrypto). Not thread-safe: the server guards it with a lock.
 *
 * Two teams, each with four secret keywords on positions 1–4 that only the team sees. Every round each team has an
 * encryptor (rotating) who gets a secret three-digit code (distinct digits 1–4, e.g. 3-1-4) and writes one clue per
 * digit, hinting at the keyword on that position. Then, at the same time:
 *  – the rest of the team deciphers their own code (a miss earns a black token);
 *  – from round 2 the other team tries to intercept it (a hit earns them a white token).
 * Every clue ever given stays on the public board under its position, so the opponents learn the keywords bit by bit
 * and encryptors have to get more and more cryptic.
 *
 * Two white tokens win, two black tokens lose. If both happen at once, or after [MAX_ROUNDS] rounds, the score is
 * whites minus blacks; equal is a draw.
 *
 * Each team's guess is one shared draft: any teammate may change it, and anyone may lock it in.
 *
 * Flow: LOBBY -> CLUES -> GUESS -> REVEAL -> CLUES … -> OVER.
 */
class Game(private val words: WordBank, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, CLUES, GUESS, REVEAL, OVER }
    enum class Spice { CLEAN, MIXED, ADULT }
    enum class Target { OWN, OPP }

    class Player(val id: String, var name: String, val joinOrder: Int, var team: Int) {
        var connected = false
    }

    /** A team's shared answer: three digits (null = not chosen yet). */
    class Guess {
        val digits = arrayOfNulls<Int>(CODE)
        var locked = false
        /** Who touched it last. */
        var by: String? = null
        fun complete() = digits.all { it != null }
        fun list() = digits.toList()
        fun reset() { digits.fill(null); locked = false; by = null }
    }

    class Team(val index: Int) {
        var words: List<String> = emptyList()
        var whites = 0
        var blacks = 0
        /** How many times an encryptor has been picked (for rotation). */
        var turns = 0
        var encryptor: String? = null
        /** Kept for the log in case the encryptor leaves mid-round. */
        var encryptorName = ""
        var code: List<Int> = emptyList()
        var clues: List<String>? = null
        /** This team deciphering its own code. */
        val own = Guess()
        /** This team intercepting the other team's code. */
        val opp = Guess()
    }

    /** What happened to one team's code in one round. [intercept] is the other team's guess (null in round 1). */
    class TeamRound(
        val encryptorName: String, val code: List<Int>, val clues: List<String>,
        val own: List<Int?>, val intercept: List<Int?>?, val ok: Boolean, val intercepted: Boolean,
    )

    class RoundLog(val round: Int, val teams: List<TeamRound>)

    val players = LinkedHashMap<String, Player>()
    val teams = listOf(Team(0), Team(1))
    var phase = Phase.LOBBY; private set
    var spice = Spice.MIXED; private set
    var round = 0; private set
    val log = ArrayList<RoundLog>()
    /** OVER: the winning team, or -1 for a draw. */
    var winner: Int? = null; private set
    var notice: String? = null; private set

    private var nextOrder = 0

    // ---------- players & teams ----------

    fun members(t: Int) = players.values.filter { it.team == t }.sortedBy { it.joinOrder }.map { it.id }

    private fun smallerTeam() = if (members(1).size < members(0).size) 1 else 0

    /** Newcomers go to the smaller team, also mid-game (they get to see that team's words). */
    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++, smallerTeam()) }.also { it.name = name }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (phase == Phase.LOBBY || phase == Phase.OVER) return
        if (teams.any { members(it.index).size < MIN_PER_TEAM }) {
            cancel("${p.name} вийшов(ла) — у команді замало гравців, гру зупинено.")
            return
        }
        val t = teams[p.team]
        if (t.encryptor == pid) {
            if (phase == Phase.CLUES && t.clues == null) pickEncryptor(t)
            else t.encryptor = null
        }
    }

    /** Lobby only: the player (or the host for them) changes sides. */
    fun switchTeam(pid: String): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return false
        val p = players[pid] ?: return false
        p.team = 1 - p.team
        return true
    }

    fun shuffleTeams(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return false
        players.values.shuffled(random).forEachIndexed { i, p -> p.team = i % 2 }
        return true
    }

    fun setSpice(s: Spice): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return false
        spice = s
        return true
    }

    fun canStart() = teams.all { members(it.index).size >= MIN_PER_TEAM }

    // ---------- flow ----------

    fun startGame(): Boolean {
        if ((phase != Phase.LOBBY && phase != Phase.OVER) || !canStart()) return false
        val dealt = words.deal(spice, random)
        for (t in teams) {
            t.words = dealt[t.index]
            t.whites = 0; t.blacks = 0
            // start the rotation at a random member so the first encryptor is not always the first to join
            t.turns = random.nextInt(members(t.index).size)
        }
        log.clear()
        winner = null; notice = null
        round = 0
        beginRound()
        return true
    }

    private fun beginRound() {
        round++
        for (t in teams) {
            t.clues = null
            t.own.reset(); t.opp.reset()
            pickEncryptor(t)
        }
        phase = Phase.CLUES
    }

    private fun pickEncryptor(t: Team) {
        val m = members(t.index)
        t.encryptor = m[t.turns++ % m.size]
        t.encryptorName = players[t.encryptor]?.name ?: "?"
        t.code = (1..WordBank.WORDS).shuffled(random).take(CODE)
    }

    fun nextRound(): Boolean {
        if (phase != Phase.REVEAL) return false
        beginRound()
        return true
    }

    fun teamOf(pid: String?) = pid?.let { players[it] }?.let { teams[it.team] }

    fun submitClues(pid: String, clues: List<String>): Boolean {
        if (phase != Phase.CLUES) return false
        val t = teamOf(pid) ?: return false
        if (t.encryptor != pid || t.clues != null || clues.size != CODE) return false
        val c = clues.map { it.trim().replace(Regex("\\s+"), " ").take(MAX_CLUE) }
        if (c.any { it.isEmpty() }) return false
        t.clues = c
        if (teams.all { it.clues != null }) phase = Phase.GUESS
        return true
    }

    fun interceptOpen() = round >= 2

    private fun guessFor(pid: String, target: Target): Guess? {
        if (phase != Phase.GUESS) return null
        val t = teamOf(pid) ?: return null
        return when (target) {
            Target.OWN -> if (t.encryptor == pid) null else t.own
            Target.OPP -> if (interceptOpen()) t.opp else null
        }
    }

    /** Sets one digit of the team's shared draft ([digit] 0 clears it). */
    fun setDigit(pid: String, target: Target, slot: Int, digit: Int): Boolean {
        val g = guessFor(pid, target) ?: return false
        if (g.locked || slot !in 0 until CODE || digit !in 0..WordBank.WORDS) return false
        g.digits[slot] = digit.takeIf { it > 0 }
        g.by = pid
        return true
    }

    fun lock(pid: String, target: Target): Boolean {
        val g = guessFor(pid, target) ?: return false
        if (g.locked || !g.complete()) return false
        g.locked = true
        g.by = pid
        if (allLocked()) resolve()
        return true
    }

    fun allLocked() = teams.all { it.own.locked && (!interceptOpen() || it.opp.locked) }

    /** Scores the round (the host may force it: anything not locked in counts as wrong). */
    fun resolve(): Boolean {
        if (phase != Phase.GUESS) return false
        val entries = teams.map { t ->
            val other = teams[1 - t.index]
            val ok = t.own.locked && t.own.list() == t.code
            val intercepted = interceptOpen() && other.opp.locked && other.opp.list() == t.code
            if (!ok) t.blacks++
            if (intercepted) other.whites++
            TeamRound(
                t.encryptorName, t.code, t.clues.orEmpty(),
                t.own.list(), if (interceptOpen()) other.opp.list() else null, ok, intercepted,
            )
        }
        log += RoundLog(round, entries)
        phase = Phase.REVEAL
        checkEnd()
        return true
    }

    private fun checkEnd() {
        val (a, b) = teams
        val aWins = a.whites >= WIN_TOKENS || b.blacks >= WIN_TOKENS
        val bWins = b.whites >= WIN_TOKENS || a.blacks >= WIN_TOKENS
        if (!aWins && !bWins && round < MAX_ROUNDS) return
        winner = when {
            aWins && !bWins -> 0
            bWins && !aWins -> 1
            else -> {
                val sa = a.whites - a.blacks
                val sb = b.whites - b.blacks
                if (sa > sb) 0 else if (sb > sa) 1 else -1
            }
        }
        phase = Phase.OVER
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        for (t in teams) { t.clues = null; t.encryptor = null; t.code = emptyList(); t.words = emptyList(); t.own.reset(); t.opp.reset() }
        log.clear()
        round = 0
        winner = null
        notice = msg
    }

    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    // ---------- state for clients ----------

    private fun ints(l: List<Int?>) = JSONArray(l.map { it ?: 0 })

    private fun guessJson(g: Guess) = JSONObject()
        .put("digits", ints(g.list())).put("locked", g.locked)
        .put("by", g.by?.let { players[it]?.name } ?: JSONObject.NULL)

    /** Every clue a team has given so far, grouped by keyword position. */
    private fun board(t: Int) = JSONArray((1..WordBank.WORDS).map { pos ->
        JSONArray(log.flatMap { r ->
            val e = r.teams[t]
            e.code.indices.filter { e.code[it] == pos }.map { JSONObject().put("round", r.round).put("clue", e.clues[it]) }
        })
    })

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        val playing = phase != Phase.LOBBY
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("spice", spice.name)
        o.put("minPerTeam", MIN_PER_TEAM)
        o.put("maxRounds", MAX_ROUNDS)
        o.put("maxClue", MAX_CLUE)
        o.put("intercept", interceptOpen())
        o.put("canStart", canStart())
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        winner?.let { o.put("winner", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("connected", p.connected).put("team", p.team)
        }))
        o.put("teams", JSONArray(teams.map { t ->
            JSONObject()
                .put("index", t.index).put("members", JSONArray(members(t.index)))
                .put("whites", t.whites).put("blacks", t.blacks)
                .put("encryptor", if (playing) t.encryptor ?: JSONObject.NULL else JSONObject.NULL)
                .put("cluesDone", t.clues != null)
                // Clues go public only once both encryptors are done; afterwards they live in the log.
                .put("clues", if (phase == Phase.GUESS) JSONArray(t.clues.orEmpty()) else JSONArray())
                .put("ownLocked", t.own.locked).put("oppLocked", t.opp.locked)
                .put("board", board(t.index))
                .apply { if (phase == Phase.OVER) put("words", JSONArray(t.words)) }
        }))
        o.put("log", JSONArray(log.map { r ->
            JSONObject().put("round", r.round).put("teams", JSONArray(r.teams.map { e ->
                JSONObject().put("encryptor", e.encryptorName).put("code", ints(e.code)).put("clues", JSONArray(e.clues))
                    .put("own", ints(e.own)).put("intercept", e.intercept?.let { ints(it) } ?: JSONObject.NULL)
                    .put("ok", e.ok).put("intercepted", e.intercepted)
            }))
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val t = teams[me.team]
            val enc = playing && t.encryptor == me.id
            o.put("me", JSONObject().put("id", me.id).put("name", me.name).put("team", me.team).put("encryptor", enc))
            if (playing) {
                o.put("words", JSONArray(t.words))
                if (enc && (phase == Phase.CLUES || phase == Phase.GUESS)) o.put("code", ints(t.code))
                if (phase == Phase.GUESS) o.put("drafts", JSONObject().put("own", guessJson(t.own)).put("opp", guessJson(t.opp)))
            }
        }
        return o
    }

    companion object {
        const val CODE = 3
        const val MIN_PER_TEAM = 2
        const val MAX_ROUNDS = 8
        const val WIN_TOKENS = 2
        const val MAX_CLUE = 30
    }
}
