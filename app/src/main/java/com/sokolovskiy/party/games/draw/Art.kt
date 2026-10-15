package com.sokolovskiy.party.games.draw

import org.json.JSONArray

/**
 * A drawing as vector strokes: each stroke is `[color, size, x0, y0, x1, y1, …]` with coordinates on a
 * [SIZE]×[SIZE] square, so it looks the same on every screen and stays small on the wire.
 * Colours and brush sizes are indexes into the palettes in `draw.js`.
 */
class Art(val strokes: List<IntArray>) {
    val isEmpty get() = strokes.isEmpty()

    val json: JSONArray by lazy { JSONArray(strokes.map { JSONArray(it.toList()) }) }

    companion object {
        const val SIZE = 1000
        const val COLORS = 12
        const val SIZES = 3
        const val MAX_STROKES = 800
        const val MAX_POINTS = 10_000

        /** Null if the drawing is malformed or too big. */
        fun parse(a: JSONArray?): Art? {
            a ?: return null
            if (a.length() > MAX_STROKES) return null
            var points = 0
            val out = ArrayList<IntArray>(a.length())
            for (i in 0 until a.length()) {
                val s = a.optJSONArray(i) ?: return null
                val n = s.length()
                if (n < 4 || n % 2 != 0) return null
                if (s.optInt(0, -1) !in 0 until COLORS || s.optInt(1, -1) !in 0 until SIZES) return null
                points += (n - 2) / 2
                if (points > MAX_POINTS) return null
                out += IntArray(n) { k -> if (k < 2) s.optInt(k) else s.optInt(k).coerceIn(0, SIZE) }
            }
            return Art(out)
        }
    }
}
