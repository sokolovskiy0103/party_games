package com.sokolovskiy.party.games.detective

import org.json.JSONArray
import kotlin.random.Random

/** One suspect of a case: the four attributes the team has to piece together. */
data class Suspect(
    val name: String,
    val motive: String,
    val where: String,
    val item: String,
    val time: String,
)

/**
 * A hand-written scenario. Only the cast is fixed: every play the server picks a random culprit
 * and builds the testimony from that suspect's attributes, so the same case can be solved again.
 */
data class Case(
    val title: String,
    val victim: String,
    val place: String,
    val intro: String,
    val suspects: List<Suspect>,
) {
    init {
        require(suspects.size >= 4) { "a case needs at least 4 suspects" }
        val combos = suspects.map { listOf(it.motive, it.where, it.item, it.time) }
        require(combos.distinct().size == suspects.size) {
            "every suspect needs a distinct motive/where/item/time combination"
        }
    }
}

/** Cases in random order without repeats until the bank is exhausted. */
class CaseBank(private val cases: List<Case>) {
    private val used = HashSet<Int>()

    init {
        require(cases.isNotEmpty()) { "case list is empty" }
    }

    fun next(random: Random): Case {
        if (used.size >= cases.size) used.clear()
        val i = cases.indices.filter { it !in used }.random(random)
        used += i
        return cases[i]
    }

    fun reset() = used.clear()

    companion object {
        fun fromJson(text: String): CaseBank {
            val arr = JSONArray(text)
            return CaseBank((0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ss = o.getJSONArray("suspects")
                Case(
                    title = o.getString("title"),
                    victim = o.getString("victim"),
                    place = o.getString("place"),
                    intro = o.getString("intro"),
                    suspects = (0 until ss.length()).map { j ->
                        val s = ss.getJSONObject(j)
                        Suspect(
                            name = s.getString("name"),
                            motive = s.getString("motive"),
                            where = s.getString("where"),
                            item = s.getString("item"),
                            time = s.getString("time"),
                        )
                    },
                )
            })
        }
    }
}
