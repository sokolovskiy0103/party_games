package com.sokolovskiy.party.games.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GameTest {

    private val bank = QuestionBank(listOf(
        Question("Чисте 1", adult = false),
        Question("Чисте 2", adult = false),
        Question("Гостре 1", adult = true),
    ))

    private fun game(n: Int = 3, turns: Int = 1): Pair<Game, List<String>> {
        val g = Game(bank, Random(3))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        g.setTurns(turns)
        return g to ids
    }

    @Test fun needsThreePlayers() {
        val (g, _) = game(2)
        assertFalse(g.startGame())
        g.add("p3", "P3")
        assertTrue(g.startGame())
        assertEquals(Game.Phase.TURN, g.phase)
        assertEquals("p1", g.subject)
    }

    @Test fun votingOpensOnlyAfterSubjectCommits() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.vote("p2", true))
        assertFalse(g.commit("p2", true))
        assertTrue(g.commit("p1", false))
        assertFalse(g.commit("p1", true))
        assertFalse(g.vote("p1", true))
        assertTrue(g.vote("p2", true))
        assertTrue(g.vote("p2", false))
        assertEquals(false, g.votes["p2"])
    }

    @Test fun scoring() {
        val (g, _) = game(4)
        g.startGame()
        g.commit("p1", false)               // will lie
        g.vote("p2", false)                 // right
        g.vote("p3", true)                  // fooled
        g.vote("p4", true)                  // fooled
        assertTrue(g.allVoted())
        assertTrue(g.reveal("p1", false))
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(1, g.players["p2"]!!.score)
        assertEquals(0, g.players["p3"]!!.score)
        assertEquals(2, g.players["p1"]!!.score)
        assertEquals(2, g.players["p1"]!!.fooled)
    }

    @Test fun onlySubjectOrHostReveals() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.reveal("p1", false))  // not committed yet
        g.commit("p1", true)
        assertFalse(g.reveal("p2", false))
        assertTrue(g.reveal("p2", true))
    }

    @Test fun rotatesThroughEveryoneThenEnds() {
        val (g, _) = game(3, turns = 2)
        g.startGame()
        val order = ArrayList<String>()
        while (g.phase == Game.Phase.TURN) {
            order += g.subject!!
            g.commit(g.subject!!, true)
            g.reveal(g.subject, false)
            g.next(g.subject, false)
        }
        assertEquals(listOf("p1", "p2", "p3", "p1", "p2", "p3"), order)
        assertEquals(Game.Phase.OVER, g.phase)
        assertNull(g.subject)
    }

    @Test fun planIsSecretUntilReveal() {
        val (g, _) = game()
        g.startGame()
        g.commit("p1", false)
        assertEquals(false, g.stateJson("p1", false, 0).opt("plan"))
        assertNull(g.stateJson("p2", false, 0).opt("plan"))
        g.vote("p2", true)
        // Others' votes are hidden too.
        assertNull(g.stateJson("p3", false, 0).getJSONArray("players").getJSONObject(1).opt("vote"))
        g.reveal("p1", false)
        assertEquals(false, g.stateJson("p3", false, 0).opt("plan"))
        assertEquals(true, g.stateJson("p3", false, 0).getJSONArray("players").getJSONObject(1).opt("vote"))
    }

    @Test fun subjectLeavingSkipsTheTurn() {
        val (g, _) = game(4)
        g.startGame()
        g.remove("p1")
        assertEquals(Game.Phase.TURN, g.phase)
        assertEquals("p2", g.subject)
        assertTrue(g.votes.isEmpty())
    }

    @Test fun tooFewPlayersCancels() {
        val (g, _) = game(3)
        g.startGame()
        g.remove("p3")
        assertEquals(Game.Phase.LOBBY, g.phase)
    }

    @Test fun swapOnlyBeforeCommit() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.swapQuestion("p2", false))
        assertTrue(g.swapQuestion("p1", false))
        assertTrue(g.swapQuestion("p2", true))
        g.commit("p1", true)
        assertFalse(g.swapQuestion("p1", false))
    }

    @Test fun spiceFiltersQuestions() {
        val (g, _) = game()
        g.setSpice(Game.Spice.ADULT)
        g.startGame()
        assertTrue(g.question!!.adult)
        g.newGame()
        g.setSpice(Game.Spice.CLEAN)
        repeat(5) { g.startGame(); assertFalse(g.question!!.adult); g.newGame() }
    }

    @Test fun shippedBankIsBigAndValid() {
        val file = listOf("src/main/assets/web/truth-questions.json", "app/src/main/assets/web/truth-questions.json", "../app/src/main/assets/web/truth-questions.json")
            .map { File(it) }.first { it.exists() }
        val b = QuestionBank.fromJson(file.readText())
        assertTrue(b.size >= 300)
    }
}
