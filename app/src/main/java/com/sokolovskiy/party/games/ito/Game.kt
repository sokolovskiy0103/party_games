package com.sokolovskiy.party.games.ito

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pure game logic for «Сотня» (after the Japanese co-op game Ito). Not thread-safe: the server guards it with a lock.
 *
 * Everyone plays together against the deck. On level N each player gets N secret numbers 1–100 (capped at
 * [MAX_CARDS], all numbers distinct). The topic is a scale ("how scary: 1 — not at all, 100 — nightmare"), and each
 * player describes each of their numbers with an example on that scale, without saying the number. Then cards go
 * on the table one at a time, lowest first, in any order of players: whoever thinks they hold the lowest card plays.
 *
 * A card played while someone still holds a lower one costs a life; those lower cards are discarded face up.
 * Clear the level with lives left to go up; a level with no mistakes gives a life back.
 *
 * Flow: LOBBY -> PLAY -> RESULT (level cleared) -> PLAY (next level) … -> OVER (out of lives).
 */
class Game(private val topics: TopicBank, private val random: Random = Random.Default) {
    enum class Phase { LOBBY, PLAY, RESULT, OVER }
    enum class Spice { CLEAN, MIXED, ADULT }

    class Player(val id: String, var name: String, val joinOrder: Int) {
        var connected = false
    }

    class Card(val value: Int, val owner: String) {
        var clue = ""
    }

    /** A card on the table. [missed]: discarded because a higher card was played before it. */
    class Played(val card: Card, val ownerName: String, val missed: Boolean)

    val players = LinkedHashMap<String, Player>()
    var phase = Phase.LOBBY; private set
    var spice = Spice.MIXED; private set
    /** Bumped on every deal so clients can reset per-level UI. */
    var round = 0; private set
    var level = 0; private set
    var lives = 0; private set
    var mistakes = 0; private set
    /** Cards per player on the current level. */
    var dealtEach = 0; private set
    /** Highest level cleared in this session. */
    var best = 0; private set
    var topic: Topic? = null; private set
    /** Each player's unplayed cards, lowest first. Only players dealt into the level have an entry. */
    val hands = LinkedHashMap<String, MutableList<Card>>()
    val table = ArrayList<Played>()
    var notice: String? = null; private set

    private var nextOrder = 0

    // ---------- players ----------

    fun add(id: String, name: String): Player =
        players.getOrPut(id) { Player(id, name, nextOrder++) }.also { it.name = name }

    /** A player leaving takes their cards with them; too few players ends the level. */
    fun remove(pid: String) {
        val p = players.remove(pid) ?: return
        if (hands.remove(pid) == null || phase != Phase.PLAY) return
        if (hands.size < MIN_PLAYERS) { cancel("${p.name} вийшов(ла) — рівень скасовано."); return }
        if (hands.values.all { it.isEmpty() }) clearLevel()
    }

    // ---------- flow ----------

    fun canStart() = players.size >= MIN_PLAYERS

    fun setSpice(s: Spice): Boolean {
        if (phase == Phase.PLAY) return false
        spice = s
        return true
    }

    /** LOBBY/OVER -> PLAY on level 1. */
    fun startGame(): Boolean {
        if (phase == Phase.PLAY || phase == Phase.RESULT || !canStart()) return false
        level = 1
        lives = START_LIVES
        deal()
        return true
    }

    /** RESULT -> PLAY on the next level. */
    fun nextLevel(): Boolean {
        if (phase != Phase.RESULT || !canStart()) return false
        level++
        deal()
        return true
    }

    fun cardsEach(n: Int = players.size) = minOf(level, MAX_CARDS, 100 / maxOf(n, 1))

    private fun deal() {
        val ids = players.values.sortedBy { it.joinOrder }.map { it.id }
        val each = cardsEach(ids.size)
        dealtEach = each
        val deck = (1..100).shuffled(random).iterator()
        hands.clear()
        for (id in ids) hands[id] = MutableList(each) { Card(deck.next(), id) }.apply { sortBy { it.value } }
        table.clear()
        mistakes = 0
        topic = topics.next(spice, random)
        notice = null
        round++
        phase = Phase.PLAY
    }

    /** Host: a different topic, only before the first card is down. Clues are dropped with the old topic. */
    fun swapTopic(): Boolean {
        if (phase != Phase.PLAY || table.isNotEmpty()) return false
        topic = topics.next(spice, random)
        hands.values.flatten().forEach { it.clue = "" }
        return true
    }

    /** Sets the example for one of the player's own unplayed cards (by its number). */
    fun setClue(pid: String, value: Int, text: String): Boolean {
        if (phase != Phase.PLAY) return false
        val card = hands[pid]?.firstOrNull { it.value == value } ?: return false
        card.clue = text.trim().replace(Regex("\\s+"), " ").take(MAX_CLUE)
        return true
    }

    /**
     * Plays the player's lowest card ([expect]: the number the client meant, so a stale double tap does not play
     * the next one). Returns the cards it jumped over (empty if the order held).
     */
    fun play(pid: String, expect: Int? = null): List<Card>? {
        if (phase != Phase.PLAY) return null
        val hand = hands[pid]?.takeIf { it.isNotEmpty() } ?: return null
        if (expect != null && hand[0].value != expect) return null
        val card = hand.removeAt(0)
        val lower = hands.values.flatMap { h -> h.filter { it.value < card.value } }.sortedBy { it.value }
        for (c in lower) {
            hands[c.owner]?.remove(c)
            table += Played(c, players[c.owner]?.name ?: "?", missed = true)
        }
        table += Played(card, players[pid]?.name ?: "?", missed = false)
        if (lower.isNotEmpty()) {
            mistakes++
            lives--
            if (lives <= 0) { phase = Phase.OVER; return lower }
        }
        if (hands.values.all { it.isEmpty() }) clearLevel()
        return lower
    }

    private fun clearLevel() {
        best = maxOf(best, level)
        if (mistakes == 0 && lives < MAX_LIVES) lives++
        phase = Phase.RESULT
    }

    private fun cancel(msg: String) {
        phase = Phase.LOBBY
        hands.clear(); table.clear()
        topic = null
        notice = msg
    }

    /** Host gives up on the current level/game. */
    fun abort() {
        if (phase != Phase.LOBBY) cancel("Ведучий перервав гру.")
    }

    fun newGame() {
        cancel("")
        notice = null
        level = 0; lives = 0; best = 0
        topics.reset()
    }

    // ---------- state for clients ----------

    private fun cardJson(c: Card) = JSONObject().put("v", c.value).put("clue", c.clue)

    fun stateJson(viewer: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("phase", phase.name)
        o.put("round", round)
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("minPlayers", MIN_PLAYERS)
        o.put("spice", spice.name)
        o.put("level", level)
        o.put("lives", lives)
        o.put("maxLives", MAX_LIVES)
        o.put("best", best)
        o.put("maxClue", MAX_CLUE)
        o.put("cardsEach", dealtEach)
        notice?.takeIf { it.isNotEmpty() }?.let { o.put("notice", it) }
        topic?.let { o.put("topic", JSONObject().put("q", it.q).put("lo", it.lo).put("hi", it.hi).put("adult", it.adult)) }

        val reveal = phase == Phase.OVER
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map { p ->
            val hand = hands[p.id]
            JSONObject()
                .put("id", p.id).put("name", p.name).put("connected", p.connected)
                .put("inRound", hand != null && phase != Phase.LOBBY)
                .put("left", hand?.size ?: 0)
                // Clues are public, numbers are not (until the game is lost).
                .put("clues", JSONArray(hand.orEmpty().map { it.clue }))
                .apply { if (reveal && hand != null) put("cards", JSONArray(hand.map { cardJson(it) })) }
        }))
        o.put("table", JSONArray(table.map {
            cardJson(it.card).put("owner", it.card.owner).put("name", it.ownerName).put("missed", it.missed)
        }))

        val me = viewer?.let { players[it] }
        if (me != null) {
            val hand = hands[me.id]
            o.put("me", JSONObject().put("id", me.id).put("name", me.name)
                .put("inRound", hand != null && phase != Phase.LOBBY)
                .put("cards", JSONArray(hand.orEmpty().map { cardJson(it) })))
        }
        return o
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_CARDS = 3
        const val START_LIVES = 3
        const val MAX_LIVES = 5
        const val MAX_CLUE = 60
    }
}
