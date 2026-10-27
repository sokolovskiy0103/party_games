package com.sokolovskiy.party.games.words

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Скажи інакше». Not thread-safe: the hub guards it with a lock.
 * Time is passed in explicitly so tests can drive it.
 *
 * Flow:
 *  LOBBY    – host picks themes, turn length, the score to win, the taboo mode, the battle mode.
 *  TEAMS    – random teams (or, in the battle mode, «Хлопці» and «Дівчата» that players join themselves).
 *  READY    – whose turn it is; the explainer starts the clock.
 *  TURN     – the explainer goes through words: guessed, skipped or (taboo mode) a foul.
 *  LAST     – time is up, the word on screen is still being explained: who got it?
 *  REVIEW   – everyone sees the turn's words; the explainer (or host) fixes marks and confirms.
 *  FINAL    – a team reached the goal after a full circle and leads alone.
 *
 * In the taboo mode the other teams see the word with its forbidden words and can call a foul.
 * In the battle mode «Хлопці» explain the «female» bank and «Дівчата» the «male» one.
 * In the kids mode (for children who cannot read yet) the card shows a big picture (an emoji) with the word,
 * the words come from the `kidspics` bank: easy things (animals, food, toys, transport…), no taboo, no battle.
 */
class Game(
    private val banks: Map<String, List<Word>>,
    private val random: Random = Random.Default,
) {
    enum class Phase { LOBBY, TEAMS, READY, TURN, LAST, REVIEW, FINAL }
    enum class Mark { GOT, SKIP, FOUL }

    /** [e] is the picture of the word (an emoji), empty if it has none. */
    class Word(val w: String, val taboo: List<String>, val e: String = "")

    class Player(val id: String, var name: String) {
        var connected = false
        val joinOrder = nextOrder++
        var team: Int? = null
        var explained = 0

        companion object {
            private var nextOrder = 0
        }
    }

    class Team(val idx: Int, val name: String, val emoji: String) {
        val members = ArrayList<String>()
        var score = 0
        var turns = 0
        var cursor = 0
    }

    class Entry(val word: Word) {
        var mark: Mark? = null
    }

    class TurnLog(val team: Int, val explainer: String, val words: List<Pair<String, Mark?>>, val delta: Int, val lastBy: Int)

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set

    // ---- settings ----
    var turnSeconds = 60
        set(v) { field = v.coerceIn(MIN_SECONDS, MAX_SECONDS) }
    var goal = 40
        set(v) { field = v.coerceIn(MIN_GOAL, MAX_GOAL) }
    /** A skipped word costs a point. */
    var skipPenalty = true
    /** Forbidden words on the card; the other teams watch and call fouls. */
    var taboo = false
    /** The word on screen when time runs out may be guessed by any team. */
    var lastForAll = true
    /** Two fixed teams, boys and girls, each explaining the other side's words. */
    var battle = false
    /** Picture cards with easy words for children who cannot read yet. */
    var kids = false
    val themes = linkedSetOf("basic", "medium")

    // ---- game ----
    val teams = ArrayList<Team>()
    private var lastTeam = -1
    /** One deck per team in the battle mode, a shared one ("") otherwise. */
    private val decks = HashMap<String, ArrayDeque<Word>>()

    // ---- current turn ----
    var turnTeam = -1; private set
    var explainer: String? = null; private set
    var deadline = 0L; private set
    val entries = ArrayList<Entry>()
    /** Who guessed the last word: a team index, or -1 for nobody. */
    var lastBy = -1; private set
    var lastTurn: TurnLog? = null; private set

    var notice: String? = null; private set
    var winner: Int? = null; private set

    // ---------- players ----------

    fun add(id: String, name: String): Player {
        val p = players.getOrPut(id) { Player(id, name) }.also { it.name = name }
        if (p.team == null && teams.isNotEmpty() && !battle) {
            val t = teams.minBy { it.members.size }
            t.members += id
            p.team = t.idx
        }
        return p
    }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        p.team?.let { teams.getOrNull(it)?.members?.remove(pid) }
        if (pid != explainer) return
        when (phase) {
            Phase.READY -> prepareTurn(sameTeam = true)
            Phase.TURN, Phase.LAST -> {
                notice = "${p.name} вийшов(ла) — хід завершено."
                toReview(lastWordLost = true)
            }
            else -> Unit
        }
    }

    fun canStart() = players.size >= MIN_PLAYERS

    // ---------- settings ----------

    fun toggleTheme(id: String): Boolean {
        if (phase != Phase.LOBBY || id !in THEMES.map { it.id }) return false
        if (id in themes) { if (themes.size > 1) themes -= id else return false } else themes += id
        return true
    }

    /** The host's mode: classic, taboo or kids (kids gets calmer defaults: no skip penalty, 90 s, 10 points). */
    fun setMode(mode: String): Boolean {
        if (phase != Phase.LOBBY) return false
        when (mode) {
            "classic" -> { taboo = false; kids = false }
            "taboo" -> { taboo = true; kids = false }
            "kids" -> {
                if (!kids) { skipPenalty = false; turnSeconds = 90; goal = 10 }
                kids = true; taboo = false; battle = false
            }
            else -> return false
        }
        return true
    }

    // ---------- teams ----------

    fun maxTeams() = (players.size / 2).coerceIn(2, MAX_TEAMS)

    /** LOBBY -> TEAMS. */
    fun toTeams(): Boolean {
        if (phase != Phase.LOBBY || !canStart()) return false
        notice = null
        if (battle && !kids) {
            teams.clear()
            BATTLE_TEAMS.forEachIndexed { i, (emoji, name) -> teams += Team(i, name, emoji) }
            players.values.forEach { it.team = null }
            phase = Phase.TEAMS
        } else {
            makeTeams((players.size / 2).coerceIn(2, MAX_TEAMS))
        }
        return true
    }

    private fun makeTeams(count: Int) {
        val n = count.coerceIn(2, maxTeams())
        teams.clear()
        TEAM_NAMES.shuffled(random).take(n).forEachIndexed { i, (emoji, name) -> teams += Team(i, name, emoji) }
        players.values.forEach { it.team = null }
        players.keys.shuffled(random).forEachIndexed { i, id ->
            teams[i % n].members += id
            players[id]!!.team = i % n
        }
        phase = Phase.TEAMS
    }

    fun setTeamCount(n: Int): Boolean {
        if (phase != Phase.TEAMS || battle) return false
        makeTeams(n)
        return true
    }

    fun reshuffle(): Boolean = setTeamCount(teams.size)

    /** A player joins a team: any time while teams form, later only if they have none yet. */
    fun join(pid: String, idx: Int): Boolean {
        val p = players[pid] ?: return false
        val to = teams.getOrNull(idx) ?: return false
        if (phase == Phase.LOBBY || phase == Phase.FINAL) return false
        if (phase != Phase.TEAMS && p.team != null) return false
        if (p.team == idx) return false
        p.team?.let { teams[it].members -= pid }
        to.members += pid
        p.team = idx
        return true
    }

    /** Host moves a player to the next team. */
    fun move(pid: String): Boolean {
        if (phase != Phase.TEAMS) return false
        val p = players[pid] ?: return false
        return join(pid, ((p.team ?: -1) + 1) % teams.size)
    }

    fun teamsReady() = teams.size >= 2 && teams.all { it.members.size >= 2 }

    /** TEAMS -> READY for the first turn. */
    fun startGame(): Boolean {
        if (phase != Phase.TEAMS || !teamsReady()) return false
        teams.forEach { it.score = 0; it.turns = 0; it.cursor = 0 }
        players.values.forEach { it.explained = 0 }
        decks.clear()
        lastTeam = -1
        lastTurn = null
        winner = null
        notice = null
        prepareTurn(sameTeam = false)
        return true
    }

    // ---------- words ----------

    /** Which bank a team explains: the opposite side's in the battle mode, the chosen themes otherwise. */
    private fun deckKey(team: Int) = if (kids) KIDS_BANK else if (battle) (if (team == 0) "female" else "male") else ""

    /** The picture of a word, looked up by its text (turn logs only keep the text). */
    private fun emojiOf(w: String): String =
        (banks[KIDS_BANK].orEmpty() + banks.values.flatten()).firstOrNull { it.w == w && it.e.isNotEmpty() }?.e.orEmpty()

    private fun fill(key: String): ArrayDeque<Word> {
        val ids = if (key.isEmpty()) themes.toList() else listOf(key)
        val seen = HashSet<String>()
        val words = ids.flatMap { banks[it].orEmpty() }.filter { seen.add(it.w.lowercase()) }
        return ArrayDeque(words.shuffled(random))
    }

    private fun drawWord(): Word? {
        val key = deckKey(turnTeam)
        val deck = decks.getOrPut(key) { fill(key) }
        // a used-up deck starts over, minus the words of this turn if there are others
        if (deck.isEmpty()) {
            val now = entries.map { it.word.w }.toSet()
            val all = fill(key)
            deck.addAll(all.filter { it.w !in now }.ifEmpty { all })
        }
        return deck.removeFirstOrNull()
    }

    // ---------- turns ----------

    private fun prepareTurn(sameTeam: Boolean) {
        entries.clear()
        lastBy = -1
        deadline = 0
        val order = if (sameTeam && turnTeam >= 0) listOf(turnTeam) + (1 until teams.size).map { (turnTeam + it) % teams.size }
        else (1..teams.size).map { (lastTeam + it) % teams.size }
        val next = order.firstOrNull { teams[it].members.isNotEmpty() }
        if (next == null || teams.count { it.members.isNotEmpty() } < 2) {
            notice = "Замало гравців у командах — починаємо спочатку."
            resetToLobby()
            return
        }
        val t = teams[next]
        turnTeam = next
        explainer = t.members[t.cursor++ % t.members.size]
        lastTeam = next
        phase = Phase.READY
    }

    /** READY -> TURN: the explainer starts the clock. */
    fun beginTurn(now: Long): Boolean {
        if (phase != Phase.READY) return false
        val w = drawWord() ?: run { notice = "Слова скінчились — оберіть інші теми."; return false }
        notice = null
        entries += Entry(w)
        deadline = now + turnSeconds * 1000L
        phase = Phase.TURN
        return true
    }

    val current: Entry? get() = entries.lastOrNull()?.takeIf { it.mark == null }

    private fun markAndNext(m: Mark): Boolean {
        if (phase != Phase.TURN) return false
        val e = current ?: return false
        if (m == Mark.FOUL && !taboo) return false
        e.mark = m
        drawWord()?.let { entries += Entry(it) } ?: toReview(lastWordLost = true)
        return true
    }

    fun got() = markAndNext(Mark.GOT)
    fun skip() = markAndNext(Mark.SKIP)
    fun foul() = markAndNext(Mark.FOUL)

    /** Players allowed to call a foul: everyone not in the explaining team. */
    fun canWatch(pid: String?) = taboo && pid != null && players[pid]?.team.let { it != null && it != turnTeam }

    fun tick(now: Long): Boolean {
        if (phase != Phase.TURN || now < deadline) return false
        deadline = 0
        phase = Phase.LAST
        return true
    }

    /** Who guessed the word left on screen: a team index or -1 for nobody. */
    fun lastWord(by: Int): Boolean {
        if (phase != Phase.LAST) return false
        if (by != -1 && teams.getOrNull(by) == null) return false
        if (!lastForAll && by != -1 && by != turnTeam) return false
        lastBy = by
        toReview(lastWordLost = false)
        return true
    }

    private fun toReview(lastWordLost: Boolean) {
        deadline = 0
        if (lastWordLost) { lastBy = -1; current?.let { entries.remove(it) } }
        phase = Phase.REVIEW
    }

    /** REVIEW: correct a mark (the team says it was guessed after all, or it was a foul). */
    fun mark(i: Int, m: Mark): Boolean {
        if (phase != Phase.REVIEW) return false
        val e = entries.getOrNull(i) ?: return false
        if (e.mark == null || (m == Mark.FOUL && !taboo)) return false
        e.mark = m
        return true
    }

    fun setLastBy(by: Int): Boolean {
        if (phase != Phase.REVIEW || current == null) return false
        if (by != -1 && (teams.getOrNull(by) == null || (!lastForAll && by != turnTeam))) return false
        lastBy = by
        return true
    }

    /** Points of the turn for the explaining team (the last word counts for whoever got it). */
    fun turnDelta(): Int = entries.sumOf {
        when (it.mark) {
            Mark.GOT -> 1
            Mark.SKIP -> if (skipPenalty) -1 else 0
            Mark.FOUL -> -1
            null -> 0
        }
    } + (if (lastBy == turnTeam && current != null) 1 else 0)

    /** REVIEW -> next turn or FINAL. */
    fun confirm(): Boolean {
        if (phase != Phase.REVIEW) return false
        val t = teams[turnTeam]
        val delta = turnDelta()
        t.score += delta
        if (current != null && lastBy >= 0 && lastBy != turnTeam) teams.getOrNull(lastBy)?.let { it.score++ }
        t.turns++
        players[explainer]?.let { p -> p.explained += entries.count { it.mark == Mark.GOT } + (if (lastBy == turnTeam && current != null) 1 else 0) }
        lastTurn = TurnLog(turnTeam, explainer!!, entries.map { it.word.w to it.mark }, delta, if (current != null) lastBy else -2)
        entries.clear()
        if (!checkWin()) prepareTurn(sameTeam = false)
        return true
    }

    /** After a full circle (every playing team had as many turns) a team at the goal that leads alone wins. */
    private fun checkWin(): Boolean {
        val playing = teams.filter { it.members.isNotEmpty() }
        if (playing.map { it.turns }.distinct().size != 1) return false
        val top = playing.maxOf { it.score }
        if (top < goal) return false
        val leaders = playing.filter { it.score == top }
        if (leaders.size > 1) { notice = "Нічия на фініші — ще одне коло!"; return false }
        win(leaders[0].idx)
        return true
    }

    private fun win(idx: Int) {
        winner = idx
        deadline = 0
        phase = Phase.FINAL
    }

    /** Host ends the turn now (e.g. the explainer is away). */
    fun endTurn(): Boolean {
        when (phase) {
            Phase.READY -> prepareTurn(sameTeam = false)
            Phase.TURN -> { deadline = 0; phase = Phase.LAST }
            Phase.LAST -> lastWord(-1)
            else -> return false
        }
        return true
    }

    /** Host stops: the leader wins (ties stay ties). */
    fun finishEarly(): Boolean {
        if (phase !in setOf(Phase.READY, Phase.TURN, Phase.LAST, Phase.REVIEW)) return false
        entries.clear()
        val top = teams.maxOf { it.score }
        winner = teams.filter { it.score == top }.singleOrNull()?.idx
        deadline = 0
        phase = Phase.FINAL
        return true
    }

    private fun resetToLobby() {
        phase = Phase.LOBBY
        teams.clear(); decks.clear(); entries.clear()
        players.values.forEach { it.team = null; it.explained = 0 }
        explainer = null; turnTeam = -1; deadline = 0; lastTurn = null; winner = null
    }

    fun newGame() {
        resetToLobby()
        notice = null
    }

    // ---------- state for clients ----------

    private fun markName(m: Mark?) = m?.name ?: JSONObject.NULL

    private fun wordJson(w: String, mark: Any, e: String) =
        JSONObject().put("w", w).put("mark", mark).also { if (e.isNotEmpty()) it.put("e", e) }

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("now", now)
        o.put("seconds", turnSeconds)
        o.put("goal", goal)
        o.put("skipPenalty", skipPenalty)
        o.put("taboo", taboo)
        o.put("lastForAll", lastForAll)
        o.put("battle", battle)
        o.put("kids", kids)
        o.put("themes", JSONArray(themes.toList()))
        o.put("minPlayers", MIN_PLAYERS)
        o.put("maxTeams", maxTeams())
        notice?.let { o.put("notice", it) }
        if (phase == Phase.LOBBY) {
            o.put("themeList", JSONArray(THEMES.map { t ->
                JSONObject().put("id", t.id).put("title", t.title).put("emoji", t.emoji).put("n", banks[t.id].orEmpty().size)
            }))
            o.put("kidsWords", banks[KIDS_BANK].orEmpty().size)
            o.put("battleWords", banks["male"].orEmpty().size.coerceAtMost(banks["female"].orEmpty().size))
        }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("team", p.team ?: JSONObject.NULL).put("explained", p.explained)
        }))
        o.put("teams", JSONArray(teams.map { t ->
            JSONObject().put("idx", t.idx).put("name", t.name).put("emoji", t.emoji)
                .put("members", JSONArray(t.members)).put("score", t.score).put("turns", t.turns)
        }))
        o.put("teamsReady", teamsReady())

        val me = viewer?.let { players[it] }
        if (me != null) o.put("me", JSONObject().put("id", me.id).put("name", me.name).put("team", me.team ?: JSONObject.NULL))

        if (phase in setOf(Phase.READY, Phase.TURN, Phase.LAST, Phase.REVIEW)) {
            val cur = current
            val done = entries.filter { it.mark != null }
            val turn = JSONObject()
                .put("team", turnTeam)
                .put("explainer", explainer ?: JSONObject.NULL)
                .put("deadline", deadline)
                .put("ms", turnSeconds * 1000L)
                .put("got", done.count { it.mark == Mark.GOT })
                .put("skips", done.count { it.mark == Mark.SKIP })
                .put("fouls", done.count { it.mark == Mark.FOUL })
                // finished words were said out loud anyway
                .put("words", JSONArray(done.map { wordJson(it.word.w, markName(it.mark), it.word.e) }))
                .put("delta", turnDelta())
            if (phase == Phase.REVIEW) {
                turn.put("lastBy", lastBy)
                cur?.let { turn.put("last", it.word.w); if (it.word.e.isNotEmpty()) turn.put("lastE", it.word.e) }
            }
            o.put("turn", turn)
            // The word on screen: the explainer, plus the other teams when they watch for taboo words.
            // Nobody in the explaining team may see it, and in the classic mode nobody else either.
            val sees = me != null && (me.id == explainer || canWatch(me.id))
            if (cur != null && sees && (phase == Phase.TURN || phase == Phase.LAST)) {
                o.put("card", JSONObject().put("w", cur.word.w).put("taboo", JSONArray(if (taboo) cur.word.taboo else emptyList()))
                    .also { c -> if (cur.word.e.isNotEmpty()) c.put("e", cur.word.e) })
            }
        }

        lastTurn?.let { l ->
            o.put("lastTurn", JSONObject().put("team", l.team).put("explainer", l.explainer).put("delta", l.delta).put("lastBy", l.lastBy)
                .put("words", JSONArray(l.words.map { (w, m) -> wordJson(w, markName(m), emojiOf(w)) })))
        }
        if (phase == Phase.FINAL) o.put("winner", winner ?: JSONObject.NULL)
        return o
    }

    class Theme(val id: String, val title: String, val emoji: String)

    companion object {
        const val MIN_PLAYERS = 4
        const val MIN_SECONDS = 30
        const val MAX_SECONDS = 120
        const val MIN_GOAL = 10
        const val MAX_GOAL = 200
        const val MAX_TEAMS = 6

        /** Banks the host can mix; `male` and `female` are only for the battle mode. */
        val THEMES = listOf(
            Theme("basic", "Легкі", "🟢"),
            Theme("medium", "Середні", "🟡"),
            Theme("hard", "Складні", "🔴"),
            Theme("food", "Їжа", "🍕"),
            Theme("animals", "Природа", "🦊"),
            Theme("movies", "Кіно й музика", "🎬"),
            Theme("tech", "Техніка й інтернет", "📱"),
            Theme("sport", "Спорт", "⚽"),
            Theme("travel", "Подорожі", "✈️"),
            Theme("ukraine", "Україна", "🇺🇦"),
            Theme("jobs", "Професії", "👷"),
            Theme("kids", "Для дітей", "🧸"),
            Theme("party", "18+", "🔥"),
        )
        /** Picture words for the kids mode; not a theme the host can pick. */
        const val KIDS_BANK = "kidspics"
        val BANKS = THEMES.map { it.id } + listOf("male", "female", KIDS_BANK)

        val BATTLE_TEAMS = listOf("🧔" to "Хлопці", "👩" to "Дівчата")
        val TEAM_NAMES = listOf(
            "🦊" to "Лисиці", "🐻" to "Ведмеді", "🦉" to "Сови", "🐺" to "Вовки",
            "🐸" to "Жаби", "🦄" to "Єдинороги", "🐙" to "Восьминоги", "🦔" to "Їжаки",
            "🐝" to "Бджоли", "🐧" to "Пінгвіни", "🦘" to "Кенгуру", "🐢" to "Черепахи",
        )
    }
}
