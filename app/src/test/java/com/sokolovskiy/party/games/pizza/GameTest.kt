package com.sokolovskiy.party.games.pizza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

fun realWords(): Words = Words.fromJson(
    listOf("src/main/assets/data/pizza-words.json", "app/src/main/assets/data/pizza-words.json")
        .map(::File).first { it.exists() }.readText()
)

class GameTest {
    private val words = realWords()

    /** A game with [n] connected players, started at t=0 and past the countdown (commands handed out). */
    private fun game(n: Int = 3, seed: Int = 7, diff: Difficulty = Difficulty.NORMAL): Game {
        val g = Game(words, Random(seed))
        (1..n).forEach { g.add("p$it", "P$it"); g.setConnected("p$it", true) }
        g.setDifficulty(diff)
        assertTrue(g.startGame(0))
        assertFalse(g.tick(Game.COUNTDOWN_MS - 1))
        assertTrue(g.tick(Game.COUNTDOWN_MS))
        return g
    }

    private val t0 = Game.COUNTDOWN_MS

    private fun Game.p(id: String) = players.getValue(id)

    /** Carries out [pid]'s command the right way: the control's owner acts. */
    private fun Game.doCmd(pid: String, now: Long = t0 + 100): Boolean {
        val c = p(pid).cmd!!
        return act(c.control.owner, c.control.id, c.value, now)
    }

    private fun activeControls(g: Game) = g.players.values.mapNotNull { it.cmd?.control }

    @Test fun needsTwoPlayers() {
        val g = Game(words, Random(1))
        g.add("p1", "P1")
        assertFalse(g.startGame(0))
        g.add("p2", "P2")
        assertTrue(g.startGame(0))
        assertEquals(Game.Phase.PLAY, g.phase)
    }

    @Test fun countdownThenEveryoneGetsACommand() {
        val g = Game(words, Random(3))
        (1..3).forEach { g.add("p$it", "P$it"); g.setConnected("p$it", true) }
        g.startGame(0)
        assertTrue(g.players.values.all { it.panel.isNotEmpty() && it.cmd == null })
        assertFalse(g.act("p1", g.p("p1").panel[0].id, 1, 10))  // panels are locked during the countdown
        g.tick(t0)
        assertTrue(g.started)
        assertTrue(g.players.values.all { it.cmd != null })
        assertTrue(g.players.values.all { it.cmd!!.deadline == t0 + g.lv.cmdMs })
    }

    @Test fun commandsNeverShareAControlAndAlwaysAskForAChange() {
        repeat(30) { seed ->
            val g = game(n = 4, seed = seed)
            var now = t0
            repeat(40) {
                val ctrls = activeControls(g)
                assertEquals(ctrls.size, ctrls.toSet().size)
                g.players.values.forEach { p ->
                    val c = p.cmd ?: return@forEach
                    if (c.control.type.stateful) assertTrue(c.control.value != c.value)
                    assertTrue(c.value in c.control.min..c.control.max)
                }
                now += 50
                if (g.phase == Game.Phase.PLAY) g.doCmd(g.players.keys.filter { k -> g.p(k).cmd != null }.random(Random(it)), now)
            }
        }
    }

    @Test fun doingACommandFillsTheBarAndGivesANewOne() {
        val g = game()
        val old = g.p("p1").cmd!!
        assertTrue(g.doCmd("p1"))
        assertEquals(1, g.progress)
        assertEquals(1, g.totalDone)
        val new = g.p("p1").cmd!!
        assertTrue(new.id != old.id)
        assertEquals(1, g.p("p1").doneSeq)
        assertEquals(1, g.p(old.control.owner).hitSeq)
        assertEquals("done", g.last)
    }

    @Test fun wrongActionsDoNothingButMoveTheControl() {
        val g = game()
        val c = g.p("p1").cmd!!
        val owner = g.p(c.control.owner)
        val other = owner.panel.first { it.type.stateful && it !in activeControls(g) }
        val before = g.progress
        val v = other.targets().first()
        assertTrue(g.act(owner.id, other.id, v, t0 + 10))
        assertEquals(v, other.value)
        assertEquals(before, g.progress)
        assertEquals(100, g.temp)
    }

    @Test fun onlyTheOwnerCanTouchAControl() {
        val g = game()
        val c = g.p("p1").cmd!!
        val stranger = g.players.keys.first { it != c.control.owner }
        assertFalse(g.act(stranger, c.control.id, c.value, t0 + 10))
        assertEquals(0, g.progress)
    }

    @Test fun outOfRangeValuesAreIgnored() {
        val g = game()
        val c = g.p("p1").panel.first()
        assertFalse(g.act("p1", c.id, c.max + 1, t0 + 10))
        assertFalse(g.act("p1", c.id, Int.MIN_VALUE, t0 + 10))
    }

    @Test fun aMissedCommandCoolsThePizza() {
        val g = game()
        val dl = g.p("p1").cmd!!.deadline
        assertFalse(g.tick(dl - 1))
        assertTrue(g.tick(dl))
        assertEquals(100 - 3 * g.lv.damage, g.temp)  // all three ran out together
        assertTrue(g.players.values.all { it.cmd != null && it.cmd!!.deadline == dl + g.lv.cmdMs })
        assertEquals(1, g.p("p1").failSeq)
        assertEquals("fail", g.last)
    }

    @Test fun coldPizzaEndsTheGameAndKeepsTheRecord() {
        val record = Game.Record()
        val g = Game(words, Random(5), record)
        (1..2).forEach { g.add("p$it", "P$it"); g.setConnected("p$it", true) }
        g.startGame(0); g.tick(t0)
        var now = t0
        while (g.phase == Game.Phase.PLAY) { now += g.lv.cmdMs; g.tick(now) }
        assertEquals(Game.Phase.OVER, g.phase)
        assertEquals(0, g.temp)
        assertEquals("over", g.last)
        assertTrue(g.line.isNotEmpty())
        assertTrue(g.players.values.all { it.cmd == null })
        assertFalse(g.act("p1", g.p("p1").panel[0].id, 0, now))
        assertEquals(0, record.best)
        assertTrue(g.startGame(now))
        assertEquals(100, g.temp)
        assertEquals(1, g.level)
    }

    @Test fun fullBarDeliversAndTheNextLevelIsHarder() {
        val record = Game.Record()
        val g = Game(words, Random(9), record)
        (1..3).forEach { g.add("p$it", "P$it"); g.setConnected("p$it", true) }
        g.startGame(0); g.tick(t0)
        val l1 = g.lv
        val names1 = g.players.values.flatMap { p -> p.panel.map { it.name.nom } }
        g.tick(t0 + l1.cmdMs)  // three misses first
        val cold = g.temp
        while (g.phase == Game.Phase.PLAY) g.doCmd(g.players.keys.first { g.p(it).cmd != null }, t0 + l1.cmdMs + 1)
        assertEquals(Game.Phase.WARP, g.phase)
        assertEquals(1, g.delivered)
        assertEquals(1, record.best)
        assertEquals(minOf(100, cold + Game.REPAIR), g.temp)
        assertEquals("warp", g.last)

        assertTrue(g.next(100_000))
        assertEquals(2, g.level)
        assertEquals(Game.Phase.PLAY, g.phase)
        assertEquals(0, g.progress)
        assertFalse(g.started)
        val l2 = g.lv
        assertTrue(l2.cmdMs < l1.cmdMs)
        assertTrue(l2.cells > l1.cells)
        assertTrue(l2.goal > l1.goal)
        assertTrue(l2.hazards.isNotEmpty())
        val names2 = g.players.values.flatMap { p -> p.panel.map { it.name.nom } }
        assertTrue(names2.toSet() != names1.toSet())
        val ship = g.players.values.flatMap { p -> p.panel.map { it.type } }.toSet()
        assertTrue(ship.containsAll(l2.types.filter { it.unlock == 2 }))
    }

    @Test fun disconnectedPlayersGetNoCommandsAndNothingPointsAtThem() {
        val g = game(n = 3)
        val p3panel = g.p("p3").panel.toSet()
        assertTrue(g.setConnected("p3", false))
        assertNull(g.p("p3").cmd)
        assertTrue(g.players.values.none { it.cmd?.control in p3panel })
        var now = t0
        repeat(40) {
            if (g.phase != Game.Phase.PLAY) return@repeat
            now += 10
            g.tick(now)
            val k = listOf("p1", "p2").first { g.p(it).cmd != null }
            g.doCmd(k, now)
            assertTrue(g.players.values.none { it.cmd?.control in p3panel })
            assertNull(g.p("p3").cmd)
        }
        assertEquals(100, g.temp)  // dropping commands costs nothing
    }

    @Test fun reconnectingGetsACommandAtTheNextTick() {
        val g = game(n = 3)
        g.setConnected("p2", false)
        g.tick(t0 + 10)
        assertNull(g.p("p2").cmd)
        g.setConnected("p2", true)
        assertTrue(g.tick(t0 + 20))
        assertNotNull(g.p("p2").cmd)
    }

    @Test fun joiningMidGameGetsAPanelAndACommand() {
        val g = game(n = 2)
        val p = g.add("p9", "Новенький")
        g.setConnected("p9", true)
        assertTrue(p.panel.isNotEmpty())
        val all = g.players.values.flatMap { it.panel }
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.name.nom }.toSet().size)
        g.tick(t0 + 10)
        assertNotNull(p.cmd)
    }

    @Test fun leavingDropsCommandsAndTooFewPlayersCancel() {
        val g = game(n = 3)
        val p3panel = g.p("p3").panel.toSet()
        g.remove("p3")
        assertTrue(g.players.values.none { it.cmd?.control in p3panel })
        assertEquals(Game.Phase.PLAY, g.phase)
        g.remove("p2")
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertNotNull(g.notice)
    }

    @Test fun renameKeepsThePanel() {
        val g = game(n = 2)
        val panel = g.p("p1").panel.toList()
        g.add("p1", "Інше ім'я")
        assertEquals("Інше ім'я", g.p("p1").name)
        assertEquals(panel, g.p("p1").panel)
    }

    @Test fun hazardsUnlockLevelByLevel() {
        assertTrue(Level.of(1, 3, Difficulty.NORMAL).hazards.isEmpty())
        assertEquals(listOf(Hazard.CHEESE, Hazard.GLITCH, Hazard.SAUCE), Level.of(2, 3, Difficulty.NORMAL).hazards)
        assertTrue(Level.of(3, 3, Difficulty.NORMAL).hazards.containsAll(listOf(Hazard.BREAK, Hazard.SHUFFLE, Hazard.GRAVITY)))
        assertTrue(Hazard.ASTEROIDS !in Level.of(3, 3, Difficulty.NORMAL).hazards)
        assertTrue(Hazard.ASTEROIDS in Level.of(4, 3, Difficulty.NORMAL).hazards)
        assertTrue(Hazard.GRAVITY in Level.of(2, 3, Difficulty.CHAOS).hazards)
        assertTrue(Level.of(1, 3, Difficulty.CHAOS).hazards.isNotEmpty())
    }

    @Test fun hazardsComeByThemselves() {
        val g = Game(words, Random(2))
        (1..3).forEach { g.add("p$it", "P$it"); g.setConnected("p$it", true) }
        g.setDifficulty(Difficulty.CHAOS)
        g.startGame(0); g.tick(t0)
        assertTrue(g.nextHazardAt > t0)
        var now = t0
        while (g.hazard == null && g.phase == Game.Phase.PLAY) {
            g.players.values.forEach { p -> p.cmd?.let { it.deadline = now + 60_000 } }
            now += 250; g.tick(now)
        }
        assertNotNull(g.hazard)
        assertEquals("hazard", g.last)
    }

    private fun me(g: Game, pid: String, now: Long = t0) = g.stateJson(pid, false, now).getJSONObject("me")

    @Test fun gravityNeedsEveryoneToBraceAndPausesCommands() {
        val g = game()
        val dl = g.p("p1").cmd!!.deadline
        g.spawnHazard(t0 + 10, Hazard.GRAVITY)
        val c = g.crisis!!
        assertEquals(dl + Game.GRAVITY_MS, g.p("p1").cmd!!.deadline)
        assertEquals(1, me(g, "p1").getInt("crisisLeft"))
        assertTrue(g.brace("p1"))
        assertFalse(g.brace("p1"))
        assertFalse(g.rock("p2"))  // not this crisis
        g.brace("p2")
        assertNotNull(g.crisis)
        g.brace("p3")
        assertNull(g.crisis)
        assertEquals("cleared", g.last)
        g.tick(c.deadline)
        assertEquals(100, g.temp)
    }

    @Test fun aCrisisNotHandledCoolsThePizza() {
        val g = game()
        g.spawnHazard(t0, Hazard.GRAVITY)
        g.brace("p1")
        g.tick(g.crisis!!.deadline)
        assertNull(g.crisis)
        assertEquals(100 - 2 * g.lv.damage, g.temp)  // two let go: double damage (capped at two)
    }

    @Test fun meteorsNeedEveryRockShot() {
        val g = game()
        g.spawnHazard(t0, Hazard.ASTEROIDS)
        val per = g.crisis!!.per
        assertTrue(per >= Game.ROCKS)
        assertEquals(per, me(g, "p2").getInt("crisisLeft"))
        for (pid in g.players.keys) repeat(per) { assertTrue(g.rock(pid)) }
        assertNull(g.crisis)
        assertEquals("cleared", g.last)
    }

    @Test fun aDisconnectedPlayerDoesNotHoldUpACrisis() {
        val g = game()
        g.spawnHazard(t0, Hazard.GRAVITY)
        g.brace("p1"); g.brace("p2")
        assertNotNull(g.crisis)
        g.setConnected("p3", false)
        assertNull(g.crisis)
    }

    @Test fun cheeseAndGlitchHitOnePlayerAndWearOff() {
        val g = game()
        g.spawnHazard(t0, Hazard.CHEESE)
        val who = g.hazard!!.who!!
        assertTrue(me(g, who).has("cheese"))
        g.players.keys.filter { it != who }.forEach { assertFalse(me(g, it).has("cheese")) }
        g.players.values.forEach { p -> p.cmd?.let { it.deadline = Long.MAX_VALUE / 4 } }
        assertTrue(g.tick(t0 + Game.CHEESE_MS))
        assertFalse(me(g, who, t0 + Game.CHEESE_MS).has("cheese"))

        val t1 = t0 + Game.CHEESE_MS
        g.spawnHazard(t1, Hazard.GLITCH)
        val w2 = g.hazard!!.who!!
        val text = me(g, w2, t1).getJSONObject("cmd").getString("text")
        assertTrue(text.contains('▒'))
        assertEquals(text, me(g, w2, t1).getJSONObject("cmd").getString("text"))
        assertEquals(g.p(w2).cmd!!.text.length, text.length)
    }

    @Test fun sauceStaysUntilWipedOrDry() {
        val g = game()
        g.spawnHazard(t0, Hazard.SAUCE)
        val who = g.hazard!!.who!!
        assertTrue(me(g, who).getJSONObject("sauce").getInt("n") >= Game.SAUCE_BLOBS)
        val other = g.players.keys.first { it != who }
        assertFalse(g.wipe(other))
        assertTrue(g.wipe(who))
        assertFalse(me(g, who).has("sauce"))
        assertEquals("wiped", g.last)
        // and it dries by itself
        g.spawnHazard(t0, Hazard.SAUCE)
        val w2 = g.hazard!!.who!!
        g.players.values.forEach { p -> p.cmd?.let { it.deadline = Long.MAX_VALUE / 4 } }
        g.tick(t0 + Game.SAUCE_MS)
        assertNull(g.p(w2).sauce)
    }

    @Test fun aBrokenInstrumentIgnoresActionsUntilFixed() {
        val g = game()
        g.spawnHazard(t0, Hazard.BREAK)
        val who = g.p(g.hazard!!.who!!)
        val c = who.panel.first { it.broken }
        val v = c.targets().first()
        val before = c.value
        assertFalse(g.act(who.id, c.id, v, t0 + 1))
        assertEquals(before, c.value)
        val panel = me(g, who.id).getJSONArray("panel")
        val shown = (0 until panel.length()).map { panel.getJSONObject(it) }.first { it.getString("id") == c.id }
        assertEquals(Game.FIX_TAPS, shown.getInt("broken"))
        val stranger = g.players.keys.first { it != who.id }
        assertFalse(g.fix(stranger, c.id))
        repeat(Game.FIX_TAPS) { assertTrue(g.fix(who.id, c.id)) }
        assertFalse(c.broken)
        assertFalse(g.fix(who.id, c.id))
        assertEquals("fixed", g.last)
        assertTrue(g.act(who.id, c.id, v, t0 + 2))
    }

    @Test fun aShakeUpReordersAPanelButKeepsItsInstruments() {
        val g = game(seed = 3)
        val before = g.players.mapValues { (_, p) -> p.panel.map { it.id } }
        repeat(10) {
            g.spawnHazard(t0, Hazard.SHUFFLE)
            assertTrue(me(g, g.hazard!!.who!!).has("shake"))
        }
        g.players.forEach { (id, p) -> assertEquals(before.getValue(id).toSet(), p.panel.map { it.id }.toSet()) }
        assertTrue(g.players.any { (id, p) -> before.getValue(id) != p.panel.map { it.id } })
    }

    @Test fun hazardsEndWithTheLevel() {
        val g = game()
        g.spawnHazard(t0, Hazard.SAUCE)
        g.spawnHazard(t0, Hazard.BREAK)
        g.spawnHazard(t0, Hazard.GRAVITY)
        g.players.keys.forEach { g.brace(it) }
        g.players.values.forEach { p -> p.panel.forEach { it.fixLeft = 0 } }
        while (g.phase == Game.Phase.PLAY) g.doCmd(g.players.keys.first { g.p(it).cmd != null }, t0 + 1)
        assertEquals(Game.Phase.WARP, g.phase)
        assertTrue(g.players.values.all { p -> p.sauce == null && p.panel.none { it.broken } })
    }

    @Test fun nothingLeaks() {
        val g = game(n = 3)
        val p2 = g.p("p2")
        val s1 = g.stateJson("p1", false, t0).toString()
        // p1 doesn't see p2's panel or p2's command
        p2.panel.forEach { c -> assertFalse("leaks ${c.id}", s1.contains("\"${c.id}\"")) }
        assertFalse(s1.contains(p2.cmd!!.text))
        // own command carries no hint of whose panel it is on
        val cmd = g.stateJson("p1", false, t0).getJSONObject("me").getJSONObject("cmd")
        assertEquals(setOf("id", "text", "deadline", "total"), cmd.keys().asSequence().toSet())

        val board = g.boardJson(t0)!!.toString()
        g.players.values.forEach { p ->
            p.panel.forEach { c -> assertFalse(board.contains("\"${c.id}\"")) }
            assertFalse(board.contains(p.cmd!!.text))
        }
        assertFalse(g.boardJson(t0)!!.has("me"))
        val host = g.stateJson(null, true, t0)
        assertFalse(host.has("me"))
    }

    @Test fun lobbyHasNoBoardAndDifficultyIsLockedInGame() {
        val g = Game(words, Random(1))
        g.add("p1", "P1"); g.add("p2", "P2")
        assertNull(g.boardJson(0))
        assertTrue(g.setDifficulty(Difficulty.EASY))
        g.startGame(0)
        assertFalse(g.setDifficulty(Difficulty.CHAOS))
        assertNotNull(g.boardJson(0))
        g.abort()
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertTrue(g.setDifficulty(Difficulty.CHAOS))
    }

    @Test fun oneShotControlsCountEveryPress() {
        // find a command on a button/keypad/hold/colors control and do it twice: the second press is a no-op for progress
        repeat(50) { seed ->
            val g = game(n = 3, seed = seed)
            val p = g.players.values.firstOrNull { it.cmd?.control?.type?.stateful == false } ?: return@repeat
            val c = p.cmd!!
            assertTrue(g.act(c.control.owner, c.control.id, c.value, t0 + 5))
            assertEquals(1, g.progress)
            return
        }
        error("no one-shot command")
    }
}
