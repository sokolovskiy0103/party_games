package com.sokolovskiy.party.games.speech

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GameTest {

    private fun game(n: Int = 4, adult: Deck<String>? = null): Pair<Game, List<String>> {
        val g = Game(
            Deck(listOf("Тема 1", "Тема 2", "Тема 3")),
            Deck(listOf("відпустка", "податок", "сумління", "мікрохвильовка", "кактус", "барабан")),
            Random(7),
            adult,
        )
        val ids = (1..n).map { "p$it".also { id -> g.add(id, "P$it") } }
        return g to ids
    }

    private fun score(g: Game, id: String) = g.players[id]!!.score

    /** Starts and runs the speech to REVIEW with the given guesses. */
    private fun toReview(g: Game, guesses: Map<String, List<String>>) {
        assertTrue(g.beginSpeech(g.speaker, false, 0))
        for ((pid, list) in guesses) assertTrue(g.guess(pid, list, false))
        assertTrue(g.tick(g.deadline))
        assertEquals(Game.Phase.GUESS, g.phase)
        for (l in g.listeners()) g.guess(l, g.guessesOf(l), true)
        assertEquals(Game.Phase.REVIEW, g.phase)
    }

    @Test fun needsThreePlayers() {
        val (g, _) = game(2)
        assertFalse(g.startGame())
        g.add("p3", "P3")
        assertTrue(g.startGame())
        assertEquals(Game.Phase.PREP, g.phase)
        assertEquals("p1", g.speaker)
        assertEquals(3, g.secret.size)
        assertEquals(3, g.secret.toSet().size)
    }

    @Test fun onlySpeakerSeesWordsBeforeReview() {
        val (g, _) = game()
        g.startGame()
        assertTrue(g.stateJson("p1", false, 0).has("words"))
        for (v in listOf("p2", "p3", null)) assertFalse(g.stateJson(v, v == null, 0).has("words"))
        g.beginSpeech("p1", false, 0)
        g.guess("p2", listOf("секрет"), false)
        assertFalse(g.stateJson("p3", false, 0).has("words"))
        // guesses stay private: only the author sees them, nobody else's state mentions them
        for (v in listOf("p1", "p3", null)) assertFalse(g.stateJson(v, v == null, 0).toString().contains("секрет"))
        assertTrue(g.stateJson("p2", false, 0).toString().contains("секрет"))
        g.tick(g.deadline); g.forceReview(true)
        val st = g.stateJson("p3", false, 0)
        assertEquals(3, st.getJSONArray("words").length())
        assertTrue(st.toString().contains("секрет"))
    }

    @Test fun matcherForgivesInflectionAndTypos() {
        assertTrue(Matcher.same("Відпустку", "відпустка"))
        assertTrue(Matcher.same(" ПОДАТКИ ", "податок"))
        assertTrue(Matcher.same("мікрохвильовкою", "мікрохвильовка"))
        assertTrue(Matcher.same("сумлиння", "сумління"))
        assertFalse(Matcher.same("кит", "кіт"))
        assertFalse(Matcher.same("стіл", "сіль"))
        assertFalse(Matcher.same("", "кіт"))
        assertFalse(Matcher.same("кактус", "барабан"))
    }

    @Test fun scoring() {
        val (g, _) = game(4)
        g.startGame()
        val (a, b, c) = g.secret
        toReview(g, mapOf(
            "p2" to listOf(a, b, "щось"),
            "p3" to listOf(a),
            "p4" to listOf("ніщо"),
        ))
        assertTrue(g.hit("p2", 0)); assertTrue(g.hit("p2", 1)); assertTrue(g.hit("p3", 0))
        assertFalse(g.hit("p4", 0))
        // word a: p2, p3 guessed (+1 each), p4 missed (+1 speaker)
        // word b: p2 guessed, p3, p4 missed (+2 speaker)
        // word c: nobody (+3 speaker)
        assertEquals(mapOf("p1" to 6, "p2" to 2, "p3" to 1), g.gains())
        // speaker never managed to say c
        assertTrue(g.setUsed("p1", false, 2, false))
        assertEquals(mapOf("p1" to 3, "p2" to 2, "p3" to 1), g.gains())
        // a listener can't judge
        assertFalse(g.setHit("p4", false, "p4", 1, true))
        // the speaker corrects the matcher: p4's guess counts for b
        assertTrue(g.setHit("p1", false, "p4", 1, true))
        assertEquals(mapOf("p1" to 2, "p2" to 2, "p3" to 1, "p4" to 1), g.gains())
        assertTrue(g.next("p1", false))
        assertEquals(2, score(g, "p1")); assertEquals(2, score(g, "p2"))
        assertEquals(1, score(g, "p3")); assertEquals(1, score(g, "p4"))
        assertEquals(Game.Phase.PREP, g.phase)
        assertEquals("p2", g.speaker)
    }

    @Test fun speakerCannotGuessAndGuessesAreCapped() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.guess("p2", listOf("x"), false))   // not before the speech
        g.beginSpeech("p1", false, 0)
        assertFalse(g.guess("p1", listOf("x"), false))
        assertTrue(g.guess("p2", listOf("a", " ", "b", "c", "d"), false))
        assertEquals(listOf("a", "b", "c"), g.guessesOf("p2"))
    }

    @Test fun speechEndsEarlyAndGuessTimerRunsOut() {
        val (g, _) = game(3)
        g.startGame()
        assertFalse(g.endSpeech("p2", false, 0))
        g.beginSpeech("p1", false, 1000)
        assertEquals(1000 + Game.DEFAULT_SPEECH * 1000L, g.deadline)
        assertFalse(g.tick(5000))
        assertTrue(g.endSpeech("p1", false, 5000))
        assertEquals(Game.Phase.GUESS, g.phase)
        g.guess("p2", listOf("x"), true)
        assertEquals(Game.Phase.GUESS, g.phase)    // p3 still writing
        g.guess("p2", listOf("x"), false)           // took «Готово» back
        assertFalse(g.isDone("p2"))
        assertTrue(g.tick(5000 + Game.GUESS_MS))
        assertEquals(Game.Phase.REVIEW, g.phase)
    }

    @Test fun everyoneSpeaksThenOver() {
        val (g, ids) = game(3)
        assertTrue(g.setCycles(2))
        g.startGame()
        val order = mutableListOf<String>()
        while (g.phase != Game.Phase.OVER) {
            order += g.speaker!!
            toReview(g, emptyMap())
            assertTrue(g.next(null, true))
        }
        assertEquals(ids + ids, order)
        assertNull(g.speaker)
        // nobody guessed anything: each speaker got 2 listeners × 3 words per turn
        ids.forEach { assertEquals(12, score(g, it)) }
    }

    @Test fun speakerLeavesMidSpeech() {
        val (g, _) = game(4)
        g.startGame()
        g.beginSpeech("p1", false, 0)
        g.remove("p1")
        assertEquals(Game.Phase.PREP, g.phase)
        assertEquals("p2", g.speaker)
        g.remove("p3")
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertEquals("Замало гравців — гру перервано.", g.notice)
    }

    @Test fun listenerLeavingUnblocksGuessing() {
        val (g, _) = game(4)
        g.startGame()
        g.beginSpeech("p1", false, 0)
        g.endSpeech("p1", false, 0)
        g.guess("p2", listOf("a"), true)
        g.guess("p3", listOf("b"), true)
        g.remove("p4")
        assertEquals(Game.Phase.REVIEW, g.phase)
    }

    @Test fun offlineListenersNeitherBlockNorFeedTheSpeaker() {
        val (g, _) = game(4)
        g.startGame()
        g.players["p4"]!!.connected = false
        g.beginSpeech("p1", false, 0)
        g.endSpeech("p1", false, 0)
        g.guess("p2", listOf("x"), true)
        g.guess("p3", emptyList(), true)
        assertEquals(Game.Phase.REVIEW, g.phase)   // nobody waits for p4
        // p2 and p3 missed all three words: 2 × 3 for the speaker, nothing for the absent p4
        assertEquals(mapOf("p1" to 6), g.gains())
        assertFalse(g.setHit("p1", false, "p4", 0, true))
    }

    @Test fun offlineListenerWhoGuessedStillCounts() {
        val (g, _) = game(3)
        g.startGame()
        g.beginSpeech("p1", false, 0)
        g.guess("p3", listOf(g.secret[0]), false)
        g.players["p3"]!!.connected = false
        g.endSpeech("p1", false, 0)
        assertFalse(g.checkDone())
        g.guess("p2", emptyList(), true)
        assertEquals(Game.Phase.REVIEW, g.phase)
        assertEquals(mapOf("p1" to 5, "p3" to 1), g.gains())
    }

    @Test fun hostSkipsAbsentSpeaker() {
        val (g, _) = game(3)
        g.startGame()
        assertFalse(g.skip(false))
        assertTrue(g.skip(true))
        assertEquals("p2", g.speaker)
        assertEquals(1, g.players["p1"]!!.spoke)
    }

    @Test fun swapOnlyBeforeSpeech() {
        val (g, _) = game()
        g.startGame()
        assertFalse(g.swapWords("p2", false))
        assertTrue(g.swapWords("p1", false))
        assertTrue(g.swapTopic(null, true))
        g.beginSpeech("p1", false, 0)
        assertFalse(g.swapWords("p1", false))
    }

    @Test fun settingsOnlyInLobby() {
        val (g, _) = game()
        assertFalse(g.setSpeech(45))
        assertTrue(g.setSpeech(60))
        g.startGame()
        assertFalse(g.setSpeech(120))
        assertEquals(60_000L, run { g.beginSpeech("p1", false, 0); g.deadline })
    }

    @Test fun bundledContentLoads() {
        val dir = listOf("src/main/assets/data", "app/src/main/assets/data").map(::File).first { it.isDirectory }
        val topics = JSONObject(File(dir, "speech-topics.json").readText())
        val clean = topics.getJSONArray("clean").length()
        val adult = topics.getJSONArray("adult").length()
        assertTrue(clean >= 100)
        assertTrue(adult >= 15)
        val all = (0 until clean).map { topics.getJSONArray("clean").getString(it) } +
            (0 until adult).map { topics.getJSONArray("adult").getString(it) }
        assertEquals(all.size, all.toSet().size)
        val words = org.json.JSONArray(File(dir, "words-medium.json").readText())
        assertTrue(JSONObject(words.getJSONObject(0).toString()).has("w"))
    }

    @Test fun micMarksSpokenWordsForTheSpeakerOnly() {
        val (g, _) = game(3)
        g.startGame()
        val (a, b, _) = g.secret
        assertFalse(g.heard("p1", a))                       // not before the speech
        g.beginSpeech("p1", false, 0)
        assertFalse(g.heard("p2", "я кажу $a"))             // only the speaker's phone counts
        assertFalse(g.heard("p1", "ну от такі справи"))
        assertTrue(g.mic)
        assertTrue(g.heard("p1", "і тут я згадав про ${b.uppercase()}"))
        assertTrue(g.heard[1]); assertFalse(g.heard[0])
        // the speaker sees what was caught, listeners don't even see the words
        val mine = g.stateJson("p1", false, 0).getJSONArray("words")
        assertTrue(mine.getJSONObject(1).getBoolean("heard"))
        assertFalse(g.stateJson("p2", false, 0).toString().contains("heard"))
        g.endSpeech("p1", false, 0)
        assertTrue(g.heard("p1", a))                        // a late final result right after the speech
        g.forceReview(true)
        // with the mic on, what it caught decides what was said; the speaker can still fix it
        assertEquals(listOf(true, true, false), g.used.toList())
        assertTrue(g.setUsed("p1", false, 2, true))
    }

    @Test fun withoutMicAllWordsCountAsSaid() {
        val (g, _) = game(3)
        g.startGame()
        g.beginSpeech("p1", false, 0)
        g.endSpeech("p1", false, 0)
        g.forceReview(true)
        assertFalse(g.mic)
        assertEquals(listOf(true, true, true), g.used.toList())
        assertFalse(g.stateJson("p2", false, 0).getJSONArray("words").getJSONObject(0).has("heard"))
    }

    @Test fun spokenMatchForgivesInflectionNotTypos() {
        assertTrue(Matcher.spoken("податки", "податки"))
        assertTrue(Matcher.spoken("відпустку", "відпустка"))
        assertTrue(Matcher.spoken("Мікрохвильовкою", "мікрохвильовка"))
        assertFalse(Matcher.spoken("сумлиння", "сумління"))
        assertFalse(Matcher.spoken("кит", "кіт"))
    }

    @Test fun adultTopicsMixInOnlyWhenOn() {
        val adult = Deck(listOf("Гостра 1", "Гостра 2"))
        fun share(on: Boolean): Int {
            val (g, _) = game(3, adult)
            assertTrue(g.setAdult(on))
            g.startGame()
            var n = 0
            repeat(400) { g.swapTopic(null, true); if (g.topicAdult) { n++; assertTrue(g.topic!!.startsWith("Гостра")) } }
            return n
        }
        assertEquals(0, share(false))
        val n = share(true)
        assertTrue("got $n of 400", n in 30..95)   // about 15%
        val (g, _) = game(3, adult)
        g.startGame()
        assertFalse(g.setAdult(false))                       // not mid-game
    }
}
