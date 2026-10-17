package com.sokolovskiy.party.games.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun game(vararg names: String): Pair<Game, List<String>> {
        val g = Game(QuestionBank(mapOf("light" to listOf("Q1", "Q2", "Q3"), "spicy" to listOf("S1"))), Random(1))
        val ids = names.map { g.join(it, null).id }
        g.start()
        return g to ids
    }

    /** answers in player order; groups by FuzzyMatcher like the offline judge */
    private fun round(g: Game, ids: List<String>, vararg answers: String) {
        if (g.phase == Game.Phase.RESULTS) g.nextQuestion()
        ids.zip(answers).forEach { (id, a) -> g.submitAnswer(id, a) }
        val judged = g.closeAnswers()
        g.applyGroups(judged, FuzzyMatcher.group(judged.map { it.second }), "fuzzy")
        g.confirm()
    }

    @Test fun majorityScores() {
        val (g, ids) = game("A", "B", "C", "D", "E")
        round(g, ids, "книжка", "Книжка", "книжку", "аптечка", "праска")
        assertEquals(listOf(1, 1, 1, 0, 0), ids.map { g.players[it]!!.score })
        assertNull("two loners: pink cow stays put", g.pinkCow)
    }

    @Test fun lonerTakesPinkCow() {
        val (g, ids) = game("A", "B", "C", "D", "E")
        round(g, ids, "в машині", "машина", "в машині", "на пляжі", "машині")
        assertEquals(ids[3], g.pinkCow)
        assertEquals(ids[3], g.result!!.pinkTo)
        assertEquals(4, ids.count { g.players[it]!!.score == 1 })
    }

    @Test fun tieScoresNobody() {
        val (g, ids) = game("A", "B", "C", "D")
        round(g, ids, "кіт", "кіт", "пес", "пес")
        assertTrue(g.result!!.tie)
        assertTrue(ids.all { g.players[it]!!.score == 0 })
    }

    @Test fun pinkCowBlocksWinUntilPassed() {
        val (g, ids) = game("A", "B", "C", "D", "E")
        g.target = 2
        // A alone, the rest tie 2-2 -> nobody scores, pink cow to A
        round(g, ids, "x", "y", "y", "z", "z")
        assertEquals(ids[0], g.pinkCow)
        assertTrue(g.result!!.tie)
        // no loners -> pink cow stays with A
        round(g, ids, "a", "a", "a", "b", "b")
        assertEquals(ids[0], g.pinkCow)
        round(g, ids, "a", "a", "a", "b", "b")
        assertEquals(2, g.players[ids[0]]!!.score)
        assertEquals(Game.Phase.GAMEOVER, g.phase)
        assertEquals(setOf(ids[1], ids[2]), g.winners.toSet()) // A reached 2 but holds the pink cow
    }

    @Test fun hostMovesAnswer() {
        val (g, ids) = game("A", "B", "C")
        ids.zip(listOf("тачка", "машина", "пиво")).forEach { (id, a) -> g.submitAnswer(id, a) }
        val judged = g.closeAnswers()
        g.applyGroups(judged, FuzzyMatcher.group(judged.map { it.second }), "fuzzy")
        assertEquals(3, g.groups.size)
        val machineGroup = g.groups.indexOfFirst { ids[1] in it }
        g.move(ids[0], machineGroup)
        assertEquals(2, g.groups.size)
        g.confirm()
        assertEquals(1, g.players[ids[0]]!!.score)
        assertEquals(ids[2], g.pinkCow)
    }

    @Test fun customQuestionReplacesWithoutCountingRound() {
        val (g, _) = game("A", "B")
        assertEquals(1, g.round)
        g.customQuestion("Моє питання?")
        assertEquals(1, g.round)
        assertEquals("Моє питання?", g.question)
    }

    @Test fun rejoinKeepsScore() {
        val (g, ids) = game("A", "B", "C")
        round(g, ids, "a", "a", "b")
        val again = g.join("A2", ids[0])
        assertEquals(ids[0], again.id)
        assertEquals(1, again.score)
        assertEquals(3, g.players.size)
    }

    @Test fun duplicateNamesGetSuffix() {
        val g = Game(QuestionBank(mapOf("light" to listOf("Q"))))
        g.join("Оля", null)
        assertEquals("оля 2", g.join("оля", null).name)
    }

    @Test fun stateHidesAnswersWhileAnswering() {
        val (g, ids) = game("A", "B")
        g.submitAnswer(ids[0], "секрет")
        val s = g.stateJson(ids[1], false)
        assertFalse(s.has("groups"))
        assertFalse(s.toString().contains("секрет"))
        assertEquals("секрет", g.stateJson(ids[0], false).getJSONObject("me").getString("answer"))
    }

    @Test fun tvBoardHidesAnswersWhileAnswering() {
        val (g, ids) = game("A", "B")
        g.submitAnswer(ids[0], "секрет")
        val board = g.stateJson(null, false)
        assertFalse(board.has("groups"))
        assertFalse(board.has("me"))
        assertFalse(board.toString().contains("секрет"))
        assertEquals(1, board.getInt("answeredCount"))
    }

    @Test fun anonymousHidesNamesButHostCanStillMove() {
        val (g, ids) = game("A", "B", "C")
        g.anonymous = true
        ids.zip(listOf("тачка", "машина", "пиво")).forEach { (id, a) -> g.submitAnswer(id, a) }
        val judged = g.closeAnswers()
        g.applyGroups(judged, FuzzyMatcher.group(judged.map { it.second }), "fuzzy")
        val st = g.stateJson(ids[0], false)
        val text = st.getJSONArray("groups").toString()
        ids.forEach { assertFalse("player id leaked", text.contains(it)) }
        listOf("\"A\"", "\"B\"", "\"C\"").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("\"mine\":true"))
        // host moves by token
        val gs = st.getJSONArray("groups")
        var tachkaToken = ""; var machineGroup = -1
        for (i in 0 until gs.length()) {
            val ms = gs.getJSONObject(i).getJSONArray("members")
            for (j in 0 until ms.length()) {
                val m = ms.getJSONObject(j)
                if (m.getString("text") == "тачка") tachkaToken = m.getString("id")
                if (m.getString("text") == "машина") machineGroup = i
            }
        }
        g.move(tachkaToken, machineGroup)
        g.confirm()
        assertEquals(1, g.players[ids[0]]!!.score)
    }

    @Test fun modelMeaningBecomesGroupTitle() {
        val (g, ids) = game("A", "B", "C")
        ids.zip(listOf("тачка", "в авто", "пиво")).forEach { (id, a) -> g.submitAnswer(id, a) }
        val judged = g.closeAnswers()
        val labels = listOf("машина", "машина", "пиво")
        g.applyGroups(judged, listOf(listOf(0, 1), listOf(2)), "ai", labels)
        val first = g.stateJson(null, true).getJSONArray("groups").getJSONObject(0)
        assertEquals("машина", first.getString("label"))
        assertEquals(2, first.getJSONArray("members").length())
    }
}
