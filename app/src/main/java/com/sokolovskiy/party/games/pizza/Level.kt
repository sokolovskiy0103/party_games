package com.sokolovskiy.party.games.pizza

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class Difficulty { EASY, NORMAL, CHAOS }

/**
 * Trouble on the way, unlocked level by level ([unlock], one level earlier on chaos).
 * Personal ones hit one player; [global] ones need the whole crew and stop the command clocks meanwhile.
 */
enum class Hazard(val unlock: Int, val global: Boolean) {
    CHEESE(2, false),     // cheese fog: one panel goes blurry for a while
    GLITCH(2, false),     // radio noise: one player's command loses letters
    SAUCE(2, false),      // a sauce flood: splats cover one panel until wiped off
    BREAK(3, false),      // a breakdown: an instrument sparks and does nothing until fixed (tap it)
    SHUFFLE(3, false),    // a shake-up: one panel's instruments get mixed up
    GRAVITY(3, true),     // a gravity well: everyone holds the pizza
    ASTEROIDS(4, true),   // a meteor shower: everyone shoots down the rocks on their screen
}

/**
 * Everything that makes a delivery (level) harder, in one place for tuning.
 *
 * @param cells panel size in grid cells (2 columns; a wide control takes 2)
 * @param types controls that may appear on a panel
 * @param cmdMs time to carry out a command
 * @param goal commands the crew must carry out to deliver
 * @param damage how many degrees the pizza loses per missed command (temperature starts at 100)
 * @param hazardMs average gap between hazards (0 = none)
 * @param sameNoun names may share a noun («Сирна помпа» and «Квантова помпа») — on purpose, to confuse
 */
data class Level(
    val number: Int,
    val cells: Int,
    val types: List<Control.Type>,
    val cmdMs: Long,
    val goal: Int,
    val damage: Int,
    val hazards: List<Hazard>,
    val hazardMs: Long,
    val sameNoun: Boolean,
) {
    companion object {
        const val MAX_CELLS = 10

        fun of(level: Int, players: Int, diff: Difficulty): Level {
            val n = max(players, 1)
            val cells = min(MAX_CELLS, 6 + 2 * (level - 1) + if (n <= 2) 2 else 0)
            val types = Control.Type.entries.filter { it.unlock <= level }
            val (base, floor) = when (diff) {
                Difficulty.EASY -> 16_000L to 8_000L
                Difficulty.NORMAL -> 13_000L to 6_500L
                Difficulty.CHAOS -> 10_000L to 5_000L
            }
            val cmdMs = max(floor, base - 1_000L * (level - 1))
            val goal = min(40, 6 + 3 * n + 2 * (level - 1))
            val allowed = max(3, 3 + n + when (diff) { Difficulty.EASY -> 2; Difficulty.NORMAL -> 0; Difficulty.CHAOS -> -1 })
            val damage = ceil(100.0 / allowed).toInt()
            val shift = if (diff == Difficulty.CHAOS) 1 else 0
            val hazards = Hazard.entries.filter { it.unlock <= level + shift }
            val gap = max(10_000L, 26_000L - 3_000L * (level + shift - 2))
            val hazardMs = if (hazards.isEmpty()) 0L else (gap * when (diff) {
                Difficulty.EASY -> 1.4; Difficulty.NORMAL -> 1.0; Difficulty.CHAOS -> 0.7
            }).roundToLong()
            return Level(level, cells, types, cmdMs, goal, damage, hazards, hazardMs, level >= 3)
        }
    }
}
