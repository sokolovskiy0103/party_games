package com.sokolovskiy.party.games.wavelength

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(SpectrumBank(listOf("a" to "b", "c" to "d", "e" to "f")), Random(7))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    @Test fun needsThreePlayersAndPsychicRotates() {
        val (g, ids) = game(2)
        assertFalse(g.startRound())
        g.add("p3", "P3")
        assertTrue(g.startRound())
        assertEquals(ids[0], g.psychicId)
        g.abortRound()
        g.startRound()
        assertEquals(ids[1], g.psychicId)
    }

    @Test fun targetIsSecretUntilReveal() {
        val (g, ids) = game()
        g.startRound()
        val guesser = ids[1]
        assertTrue(g.stateJson(g.psychicId, false, 0).has("target"))
        assertFalse(g.stateJson(guesser, false, 0).has("target"))
        g.submitClue("тепло")
        g.lock(guesser, 40)
        val other = g.stateJson(ids[2], false, 0)
        assertFalse(other.has("target"))
        assertFalse(other.has("result"))
        assertFalse(other.has("myGuess"))
        assertEquals(40, g.stateJson(guesser, false, 0).getInt("myGuess"))
        g.reveal()
        assertTrue(g.stateJson(guesser, false, 0).getJSONObject("result").has("target"))
    }

    @Test fun cluePhaseRules() {
        val (g, ids) = game()
        g.startRound()
        assertFalse(g.lock(ids[1], 50))
        assertFalse(g.submitClue("   "))
        assertTrue(g.swapSpectrum())
        assertTrue(g.submitClue("щось"))
        assertFalse(g.swapSpectrum())
        assertFalse(g.lock(ids[0], 50)) // the Psychic doesn't guess
        assertTrue(g.lock(ids[1], 50))
    }

    @Test fun scoringByDistanceAndPsychicAverage() {
        val (g, ids) = game(4)
        g.startRound()
        g.submitClue("x")
        val t = g.target
        val (a, b, c) = ids.drop(1)
        g.lock(a, t)                 // 4
        g.lock(b, (t + 10).coerceAtMost(100)) // 2 (distance 10, or less near the edge)
        assertEquals(Game.Phase.GUESS, g.phase)
        g.lock(c, (t - 60).coerceAtLeast(0).let { if (t - 60 < 0) 0 else it }) // far away
        assertEquals(Game.Phase.RESULT, g.phase)
        val pts = g.result!!.points
        assertEquals(4, pts[a])
        assertEquals(Game.pointsFor(Math.abs((t + 10).coerceAtMost(100) - t)), pts[b])
        assertEquals(Math.round((pts[a]!! + pts[b]!! + pts[c]!!) / 3.0).toInt(), pts[ids[0]])
        assertEquals(pts[a], g.players[a]!!.score)
    }

    @Test fun zones() {
        assertEquals(4, Game.pointsFor(0))
        assertEquals(4, Game.pointsFor(3))
        assertEquals(3, Game.pointsFor(8))
        assertEquals(2, Game.pointsFor(14))
        assertEquals(1, Game.pointsFor(22))
        assertEquals(0, Game.pointsFor(23))
    }

    @Test fun hostCanRevealEarlyAndUnlockedGetNothing() {
        val (g, ids) = game()
        g.startRound()
        g.submitClue("x")
        g.lock(ids[1], g.target)
        assertTrue(g.reveal())
        assertEquals(setOf(ids[1], ids[0]), g.result!!.points.keys)
        assertEquals(0, g.players[ids[2]]!!.score)
        assertFalse(g.reveal())
    }

    @Test fun noGuessesMeansNoPoints() {
        val (g, ids) = game()
        g.startRound()
        g.submitClue("x")
        g.reveal()
        assertEquals(0, g.players[ids[0]]!!.score)
    }

    @Test fun lastGuesserLeavingCompletesRound() {
        val (g, ids) = game(4)
        g.startRound()
        g.submitClue("x")
        g.lock(ids[1], 50)
        g.lock(ids[2], 50)
        g.remove(ids[3])
        assertEquals(Game.Phase.RESULT, g.phase)
    }

    @Test fun psychicLeavingCancelsRound() {
        val (g, ids) = game(4)
        g.startRound()
        g.remove(ids[0])
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertTrue(g.canStart())
    }

    @Test fun newGameResetsScores() {
        val (g, ids) = game()
        g.startRound()
        g.submitClue("x")
        g.lock(ids[1], g.target)
        g.reveal()
        g.newGame()
        assertEquals(0, g.round)
        assertTrue(g.players.values.all { it.score == 0 })
    }

    @Test fun bankDoesNotRepeatUntilExhausted() {
        val bank = SpectrumBank(listOf("a" to "b", "c" to "d", "e" to "f"))
        val r = Random(1)
        val seen = (1..3).map { bank.next(r).let { s -> setOf(s.left, s.right) } }.toSet()
        assertEquals(3, seen.size)
    }
}
