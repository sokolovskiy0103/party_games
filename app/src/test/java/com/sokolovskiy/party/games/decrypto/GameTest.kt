package com.sokolovskiy.party.games.decrypto

import com.sokolovskiy.party.games.decrypto.Game.Target.OPP
import com.sokolovskiy.party.games.decrypto.Game.Target.OWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private val bank = WordBank((1..20).map { "c$it" }, (1..20).map { "a$it" })

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(bank, Random(5))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    private fun Game.enc(t: Int) = teams[t].encryptor!!
    private fun Game.guesser(t: Int) = members(t).first { it != teams[t].encryptor }

    private fun Game.allClues() = (0..1).forEach { assertTrue(submitClues(enc(it), listOf("a", "b", "c"))) }

    private fun Game.answer(pid: String, target: Game.Target, code: List<Int>) {
        code.forEachIndexed { i, d -> assertTrue(setDigit(pid, target, i, d)) }
        assertTrue(lock(pid, target))
    }

    private fun wrong(code: List<Int>) = code.reversed().takeIf { it != code } ?: listOf(code[1], code[0], code[2])

    /** Plays one round: each team gets its own code right or wrong, and intercepts (round 2+) right or wrong. */
    private fun Game.playRound(ownOk: List<Boolean>, intercept: List<Boolean> = listOf(false, false)) {
        allClues()
        for (t in 0..1) {
            val code = teams[t].code
            answer(guesser(t), OWN, if (ownOk[t]) code else wrong(code))
        }
        if (interceptOpen()) for (t in 0..1) {
            val code = teams[1 - t].code
            answer(enc(t), OPP, if (intercept[t]) code else wrong(code))
        }
    }

    @Test fun tvBoardHidesSecretWordsAndCodes() {
        val (g, _) = game()
        assertTrue(g.startGame())
        val secret = g.teams.flatMap { it.words } + g.teams.map { it.code.joinToString("") }
        fun assertHidden(s: String) {
            assertFalse(s, s.contains("\"words\""))
            secret.filter { it.length > 1 }.forEach { assertFalse(s, s.contains("\"$it\"")) }
            g.teams.forEach { t -> t.words.forEach { assertFalse(s, s.contains("\"$it\"")) } }
        }
        assertHidden(g.stateJson(null, false, 0).toString())
        g.allClues()
        assertHidden(g.stateJson(null, false, 0).toString())
        for (t in 0..1) g.answer(g.guesser(t), OWN, g.teams[t].code)
        assertEquals(Game.Phase.REVEAL, g.phase)
        // after the reveal the codes and clues are public, so the board may show them
        assertEquals(1, g.stateJson(null, false, 0).getJSONArray("log").length())
    }

    @Test fun newcomersBalanceTeamsAndStartNeedsTwoEach() {
        val (g, _) = game(3)
        assertEquals(listOf(2, 1), (0..1).map { g.members(it).size })
        assertFalse(g.startGame())
        g.add("p4", "P4")
        assertTrue(g.startGame())
        assertEquals(Game.Phase.CLUES, g.phase)
    }

    @Test fun teamsSeeOnlyTheirWordsAndOnlyEncryptorSeesCode() {
        val (g, _) = game()
        g.startGame()
        val a = g.teams[0]; val b = g.teams[1]
        assertTrue(a.words.intersect(b.words.toSet()).isEmpty())
        val es = g.stateJson(g.enc(0), false, 0)
        assertEquals(a.words, es.getJSONArray("words").let { w -> (0 until w.length()).map { w.getString(it) } })
        assertEquals(a.code, es.getJSONArray("code").let { c -> (0 until c.length()).map { c.getInt(it) } })
        val gs = g.stateJson(g.guesser(0), false, 0)
        assertFalse(gs.has("code"))
        assertFalse(gs.toString().contains(b.words[0]))
        val host = g.stateJson(null, true, 0)
        assertFalse(host.has("words"))
        assertFalse(host.toString().contains(a.words[0]))
    }

    @Test fun codesHaveThreeDistinctDigits() {
        val (g, _) = game()
        g.startGame()
        for (t in g.teams) {
            assertEquals(3, t.code.size)
            assertEquals(3, t.code.toSet().size)
            assertTrue(t.code.all { it in 1..4 })
        }
    }

    @Test fun cluesGoPublicOnlyWhenBothTeamsAreDone() {
        val (g, ids) = game()
        g.startGame()
        assertFalse(g.submitClues(g.guesser(0), listOf("a", "b", "c")))
        assertFalse(g.submitClues(g.enc(0), listOf("a", " ", "c")))
        assertTrue(g.submitClues(g.enc(0), listOf("сонце", "b", "c")))
        assertEquals(Game.Phase.CLUES, g.phase)
        assertFalse(g.stateJson(ids[0], false, 0).toString().contains("сонце"))
        assertTrue(g.submitClues(g.enc(1), listOf("x", "y", "z")))
        assertEquals(Game.Phase.GUESS, g.phase)
        assertTrue(g.stateJson(ids[0], false, 0).toString().contains("сонце"))
    }

    @Test fun encryptorCannotGuessOwnAndNoInterceptInRoundOne() {
        val (g, _) = game()
        g.startGame()
        g.allClues()
        assertFalse(g.setDigit(g.enc(0), OWN, 0, 1))
        assertFalse(g.setDigit(g.guesser(0), OPP, 0, 1))
        assertTrue(g.setDigit(g.guesser(0), OWN, 0, 1))
        assertFalse(g.lock(g.guesser(0), OWN)) // incomplete
    }

    @Test fun draftIsSharedWithinTheTeamOnly() {
        val (g, _) = game(6)
        g.startGame()
        g.allClues()
        val (x, y) = g.members(0).filter { it != g.teams[0].encryptor }
        g.setDigit(x, OWN, 0, 3)
        val mate = g.stateJson(y, false, 0).getJSONObject("drafts").getJSONObject("own")
        assertEquals(3, mate.getJSONArray("digits").getInt(0))
        assertEquals("P${x.drop(1)}", mate.getString("by"))
        val foe = g.stateJson(g.guesser(1), false, 0).getJSONObject("drafts").getJSONObject("own")
        assertEquals(0, foe.getJSONArray("digits").getInt(0))
    }

    @Test fun roundOneScoresMissesOnly() {
        val (g, _) = game()
        g.startGame()
        g.playRound(ownOk = listOf(true, false))
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(0, g.teams[0].blacks)
        assertEquals(1, g.teams[1].blacks)
        assertTrue(g.teams.all { it.whites == 0 })
        val board = g.stateJson(null, false, 0).getJSONArray("teams").getJSONObject(0).getJSONArray("board")
        assertEquals(3, (0 until 4).sumOf { board.getJSONArray(it).length() })
    }

    @Test fun twoInterceptsWin() {
        val (g, _) = game()
        g.startGame()
        g.playRound(listOf(true, true))
        g.nextRound()
        g.playRound(listOf(true, true), intercept = listOf(true, false))
        assertEquals(1, g.teams[0].whites)
        g.nextRound()
        g.playRound(listOf(true, true), intercept = listOf(true, false))
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(0, g.winner)
        // words are revealed at the end
        assertTrue(g.stateJson(null, false, 0).getJSONArray("teams").getJSONObject(1).has("words"))
    }

    @Test fun twoMissesLose() {
        val (g, _) = game()
        g.startGame()
        g.playRound(listOf(true, false))
        g.nextRound()
        g.playRound(listOf(true, false))
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(0, g.winner)
    }

    @Test fun simultaneousEndGoesToScoreOrDraw() {
        val (g, _) = game()
        g.startGame()
        g.playRound(listOf(false, false))
        g.nextRound()
        g.playRound(listOf(false, false))
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(-1, g.winner)
    }

    @Test fun gameEndsAfterMaxRounds() {
        val (g, _) = game()
        g.startGame()
        repeat(Game.MAX_ROUNDS) {
            g.playRound(listOf(true, true))
            if (g.phase == Game.Phase.REVEAL) g.nextRound()
        }
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(-1, g.winner)
    }

    @Test fun encryptorRotates() {
        val (g, _) = game(6)
        g.startGame()
        val seen = HashSet<String>()
        repeat(3) {
            seen += g.enc(0)
            g.playRound(listOf(true, true))
            g.nextRound()
        }
        assertEquals(3, seen.size)
    }

    @Test fun hostCanForceResolveAndUnlockedCountsAsMiss() {
        val (g, _) = game()
        g.startGame()
        g.allClues()
        g.answer(g.guesser(0), OWN, g.teams[0].code)
        assertTrue(g.resolve())
        assertEquals(0, g.teams[0].blacks)
        assertEquals(1, g.teams[1].blacks)
    }

    @Test fun encryptorLeavingBeforeCluesHandsOver() {
        val (g, _) = game(6)
        g.startGame()
        val old = g.enc(0)
        g.remove(old)
        assertNotEquals(old, g.teams[0].encryptor)
        assertEquals(Game.Phase.CLUES, g.phase)
    }

    @Test fun teamTooSmallStopsTheGame() {
        val (g, _) = game()
        g.startGame()
        g.remove(g.guesser(1))
        assertEquals(Game.Phase.LOBBY, g.phase)
    }

    @Test fun switchingTeamsOnlyBetweenGames() {
        val (g, ids) = game()
        assertTrue(g.switchTeam(ids[0]))
        g.switchTeam(ids[0])
        g.startGame()
        assertFalse(g.switchTeam(ids[0]))
    }

    @Test fun spiceControlsAdultWords() {
        val (g, _) = game()
        g.setSpice(Game.Spice.CLEAN)
        g.startGame()
        assertTrue(g.teams.all { t -> t.words.all { it.startsWith("c") } })
        g.abort()
        g.setSpice(Game.Spice.ADULT)
        g.startGame()
        assertTrue(g.teams.all { t -> t.words.all { it.startsWith("a") } })
        g.abort()
        g.setSpice(Game.Spice.MIXED)
        g.startGame()
        assertTrue(g.teams.all { t -> t.words.count { it.startsWith("a") } == 2 })
    }
}
