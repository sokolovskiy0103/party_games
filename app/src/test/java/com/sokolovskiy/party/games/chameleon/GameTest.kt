package com.sokolovskiy.party.games.chameleon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private val words = (1..16).map { "w$it" }

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(
            TopicBank(listOf(Topic("T1", words), Topic("T2", words.map { "x$it" }))),
            PairBank(listOf("кава" to "чай", "яблуко" to "груша", "море" to "океан")),
            Random(3),
        )
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    private fun Game.others() = inRound.filter { it != chameleonId }

    /** Plays through the ROLES and CLUES phases. */
    private fun Game.toVote() {
        inRound.toList().forEach { markReady(it) }
        while (phase == Game.Phase.CLUES) submitClue(currentClueGiver()!!, "підказка")
    }

    @Test fun needsThreePlayers() {
        val (g, _) = game(2)
        assertFalse(g.startRound())
        g.add("p3", "P3")
        assertTrue(g.startRound())
        assertEquals(Game.Phase.ROLES, g.phase)
    }

    @Test fun chameleonNeverSeesWordOrOthersRole() {
        val (g, _) = game()
        g.startRound()
        val c = g.chameleonId!!
        val cs = g.stateJson(c, false, 0)
        assertFalse(cs.has("word"))
        assertTrue(cs.getJSONObject("me").getBoolean("chameleon"))
        assertFalse(cs.has("chameleon"))
        val other = g.others().first()
        val os = g.stateJson(other, false, 0)
        assertEquals(g.word, os.getString("word"))
        assertFalse(os.getJSONObject("me").getBoolean("chameleon"))
        assertFalse(os.has("chameleon"))
        // the secret word is one of the words on the shared card
        assertTrue(g.word in os.getJSONObject("topic").getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun allReadyStartsClues() {
        val (g, ids) = game()
        g.startRound()
        ids.dropLast(1).forEach { g.markReady(it) }
        assertEquals(Game.Phase.ROLES, g.phase)
        g.markReady(ids.last())
        assertEquals(Game.Phase.CLUES, g.phase)
    }

    @Test fun cluesGoInTurnAndEndInVote() {
        val (g, ids) = game()
        g.startRound()
        ids.forEach { g.markReady(it) }
        val first = g.currentClueGiver()!!
        val wrong = ids.first { it != first }
        assertFalse(g.submitClue(wrong, "ні"))
        assertFalse(g.submitClue(first, "   "))
        assertTrue(g.submitClue(first, "  море  "))
        assertEquals("море", g.clues.first().text)
        while (g.phase == Game.Phase.CLUES) g.submitClue(g.currentClueGiver()!!, "а")
        assertEquals(Game.Phase.VOTE, g.phase)
        assertEquals(ids.size, g.clues.size)
    }

    @Test fun cannotVoteForSelf() {
        val (g, ids) = game()
        g.startRound(); g.toVote()
        assertFalse(g.castVote(ids[0], ids[0]))
        assertTrue(g.castVote(ids[0], ids[1]))
    }

    @Test fun chameleonEscapesWhenWrongPlayerAccused() {
        val (g, _) = game()
        g.startRound(); g.toVote()
        val c = g.chameleonId!!
        val scapegoat = g.others().first()
        g.inRound.forEach { v -> g.castVote(v, if (v == scapegoat) c else scapegoat) }
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.Outcome.ESCAPED, g.result!!.outcome)
        assertEquals(Game.ESCAPED_POINTS, g.players[c]!!.score)
    }

    @Test fun splitVoteMeansChameleonEscapes() {
        val (g, ids) = game(4)
        g.startRound(); g.toVote()
        g.castVote(ids[0], ids[1]); g.castVote(ids[1], ids[0]); g.castVote(ids[2], ids[3]); g.castVote(ids[3], ids[2])
        assertEquals(Game.Outcome.ESCAPED, g.result!!.outcome)
        assertNull(g.result!!.accused)
    }

    @Test fun caughtChameleonMissesGuess() {
        val (g, _) = game()
        g.startRound(); g.toVote()
        val c = g.chameleonId!!
        g.inRound.forEach { v -> if (v != c) g.castVote(v, c) }
        g.castVote(c, g.others().first())
        assertEquals(Game.Phase.GUESS, g.phase)
        // the Chameleon is revealed to everyone only by the vote, the word stays secret from them
        assertFalse(g.stateJson(c, false, 0).has("word"))
        assertEquals(c, g.stateJson(g.others().first(), false, 0).getString("accused"))
        assertFalse(g.submitGuess(g.others().first(), g.word!!)) // only the Chameleon guesses
        assertFalse(g.submitGuess(c, "нема такого слова"))
        val wrong = g.topic!!.words.first { it != g.word }
        assertTrue(g.submitGuess(c, wrong))
        assertEquals(Game.Outcome.CAUGHT, g.result!!.outcome)
        g.others().forEach { assertEquals(Game.CAUGHT_POINTS, g.players[it]!!.score) }
        assertEquals(0, g.players[c]!!.score)
    }

    @Test fun caughtChameleonGuessesRight() {
        val (g, _) = game()
        g.startRound(); g.toVote()
        val c = g.chameleonId!!
        g.inRound.forEach { v -> if (v != c) g.castVote(v, c) }
        g.castVote(c, g.others().first())
        assertTrue(g.submitGuess(c, g.word!!.uppercase()))
        assertEquals(Game.Outcome.GUESSED, g.result!!.outcome)
        assertEquals(Game.GUESSED_POINTS, g.players[c]!!.score)
    }

    @Test fun roleRevealedOnlyInResult() {
        val (g, _) = game()
        g.startRound(); g.toVote()
        val viewer = g.others().first()
        assertFalse(g.stateJson(viewer, false, 0).has("chameleon"))
        g.closeVote()
        assertEquals(Game.Phase.RESULT, g.phase)
        val s = g.stateJson(viewer, false, 0)
        assertEquals(g.chameleonId, s.getString("chameleon"))
        assertNotNull(s.getString("word"))
    }

    @Test fun chameleonLeavingCancelsRound() {
        val (g, _) = game(4)
        g.startRound()
        g.remove(g.chameleonId!!)
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertTrue(g.canStart())
    }

    @Test fun leavingDuringCluesKeepsTurnsConsistent() {
        val (g, ids) = game(5)
        g.startRound()
        ids.forEach { g.markReady(it) }
        val giver = g.currentClueGiver()!!
        g.submitClue(giver, "а")
        val next = g.currentClueGiver()!!
        val leaver = g.others().first { it != next && it != giver }
        g.remove(leaver)
        assertEquals(next, g.currentClueGiver())
        while (g.phase == Game.Phase.CLUES) g.submitClue(g.currentClueGiver()!!, "б")
        assertEquals(Game.Phase.VOTE, g.phase)
    }

    @Test fun lastVoterLeavingClosesVote() {
        val (g, _) = game(4)
        g.startRound(); g.toVote()
        val order = g.inRound.toList()
        // everyone but the last player votes (for someone who stays), then the last player leaves
        g.castVote(order[0], order[1]); g.castVote(order[1], order[0]); g.castVote(order[2], order[0])
        assertEquals(Game.Phase.VOTE, g.phase)
        g.remove(order.last())
        assertTrue(g.phase == Game.Phase.RESULT || g.phase == Game.Phase.GUESS)
    }

    @Test fun newGameResetsScores() {
        val (g, _) = game()
        g.startRound(); g.toVote(); g.closeVote()
        g.newGame()
        assertEquals(0, g.round)
        assertTrue(g.players.values.all { it.score == 0 })
    }

    @Test fun topicsDoNotRepeatUntilExhausted() {
        val bank = TopicBank(listOf(Topic("A", listOf("1", "2")), Topic("B", listOf("3", "4"))))
        val r = Random(1)
        assertEquals(setOf("A", "B"), setOf(bank.next(r).name, bank.next(r).name))
    }

    // ---------- «Схожі слова» ----------

    private fun similarGame(n: Int = 4): Pair<Game, List<String>> {
        val (g, ids) = game(n)
        assertTrue(g.setMode(Game.Mode.SIMILAR))
        return g to ids
    }

    @Test fun similarGivesChameleonADifferentWordAndNoRole() {
        val (g, ids) = similarGame()
        g.startRound()
        val c = g.chameleonId!!
        val common = g.word!!
        val odd = g.chameleonWord!!
        assertTrue(common != odd)
        assertEquals(null, g.topic)
        for (id in ids) {
            val st = g.stateJson(id, false, 0)
            assertEquals(if (id == c) odd else common, st.getString("word"))
            // nobody may learn their role from the state, the Chameleon included
            assertFalse(st.getJSONObject("me").has("chameleon"))
            assertFalse(st.has("chameleon"))
            assertFalse(st.has("topic"))
            assertFalse(st.has("chameleonWord"))
        }
    }

    @Test fun similarCaughtChameleonEndsWithoutGuess() {
        val (g, _) = similarGame()
        g.startRound(); g.toVote()
        val c = g.chameleonId!!
        g.inRound.forEach { v -> if (v != c) g.castVote(v, c) }
        g.castVote(c, g.others().first())
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.Outcome.CAUGHT, g.result!!.outcome)
        g.others().forEach { assertEquals(Game.CAUGHT_POINTS, g.players[it]!!.score) }
        val st = g.stateJson(g.others().first(), false, 0)
        assertEquals(g.chameleonId, st.getString("chameleon"))
        assertEquals(g.chameleonWord, st.getString("chameleonWord"))
    }

    @Test fun similarEscapeScoresChameleon() {
        val (g, _) = similarGame()
        g.startRound(); g.toVote()
        val c = g.chameleonId!!
        val scapegoat = g.others().first()
        g.inRound.forEach { v -> g.castVote(v, if (v == scapegoat) c else scapegoat) }
        assertEquals(Game.Outcome.ESCAPED, g.result!!.outcome)
        assertEquals(Game.ESCAPED_POINTS, g.players[c]!!.score)
    }

    @Test fun similarAllowsLongerClues() {
        val (g, ids) = similarGame()
        g.startRound()
        ids.forEach { g.markReady(it) }
        val long = "а".repeat(35)
        g.submitClue(g.currentClueGiver()!!, long)
        assertEquals(long, g.clues.first().text)
    }

    @Test fun modeCanOnlyChangeBetweenRounds() {
        val (g, _) = game()
        g.startRound()
        assertFalse(g.setMode(Game.Mode.SIMILAR))
        assertEquals(Game.Mode.CLASSIC, g.mode)
    }

    @Test fun pairsDoNotRepeatUntilExhausted() {
        val bank = PairBank(listOf("a" to "b", "c" to "d"))
        val r = Random(1)
        val seen = (1..2).map { bank.next(r).let { p -> setOf(p.common, p.odd) } }.toSet()
        assertEquals(2, seen.size)
    }
}
