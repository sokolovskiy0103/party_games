package com.sokolovskiy.party.games.truel

import com.sokolovskiy.party.games.truel.Game.Act
import com.sokolovskiy.party.games.truel.Game.Card
import com.sokolovskiy.party.games.truel.Game.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameTest {

    private fun game(n: Int = 3, cards: Boolean = true): Game {
        val g = Game(Random(7))
        (1..n).forEach { g.add("p$it", "P$it").connected = true }
        g.setCards(cards)
        assertTrue(g.startGame())
        return g
    }

    private fun Game.p(id: String) = players[id]!!
    private fun Game.give(id: String, vararg c: Card) { p(id).hand.clear(); p(id).hand += c }

    private fun shoot(t: String, card: Card? = null, ct: String? = null) = Move(Act.SHOOT, t, card, ct)
    private fun dodge(card: Card? = null, ct: String? = null) = Move(Act.DODGE, null, card, ct)
    private fun reload(card: Card? = null, ct: String? = null) = Move(Act.RELOAD, null, card, ct)
    private fun idle(card: Card? = null, ct: String? = null) = Move(Act.IDLE, null, card, ct)

    private fun Game.turn(vararg m: Pair<String, Move>) {
        assertEquals(Game.Phase.PLAN, phase)
        for ((id, mv) in m) assertTrue("${id}: ${check(id, mv)}", move(id, mv))
        if (phase == Game.Phase.PLAN) assertTrue(forceResolve(true))
        assertEquals(Game.Phase.REVEAL, phase)
    }

    private fun Game.goOn() = assertTrue(next(null, true))

    @Test fun needsThree() {
        val g = Game()
        g.add("a", "A"); g.add("b", "B")
        assertFalse(g.startGame())
        g.add("c", "C")
        assertTrue(g.startGame())
        g.players.values.forEach {
            assertEquals(Game.START_HP, it.hp); assertEquals(Game.START_AMMO, it.ammo); assertEquals(Game.START_HAND, it.hand.size)
        }
    }

    @Test fun resolvesOnlyWhenEveryoneMoved() {
        val g = game(cards = false)
        assertTrue(g.move("p1", shoot("p2")))
        assertTrue(g.move("p1", reload()))     // can change
        g.move("p2", reload())
        assertEquals(Game.Phase.PLAN, g.phase)
        g.move("p3", reload())
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(2, g.p("p1").ammo)
    }

    @Test fun illegalMoves() {
        val g = game(cards = false)
        assertFalse(g.move("p1", shoot("p1")))          // yourself
        assertFalse(g.move("p1", shoot("nobody")))
        g.turn("p1" to dodge(), "p2" to shoot("p3"), "p3" to idle())
        g.goOn()
        assertFalse(g.move("p1", dodge()))               // tired
        assertFalse(g.move("p2", shoot("p1")))           // no bullets left
        assertFalse(g.move("p3", idle(Card.VEST)))       // no such card
        assertTrue(g.move("p1", reload()))
    }

    @Test fun simultaneousShotsAndDodge() {
        val g = game(cards = false)
        g.turn("p1" to shoot("p2"), "p2" to shoot("p1"), "p3" to dodge())
        assertEquals(1, g.p("p1").hp); assertEquals(1, g.p("p2").hp)
        assertEquals(0, g.p("p1").ammo)
        g.goOn()
        g.turn("p1" to reload(), "p2" to reload(), "p3" to shoot("p1"))
        assertEquals(0, g.p("p1").hp); assertFalse(g.p("p1").alive)
        assertEquals(1, g.p("p3").kills)
        assertEquals(1, g.p("p3").score)
    }

    @Test fun lastStandingWinsShowdown() {
        val g = game(cards = false)
        g.p("p1").hp = 1; g.p("p2").hp = 1
        g.p("p3").ammo = 2
        g.turn("p1" to shoot("p2"), "p2" to idle(), "p3" to shoot("p1"))
        // p1 and p2 both die; p3 the only survivor: kill +1, survivor +3; p1's kill of p2 still counts
        assertTrue(g.showdownOver)
        assertEquals(1 + Game.SURVIVOR_POINTS, g.p("p3").score)
        assertEquals(1, g.p("p1").score)
        assertEquals(mapOf("p3" to 4, "p1" to 1), g.gains)
        g.goOn()
        assertEquals(2, g.showdown)
        assertTrue(g.players.values.all { it.alive && it.hp == Game.START_HP })
    }

    @Test fun everyoneDeadNoSurvivorBonus() {
        val g = game(cards = false)
        g.players.values.forEach { it.hp = 1 }
        g.turn("p1" to shoot("p2"), "p2" to shoot("p3"), "p3" to shoot("p1"))
        assertTrue(g.showdownOver)
        g.players.values.forEach { assertEquals(1, it.score) }
    }

    @Test fun turnLimitGivesSurvivorsAPoint() {
        val g = game(cards = false)
        repeat(Game.MAX_TURNS) {
            g.turn("p1" to idle(), "p2" to idle(), "p3" to idle())
            if (!g.showdownOver) g.goOn()
        }
        assertTrue(g.showdownOver)
        g.players.values.forEach { assertEquals(1, it.score) }
    }

    @Test fun vestSniperAndAngel() {
        val g = game()
        g.p("p1").ammo = 2
        g.give("p1", Card.SNIPER); g.give("p2", Card.VEST); g.give("p3", Card.ANGEL)
        // vest stops a plain shot
        g.turn("p1" to shoot("p2"), "p2" to idle(Card.VEST), "p3" to idle())
        assertEquals(2, g.p("p2").hp)
        g.goOn()
        g.give("p2", Card.VEST)
        // a sniper's shot goes through a vest — unless an angel watches over the target
        g.turn("p1" to shoot("p2", Card.SNIPER), "p2" to idle(Card.VEST), "p3" to idle(Card.ANGEL, "p2"))
        assertEquals(2, g.p("p2").hp)
        g.goOn()
        g.p("p1").ammo = 1
        g.give("p1", Card.SNIPER)
        g.turn("p1" to shoot("p2", Card.SNIPER), "p2" to dodge(), "p3" to idle())
        assertEquals(1, g.p("p2").hp)  // dodge doesn't help against a sniper
    }

    @Test fun angelOnlyOnOthers() {
        val g = game()
        g.give("p1", Card.ANGEL)
        assertFalse(g.move("p1", idle(Card.ANGEL, "p1")))
        assertFalse(g.move("p1", idle(Card.ANGEL)))
        assertTrue(g.move("p1", idle(Card.ANGEL, "p2")))
    }

    @Test fun mirrorReflectsAndOwnerGetsTheKill() {
        val g = game()
        g.p("p1").hp = 1
        g.give("p2", Card.MIRROR)
        g.turn("p1" to shoot("p2"), "p2" to idle(Card.MIRROR), "p3" to idle())
        assertFalse(g.p("p1").alive)
        assertEquals(2, g.p("p2").hp)
        assertEquals(1, g.p("p2").kills)
        assertEquals(listOf("mirror", "hit", "dead"), g.events.map { it.k })
    }

    @Test fun fanHitsEveryOpponentForOneBullet() {
        val g = game()
        g.give("p1", Card.FAN)
        assertFalse(g.move("p1", reload(Card.FAN)))   // only with a shot
        g.turn("p1" to Move(Act.SHOOT, null, Card.FAN, null), "p2" to dodge(), "p3" to idle())
        assertEquals(0, g.p("p1").ammo)
        assertEquals(2, g.p("p2").hp)
        assertEquals(1, g.p("p3").hp)
    }

    @Test fun jamMedkitAmmo() {
        val g = game()
        g.p("p3").hp = 1
        g.give("p1", Card.JAM); g.give("p2", Card.AMMO); g.give("p3", Card.MEDKIT)
        g.turn("p1" to idle(Card.JAM, "p2"), "p2" to shoot("p3", Card.AMMO), "p3" to idle(Card.MEDKIT))
        assertEquals(2, g.p("p3").hp)          // healed, the shot never came
        assertEquals(2, g.p("p2").ammo)        // 1 - 1 + 2
        assertTrue(g.events.any { it.k == "jam" && it.a == "p2" })
        assertTrue(g.p("p1").hand.isEmpty())
    }

    @Test fun medkitBeforeShotsAndCapped() {
        val g = game()
        g.p("p2").hp = 1
        g.give("p2", Card.MEDKIT)
        g.turn("p1" to shoot("p2"), "p2" to idle(Card.MEDKIT), "p3" to idle())
        assertTrue(g.p("p2").alive)
        g.goOn()
        g.p("p2").hp = Game.MAX_HP
        g.give("p2", Card.MEDKIT)
        g.turn("p1" to idle(), "p2" to idle(Card.MEDKIT), "p3" to idle())
        assertEquals(Game.MAX_HP, g.p("p2").hp)
    }

    @Test fun reloadDrawsUpToHandLimit() {
        val g = game()
        g.give("p1", Card.VEST)
        g.give("p2", Card.VEST, Card.VEST, Card.VEST)
        g.turn("p1" to reload(), "p2" to reload(), "p3" to idle())
        assertEquals(2, g.p("p1").hand.size)
        assertEquals(Game.MAX_HAND, g.p("p2").hand.size)
        repeat(3) { g.goOn(); g.turn("p1" to reload(), "p2" to idle(), "p3" to idle()) }
        assertEquals(Game.MAX_AMMO, g.p("p1").ammo)
    }

    @Test fun underdogGetsAnExtraCard() {
        val g = game()
        g.players.values.forEach { it.hp = 1 }
        g.turn("p1" to shoot("p2"), "p2" to idle(), "p3" to idle())
        g.goOn()
        g.turn("p1" to idle(), "p3" to idle())   // p2 is out; p1 has no bullet left
        // finish the showdown by hand: p1 shoots p3
        g.goOn(); g.p("p1").ammo = 1
        g.turn("p1" to shoot("p3"), "p3" to idle())
        assertTrue(g.showdownOver)
        assertEquals(5, g.p("p1").score); assertEquals(0, g.p("p3").score)
        g.goOn()
        assertEquals(Game.START_HAND, g.p("p1").hand.size)
        assertEquals(Game.START_HAND + 1, g.p("p2").hand.size)
        assertEquals(Game.START_HAND + 1, g.p("p3").hand.size)
    }

    @Test fun matchEndsAtTargetButNotOnATie() {
        val g = Game(Random(1))
        listOf("a", "b", "c").forEach { g.add(it, it.uppercase()).connected = true }
        g.setTarget(5); g.setCards(false)
        g.startGame()
        g.p("a").score = 4; g.p("b").score = 5
        g.players.values.forEach { it.hp = 1 }
        g.turn("a" to shoot("b"), "b" to idle(), "c" to shoot("a"))
        // a kills b (5), c kills a and survives (0+1+3 = 4), b stays on 5: a tie on 5 -> another showdown
        assertTrue(g.showdownOver)
        assertEquals(listOf(5, 5, 4), listOf("a", "b", "c").map { g.p(it).score })
        g.goOn()
        assertEquals(Game.Phase.PLAN, g.phase)
        g.players.values.forEach { it.hp = 1 }
        g.turn("a" to shoot("b"), "b" to idle(), "c" to idle())
        g.goOn()
        g.turn("a" to idle(), "c" to idle())
        g.goOn()
        // a: 6 after the kill; finish c
        g.p("a").ammo = 1
        g.turn("a" to shoot("c"), "c" to idle())
        assertTrue(g.showdownOver)
        g.goOn()
        assertEquals(Game.Phase.OVER, g.phase)
    }

    @Test fun readyFromLivingPlayersGoesOn() {
        val g = game(cards = false)
        g.turn("p1" to idle(), "p2" to idle(), "p3" to idle())
        assertFalse(g.next(null, false))
        g.setReady("p1"); g.setReady("p2")
        assertEquals(Game.Phase.REVEAL, g.phase)
        g.setReady("p3")
        assertEquals(Game.Phase.PLAN, g.phase)
        assertEquals(2, g.turn)
    }

    @Test fun timerPlaysTheTurnWithSilentStandingStill() {
        val g = game(cards = false)
        assertTrue(g.tick(1000))
        val dl = g.deadline
        assertEquals(1000 + Game.DEFAULT_SECONDS * 1000L, dl)
        g.move("p1", shoot("p2"))
        assertFalse(g.tick(dl - 1))
        assertTrue(g.tick(dl))
        assertEquals(Game.Phase.REVEAL, g.phase)
        assertEquals(1, g.p("p2").hp)
    }

    @Test fun secretsNeverLeak() {
        val g = game()
        g.give("p1", Card.SNIPER, Card.MIRROR)
        g.move("p1", shoot("p2", Card.SNIPER))
        val other = g.stateJson("p2", false, 0)
        val txt = other.toString()
        assertFalse(txt.contains("SNIPER")); assertFalse(txt.contains("MIRROR"))
        assertFalse(txt.contains("\"act\""))
        assertNull(other.opt("moves"))
        val p1 = other.getJSONArray("players").getJSONObject(0)
        assertTrue(p1.getBoolean("moved"))
        assertEquals(2, p1.getInt("cards"))
        // the host who plays sees only their own too
        assertFalse(g.stateJson("p3", true, 0).toString().contains("SNIPER"))
        // the owner sees hand and move
        val mine = g.stateJson("p1", false, 0).getJSONObject("me")
        assertEquals("SHOOT", mine.getJSONObject("move").getString("act"))
        assertEquals(2, mine.getJSONArray("hand").length())
        // after the reveal moves are public, hands still not
        g.move("p2", idle()); g.move("p3", idle())
        val after = g.stateJson("p2", false, 0)
        assertNotNull(after.getJSONObject("moves").getJSONObject("p1"))
        assertFalse(after.toString().contains("MIRROR"))
    }

    @Test fun tvSeesTheBoardOnly() {
        val lobby = Game()
        assertNull(lobby.boardJson(0))
        val g = game()
        g.give("p1", Card.SNIPER, Card.MIRROR)
        g.give("p2", Card.ANGEL)
        g.move("p1", shoot("p2", Card.SNIPER))
        val tv = g.boardJson(0)!!.toString()
        for (secret in listOf("SNIPER", "MIRROR", "ANGEL", "\"hand\"", "\"me\"", "\"act\"")) assertFalse(secret, tv.contains(secret))
        g.move("p2", idle()); g.move("p3", idle())
        val after = g.boardJson(0)!!
        assertEquals("SNIPER", after.getJSONObject("moves").getJSONObject("p1").getString("card"))   // played cards are public
        assertFalse(after.toString().contains("MIRROR"))                                            // the rest of the hand isn't
        assertFalse(after.has("me"))
    }

    @Test fun leavingBelowThreeCancels() {
        val g = game(4, cards = false)
        g.move("p1", idle()); g.move("p2", idle()); g.move("p3", idle())
        g.remove("p4")
        assertEquals(Game.Phase.REVEAL, g.phase)    // the rest had moved
        g.remove("p3")
        assertEquals(Game.Phase.LOBBY, g.phase)
        assertNotNull(g.notice)
    }

    @Test fun lateJoinerWaitsForNextShowdown() {
        val g = game(cards = false)
        g.add("p4", "P4").connected = true
        assertFalse(g.p("p4").alive)
        g.turn("p1" to idle(), "p2" to idle(), "p3" to idle())
        assertFalse(g.move("p4", idle()))
    }
}
