package com.sokolovskiy.party.games.speech

/**
 * Does a listener's guess name a secret word? Forgiving about case, apostrophes, inflection («відпустку» for
 * «відпустка») and a typo or two in longer words; strict on short ones. The speaker or the host corrects the rest.
 */
object Matcher {

    // longest first
    private val endings = listOf(
        "ами", "ями", "ові", "еві", "єві", "ого", "ому", "ими", "іми", "ях", "ах", "ою", "ею", "єю",
        "ом", "ем", "ам", "ям", "ів", "їв", "ий", "ій", "ия", "ая", "ое", "ее", "ые",
        "а", "я", "у", "ю", "і", "и", "е", "є", "о", "ь", "ї", "й", "ы",
    )

    fun normalize(raw: String): String {
        val lower = raw.lowercase()
            .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
            .replace('ё', 'е').replace('\'', ' ')
        return lower.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun stem(token: String): String {
        for (e in endings) if (token.endsWith(e) && token.length - e.length >= 3) return token.dropLast(e.length)
        return token
    }

    private fun stems(s: String) = s.split(' ').joinToString(" ") { stem(it) }

    fun same(guess: String, word: String): Boolean {
        val a = normalize(guess)
        val b = normalize(word)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val sa = stems(a)
        val sb = stems(b)
        if (sa == sb) return true
        val len = maxOf(sa.length, sb.length)
        val allowed = when {
            len <= 4 -> 0
            len <= 7 -> 1
            else -> 2
        }
        return levenshtein(sa, sb) <= allowed
    }

    /**
     * Stricter check for a word the recogniser heard: recognisers spell correctly, so only inflection is forgiven
     * (the same stem), not typos: fewer false "said it" marks.
     */
    fun spoken(token: String, word: String): Boolean {
        val a = normalize(token)
        val b = normalize(word)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || stems(a) == stems(b)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
