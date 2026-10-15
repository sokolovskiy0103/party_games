package com.sokolovskiy.party.games.herd

import android.content.Context
import org.json.JSONObject

/** Dev stub: no ONNX models, always fuzzy grouping. */
class Judge(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") onChange: () -> Unit) {
    class Verdict(val groups: List<List<Int>>, val source: String)

    fun init() {}
    fun statusJson(): JSONObject = JSONObject().put("enabled", false).put("state", "OFF")
        .put("progress", 0.0).put("message", "dev").put("lastMillis", 0L)
    fun setEnabled(@Suppress("UNUSED_PARAMETER") on: Boolean) {}
    suspend fun group(answers: List<String>): Verdict = Verdict(FuzzyMatcher.group(answers), "fuzzy")
}
