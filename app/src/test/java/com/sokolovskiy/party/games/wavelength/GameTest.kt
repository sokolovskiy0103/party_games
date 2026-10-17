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

    @Test fun needsTwoPlayersAndPsychicRotates() {
        val (g, _) = game(1)
        assertFalse(g.startRound())
        val ids = listOf("p1", "p2", "p3")
        g.add("p2", "P2")
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

    // ---------- how a game ends (three or more players) ----------

    private fun missFor(t: Int) = if (t < 50) 100 else 0

    /** One classic round: [hitters] land on the target, every other guesser is far off. */
    private fun classicRound(g: Game, hitters: Set<String>) {
        assertTrue(g.startRound())
        g.submitClue("x")
        val t = g.target
        g.guessers().forEach { g.lock(it, if (it in hitters) t else missFor(t)) }
        assertEquals(Game.Phase.RESULT, g.phase)
    }

    @Test fun endlessIsTheDefaultAndNeverEnds() {
        val (g, ids) = game(3)
        assertEquals(Game.Goal.ENDLESS, g.goal)
        repeat(12) { classicRound(g, ids.toSet()) }
        assertFalse(g.over)
        assertFalse(g.tieBreak)
        assertTrue(g.players.values.all { it.score == 48 })
    }

    @Test fun pointsGoalEndsWhenSomeoneLeads() {
        val (g, ids) = game(3)
        assertTrue(g.configureGoal(Game.Goal.POINTS, points = 5))
        classicRound(g, setOf(ids[1])) // psychic p1: p1 2, p2 4, p3 0
        assertFalse(g.over)
        classicRound(g, setOf(ids[2])) // psychic p2: p1 2, p2 6, p3 4
        assertTrue(g.over)
        assertEquals(listOf(ids[1]), g.winners)
        val goal = g.stateJson(ids[0], false, 0).getJSONObject("goal")
        assertTrue(goal.getBoolean("over"))
        assertEquals(ids[1], goal.getJSONArray("winners").getString(0))
    }

    @Test fun belowTheLimitTheGameGoesOn() {
        val (g, ids) = game(3)
        g.configureGoal(Game.Goal.POINTS, points = 10)
        classicRound(g, setOf(ids[1]))
        assertFalse(g.over)
        assertFalse(g.tieBreak)
        assertTrue(g.winners.isEmpty())
    }

    @Test fun sharedTopScoreAtTheLimitPlaysAnExtraRound() {
        val (g, ids) = game(3)
        g.configureGoal(Game.Goal.POINTS, points = 5)
        classicRound(g, ids.toSet()) // everybody 4 — below the limit
        assertFalse(g.tieBreak)
        classicRound(g, ids.toSet()) // everybody 8 — a three-way tie past the limit
        assertFalse(g.over)
        assertTrue(g.tieBreak)
        assertTrue(g.stateJson(ids[0], false, 0).getJSONObject("goal").getBoolean("tieBreak"))
        classicRound(g, setOf(ids[0])) // psychic p3: p1 12, p2 8, p3 10
        assertTrue(g.over)
        assertFalse(g.tieBreak)
        assertEquals(listOf(ids[0]), g.winners)
    }

    @Test fun circlesGoalEndsAfterEveryoneWasPsychic() {
        val (g, ids) = game(3)
        g.configureGoal(Game.Goal.CIRCLES, circles = 1)
        classicRound(g, setOf(ids[1])) // psychic p1: p1 2, p2 4, p3 0
        classicRound(g, setOf(ids[0])) // psychic p2: p1 6, p2 6, p3 0
        assertFalse(g.over)
        assertFalse(g.tieBreak) // two rounds of three: the circle isn't done yet
        classicRound(g, setOf(ids[0])) // psychic p3: p1 10, p2 6, p3 2
        assertEquals(3, g.finishedRounds)
        assertTrue(g.over)
        assertEquals(listOf(ids[0]), g.winners)
    }

    @Test fun startingAfterTheEndBeginsAFreshGame() {
        val (g, ids) = game(3)
        g.configureGoal(Game.Goal.POINTS, points = 5)
        classicRound(g, setOf(ids[1]))
        classicRound(g, setOf(ids[2]))
        assertTrue(g.over)
        assertTrue(g.startRound())
        assertFalse(g.over)
        assertEquals(1, g.round)
        assertEquals(0, g.finishedRounds)
        assertEquals(ids[0], g.psychicId)
        assertTrue(g.players.values.all { it.score == 0 })
        assertEquals(Game.Goal.POINTS, g.goal) // the host's choice stays
        assertEquals(5, g.goalPoints)
    }

    @Test fun goalSettingsAreClampedAndOnlyChangeInTheLobby() {
        val (g, _) = game(3)
        g.configureGoal(Game.Goal.POINTS, points = 1000, circles = 0)
        assertEquals(Game.MAX_GOAL_POINTS, g.goalPoints)
        assertEquals(Game.MIN_GOAL_CIRCLES, g.goalCircles)
        g.configureGoal(points = 1)
        assertEquals(Game.MIN_GOAL_POINTS, g.goalPoints)
        g.startRound()
        assertFalse(g.configureGoal(Game.Goal.ENDLESS))
        assertEquals(Game.Goal.POINTS, g.goal)
    }

    @Test fun coopIgnoresTheGoal() {
        val (g, _) = game(2)
        g.configureGoal(Game.Goal.POINTS, points = 5)
        coopRound(g, 0)
        assertFalse(g.over)
        assertEquals(0, g.finishedRounds)
    }

    // ---------- co-op (two players) ----------

    /** Plays one co-op round with the guesser landing [offset] away from the target (towards the far edge). */
    private fun coopRound(g: Game, offset: Int) {
        assertTrue(g.startRound())
        g.submitClue("x")
        val guesser = g.guessers().single()
        g.lock(guesser, if (g.target < 50) g.target + offset else g.target - offset)
    }

    @Test fun twoPlayersPlayCoopWithSharedScore() {
        val (g, ids) = game(2)
        coopRound(g, 10) // 2 points
        assertTrue(g.coop)
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(2, g.coopScore)
        assertEquals(Game.COOP_ROUNDS - 1, g.coopLeft)
        assertTrue(g.players.values.all { it.score == 0 })
        coopRound(g, 30) // miss
        assertEquals(2, g.coopScore)
        assertEquals(ids[1], g.psychicId) // still alternating
        val st = g.stateJson(ids[0], false, 0)
        assertTrue(st.getBoolean("coop"))
        assertEquals(2, st.getJSONObject("series").getInt("played"))
    }

    @Test fun bullseyeGivesBonusRoundAndSeriesEnds() {
        val (g, _) = game(2)
        coopRound(g, 0)
        assertTrue(g.coopBonus)
        assertEquals(Game.COOP_ROUNDS, g.coopLeft)
        repeat(Game.COOP_ROUNDS) { coopRound(g, 30) }
        assertTrue(g.coopOver)
        assertEquals(Game.COOP_ROUNDS + 1, g.coopPlayed)
        assertEquals(4, g.coopScore)
        coopRound(g, 10) // a new series starts from zero
        assertEquals(2, g.coopScore)
        assertEquals(1, g.coopPlayed)
    }

    @Test fun thirdPlayerSwitchesBackToClassic() {
        val (g, ids) = game(2)
        coopRound(g, 10)
        g.add("p3", "P3")
        assertFalse(g.stateJson(ids[0], false, 0).getBoolean("coop"))
        assertTrue(g.stateJson(ids[0], false, 0).getJSONObject("series").getBoolean("active"))
        assertTrue(g.startRound())
        assertFalse(g.coop)
        g.submitClue("x")
        g.guessers().forEach { g.lock(it, g.target) }
        assertTrue(g.players.values.all { it.score == 4 })
        g.remove("p3")
        coopRound(g, 10)
        assertEquals(2, g.coopScore) // the old series doesn't carry over
    }
}
