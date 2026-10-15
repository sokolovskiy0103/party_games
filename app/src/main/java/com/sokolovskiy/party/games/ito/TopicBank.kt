package com.sokolovskiy.party.games.ito

import org.json.JSONArray
import kotlin.random.Random

/** A scale to describe numbers on: "[q]: 1 — [lo], 100 — [hi]". */
class Topic(val q: String, val lo: String, val hi: String, val adult: Boolean)

/** Hands out each topic once per game, then reshuffles. Which topics are eligible depends on the spice level. */
class TopicBank(topics: List<Topic>) {
    private val all = topics
        .map { Topic(it.q.trim(), it.lo.trim(), it.hi.trim(), it.adult) }
        .filter { it.q.isNotEmpty() && it.lo.isNotEmpty() && it.hi.isNotEmpty() }
        .distinctBy { it.q }
    private val used = HashSet<String>()

    init {
        require(all.any { !it.adult } && all.any { it.adult }) { "need both clean and adult topics" }
    }

    val size get() = all.size

    fun next(spice: Game.Spice, random: Random): Topic {
        val pool = all.filter {
            when (spice) {
                Game.Spice.CLEAN -> !it.adult
                Game.Spice.ADULT -> it.adult
                Game.Spice.MIXED -> true
            }
        }
        if (pool.all { it.q in used }) used.removeAll(pool.map { it.q }.toSet())
        val t = pool.filter { it.q !in used }.random(random)
        used += t.q
        return t
    }

    fun reset() = used.clear()

    companion object {
        fun fromJson(text: String): TopicBank {
            val arr = JSONArray(text)
            return TopicBank((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Topic(o.getString("q"), o.getString("lo"), o.getString("hi"), o.optBoolean("adult"))
            })
        }
    }
}
