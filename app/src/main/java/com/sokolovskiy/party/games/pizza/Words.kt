package com.sokolovskiy.party.games.pizza

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A control's name in the two cases the game needs: as a label (nominative) and in a command (accusative). */
data class Name(val nom: String, val acc: String, val noun: String)

/**
 * The content bank of «Піца-шатл» (`assets/data/pizza-words.json`): adjectives and nouns that make control names,
 * labels for selectors, colours, command templates, delivery addresses and jokes.
 *
 * Adjectives are stored in the masculine nominative (hard «-ий» or soft «-ій»); the other forms are derived, so a
 * name always agrees in gender: «Квантовий клапан», «Квантова помпа», «Квантове сопло», «Квантові щипці».
 * Commands use the accusative, which differs only for the feminine: «Увімкни Квантову помпу».
 */
class Words(
    val adjectives: List<String>,
    val nouns: List<Noun>,
    val optionSets: List<List<String>>,
    val colors: List<Color>,
    private val verbs: Map<String, List<String>>,
    val places: List<String>,
    val warpLines: List<String>,
    val overLines: List<String>,
) {
    /** [g]: m, f, n or p (plural-only nouns like «ножиці»). */
    data class Noun(val w: String, val g: String)
    data class Color(val name: String, val css: String)

    fun name(adj: String, noun: Noun): Name =
        Name("${adjForm(adj, noun.g, false)} ${noun.w}", "${adjForm(adj, noun.g, true)} ${nounAcc(noun)}", noun.w)

    /** [key] is one of [VERB_KEYS] (the «verbs» object in the JSON). */
    fun phrase(key: String, name: Name, value: String, random: Random): String {
        val t = verbs[key]?.takeIf { it.isNotEmpty() }?.random(random) ?: "{nom}: {v}"
        val s = t.replace("{acc}", name.acc).replace("{nom}", name.nom).replace("{v}", value)
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    companion object {
        val VERB_KEYS = listOf(
            "button", "on", "off", "seg", "slider", "dial", "keypad", "hold", "colors",
            "cover", "rocker", "fader", "counter", "plug", "joystick", "pull", "crank",
        )

        fun adjForm(m: String, g: String, acc: Boolean): String {
            val soft = m.endsWith("ій")
            val stem = m.dropLast(2)
            return when (g) {
                "f" -> stem + if (soft) (if (acc) "ю" else "я") else (if (acc) "у" else "а")
                "n" -> stem + if (soft) "є" else "е"
                "p" -> stem + "і"
                else -> m
            }
        }

        fun nounAcc(n: Noun): String = when {
            n.g != "f" -> n.w
            n.w.endsWith("а") -> n.w.dropLast(1) + "у"
            n.w.endsWith("я") -> n.w.dropLast(1) + "ю"
            else -> n.w  // «піч»
        }

        private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()

        fun fromJson(text: String): Words {
            val o = JSONObject(text)
            val nouns = o.getJSONArray("nouns").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { n -> Noun(n.getString("w"), n.getString("g")) } }
            }
            val sets = o.getJSONArray("optionSets").let { a -> (0 until a.length()).map { strings(a.getJSONArray(it)) } }
            val colors = o.getJSONArray("colors").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { c -> Color(c.getString("name"), c.getString("css")) } }
            }
            val v = o.getJSONObject("verbs")
            val verbs = VERB_KEYS.associateWith { strings(v.optJSONArray(it)) }
            return Words(strings(o.getJSONArray("adjectives")), nouns, sets, colors, verbs,
                strings(o.optJSONArray("places")), strings(o.optJSONArray("warpLines")), strings(o.optJSONArray("overLines")))
        }
    }
}
