package com.sokolovskiy.party.games.wavelength

import org.json.JSONArray
import kotlin.random.Random

/** A scale between two opposite concepts. */
class Spectrum(val left: String, val right: String)

/** Hands out each spectrum once per game (in a random orientation), then reshuffles. */
class SpectrumBank(pairs: List<Pair<String, String>>) {
    private val all = pairs.map { it.first.trim() to it.second.trim() }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.distinct()
    private val used = HashSet<Pair<String, String>>()

    init {
        require(all.isNotEmpty()) { "spectrum list is empty" }
    }

    val size get() = all.size

    fun next(random: Random): Spectrum {
        if (used.size >= all.size) used.clear()
        val p = all.filter { it !in used }.random(random)
        used += p
        return if (random.nextBoolean()) Spectrum(p.first, p.second) else Spectrum(p.second, p.first)
    }

    fun reset() = used.clear()

    companion object {
        fun fromJson(text: String): SpectrumBank {
            val arr = JSONArray(text)
            return SpectrumBank((0 until arr.length()).map {
                val pair = arr.getJSONArray(it)
                pair.getString(0) to pair.getString(1)
            })
        }
    }
}
