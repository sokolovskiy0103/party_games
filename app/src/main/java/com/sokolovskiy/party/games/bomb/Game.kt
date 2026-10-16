package com.sokolovskiy.party.games.bomb

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Сапери», a co-op wire-cutting deduction game in the spirit of Bomb Busters.
 * Not thread-safe: the server guards it with a lock. Time is passed in explicitly so tests can drive it.
 *
 * All wires of the bomb are dealt out; every player holds theirs sorted ascending and hidden from the others.
 * Blue wires are numbered 1–12 (four of each, fewer numbers on the training mission). Yellow wires sit between
 * blues (3.1 lies between 3 and 4) and all count as one value, «жовтий». Red wires (3.5) are the bomb: cutting one
 * is an instant loss. Cut wires stay in place face up, so positions are part of the puzzle.
 *
 * Before play each player shows one of their blue wires to everyone. Then on your turn you do one thing:
 *  - dual cut: point at a teammate's wire and name a value you hold yourself. Right — both wires are cut.
 *    Wrong — the detonator ticks and the wire gets an info token with its real value. A red wire explodes.
 *  - solo cut: if every uncut wire of a value is in your hand (the public cut counts tell you), cut them all.
 *  - reveal reds: if only red wires are left in your hand, show them.
 * Before that, on your turn, you may use free tools: your character's once-per-mission ability and any unlocked
 * equipment (a card tied to a number unlocks once two wires of that number are cut; each works once).
 * Everyone wins when every non-red wire is cut; the bomb goes off on a red wire or on one mistake too many.
 *
 * Flow: LOBBY -> SETUP (everyone shows a wire) -> PLAY -> WIN | LOSE -> (next mission / retry) SETUP …
 */
class Game(private val random: Random = Random.Default) {
    enum class Phase { LOBBY, SETUP, PLAY, WIN, LOSE }
    enum class Kind(val code: String) { BLUE("b"), YELLOW("y"), RED("r") }

    /** What a tool needs from the client: a teammate's wire, two of them, a teammate, a value, or nothing. */
    enum class Needs { NONE, WIRE, TWO_WIRES, PLAYER_VALUE, WIRE_VALUE, VALUE }

    enum class CharType(val title: String, val emoji: String, val desc: String, val needs: Needs) {
        DETECTOR("Детектор", "📟", "Твій зріз: вкажи на два дроти одного напарника й назви номер — вдалий, якщо збігся хоч один. Червоний при цьому не вибухає", Needs.TWO_WIRES),
        SCANNER("Сканер", "📶", "Спитай, чи є в напарника певний номер — відповідь бачать усі", Needs.PLAYER_VALUE),
        SHIELD("Щит", "🛡️", "Промах цього ходу не рухає детонатор (червоний усе одно вибухає)", Needs.NONE),
        MECHANIC("Механік", "🔧", "Поверни детонатор на одну позначку назад", Needs.NONE),
        ANALYST("Аналітик", "📐", "Вкажи дріт напарника й номер — дізнаєтесь, менший він, такий самий чи більший", Needs.WIRE_VALUE),
    }

    enum class EquipType(val title: String, val emoji: String, val desc: String, val needs: Needs) {
        XRAY("Рентген", "🩻", "Покажи всім, що за дріт у напарника (кладе жетон із номером)", Needs.WIRE),
        SONAR("Ехолот", "📡", "Назви номер — побачите, скільки таких неперерізаних дротів у кожного", Needs.VALUE),
        PROBE("Щуп", "🔎", "Перевір, чи червоний обраний дріт напарника", Needs.WIRE),
        FUSE("Запобіжник", "🧯", "Поверни детонатор на одну позначку назад", Needs.NONE),
        EXTRA("Друга спроба", "🔁", "Після наступної дії ходиш ще раз", Needs.NONE),
    }

    /**
     * [maxBlue]: blue values 1..maxBlue (four of each). [yellows] of [yellowShown] candidate yellow values are in
     * the bomb (the candidates are public, which of them are real is not). [reds]: hidden red wires.
     * [equip]: equipment cards on the board. [fuseDelta]: change to the allowed mistakes. [noInfo]: misses leave no
     * info token. [noChars]: no character abilities. [turnSeconds]: a clock per turn (0 — none).
     */
    class Mission(
        val name: String, val desc: String,
        val maxBlue: Int = 12, val yellows: Int = 0, val yellowShown: Int = yellows, val reds: Int = 0,
        val equip: Int = 0, val fuseDelta: Int = 0, val noInfo: Boolean = false, val noChars: Boolean = false,
        val turnSeconds: Int = 0,
    ) {
        /** Special rules, as shown to players. */
        fun rules(): List<String> = buildList {
            if (yellowShown > yellows) add("🟡 Жовтих $yellows з $yellowShown можливих")
            if (fuseDelta < 0) add("💣 Детонатор чутливіший: на ${-fuseDelta} помилку менше")
            if (noInfo) add("🤫 Промахи не залишають жетонів")
            if (noChars) add("🚫 Без особистих здібностей")
            if (turnSeconds > 0) add("⏱ $turnSeconds с на хід: не встиг — детонатор цокає")
        }
    }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
    }

    /** [key] is the value ×10: blue 7 = 70, yellow 7.1 = 71, red 7.5 = 75 — so sorting by key sorts the hand. */
    class Wire(val key: Int, val kind: Kind) {
        var cut = false
        /** An info token in front of it shows its value (yellow: just «жовтий»). */
        var info = false
        val value get() = key / 10
        val label get() = if (kind == Kind.BLUE) "$value" else "$value.${key % 10}"
        fun matches(guess: Int) = if (guess == YELLOW) kind == Kind.YELLOW else kind == Kind.BLUE && value == guess
    }

    /** An equipment card tied to blue number [number]; unlocked once two wires of it are cut. */
    class Equip(val type: EquipType, val number: Int) {
        var used = false
    }

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var level = 1; private set
    /** Highest mission cleared in this session. */
    var best = 0; private set
    /** Bumped on every deal so clients can reset per-game UI. */
    var round = 0; private set
    var mistakes = 0; private set
    /** Mistakes the bomb survives; the next one explodes it. */
    var maxMistakes = 0; private set
    var turn: String? = null; private set
    /** End of the current turn's clock (0 — not started yet or no clock on this mission). */
    var deadline = 0L; private set
    /** Each dealt player's wires, sorted. Players who joined mid-game have no entry. */
    val hands = LinkedHashMap<String, MutableList<Wire>>()
    /** Players who already showed their starting wire. */
    val ready = HashSet<String>()
    val chars = HashMap<String, CharType>()
    val charUsed = HashSet<String>()
    val equipment = ArrayList<Equip>()
    /** This turn's miss doesn't tick the detonator. */
    var shield = false; private set
    /** The current player acts once more after their next action. */
    var extraTurn = false; private set
    /** Public candidate yellow values (keys), and how many reds are in the bomb. */
    var yellowShown: List<Int> = emptyList(); private set
    var yellowsInPlay = 0; private set
    var redsInPlay = 0; private set
    var notice: String? = null; private set
    val log = ArrayList<String>()
    /** Last action, for client effects: bumped [seq] + kind ("hit", "miss", "solo", "reds", "tool", "boom", "win"). */
    var seq = 0; private set
    var lastKind = ""; private set
    var lastTarget: String? = null; private set
    /** Wires touched by the last action in [lastTarget]'s hand. */
    var lastIndex: List<Int> = emptyList(); private set

    private var nextOrder = 0

    val current get() = MISSIONS[level - 1]

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    /** Their wires leave with them, so the bomb can't be finished: the game in progress is called off. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        ready.remove(pid)
        if (hands.remove(pid) == null) return
        if (phase == Phase.SETUP || phase == Phase.PLAY) cancel("${p.name} вийшов(ла) — гру скасовано.")
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    fun setLevel(n: Int): Boolean {
        if (phase == Phase.SETUP || phase == Phase.PLAY || n !in 1..MISSIONS.size) return false
        level = n
        return true
    }

    /** LOBBY/WIN/LOSE -> SETUP on the selected mission. */
    fun start(): Boolean {
        if (phase == Phase.SETUP || phase == Phase.PLAY || !canStart()) return false
        deal()
        return true
    }

    /** WIN -> SETUP on the next mission (or the same one again after the last). */
    fun nextLevel(): Boolean {
        if (phase != Phase.WIN || !canStart()) return false
        if (level < MISSIONS.size) level++
        deal()
        return true
    }

    private fun deal() {
        val m = current
        val ids = players.values.sortedBy { it.joinOrder }.map { it.id }
        val wires = ArrayList<Wire>()
        for (v in 1..m.maxBlue) repeat(4) { wires += Wire(v * 10, Kind.BLUE) }
        // Yellow and red wires sit between two blues, so their numbers run up to maxBlue - 1.
        val between = (1 until m.maxBlue).toList()
        val shown = between.shuffled(random).take(m.yellowShown).map { it * 10 + 1 }
        shown.shuffled(random).take(m.yellows).forEach { wires += Wire(it, Kind.YELLOW) }
        between.shuffled(random).take(m.reds).forEach { wires += Wire(it * 10 + 5, Kind.RED) }
        yellowShown = shown.sorted()
        yellowsInPlay = m.yellows
        redsInPlay = m.reds

        wires.shuffle(random)
        hands.clear()
        ids.forEach { hands[it] = ArrayList() }
        wires.forEachIndexed { i, w -> hands[ids[i % ids.size]]!! += w }
        hands.values.forEach { h -> h.sortBy { it.key } }

        chars.clear(); charUsed.clear()
        if (!m.noChars) {
            val pool = CharType.entries.shuffled(random)
            ids.forEachIndexed { i, id -> chars[id] = pool[i % pool.size] }
        }
        equipment.clear()
        val types = EquipType.entries.filter { m.reds > 0 || it != EquipType.PROBE }
        (1..m.maxBlue).shuffled(random).take(m.equip).sorted().forEach { equipment += Equip(types.random(random), it) }

        ready.clear()
        // A player can't show a blue wire if they hold none (possible only with tiny hands): skip them.
        hands.filterValues { h -> h.none { it.kind == Kind.BLUE } }.keys.forEach { ready += it }
        mistakes = 0
        maxMistakes = maxOf(1, maxOf(2, ids.size - 1) + m.fuseDelta)
        turn = null; deadline = 0; shield = false; extraTurn = false
        log.clear()
        notice = null
        lastKind = ""; lastTarget = null; lastIndex = emptyList()
        round++
        phase = Phase.SETUP
        if (ready.size == hands.size) beginPlay()
    }

    /** SETUP: the player shows one of their blue wires to everyone. */
    fun showWire(pid: String, index: Int): Boolean {
        if (phase != Phase.SETUP || pid in ready) return false
        val w = hands[pid]?.getOrNull(index)?.takeIf { it.kind == Kind.BLUE } ?: return false
        w.info = true
        ready += pid
        if (ready.containsAll(hands.keys)) beginPlay()
        return true
    }

    private fun beginPlay() {
        phase = Phase.PLAY
        turn = hands.keys.toList().random(random)
        deadline = 0
        log += "🎲 Першим ходить ${name(turn)}"
    }

    /** Starts the turn clock lazily and handles running out of time. */
    fun tick(now: Long): Boolean {
        val secs = current.turnSeconds
        if (phase != Phase.PLAY || secs <= 0) return false
        if (deadline == 0L) { deadline = now + secs * 1000L; return true }
        if (now < deadline) return false
        val who = turn
        mistakes++
        extraTurn = false
        lastTarget = null; lastIndex = emptyList()
        log += "⏰ ${name(who)} не встиг(ла) — детонатор цокає"
        if (mistakes > maxMistakes) { log += "💥 Детонатор спрацював"; lose() } else { event("miss"); passTurn() }
        return true
    }

    // ---------- actions ----------

    private fun myTurn(pid: String) = phase == Phase.PLAY && turn == pid && hands.containsKey(pid)

    fun holds(pid: String, guess: Int) = hands[pid].orEmpty().any { !it.cut && it.matches(guess) }

    /** Can [pid] solo-cut [guess]: they hold every uncut wire of it. */
    fun canSolo(pid: String, guess: Int) =
        holds(pid, guess) && hands.all { (id, h) -> id == pid || h.none { !it.cut && it.matches(guess) } }

    private fun teammateWire(pid: String, target: String, index: Int): Wire? =
        if (target == pid) null else hands[target]?.getOrNull(index)?.takeIf { !it.cut }

    /** Dual cut: [pid] says wire [index] of [target] is [guess] (1..12, or [YELLOW]). */
    fun dual(pid: String, target: String, index: Int, guess: Int): Boolean {
        if (!myTurn(pid) || !holds(pid, guess)) return false
        val w = teammateWire(pid, target, index) ?: return false
        val what = guessName(guess)
        lastTarget = target; lastIndex = listOf(index)
        when {
            w.kind == Kind.RED -> {
                w.cut = true
                log += "💥 ${name(pid)} → ${name(target)}: «$what» — це був червоний дріт!"
                lose()
            }
            w.matches(guess) -> {
                w.cut = true
                hands[pid]!!.first { !it.cut && it.matches(guess) }.cut = true
                log += "✂️ ${name(pid)} → ${name(target)}: «$what» — влучно"
                afterHit("hit")
            }
            else -> miss(pid, target, w, what)
        }
        return true
    }

    private fun miss(pid: String, target: String, w: Wire, what: String) {
        val truth = if (current.noInfo) "" else ", там ${if (w.kind == Kind.YELLOW) "жовтий" else w.label}"
        if (!current.noInfo) w.info = true
        if (shield) {
            log += "🛡️ ${name(pid)} → ${name(target)}: «$what» — ні$truth. Щит утримав детонатор"
        } else {
            mistakes++
            log += "❌ ${name(pid)} → ${name(target)}: «$what» — ні$truth"
        }
        if (mistakes > maxMistakes) { log += "💥 Детонатор спрацював"; lose() } else { event("miss"); passTurn() }
    }

    private fun afterHit(kind: String) {
        event(kind)
        if (!checkWin()) passTurn()
    }

    fun solo(pid: String, guess: Int): Boolean {
        if (!myTurn(pid) || !canSolo(pid, guess)) return false
        val hand = hands[pid]!!
        val cut = hand.filter { !it.cut && it.matches(guess) }
        cut.forEach { it.cut = true }
        lastTarget = pid; lastIndex = cut.map { hand.indexOf(it) }
        log += "✂️ ${name(pid)} сам(а) перерізає «${guessName(guess)}» ×${cut.size}"
        afterHit("solo")
        return true
    }

    /** Allowed only when every uncut wire in the hand is red. */
    fun revealReds(pid: String): Boolean {
        if (!myTurn(pid)) return false
        val hand = hands[pid]!!
        val left = hand.filter { !it.cut }
        if (left.isEmpty() || left.any { it.kind != Kind.RED || it.info }) return false
        left.forEach { it.info = true }
        lastTarget = pid; lastIndex = left.map { hand.indexOf(it) }
        log += "🔴 ${name(pid)} показує червоні дроти (${left.size})"
        event("reds")
        passTurn()
        return true
    }

    // ---------- tools ----------

    fun canUseChar(pid: String) = myTurn(pid) && chars[pid] != null && pid !in charUsed

    /** The player's character ability. [to]/[i]/[j]/[v] as its [CharType.needs] asks. */
    fun useChar(pid: String, to: String?, i: Int, j: Int, v: Int): Boolean {
        if (!canUseChar(pid)) return false
        val who = name(pid)
        when (chars[pid]!!) {
            CharType.DETECTOR -> {
                if (to == null || i == j || !holds(pid, v)) return false
                val a = teammateWire(pid, to, i) ?: return false
                val b = teammateWire(pid, to, j) ?: return false
                charUsed += pid
                val what = guessName(v)
                val hit = listOf(i to a, j to b).firstOrNull { it.second.matches(v) }
                lastTarget = to; lastIndex = listOf(i, j)
                if (hit != null) {
                    hit.second.cut = true
                    hands[pid]!!.first { !it.cut && it.matches(v) }.cut = true
                    lastIndex = listOf(hit.first)
                    log += "📟 $who → ${name(to)}: №${i + 1} чи №${j + 1} — «$what»? Влучно, №${hit.first + 1}"
                    afterHit("hit")
                } else {
                    log += "📟 $who перевіряє №${i + 1} і №${j + 1} у ${name(to)} детектором"
                    miss(pid, to, a, what)
                }
                return true
            }
            CharType.SCANNER -> {
                if (to == null || to == pid || !hands.containsKey(to) || !validValue(v)) return false
                charUsed += pid
                val yes = holds(to, v)
                log += "📶 $who сканує ${name(to)}: «${guessName(v)}» — ${if (yes) "є" else "немає"}"
            }
            CharType.SHIELD -> { charUsed += pid; shield = true; log += "🛡️ $who вмикає щит на цей хід" }
            CharType.MECHANIC -> {
                if (mistakes == 0) return false
                charUsed += pid; mistakes--
                log += "🔧 $who відкручує детонатор на позначку назад"
            }
            CharType.ANALYST -> {
                if (to == null || v !in 1..current.maxBlue) return false
                val w = teammateWire(pid, to, i) ?: return false
                charUsed += pid
                lastTarget = to; lastIndex = listOf(i)
                log += "📐 $who: №${i + 1} у ${name(to)} ${compare(w, v)} за $v"
            }
        }
        event("tool")
        return true
    }

    fun equipUnlocked(e: Equip) = cutCount(e.number) >= 2

    /** Uses unlocked, unused equipment card [idx]. */
    fun useEquip(pid: String, idx: Int, to: String?, i: Int, v: Int): Boolean {
        if (!myTurn(pid)) return false
        val e = equipment.getOrNull(idx)?.takeIf { !it.used && equipUnlocked(it) } ?: return false
        val who = name(pid)
        when (e.type) {
            EquipType.XRAY -> {
                val w = to?.let { teammateWire(pid, it, i) } ?: return false
                w.info = true
                lastTarget = to; lastIndex = listOf(i)
                log += "🩻 $who просвічує №${i + 1} у ${name(to)}: ${if (w.kind == Kind.YELLOW) "жовтий" else w.label}"
            }
            EquipType.SONAR -> {
                if (!validValue(v)) return false
                val counts = hands.keys.filter { it != pid }.joinToString(", ") { id ->
                    "${name(id)} ${hands[id]!!.count { !it.cut && it.matches(v) }}"
                }
                log += "📡 $who, ехолот «${guessName(v)}»: $counts"
            }
            EquipType.PROBE -> {
                val w = to?.let { teammateWire(pid, it, i) } ?: return false
                if (w.kind == Kind.RED) w.info = true
                lastTarget = to; lastIndex = listOf(i)
                log += "🔎 $who: №${i + 1} у ${name(to)} — ${if (w.kind == Kind.RED) "ЧЕРВОНИЙ" else "не червоний"}"
            }
            EquipType.FUSE -> {
                if (mistakes == 0) return false
                mistakes--
                log += "🧯 $who: детонатор на позначку назад"
            }
            EquipType.EXTRA -> { extraTurn = true; log += "🔁 $who отримує ще один хід" }
        }
        e.used = true
        event("tool")
        return true
    }

    private fun validValue(v: Int) = v in 1..current.maxBlue || (v == YELLOW && yellowsInPlay > 0)

    private fun compare(w: Wire, v: Int) = when {
        w.key < v * 10 -> "менший"
        w.key == v * 10 -> "такий самий"
        else -> "більший"
    }

    fun cutCount(v: Int) = hands.values.sumOf { h -> h.count { it.cut && it.kind == Kind.BLUE && it.value == v } }

    /** True if [pid] still has to act at some point: anything uncut that isn't an already shown red. */
    private fun active(pid: String) = hands[pid].orEmpty().any { !it.cut && !(it.kind == Kind.RED && it.info) }

    private fun passTurn() {
        shield = false
        deadline = 0
        if (extraTurn && turn?.let { active(it) } == true) { extraTurn = false; return }
        extraTurn = false
        val order = hands.keys.toList()
        val from = order.indexOf(turn)
        turn = (1..order.size).map { order[(from + it) % order.size] }.firstOrNull { active(it) }
    }

    private fun checkWin(): Boolean {
        if (hands.values.flatten().any { !it.cut && it.kind != Kind.RED }) return false
        best = maxOf(best, level)
        phase = Phase.WIN
        turn = null
        log += "🎉 Бомбу знешкоджено!"
        event("win")
        return true
    }

    private fun lose() {
        phase = Phase.LOSE
        turn = null
        event("boom")
    }

    private fun event(kind: String) { lastKind = kind; seq++ }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        hands.clear(); ready.clear(); log.clear(); chars.clear(); charUsed.clear(); equipment.clear()
        turn = null
        notice = msg
    }

    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    fun newGame() {
        cancel("")
        notice = null
        level = 1; best = 0
    }

    private fun name(pid: String?) = players[pid]?.name ?: "?"

    private fun guessName(g: Int) = if (g == YELLOW) "жовтий" else "$g"

    // ---------- state for clients ----------

    private fun wireJson(w: Wire, full: Boolean): JSONObject {
        val o = JSONObject()
        if (w.cut) o.put("c", true)
        if (w.info) o.put("i", true)
        when {
            full || w.cut || (w.info && w.kind != Kind.YELLOW) -> o.put("k", w.kind.code).put("v", w.label)
            w.info -> o.put("k", w.kind.code) // a yellow info token says «yellow», not which one
        }
        return o
    }

    private fun charJson(c: CharType) = JSONObject().put("id", c.name).put("name", c.title).put("emoji", c.emoji)
        .put("desc", c.desc).put("needs", c.needs.name)

    private fun missionJson(n: Int, m: Mission) = JSONObject().put("n", n).put("name", m.name).put("desc", m.desc)
        .put("rules", JSONArray(m.rules()))

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val m = current
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("level", level)
        o.put("best", best)
        o.put("levels", JSONArray(MISSIONS.mapIndexed { i, l -> missionJson(i + 1, l) }))
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }

        val dealt = phase != Phase.LOBBY
        if (dealt) {
            o.put("mistakes", mistakes).put("maxMistakes", maxMistakes)
            turn?.let { o.put("turn", it) }
            if (deadline > 0 && phase == Phase.PLAY) o.put("deadline", deadline).put("turnSeconds", m.turnSeconds)
            o.put("shield", shield).put("extra", extraTurn)
            o.put("maxBlue", m.maxBlue)
            o.put("yellowShown", JSONArray(yellowShown.map { "${it / 10}.1" }))
            o.put("yellows", yellowsInPlay).put("reds", redsInPlay)
            val all = hands.values.flatten()
            o.put("cutBlue", JSONArray((1..m.maxBlue).map { cutCount(it) }))
            o.put("cutYellow", all.count { it.cut && it.kind == Kind.YELLOW })
            o.put("equipment", JSONArray(equipment.map { e ->
                JSONObject().put("n", e.number).put("id", e.type.name).put("name", e.type.title).put("emoji", e.type.emoji)
                    .put("desc", e.type.desc).put("needs", e.type.needs.name)
                    .put("unlocked", equipUnlocked(e)).put("used", e.used)
            }))
            o.put("log", JSONArray(log.takeLast(LOG_SIZE).reversed()))
            o.put("seq", seq).put("last", lastKind)
            lastTarget?.let { o.put("lastTarget", it).put("lastIndex", JSONArray(lastIndex)) }
        }

        val reveal = phase == Phase.WIN || phase == Phase.LOSE
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            val hand = hands[p.id]
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("inGame", hand != null && dealt)
                .put("ready", p.id in ready)
                .put("left", hand.orEmpty().count { !it.cut })
                .put("wires", JSONArray(hand.orEmpty().map { wireJson(it, reveal) }))
                .apply { chars[p.id]?.let { put("char", charJson(it).put("used", p.id in charUsed)) } }
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val hand = hands[me.id]
            val mo = JSONObject().put("id", me.id).put("name", me.name)
                .put("inGame", hand != null && dealt)
                .put("ready", me.id in ready)
                .put("wires", JSONArray(hand.orEmpty().map { wireJson(it, true) }))
            if (phase == Phase.PLAY && hand != null) {
                // What the player may name: every value they still hold (derived from their own hand only).
                val values = hand.filter { !it.cut && it.kind != Kind.RED }
                    .map { if (it.kind == Kind.YELLOW) YELLOW else it.value }.distinct()
                mo.put("guesses", JSONArray(values))
                mo.put("solo", JSONArray(values.filter { canSolo(me.id, it) }))
                mo.put("canReveal", hand.any { !it.cut } && hand.filter { !it.cut }.all { it.kind == Kind.RED && !it.info })
            }
            o.put("me", mo)
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 2
        /** Guess code for «yellow». */
        const val YELLOW = 0
        const val LOG_SIZE = 16

        val MISSIONS = listOf(
            Mission("Тренування", "Лише сині дроти 1–6, без інструментів", maxBlue = 6, noChars = true),
            Mission("Повний набір", "Сині 1–12, перше спорядження", equip = 2),
            Mission("Жовта тривога", "Сині й 2 жовті", yellows = 2, equip = 3),
            Mission("Червоний дріт", "Сині й 1 червоний", reds = 1, equip = 3),
            Mission("Мінне поле", "2 жовті й 1 червоний", yellows = 2, reds = 1, equip = 4),
            Mission("Тиша в ефірі", "1 червоний, промахи нічого не підказують", reds = 1, equip = 4, noInfo = true),
            Mission("Туман", "Жовті під питанням і 2 червоні", yellows = 2, yellowShown = 3, reds = 2, equip = 4),
            Mission("Цейтнот", "Хвилина на хід", yellows = 2, reds = 1, equip = 4, turnSeconds = 60),
            Mission("Сам на сам", "2 жовті, 2 червоні, без здібностей", yellows = 2, reds = 2, equip = 5, noChars = true),
            Mission("Тонкий лід", "Чутливий детонатор", yellows = 3, yellowShown = 4, reds = 2, equip = 5, fuseDelta = -1),
            Mission("Червоний туман", "3 червоні, 90 секунд на хід", yellows = 2, reds = 3, equip = 5, turnSeconds = 90),
            Mission("Фінал", "Усе разом", yellows = 4, yellowShown = 5, reds = 3, equip = 6, fuseDelta = -1, turnSeconds = 60),
        )
    }
}
