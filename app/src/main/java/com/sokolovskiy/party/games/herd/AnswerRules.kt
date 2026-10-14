package com.sokolovskiy.party.games.herd

/**
 * Cheap deterministic rules layered over the model similarity (pure logic, unit-tested):
 *  - numbers are the same only if they are the same number («п'ять» = «5 секунд», «тридцять» ≠ «3»);
 *  - near-identical spellings and «чорний перець» ⊃ «перець» always merge.
 * Also hosts the text cleaning and the clustering used by [Judge].
 */
object AnswerRules {

    private val numberWords = mapOf(
        "нуль" to 0L, "жодного" to 0L, "один" to 1L, "одна" to 1L, "одного" to 1L, "два" to 2L, "двоє" to 2L,
        "три" to 3L, "троє" to 3L, "чотири" to 4L, "п'ять" to 5L, "шість" to 6L, "сім" to 7L, "вісім" to 8L,
        "дев'ять" to 9L, "десять" to 10L, "двадцять" to 20L, "тридцять" to 30L, "сорок" to 40L,
        "п'ятдесят" to 50L, "шістдесят" to 60L, "сто" to 100L,
    )

    /** Text fed to the models: lowercase, letters/digits/apostrophes only. */
    fun clean(raw: String): String {
        val lower = raw.lowercase()
            .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
            .replace('ё', 'е')
        val sb = StringBuilder(lower.length)
        for (ch in lower) sb.append(if (ch.isLetterOrDigit() || ch == '\'') ch else ' ')
        return sb.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Numeric value of an answer ("5", "п'ять секунд"), null when it is not a number. */
    fun number(raw: String): Long? {
        for (t in clean(raw).split(' ')) {
            if (t.isNotEmpty() && t.all { it.isDigit() }) return t.toLongOrNull()
            numberWords[t]?.let { return it }
        }
        return null
    }

    /** One answer's words are all contained in the other's («перець» ⊂ «чорний перець»). */
    fun contains(a: String, b: String): Boolean {
        val ta = FuzzyMatcher.stems(a)
        val tb = FuzzyMatcher.stems(b)
        if (ta.isEmpty() || tb.isEmpty() || ta == tb) return false
        val (small, big) = if (ta.size < tb.size) ta to tb else tb to ta
        return big.containsAll(small) && small.all { it.length >= 4 }
    }

    /** Returns a copy of [sim] with rule-decided pairs forced to 1.0 (same) or 0.0 (different numbers). */
    fun adjust(answers: List<String>, sim: Array<DoubleArray>): Array<DoubleArray> {
        val s = Array(sim.size) { sim[it].clone() }
        for (i in answers.indices) for (j in i + 1 until answers.size) {
            val ni = number(answers[i])
            val nj = number(answers[j])
            val v = when {
                ni != null || nj != null -> if (ni != null && ni == nj) 1.0 else 0.0
                FuzzyMatcher.same(answers[i], answers[j]) || contains(answers[i], answers[j]) -> 1.0
                else -> continue
            }
            s[i][j] = v; s[j][i] = v
        }
        return s
    }

    /** Average-linkage agglomerative clustering; groups keep first-appearance order. */
    fun cluster(sim: Array<DoubleArray>, threshold: Double): List<List<Int>> {
        val clusters = MutableList(sim.size) { mutableListOf(it) }
        while (clusters.size > 1) {
            var best = -1.0; var bi = 0; var bj = 0
            for (a in clusters.indices) for (b in a + 1 until clusters.size) {
                var sum = 0.0
                for (i in clusters[a]) for (j in clusters[b]) sum += sim[i][j]
                val avg = sum / (clusters[a].size * clusters[b].size)
                if (avg > best) { best = avg; bi = a; bj = b }
            }
            if (best < threshold) break
            clusters[bi].addAll(clusters.removeAt(bj))
        }
        return clusters.map { it.sorted() }.sortedBy { it.first() }
    }
}
