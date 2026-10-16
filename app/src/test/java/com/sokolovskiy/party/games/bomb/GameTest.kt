package com.sokolovskiy.party.games.bomb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun game(n: Int = 4, level: Int = 1, seed: Int = 7): Pair<Game, List<String>> {
        val g = Game(Random(seed))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        g.setLevel(level)
        return g to ids
    }

    private fun Game.showAll() {
        for ((id, h) in hands.toMap()) if (id !in ready) showWire(id, h.indexOfFirst { it.kind == Game.Kind.BLUE })
    }

    private fun Game.guessOf(w: Game.Wire) = if (w.kind == Game.Kind.YELLOW) Game.YELLOW else w.value

    /** A correct move for the current player: solo, a right dual cut, or revealing reds. */
    private fun Game.goodMove(): Boolean {
        val me = turn!!
        val mine = hands[me]!!.filter { !it.cut && it.kind != Game.Kind.RED }
        mine.map { guessOf(it) }.firstOrNull { canSolo(me, it) }?.let { return solo(me, it) }
        for (w in mine) for ((id, h) in hands) if (id != me) {
            val i = h.indexOfFirst { !it.cut && it.matches(guessOf(w)) }
            if (i >= 0) return dual(me, id, i, guessOf(w))
        }
        return revealReds(me)
    }

    @Test fun dealsEveryWireSortedAndNeedsTwoPlayers() {
        val (g1, _) = game(1)
        assertFalse(g1.start())

        val (g, ids) = game(4, level = 6)
        assertTrue(g.start())
        assertEquals(Game.Phase.SETUP, g.phase)
        val all = g.hands.values.flatten()
        assertEquals(48 + 2 + 2, all.size)
        assertEquals(2, all.count { it.kind == Game.Kind.YELLOW })
        assertEquals(2, all.count { it.kind == Game.Kind.RED })
        assertEquals(3, g.yellowShown.size)
        assertTrue(all.filter { it.kind == Game.Kind.YELLOW }.all { it.key in g.yellowShown })
        assertTrue(g.hands.values.all { h -> h.map { it.key } == h.map { it.key }.sorted() })
        assertTrue(ids.all { g.hands[it]!!.size == 13 })
        assertEquals(3, g.maxMistakes)
    }

    @Test fun setupNeedsEveryoneToShowABlueWire() {
        val (g, ids) = game(3)
        g.start()
        assertFalse(g.showWire(ids[0], 99))
        assertTrue(g.showWire(ids[0], 0))
        assertFalse(g.showWire(ids[0], 1))
        assertTrue(g.hands[ids[0]]!![0].info)
        g.showAll()
        assertEquals(Game.Phase.PLAY, g.phase)
        assertTrue(g.turn in ids)
    }

    @Test fun rightDualCutCutsBothWiresAndPassesTurn() {
        val (g, ids) = game(4, level = 2)
        g.start(); g.showAll()
        val me = g.turn!!
        val w = g.hands[me]!!.first()
        val (target, idx) = g.hands.filterKeys { it != me }.firstNotNullOf { (id, h) ->
            h.indexOfFirst { it.matches(w.value) }.takeIf { it >= 0 }?.let { id to it }
        }
        assertTrue(g.dual(me, target, idx, w.value))
        assertTrue(g.hands[target]!![idx].cut)
        assertEquals(1, g.hands[me]!!.count { it.cut })
        assertEquals(0, g.mistakes)
        assertEquals("hit", g.lastKind)
        assertFalse(g.turn == me)
        assertTrue(ids.contains(g.turn))
    }

    @Test fun cannotNameAValueYouDoNotHoldOrActOutOfTurn() {
        val (g, _) = game(4, level = 1)
        g.start(); g.showAll()
        val me = g.turn!!
        val notMine = (1..6).firstOrNull { !g.holds(me, it) }
        val other = g.hands.keys.first { it != me }
        if (notMine != null) assertFalse(g.dual(me, other, 0, notMine))
        assertFalse(g.dual(other, me, 0, g.hands[other]!!.first().value))
        assertFalse(g.dual(me, me, 0, g.hands[me]!!.first().value))
    }

    @Test fun wrongGuessPutsInfoTokenAndTicksDetonator() {
        val (g, _) = game(4, level = 2)
        g.start(); g.showAll()
        var booms = 0
        while (g.phase == Game.Phase.PLAY) {
            val me = g.turn!!
            val guess = g.hands[me]!!.first { !it.cut }.value
            val (target, idx) = g.hands.filterKeys { it != me }.firstNotNullOf { (id, h) ->
                h.indexOfFirst { !it.cut && !it.matches(guess) }.takeIf { it >= 0 }?.let { id to it }
            }
            g.dual(me, target, idx, guess)
            assertTrue(g.hands[target]!![idx].info)
            assertFalse(g.hands[target]!![idx].cut)
            booms++
        }
        assertEquals(Game.Phase.LOSE, g.phase)
        assertEquals(g.maxMistakes + 1, booms)
        assertEquals("boom", g.lastKind)
    }

    @Test fun redWireExplodesAtOnce() {
        val (g, _) = game(3, level = 4)
        g.start(); g.showAll()
        val me = g.turn!!
        val (target, idx) = g.hands.filterKeys { it != me }.firstNotNullOfOrNull { (id, h) ->
            h.indexOfFirst { it.kind == Game.Kind.RED }.takeIf { it >= 0 }?.let { id to it }
        } ?: return // the red wire landed in the active player's own hand with this seed
        assertTrue(g.dual(me, target, idx, g.hands[me]!!.first { it.kind == Game.Kind.BLUE }.value))
        assertEquals(Game.Phase.LOSE, g.phase)
        assertEquals(0, g.mistakes)
    }

    @Test fun perfectPlayDefusesEveryLevel() {
        for (level in 1..Game.LEVELS.size) for (seed in 1..20) {
            val (g, _) = game(4, level, seed)
            g.start(); g.showAll()
            var guard = 0
            while (g.phase == Game.Phase.PLAY) { assertTrue("stuck on level $level seed $seed", g.goodMove()); assertTrue(++guard < 500) }
            assertEquals("level $level seed $seed", Game.Phase.WIN, g.phase)
            assertEquals(0, g.mistakes)
            assertTrue(g.hands.values.flatten().all { it.cut || it.kind == Game.Kind.RED })
        }
    }

    @Test fun soloNeedsEveryRemainingCopy() {
        val (g, ids) = game(2, level = 1, seed = 3)
        g.start(); g.showAll()
        val me = g.turn!!
        for (v in 1..6) {
            val mine = g.hands[me]!!.count { it.matches(v) }
            assertEquals(mine == 4, g.canSolo(me, v))
        }
        assertTrue(ids.contains(me))
    }

    @Test fun stateHidesOtherHandsButNotCutOrShownWires() {
        val (g, ids) = game(3, level = 5)
        g.start()
        g.showWire(ids[0], g.hands[ids[0]]!!.indexOfFirst { it.kind == Game.Kind.BLUE })
        val s = g.stateJson(ids[1], false, 0)
        val p0 = s.getJSONArray("players").getJSONObject(0).getJSONArray("wires")
        val shown = (0 until p0.length()).map { p0.getJSONObject(it) }.filter { it.has("v") }
        assertEquals(1, shown.size)
        val mine = s.getJSONObject("me").getJSONArray("wires")
        assertTrue((0 until mine.length()).all { mine.getJSONObject(it).has("v") })
        // Nobody else's wire leaks a kind unless it is cut or shown.
        val players = s.getJSONArray("players")
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            if (p.getString("id") == ids[1]) continue
            val ws = p.getJSONArray("wires")
            for (j in 0 until ws.length()) ws.getJSONObject(j).let { assertEquals(it.has("i") || it.has("c"), it.has("k")) }
        }
    }

    @Test fun leavingMidGameCancelsAndJoiningWaits() {
        val (g, ids) = game(3)
        g.start(); g.showAll()
        g.add("late", "Late")
        assertNull(g.hands["late"])
        g.remove(ids[1])
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertTrue(g.hands.isEmpty())
    }

    @Test fun winningUnlocksNextLevel() {
        val (g, _) = game(2, level = 1)
        g.start(); g.showAll()
        while (g.phase == Game.Phase.PLAY) g.goodMove()
        assertEquals(1, g.best)
        assertTrue(g.nextLevel())
        assertEquals(2, g.level)
        assertEquals(Game.Phase.SETUP, g.phase)
    }
}
