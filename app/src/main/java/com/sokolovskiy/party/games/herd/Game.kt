package com.sokolovskiy.party.games.herd

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** Question pack: level id -> questions. */
class QuestionBank(val levels: Map<String, List<String>>) {
    private val used = HashSet<String>()

    fun pick(allowed: Collection<String>, rnd: Random): Pair<String, String>? {
        val pool = allowed.flatMap { lvl -> levels[lvl].orEmpty().map { lvl to it } }
        if (pool.isEmpty()) return null
        var fresh = pool.filter { it.second !in used }
        if (fresh.isEmpty()) {
            // every question in these levels was asked: start over
            pool.forEach { used.remove(it.second) }
            fresh = pool
        }
        val q = fresh[rnd.nextInt(fresh.size)]
        used += q.second
        return q
    }

    companion object {
        fun fromJson(text: String): QuestionBank {
            val o = JSONObject(text)
            val map = LinkedHashMap<String, List<String>>()
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                map[k] = (0 until arr.length()).map { arr.getString(it) }
            }
            return QuestionBank(map)
        }
    }
}

/**
 * Herd Mentality rules. Not thread-safe: callers serialise access.
 *
 * - Everyone secretly answers the same question.
 * - The single largest group (2+ people) gets a cow each. A tie for largest: nobody scores.
 * - If exactly one player is alone with their answer, they take the pink cow
 *   (and can't win while holding it).
 * - First to [target] cows without the pink cow wins.
 */
class Game(private val bank: QuestionBank, private val rnd: Random = Random.Default) {

    class Player(val id: String, var name: String) {
        var score = 0
        var connected = false
    }

    enum class Phase { LOBBY, QUESTION, JUDGING, RESULTS, GAMEOVER }

    data class Result(
        val majorityGroup: Int,     // index in groups, -1 if none
        val tie: Boolean,
        val scored: List<String>,
        val pinkFrom: String?,
        val pinkTo: String?,        // non-null only when the pink cow moved this round
    )

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY
        private set
    val levels = linkedSetOf("light", "spicy")
    var target = 8
    var round = 0
        private set
    var question = ""
        private set
    var questionLevel = ""
        private set
    /** playerId -> answer text, in submission order */
    val answers = LinkedHashMap<String, String>()
    /** groups of playerIds; only meaningful in JUDGING / RESULTS / GAMEOVER */
    var groups: MutableList<MutableList<String>> = mutableListOf()
        private set
    var groupSource = ""
    /** playerId -> what the judge model thinks the answer means (shown as group title) */
    val meanings = HashMap<String, String>()
    /** Hide who wrote which answer; members are addressed by per-round tokens. */
    var anonymous = false
    private val tokens = HashMap<String, String>()   // playerId -> token
    var result: Result? = null
        private set
    var pinkCow: String? = null
        private set
    var winners: List<String> = emptyList()
        private set

    // ---------- players ----------

    /** Adds a player whose id and (unique) name come from the room. */
    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name) }.also { it.name = name }

    fun join(name: String, existingId: String?): Player {
        val clean = name.trim().take(20).ifEmpty { "Гравець" }
        val existing = existingId?.let { players[it] }
        if (existing != null) {
            existing.name = clean
            return existing
        }
        val p = Player(UUID.randomUUID().toString().take(8), uniqueName(clean))
        players[p.id] = p
        return p
    }

    private fun uniqueName(name: String): String {
        if (players.values.none { it.name.equals(name, true) }) return name
        var i = 2
        while (players.values.any { it.name.equals("$name $i", true) }) i++
        return "$name $i"
    }

    fun remove(id: String) {
        players.remove(id) ?: return
        answers.remove(id)
        groups.forEach { it.remove(id) }
        groups.removeAll { it.isEmpty() }
        if (pinkCow == id) pinkCow = null
    }

    // ---------- flow ----------

    fun setLevels(newLevels: Collection<String>) {
        val valid = newLevels.filter { it in bank.levels.keys }
        if (valid.isEmpty()) return
        levels.clear(); levels.addAll(valid)
    }

    fun start(): Boolean {
        if (phase != Phase.LOBBY || players.isEmpty()) return false
        nextQuestion()
        return true
    }

    fun nextQuestion() {
        val q = bank.pick(levels, rnd) ?: return
        beginRound(q.second, q.first)
    }

    fun customQuestion(text: String) {
        val t = text.trim().take(200)
        if (t.isEmpty()) return
        beginRound(t, "custom")
    }

    private fun beginRound(q: String, level: String) {
        if (phase == Phase.GAMEOVER) return
        if (phase == Phase.QUESTION) round-- // replacing the current question doesn't count as a round
        round++
        question = q
        questionLevel = level
        answers.clear()
        meanings.clear()
        groups = mutableListOf()
        groupSource = ""
        result = null
        phase = Phase.QUESTION
    }

    fun submitAnswer(playerId: String, text: String) {
        if (phase != Phase.QUESTION || playerId !in players) return
        val t = text.trim().replace(Regex("\\s+"), " ").take(80)
        if (t.isEmpty()) answers.remove(playerId) else answers[playerId] = t
    }

    /** Close answering; returns the answers to be judged (playerId to text). */
    fun closeAnswers(): List<Pair<String, String>> {
        if (phase != Phase.QUESTION) return emptyList()
        phase = Phase.JUDGING
        val list = answers.entries.filter { it.key in players }.map { it.key to it.value }
        groups = list.map { mutableListOf(it.first) }.toMutableList()
        groupSource = "pending"
        tokens.clear()
        list.forEach { tokens[it.first] = UUID.randomUUID().toString().take(10) }
        return list
    }

    /** Apply groups from a judge: index groups into [judged]. */
    fun applyGroups(
        judged: List<Pair<String, String>>,
        idxGroups: List<List<Int>>,
        source: String,
        labels: List<String>? = null,
    ) {
        if (phase != Phase.JUDGING) return
        meanings.clear()
        labels?.forEachIndexed { i, l -> if (i in judged.indices && !l.startsWith("#")) meanings[judged[i].first] = l }
        val seen = HashSet<Int>()
        val out = mutableListOf<MutableList<String>>()
        for (g in idxGroups) {
            val ids = g.filter { it in judged.indices && seen.add(it) }
                .map { judged[it].first }
                .filter { it in answers }
            if (ids.isNotEmpty()) out += ids.toMutableList()
        }
        judged.indices.filter { it !in seen }.forEach { i ->
            val id = judged[i].first
            if (id in answers) out += mutableListOf(id)
        }
        out.sortByDescending { it.size }
        groups = out
        groupSource = source
    }

    /** Host moves a player's answer to group [toGroup] (index), or to a new group when -1. */
    /** Accepts a playerId or an anonymous answer token. */
    fun resolveMember(idOrToken: String): String =
        if (idOrToken in players) idOrToken
        else tokens.entries.firstOrNull { it.value == idOrToken }?.key ?: idOrToken

    fun move(member: String, toGroup: Int) {
        if (phase != Phase.JUDGING) return
        val playerId = resolveMember(member)
        val from = groups.indexOfFirst { playerId in it }
        if (from < 0 || from == toGroup) return
        val target: MutableList<String>? = when {
            toGroup == -1 -> null
            toGroup in groups.indices -> groups[toGroup]
            else -> return
        }
        groups[from].remove(playerId)
        if (target == null) groups.add(mutableListOf(playerId)) else target.add(playerId)
        groups.removeAll { it.isEmpty() }
        if (groupSource != "pending") groupSource = "manual"
    }

    fun confirm() {
        if (phase != Phase.JUDGING) return
        val sizes = groups.map { it.size }
        val max = sizes.maxOrNull() ?: 0
        val topCount = sizes.count { it == max }
        val majority = if (max >= 2 && topCount == 1) sizes.indexOf(max) else -1
        val scored = if (majority >= 0) groups[majority].toList() else emptyList()
        scored.forEach { players[it]?.let { p -> p.score++ } }

        val loners = groups.filter { it.size == 1 }
        val from = pinkCow
        var to: String? = null
        if (loners.size == 1 && groups.size >= 2) {
            val loner = loners[0][0]
            if (loner != pinkCow) {
                pinkCow = loner
                to = loner
            }
        }
        result = Result(majority, max >= 2 && topCount > 1, scored, from, to)

        val eligible = players.values.filter { it.score >= target && it.id != pinkCow }
        if (eligible.isNotEmpty()) {
            val best = eligible.maxOf { it.score }
            winners = eligible.filter { it.score == best }.map { it.id }
            phase = Phase.GAMEOVER
        } else {
            phase = Phase.RESULTS
        }
    }

    fun newGame() {
        players.values.forEach { it.score = 0 }
        pinkCow = null
        winners = emptyList()
        round = 0
        question = ""
        answers.clear()
        groups = mutableListOf()
        result = null
        phase = Phase.LOBBY
    }

    // ---------- state for clients ----------

    fun stateJson(me: String?, isHost: Boolean): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("target", target)
        o.put("levels", JSONArray(levels.toList()))
        o.put("question", question)
        o.put("questionLevel", questionLevel)
        o.put("pinkCow", pinkCow ?: JSONObject.NULL)
        o.put("anonymous", anonymous)
        val ps = JSONArray()
        for (p in players.values) {
            ps.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("score", p.score)
                put("connected", p.connected)
                put("answered", p.id in answers)
            })
        }
        o.put("players", ps)
        o.put("answeredCount", answers.size)

        // answers are revealed to everyone once answering closes — arguing about them is half the fun
        if (phase == Phase.JUDGING || phase == Phase.RESULTS || phase == Phase.GAMEOVER) {
            val gs = JSONArray()
            for (g in groups) {
                val members = JSONArray()
                // anonymous: no names, token ids, and an order that says nothing about who answered first
                val ordered = if (anonymous) g.sortedBy { tokens[it] ?: it } else g
                for (id in ordered) {
                    members.put(JSONObject().apply {
                        put("id", if (anonymous) (tokens[id] ?: "") else id)
                        put("name", if (anonymous) "" else (players[id]?.name ?: "?"))
                        put("text", answers[id] ?: "")
                        put("mine", id == me)
                    })
                }
                gs.put(JSONObject().apply {
                    put("label", groupLabel(g))
                    put("members", members)
                })
            }
            o.put("groups", gs)
            o.put("groupSource", groupSource)
        }
        result?.let { r ->
            o.put("result", JSONObject().apply {
                put("majorityGroup", r.majorityGroup)
                put("tie", r.tie)
                put("scored", JSONArray(r.scored))
                put("pinkFrom", r.pinkFrom ?: JSONObject.NULL)
                put("pinkTo", r.pinkTo ?: JSONObject.NULL)
            })
        }
        if (phase == Phase.GAMEOVER) o.put("winners", JSONArray(winners))

        if (me != null && me in players) {
            o.put("me", JSONObject().apply {
                put("id", me)
                put("name", players[me]!!.name)
                put("answer", answers[me] ?: "")
            })
        }
        o.put("isHost", isHost)
        return o
    }

    private fun groupLabel(ids: List<String>): String {
        // the model's meaning, when most of the group shares it
        val m = ids.mapNotNull { meanings[it] }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (m != null && m.value * 2 > ids.size) return m.key
        val texts = ids.mapNotNull { answers[it] }
        if (texts.isEmpty()) return ""
        // most common normalised spelling, shown in its first original form
        val byKey = texts.groupBy { FuzzyMatcher.normalize(it) }
        return byKey.maxByOrNull { it.value.size }!!.value.first()
    }
}
