package com.sokolovskiy.party.games.hat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private val bank = (1..60).map { "Персонаж $it" }

    private fun game(n: Int = 4, per: Int = 3): Pair<Game, List<String>> {
        val g = Game(bank, Random(7))
        g.perPlayer = per
        val ids = (1..n).map { "p$it" }
        ids.forEachIndexed { i, id -> g.add(id, "P${i + 1}") }
        return g to ids
    }

    /** Collected, split into teams and started. */
    private fun started(n: Int = 4, per: Int = 3, seconds: Int = 60): Pair<Game, List<String>> {
        val (g, ids) = game(n, per)
        g.turnSeconds = seconds
        assertTrue(g.startCollect())
        ids.forEach { g.autofill(it) }
        assertTrue(g.finishCollect())
        assertTrue(g.startGame())
        return g to ids
    }

    @Test fun collectingNeedsEveryoneToFillTheirSlots() {
        val (g, ids) = game(4, 2)
        assertFalse(g.addCard(ids[0], "Шрек")) // not collecting yet
        g.startCollect()
        assertEquals(Game.Phase.COLLECT, g.phase)
        assertTrue(g.addCard(ids[0], "  Шрек  "))
        assertEquals("Шрек", g.cards.last().name)
        assertFalse(g.addCard(ids[1], "шрек")) // duplicate, any case
        assertTrue(g.stateJson(ids[1], false, 0).has("err"))
        assertTrue(g.addCard(ids[0], "Гаррі Поттер"))
        assertFalse(g.addCard(ids[0], "Третій")) // over the limit
        assertFalse(g.finishCollect())
        g.autofillAll()
        assertTrue(g.allDone())
        assertEquals(8, g.cards.size)
        assertTrue(g.finishCollect())
        assertEquals(Game.Phase.TEAMS, g.phase)
    }

    @Test fun ownCardsArePrivate() {
        val (g, ids) = game()
        g.startCollect()
        g.addCard(ids[0], "Шрек")
        val mine = g.stateJson(ids[0], false, 0).getJSONArray("myCards")
        val theirs = g.stateJson(ids[1], false, 0).getJSONArray("myCards")
        assertEquals(1, mine.length())
        assertEquals(0, theirs.length())
        assertFalse(g.stateJson(ids[1], false, 0).toString().contains("Шрек"))
        assertTrue(g.removeCard(ids[0], mine.getJSONObject(0).getInt("id")))
        assertTrue(g.cards.isEmpty())
    }

    @Test fun autoPoolSkipsCollecting() {
        val (g, _) = game(4, 4)
        g.autoPool = true
        g.startCollect()
        assertEquals(Game.Phase.TEAMS, g.phase)
        assertEquals(16, g.cards.size)
        assertEquals(16, g.cards.map { it.name }.distinct().size)
    }

    @Test fun teamsArePairsByDefaultAndCoverEveryone() {
        val (g, ids) = game(6)
        g.startCollect(); g.autofillAll(); g.finishCollect()
        assertEquals(3, g.teams.size)
        assertTrue(g.teams.all { it.members.size == 2 })
        assertEquals(ids.toSet(), g.teams.flatMap { it.members }.toSet())
        g.setTeamCount(2)
        assertEquals(2, g.teams.size)
        val p = ids[0]
        val from = g.players[p]!!.team!!
        g.move(p)
        assertTrue(p in g.teams[(from + 1) % 2].members)
        assertFalse(p in g.teams[from].members)
    }

    @Test fun teamsAlternateAndExplainersRotate() {
        val (g, _) = started(4, 5)
        val seen = mutableListOf<Pair<Int, String>>()
        repeat(4) {
            assertEquals(Game.Phase.READY, g.phase)
            seen += g.turnTeam to g.explainer!!
            g.beginTurn(0)
            g.got(1000)
            g.tick(60_000)
            g.lastCard(false)
        }
        assertEquals(listOf(0, 1, 0, 1), seen.map { it.first })
        // both members of team 0 explain in turn
        assertTrue(seen[0].second != seen[2].second)
        assertTrue(seen[1].second != seen[3].second)
    }

    @Test fun cardVisibleToExplainerAndOpponentsOnly() {
        val (g, _) = started()
        g.beginTurn(0)
        val team = g.teams[g.turnTeam]
        val mate = team.members.first { it != g.explainer }
        val opponent = g.teams.first { it.idx != g.turnTeam }.members.first()
        assertTrue(g.stateJson(g.explainer, false, 0).has("card"))
        assertTrue(g.stateJson(opponent, false, 0).has("card"))
        assertFalse(g.stateJson(mate, false, 0).has("card"))
        assertFalse(g.stateJson(null, true, 0).has("card"))
    }

    @Test fun guessedLeaveTheHatSkippedReturn() {
        val (g, _) = started(4, 3)
        val total = g.cards.size
        g.beginTurn(0)
        val first = g.current!!
        g.skip(1000)
        assertTrue(first in g.pool)
        assertTrue(g.current != first)
        g.got(2000)
        assertEquals(total - 1, g.pool.size + 1) // one guessed, one on screen
        assertEquals(1, g.teams[g.turnTeam].score[0])
        g.undo(3000)
        assertEquals(0, g.teams[g.turnTeam].score[0])
        assertEquals(total, g.pool.size + 1)
    }

    @Test fun lastCardAfterTimeout() {
        val (g, _) = started()
        g.beginTurn(0)
        assertFalse(g.tick(59_999))
        assertTrue(g.tick(60_000))
        assertEquals(Game.Phase.TURN_END, g.phase)
        val team = g.turnTeam
        g.lastCard(true)
        assertEquals(1, g.teams[team].score[0])
        assertEquals(Game.Phase.READY, g.phase)
        assertTrue(g.turnTeam != team)
    }

    @Test fun emptyingTheHatCarriesTimeToTheSameTeamNextRound() {
        val (g, _) = started(4, 2, 60) // 8 cards
        g.beginTurn(0)
        val team = g.turnTeam
        val ex = g.explainer
        repeat(8) { i -> g.got(1000L * (i + 1)) } // empties the hat at 8 s
        assertEquals(Game.Phase.ROUND_END, g.phase)
        assertEquals(8, g.teams[team].score[0])
        val carry = g.stateJson(null, true, 0).getJSONObject("carry")
        assertEquals(52_000L, carry.getLong("ms"))
        g.nextRound()
        assertEquals(2, g.round)
        assertEquals(team, g.turnTeam)
        assertEquals(ex, g.explainer)
        assertTrue(g.bonusTurn)
        assertEquals(52_000L, g.turnMs)
        g.beginTurn(100_000)
        g.tick(152_000)
        g.lastCard(false)
        // after the bonus turn the other team goes
        assertTrue(g.turnTeam != team)
        assertFalse(g.bonusTurn)
        assertEquals(60_000L, g.turnMs)
    }

    @Test fun tinyLeftoverIsNotCarried() {
        val (g, _) = started(4, 2, 20)
        g.beginTurn(0)
        repeat(8) { i -> g.got(2_000L * (i + 1) + 2_000) } // last at 18 s, 2 s left
        assertEquals(Game.Phase.ROUND_END, g.phase)
        assertNull(g.carry)
    }

    @Test fun threeRoundsThenFinalWithStats() {
        val (g, ids) = started(4, 2)
        var t = 0L
        for (r in 1..3) {
            assertEquals(r, g.round)
            while (g.phase != Game.Phase.ROUND_END && g.phase != Game.Phase.FINAL) {
                when (g.phase) {
                    Game.Phase.READY -> g.beginTurn(t)
                    Game.Phase.TURN -> {
                        // the first card on screen is slow, the rest are quick
                        t += if (g.current == g.cards[0].id) 20_000 else 2_000
                        if (!g.tick(t)) g.got(t)
                    }
                    Game.Phase.TURN_END -> g.lastCard(false)
                    else -> error("unexpected ${g.phase}")
                }
            }
            if (r < 3) g.nextRound()
        }
        assertEquals(Game.Phase.FINAL, g.phase)
        assertEquals(8 * 3, g.teams.sumOf { it.total })
        val stats = g.statsJson()
        val cards = stats.getJSONArray("cards")
        assertEquals(8, cards.length())
        assertEquals(g.cards[0].name, cards.getJSONObject(0).getString("name")) // hardest first
        val players = stats.getJSONArray("players")
        assertEquals(24, (0 until players.length()).sumOf { players.getJSONObject(it).getInt("explained") })
        assertNotNull(g.stateJson(ids[0], false, t).optJSONObject("stats"))
    }

    @Test fun explainerLeavingEndsTheirTurn() {
        val (g, _) = started(6, 2)
        g.beginTurn(0)
        val ex = g.explainer!!
        val team = g.turnTeam
        val total = g.cards.size
        g.remove(ex)
        assertEquals(Game.Phase.READY, g.phase)
        assertTrue(g.turnTeam != team)
        assertEquals(total, g.pool.size)
    }

    @Test fun lateJoinerGoesToSmallestTeam() {
        val (g, _) = started(5, 2)
        val small = g.teams.minBy { it.members.size }.idx
        g.add("late", "Late")
        assertEquals(small, g.players["late"]!!.team)
    }

    @Test fun newGameResets() {
        val (g, _) = started()
        g.beginTurn(0); g.got(1000)
        g.newGame()
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertTrue(g.cards.isEmpty())
        assertTrue(g.teams.isEmpty())
        assertEquals(0, g.round)
    }
}
