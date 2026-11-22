package com.sokolovskiy.party.games.detective

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Pure logic for «Слідство» — a cooperative detective game.
 *
 * A case has six suspects, each with a motive, a place, an item and a time. The server picks one
 * culprit and deals out true pieces of testimony: four "positive" clues describe the culprit, the
 * rest clear other values. Nobody sees the whole picture, so the team has to say their testimony
 * out loud and cross suspects off together.
 *
 * Flow:
 *  BRIEF         – the case and each detective's private testimony; everyone marks ready.
 *  INVESTIGATION – discussion on a timer; testimony can be published, suspects crossed out.
 *  VOTE          – accusation; a wrong accusation spends one of two attempts.
 *  RESULT        – the culprit is revealed, the case counted as solved or failed.
 */
class Game(
    private val bank: CaseBank,
    private val random: Random = Random.Default,
) {
    enum class Phase { LOBBY, BRIEF, INVESTIGATION, VOTE, RESULT }

    class Player(val id: String, var name: String) {
        var connected = false
        val joinOrder = nextOrder++

        companion object {
            private var nextOrder = 0
        }
    }

    class Clue(val id: Int, val text: String)

    class Result(
        val won: Boolean,
        val culprit: Int,
        /** "caught", "wrong" or "split". */
        val reason: String,
        val accused: Int?,
        val note: String,
        val attemptsUsed: Int,
    )

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var round = 0; private set
    var solved = 0; private set
    var failed = 0; private set

    var case: Case? = null; private set
    var culpritIndex = -1; private set
    val inRound = LinkedHashSet<String>()
    val ready = HashSet<String>()
    val votes = LinkedHashMap<String, Int>()
    val cleared = LinkedHashSet<Int>()
    val marks = LinkedHashSet<Int>()
    var attemptsLeft = ATTEMPTS; private set
    var deadline = 0L; private set
    var result: Result? = null; private set
    var notice: String? = null; private set

    /** Investigation time in seconds (default 5 min). */
    var investigationSeconds = DEFAULT_SECONDS
        set(v) { field = v.coerceIn(MIN_SECONDS, MAX_SECONDS) }

    private val allClues = LinkedHashMap<Int, String>()
    private val privateClues = HashMap<String, MutableList<Int>>()
    private val publicClues = LinkedHashSet<Int>()
    private var accusations = 0

    // ---------- players ----------

    /** Adds a player whose id and (unique) name come from the room. */
    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name) }.also { it.name = name }

    fun join(rawName: String): Player {
        val p = Player(UUID.randomUUID().toString().take(8), "")
        p.name = cleanName(rawName, p)
        players[p.id] = p
        return p
    }

    private fun cleanName(raw: String, self: Player): String {
        var base = raw.trim().replace(Regex("\\s+"), " ").take(20)
        if (base.isEmpty()) base = "Гравець ${players.size + 1}"
        var name = base
        var n = 2
        while (players.values.any { it !== self && it.name.equals(name, ignoreCase = true) }) name = "$base ${n++}"
        return name
    }

    /** Removes a player. Their testimony is left on the table so the team does not lose it. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (phase == Phase.LOBBY || phase == Phase.RESULT) {
            inRound -= pid
            ready -= pid
            votes.remove(pid)
            privateClues.remove(pid)
            return
        }
        if (pid !in inRound) {
            votes.remove(pid)
            privateClues.remove(pid)
            return
        }
        inRound -= pid
        ready -= pid
        votes.remove(pid)
        privateClues.remove(pid)?.let { publicClues.addAll(it) }
        if (inRound.size < MIN_PLAYERS) {
            cancel("${p.name} вийшов(ла) — детективів замало, справу закрито.")
        }
    }

    // ---------- round flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    /** LOBBY/RESULT -> BRIEF with a fresh case and a fresh deal of testimony. */
    fun startCase(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.RESULT) return false
        if (!canStart()) return false
        val order = players.values.sortedBy { it.joinOrder }.map { it.id }
        val c = bank.next(random)
        case = c
        culpritIndex = random.nextInt(c.suspects.size)
        inRound.clear()
        inRound += order
        ready.clear()
        votes.clear()
        cleared.clear()
        marks.clear()
        attemptsLeft = ATTEMPTS
        accusations = 0
        deadline = 0
        result = null
        notice = null
        dealClues(c)
        round++
        phase = Phase.BRIEF
        return true
    }

    fun markReady(pid: String) {
        if (phase == Phase.BRIEF && pid in inRound) ready += pid
    }

    fun allReady(): Boolean = inRound.isNotEmpty() && inRound.all { it in ready }

    fun begin(now: Long): Boolean {
        if (phase != Phase.BRIEF || inRound.size < MIN_PLAYERS) return false
        phase = Phase.INVESTIGATION
        ready.clear()
        deadline = now + investigationSeconds * 1000L
        return true
    }

    /** A detective shows their testimony to the whole team. */
    fun share(pid: String, clueId: Int): Boolean {
        if (case == null || phase == Phase.RESULT) return false
        val own = privateClues[pid] ?: return false
        if (!own.remove(clueId)) return false
        publicClues += clueId
        return true
    }

    /** Team notes on the board: crossed out suspects. Public by design. */
    fun toggleMark(index: Int): Boolean {
        val c = case ?: return false
        if (phase == Phase.RESULT || index !in c.suspects.indices) return false
        if (!marks.remove(index)) marks += index
        return true
    }

    fun cluesFor(pid: String): List<Clue> =
        privateClues[pid].orEmpty().sorted().map { Clue(it, allClues[it] ?: "") }

    fun allCluesFor(pid: String): List<Clue> =
        (privateClues[pid].orEmpty() + publicClues).sorted().map { Clue(it, allClues[it] ?: "") }

    // ---------- accusation ----------

    fun callVote(now: Long): Boolean {
        if (phase != Phase.INVESTIGATION) return false
        phase = Phase.VOTE
        votes.clear()
        deadline = now + VOTE_SECONDS * 1000L
        return true
    }

    fun voteCandidates(): List<Int> {
        val c = case ?: return emptyList()
        return c.suspects.indices.filter { it !in cleared }
    }

    fun castVote(pid: String, index: Int, now: Long): Boolean {
        if (phase != Phase.VOTE || pid !in inRound) return false
        if (index !in voteCandidates()) return false
        votes[pid] = index
        if (inRound.all { it in votes }) closeVote(now)
        return true
    }

    /** Resolves the vote (also used by the host to close it early). */
    fun closeVote(now: Long): Boolean {
        if (phase != Phase.VOTE) return false
        if (votes.isEmpty()) {
            finish(false, "split", null, "Ніхто не наважився звинуватити — злочинець утік.")
            return true
        }
        val tally = votes.values.groupingBy { it }.eachCount()
        val max = tally.values.maxOrNull() ?: 0
        val top = tally.filterValues { it == max }.keys
        if (top.size != 1) {
            finish(false, "split", null, "Голоси розділилися — злочинець утік.")
            return true
        }
        accusations++
        val accused = top.first()
        if (accused == culpritIndex) {
            finish(true, "caught", accused, "Ви вказали на справжнього вбивцю. Справу розкрито!")
        } else {
            attemptsLeft--
            if (attemptsLeft > 0) {
                cleared += accused
                votes.clear()
                phase = Phase.INVESTIGATION
                deadline = now + SECOND_CHANCE_SECONDS * 1000L
                notice = "Звинувачення хибне. У вас ще одна спроба."
            } else {
                finish(false, "wrong", accused, "Останню спробу витрачено на невинного. Злочинець утік.")
            }
        }
        return true
    }

    private fun finish(won: Boolean, reason: String, accused: Int?, note: String) {
        phase = Phase.RESULT
        deadline = 0
        votes.clear()
        if (won) solved++ else failed++
        result = Result(won, culpritIndex, reason, accused, note, accusations)
    }

    /** Advances phases whose time ran out. Returns true if anything changed. */
    fun tick(now: Long): Boolean {
        if (deadline == 0L || now < deadline) return false
        return when (phase) {
            Phase.INVESTIGATION -> {
                phase = Phase.VOTE
                votes.clear()
                deadline = now + VOTE_SECONDS * 1000L
                notice = "Час вийшов — час звинувачення."
                true
            }
            Phase.VOTE -> closeVote(now)
            else -> false
        }
    }

    private fun cancel(message: String) {
        phase = Phase.LOBBY
        case = null
        culpritIndex = -1
        inRound.clear()
        ready.clear()
        votes.clear()
        cleared.clear()
        marks.clear()
        allClues.clear()
        privateClues.clear()
        publicClues.clear()
        attemptsLeft = ATTEMPTS
        accusations = 0
        deadline = 0
        result = null
        notice = message
    }

    /** Host aborts the current case without a result. */
    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий зупинив розслідування.")
    }

    fun newGame() {
        cancel("")
        notice = null
        round = 0
        solved = 0
        failed = 0
        bank.reset()
    }

    // ---------- clues ----------

    private fun dealClues(c: Case) {
        val culprit = c.suspects[culpritIndex]
        val positives = listOf(
            "Мотив злочинця — ${culprit.motive}.",
            "Злочинця бачили ${culprit.where}.",
            "При злочинці знайшли — ${culprit.item}.",
            "Злочинця бачили ${culprit.time}.",
        )
        val negatives = ArrayList<String>()
        c.suspects.map { it.motive }.distinct().filter { it != culprit.motive }
            .forEach { negatives += "Мотив злочинця — не $it." }
        c.suspects.map { it.where }.distinct().filter { it != culprit.where }
            .forEach { negatives += "Злочинця не бачили $it." }
        c.suspects.map { it.item }.distinct().filter { it != culprit.item }
            .forEach { negatives += "При злочинці не знайшли — $it." }
        c.suspects.map { it.time }.distinct().filter { it != culprit.time }
            .forEach { negatives += "Злочинця не бачили $it." }

        // Four positive clues always identify exactly one suspect; extra negative testimony fills
        // the deal so every detective gets at least one piece to share. With a big party we add
        // "not this suspect" testimony, and past the whole pool we allow repeats.
        val target = maxOf(4, inRound.size)
        val filler = ArrayList(negatives)
        if (target > positives.size + filler.size) {
            c.suspects.forEachIndexed { i, s ->
                if (i != culpritIndex) filler += "${s.name} — не вбивця."
            }
        }
        val pool = positives + filler.shuffled(random).take((target - 4).coerceAtLeast(0))
        val texts = ArrayList<String>()
        while (texts.size < target) texts += pool.shuffled(random).take(target - texts.size)
        texts.shuffle(random)
        allClues.clear()
        privateClues.clear()
        publicClues.clear()
        texts.forEachIndexed { i, text -> allClues[i] = text }
        val order = inRound.toList()
        order.forEach { privateClues[it] = mutableListOf() }
        texts.forEachIndexed { i, _ -> privateClues[order[i % order.size]]!!.add(i) }
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        val reveal = phase == Phase.RESULT
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("deadline", deadline)
        o.put("seconds", investigationSeconds)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("attemptsLeft", attemptsLeft)
        o.put("solved", solved)
        o.put("failed", failed)
        o.put("isHost", isHost)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject()
                .put("id", p.id).put("name", p.name)
                .put("connected", p.connected)
                .put("inRound", p.id in inRound)
                .put("ready", p.id in ready)
                .put("voted", p.id in votes)
        }))

        val c = case
        if (c != null) {
            o.put("case", JSONObject()
                .put("title", c.title)
                .put("victim", c.victim)
                .put("place", c.place)
                .put("intro", c.intro))
            o.put("suspects", JSONArray(c.suspects.mapIndexed { i, s ->
                JSONObject()
                    .put("i", i)
                    .put("name", s.name)
                    .put("motive", s.motive)
                    .put("where", s.where)
                    .put("item", s.item)
                    .put("time", s.time)
                    .put("marked", i in marks)
                    .put("cleared", i in cleared)
            }))
        }

        val publicIds = if (reveal) allClues.keys.toList().sorted() else publicClues.sorted()
        o.put("publicClues", JSONArray(publicIds.map { allClues[it] ?: "" }))
        o.put("cleared", JSONArray(cleared.toList().sorted()))
        o.put("marked", JSONArray(marks.toList().sorted()))

        val me = viewer?.let { players[it] }
        if (me != null) {
            o.put("me", JSONObject()
                .put("id", me.id)
                .put("name", me.name)
                .put("inRound", me.id in inRound))
            val own = if (reveal) emptyList() else privateClues[me.id].orEmpty().sorted()
            o.put("clues", JSONArray(own.map { id ->
                JSONObject().put("i", id).put("text", allClues[id] ?: "")
            }))
            if (phase == Phase.VOTE) votes[me.id]?.let { o.put("myVote", it) }
        }

        if (phase == Phase.VOTE) {
            o.put("voters", JSONArray(inRound.toList()))
            o.put("candidates", JSONArray(voteCandidates()))
        }

        if (reveal) {
            o.put("culprit", culpritIndex)
            result?.let { r ->
                o.put("result", JSONObject()
                    .put("won", r.won)
                    .put("reason", r.reason)
                    .put("accused", r.accused ?: JSONObject.NULL)
                    .put("note", r.note)
                    .put("attemptsUsed", r.attemptsUsed))
            }
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val ATTEMPTS = 2
        const val DEFAULT_SECONDS = 300
        const val MIN_SECONDS = 120
        const val MAX_SECONDS = 900
        const val VOTE_SECONDS = 60
        const val SECOND_CHANCE_SECONDS = 90
    }
}
