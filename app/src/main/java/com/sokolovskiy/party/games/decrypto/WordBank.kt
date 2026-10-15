package com.sokolovskiy.party.games.decrypto

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Keyword pools for «Шифр». Hands out each word once per game, then reshuffles that pool. */
class WordBank(clean: List<String>, adult: List<String>) {
    private val clean = clean.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val adult = adult.map { it.trim() }.filter { it.isNotEmpty() && it !in this.clean }.distinct()
    private val used = HashSet<String>()

    init {
        require(this.clean.size >= 2 * WORDS && this.adult.size >= 2 * WORDS) { "word lists are too short" }
    }

    /** Two disjoint sets of [WORDS] keywords, one per team. */
    fun deal(spice: Game.Spice, random: Random): List<List<String>> {
        // adult words per team: none, half or all of them
        val nAdult = when (spice) { Game.Spice.CLEAN -> 0; Game.Spice.MIXED -> WORDS / 2; Game.Spice.ADULT -> WORDS }
        val a = take(adult, 2 * nAdult, random)
        val c = take(clean, 2 * (WORDS - nAdult), random)
        return (0..1).map { t ->
            (a.subList(t * nAdult, (t + 1) * nAdult) + c.subList(t * (WORDS - nAdult), (t + 1) * (WORDS - nAdult))).shuffled(random)
        }
    }

    private fun take(pool: List<String>, n: Int, random: Random): List<String> {
        if (n == 0) return emptyList()
        if (pool.count { it !in used } < n) used.removeAll(pool.toSet())
        return pool.filter { it !in used }.shuffled(random).take(n).also { used += it }
    }

    fun reset() = used.clear()

    companion object {
        const val WORDS = 4

        fun fromJson(text: String): WordBank {
            val o = JSONObject(text)
            fun list(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            return WordBank(list(o.getJSONArray("clean")), list(o.getJSONArray("adult")))
        }
    }
}
