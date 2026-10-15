package com.sokolovskiy.party.games.draw

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GameTest {

    private val bank = PromptBank((1..20).map { Prompt("Тема №$it!", adult = false) } + Prompt("Гостра", adult = true))
    private val line = Art(listOf(intArrayOf(0, 1, 10, 10, 500, 500)))

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(bank, Random(5))
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    private fun drawAll(g: Game, ids: List<String>) = ids.forEach { assertTrue(g.submit(it, line, 0)) }

    /** Everyone but the artist writes "<id> fake". */
    private fun fakeAll(g: Game) = g.players.keys.filter { it != g.artist }.forEach { assertTrue(g.writeFake(it, "$it вигадка", 0)) }

    private fun realIndex(g: Game) = g.options.indexOfFirst { it.real }
    private fun indexOf(g: Game, author: String) = g.options.indexOfFirst { author in it.authors }

    @Test fun needsThreePlayers() {
        val (g, _) = game(2)
        assertFalse(g.startGame(0))
        g.add("p3", "P3")
        assertTrue(g.startGame(0))
        assertEquals(Game.Phase.DRAW, g.phase)
        assertEquals(Game.DEFAULT_DRAW_SECONDS * 1000L, g.deadline)
    }

    @Test fun everyoneGetsADifferentPrompt() {
        val (g, ids) = game(5)
        g.startGame(0)
        assertEquals(5, ids.map { g.promptOf(it)!!.text }.toSet().size)
    }

    @Test fun cannotHandInAnEmptyDrawing() {
        val (g, _) = game()
        g.startGame(0)
        assertFalse(g.submit("p1", Art(emptyList()), 0))
        assertFalse(g.submit("p1", null, 0))
        assertTrue(g.draft("p1", line))
        assertTrue(g.submit("p1", null, 0))   // the draft is handed in
    }

    @Test fun swapOnlyOnceAndBeforeDrawing() {
        val (g, _) = game()
        g.startGame(0)
        g.draft("p2", line)
        assertFalse(g.swapPrompt("p2"))
        val before = g.promptOf("p1")!!.text
        assertTrue(g.swapPrompt("p1"))
        assertTrue(before != g.promptOf("p1")!!.text)
        assertFalse(g.swapPrompt("p1"))
    }

    @Test fun fullRoundScoring() {
        val (g, ids) = game()
        g.startGame(0)
        drawAll(g, ids)
        assertEquals(Game.Phase.FAKE, g.phase)
        val artist = g.artist!!
        assertFalse(g.writeFake(artist, "щось", 0))
        fakeAll(g)
        assertEquals(Game.Phase.VOTE, g.phase)
        assertEquals(4, g.options.size)

        val others = ids.filter { it != artist }
        val (a, b, c) = others
        assertFalse(g.vote(a, indexOf(g, a), 0))     // not for your own fake
        assertFalse(g.vote(artist, realIndex(g), 0)) // the artist doesn't vote
        g.vote(a, realIndex(g), 0)
        g.vote(b, realIndex(g), 0)
        g.vote(c, indexOf(g, a), 0)
        assertEquals(Game.Phase.RESULT, g.phase)

        assertEquals(2 * Game.ARTIST, g.players[artist]!!.score)
        assertEquals(Game.GUESS + Game.FOOL, g.players[a]!!.score)
        assertEquals(Game.GUESS, g.players[b]!!.score)
        assertEquals(0, g.players[c]!!.score)
        assertEquals(1, g.players[a]!!.fooled)
        assertEquals(1, g.players[b]!!.found)
    }

    @Test fun theTruthIsNotAFake() {
        val (g, ids) = game()
        g.startGame(0)
        drawAll(g, ids)
        val other = ids.first { it != g.artist }
        assertFalse(g.writeFake(other, "  " + g.truth!!.text.uppercase() + "!", 0))
        assertTrue(g.stateJson(other, false, 0).getJSONObject("me").optBoolean("tooTrue"))
        assertTrue(g.writeFake(other, "Інше", 0))
        assertFalse(g.stateJson(other, false, 0).getJSONObject("me").optBoolean("tooTrue"))
    }

    @Test fun identicalFakesMerge() {
        val (g, ids) = game()
        g.startGame(0)
        drawAll(g, ids)
        val (a, b, c) = ids.filter { it != g.artist }
        g.writeFake(a, "Кіт на даху", 0)
        g.writeFake(b, "кіт на даху", 0)
        g.writeFake(c, "Пес", 0)
        assertEquals(3, g.options.size)
        g.vote(c, indexOf(g, a), 0)
        assertFalse(g.vote(a, indexOf(g, b), 0))   // it's also a's own
        g.vote(a, indexOf(g, c), 0)
        g.vote(b, indexOf(g, c), 0)
        assertEquals(Game.FOOL, g.players[a]!!.score)
        assertEquals(Game.FOOL, g.players[b]!!.score)
        assertEquals(2 * Game.FOOL, g.players[c]!!.score)
    }

    @Test fun goesThroughEveryDrawingThenNextRoundThenOver() {
        val (g, ids) = game(3)
        g.setRounds(2)
        g.startGame(0)
        repeat(2) { r ->
            drawAll(g, ids)
            val artists = HashSet<String>()
            repeat(3) {
                assertEquals(Game.Phase.FAKE, g.phase)
                artists += g.artist!!
                fakeAll(g)
                g.players.keys.filter { it != g.artist }.forEach { g.vote(it, realIndex(g), 0) }
                assertFalse(g.next(ids.first { it != g.artist }, false, 0))   // only host or artist
                assertTrue(g.next(null, true, 0))
            }
            assertEquals(ids.toSet(), artists)
            assertEquals(if (r == 0) Game.Phase.DRAW else Game.Phase.OVER, g.phase)
        }
        assertEquals(2, g.round)
    }

    @Test fun timersMoveThingsAlong() {
        val (g, ids) = game()
        g.startGame(0)
        g.draft("p1", line)
        g.submit("p2", line, 0)
        assertFalse(g.tick(1000))
        assertTrue(g.tick(g.deadline!!))
        // p3 and p4 drew nothing: only two drawings
        assertEquals(Game.Phase.FAKE, g.phase)
        assertEquals(2, g.shownTotal)
        val writer = ids.first { it != g.artist }
        g.writeFake(writer, "Щось", 0)
        assertTrue(g.tick(g.deadline!!))
        assertEquals(Game.Phase.VOTE, g.phase)
        assertTrue(g.tick(g.deadline!!))
        assertEquals(Game.Phase.RESULT, g.phase)
        assertNull(g.deadline)
        assertFalse(g.tick(Long.MAX_VALUE))
    }

    @Test fun noFakesSkipsTheVote() {
        val (g, ids) = game()
        g.startGame(0)
        drawAll(g, ids)
        assertTrue(g.hurry(true, 0))
        assertEquals(Game.Phase.RESULT, g.phase)
        assertFalse(g.hurry(false, 0))
    }

    @Test fun artistLeavingSkipsTheirDrawing() {
        val (g, ids) = game(4)
        g.startGame(0)
        drawAll(g, ids)
        val first = g.artist!!
        g.remove(first, 0)
        assertEquals(Game.Phase.FAKE, g.phase)
        assertTrue(g.artist != first)
        assertEquals(2, g.shownIndex)
    }

    @Test fun tooFewPlayersCancels() {
        val (g, ids) = game(3)
        g.startGame(0)
        g.remove(ids[0], 0)
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertNotNull(g.notice)
    }

    @Test fun stateHidesSecrets() {
        val (g, ids) = game()
        g.startGame(0)
        g.draft("p1", line)
        val other = g.stateJson("p2", false, 0)
        val text = other.toString()
        assertFalse(text.contains(g.promptOf("p1")!!.text))
        assertFalse(other.getJSONObject("me").has("art"))
        assertEquals(1, g.stateJson("p1", false, 0).getJSONObject("me").getJSONArray("art").length())

        drawAll(g, ids)
        val outsider = ids.first { it != g.artist }
        assertFalse(g.stateJson(outsider, false, 0).toString().contains(g.truth!!.text))
        assertTrue(g.stateJson(g.artist, false, 0).toString().contains(g.truth!!.text))
        fakeAll(g)
        val vote = g.stateJson(outsider, true, 0)
        val opts = vote.getJSONArray("options")
        for (i in 0 until opts.length()) {
            val o = opts.getJSONObject(i)
            assertFalse(o.has("real")); assertFalse(o.has("authors"))
        }
    }

    @Test fun artParsing() {
        assertEquals(1, Art.parse(JSONArray("[[0,1,5,5]]"))!!.strokes.size)
        assertEquals(1000, Art.parse(JSONArray("[[0,1,5,5,2000,-3]]"))!!.strokes[0][4])
        assertNull(Art.parse(JSONArray("[[0,1,5]]")))
        assertNull(Art.parse(JSONArray("[[99,1,5,5]]")))
        assertNull(Art.parse(JSONArray("[[0,9,5,5]]")))
        assertNull(Art.parse(JSONArray("[5]")))
        assertNull(Art.parse(null))
    }

    @Test fun bundledPrompts() {
        val f = listOf("src/main/assets/web/draw-prompts.json", "app/src/main/assets/web/draw-prompts.json").map(::File).first { it.exists() }
        val b = PromptBank.fromJson(f.readText())
        assertTrue(b.size > 100)
    }
}
