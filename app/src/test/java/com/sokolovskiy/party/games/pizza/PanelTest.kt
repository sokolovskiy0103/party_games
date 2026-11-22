package com.sokolovskiy.party.games.pizza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PanelTest {
    private val words = realWords()

    @Test fun panelsFillTheirCellsWithUnlockedTypes() {
        for (level in 1..8) for (n in 2..8) for (seed in 0..5) {
            val lv = Level.of(level, n, Difficulty.NORMAL)
            val maker = PanelMaker(words, Random(seed), lv)
            val panels = (1..n).map { maker.make("p$it") }
            panels.forEach { panel ->
                assertEquals(lv.cells, panel.sumOf { it.type.cells })
                assertTrue(panel.all { it.type.unlock <= level })
                assertTrue(panel.all { it.type.stateful.not() || it.value in it.min..it.max })
                assertTrue(panel.size >= 3)
            }
            val all = panels.flatten()
            // every instrument new on this level is somewhere on the ship (two per panel)
            lv.types.filter { it.unlock == level }.forEach { t -> assertTrue("$t on level $level", all.any { it.type == t }) }
            assertEquals(all.size, all.map { it.id }.toSet().size)
            assertEquals(all.size, all.map { it.name.nom }.toSet().size)
        }
    }

    @Test fun levelsGetHarder() {
        for (n in 2..8) for (d in Difficulty.entries) {
            val ls = (1..10).map { Level.of(it, n, d) }
            ls.zipWithNext().forEach { (a, b) ->
                assertTrue(b.cmdMs <= a.cmdMs)
                assertTrue(b.cells >= a.cells)
                assertTrue(b.goal >= a.goal)
                assertTrue(b.types.size >= a.types.size)
            }
            assertTrue(ls.all { it.cells <= Level.MAX_CELLS && it.damage in 1..40 })
        }
        assertTrue(Level.of(1, 3, Difficulty.EASY).cmdMs > Level.of(1, 3, Difficulty.CHAOS).cmdMs)
        assertEquals(listOf(Control.Type.BUTTON, Control.Type.TOGGLE, Control.Type.SEG, Control.Type.COVER), Level.of(1, 4, Difficulty.NORMAL).types)
        assertEquals(Control.Type.entries.size, Level.of(4, 4, Difficulty.NORMAL).types.size)
    }
}
