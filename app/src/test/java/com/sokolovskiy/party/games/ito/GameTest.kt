package com.sokolovskiy.party.games.ito

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private val bank = TopicBank(listOf(
        Topic("Страшно", "ні", "жах", adult = false),
        Topic("Смачно", "ні", "дуже", adult = false),
        Topic("Сексуально", "ні", "дуже", adult = true),
    ))

    private fun game(n: Int = 3): Pair<Game, List<String>> {
        val g = Game(bank, Random(7))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    /** Owner of the lowest card still in anyone's hand. */
    private fun Game.lowestOwner() = hands.values.flatten().minByOrNull { it.value }!!.owner

    /** Owner of the highest card still in anyone's hand. */
    private fun Game.highestOwner() = hands.values.flatten().maxByOrNull { it.value }!!.owner

    private fun Game.playPerfectly() { while (phase == Game.Phase.PLAY) play(lowestOwner()) }

    @Test fun needsTwoPlayers() {
        val (g, _) = game(1)
        assertFalse(g.startGame())
        g.add("p2", "P2")
        assertTrue(g.startGame())
        assertEquals(Game.Phase.PLAY, g.phase)
        assertEquals(1, g.level)
    }

    @Test fun cardsAreDistinctAndSortedAndGrowWithLevel() {
        val (g, ids) = game(4)
        g.startGame()
        assertTrue(ids.all { g.hands[it]!!.size == 1 })
        g.playPerfectly()
        g.nextLevel()
        assertEquals(2, g.level)
        assertTrue(ids.all { g.hands[it]!!.size == 2 })
        val all = g.hands.values.flatten().map { it.value }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it in 1..100 })
        assertTrue(g.hands.values.all { h -> h.map { it.value } == h.map { it.value }.sorted() })
    }

    @Test fun cardsPerPlayerAreCapped() {
        val (g, _) = game(2)
        g.startGame()
        repeat(4) { g.playPerfectly(); g.nextLevel() }
        assertEquals(5, g.level)
        assertEquals(Game.MAX_CARDS, g.dealtEach)
    }

    @Test fun stateShowsOnlyOwnNumbers() {
        val (g, ids) = game()
        g.startGame()
        g.setClue(ids[1], g.hands[ids[1]]!![0].value, "  привид  ")
        val s = g.stateJson(ids[0], false, 0)
        val mine = s.getJSONObject("me").getJSONArray("cards")
        assertEquals(g.hands[ids[0]]!![0].value, mine.getJSONObject(0).getInt("v"))
        val players = s.getJSONArray("players")
        for (i in 0 until players.length()) assertFalse(players.getJSONObject(i).has("cards"))
        val p2 = (0 until players.length()).map { players.getJSONObject(it) }.first { it.getString("id") == ids[1] }
        assertEquals("привид", p2.getJSONArray("clues").getString(0))
    }

    @Test fun correctOrderClearsLevelAndCleanLevelGivesLife() {
        val (g, _) = game()
        g.startGame()
        g.playPerfectly()
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.START_LIVES + 1, g.lives)
        assertEquals(1, g.best)
        assertTrue(g.table.none { it.missed })
    }

    @Test fun jumpingTheQueueCostsALifeAndDiscardsLowerCards() {
        val (g, _) = game()
        g.startGame()
        val jumped = g.play(g.highestOwner())!!
        assertEquals(2, jumped.size)
        assertEquals(Game.START_LIVES - 1, g.lives)
        assertEquals(listOf(true, true, false), g.table.map { it.missed })
        assertEquals(g.table.map { it.card.value }.sorted(), g.table.map { it.card.value })
        // every card is down now, so the level is over — without the bonus life
        assertEquals(Game.Phase.RESULT, g.phase)
        assertEquals(Game.START_LIVES - 1, g.lives)
    }

    @Test fun losingAllLivesEndsTheGameAndRevealsHands() {
        val (g, ids) = game(3)
        g.startGame()
        // Always let the holder of the highest card go first: mistakes every level until the lives run out.
        var guard = 0
        while (g.phase != Game.Phase.OVER && guard++ < 200) {
            if (g.phase == Game.Phase.PLAY) g.play(g.highestOwner()) else g.nextLevel()
        }
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(0, g.lives)
        assertFalse(g.nextLevel())
        val s = g.stateJson(ids[0], false, 0)
        val ps = s.getJSONArray("players")
        val revealed = (0 until ps.length()).sumOf { i -> ps.getJSONObject(i).optJSONArray("cards")?.length() ?: 0 }
        assertEquals(g.hands.values.sumOf { it.size }, revealed)
        assertTrue(g.startGame())
        assertEquals(1, g.level)
        assertEquals(Game.START_LIVES, g.lives)
    }

    @Test fun staleTapDoesNotPlayTheNextCard() {
        val (g, ids) = game(2)
        g.startGame()
        g.playPerfectly(); g.nextLevel()
        val p = ids[0]
        val first = g.hands[p]!![0].value
        assertTrue(g.play(p, first) != null)
        assertNull(g.play(p, first))
    }

    @Test fun cluesOnlyForOwnCards() {
        val (g, ids) = game()
        g.startGame()
        val other = g.hands[ids[1]]!![0].value
        assertFalse(g.setClue(ids[0], other, "чуже"))
        assertTrue(g.setClue(ids[0], g.hands[ids[0]]!![0].value, "x".repeat(200)))
        assertEquals(Game.MAX_CLUE, g.hands[ids[0]]!![0].clue.length)
    }

    @Test fun swapTopicOnlyBeforeFirstCard() {
        val (g, ids) = game()
        g.startGame()
        g.setClue(ids[0], g.hands[ids[0]]!![0].value, "щось")
        assertTrue(g.swapTopic())
        assertEquals("", g.hands[ids[0]]!![0].clue)
        g.play(g.lowestOwner())
        assertFalse(g.swapTopic())
    }

    @Test fun spiceFiltersTopics() {
        val (g, _) = game()
        g.setSpice(Game.Spice.ADULT)
        g.startGame()
        assertTrue(g.topic!!.adult)
        g.abort()
        g.setSpice(Game.Spice.CLEAN)
        repeat(5) { g.startGame(); assertFalse(g.topic!!.adult); g.abort() }
    }

    @Test fun leavingTakesCardsAndMayFinishLevel() {
        val (g, ids) = game()
        g.startGame()
        // play everyone's card except one player's, then that player leaves
        val last = g.highestOwner()
        while (g.hands.values.flatten().size > 1) g.play(g.lowestOwner())
        assertEquals(Game.Phase.PLAY, g.phase)
        g.remove(last)
        assertEquals(Game.Phase.RESULT, g.phase)
        // too few players cancels the level
        g.nextLevel()
        g.remove(ids.first { it != last && it in g.players })
        assertEquals(Game.Phase.LOBBY, g.phase)
    }

    @Test fun lateJoinerWaitsForNextLevel() {
        val (g, _) = game()
        g.startGame()
        g.add("late", "Late")
        assertFalse(g.stateJson("late", false, 0).getJSONObject("me").getBoolean("inRound"))
        g.playPerfectly()
        g.nextLevel()
        assertTrue(g.stateJson("late", false, 0).getJSONObject("me").getBoolean("inRound"))
    }
}
