package com.sokolovskiy.party.games.pizza

import kotlin.random.Random

/**
 * One instrument on a player's panel.
 *
 * Stateful controls (toggle, selector, slider, dial, rocker, fader, counter, plug) hold a [value]; a command for them
 * is done when the value becomes the target. The others are one-shot: a command is done by the matching action.
 */
class Control(
    val id: String,
    val owner: String,
    val type: Type,
    val name: Name,
    /** Selector labels, colour names, rocker positions or joystick directions — as they sound in a command. */
    val opts: List<String>,
    val min: Int,
    val max: Int,
    var value: Int,
) {
    /** Sparking after a BREAK hazard: taps left to fix it; it ignores actions until then. 0 = working. */
    var fixLeft = 0
    val broken get() = fixLeft > 0

    /**
     * [cells]: width in the 2-column grid; [unlock]: the first level it appears on; [weight]: how often it is picked.
     * The verb key of a command is in [Game.text].
     */
    enum class Type(val cells: Int, val stateful: Boolean, val unlock: Int, val weight: Int) {
        BUTTON(1, false, 1, 3),    // a big round button
        TOGGLE(1, true, 1, 3),     // a lever: on / off
        SEG(2, true, 1, 2),        // a row of labelled keys
        COVER(1, false, 1, 2),     // a button under a safety cover: flip it open, then press
        SLIDER(2, true, 2, 2),     // horizontal, 0..5
        DIAL(1, true, 2, 2),       // a knob, 0..5
        ROCKER(1, true, 2, 2),     // three positions: down / centre / up
        FADER(1, true, 2, 2),      // vertical, 0..4
        KEYPAD(2, false, 3, 1),    // keys 1..9
        HOLD(1, false, 3, 2),      // hold until it fills
        COUNTER(1, true, 3, 2),    // an odometer 0..9 with − / +
        PLUG(2, true, 3, 1),       // a cable plugged into socket 1..4
        COLORS(2, false, 4, 1),    // four coloured buttons
        JOYSTICK(1, false, 4, 2),  // flick it left / up / right / down
        PULL(1, false, 4, 2),      // pull the cord all the way down
        CRANK(1, false, 4, 2),     // turn the handle a full circle
    }

    /** Values a command may ask for: anything but the current one for stateful controls. */
    fun targets(): List<Int> = (min..max).filter { !type.stateful || it != value }

    companion object {
        /** How the positions sound in a command («Переведи … вгору»). */
        val ROCKER_OPTS = listOf("вниз", "в центр", "вгору")
        val JOY_OPTS = listOf("вліво", "вгору", "вправо", "вниз")
    }
}

/**
 * Hands out unique control names for one level. Unless [sameNoun], nouns don't repeat either while there are
 * fresh ones left, so names are easy to tell apart by ear on early levels.
 */
class NameBag(private val words: Words, private val random: Random, private val sameNoun: Boolean) {
    private val used = HashSet<String>()
    private val usedNouns = HashSet<String>()

    fun next(): Name {
        val fresh = words.nouns.filter { it.w !in usedNouns }
        // later levels: prefer nouns already on the ship, so «Сирна помпа» meets «Квантова помпа»
        val pool = when {
            sameNoun && usedNouns.isNotEmpty() && random.nextInt(100) < 40 -> words.nouns.filter { it.w in usedNouns }
            fresh.isNotEmpty() -> fresh
            else -> words.nouns
        }
        repeat(200) {
            val n = words.name(words.adjectives.random(random), pool.random(random))
            if (used.add(n.nom)) { usedNouns += n.noun; return n }
        }
        // practically unreachable with the shipped bank; numbered fallback keeps names unique
        val n = words.name(words.adjectives.random(random), words.nouns.random(random))
        val k = used.size + 1
        return Name("${n.nom} $k", "${n.acc} $k", n.noun).also { used += it.nom }
    }
}

/**
 * Builds panels for one level. The types new on this level are spread over the ship: each panel takes up to
 * [FRESH_PER_PANEL] of those nobody has yet, so every new instrument shows up somewhere. At most two of a kind per panel.
 */
class PanelMaker(private val words: Words, private val random: Random, private val level: Level, private val idPrefix: String = "c") {
    private val names = NameBag(words, random, level.sameNoun)
    private var nextId = 0
    private val usedSets = HashSet<Int>()
    private val freshLeft = level.types.filter { it.unlock == level.number }.shuffled(random).toMutableList()

    fun make(owner: String): List<Control> {
        val out = ArrayList<Control>()
        var left = level.cells
        val count = HashMap<Control.Type, Int>()
        fun place(t: Control.Type) { out += control(owner, t); left -= t.cells; count[t] = (count[t] ?: 0) + 1 }
        repeat(FRESH_PER_PANEL) {
            val t = freshLeft.firstOrNull { it.cells <= left } ?: return@repeat
            freshLeft.remove(t); place(t)
        }
        while (left > 0) {
            val options = level.types.filter { it.cells <= left && (count[it] ?: 0) < 2 }
                .ifEmpty { level.types.filter { it.cells <= left } }
            if (options.isEmpty()) break
            val total = options.sumOf { it.weight }
            var r = random.nextInt(total)
            val t = options.first { r -= it.weight; r < 0 }
            place(t)
        }
        out.shuffle(random)
        return out
    }

    private fun control(owner: String, t: Control.Type): Control {
        val id = "$idPrefix${nextId++}"
        val name = names.next()
        fun c(opts: List<String>, min: Int, max: Int, v: Int) = Control(id, owner, t, name, opts, min, max, v)
        return when (t) {
            Control.Type.BUTTON, Control.Type.HOLD, Control.Type.COVER,
            Control.Type.PULL, Control.Type.CRANK -> c(emptyList(), 0, 0, 0)
            Control.Type.TOGGLE -> c(emptyList(), 0, 1, random.nextInt(2))
            Control.Type.SEG -> pickSet().let { c(it, 0, it.size - 1, random.nextInt(it.size)) }
            Control.Type.SLIDER, Control.Type.DIAL -> c(emptyList(), 0, 5, random.nextInt(6))
            Control.Type.ROCKER -> c(Control.ROCKER_OPTS, 0, 2, random.nextInt(3))
            Control.Type.FADER -> c(emptyList(), 0, 4, random.nextInt(5))
            Control.Type.COUNTER -> c(emptyList(), 0, 9, random.nextInt(10))
            Control.Type.PLUG -> c(emptyList(), 1, 4, random.nextInt(1, 5))
            Control.Type.KEYPAD -> c(emptyList(), 1, 9, 0)
            Control.Type.COLORS -> c(words.colors.map { it.name }, 0, words.colors.size - 1, 0)
            Control.Type.JOYSTICK -> c(Control.JOY_OPTS, 0, 3, 0)
        }
    }

    private fun pickSet(): List<String> {
        val free = words.optionSets.indices.filter { it !in usedSets }.ifEmpty { words.optionSets.indices.toList() }
        val i = free.random(random)
        usedSets += i
        return words.optionSets[i]
    }

    companion object {
        const val FRESH_PER_PANEL = 2
    }
}
