package com.sokolovskiy.party.games.chameleon

import org.json.JSONArray
import kotlin.random.Random

/** Two similar words: most players get [common], the Chameleon gets [odd]. */
class WordPair(val common: String, val odd: String)

/** Hands out each pair once per game (in a random orientation), then reshuffles. */
class PairBank(pairs: List<Pair<String, String>>) {
    private val all = pairs.map { it.first.trim() to it.second.trim() }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() && it.first != it.second }.distinct()
    private val used = HashSet<Pair<String, String>>()

    init {
        require(all.isNotEmpty()) { "pair list is empty" }
    }

    val size get() = all.size

    fun next(random: Random): WordPair {
        if (used.size >= all.size) used.clear()
        val p = all.filter { it !in used }.random(random)
        used += p
        return if (random.nextBoolean()) WordPair(p.first, p.second) else WordPair(p.second, p.first)
    }

    fun reset() = used.clear()

    companion object {
        fun fromJson(text: String): PairBank {
            val arr = JSONArray(text)
            return PairBank((0 until arr.length()).map {
                val pair = arr.getJSONArray(it)
                pair.getString(0) to pair.getString(1)
            })
        }
    }
}
