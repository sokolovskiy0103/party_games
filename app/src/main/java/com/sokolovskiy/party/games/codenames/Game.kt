package com.sokolovskiy.party.games.codenames

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Кодові імена» (after Codenames and Codenames Duet). Not thread-safe: the server guards it
 * with a lock. 25 word cards on the table; a secret key says which of them are agents.
 *
 * CLASSIC (two teams, 2+ each): each team has a captain who sees the whole key. The starting team has 9 agents, the
 * other 8; 7 cards are bystanders and one is the assassin. On their turn the captain gives a clue — one word and a
 * number — and the team opens cards: up to number + 1 (no limit for 0 or ∞). Their own agent lets them go on; a
 * bystander or an opponent's agent ends the turn; the assassin loses the game. All own agents open — the team wins.
 *
 * DUET (co-op, two sides of 1+ players; the only mode for 2–3 players): each side sees its own key with 9 agents,
 * 3 assassins and 13 bystanders; the keys overlap so there are 15 agents in total. The sides take turns giving
 * clues from their key; the other side guesses with no limit. An agent on the clue side's key is found; a bystander
 * on it ends the turn (and is marked, so that side won't be asked about it again); an assassin loses. Every clue
 * costs one of [duetTurns] turns. A side with no agents left to show passes the clue back. When the turns run out,
 * sudden death: anyone may still open cards, but any miss loses. All 15 agents found — everybody wins.
 *
 * Flow: LOBBY -> PLAY (steps CLUE -> GUESS …, SUDDEN in duet) -> OVER.
 */
class Game(private val words: WordBank, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, PLAY, OVER }
    enum class Mode { CLASSIC, DUET }
    enum class Step { CLUE, GUESS, SUDDEN }
    enum class Spice { CLEAN, MIXED, ADULT }
    /** BLUE/RED: classic team agents (team 0/1); GREEN: a duet agent. */
    enum class Color { BLUE, RED, GREEN, NEUTRAL, ASSASSIN }

    class Player(val id: String, var name: String, val joinOrder: Int, var team: Int) {
        var connected = false
        /** The card this guesser is pointing at (shown to everyone). */
        var pointing: Int? = null
    }

    class Card(val word: String) {
        /** Classic: the card's colour. */
        var color = Color.NEUTRAL
        /** Duet: the card on side 0's and side 1's key. */
        val keys = arrayOf(Color.NEUTRAL, Color.NEUTRAL)
        /** What the card turned out to be once opened (duet: GREEN, or ASSASSIN on the losing card). */
        var open: Color? = null
        /** Duet: sides whose key showed this card as a bystander. */
        val neutralFor = LinkedHashSet<Int>()
    }

    /** [n]: the number, [UNLIMITED] for ∞. */
    class Clue(val team: Int, val word: String, val n: Int, val by: String)

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    /** The mode the host picked for 4+ players. */
    var chosenMode = Mode.CLASSIC; private set
    /** The mode of the running (or last) game. */
    var mode = Mode.DUET; private set
    var spice = Spice.MIXED; private set
    var duetTurns = DUET_TURNS; private set
    /** Bumped on every new game so clients can reset. */
    var game = 0; private set

    val cards = ArrayList<Card>()
    var step = Step.CLUE; private set
    /** Classic: the team on the move. Duet: the side giving the clue. */
    var turn = 0; private set
    var clue: Clue? = null; private set
    /** Classic: guesses left this turn (null = no limit). */
    var guessesLeft: Int? = null; private set
    /** Cards opened this turn. */
    var guessed = 0; private set
    val log = ArrayList<Clue>()
    var lastOpened: Int? = null; private set

    // classic
    val captains = arrayOfNulls<String>(2)
    val agentsLeft = IntArray(2)
    private val captainTurns = IntArray(2)

    // duet
    var turnsLeft = 0; private set
    var found = 0; private set

    /** OVER. Classic: the winning team. Duet: 1 — everybody won, 0 — lost. */
    var winner: Int? = null; private set
    var reason = ""; private set
    var notice: String? = null; private set

    private var nextOrder = 0

    // ---------- players & teams ----------

    fun members(t: Int) = players.values.filter { it.team == t }.sortedBy { it.joinOrder }.map { it.id }

    private fun smallerTeam() = if (members(1).size < members(0).size) 1 else 0

    /** Newcomers go to the smaller side, also mid-game (they see what that side sees). */
    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++, smallerTeam()) }.also { it.name = name }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (phase != Phase.PLAY) return
        val need = if (mode == Mode.CLASSIC) MIN_PER_TEAM else 1
        if ((0..1).any { members(it).size < need }) {
            cancel("${p.name} вийшов(ла) — замало гравців, гру зупинено.")
            return
        }
        if (mode == Mode.CLASSIC && captains[p.team] == pid) pickCaptain(p.team)
    }

    private fun lobbyish() = phase != Phase.PLAY

    fun switchTeam(pid: String): Boolean {
        if (!lobbyish()) return false
        val p = players[pid] ?: return false
        p.team = 1 - p.team
        return true
    }

    fun shuffleTeams(): Boolean {
        if (!lobbyish()) return false
        players.values.shuffled(random).forEachIndexed { i, p -> p.team = i % 2 }
        return true
    }

    fun setMode(m: Mode): Boolean {
        if (!lobbyish()) return false
        chosenMode = m
        return true
    }

    fun setSpice(s: Spice): Boolean {
        if (!lobbyish()) return false
        spice = s
        return true
    }

    fun setDuetTurns(n: Int): Boolean {
        if (!lobbyish() || n !in DUET_TURN_OPTIONS) return false
        duetTurns = n
        return true
    }

    /** Fewer than four players can only play Duet. */
    fun nextMode() = if (players.size < CLASSIC_MIN) Mode.DUET else chosenMode

    fun canStart() = when (nextMode()) {
        Mode.CLASSIC -> (0..1).all { members(it).size >= MIN_PER_TEAM }
        Mode.DUET -> (0..1).all { members(it).isNotEmpty() }
    }

    // ---------- flow ----------

    fun startGame(): Boolean {
        if (phase == Phase.PLAY || !canStart()) return false
        mode = nextMode()
        cards.clear()
        words.deal(spice, random).mapTo(cards) { Card(it) }
        if (mode == Mode.CLASSIC) dealClassic() else dealDuet()
        log.clear()
        clue = null; guessesLeft = null; guessed = 0; lastOpened = null
        winner = null; reason = ""; notice = null
        players.values.forEach { it.pointing = null }
        step = Step.CLUE
        game++
        phase = Phase.PLAY
        return true
    }

    private fun dealClassic() {
        val first = random.nextInt(2)
        val colors = List(9) { teamColor(first) } + List(8) { teamColor(1 - first) } +
            List(7) { Color.NEUTRAL } + Color.ASSASSIN
        colors.shuffled(random).forEachIndexed { i, c -> cards[i].color = c }
        agentsLeft[first] = 9; agentsLeft[1 - first] = 8
        turn = first
        for (t in 0..1) {
            if (captainTurns[t] == 0) captainTurns[t] = random.nextInt(members(t).size)
            pickCaptain(t)
        }
    }

    /** The next member of team [t] in rotation becomes captain. */
    private fun pickCaptain(t: Int) {
        val m = members(t)
        captains[t] = m.getOrNull(captainTurns[t]++ % maxOf(m.size, 1))
    }

    private fun dealDuet() {
        val g = Color.GREEN; val n = Color.NEUTRAL; val x = Color.ASSASSIN
        // (side 0, side 1) pairs of the official Duet key: 9 agents, 3 assassins, 13 bystanders on each side.
        val pairs = List(3) { g to g } + (g to x) + List(5) { g to n } + (x to g) + (x to x) + (x to n) +
            List(5) { n to g } + (n to x) + List(7) { n to n }
        pairs.shuffled(random).forEachIndexed { i, (a, b) -> cards[i].keys[0] = a; cards[i].keys[1] = b }
        turnsLeft = duetTurns
        found = 0
        turn = random.nextInt(2)
    }

    fun teamColor(t: Int) = if (t == 0) Color.BLUE else Color.RED

    fun clueGiver(pid: String): Boolean {
        if (phase != Phase.PLAY || step != Step.CLUE) return false
        val p = players[pid] ?: return false
        return if (mode == Mode.CLASSIC) captains[turn] == pid else p.team == turn
    }

    /** Whose key a card is checked against when [pid] opens it (duet: the other side's). */
    private fun keySide(pid: String) = 1 - players[pid]!!.team

    fun canGuess(pid: String): Boolean {
        if (phase != Phase.PLAY) return false
        val p = players[pid] ?: return false
        return when {
            mode == Mode.CLASSIC -> step == Step.GUESS && p.team == turn && captains[turn] != pid
            step == Step.SUDDEN -> true
            else -> step == Step.GUESS && p.team == 1 - turn
        }
    }

    private fun openable(pid: String, idx: Int): Boolean {
        val c = cards.getOrNull(idx) ?: return false
        if (c.open != null) return false
        return mode == Mode.CLASSIC || keySide(pid) !in c.neutralFor
    }

    fun submitClue(pid: String, word: String, n: Int): Boolean {
        if (!clueGiver(pid)) return false
        val w = word.trim().replace(Regex("\\s+"), " ").take(MAX_CLUE)
        if (w.isEmpty() || isBoardWord(w)) return false
        val num = if (n == UNLIMITED) n else n.coerceIn(0, 9)
        clue = Clue(turn, w, num, players[pid]!!.name).also { log += it }
        guessesLeft = if (mode == Mode.CLASSIC && num in 1..9) num + 1 else null
        guessed = 0
        step = Step.GUESS
        return true
    }

    /** A clue may not be one of the words still on the table. */
    fun isBoardWord(w: String) = cards.any { it.open == null && it.word.equals(w.trim(), ignoreCase = true) }

    fun point(pid: String, idx: Int): Boolean {
        if (!canGuess(pid) || !openable(pid, idx)) return false
        val p = players[pid]!!
        p.pointing = if (p.pointing == idx) null else idx
        return true
    }

    fun open(pid: String, idx: Int): Boolean {
        if (!canGuess(pid) || !openable(pid, idx)) return false
        lastOpened = idx
        guessed++
        players.values.forEach { if (it.pointing == idx) it.pointing = null }
        if (mode == Mode.CLASSIC) openClassic(cards[idx]) else openDuet(pid, cards[idx])
        return true
    }

    private fun openClassic(c: Card) {
        c.open = c.color
        val own = teamColor(turn)
        when (c.color) {
            Color.ASSASSIN -> return finish(1 - turn, "${teamName(turn)} відкрили вбивцю")
            Color.BLUE, Color.RED -> {
                val t = if (c.color == Color.BLUE) 0 else 1
                if (--agentsLeft[t] == 0) {
                    return finish(t, if (t == turn) "${teamName(t)} знайшли всіх своїх агентів"
                    else "${teamName(turn)} відкрили останнього агента суперників")
                }
            }
            else -> {}
        }
        if (c.color != own) return passTurn()
        guessesLeft = guessesLeft?.minus(1)
        if (guessesLeft == 0) passTurn()
    }

    private fun openDuet(pid: String, c: Card) {
        val side = keySide(pid)
        when (c.keys[side]) {
            Color.GREEN -> {
                c.open = Color.GREEN
                if (++found == DUET_AGENTS) return finish(1, "Усіх $DUET_AGENTS агентів знайдено")
                // nothing left to find on this key: the clue is used up
                if (step == Step.GUESS && greensLeft(side) == 0) endDuetTurn()
            }
            Color.ASSASSIN -> {
                c.open = Color.ASSASSIN
                finish(0, "Це був вбивця")
            }
            else -> {
                c.neutralFor += side
                if (step == Step.SUDDEN) finish(0, "У раптовій смерті будь-який промах програє") else endDuetTurn()
            }
        }
    }

    fun greensLeft(side: Int) = cards.count { it.open == null && it.keys[side] == Color.GREEN }

    /** The guessers stop (after at least one card). */
    fun endTurn(pid: String): Boolean {
        if (!canGuess(pid) || step != Step.GUESS || guessed == 0) return false
        if (mode == Mode.CLASSIC) passTurn() else endDuetTurn()
        return true
    }

    private fun passTurn() {
        turn = 1 - turn
        resetTurn()
    }

    private fun endDuetTurn() {
        turnsLeft--
        resetTurn()
        if (turnsLeft <= 0) { step = Step.SUDDEN; return }
        // the sides alternate, unless the next one has no agents left to show
        if (greensLeft(1 - turn) > 0) turn = 1 - turn
    }

    private fun resetTurn() {
        clue = null; guessesLeft = null; guessed = 0
        step = Step.CLUE
        players.values.forEach { it.pointing = null }
    }

    private fun finish(w: Int, why: String) {
        winner = w
        reason = why
        clue = null
        players.values.forEach { it.pointing = null }
        phase = Phase.OVER
    }

    private fun teamName(t: Int) = if (t == 0) "Сині" else "Червоні"

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        cards.clear(); log.clear()
        clue = null
        players.values.forEach { it.pointing = null }
        notice = msg
    }

    fun abort() {
        if (phase == Phase.PLAY) cancel("Ведучий перервав гру.")
    }

    /** OVER -> LOBBY (to change teams or settings). */
    fun toLobby() {
        if (phase != Phase.OVER) return
        cancel("")
        notice = null
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("game", game)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("mode", (if (phase == Phase.LOBBY) nextMode() else mode).name)
        o.put("chosenMode", chosenMode.name)
        o.put("classicMin", CLASSIC_MIN)
        o.put("minPerTeam", MIN_PER_TEAM)
        o.put("canStart", canStart())
        o.put("spice", spice.name)
        o.put("duetTurns", duetTurns)
        o.put("duetTurnOptions", JSONArray(DUET_TURN_OPTIONS))
        o.put("maxClue", MAX_CLUE)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("connected", p.connected).put("team", p.team)
        }))
        o.put("teams", JSONArray((0..1).map { JSONArray(members(it)) }))

        if (phase != Phase.LOBBY) {
            o.put("step", step.name)
            o.put("turn", turn)
            clue?.let { o.put("clue", clueJson(it)) }
            o.put("log", JSONArray(log.map { clueJson(it) }))
            o.put("guessed", guessed)
            guessesLeft?.let { o.put("guessesLeft", it) }
            lastOpened?.let { o.put("lastOpened", it) }
            if (mode == Mode.CLASSIC) {
                o.put("captains", JSONArray(captains.map { it ?: JSONObject.NULL }))
                o.put("agentsLeft", JSONArray(agentsLeft.toList()))
            } else {
                o.put("turnsLeft", turnsLeft).put("found", found).put("agents", DUET_AGENTS)
            }
            if (phase == Phase.OVER) o.put("winner", winner).put("reason", reason)
            o.put("cards", cardsJson(viewer))
        }

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject().put("id", me.id).put("name", me.name).put("team", me.team)
                .put("captain", phase != Phase.LOBBY && mode == Mode.CLASSIC && me.id in captains)
                .put("giver", clueGiver(me.id))
                .put("guesser", canGuess(me.id))
                .put("canEnd", canGuess(me.id) && step == Step.GUESS && guessed > 0))
        }
        return o
    }

    private fun clueJson(c: Clue) = JSONObject().put("team", c.team).put("word", c.word).put("n", c.n).put("by", c.by)

    private fun cardsJson(viewer: String?): JSONArray {
        val me = viewer?.let { players[it] }
        val over = phase == Phase.OVER
        val captain = me != null && me.id in captains
        return JSONArray(cards.mapIndexed { i, c ->
            val o = JSONObject().put("w", c.word)
            c.open?.let { o.put("open", it.name) }
            if (mode == Mode.CLASSIC) {
                // the key is the captains' secret until the end
                if (over || captain) o.put("key", c.color.name)
            } else {
                o.put("neutral", JSONArray(c.neutralFor.toList()))
                if (over) o.put("k0", c.keys[0].name).put("k1", c.keys[1].name)
                else if (me != null) o.put("key", c.keys[me.team].name)
            }
            if (me != null && phase == Phase.PLAY) o.put("canOpen", canGuess(me.id) && openable(me.id, i))
            val ptr = players.values.filter { it.pointing == i }.map { it.id }
            if (ptr.isNotEmpty()) o.put("ptr", JSONArray(ptr))
            o
        })
    }

    companion object {
        const val CARDS = 25
        const val MIN_PER_TEAM = 2
        const val CLASSIC_MIN = 2 * MIN_PER_TEAM
        const val DUET_AGENTS = 15
        const val DUET_TURNS = 9
        val DUET_TURN_OPTIONS = listOf(11, 9, 7)
        const val MAX_CLUE = 30
        const val UNLIMITED = -1
    }
}
