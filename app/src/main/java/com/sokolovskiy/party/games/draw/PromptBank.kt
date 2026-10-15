package com.sokolovskiy.party.games.draw

import org.json.JSONObject
import kotlin.random.Random

/** Something to draw. [adult] ones are only used in the 18+ / mixed modes. */
class Prompt(val text: String, val adult: Boolean)

/** Hands out each prompt once per evening, then reshuffles. Which prompts are eligible depends on the spice level. */
class PromptBank(prompts: List<Prompt>) {
    private val all = prompts
        .map { Prompt(it.text.trim(), it.adult) }
        .filter { it.text.isNotEmpty() }
        .distinctBy { it.text }
    private val used = HashSet<String>()

    init {
        require(all.any { !it.adult } && all.any { it.adult }) { "need both clean and adult prompts" }
    }

    val size get() = all.size

    fun next(spice: Game.Spice, random: Random): Prompt {
        val pool = all.filter {
            when (spice) {
                Game.Spice.CLEAN -> !it.adult
                Game.Spice.ADULT -> it.adult
                Game.Spice.MIXED -> true
            }
        }
        if (pool.all { it.text in used }) used.removeAll(pool.map { it.text }.toSet())
        val p = pool.filter { it.text !in used }.random(random)
        used += p.text
        return p
    }

    fun reset() = used.clear()

    companion object {
        /** `{"clean": ["…"], "adult": ["…"]}` */
        fun fromJson(text: String): PromptBank {
            val o = JSONObject(text)
            fun list(key: String, adult: Boolean) = o.getJSONArray(key).let { a -> (0 until a.length()).map { Prompt(a.getString(it), adult) } }
            return PromptBank(list("clean", false) + list("adult", true))
        }
    }
}
