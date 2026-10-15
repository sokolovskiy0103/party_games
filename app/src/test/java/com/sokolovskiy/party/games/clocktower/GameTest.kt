package com.sokolovskiy.party.games.clocktower

import com.sokolovskiy.party.games.clocktower.Game.Phase
import com.sokolovskiy.party.games.clocktower.Game.Side
import com.sokolovskiy.party.games.clocktower.Game.Step
import com.sokolovskiy.party.games.clocktower.Game.TaskKind
import com.sokolovskiy.party.games.clocktower.Role.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    /** p1…pN sit in this order and get [roles] in the same order. Storyteller dice never misregister. */
    private fun game(vararg roles: Role, drunkAs: Role? = null, herring: String? = null, seed: Int = 1): Game {
        val g = Game(Random(seed))
        g.misregister = 0.0; g.spyAsGood = 0.0; g.mayorBounce = 0.0
        roles.indices.forEach { g.add("p${it + 1}", "P${it + 1}") }
        g.begin(roles.toList(), drunkAs, herring ?: g.seating.first { id -> roles[g.seating.indexOf(id)].kind.good })
        return g
    }

    private val seven = arrayOf(IMP, POISONER, EMPATH, CHEF, WASHERWOMAN, SOLDIER, MONK)

    private fun Game.id(role: Role) = seats.values.first { it.role == role }.id
    private fun Game.seat(role: Role) = seats.values.first { it.role == role }

    /** Plays a whole night. Night roles not in [picks] (and everyone else) tap the first other living player. */
    private fun Game.playNight(picks: Map<String, List<String>> = emptyMap(), raven: String? = null) {
        assertEquals(Phase.NIGHT, phase)
        assertEquals(Step.CHOOSE, step)
        for ((pid, t) in tasks.toMap()) {
            val ts = picks[pid] ?: seats.values.filter { it.alive && it.id != pid }.take(t.picks).map { it.id }
            assertTrue("choose $pid", choose(pid, ts))
        }
        assertEquals(Step.INFO, step.takeIf { phase == Phase.NIGHT } ?: Step.INFO)
        for ((pid, t) in tasks.toMap()) if (t.kind == TaskKind.RAVEN) assertTrue(choose(pid, listOf(raven ?: seats.keys.first { it != pid })))
        for (pid in tasks.keys.toList()) markRead(pid)
    }

    private fun Game.lastNote(role: Role) = seat(role).notes.last().text

    private fun Game.everyoneVotes(yes: Set<String>) {
        for (s in seats.values.filter { canVote(it) }) castVote(s.id, s.id in yes)
    }

    // ---------- setup ----------

    @Test fun compositionMatchesPlayerCount() {
        for (n in 5..15) repeat(5) { seed ->
            val g = Game(Random(seed * 31 + n))
            (1..n).forEach { g.add("p$it", "P$it") }
            assertTrue(g.startGame())
            val c = Composition.forPlayers(n)!!
            val kinds = g.seats.values.groupingBy { it.role.kind }.eachCount()
            val baron = g.seats.values.any { it.role == BARON }
            assertEquals(1, kinds[Kind.DEMON])
            assertEquals(c.minions, kinds[Kind.MINION])
            assertEquals(c.outsiders + if (baron) 2 else 0, kinds[Kind.OUTSIDER] ?: 0)
            assertEquals(c.townsfolk - if (baron) 2 else 0, kinds[Kind.TOWNSFOLK] ?: 0)
            assertEquals(n, g.seats.values.map { it.role }.toSet().size)
            g.seats.values.firstOrNull { it.role == DRUNK }?.let { d ->
                assertEquals(Kind.TOWNSFOLK, d.shown.kind)
                assertTrue(g.seats.values.none { it.role == d.shown })
            }
            assertTrue(g.bluffs.none { b -> g.seats.values.any { it.role == b || it.shown == b } })
            assertFalse(g.seats.getValue(g.redHerring!!).evil)
        }
    }

    @Test fun needsFiveToFifteen() {
        val g = Game(Random(1))
        (1..4).forEach { g.add("p$it", "P$it") }
        assertFalse(g.startGame())
        g.add("p5", "P5")
        assertTrue(g.startGame())
    }

    @Test fun stateRevealsOnlyOwnRole() {
        val g = game(*seven)
        val s = g.stateJson("p3", false, 0)
        assertEquals("EMPATH", s.getJSONObject("me").getString("role"))
        val seats = s.getJSONArray("seats")
        for (i in 0 until seats.length()) assertFalse(seats.getJSONObject(i).has("role"))
        assertFalse(s.has("grimoire"))
        assertFalse(s.has("log"))
        assertTrue(g.stateJson(null, true, 0).has("grimoire"))
    }

    @Test fun drunkSeesTheirImaginedRole() {
        val g = game(IMP, POISONER, DRUNK, CHEF, SOLDIER, drunkAs = MONK)
        val me = g.stateJson(g.id(DRUNK), false, 0).getJSONObject("me")
        assertEquals("MONK", me.getString("role"))
        assertFalse(me.getBoolean("evil"))
    }

    // ---------- nights ----------

    @Test fun everyoneHasANightTaskAndTheDayStartsAfterReading() {
        val g = game(*seven)
        assertEquals(g.seats.size, g.tasks.size)
        assertEquals(TaskKind.POISON, g.tasks[g.id(POISONER)]!!.kind)
        assertEquals(TaskKind.DUMMY, g.tasks[g.id(IMP)]!!.kind) // no kill on the first night
        assertEquals(TaskKind.DUMMY, g.tasks[g.id(MONK)]!!.kind)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertEquals(Phase.DAY, g.phase)
        assertTrue(g.events.last().contains("спокійно"))
    }

    @Test fun firstNightInformationIsTrue() {
        val g = game(*seven)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        // Empath p3 sits between Poisoner p2 and Chef p4
        assertTrue(g.lastNote(EMPATH).endsWith(": 1."))
        // Imp p1 and Poisoner p2 sit together
        assertTrue(g.lastNote(CHEF).endsWith(": 1."))
        val ww = g.lastNote(WASHERWOMAN)
        val named = listOf(EMPATH, CHEF, SOLDIER, MONK).filter { ww.contains(it.title) }
        assertEquals(1, named.size)
        assertTrue(ww.contains("P${g.seating.indexOf(g.id(named[0])) + 1} ") || ww.endsWith("P${g.seating.indexOf(g.id(named[0])) + 1}."))
    }

    @Test fun evilLearnsEachOtherWithSevenOrMore() {
        val g = game(*seven)
        g.playNight()
        assertTrue(g.seat(POISONER).notes.any { it.text.contains("Демон — P1") })
        val imp = g.seat(IMP).notes.map { it.text }
        assertTrue(imp.any { it.contains("Поплічники: P2") })
        assertTrue(imp.any { t -> g.bluffs.all { t.contains(it.title) } })
    }

    @Test fun smallGameHasNoEvilTeamInfoButDemonGetsBluffs() {
        val g = game(IMP, POISONER, EMPATH, CHEF, SOLDIER)
        g.playNight()
        assertTrue(g.seat(POISONER).notes.isEmpty())
        assertEquals(1, g.seat(IMP).notes.size)
    }

    @Test fun poisonedEmpathGetsFalseNumber() {
        val g = game(*seven)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(EMPATH))))
        assertFalse(g.lastNote(EMPATH).endsWith(": 1."))
        assertTrue(g.stLog.any { it.contains("отруєний") })
    }

    @Test fun impKillsAndMonkAndSoldierProtect() {
        val g = game(*seven)
        g.playNight()
        g.endDay()
        // night 2: the Imp attacks the Soldier — nobody dies
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(SOLDIER)), g.id(POISONER) to listOf(g.id(CHEF))))
        assertTrue(g.dawnDeaths.isEmpty())
        g.endDay()
        // night 3: Monk protects the Chef, the Imp attacks the Chef
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(CHEF)), g.id(MONK) to listOf(g.id(CHEF)), g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertTrue(g.dawnDeaths.isEmpty())
        g.endDay()
        // night 4: the Empath dies
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(EMPATH)), g.id(MONK) to listOf(g.id(CHEF)), g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertEquals(listOf(g.id(EMPATH)), g.dawnDeaths)
        assertFalse(g.seat(EMPATH).alive)
    }

    @Test fun poisonedSoldierDies() {
        val g = game(*seven)
        g.playNight(); g.endDay()
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(SOLDIER)), g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertEquals(listOf(g.id(SOLDIER)), g.dawnDeaths)
    }

    @Test fun drunkMonkProtectsNobody() {
        val g = game(IMP, POISONER, DRUNK, CHEF, SOLDIER, EMPATH, drunkAs = MONK)
        g.playNight(); g.endDay()
        assertEquals(TaskKind.MONK, g.tasks[g.id(DRUNK)]!!.kind)
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(CHEF)), g.id(DRUNK) to listOf(g.id(CHEF)), g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertEquals(listOf(g.id(CHEF)), g.dawnDeaths)
    }

    @Test fun starPassMakesScarletWomanTheImp() {
        val g = game(IMP, SCARLET_WOMAN, EMPATH, CHEF, SOLDIER, MONK, WASHERWOMAN)
        g.playNight(); g.endDay()
        val sw = g.id(SCARLET_WOMAN)
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(IMP))))
        assertEquals(Phase.DAY, g.phase)
        assertEquals(IMP, g.seats.getValue(sw).role)
        assertEquals("IMP", g.stateJson(sw, false, 0).getJSONObject("me").getString("role"))
        assertTrue(g.seats.getValue(sw).notes.last().text.contains("Біс"))
    }

    @Test fun ravenkeeperKilledAtNightLearnsARole() {
        val g = game(IMP, POISONER, RAVENKEEPER, CHEF, SOLDIER)
        g.playNight(); g.endDay()
        val rk = g.id(RAVENKEEPER)
        g.playNight(mapOf(g.id(IMP) to listOf(rk), g.id(POISONER) to listOf(g.id(CHEF))), raven = g.id(POISONER))
        assertEquals(Phase.DAY, g.phase)
        assertTrue(g.seats.getValue(rk).notes.last().text.contains(POISONER.title))
    }

    @Test fun undertakerLearnsExecutedRole() {
        val g = game(IMP, POISONER, UNDERTAKER, CHEF, SOLDIER, MONK, EMPATH)
        g.playNight()
        g.nominate(g.id(CHEF), g.id(POISONER))
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        assertFalse(g.seat(POISONER).alive)
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(SOLDIER))))
        assertTrue(g.lastNote(UNDERTAKER).contains(POISONER.title))
    }

    @Test fun fortuneTellerSeesDemonAndRedHerring() {
        val g = game(IMP, POISONER, FORTUNE_TELLER, CHEF, SOLDIER, herring = "p4")
        g.playNight(mapOf(g.id(FORTUNE_TELLER) to listOf("p1", "p5")))
        assertTrue(g.lastNote(FORTUNE_TELLER).contains("ТАК"))
        g.endDay()
        g.playNight(mapOf(g.id(FORTUNE_TELLER) to listOf("p4", "p5"), g.id(IMP) to listOf("p5")))
        assertTrue(g.lastNote(FORTUNE_TELLER).contains("ТАК"))
        g.endDay()
        g.playNight(mapOf(g.id(FORTUNE_TELLER) to listOf("p2", "p3"), g.id(IMP) to listOf("p4")))
        assertTrue(g.lastNote(FORTUNE_TELLER).contains("ні"))
    }

    @Test fun spySeesTheGrimoire() {
        val g = game(IMP, SPY, EMPATH, DRUNK, SOLDIER, drunkAs = CHEF)
        g.playNight()
        val note = g.lastNote(SPY)
        assertTrue(note.contains("Гримуар"))
        assertTrue(note.contains("думає, що ${CHEF.title}"))
        assertTrue(note.contains(IMP.title))
    }

    @Test fun forceFillsMissingChoices() {
        val g = game(*seven)
        g.choose(g.id(POISONER), listOf(g.id(CHEF)))
        assertTrue(g.forceNight())
        assertEquals(Step.INFO, g.step)
        assertTrue(g.forceNight())
        assertEquals(Phase.DAY, g.phase)
    }

    @Test fun invalidChoicesAreRejected() {
        val g = game(*seven)
        g.playNight(); g.endDay()
        val monk = g.id(MONK)
        assertFalse(g.choose(monk, listOf(monk)))           // not self
        assertFalse(g.choose(monk, listOf("p2", "p3")))      // one pick
        assertFalse(g.choose(monk, listOf("nobody")))
        assertTrue(g.choose(monk, listOf("p2")))
        assertFalse(g.choose(monk, listOf("p3")))            // only once
    }

    // ---------- days ----------

    @Test fun executionNeedsHalfTheLivingAndTiesSaveBoth() {
        val g = game(*seven)
        g.playNight()
        assertEquals(4, g.threshold())
        assertTrue(g.nominate("p3", "p4"))
        assertFalse(g.nominate("p5", "p6")) // a vote is open
        g.everyoneVotes(setOf("p1", "p2", "p3"))
        assertNull(g.block)
        g.nominate("p4", "p5")
        g.everyoneVotes(setOf("p1", "p2", "p3", "p4"))
        assertEquals("p5", g.block)
        g.nominate("p5", "p6")
        g.everyoneVotes(setOf("p1", "p2", "p3", "p4"))
        assertNull(g.block) // tie
        assertFalse(g.nominate("p3", "p7")) // p3 already nominated today
        assertFalse(g.nominate("p6", "p4")) // p4 already nominated today
        g.endDay()
        assertTrue(g.seats.values.all { it.alive })
        assertEquals(Phase.NIGHT, g.phase)
    }

    @Test fun executingTheImpWins() {
        val g = game(*seven)
        g.playNight()
        g.nominate("p3", "p1")
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        assertEquals(Phase.OVER, g.phase)
        assertEquals(Side.GOOD, g.winner)
        val s = g.stateJson("p3", false, 0)
        assertTrue(s.getJSONArray("seats").getJSONObject(0).has("role"))
        assertTrue(s.has("log"))
    }

    @Test fun scarletWomanTakesOverWhenFiveAreAlive() {
        val g = game(IMP, SCARLET_WOMAN, EMPATH, CHEF, SOLDIER, MONK, WASHERWOMAN)
        g.playNight()
        g.nominate("p3", "p1")
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        assertEquals(Phase.NIGHT, g.phase)
        assertEquals(IMP, g.seats.getValue("p2").role)
    }

    @Test fun executingTheSaintLoses() {
        val g = game(IMP, POISONER, SAINT, CHEF, SOLDIER)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(CHEF))))
        g.nominate("p4", "p3")
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        assertEquals(Side.EVIL, g.winner)
    }

    @Test fun virginExecutesTownsfolkNominator() {
        val g = game(IMP, POISONER, VIRGIN, CHEF, SOLDIER)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertTrue(g.nominate(g.id(CHEF), g.id(VIRGIN)))
        assertFalse(g.seat(CHEF).alive)
        assertEquals(Phase.NIGHT, g.phase)
        assertEquals(g.id(CHEF), g.executedToday)
    }

    @Test fun virginIgnoresNonTownsfolkAndIsSpentAfterwards() {
        val g = game(IMP, POISONER, VIRGIN, CHEF, SOLDIER, EMPATH)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertTrue(g.nominate(g.id(POISONER), g.id(VIRGIN)))
        assertTrue(g.seat(POISONER).alive)
        assertTrue(g.seat(VIRGIN).virginSpent)
        assertTrue(g.vote != null)
    }

    @Test fun slayerKillsOnlyTheDemon() {
        val g = game(IMP, POISONER, SLAYER, CHEF, SOLDIER, EMPATH)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        // a bluffing "slayer" fires a blank
        assertTrue(g.shoot(g.id(CHEF), g.id(IMP)))
        assertTrue(g.seat(IMP).alive)
        assertFalse(g.shoot(g.id(CHEF), g.id(IMP))) // only once
        assertTrue(g.shoot(g.id(SLAYER), g.id(IMP)))
        assertEquals(Side.GOOD, g.winner)
    }

    @Test fun butlerVoteCountsOnlyWithMaster() {
        val g = game(IMP, POISONER, BUTLER, CHEF, SOLDIER, EMPATH, MONK)
        g.playNight(mapOf(g.id(BUTLER) to listOf(g.id(CHEF)), g.id(POISONER) to listOf(g.id(SOLDIER))))
        g.nominate("p6", "p7")
        g.everyoneVotes(setOf("p1", "p2", g.id(BUTLER), "p5"))
        assertEquals(3, g.nominations.last().yes)
        g.nominate("p7", "p6")
        g.everyoneVotes(setOf("p1", "p2", g.id(BUTLER), g.id(CHEF)))
        assertEquals(4, g.nominations.last().yes)
    }

    @Test fun deadPlayersHaveOneGhostVote() {
        val g = game(*seven)
        g.playNight()
        g.nominate("p3", "p4")
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        val dead = g.seats.getValue("p4")
        assertFalse(dead.alive)
        g.playNight(mapOf(g.id(IMP) to listOf("p5")))
        g.nominate("p3", "p6")
        g.everyoneVotes(setOf("p4"))
        assertFalse(dead.ghostVote)
        assertFalse(g.canVote(dead))
        assertFalse(g.nominate("p4", "p7")) // the dead can't nominate
    }

    @Test fun mayorWinsWithThreeAliveAndNoExecution() {
        val g = game(IMP, POISONER, MAYOR, CHEF, SOLDIER)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        g.nominate("p4", "p2")
        g.everyoneVotes(g.seats.keys)
        g.endDay() // Poisoner executed: 4 alive
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(CHEF))))
        assertEquals(3, g.aliveCount())
        g.endDay()
        assertEquals(Side.GOOD, g.winner)
    }

    @Test fun twoAliveIsAnEvilWin() {
        val g = game(IMP, POISONER, EMPATH, CHEF, SOLDIER)
        g.playNight(mapOf(g.id(POISONER) to listOf(g.id(SOLDIER))))
        g.nominate("p2", "p3")
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        g.playNight(mapOf(g.id(IMP) to listOf(g.id(CHEF)), g.id(POISONER) to listOf(g.id(SOLDIER))))
        assertEquals(3, g.aliveCount())
        g.nominate("p2", "p5")
        g.everyoneVotes(g.seats.keys)
        g.endDay()
        assertEquals(Side.EVIL, g.winner)
    }

    @Test fun demonLeavingCancelsAndOthersLeavingDie() {
        val g = game(*seven)
        g.remove("p5")
        assertFalse(g.seats.getValue("p5").alive)
        assertEquals(Phase.NIGHT, g.phase)
        g.remove("p1")
        assertEquals(Phase.LOBBY, g.phase)
        assertNotEquals(null, g.notice)
    }

    @Test fun lobbySeatsCanBeRearranged() {
        val g = Game(Random(1))
        (1..5).forEach { g.add("p$it", "P$it") }
        assertTrue(g.moveSeat("p2", -1))
        assertEquals(listOf("p2", "p1", "p3", "p4", "p5"), g.seating)
        assertFalse(g.moveSeat("p2", -1))
    }
}
