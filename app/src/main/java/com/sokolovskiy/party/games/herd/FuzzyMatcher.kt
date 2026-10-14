package com.sokolovskiy.party.games.herd

/**
 * Offline fallback judge: groups answers that are "obviously the same" —
 * same words after normalisation, prepositions dropped, crude Ukrainian/Russian
 * stemming, and small typos. Deliberately conservative: when unsure it keeps
 * answers apart and leaves the decision to the host.
 */
object FuzzyMatcher {

    private val stopWords = setOf(
        // prepositions / particles that don't change the meaning of a short answer
        "в", "у", "на", "з", "із", "зі", "до", "по", "під", "за", "біля", "коло", "від", "для", "про",
        "через", "при", "над", "між", "без", "к", "о", "об", "і", "й", "та", "а", "це", "то", "ну",
        "мій", "моя", "моє", "мої", "свій", "своя", "своє", "свої", "якийсь", "якась", "якесь",
        "во", "со", "с", "и", "это", "мой", "моя", "свой",
        "the", "a", "an", "in", "on", "at", "of", "my",
    )

    // longest first
    private val endings = listOf(
        "ами", "ями", "ові", "еві", "єві", "ого", "ому", "ими", "іми", "ях", "ах", "ою", "ею", "єю",
        "ом", "ем", "ам", "ям", "ів", "їв", "ий", "ій", "ій", "ия", "ая", "яя", "ое", "ее", "ые",
        "а", "я", "у", "ю", "і", "и", "е", "є", "о", "ь", "ї", "й", "ы",
    )

    fun normalize(raw: String): String {
        val lower = raw.lowercase()
            .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
            .replace('ё', 'е')
        val cleaned = buildString {
            for (ch in lower) {
                append(if (ch.isLetterOrDigit()) ch else ' ')
            }
        }
        return cleaned.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun stem(token: String): String {
        if (token.all { it.isDigit() }) return token
        for (e in endings) {
            if (token.endsWith(e) && token.length - e.length >= 3) {
                return token.substring(0, token.length - e.length)
            }
        }
        return token
    }

    /** Key used for exact matching: meaningful stemmed tokens, sorted. */
    fun key(raw: String): String {
        val tokens = normalize(raw).split(' ').filter { it.isNotEmpty() }
        val meaningful = tokens.filter { it !in stopWords }.ifEmpty { tokens }
        return meaningful.map(::stem).sorted().joinToString(" ")
    }

    /** Set of stemmed meaningful words of an answer. */
    fun stems(raw: String): Set<String> {
        val tokens = normalize(raw).split(' ').filter { it.isNotEmpty() }
        val meaningful = tokens.filter { it !in stopWords }.ifEmpty { tokens }
        return meaningful.map(::stem).toSet()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
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

    fun same(a: String, b: String): Boolean {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        // numbers must match exactly
        if (ka.any { it.isDigit() } || kb.any { it.isDigit() }) return false
        val maxLen = maxOf(ka.length, kb.length)
        if (maxLen < 4) return false
        val dist = levenshtein(ka, kb)
        val allowed = when {
            maxLen <= 5 -> 1
            maxLen <= 10 -> 2
            else -> 3
        }
        return dist <= allowed
    }

    /** Returns groups as lists of indices into [answers]. */
    fun group(answers: List<String>): List<List<Int>> {
        val parent = IntArray(answers.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        for (i in answers.indices) for (j in i + 1 until answers.size) {
            if (same(answers[i], answers[j])) {
                val ri = find(i); val rj = find(j)
                if (ri != rj) parent[rj] = ri
            }
        }
        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (i in answers.indices) byRoot.getOrPut(find(i)) { mutableListOf() }.add(i)
        return byRoot.values.toList()
    }
}
