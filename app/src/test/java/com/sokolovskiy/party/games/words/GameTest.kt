package com.sokolovskiy.party.games.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun bank(prefix: String, n: Int = 50) = (1..n).map { Game.Word("$prefix$it", listOf("a", "b", "c", "d", "e")) }

    private val banks = mapOf(
        "basic" to bank("basic"), "medium" to bank("medium"), "food" to bank("food"),
        "male" to bank("male"), "female" to bank("female"),
        Game.KIDS_BANK to (1..20).map { Game.Word("kid$it", emptyList(), "🐱") },
    )

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(banks, Random(3))
        val ids = (1..n).map { "p$it" }
        ids.forEachIndexed { i, id -> g.add(id, "P${i + 1}") }
        return g to ids
    }

    /** Split into two teams and at the first READY. */
    private fun started(n: Int = 4, setup: Game.() -> Unit = {}): Pair<Game, List<String>> {
        val (g, ids) = game(n)
        g.setup()
        assertTrue(g.toTeams())
        assertTrue(g.startGame())
        return g to ids
    }

    private fun Game.teammateOf(pid: String) = players.values.first { it.id != pid && it.team == players[pid]!!.team }.id
    private fun Game.opponentOf(pid: String) = players.values.first { it.team != players[pid]!!.team }.id

    /** Plays a whole turn: [got] guessed, [skips] skipped, nobody gets the last word. */
    private fun Game.playTurn(now: Long, got: Int, skips: Int = 0): Long {
        assertTrue(beginTurn(now))
        repeat(got) { got() }
        repeat(skips) { skip() }
        tick(now + turnSeconds * 1000L)
        assertEquals(Game.Phase.LAST, phase)
        lastWord(-1)
        assertTrue(confirm())
        return now + turnSeconds * 1000L + 1
    }

    @Test fun needsFourPlayersAndTwoPerTeam() {
        val (g, _) = game(3)
        assertFalse(g.toTeams())
        g.add("p4", "P4")
        assertTrue(g.toTeams())
        assertEquals(Game.Phase.TEAMS, g.phase)
        assertEquals(2, g.teams.size)
        assertTrue(g.teams.all { it.members.size == 2 })
        assertTrue(g.startGame())
        assertEquals(Game.Phase.READY, g.phase)
    }

    @Test fun playersCanSwitchTeamsOnlyWhileTheyForm() {
        val (g, ids) = game(5)
        g.toTeams()
        val p = ids[0]
        val other = 1 - g.players[p]!!.team!!
        assertTrue(g.join(p, other))
        assertEquals(other, g.players[p]!!.team)
        g.join(ids.first { g.players[it]!!.team == other && it != p }, 1 - other)
        assertTrue(g.startGame())
        assertFalse(g.join(p, 1 - other))
    }

    @Test fun wordIsHiddenFromTheExplainersTeamAndInClassicFromEveryone() {
        val (g, _) = started()
        val ex = g.explainer!!
        g.beginTurn(0)
        assertTrue(g.stateJson(ex, false, 0).has("card"))
        assertFalse(g.stateJson(g.teammateOf(ex), false, 0).has("card"))
        assertFalse(g.stateJson(g.opponentOf(ex), false, 0).has("card"))
        assertFalse(g.stateJson(null, true, 0).has("card"))
        assertEquals(0, g.stateJson(ex, false, 0).getJSONObject("card").getJSONArray("taboo").length())
    }

    @Test fun tabooShowsTheCardToOpponentsWhoCanCallAFoul() {
        val (g, _) = started { taboo = true }
        val ex = g.explainer!!
        g.beginTurn(0)
        val mate = g.teammateOf(ex)
        val opp = g.opponentOf(ex)
        assertFalse(g.stateJson(mate, false, 0).has("card"))
        val card = g.stateJson(opp, false, 0).getJSONObject("card")
        assertEquals(5, card.getJSONArray("taboo").length())
        assertFalse(g.canWatch(mate))
        assertTrue(g.canWatch(opp))
        val w = g.current!!.word.w
        assertTrue(g.foul())
        assertNotEquals(w, g.current!!.word.w)
        assertEquals(-1, g.turnDelta())
    }

    @Test fun theTvBoardNeverShowsTheCard() {
        val (g, _) = started { taboo = true }
        g.beginTurn(0)
        g.got()
        val board = g.stateJson(null, false, 0)
        assertFalse(board.has("card"))
        assertEquals(1, board.getJSONObject("turn").getJSONArray("words").length()) // said words are public
        g.tick(60_000)
        assertFalse(g.stateJson(null, false, 0).has("card"))
    }

    @Test fun foulNeedsTabooMode() {
        val (g, _) = started()
        g.beginTurn(0)
        assertFalse(g.foul())
    }

    @Test fun scoringWithAndWithoutSkipPenalty() {
        val (g, _) = started()
        val team = g.turnTeam
        g.playTurn(0, got = 5, skips = 2)
        assertEquals(3, g.teams[team].score)

        val (h, _) = started { skipPenalty = false }
        h.playTurn(0, got = 5, skips = 2)
        assertEquals(5, h.teams[h.lastTurn!!.team].score)
    }

    @Test fun lastWordCanGoToTheOtherTeam() {
        val (g, _) = started()
        val team = g.turnTeam
        g.beginTurn(0)
        g.got()
        g.tick(60_000)
        assertEquals(Game.Phase.LAST, g.phase)
        assertTrue(g.lastWord(1 - team))
        assertEquals(Game.Phase.REVIEW, g.phase)
        assertEquals(1, g.turnDelta())
        g.confirm()
        assertEquals(1, g.teams[team].score)
        assertEquals(1, g.teams[1 - team].score)
    }

    @Test fun lastWordOnlyForOwnTeamWhenSwitchedOff() {
        val (g, _) = started { lastForAll = false }
        val team = g.turnTeam
        g.beginTurn(0)
        g.tick(60_000)
        assertFalse(g.lastWord(1 - team))
        assertTrue(g.lastWord(team))
        assertEquals(1, g.turnDelta())
    }

    @Test fun reviewCanFixMarks() {
        val (g, _) = started()
        g.beginTurn(0)
        g.got(); g.skip(); g.got()
        g.tick(60_000)
        g.lastWord(-1)
        assertEquals(1, g.turnDelta())
        assertTrue(g.mark(1, Game.Mark.GOT))
        assertEquals(3, g.turnDelta())
        assertFalse(g.mark(0, Game.Mark.FOUL)) // no taboo mode
        // everyone sees the finished words now
        val words = g.stateJson(g.opponentOf(g.explainer!!), false, 0).getJSONObject("turn").getJSONArray("words")
        assertEquals(3, words.length())
    }

    @Test fun turnsRotateTeamsAndExplainers() {
        val (g, _) = started()
        val first = g.explainer!!
        val t0 = g.turnTeam
        var now = g.playTurn(0, 0)
        assertNotEquals(t0, g.turnTeam)
        now = g.playTurn(now, 0)
        assertEquals(t0, g.turnTeam)
        assertNotEquals(first, g.explainer)
        assertEquals(g.teammateOf(first), g.explainer)
    }

    @Test fun winOnlyAfterAFullCircle() {
        val (g, _) = started { goal = 10 }
        val t0 = g.turnTeam
        var now = g.playTurn(0, 10)
        assertEquals(Game.Phase.READY, g.phase) // the other team still gets its turn
        now = g.playTurn(now, 3)
        assertEquals(Game.Phase.FINAL, g.phase)
        assertEquals(t0, g.winner)
    }

    @Test fun tieAtTheFinishPlaysAnotherCircle() {
        val (g, _) = started { goal = 10 }
        var now = g.playTurn(0, 10)
        now = g.playTurn(now, 10)
        assertEquals(Game.Phase.READY, g.phase)
        now = g.playTurn(now, 2)
        g.playTurn(now, 1)
        assertEquals(Game.Phase.FINAL, g.phase)
    }

    @Test fun themesDecideTheWords() {
        val (g, _) = started { toggleTheme("basic"); toggleTheme("food") }
        g.beginTurn(0)
        repeat(20) { assertTrue(g.current!!.word.w.startsWith("medium") || g.current!!.word.w.startsWith("food")); g.got() }
        val (h, _) = game()
        assertTrue(h.toggleTheme("basic"))
        assertFalse(h.toggleTheme("medium")) // the last one stays
        assertFalse(h.toggleTheme("nope"))
    }

    @Test fun battleTeamsExplainTheOtherSidesWords() {
        val (g, ids) = game()
        g.battle = true
        g.toTeams()
        assertEquals(listOf("Хлопці", "Дівчата"), g.teams.map { it.name })
        assertTrue(g.players.values.all { it.team == null })
        assertFalse(g.startGame())
        g.join(ids[0], 0); g.join(ids[1], 0); g.join(ids[2], 1)
        assertFalse(g.startGame())
        g.join(ids[3], 1)
        assertTrue(g.startGame())
        repeat(2) {
            g.beginTurn(0)
            val prefix = if (g.turnTeam == 0) "female" else "male"
            repeat(5) { assertTrue(g.current!!.word.w.startsWith(prefix)); g.got() }
            g.tick(60_000); g.lastWord(-1); g.confirm()
        }
        // a latecomer picks a side mid-game
        g.add("p5", "P5")
        assertNull(g.players["p5"]!!.team)
        assertTrue(g.join("p5", 1))
        assertFalse(g.join("p5", 0))
    }

    @Test fun usedUpDeckStartsOver() {
        val (g, _) = started { toggleTheme("food"); toggleTheme("basic"); toggleTheme("medium") }
        g.turnSeconds = 120
        g.beginTurn(0)
        repeat(120) { assertTrue(g.got()) }
        assertEquals(120, g.turnDelta())
    }

    @Test fun explainerLeavingEndsTheTurn() {
        val (g, _) = started()
        val ex = g.explainer!!
        g.beginTurn(0)
        g.got()
        g.remove(ex)
        assertEquals(Game.Phase.REVIEW, g.phase)
        assertEquals(1, g.turnDelta())
        assertTrue(g.confirm())
        // two players left in teams of one and two: still playable
        assertEquals(Game.Phase.READY, g.phase)
    }

    @Test fun explainerLeavingBeforeTheTurnPassesItToATeammate() {
        val (g, _) = started(6)
        val ex = g.explainer!!
        val team = g.turnTeam
        g.remove(ex)
        assertEquals(Game.Phase.READY, g.phase)
        assertEquals(team, g.turnTeam)
        assertNotEquals(ex, g.explainer)
    }
    @Test fun kidsModeDealsPictureWordsFromItsOwnBank() {
        val (g, _) = started { setMode("kids") }
        assertTrue(g.kids)
        val ex = g.explainer!!
        assertTrue(g.beginTurn(0))
        val card = g.stateJson(ex, false, 0).getJSONObject("card")
        assertTrue(card.getString("w").startsWith("kid"))
        assertEquals("🐱", card.getString("e"))
        assertEquals(0, card.getJSONArray("taboo").length())
        assertFalse(g.stateJson(g.teammateOf(ex), false, 0).has("card"))
        g.got()
        val words = g.stateJson(ex, false, 0).getJSONObject("turn").getJSONArray("words")
        assertEquals("🐱", words.getJSONObject(0).getString("e"))
    }

    @Test fun kidsModeIsExclusiveWithTabooAndBattle() {
        val (g, _) = game()
        g.setMode("taboo"); g.battle = true
        g.setMode("kids")
        assertTrue(g.kids); assertFalse(g.taboo); assertFalse(g.battle)
        assertEquals(10, g.goal); assertEquals(90, g.turnSeconds); assertFalse(g.skipPenalty)
        g.setMode("taboo")
        assertTrue(g.taboo); assertFalse(g.kids)
        g.setMode("classic")
        assertFalse(g.taboo); assertFalse(g.kids)
        assertFalse(g.setMode("nonsense"))
    }

    @Test fun pictureSurvivesInTheLastTurnLog() {
        val (g, _) = started { setMode("kids") }
        g.playTurn(0, got = 2)
        val l = g.stateJson(null, false, 0).getJSONObject("lastTurn").getJSONArray("words")
        assertEquals("🐱", l.getJSONObject(0).getString("e"))
    }
}
