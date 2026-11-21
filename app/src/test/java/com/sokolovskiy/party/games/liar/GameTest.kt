package com.sokolovskiy.party.games.liar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun game(n: Int = 3, seed: Int = 1): Pair<Game, List<String>> {
        val g = Game(Random(seed))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    /** Sets everyone's dice for a deterministic round. */
    private fun Game.rig(vararg dice: List<Int>) {
        players.values.sortedBy { it.joinOrder }.forEachIndexed { i, p -> p.dice = dice[i]; p.count = dice[i].size }
    }

    private fun Game.turnBid(q: Int, f: Int) = assertTrue(bid(turn!!, q, f))

    @Test fun startDealsDiceToEveryone() {
        val (g, ids) = game()
        assertTrue(g.startGame())
        assertEquals(Game.Phase.BID, g.phase)
        for (id in ids) {
            val p = g.players[id]!!
            assertEquals(5, p.count)
            assertEquals(5, p.dice.size)
            assertTrue(p.dice.all { it in 1..6 })
        }
        assertEquals(15, g.totalDice())
        assertTrue(g.turn in ids)
    }

    @Test fun needsTwoPlayers() {
        val (g, _) = game(1)
        assertFalse(g.startGame())
        g.add("p2", "P2")
        assertTrue(g.startGame())
    }

    @Test fun bidsMustRiseAndGoInTurn() {
        val (g, _) = game()
        g.startGame()
        val first = g.turn!!
        g.turnBid(2, 3)
        assertNotEquals(first, g.turn)
        val t = g.turn!!
        assertFalse(g.bid(t, 2, 3))   // same
        assertFalse(g.bid(t, 2, 2))   // lower face
        assertFalse(g.bid(t, 1, 6))   // lower quantity
        assertFalse(g.bid(t, 16, 6))  // more dice than exist
        assertFalse(g.bid(t, 3, 1))   // ones are wild
        assertTrue(g.bid(t, 2, 4))
        assertTrue(g.bid(g.turn!!, 3, 2))
    }

    @Test fun onlyPlayerOnTurnMayBid() {
        val (g, ids) = game()
        g.startGame()
        val notTurn = ids.first { it != g.turn }
        assertFalse(g.bid(notTurn, 1, 2))
        assertTrue(g.bids.isEmpty())
    }

    @Test fun turnsGoAroundInSeatOrder() {
        val (g, ids) = game()
        g.startGame()
        val start = ids.indexOf(g.turn)
        g.turnBid(1, 2)
        assertEquals(ids[(start + 1) % 3], g.turn)
        g.turnBid(1, 3)
        assertEquals(ids[(start + 2) % 3], g.turn)
        g.turnBid(1, 4)
        assertEquals(ids[start], g.turn)
    }

    @Test fun cannotCallWithoutBid() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.call(g.turn!!, Game.Call.LIAR))
    }

    @Test fun onesAreWild() {
        val (g, _) = game()
        g.startGame()
        g.rig(listOf(1, 1, 3, 4, 5), listOf(3, 3, 6, 6, 2), listOf(1, 2, 2, 2, 5))
        assertEquals(6, g.countOf(3))
        g.setWildForTest(false)
        assertEquals(3, g.countOf(3))
    }

    private fun Game.setWildForTest(v: Boolean) {
        // Settings are locked mid-game; go through the lobby and back with the same dice.
        val dice = players.values.sortedBy { it.joinOrder }.map { it.dice }
        abort(); setWild(v); startGame(); rig(*dice.toTypedArray())
    }

    @Test fun liarCallTrueBidCostsCaller() {
        val (g, ids) = game()
        g.startGame()
        g.rig(listOf(1, 3, 3, 4, 5), listOf(3, 2, 6, 6, 2), listOf(4, 2, 2, 2, 5))  // threes: 1+1+2 wild → 4
        val bidder = g.turn!!
        g.turnBid(4, 3)
        val caller = g.turn!!
        assertTrue(g.call(caller, Game.Call.LIAR))
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(4, g.result!!.actual)
        assertEquals(caller, g.result!!.loser)
        assertEquals(4, g.players[caller]!!.count)
        assertEquals(5, g.players[bidder]!!.count)
        assertEquals(ids.toSet(), g.revealed.keys)
        g.next()
        assertEquals(caller, g.turn)  // the loser starts
        assertEquals(14, g.totalDice())
    }

    @Test fun liarCallFalseBidCostsBidder() {
        val (g, _) = game()
        g.startGame()
        g.rig(listOf(2, 2, 2, 2, 2), listOf(2, 2, 2, 2, 2), listOf(2, 2, 2, 2, 2))
        val bidder = g.turn!!
        g.turnBid(1, 6)
        g.call(g.turn!!, Game.Call.LIAR)
        assertEquals(bidder, g.result!!.loser)
        assertEquals(4, g.players[bidder]!!.count)
    }

    @Test fun exactCallRightGainsDieUpToStart() {
        val (g, _) = game()
        g.startGame()
        g.rig(listOf(5, 5, 4), listOf(2, 2, 2, 2, 2), listOf(3, 3, 3, 3, 3))
        val p1 = g.players.values.first { it.joinOrder == 0 }
        // Make sure p1 is the one who calls: bid by whoever precedes p1 in turn order.
        while (g.turn != p1.id) g.turnBid(g.bids.size + 1, 6)
        // Re-rig after the forced bids and make a fresh round where the bid before p1 is exactly right.
        g.bids.clear()
        val before = g.players.values.first { it.joinOrder == 2 }
        g.bids += Game.Bid(2, 5, before.id)
        assertTrue(g.call(p1.id, Game.Call.EXACT))
        assertEquals(p1.id, g.result!!.gainer)
        assertNull(g.result!!.loser)
        assertEquals(4, p1.count)
    }

    @Test fun exactCallWrongCostsCaller() {
        val (g, _) = game()
        g.startGame()
        g.rig(listOf(5, 5, 5, 4, 4), listOf(2, 2, 2, 2, 2), listOf(3, 3, 3, 3, 3))
        g.turnBid(2, 5)
        val caller = g.turn!!
        g.call(caller, Game.Call.EXACT)
        assertEquals(caller, g.result!!.loser)
        assertNull(g.result!!.gainer)
    }

    @Test fun exactCallCanBeDisabled() {
        val (g, _) = game()
        g.setExact(false)
        g.startGame()
        g.turnBid(1, 2)
        assertFalse(g.call(g.turn!!, Game.Call.EXACT))
        assertEquals(Game.Phase.BID, g.phase)
    }

    @Test fun eliminationAndWin() {
        val (g, ids) = game(2)
        g.setDice(3)
        g.startGame()
        var rounds = 0
        while (g.phase != Game.Phase.OVER && rounds++ < 20) {
            // The starter always bids impossibly high; the other calls and wins the round.
            g.turnBid(g.totalDice(), 6)
            assertTrue(g.call(g.turn!!, Game.Call.LIAR))
            if (g.phase == Game.Phase.REVEAL) g.next()
        }
        assertEquals(Game.Phase.OVER, g.phase)
        assertTrue(g.winner in ids)
        val loser = ids.first { it != g.winner }
        assertEquals(0, g.players[loser]!!.count)
        assertTrue(g.result!!.out)
        // A new game resets dice.
        assertTrue(g.startGame())
        assertTrue(ids.all { g.players[it]!!.count == 3 })
    }

    @Test fun eliminatedPlayersAreSkipped() {
        val (g, ids) = game()
        g.startGame()
        g.rig(listOf(2), listOf(3, 3, 3), listOf(4, 4, 4))
        while (g.turn != ids[2]) g.turnBid(g.bids.size + 1, 6)
        g.bids.clear()
        g.bids += Game.Bid(3, 6, ids[0])  // p1 bid three sixes (wrong), p3 calls
        g.call(ids[2], Game.Call.LIAR)
        assertTrue(g.result!!.out)
        assertFalse(g.players[ids[0]]!!.alive)
        g.next()
        assertEquals(ids[1], g.turn)  // the seat after the eliminated one
        assertTrue(g.players[ids[0]]!!.dice.isEmpty())
        g.turnBid(1, 2)
        assertEquals(ids[2], g.turn)
        g.turnBid(1, 3)
        assertEquals(ids[1], g.turn)
    }

    @Test fun diceStayHiddenUntilCall() {
        val (g, ids) = game()
        g.startGame()
        g.rig(listOf(6, 6, 6, 6, 6), listOf(2, 2, 2, 2, 2), listOf(3, 3, 3, 3, 3))
        val s = g.stateJson(ids[1], false, 0)
        val players = s.getJSONArray("players")
        for (i in 0 until players.length()) assertFalse(players.getJSONObject(i).has("dice"))
        assertEquals("[2,2,2,2,2]", s.getJSONObject("me").getJSONArray("dice").toString())
        assertFalse(s.toString().contains("6,6"))
        assertFalse(g.stateJson(null, true, 0).toString().contains("6,6"))  // a host without a seat
        g.turnBid(1, 2)
        g.call(g.turn!!, Game.Call.LIAR)
        val after = g.stateJson(ids[1], false, 0).getJSONArray("players")
        for (i in 0 until after.length()) assertTrue(after.getJSONObject(i).has("dice"))
    }

    @Test fun leavingMidRoundRerolls() {
        val (g, ids) = game()
        g.startGame()
        g.turnBid(1, 2)
        val r = g.round
        val leaver = ids.first { it != g.turn }
        g.remove(leaver)
        assertEquals(r + 1, g.round)
        assertTrue(g.bids.isEmpty())
        assertEquals(10, g.totalDice())
        assertTrue(g.turn in g.players.keys)
        assertTrue(g.notice!!.contains("P"))
    }

    @Test fun leavingDownToOneEndsGame() {
        val (g, ids) = game(2)
        g.startGame()
        g.remove(ids[0])
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(ids[1], g.winner)
    }

    @Test fun lateJoinerSitsOut() {
        val (g, _) = game()
        g.startGame()
        g.add("late", "Late")
        assertFalse(g.players["late"]!!.alive)
        assertEquals(15, g.totalDice())
        assertFalse(g.bid("late", 1, 2))
        g.turnBid(15, 6)
        g.call(g.turn!!, Game.Call.LIAR)
        g.next()
        assertTrue(g.players["late"]!!.dice.isEmpty())
    }

    @Test fun settingsLockedDuringGame() {
        val (g, _) = game()
        assertTrue(g.setDice(3))
        assertFalse(g.setDice(9))
        g.startGame()
        assertFalse(g.setDice(5))
        assertFalse(g.setWild(false))
        assertEquals(3, g.players.values.first().count)
    }
}
