package com.sokolovskiy.party.games.chameleon

import org.json.JSONArray
import kotlin.random.Random

/** A topic card: a name and the words one of which becomes the secret word. */
class Topic(val name: String, val words: List<String>)

/** Hands out each topic once per game, then reshuffles. */
class TopicBank(topics: List<Topic>) {
    private val all = topics
        .map { t -> Topic(t.name.trim(), t.words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) }
        .filter { it.name.isNotEmpty() && it.words.size >= 2 }
    private val used = HashSet<String>()

    init {
        require(all.isNotEmpty()) { "topic list is empty" }
    }

    val size get() = all.size

    fun next(random: Random): Topic {
        if (used.size >= all.size) used.clear()
        val t = all.filter { it.name !in used }.random(random)
        used += t.name
        return t
    }

    fun reset() = used.clear()

    companion object {
        fun fromJson(text: String): TopicBank {
            val arr = JSONArray(text)
            return TopicBank((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val ws = o.getJSONArray("words")
                Topic(o.getString("topic"), (0 until ws.length()).map { i -> ws.getString(i) })
            })
        }
    }
}
