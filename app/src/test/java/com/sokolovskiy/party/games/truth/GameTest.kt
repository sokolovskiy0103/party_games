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

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(bank, Random(3))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    private fun score(g: Game, id: String) = g.players[id]!!.score

    @Test fun needsFourPlayers() {
        val (g, _) = game(3)
        assertFalse(g.startGame())
        g.add("p4", "P4")
        assertTrue(g.startGame())
        assertEquals(Game.Phase.ROUND, g.phase)
        assertEquals("p1", g.reader)
    }

    @Test fun revealsWhenEveryoneAnswered() {
        val (g, _) = game()
        g.startGame()
        assertTrue(g.answer("p1", true, 2))
        assertTrue(g.answer("p1", false, 1))   // can change before the end
        g.answer("p2", true, 2)
        g.answer("p3", true, 3)
        assertEquals(Game.Phase.ROUND, g.phase)
        g.answer("p4", false, 0)
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(2, g.yesCount)
        assertEquals(4, g.answered)
        assertEquals(0, score(g, "p1"))   // 1 vs 2: off by one scores nothing
        assertEquals(1, score(g, "p2"))   // exact
        assertEquals(0, score(g, "p3"))
        assertEquals(0, score(g, "p4"))
        assertEquals(mapOf("p2" to 1), g.gains)
        assertFalse(g.answer("p1", true, 2))
    }

    @Test fun targetMustBeOffered() {
        val (g, _) = game()
        assertFalse(g.setTarget(4))
        assertTrue(g.setTarget(7))
        g.startGame()
        assertFalse(g.setTarget(3))
    }

    @Test fun rejectsBadGuess() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.answer("p1", true, -1))
        assertFalse(g.answer("p1", true, 5))
        assertTrue(g.answer("p1", true, 4))
        assertFalse(g.answer("stranger", true, 1))
    }

    @Test fun answersNeverLeak() {
        val (g, ids) = game()
        g.startGame()
        g.answer("p1", true, 2)
        val other = g.stateJson("p2", false, 0)
        assertFalse(other.toString().contains("\"yes\""))
        assertEquals(true, other.getJSONArray("players").getJSONObject(0).getBoolean("answered"))
        assertEquals(true, g.stateJson("p1", false, 0).getJSONObject("me").getBoolean("yes"))
        ids.drop(1).forEach { g.answer(it, false, 1) }
        for (v in ids) {
            val s = g.stateJson(v, v == "p1", 0)
            assertEquals(1, s.getInt("yes"))
            assertNull(s.getJSONObject("me").opt("guess"))
            val ps = s.getJSONArray("players")
            for (i in 0 until ps.length()) assertNull(ps.getJSONObject(i).opt("yes"))
        }
    }

    @Test fun hostCanRevealWithWhoAnswered() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.forceReveal(true))      // nobody answered yet
        g.answer("p1", true, 1)
        g.answer("p2", false, 1)
        assertFalse(g.forceReveal(false))
        assertTrue(g.forceReveal(true))
        assertEquals(2, g.answered)
        assertEquals(1, g.yesCount)
        assertEquals(1, score(g, "p1"))
    }

    @Test fun readerRotatesAndGameEndsAtTarget() {
        val (g, ids) = game()
        g.setTarget(3)
        g.startGame()
        val readers = ArrayList<String>()
        while (g.phase == Game.Phase.ROUND) {
            readers += g.reader!!
            ids.forEach { g.answer(it, it == "p1", if (it == "p1") 1 else 0) }   // only p1 exact
            assertFalse(g.next(ids.first { it != g.reader }, false))
            assertTrue(g.next(g.reader, false))
        }
        assertEquals(listOf("p1", "p2", "p3"), readers)
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(3, score(g, "p1"))
    }

    @Test fun swapOnlyBeforeAnyAnswer() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.swapQuestion("p2", false))
        assertTrue(g.swapQuestion("p1", false))
        assertTrue(g.swapQuestion("p2", true))
        g.answer("p3", true, 1)
        assertFalse(g.swapQuestion("p1", false))
    }

    @Test fun leavingRevealsIfOthersAreDone() {
        val (g, _) = game(5)
        g.startGame()
        listOf("p1", "p2", "p3", "p4").forEach { g.answer(it, true, 4) }
        g.remove("p5")
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(4, g.yesCount)
    }

    @Test fun tooFewPlayersCancels() {
        val (g, _) = game(4)
        g.startGame()
        g.remove("p4")
        assertEquals(Game.Phase.LOBBY, g.phase)
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

    @Test fun shippedBankIsBigAndYesNo() {
        val file = listOf("src/main/assets/web/truth-questions.json", "app/src/main/assets/web/truth-questions.json", "../app/src/main/assets/web/truth-questions.json")
            .map { File(it) }.first { it.exists() }
        val b = QuestionBank.fromJson(file.readText())
        assertTrue(b.size >= 300)
        val text = file.readText()
        assertFalse("open questions don't fit «так»/«ні»", text.contains("Назви число"))
    }
}
