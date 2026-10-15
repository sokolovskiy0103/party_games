package com.sokolovskiy.party.games.clocktower

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Кривава вежа» (after Blood on the Clocktower, «Trouble Brewing»). Not thread-safe: the server
 * guards it with a lock. The phone is the Storyteller: it deals roles, runs the nights, hands out (possibly false)
 * information and counts votes. Talking, bluffing and accusing happen at the table.
 *
 * Seating matters (Chef, Empath): players sit in a circle in [seating] order.
 *
 * Night:
 *  CHOOSE – every living player gets a task on their phone. Night roles pick targets for real; everyone else picks
 *           any player for nothing, so nobody can tell who has a power by watching people tap.
 *           Choices resolve in the night order: Poisoner, Butler, Monk, Imp.
 *  INFO   – information roles read what they learned (false if they are drunk or poisoned); a Ravenkeeper killed
 *           tonight picks a player first. Everyone taps «прочитав».
 * Day: deaths are announced; players nominate (once each per day, each player nominated once per day) and vote.
 * A nomination passes with votes from at least half the living, and the player with the most votes (no tie) is
 * executed when the host ends the day. Dead players keep one ghost vote for the rest of the game.
 *
 * Where the real Storyteller would decide (Recluse/Spy registration, Mayor redirect, which Minion inherits the Imp),
 * the game rolls dice with the probabilities below.
 *
 * Good wins when the Demon dies; evil wins when only two players are alive or the Saint is executed.
 */
class Game(private val random: Random = Random.Default) {
    enum class Phase { LOBBY, NIGHT, DAY, OVER }
    enum class Step { CHOOSE, INFO }
    enum class Side { GOOD, EVIL }
    enum class TaskKind { POISON, MONK, KILL, FORTUNE, BUTLER, RAVEN, DUMMY }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
    }

    class Seat(val id: String, var role: Role, var shown: Role) {
        var evil = !role.kind.good
        var alive = true
        var ghostVote = true
        var left = false
        /** Has publicly fired a Slayer shot (anyone may claim to be the Slayer, once). */
        var shot = false
        var virginSpent = false
        var master: String? = null
        val notes = ArrayList<Note>()
    }

    /** Private information for one player. [night] 0 = learned during the day. */
    class Note(val night: Int, val text: String)

    class Task(val kind: TaskKind, val picks: Int, val notSelf: Boolean, val aliveOnly: Boolean, val prompt: String)

    class Vote(val nominator: String, val nominee: String) {
        val votes = LinkedHashMap<String, Boolean>()
    }

    class Nomination(val nominator: String, val nominee: String, val yes: Int, val voters: List<String>, val threshold: Int)

    // Storyteller dice (tests pin them to 0 or 1).
    var misregister = 0.35
    var spyAsGood = 0.5
    var mayorBounce = 0.5

    val players = LinkedHashMap<String, Player>()
    /** Circle order in the lobby; frozen into [seats] when the game starts. */
    val seating = ArrayList<String>()
    val seats = LinkedHashMap<String, Seat>()
    var phase = Phase.LOBBY; private set
    var step = Step.CHOOSE; private set
    var night = 0; private set
    var winner: Side? = null; private set
    var winReason = ""; private set
    var redHerring: String? = null; private set
    var bluffs: List<Role> = emptyList(); private set
    var notice: String? = null; private set

    // night
    var poisoned: String? = null; private set
    var protectedId: String? = null; private set
    val tasks = LinkedHashMap<String, Task>()
    val choices = HashMap<String, List<String>>()
    val ready = HashSet<String>()
    val killedTonight = ArrayList<String>()

    // day
    val dawnDeaths = ArrayList<String>()
    var executedToday: String? = null; private set
    private var executedYesterday: String? = null
    val nominations = ArrayList<Nomination>()
    val nominators = HashSet<String>()
    val nominees = HashSet<String>()
    var vote: Vote? = null; private set
    var block: String? = null; private set
    var blockVotes = 0; private set

    /** Public announcements. */
    val events = ArrayList<String>()
    /** What really happened, revealed at the end. */
    val stLog = ArrayList<String>()

    private var nextOrder = 0

    // ---------- players & seating ----------

    fun add(id: String, name: String): Player {
        val p = players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }
        if (id !in seating && (phase == Phase.LOBBY || phase == Phase.OVER)) seating += id
        return p
    }

    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        seating -= pid
        val s = seats[pid] ?: return
        if (phase == Phase.OVER) return
        s.left = true
        if (s.role == Role.IMP && s.alive) {
            cancel("${p.name} (Демон) вийшов(ла) з гри — гру зупинено.")
            return
        }
        if (s.alive) {
            s.alive = false
            events += "${p.name} покидає місто."
        }
        tasks.remove(pid); ready -= pid
        vote?.let { v -> if (v.nominee == pid || v.nominator == pid) vote = null else v.votes.remove(pid) }
        if (block == pid) { block = null }
        checkWin()
        if (phase == Phase.NIGHT) advanceNight()
    }

    /** Lobby: the host moves a player one seat left (-1) or right (+1) around the circle. */
    fun moveSeat(pid: String, delta: Int): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return false
        val i = seating.indexOf(pid)
        val j = i + delta
        if (i < 0 || j !in seating.indices) return false
        seating[i] = seating[j].also { seating[j] = seating[i] }
        return true
    }

    fun shuffleSeats(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return false
        seating.shuffle(random)
        return true
    }

    fun name(id: String?) = id?.let { players[it]?.name } ?: "?"

    fun canStart() = Composition.forPlayers(seating.count { it in players }) != null

    // ---------- setup ----------

    fun startGame(): Boolean {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return false
        // anyone who joined while the last game was on takes a seat now
        players.keys.filter { it !in seating }.forEach { seating += it }
        seating.retainAll(players.keys)
        val comp = Composition.forPlayers(seating.size) ?: return false
        var nTown = comp.townsfolk
        var nOut = comp.outsiders
        val minions = Role.of(Kind.MINION).shuffled(random).take(comp.minions)
        if (Role.BARON in minions) { nTown -= 2; nOut += 2 }
        val outsiders = Role.of(Kind.OUTSIDER).shuffled(random).take(nOut)
        val town = Role.of(Kind.TOWNSFOLK).shuffled(random).take(nTown)
        begin((town + outsiders + minions + Role.IMP).shuffled(random))
        return true
    }

    /** Deals [roles] around the circle in seating order (tests call this directly to pin the setup). */
    internal fun begin(roles: List<Role>, drunkAs: Role? = null, herring: String? = null) {
        val inPlay = roles.toSet()
        val fake = drunkAs ?: Role.of(Kind.TOWNSFOLK).filter { it !in inPlay }.random(random)
        seats.clear()
        roles.zip(seating).forEach { (role, id) -> seats[id] = Seat(id, role, if (role == Role.DRUNK) fake else role) }
        redHerring = herring ?: seats.values.filter { !it.evil }.random(random).id
        bluffs = (Role.of(Kind.TOWNSFOLK) + Role.of(Kind.OUTSIDER)).filter { it !in inPlay && it != fake }.shuffled(random).take(3)

        night = 0
        winner = null; winReason = ""; notice = null
        events.clear(); stLog.clear()
        executedToday = null; executedYesterday = null
        stLog += "Ролі: " + order().joinToString(", ") { s ->
            "${name(s.id)} — ${s.role.title}" + if (s.role == Role.DRUNK) " (думає, що ${s.shown.title})" else ""
        }
        stLog += "Хибний Демон для Ворожки: ${name(redHerring)}. Блеф для Демона: ${bluffs.joinToString { it.title }}."
        startNight()
    }

    // ---------- helpers ----------

    fun order(): List<Seat> = seating.mapNotNull { seats[it] }
    private fun living() = order().filter { it.alive }
    fun aliveCount() = seats.values.count { it.alive }
    private fun seatWith(role: Role) = seats.values.firstOrNull { it.role == role }
    private fun roll(p: Double) = random.nextDouble() < p

    /** Drunk or poisoned: the ability does nothing and information may be false. */
    fun malfunction(s: Seat) = s.role == Role.DRUNK || poisoned == s.id

    private fun registersEvil(s: Seat) = when (s.role) {
        Role.RECLUSE -> roll(misregister)
        Role.SPY -> !roll(spyAsGood)
        else -> s.evil
    }

    private fun registersDemon(s: Seat) = s.role == Role.IMP || (s.role == Role.RECLUSE && roll(misregister))

    private fun inPlay() = seats.values.map { it.role }.toSet()

    /** The character a player shows to the Undertaker or the Ravenkeeper. */
    private fun registeredRole(s: Seat): Role = when {
        s.role == Role.RECLUSE && roll(misregister) -> (Role.of(Kind.MINION) + Role.IMP).random(random)
        s.role == Role.SPY && roll(spyAsGood) -> (Role.of(Kind.TOWNSFOLK) + Role.of(Kind.OUTSIDER)).filter { it !in inPlay() }.randomOrNull(random) ?: Role.SPY
        else -> s.role
    }

    /** The nearest living players on each side of [s] (fewer if almost everyone is dead). */
    private fun livingNeighbours(s: Seat): List<Seat> {
        val o = order()
        val i = o.indexOf(s)
        val n = o.size
        val left = (1 until n).map { o[(i - it + n) % n] }.firstOrNull { it.alive && it !== s }
        val right = (1 until n).map { o[(i + it) % n] }.firstOrNull { it.alive && it !== s }
        return listOfNotNull(left, right).distinct()
    }

    private fun note(s: Seat, text: String, n: Int = night) { s.notes += Note(n, text) }

    private fun falseNum(truth: Int, max: Int) = (0..max).filter { it != truth }.randomOrNull(random) ?: truth

    // ---------- night ----------

    private fun startNight() {
        night++
        phase = Phase.NIGHT
        step = Step.CHOOSE
        executedYesterday = executedToday
        poisoned = null; protectedId = null
        vote = null
        tasks.clear(); choices.clear(); ready.clear(); killedTonight.clear()
        for (s in living()) tasks[s.id] = taskFor(s)
        stLog += "— Ніч $night —"
    }

    private fun dummy() = Task(TaskKind.DUMMY, 1, false, false,
        "Ти спиш. Торкнись будь-кого — це нічого не змінить, але так ніхто не здогадається, у кого є нічна дія.")

    /** What a living player does tonight. The Drunk acts out the role they think they have. */
    private fun taskFor(s: Seat): Task {
        val first = night == 1
        return when (s.shown) {
            Role.POISONER -> Task(TaskKind.POISON, 1, false, true, "Обери, кого отруїти: до кінця завтрашнього дня його здібність не працюватиме.")
            Role.IMP -> if (first) dummy() else Task(TaskKind.KILL, 1, false, true, "Обери, кого вбити цієї ночі. Якщо обереш себе — помреш, а Бісом стане твій Поплічник.")
            Role.MONK -> if (first) dummy() else Task(TaskKind.MONK, 1, true, true, "Обери, кого захистити від Демона цієї ночі.")
            Role.FORTUNE_TELLER -> Task(TaskKind.FORTUNE, 2, false, false, "Обери двох гравців — дізнаєшся, чи є серед них Демон.")
            Role.BUTLER -> Task(TaskKind.BUTLER, 1, true, false, "Обери господаря: завтра твій голос «за» рахується, лише коли він теж голосує «за».")
            else -> dummy()
        }
    }

    fun choose(pid: String, targets: List<String>): Boolean {
        if (phase != Phase.NIGHT) return false
        val t = tasks[pid] ?: return false
        if (pid in choices) return false
        if (step == Step.CHOOSE && t.kind == TaskKind.RAVEN) return false
        if (step == Step.INFO && t.kind != TaskKind.RAVEN) return false
        val ts = targets.distinct()
        if (ts.size != t.picks || ts.any { it !in seats }) return false
        if (t.notSelf && pid in ts) return false
        if (t.aliveOnly && ts.any { seats[it]?.alive != true }) return false
        choices[pid] = ts
        if (t.kind == TaskKind.RAVEN) ravenInfo(seats.getValue(pid), seats.getValue(ts[0]))
        advanceNight()
        return true
    }

    fun markRead(pid: String): Boolean {
        if (phase != Phase.NIGHT || step != Step.INFO || pid !in tasks) return false
        if (tasks[pid]?.kind == TaskKind.RAVEN && pid !in choices) return false
        ready += pid
        advanceNight()
        return true
    }

    private fun advanceNight() {
        if (phase != Phase.NIGHT) return
        when (step) {
            Step.CHOOSE -> if (tasks.keys.all { it in choices }) resolveChoices()
            Step.INFO -> if (tasks.keys.all { it in ready }) dawn()
        }
    }

    /** Host: stop waiting. Missing night choices are made at random, unread info counts as read. */
    fun forceNight(): Boolean {
        if (phase != Phase.NIGHT) return false
        if (step == Step.CHOOSE) {
            for ((pid, t) in tasks) if (pid !in choices) choices[pid] = randomPick(pid, t)
            resolveChoices()
        } else {
            for ((pid, t) in tasks) if (t.kind == TaskKind.RAVEN && pid !in choices) {
                val ts = randomPick(pid, t); choices[pid] = ts
                ravenInfo(seats.getValue(pid), seats.getValue(ts[0]))
            }
            dawn()
        }
        return true
    }

    /** A pick on behalf of a player who did not choose (never themselves, so a forced Imp does not star-pass). */
    private fun randomPick(pid: String, t: Task): List<String> {
        val ok = seats.values.filter { (!t.aliveOnly || it.alive) }
        val pool = ok.filter { it.id != pid }.takeIf { it.size >= t.picks } ?: ok
        return pool.shuffled(random).take(t.picks).map { it.id }
    }

    private fun resolveChoices() {
        val poisoner = seatWith(Role.POISONER)?.takeIf { it.alive }
        poisoner?.let { p -> choices[p.id]?.firstOrNull()?.let { poisoned = it; stLog += "${name(p.id)} (Отруйник) отруює ${name(it)}." } }

        seats.values.filter { it.role == Role.BUTLER && it.alive }.forEach { b -> choices[b.id]?.firstOrNull()?.let { b.master = it } }

        seatWith(Role.MONK)?.takeIf { it.alive }?.let { m ->
            choices[m.id]?.firstOrNull()?.let {
                if (malfunction(m)) stLog += "${name(m.id)} (Чернець) захищає ${name(it)}, але отруєний — марно."
                else { protectedId = it; stLog += "${name(m.id)} (Чернець) захищає ${name(it)}." }
            }
        }
        if (night > 1) seatWith(Role.IMP)?.takeIf { it.alive }?.let { imp ->
            choices[imp.id]?.firstOrNull()?.let { t ->
                if (malfunction(imp)) stLog += "${name(imp.id)} (Біс) атакує ${name(t)}, але отруєний — ніхто не гине."
                else attack(imp, seats.getValue(t))
            }
        }

        step = Step.INFO
        tasks.clear()
        // A Ravenkeeper (real or only in the Drunk's head) killed tonight wakes up to pick a player.
        for (v in killedTonight) seats[v]?.takeIf { it.shown == Role.RAVENKEEPER && !it.left }?.let {
            tasks[it.id] = Task(TaskKind.RAVEN, 1, false, false, "Тебе вбили цієї ночі. Обери гравця — дізнаєшся його роль.")
            choices.remove(it.id) // their earlier pick tonight was a dummy one
        }
        for (s in living()) {
            tasks[s.id] = Task(TaskKind.DUMMY, 0, false, false, "")
            nightInfo(s)
        }
        advanceNight()
    }

    private fun attack(imp: Seat, target: Seat) {
        val who = "${name(imp.id)} (Біс) атакує ${name(target.id)}"
        when {
            target === imp -> { starPass(imp); return }
            !target.alive -> { stLog += "$who — він уже мертвий."; return }
            target.id == protectedId -> { stLog += "$who — але Чернець захистив."; return }
            target.role == Role.SOLDIER && !malfunction(target) -> { stLog += "$who — Солдат вижив."; return }
        }
        var victim = target
        if (target.role == Role.MAYOR && !malfunction(target) && roll(mayorBounce)) {
            living().filter {
                it !== target && it.role != Role.IMP && it.id != protectedId && !(it.role == Role.SOLDIER && !malfunction(it))
            }.randomOrNull(random)?.let { victim = it; stLog += "$who — Мер відводить удар на ${name(it.id)}." }
        }
        if (victim === target) stLog += "$who — і вбиває."
        victim.alive = false
        killedTonight += victim.id
    }

    private fun starPass(imp: Seat) {
        imp.alive = false
        killedTonight += imp.id
        val heir = living().filter { it.role.kind == Kind.MINION }.let { ms -> ms.firstOrNull { it.role == Role.SCARLET_WOMAN } ?: ms.randomOrNull(random) }
        if (heir == null) { stLog += "${name(imp.id)} (Біс) вбиває себе — і Поплічників не лишилось."; return }
        stLog += "${name(imp.id)} (Біс) вбиває себе. Новий Біс — ${name(heir.id)} (${heir.role.title})."
        heir.role = Role.IMP; heir.shown = Role.IMP
        note(heir, "😈 Біс пожертвував собою. Тепер Біс — ти! З наступної ночі ти вбиваєш.")
    }

    private fun pairText(a: Seat, b: Seat, role: Role): String {
        val (x, y) = listOf(a, b).shuffled(random)
        return "Один із цих двох — ${role.emoji} ${role.title}: ${name(x.id)} або ${name(y.id)}."
    }

    /** Washerwoman / Librarian / Investigator: one of two players is a certain character of [kind]. */
    private fun pairInfo(me: Seat, kind: Kind): String {
        val others = order().filter { it !== me }
        val falseRole = { Role.of(kind).filter { it != me.shown }.random(random) }
        if (malfunction(me)) {
            val (a, b) = others.shuffled(random).take(2)
            return pairText(a, b, falseRole())
        }
        val candidates = others.mapNotNull { s ->
            when {
                s.role.kind == kind -> s to s.role
                kind != Kind.MINION && s.role == Role.SPY && roll(spyAsGood) -> s to Role.of(kind).filter { it != Role.DRUNK }.random(random)
                kind == Kind.MINION && s.role == Role.RECLUSE && roll(misregister) -> s to Role.of(Kind.MINION).random(random)
                else -> null
            }
        }
        val pick = candidates.randomOrNull(random)
            ?: return if (kind == Kind.OUTSIDER) "Чужинців у грі немає." else others.shuffled(random).take(2).let { pairText(it[0], it[1], falseRole()) }
        val other = others.filter { it !== pick.first }.random(random)
        return pairText(pick.first, other, pick.second)
    }

    private fun chefCount(): Int {
        val o = order()
        val ev = o.map { registersEvil(it) }
        return o.indices.count { ev[it] && ev[(it + 1) % o.size] }
    }

    private fun logInfo(s: Seat, text: String) {
        val why = when {
            s.role == Role.DRUNK -> " (хибно: П'яниця)"
            poisoned == s.id -> " (може бути хибно: отруєний)"
            else -> ""
        }
        stLog += "${name(s.id)} (${s.shown.title})$why: $text"
    }

    private fun nightInfo(s: Seat) {
        val first = night == 1
        val bad = malfunction(s)
        val text: String? = when (s.shown) {
            Role.WASHERWOMAN -> if (first) pairInfo(s, Kind.TOWNSFOLK) else null
            Role.LIBRARIAN -> if (first) pairInfo(s, Kind.OUTSIDER) else null
            Role.INVESTIGATOR -> if (first) pairInfo(s, Kind.MINION) else null
            Role.CHEF -> if (first) {
                val n = chefCount().let { if (bad) falseNum(it, 2) else it }
                "Пар злих гравців, які сидять поруч: $n."
            } else null
            Role.EMPATH -> {
                val nb = livingNeighbours(s)
                val n = nb.count { registersEvil(it) }.let { if (bad) falseNum(it, nb.size) else it }
                "Злих серед твоїх живих сусідів (${nb.joinToString(" і ") { name(it.id) }}): $n."
            }
            Role.FORTUNE_TELLER -> choices[s.id]?.mapNotNull { seats[it] }?.let { picked ->
                val yes = picked.any { registersDemon(it) || it.id == redHerring }.let { if (bad) !it else it }
                "${picked.joinToString(" і ") { name(it.id) }}: ${if (yes) "ТАК, серед них є Демон" else "ні, Демона серед них немає"}."
            }
            Role.UNDERTAKER -> if (!first) executedYesterday?.let { seats[it] }?.let { e ->
                val r = registeredRole(e).let { r -> if (bad) Role.entries.filter { it != r }.random(random) else r }
                "Страчений учора ${name(e.id)} — ${r.emoji} ${r.title}."
            } else null
            else -> null
        }
        text?.let { note(s, it); logInfo(s, it) }

        if (s.role == Role.SPY) note(s, "Гримуар:\n" + order().joinToString("\n") { grimoireLine(it) })
        if (first) evilInfo(s)
    }

    private fun grimoireLine(s: Seat): String {
        val tags = buildList {
            if (s.role == Role.DRUNK) add("думає, що ${s.shown.title}")
            if (poisoned == s.id) add("отруєний")
            if (s.id == redHerring) add("хибний Демон для Ворожки")
            if (!s.alive) add("мертвий")
        }
        return "${name(s.id)} — ${s.role.emoji} ${s.role.title}" + if (tags.isEmpty()) "" else " (${tags.joinToString()})"
    }

    /** First night: evil players learn each other (7+ players); the Demon also learns three safe bluffs. */
    private fun evilInfo(s: Seat) {
        if (!s.evil) return
        val big = seats.size >= 7
        val demon = seats.values.first { it.role == Role.IMP }
        val minions = order().filter { it.role.kind == Kind.MINION }
        if (s.role == Role.IMP) {
            if (big) note(s, "Твої Поплічники: ${minions.joinToString { name(it.id) }}.")
            note(s, "Цих ролей немає в грі — можеш прикидатися будь-якою: ${bluffs.joinToString { "${it.emoji} ${it.title}" }}.")
        } else if (big) {
            val mates = minions.filter { it !== s }
            note(s, "Демон — ${name(demon.id)}." + if (mates.isEmpty()) "" else " Інші Поплічники: ${mates.joinToString { name(it.id) }}.")
        }
    }

    private fun ravenInfo(rk: Seat, target: Seat) {
        val r = registeredRole(target).let { r -> if (malfunction(rk)) Role.entries.filter { it != r }.random(random) else r }
        val text = "${name(target.id)} — ${r.emoji} ${r.title}."
        note(rk, text)
        logInfo(rk, text)
    }

    private fun dawn() {
        phase = Phase.DAY
        dawnDeaths.clear(); dawnDeaths += killedTonight
        events += if (killedTonight.isEmpty()) "Ранок $night: ніч минула спокійно."
        else "Ранок $night: ${if (killedTonight.size == 1) "загинув(ла)" else "загинули"} ${killedTonight.joinToString { name(it) }}."
        tasks.clear(); ready.clear()
        nominations.clear(); nominators.clear(); nominees.clear()
        vote = null; block = null; blockVotes = 0
        executedToday = null
        checkWin()
    }

    // ---------- day ----------

    fun threshold() = (aliveCount() + 1) / 2

    fun canVote(s: Seat) = !s.left && (s.alive || s.ghostVote)

    fun nominate(nominator: String, nominee: String): Boolean {
        if (phase != Phase.DAY || vote != null) return false
        val a = seats[nominator] ?: return false
        val b = seats[nominee] ?: return false
        if (!a.alive || !b.alive || nominator in nominators || nominee in nominees) return false
        nominators += nominator; nominees += nominee
        events += "${name(nominator)} висуває ${name(nominee)}."
        if (b.role == Role.VIRGIN && !b.virginSpent && !malfunction(b)) {
            b.virginSpent = true
            val townsfolk = a.role.kind == Kind.TOWNSFOLK || (a.role == Role.SPY && roll(spyAsGood))
            if (townsfolk) {
                events += "⚡ ${name(nominator)} миттєво страчено!"
                stLog += "${name(nominee)} — Діва: висунув Містянин ${name(nominator)}, його страчено."
                execute(a)
                if (phase == Phase.DAY) startNight()
                return true
            }
        }
        vote = Vote(nominator, nominee)
        return true
    }

    fun castVote(pid: String, yes: Boolean): Boolean {
        val v = vote ?: return false
        val s = seats[pid] ?: return false
        if (phase != Phase.DAY || !canVote(s)) return false
        v.votes[pid] = yes
        if (seats.values.filter { canVote(it) }.all { it.id in v.votes }) closeVote()
        return true
    }

    /** Counts the open vote (the host may close it early: no answer = no). */
    fun closeVote(): Boolean {
        val v = vote ?: return false
        if (phase != Phase.DAY) return false
        val voters = v.votes.filter { (pid, yes) ->
            val s = seats[pid]
            yes && s != null && canVote(s) && !(s.role == Role.BUTLER && !malfunction(s) && s.master != null && v.votes[s.master] != true)
        }.keys.toList()
        // dead players spend their ghost vote when they raise their hand, even if it doesn't count
        v.votes.filter { it.value }.keys.mapNotNull { seats[it] }.filter { !it.alive }.forEach { it.ghostVote = false }
        val need = threshold()
        nominations += Nomination(v.nominator, v.nominee, voters.size, voters, need)
        val tail = "${voters.size} з потрібних $need"
        when {
            voters.size >= need && voters.size > blockVotes -> {
                block = v.nominee; blockVotes = voters.size
                events += "За страту ${name(v.nominee)}: $tail. Тепер на пласі ${name(v.nominee)}."
            }
            voters.size >= need && voters.size == blockVotes -> {
                block = null
                events += "За страту ${name(v.nominee)}: $tail — нічия, на пласі нікого."
            }
            else -> events += "За страту ${name(v.nominee)}: $tail — замало."
        }
        vote = null
        return true
    }

    /** Anyone may claim to be the Slayer and shoot once; only a working Slayer hitting the Demon kills. */
    fun shoot(pid: String, target: String): Boolean {
        if (phase != Phase.DAY || vote != null) return false
        val s = seats[pid] ?: return false
        val t = seats[target] ?: return false
        if (!s.alive || s.shot || !t.alive || s === t) return false
        s.shot = true
        val hit = s.role == Role.SLAYER && !malfunction(s) && registersDemon(t)
        if (!hit) { events += "🏹 ${name(pid)} стріляє в ${name(target)}… нічого не сталося."; return true }
        val before = aliveCount()
        t.alive = false
        events += "🏹 ${name(pid)} стріляє в ${name(target)} — і ${name(target)} падає замертво!"
        stLog += "${name(pid)} (Мисливець) вбиває ${name(target)} (${t.role.title})."
        if (t.role == Role.IMP) demonDied(before)
        checkWin()
        return true
    }

    /** Host: dusk. Executes whoever is on the block, then night falls. */
    fun endDay(): Boolean {
        if (phase != Phase.DAY) return false
        if (vote != null) closeVote()
        val b = block?.let { seats[it] }
        if (b != null && b.alive) {
            events += "⚖️ Страчено ${name(b.id)}."
            execute(b)
        } else {
            events += "Сьогодні нікого не стратили."
            seatWith(Role.MAYOR)?.takeIf { it.alive && !malfunction(it) && aliveCount() == 3 }?.let {
                stLog += "${name(it.id)} — Мер: троє живих і день без страти."
                finish(Side.GOOD, "Троє живих, день без страти — і серед них Мер.")
            }
        }
        if (phase == Phase.DAY) startNight()
        return true
    }

    private fun execute(s: Seat) {
        val before = aliveCount()
        s.alive = false
        executedToday = s.id
        if (s.role == Role.SAINT && !malfunction(s)) { finish(Side.EVIL, "Стратили Святого (${name(s.id)})."); return }
        if (s.role == Role.IMP) demonDied(before)
        checkWin()
    }

    private fun demonDied(aliveBefore: Int) {
        val sw = seatWith(Role.SCARLET_WOMAN)?.takeIf { it.alive && !malfunction(it) } ?: return
        if (aliveBefore < 5) return
        sw.role = Role.IMP; sw.shown = Role.IMP
        note(sw, "💋 Демон загинув — тепер Біс ти!", 0)
        stLog += "${name(sw.id)} (Багряна жінка) стає Бісом."
    }

    private fun checkWin() {
        if (phase == Phase.OVER || phase == Phase.LOBBY) return
        when {
            seats.values.none { it.alive && it.role == Role.IMP } -> finish(Side.GOOD, "Демон мертвий.")
            aliveCount() <= 2 -> finish(Side.EVIL, "Живих лишилось двоє.")
        }
    }

    private fun finish(side: Side, reason: String) {
        winner = side
        winReason = reason
        phase = Phase.OVER
        vote = null
        tasks.clear()
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        seats.clear(); tasks.clear(); choices.clear(); ready.clear()
        vote = null; block = null
        events.clear(); stLog.clear()
        notice = msg
    }

    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    // ---------- state for clients ----------

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        val playing = phase == Phase.NIGHT || phase == Phase.DAY
        o.put("phase", phase.name)
        o.put("step", step.name)
        o.put("night", night)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("canStart", canStart())
        o.put("minPlayers", MIN_PLAYERS)
        o.put("maxPlayers", MAX_PLAYERS)
        o.put("script", Role.scriptJson)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        val n = if (seats.isEmpty() || phase == Phase.LOBBY) seating.size else seats.size
        Composition.forPlayers(n)?.let { o.put("composition", it.json()) }

        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map {
            JSONObject().put("id", it.id).put("name", it.name).put("connected", it.connected).put("seated", it.id in seats || phase == Phase.LOBBY)
        }))
        o.put("seating", JSONArray(seating.filter { it in players || it in seats }))
        o.put("seats", JSONArray(order().map { s ->
            JSONObject().put("id", s.id).put("name", name(s.id)).put("alive", s.alive).put("ghostVote", s.ghostVote)
                .put("left", s.left).put("shot", s.shot)
                .put("nominated", s.id in nominees).put("nominator", s.id in nominators)
                // Only the living: a dead player still busy at night would give the Ravenkeeper away.
                .put("pending", phase == Phase.NIGHT && s.alive && s.id in tasks && s.id !in (if (step == Step.CHOOSE) choices.keys else ready))
                .apply { if (phase == Phase.OVER) put("role", s.role.name).put("shown", s.shown.name).put("evil", s.evil) }
        }))
        o.put("events", JSONArray(events))
        o.put("dawn", JSONArray(dawnDeaths))
        o.put("aliveCount", aliveCount())
        o.put("threshold", threshold())
        o.put("block", block ?: JSONObject.NULL)
        o.put("blockVotes", blockVotes)
        o.put("nominations", JSONArray(nominations.map {
            JSONObject().put("nominator", it.nominator).put("nominee", it.nominee).put("yes", it.yes)
                .put("voters", JSONArray(it.voters)).put("threshold", it.threshold)
        }))
        vote?.let { v ->
            o.put("vote", JSONObject().put("nominator", v.nominator).put("nominee", v.nominee)
                .put("votes", JSONObject(v.votes as Map<*, *>))
                .put("eligible", JSONArray(seats.values.filter { canVote(it) }.map { it.id })))
        }

        val me = viewer?.let { seats[it] }
        if (me != null && phase != Phase.LOBBY) {
            val m = JSONObject().put("id", me.id).put("seated", true)
                .put("role", me.shown.name).put("evil", me.evil).put("alive", me.alive).put("ghostVote", me.ghostVote)
                .put("shot", me.shot).put("nominated", me.id in nominators)
                .put("notes", JSONArray(me.notes.map { JSONObject().put("night", it.night).put("text", it.text) }))
            if (phase == Phase.NIGHT) tasks[me.id]?.let { t ->
                val picking = step == Step.CHOOSE || t.kind == TaskKind.RAVEN
                if (picking) m.put("task", JSONObject().put("kind", t.kind.name).put("picks", t.picks).put("notSelf", t.notSelf)
                    .put("aliveOnly", t.aliveOnly).put("prompt", t.prompt).put("done", me.id in choices)
                    .put("chosen", JSONArray(choices[me.id].orEmpty())))
                if (step == Step.INFO) m.put("mustRead", true).put("read", me.id in ready)
            }
            if (me.role == Role.BUTLER && playing) me.master?.let { m.put("master", it) }
            o.put("me", m)
        } else if (viewer != null) {
            o.put("me", JSONObject().put("id", viewer).put("seated", phase == Phase.LOBBY && viewer in seating))
        }

        if (phase == Phase.OVER) {
            o.put("winner", winner?.name ?: JSONObject.NULL)
            o.put("winReason", winReason)
            o.put("log", JSONArray(stLog))
        } else if (isHost && viewer == null && playing) {
            // A host who only runs the game may peek at the grimoire (the page hides it behind a tap).
            o.put("grimoire", JSONArray(order().map { grimoireLine(it) }))
            o.put("log", JSONArray(stLog))
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 5
        const val MAX_PLAYERS = 15
    }
}
