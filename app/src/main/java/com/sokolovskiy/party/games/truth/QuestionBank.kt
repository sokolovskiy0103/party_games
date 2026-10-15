package com.sokolovskiy.party.games.truth

import org.json.JSONObject
import kotlin.random.Random

/** A personal question for the hot seat. [adult] ones are only used in the 18+ / mixed modes. */
class Question(val q: String, val adult: Boolean)

/** Hands out each question once per game, then reshuffles. Which questions are eligible depends on the spice level. */
class QuestionBank(questions: List<Question>) {
    private val all = questions
        .map { Question(it.q.trim(), it.adult) }
        .filter { it.q.isNotEmpty() }
        .distinctBy { it.q }
    private val used = HashSet<String>()

    init {
        require(all.any { !it.adult } && all.any { it.adult }) { "need both clean and adult questions" }
    }

    val size get() = all.size

    fun next(spice: Game.Spice, random: Random): Question {
        val pool = all.filter {
            when (spice) {
                Game.Spice.CLEAN -> !it.adult
                Game.Spice.ADULT -> it.adult
                Game.Spice.MIXED -> true
            }
        }
        if (pool.all { it.q in used }) used.removeAll(pool.map { it.q }.toSet())
        val q = pool.filter { it.q !in used }.random(random)
        used += q.q
        return q
    }

    fun reset() = used.clear()

    companion object {
        /** `{"clean": ["…"], "adult": ["…"]}` */
        fun fromJson(text: String): QuestionBank {
            val o = JSONObject(text)
            fun list(key: String, adult: Boolean) = o.getJSONArray(key).let { a -> (0 until a.length()).map { Question(a.getString(it), adult) } }
            return QuestionBank(list("clean", false) + list("adult", true))
        }
    }
}
