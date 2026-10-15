package com.sokolovskiy.party.games.codenames

import com.sokolovskiy.party.games.codenames.Game.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private val bank = WordBank((1..60).map { "w$it" }, (1..40).map { "a$it" })

    private fun game(n: Int, seed: Int = 3): Pair<Game, List<String>> {
        val g = Game(bank, Random(seed))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    private fun Game.idx(pred: (Game.Card) -> Boolean) = cards.indexOfFirst { it.open == null && pred(it) }
    private fun Game.guesser(t: Int) = members(t).first { it != captains[t] }
    private fun Game.giver() = players.keys.first { clueGiver(it) }

    // ---------- setup ----------

    @Test fun modeDependsOnPlayerCount() {
        val (g, _) = game(3)
        assertEquals(Game.Mode.DUET, g.nextMode())
        g.add("p4", "P4")
        assertEquals(Game.Mode.CLASSIC, g.nextMode())
        g.setMode(Game.Mode.DUET)
        assertEquals(Game.Mode.DUET, g.nextMode())
    }

    @Test fun bankDealsDistinctWordsWithSpice() {
        val words = bank.deal(Game.Spice.MIXED, Random(1))
        assertEquals(Game.CARDS, words.toSet().size)
        assertEquals(WordBank.MIXED_ADULT, words.count { it.startsWith("a") })
        assertTrue(bank.deal(Game.Spice.CLEAN, Random(1)).none { it.startsWith("a") })
    }

    @Test fun classicKey() {
        val (g, _) = game(4)
        assertTrue(g.startGame())
        assertEquals(Game.Mode.CLASSIC, g.mode)
        val counts = g.cards.groupingBy { it.color }.eachCount()
        assertEquals(9, counts[g.teamColor(g.turn)])
        assertEquals(8, counts[g.teamColor(1 - g.turn)])
        assertEquals(7, counts[Color.NEUTRAL])
        assertEquals(1, counts[Color.ASSASSIN])
        assertTrue((0..1).all { g.captains[it] in g.members(it) })
    }

    @Test fun duetKeysOverlapLikeTheOriginal() {
        val (g, _) = game(2)
        assertTrue(g.startGame())
        for (side in 0..1) {
            val c = g.cards.groupingBy { it.keys[side] }.eachCount()
            assertEquals(9, c[Color.GREEN]); assertEquals(3, c[Color.ASSASSIN]); assertEquals(13, c[Color.NEUTRAL])
        }
        assertEquals(Game.DUET_AGENTS, g.cards.count { it.keys.contains(Color.GREEN) })
        assertEquals(3, g.cards.count { it.keys[0] == Color.GREEN && it.keys[1] == Color.GREEN })
    }

    // ---------- secrecy ----------

    @Test fun onlyCaptainsSeeTheClassicKey() {
        val (g, _) = game(4)
        g.startGame()
        val cap = g.captains[0]!!
        val agent = g.guesser(0)
        assertTrue(g.stateJson(cap, false, 0).getJSONArray("cards").getJSONObject(0).has("key"))
        assertFalse(g.stateJson(agent, false, 0).getJSONArray("cards").getJSONObject(0).has("key"))
        assertFalse(g.stateJson(null, true, 0).getJSONArray("cards").getJSONObject(0).has("key"))
    }

    @Test fun duetPlayersSeeOnlyTheirOwnKey() {
        val (g, ids) = game(2)
        g.startGame()
        for (id in ids) {
            val side = g.players[id]!!.team
            val cards = g.stateJson(id, false, 0).getJSONArray("cards")
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                assertEquals(g.cards[i].keys[side].name, c.getString("key"))
                assertFalse(c.has("k0") || c.has("k1"))
            }
        }
    }

    // ---------- classic play ----------

    @Test fun clueRules() {
        val (g, _) = game(4)
        g.startGame()
        val cap = g.captains[g.turn]!!
        assertFalse(g.submitClue(g.guesser(g.turn), "x", 1))
        assertFalse(g.submitClue(cap, g.cards[0].word.uppercase(), 1)) // a word on the table
        assertFalse(g.submitClue(cap, "  ", 1))
        assertTrue(g.submitClue(cap, "тварини", 2))
        assertEquals(3, g.guessesLeft)
        assertFalse(g.open(cap, 0)) // the captain doesn't guess
    }

    @Test fun ownAgentsLetTeamGoOnUntilLimit() {
        val (g, _) = game(4)
        g.startGame()
        val t = g.turn
        g.submitClue(g.captains[t]!!, "x", 1)
        val p = g.guesser(t)
        assertFalse(g.endTurn(p)) // must open at least one
        assertTrue(g.open(p, g.idx { it.color == g.teamColor(t) }))
        assertEquals(t, g.turn)
        assertTrue(g.open(p, g.idx { it.color == g.teamColor(t) }))
        assertEquals(1 - t, g.turn) // number + 1 used up
        assertEquals(Game.Step.CLUE, g.step)
        assertEquals(7, g.agentsLeft[t])
    }

    @Test fun wrongCardEndsTurnAndOpponentAgentCounts() {
        val (g, _) = game(4)
        g.startGame()
        val t = g.turn
        g.submitClue(g.captains[t]!!, "x", 0)
        assertNull(g.guessesLeft)
        g.open(g.guesser(t), g.idx { it.color == g.teamColor(1 - t) })
        assertEquals(1 - t, g.turn)
        assertEquals(7, g.agentsLeft[1 - t])
    }

    @Test fun assassinLoses() {
        val (g, _) = game(4)
        g.startGame()
        val t = g.turn
        g.submitClue(g.captains[t]!!, "x", 1)
        g.open(g.guesser(t), g.idx { it.color == Color.ASSASSIN })
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(1 - t, g.winner)
        assertTrue(g.stateJson(g.guesser(t), false, 0).getJSONArray("cards").getJSONObject(0).has("key"))
    }

    @Test fun lastAgentWins() {
        val (g, _) = game(4)
        g.startGame()
        val t = g.turn
        repeat(9) {
            if (g.step == Game.Step.CLUE) {
                if (g.turn != t) { g.submitClue(g.captains[g.turn]!!, "x", 1); g.open(g.guesser(g.turn), g.idx { it.color == Color.NEUTRAL }) }
                g.submitClue(g.captains[t]!!, "x", -1)
            }
            g.open(g.guesser(t), g.idx { it.color == g.teamColor(t) })
        }
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(t, g.winner)
    }

    @Test fun captainLeavingHandsOverAndTooFewCancels() {
        val (g, _) = game(5)
        g.startGame()
        val big = if (g.members(0).size == 3) 0 else 1
        val cap = g.captains[big]!!
        g.remove(cap)
        assertEquals(Game.Phase.PLAY, g.phase)
        assertTrue(g.captains[big] in g.members(big) && g.captains[big] != cap)
        g.remove(g.members(1 - big).first())
        assertEquals(Game.Phase.LOBBY, g.phase)
    }

    // ---------- duet play ----------

    @Test fun duetFlow() {
        val (g, ids) = game(2)
        g.startGame()
        val giver = g.giver()
        val side = g.players[giver]!!.team
        val partner = ids.first { it != giver }
        assertFalse(g.open(partner, 0)) // no clue yet
        assertTrue(g.submitClue(giver, "x", 2))
        assertFalse(g.open(giver, 0)) // the giver doesn't guess
        assertTrue(g.open(partner, g.idx { it.keys[side] == Color.GREEN }))
        assertEquals(1, g.found)
        assertEquals(Game.Step.GUESS, g.step)
        val bystander = g.idx { it.keys[side] == Color.NEUTRAL }
        assertTrue(g.open(partner, bystander))
        assertEquals(setOf(side), g.cards[bystander].neutralFor)
        assertEquals(Game.DUET_TURNS - 1, g.turnsLeft)
        assertEquals(1 - side, g.turn) // sides alternate
        assertTrue(g.clueGiver(partner))
        // a bystander on one key can still be guessed against the other key
        g.submitClue(partner, "y", 1)
        assertTrue(g.point(giver, bystander))
        assertEquals(bystander, g.players[giver]!!.pointing)
    }

    @Test fun duetAssassinLoses() {
        val (g, ids) = game(2)
        g.startGame()
        val giver = g.giver()
        val side = g.players[giver]!!.team
        g.submitClue(giver, "x", 1)
        g.open(ids.first { it != giver }, g.idx { it.keys[side] == Color.ASSASSIN })
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(0, g.winner)
        assertTrue(g.stateJson(ids[0], false, 0).getJSONArray("cards").getJSONObject(0).has("k1"))
    }

    @Test fun duetSuddenDeathAndWin() {
        val (g, _) = game(2)
        g.setDuetTurns(7)
        g.startGame()
        repeat(7) {
            val giver = g.giver()
            val side = g.players[giver]!!.team
            g.submitClue(giver, "x", 1)
            val guesser = g.members(1 - side).first()
            g.open(guesser, g.idx { it.keys[side] == Color.GREEN })
            g.endTurn(guesser)
        }
        assertEquals(Game.Step.SUDDEN, g.step)
        assertEquals(Game.Phase.PLAY, g.phase)
        // open every remaining agent through the side whose partner holds it
        while (g.phase == Game.Phase.PLAY) {
            val i = g.idx { it.keys.contains(Color.GREEN) }
            val side = if (g.cards[i].keys[0] == Color.GREEN) 0 else 1
            assertTrue(g.open(g.members(1 - side).first(), i))
        }
        assertEquals(1, g.winner)
        assertEquals(Game.DUET_AGENTS, g.found)
    }

    @Test fun suddenDeathMissLoses() {
        val (g, _) = game(2)
        g.setDuetTurns(7)
        g.startGame()
        repeat(7) {
            val giver = g.giver()
            val side = g.players[giver]!!.team
            g.submitClue(giver, "x", 1)
            g.open(g.members(1 - side).first(), g.idx { it.keys[side] == Color.NEUTRAL && side !in it.neutralFor })
        }
        assertEquals(Game.Step.SUDDEN, g.step)
        val p = g.members(0).first()
        g.open(p, g.idx { it.keys[1] == Color.NEUTRAL && 1 !in it.neutralFor })
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(0, g.winner)
    }

    @Test fun sideLeavingCancelsDuet() {
        val (g, ids) = game(2)
        g.startGame()
        g.remove(ids[1])
        assertEquals(Game.Phase.LOBBY, g.phase)
    }
}
