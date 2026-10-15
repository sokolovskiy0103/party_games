package com.sokolovskiy.party.games.codenames

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Board words for «Кодові імена». Hands out each word once per game, then reshuffles that pool. */
class WordBank(clean: List<String>, adult: List<String>) {
    private val clean = clean.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val adult = adult.map { it.trim() }.filter { it.isNotEmpty() && it !in this.clean }.distinct()
    private val used = HashSet<String>()

    init {
        require(this.clean.size >= Game.CARDS && this.adult.size >= Game.CARDS) { "word lists are too short" }
    }

    /** [Game.CARDS] distinct words in random order. */
    fun deal(spice: Game.Spice, random: Random): List<String> {
        val nAdult = when (spice) { Game.Spice.CLEAN -> 0; Game.Spice.MIXED -> MIXED_ADULT; Game.Spice.ADULT -> Game.CARDS }
        return (take(adult, nAdult, random) + take(clean, Game.CARDS - nAdult, random)).shuffled(random)
    }

    private fun take(pool: List<String>, n: Int, random: Random): List<String> {
        if (n == 0) return emptyList()
        if (pool.count { it !in used } < n) used.removeAll(pool.toSet())
        return pool.filter { it !in used }.shuffled(random).take(n).also { used += it }
    }

    fun reset() = used.clear()

    companion object {
        const val MIXED_ADULT = 8

        fun fromJson(text: String): WordBank {
            val o = JSONObject(text)
            fun list(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            return WordBank(list(o.getJSONArray("clean")), list(o.getJSONArray("adult")))
        }
    }
}
