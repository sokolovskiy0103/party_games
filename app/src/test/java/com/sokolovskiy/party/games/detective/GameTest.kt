package com.sokolovskiy.party.games.detective

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GameTest {

    private fun caseOne() = Case(
        title = "Тестова справа",
        victim = "Жертва",
        place = "Місце",
        intro = "Інтро",
        suspects = listOf(
            Suspect("A", "спадок", "у бібліотеці", "келих вина", "о 22:00"),
            Suspect("B", "ревнощі", "у саду", "міцна мотузка", "о 22:30"),
            Suspect("C", "помста", "на кухні", "пляшечка отрути", "о 22:30"),
            Suspect("D", "помста", "у бібліотеці", "пляшечка отрути", "о 23:00"),
            Suspect("E", "спадок", "у саду", "келих вина", "о 23:00"),
            Suspect("F", "ревнощі", "на кухні", "міцна мотузка", "о 22:00"),
        ),
    )

    private fun game(n: Int = 4): Pair<Game, List<String>> {
        val g = Game(CaseBank(listOf(caseOne())), Random(42))
        val ids = (1..n).map { g.join("P$it").id }
        return g to ids
    }

    private fun myClueTexts(s: JSONObject): List<String> {
        val arr = s.optJSONArray("clues") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("text") }
    }

    private fun publicClueTexts(s: JSONObject): List<String> {
        val arr = s.optJSONArray("publicClues") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test fun needsTwoDetectives() {
        val (g, _) = game(1)
        assertFalse(g.startCase())
        g.join("P2")
        assertTrue(g.startCase())
        assertEquals(Game.Phase.BRIEF, g.phase)
    }

    @Test fun everyDetectiveGetsTestimony() {
        for (n in 2..6) {
            val (g, ids) = game(n)
            assertTrue(g.startCase())
            assertEquals(n, g.inRound.size)
            ids.forEach { assertTrue("$it has no clue", g.cluesFor(it).isNotEmpty()) }
        }
    }

    @Test fun bigPartyStillGetsTestimony() {
        val (g, ids) = game(14)
        assertTrue(g.startCase())
        assertEquals(14, g.inRound.size)
        ids.forEach { assertTrue("$it has no clue", g.cluesFor(it).isNotEmpty()) }
    }

    @Test fun testimonyStaysPrivateAndCulpritStaysHidden() {
        val (g, ids) = game(4)
        g.startCase()
        val a = g.stateJson(ids[0], false, 0)
        assertFalse(a.has("culprit"))
        assertFalse(a.has("result"))
        val mine = myClueTexts(a).toSet()
        assertEquals(g.cluesFor(ids[0]).map { it.text }.toSet(), mine)
        for (other in ids.drop(1)) {
            g.cluesFor(other).forEach {
                assertFalse("another detective's clue leaked", it.text in mine)
                assertFalse("another detective's clue leaked publicly", it.text in publicClueTexts(a))
            }
        }
        val suspects = a.getJSONArray("suspects")
        (0 until suspects.length()).forEach {
            assertFalse(suspects.getJSONObject(it).has("culprit"))
        }
    }

    @Test fun theFourPositiveCluesAreAlwaysDealt() {
        val (g, _) = game(5)
        g.startCase()
        val culprit = g.case!!.suspects[g.culpritIndex]
        val all = g.inRound.flatMap { g.cluesFor(it).map { c -> c.text } }
        assertTrue("Мотив злочинця — ${culprit.motive}." in all)
        assertTrue("Злочинця бачили ${culprit.where}." in all)
        assertTrue("При злочинці знайшли — ${culprit.item}." in all)
        assertTrue("Злочинця бачили ${culprit.time}." in all)
        assertEquals(
            listOf(culprit),
            g.case!!.suspects.filter {
                it.motive == culprit.motive && it.where == culprit.where &&
                    it.item == culprit.item && it.time == culprit.time
            },
        )
    }

    @Test fun allReadyBeginsInvestigation() {
        val (g, ids) = game(3)
        g.startCase()
        g.markReady(ids[0])
        g.markReady(ids[1])
        assertEquals(Game.Phase.BRIEF, g.phase)
        g.markReady(ids[2])
        assertTrue(g.allReady())
        assertTrue(g.begin(5_000))
        assertEquals(Game.Phase.INVESTIGATION, g.phase)
        assertEquals(305_000, g.deadline)
    }

    @Test fun sharingMovesClueToThePublicBoard() {
        val (g, ids) = game(4)
        g.startCase()
        val mine = g.cluesFor(ids[0]).first()
        assertTrue(g.share(ids[0], mine.id))
        assertTrue(g.cluesFor(ids[0]).none { it.id == mine.id })
        val other = g.stateJson(ids[1], false, 0)
        assertTrue(mine.text in publicClueTexts(other))
        assertFalse("cannot share somebody else's clue", g.share(ids[0], g.cluesFor(ids[1]).first().id))
    }

    @Test fun leavingDetectivePublishesTheirTestimony() {
        val (g, ids) = game(4)
        g.startCase()
        val texts = g.cluesFor(ids[3]).map { it.text }
        g.remove(ids[3])
        val st = g.stateJson(ids[0], false, 0)
        texts.forEach { assertTrue(it in publicClueTexts(st)) }
    }

    @Test fun leavingDuringBriefWithTooFewDetectivesCancelsCase() {
        val (g, ids) = game(2)
        g.startCase()
        g.remove(ids[0])
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertNotNull(g.notice)
    }

    @Test fun lateJoinerHelpsButDoesNotVote() {
        val (g, _) = game(3)
        g.startCase()
        val late = g.join("Пізній").id
        assertFalse(late in g.inRound)
        assertTrue(g.cluesFor(late).isEmpty())
        val st = g.stateJson(late, false, 0)
        assertFalse(st.getJSONObject("me").getBoolean("inRound"))
        assertEquals(0, st.getJSONArray("clues").length())
        g.begin(0)
        g.callVote(0)
        assertFalse(g.castVote(late, g.culpritIndex, 1_000))
    }

    @Test fun correctAccusationSolvesTheCase() {
        val (g, ids) = game(3)
        g.startCase()
        g.begin(0)
        g.callVote(0)
        ids.forEachIndexed { i, pid -> assertTrue(g.castVote(pid, g.culpritIndex, 1_000L + i)) }
        assertEquals(Game.Phase.RESULT, g.phase)
        val r = g.result!!
        assertTrue(r.won)
        assertEquals("caught", r.reason)
        assertEquals(g.culpritIndex, r.culprit)
        assertEquals(1, r.attemptsUsed)
        assertEquals(1, g.solved)
        assertEquals(0, g.failed)
        val st = g.stateJson(ids[0], false, 2_000)
        assertEquals(g.culpritIndex, st.getInt("culprit"))
        assertTrue(st.has("result"))
        assertEquals(g.inRound.sumOf { g.cluesFor(it).size }, publicClueTexts(st).size)
    }

    @Test fun wrongAccusationCostsOneAttemptAndClearsTheInnocent() {
        val (g, ids) = game(3)
        g.startCase()
        g.begin(0)
        g.callVote(0)
        val wrong = g.case!!.suspects.indices.first { it != g.culpritIndex }
        ids.forEachIndexed { i, pid -> g.castVote(pid, wrong, 1_000L * (i + 1)) }
        assertEquals(Game.Phase.INVESTIGATION, g.phase)
        assertEquals(1, g.attemptsLeft)
        assertTrue(wrong in g.cleared)
        assertEquals(3_000L + 90_000, g.deadline)
        assertNotNull(g.notice)

        assertTrue(g.callVote(g.deadline))
        val wrong2 = g.voteCandidates().first { it != g.culpritIndex }
        ids.forEachIndexed { i, pid -> g.castVote(pid, wrong2, g.deadline + 1_000L * (i + 1)) }
        assertEquals(Game.Phase.RESULT, g.phase)
        assertFalse(g.result!!.won)
        assertEquals("wrong", g.result!!.reason)
        assertEquals(2, g.result!!.attemptsUsed)
        assertEquals(1, g.failed)
    }

    @Test fun splitVoteLosesTheCase() {
        val (g, ids) = game(2)
        g.startCase()
        g.begin(0)
        g.callVote(0)
        g.castVote(ids[0], 0, 1_000)
        g.castVote(ids[1], 1, 2_000)
        assertEquals(Game.Phase.RESULT, g.phase)
        assertFalse(g.result!!.won)
        assertEquals("split", g.result!!.reason)
    }

    @Test fun investigationTimeoutOpensVoteAndVoteTimeoutLoses() {
        val (g, _) = game(2)
        g.investigationSeconds = 120
        g.startCase()
        g.begin(0)
        assertFalse(g.tick(119_999))
        assertTrue(g.tick(120_000))
        assertEquals(Game.Phase.VOTE, g.phase)
        assertEquals(180_000, g.deadline)
        assertFalse(g.tick(179_999))
        assertTrue(g.tick(180_000))
        assertEquals(Game.Phase.RESULT, g.phase)
        assertFalse(g.result!!.won)
        assertEquals("split", g.result!!.reason)
    }

    @Test fun teamCanCrossOutSuspectsTogether() {
        val (g, ids) = game(2)
        g.startCase()
        assertTrue(g.toggleMark(2))
        val st = g.stateJson(ids[0], false, 0)
        assertEquals(1, st.getJSONArray("marked").length())
        assertTrue(st.getJSONArray("suspects").getJSONObject(2).getBoolean("marked"))
        assertTrue(g.toggleMark(2))
        assertEquals(0, g.stateJson(ids[0], false, 0).getJSONArray("marked").length())
        assertFalse(g.toggleMark(99))
    }

    @Test fun voteTargetsAreNotVisibleToOthers() {
        val (g, ids) = game(3)
        g.startCase()
        g.begin(0)
        g.callVote(0)
        g.castVote(ids[0], 2, 1_000)
        val other = g.stateJson(ids[1], false, 1_000)
        assertFalse(other.has("myVote"))
        assertFalse(other.toString().contains("\"votes\""))
        assertEquals(2, g.stateJson(ids[0], false, 1_000).getInt("myVote"))
    }

    @Test fun abortAndNewGameResetTheTable() {
        val (g, _) = game(3)
        g.startCase()
        g.begin(0)
        g.abort()
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertNotNull(g.notice)
        assertFalse(g.stateJson(null, true, 0).has("case"))
    }

    @Test fun shippedCasesArePlayable() {
        val file = listOf(
            "src/main/assets/data/detective-cases.json",
            "app/src/main/assets/data/detective-cases.json",
            "../app/src/main/assets/data/detective-cases.json",
        ).map(::File).first { it.exists() }
        val text = file.readText()
        val arr = JSONArray(text)
        assertTrue(arr.length() >= 4)
        (0 until arr.length()).forEach { i ->
            val o = arr.getJSONObject(i)
            val ss = o.getJSONArray("suspects")
            assertEquals(6, ss.length())
            val suspects = (0 until ss.length()).map { j ->
                val s = ss.getJSONObject(j)
                Suspect(
                    s.getString("name"), s.getString("motive"),
                    s.getString("where"), s.getString("item"), s.getString("time"),
                )
            }
            Case(o.getString("title"), o.getString("victim"), o.getString("place"), o.getString("intro"), suspects)
            listOf(
                suspects.map { it.motive }, suspects.map { it.where },
                suspects.map { it.item }, suspects.map { it.time },
            ).forEach { values ->
                values.groupingBy { it }.eachCount().values.forEach { count ->
                    assertTrue("every value must fit at least two suspects", count >= 2)
                }
            }
        }
        val bank = CaseBank.fromJson(text)
        val seen = (1..arr.length()).map { bank.next(Random(7)).title }.toSet()
        assertEquals(arr.length(), seen.size)
    }
}
