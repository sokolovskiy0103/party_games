package com.sokolovskiy.party.games.hat

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Шляпа». Not thread-safe: the hub guards it with a lock.
 * Time is passed in explicitly so tests can drive it.
 *
 * Flow:
 *  LOBBY     – host picks how many characters each player adds, turn length, pictures.
 *  COLLECT   – everyone drops [perPlayer] characters into the hat (or the app fills them in).
 *  TEAMS     – random teams; the host can reshuffle, change the count or move people.
 *  READY     – whose turn it is; the explainer starts the timer.
 *  TURN      – the explainer works through the hat; guessed cards leave it, skipped go back.
 *  TURN_END  – time is up: did the team get the last card?
 *  ROUND_END – the hat is empty. Unused time of the turn that emptied it carries over
 *              to the same team and explainer at the start of the next round.
 *  FINAL     – after round 3: totals and statistics.
 *
 * Rounds: 1 – explain in words, 2 – one word, 3 – no words (charades).
 */
class Game(
    private val bank: List<String>,
    private val random: Random = Random.Default,
) {
    enum class Phase { LOBBY, COLLECT, TEAMS, READY, TURN, TURN_END, ROUND_END, FINAL }

    class Player(val id: String, var name: String) {
        var connected = false
        val joinOrder = nextOrder++
        var team: Int? = null

        // statistics as the explainer
        var explained = 0
        var explainMs = 0L
        var skips = 0
        var bestTurn = 0

        fun resetStats() { explained = 0; explainMs = 0; skips = 0; bestTurn = 0 }

        companion object {
            private var nextOrder = 0
        }
    }

    class Card(val id: Int, val name: String, val author: String?) {
        /** Picture chosen by the author: null = look it up automatically, "" = no picture. */
        var pic: String? = null
        /** Time on screen per round. */
        val ms = LongArray(ROUNDS)
        val skips = IntArray(ROUNDS)
        val guessedBy = arrayOfNulls<String>(ROUNDS)
        /** Time on screen in the turn it was guessed (the "how fast" stat). */
        val guessMs = LongArray(ROUNDS)

        fun reset() { ms.fill(0); skips.fill(0); guessedBy.fill(null); guessMs.fill(0) }
    }

    class Team(val idx: Int, val name: String, val emoji: String) {
        val members = ArrayList<String>()
        val score = IntArray(ROUNDS)
        var cursor = 0
        val total get() = score.sum()
    }

    class Carry(val team: Int, val explainer: String, val ms: Long)

    class TurnLog(val team: Int, val explainer: String, val guessed: List<Int>, val skips: Int, val bonus: Boolean)

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var round = 0; private set

    // ---- settings ----
    var perPlayer = 5
        set(v) { field = v.coerceIn(MIN_PER_PLAYER, MAX_PER_PLAYER) }
    var turnSeconds = 60
        set(v) { field = v.coerceIn(MIN_SECONDS, MAX_SECONDS) }
    var images = true
    /** Off: every character on screen must be guessed, no passing. */
    var skipsAllowed = true
    /** Skip collecting: the app fills the hat itself. */
    var autoPool = false

    // ---- the hat ----
    val cards = ArrayList<Card>()
    private var nextCardId = 1
    val pool = ArrayList<Int>()
    val teams = ArrayList<Team>()
    private var lastTeam = -1
    var carry: Carry? = null; private set

    // ---- current turn ----
    var turnTeam = -1; private set
    var explainer: String? = null; private set
    var current: Int? = null; private set
    private var shownAt = 0L
    var turnStart = 0L; private set
    var deadline = 0L; private set
    var turnMs = 0L; private set
    var bonusTurn = false; private set
    val turnGuessed = ArrayList<Int>()
    private var turnSkips = 0
    var lastTurn: TurnLog? = null; private set
    /** Turn that emptied the hat, for the round summary. */
    private var carryNote: Carry? = null

    private val errors = HashMap<String, String>()
    var notice: String? = null; private set

    // ---------- players ----------

    fun add(id: String, name: String): Player {
        val p = players.getOrPut(id) { Player(id, name) }.also { it.name = name }
        if (p.team == null && teams.isNotEmpty()) {
            val t = teams.minBy { it.members.size }
            t.members += id
            p.team = t.idx
        }
        return p
    }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        errors.remove(pid)
        if (phase == Phase.COLLECT) cards.removeAll { it.author == pid }
        p.team?.let { teams.getOrNull(it)?.members?.remove(pid) }
        if (carry?.explainer == pid) carry = null
        if (pid != explainer) return
        when (phase) {
            Phase.READY -> {
                val t = teams[turnTeam]
                if (t.members.isNotEmpty()) explainer = t.members[t.cursor++ % t.members.size]
                else prepareTurn()
            }
            Phase.TURN, Phase.TURN_END -> {
                current?.let { pool += it }
                current = null
                notice = "${p.name} вийшов(ла) — хід завершено."
                finishTurn()
            }
            else -> Unit
        }
    }

    // ---------- collecting ----------

    fun cardsOf(pid: String) = cards.filter { it.author == pid }

    fun isDone(pid: String) = cardsOf(pid).size >= perPlayer

    fun allDone() = players.isNotEmpty() && players.keys.all { isDone(it) }

    fun canStart() = players.size >= MIN_PLAYERS

    /** LOBBY -> COLLECT, or straight to TEAMS when the app fills the hat. */
    fun startCollect(): Boolean {
        if (phase != Phase.LOBBY || !canStart()) return false
        cards.clear(); nextCardId = 1
        errors.clear(); notice = null
        if (autoPool) {
            pickFromBank(perPlayer * players.size).forEach { cards += Card(nextCardId++, it, null) }
            makeTeams(defaultTeamCount())
        } else {
            phase = Phase.COLLECT
        }
        return true
    }

    fun addCard(pid: String, raw: String): Boolean {
        if (phase != Phase.COLLECT || pid !in players) return false
        errors.remove(pid)
        val name = clean(raw)
        if (name.isEmpty()) return false
        if (isDone(pid)) { errors[pid] = "У тебе вже $perPlayer — прибери когось, щоб додати."; return false }
        if (cards.any { it.name.equals(name, ignoreCase = true) }) {
            errors[pid] = "«$name» вже є в шляпі — придумай когось іншого."
            return false
        }
        cards += Card(nextCardId++, name, pid)
        return true
    }

    /** The author (or the host) picks another picture for a character, or none (""). */
    fun setPic(pid: String?, isHost: Boolean, cardId: Int, url: String): Boolean {
        val c = cards.firstOrNull { it.id == cardId } ?: return false
        if (!isHost && (phase != Phase.COLLECT || pid == null || c.author != pid)) return false
        val u = url.trim()
        if (u.isNotEmpty() && (!u.startsWith("https://") || u.length > MAX_PIC_URL || u.any { it.isWhitespace() || it == '"' || it == '<' })) return false
        c.pic = u
        return true
    }

    fun removeCard(pid: String, cardId: Int): Boolean {
        if (phase != Phase.COLLECT) return false
        return cards.removeAll { it.id == cardId && it.author == pid }
    }

    /** Fills the player's empty slots with random well-known characters. */
    fun autofill(pid: String): Boolean {
        if (phase != Phase.COLLECT || pid !in players) return false
        val need = perPlayer - cardsOf(pid).size
        if (need <= 0) return false
        errors.remove(pid)
        pickFromBank(need).forEach { cards += Card(nextCardId++, it, pid) }
        return true
    }

    fun autofillAll(): Boolean {
        if (phase != Phase.COLLECT) return false
        players.keys.forEach { autofill(it) }
        return true
    }

    private fun pickFromBank(n: Int): List<String> {
        val taken = cards.map { it.name.lowercase() }.toHashSet()
        return bank.filter { it.lowercase() !in taken }.shuffled(random).take(n)
    }

    private fun clean(raw: String) = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

    /** COLLECT -> TEAMS once everyone has filled their slots. */
    fun finishCollect(): Boolean {
        if (phase != Phase.COLLECT || !allDone() || !canStart()) return false
        makeTeams(defaultTeamCount())
        return true
    }

    // ---------- teams ----------

    /** Pairs by default, as in the classic game. */
    fun defaultTeamCount() = (players.size / 2).coerceIn(2, MAX_TEAMS)

    fun maxTeams() = (players.size / 2).coerceIn(2, MAX_TEAMS)

    fun makeTeams(count: Int) {
        val n = count.coerceIn(2, maxTeams())
        val names = TEAM_NAMES.shuffled(random).take(n)
        teams.clear()
        names.forEachIndexed { i, (emoji, name) -> teams += Team(i, name, emoji) }
        players.values.forEach { it.team = null }
        players.keys.shuffled(random).forEachIndexed { i, id ->
            val t = teams[i % n]
            t.members += id
            players[id]!!.team = t.idx
        }
        phase = Phase.TEAMS
    }

    fun setTeamCount(n: Int): Boolean {
        if (phase != Phase.TEAMS) return false
        makeTeams(n)
        return true
    }

    fun reshuffle(): Boolean = setTeamCount(teams.size)

    /** Host moves a player to the next team. */
    fun move(pid: String): Boolean {
        if (phase != Phase.TEAMS) return false
        val p = players[pid] ?: return false
        val from = p.team ?: return false
        teams[from].members -= pid
        val to = (from + 1) % teams.size
        teams[to].members += pid
        p.team = to
        return true
    }

    /** TEAMS -> READY for the first turn of round 1. */
    fun startGame(): Boolean {
        if (phase != Phase.TEAMS || teams.count { it.members.isNotEmpty() } < 2 || cards.isEmpty()) return false
        round = 1
        lastTeam = -1
        carry = null
        teams.forEach { it.cursor = 0; it.score.fill(0) }
        cards.forEach { it.reset() }
        players.values.forEach { it.resetStats() }
        initRound()
        prepareTurn()
        return true
    }

    // ---------- rounds and turns ----------

    private fun initRound() {
        pool.clear()
        pool += cards.map { it.id }.shuffled(random)
        lastTurn = null
        carryNote = null
    }

    private fun prepareTurn() {
        current = null
        turnGuessed.clear(); turnSkips = 0
        deadline = 0; turnStart = 0
        val c = carry?.takeIf { c ->
            c.explainer in players && teams.getOrNull(c.team)?.members?.contains(c.explainer) == true
        }
        carry = null
        if (c != null) {
            turnTeam = c.team
            explainer = c.explainer
            turnMs = c.ms
            bonusTurn = true
            lastTeam = c.team
        } else {
            val next = (1..teams.size).map { (lastTeam + it) % teams.size }.firstOrNull { teams[it].members.isNotEmpty() }
            if (next == null) {
                notice = "Не залишилось гравців у командах."
                resetToLobby(); return
            }
            val t = teams[next]
            turnTeam = next
            explainer = t.members[t.cursor++ % t.members.size]
            turnMs = turnSeconds * 1000L
            bonusTurn = false
            lastTeam = next
        }
        phase = Phase.READY
    }

    /** READY -> TURN: the explainer starts the clock. */
    fun beginTurn(now: Long): Boolean {
        if (phase != Phase.READY) return false
        notice = null
        phase = Phase.TURN
        turnStart = now
        deadline = now + turnMs
        draw(now, null)
        return true
    }

    private fun draw(now: Long, avoid: Int?) {
        if (pool.isEmpty()) { current = null; return }
        val choices = if (pool.size > 1 && avoid != null) pool.filter { it != avoid } else pool
        val id = choices.random(random)
        pool.remove(id)
        current = id
        shownAt = now
    }

    private fun card(id: Int) = cards.first { it.id == id }

    private fun account(now: Long) {
        val id = current ?: return
        card(id).ms[round - 1] += (now - shownAt).coerceAtLeast(0)
        shownAt = now
    }

    private fun credit(id: Int, onScreen: Long) {
        val c = card(id)
        c.guessedBy[round - 1] = explainer
        c.guessMs[round - 1] = onScreen
        teams[turnTeam].score[round - 1]++
        players[explainer]?.let { it.explained++ }
        turnGuessed += id
    }

    /** The team named the character on screen. */
    fun got(now: Long): Boolean {
        if (phase != Phase.TURN) return false
        val id = current ?: return false
        val onScreen = (now - shownAt).coerceAtLeast(0)
        account(now)
        credit(id, onScreen)
        current = null
        if (pool.isEmpty()) {
            val left = deadline - now
            players[explainer]?.let { it.explainMs += now - turnStart }
            val c = if (left >= MIN_CARRY_MS && round < ROUNDS) Carry(turnTeam, explainer!!, left) else null
            carry = c
            carryNote = c
            logTurn()
            endRound()
        } else {
            draw(now, null)
        }
        return true
    }

    /** Back into the hat, next one please. */
    fun skip(now: Long): Boolean {
        if (phase != Phase.TURN || !skipsAllowed) return false
        val id = current ?: return false
        account(now)
        card(id).skips[round - 1]++
        players[explainer]?.let { it.skips++ }
        turnSkips++
        pool += id
        draw(now, id)
        return true
    }

    /** Undo a mistaken "guessed" tap in this turn: that card comes back on screen. */
    fun undo(now: Long): Boolean {
        if (phase != Phase.TURN || turnGuessed.isEmpty()) return false
        val id = turnGuessed.removeAt(turnGuessed.lastIndex)
        val c = card(id)
        c.guessedBy[round - 1] = null
        c.guessMs[round - 1] = 0
        teams[turnTeam].score[round - 1]--
        players[explainer]?.let { it.explained-- }
        current?.let { account(now); pool += it }
        current = id
        shownAt = now
        return true
    }

    fun tick(now: Long): Boolean {
        if (phase != Phase.TURN || now < deadline) return false
        account(deadline)
        players[explainer]?.let { it.explainMs += turnMs }
        phase = Phase.TURN_END
        return true
    }

    /** Time is up — did the team still get the last card? */
    fun lastCard(yes: Boolean): Boolean {
        if (phase != Phase.TURN_END) return false
        current?.let { id ->
            if (yes) credit(id, card(id).ms[round - 1]) else pool += id
        }
        current = null
        finishTurn()
        return true
    }

    /** Host ends the turn now (e.g. someone had to step away). */
    fun endTurn(now: Long): Boolean {
        when (phase) {
            Phase.TURN -> {
                account(now)
                players[explainer]?.let { it.explainMs += now - turnStart }
                current?.let { pool += it }
                current = null
                finishTurn()
            }
            Phase.TURN_END -> lastCard(false)
            Phase.READY -> { finishTurnNoLog() }
            else -> return false
        }
        return true
    }

    private fun logTurn() {
        val ex = explainer ?: return
        lastTurn = TurnLog(turnTeam, ex, turnGuessed.toList(), turnSkips, bonusTurn)
        players[ex]?.let { it.bestTurn = maxOf(it.bestTurn, turnGuessed.size) }
    }

    private fun finishTurn() {
        logTurn()
        finishTurnNoLog()
    }

    private fun finishTurnNoLog() {
        deadline = 0
        if (pool.isEmpty() && current == null) endRound() else prepareTurn()
    }

    private fun endRound() {
        deadline = 0
        current = null
        phase = if (round >= ROUNDS) Phase.FINAL else Phase.ROUND_END
    }

    fun nextRound(): Boolean {
        if (phase != Phase.ROUND_END) return false
        round++
        initRound()
        prepareTurn()
        return true
    }

    /** Host stops early and goes to the final statistics. */
    fun finishEarly(now: Long): Boolean {
        if (phase !in setOf(Phase.READY, Phase.TURN, Phase.TURN_END, Phase.ROUND_END)) return false
        if (phase == Phase.TURN) { account(now); players[explainer]?.let { it.explainMs += now - turnStart } }
        if (phase == Phase.TURN || phase == Phase.TURN_END) logTurn()
        current = null
        carry = null
        deadline = 0
        phase = Phase.FINAL
        return true
    }

    private fun resetToLobby() {
        phase = Phase.LOBBY
        round = 0
        cards.clear(); pool.clear(); teams.clear()
        players.values.forEach { it.team = null; it.resetStats() }
        carry = null; carryNote = null; lastTurn = null
        current = null; explainer = null; turnTeam = -1; deadline = 0
        errors.clear()
    }

    fun newGame() {
        resetToLobby()
        notice = null
    }

    // ---------- state for clients ----------

    private fun cardJson(id: Int) = card(id).let { JSONObject().put("id", it.id).put("name", it.name).putOpt("pic", it.pic) }

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("rounds", ROUNDS)
        o.put("now", now)
        o.put("perPlayer", perPlayer)
        o.put("turnSeconds", turnSeconds)
        o.put("images", images)
        o.put("skips", skipsAllowed)
        o.put("autoPool", autoPool)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("maxTeams", maxTeams())
        o.put("totalCards", cards.size)
        o.put("poolLeft", pool.size + (if (current != null) 1 else 0))
        notice?.let { o.put("notice", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("team", p.team ?: JSONObject.NULL)
                .put("n", cardsOf(p.id).size)
        }))
        o.put("teams", JSONArray(teams.map { t ->
            JSONObject().put("idx", t.idx).put("name", t.name).put("emoji", t.emoji)
                .put("members", JSONArray(t.members))
                .put("score", JSONArray(t.score.toList())).put("total", t.total)
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name).put("team", me.team ?: JSONObject.NULL))
            if (phase == Phase.COLLECT) {
                o.put("myCards", JSONArray(cardsOf(me.id).map { cardJson(it.id) }))
                errors[me.id]?.let { o.put("err", it) }
            }
        }

        if (phase in setOf(Phase.READY, Phase.TURN, Phase.TURN_END)) {
            o.put("turn", JSONObject()
                .put("team", turnTeam)
                .put("explainer", explainer ?: JSONObject.NULL)
                .put("ms", turnMs)
                .put("bonus", bonusTurn)
                .put("deadline", deadline)
                .put("skips", turnSkips)
                .put("guessed", JSONArray(turnGuessed.map { cardJson(it) })))
            // Only the explainer sees the card: everyone else would learn what is left in the hat.
            if (me != null && me.id == explainer) current?.let { o.put("card", cardJson(it)) }
        }

        lastTurn?.let { l ->
            o.put("lastTurn", JSONObject().put("team", l.team).put("explainer", l.explainer)
                .put("guessed", JSONArray(l.guessed.map { cardJson(it) })).put("skips", l.skips).put("bonus", l.bonus))
        }
        if (phase == Phase.ROUND_END) {
            carryNote?.let { o.put("carry", JSONObject().put("team", it.team).put("explainer", it.explainer).put("ms", it.ms)) }
        }
        if (phase == Phase.ROUND_END || phase == Phase.FINAL) o.put("stats", statsJson())
        return o
    }

    private fun roundsPlayed() = if (phase == Phase.FINAL) round.coerceAtLeast(1) else round

    fun statsJson(): JSONObject {
        val r = roundsPlayed()
        val cs = cards.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("author", c.author ?: JSONObject.NULL).putOpt("pic", c.pic)
                .put("ms", c.ms.take(r).sum())
                .put("rounds", JSONArray(c.ms.take(r)))
                .put("skips", c.skips.take(r).sum())
                .put("guessed", c.guessedBy.take(r).count { it != null })
                .put("fastest", c.guessMs.take(r).filterIndexed { i, _ -> c.guessedBy[i] != null }.minOrNull() ?: JSONObject.NULL)
        }.sortedByDescending { it.getLong("ms") }
        val ps = players.values.sortedBy { it.joinOrder }.map { p ->
            val mine = cards.filter { it.author == p.id }
            JSONObject().put("id", p.id).put("explained", p.explained).put("ms", p.explainMs)
                .put("skips", p.skips).put("bestTurn", p.bestTurn)
                .put("authored", mine.size)
                .put("authoredMs", if (mine.isEmpty()) JSONObject.NULL else mine.sumOf { it.ms.take(r).sum() } / mine.size)
        }
        return JSONObject().put("cards", JSONArray(cs)).put("players", JSONArray(ps)).put("roundsPlayed", r)
    }

    companion object {
        const val ROUNDS = 3
        const val MIN_PLAYERS = 4
        const val MIN_PER_PLAYER = 2
        const val MAX_PER_PLAYER = 10
        const val MIN_SECONDS = 20
        const val MAX_SECONDS = 120
        const val MAX_TEAMS = 6
        const val MAX_NAME = 40
        const val MAX_PIC_URL = 600
        /** Leftover shorter than this is not worth a separate turn. */
        const val MIN_CARRY_MS = 3_000L

        val TEAM_NAMES = listOf(
            "🦊" to "Лисиці", "🐻" to "Ведмеді", "🦉" to "Сови", "🐺" to "Вовки",
            "🐸" to "Жаби", "🦄" to "Єдинороги", "🐙" to "Восьминоги", "🦔" to "Їжаки",
            "🐝" to "Бджоли", "🐧" to "Пінгвіни", "🦘" to "Кенгуру", "🐢" to "Черепахи",
        )
    }
}
