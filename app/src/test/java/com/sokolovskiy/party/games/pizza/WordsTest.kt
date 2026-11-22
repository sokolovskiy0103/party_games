package com.sokolovskiy.party.games.pizza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WordsTest {
    private val words = realWords()

    @Test fun bankIsBigEnough() {
        assertTrue(words.adjectives.size >= 40)
        assertTrue(words.nouns.size >= 45)
        assertTrue(words.optionSets.all { it.size in 3..4 && it.toSet().size == it.size })
        assertEquals(4, words.colors.size)
        assertTrue(words.places.isNotEmpty() && words.warpLines.isNotEmpty() && words.overLines.isNotEmpty())
        assertTrue(words.nouns.all { it.g in setOf("m", "f", "n", "p") })
        assertTrue(words.adjectives.all { it.endsWith("ий") || it.endsWith("ій") })
        assertEquals(words.adjectives.size, words.adjectives.toSet().size)
        assertEquals(words.nouns.size, words.nouns.map { it.w }.toSet().size)
    }

    @Test fun adjectivesAgreeWithTheNoun() {
        fun n(w: String) = words.nouns.first { it.w == w }
        assertEquals(Name("Квантовий клапан", "Квантовий клапан", "клапан"), words.name("Квантовий", n("клапан")))
        assertEquals(Name("Квантова помпа", "Квантову помпу", "помпа"), words.name("Квантовий", n("помпа")))
        assertEquals(Name("Квантове сопло", "Квантове сопло", "сопло"), words.name("Квантовий", n("сопло")))
        assertEquals(Name("Квантові щипці", "Квантові щипці", "щипці"), words.name("Квантовий", n("щипці")))
        assertEquals(Name("Синя шестерня", "Синю шестерню", "шестерня"), words.name("Синій", n("шестерня")))
        assertEquals(Name("Синє ядро", "Синє ядро", "ядро"), words.name("Синій", n("ядро")))
        assertEquals(Name("Гаряча піч", "Гарячу піч", "піч"), words.name("Гарячий", n("піч")))
    }

    @Test fun phrasesUseTheRightCase() {
        val name = words.name("Сирний", words.nouns.first { it.w == "помпа" })
        val on = words.phrase("on", name, "", Random(1))
        assertTrue(on, on.endsWith("Сирну помпу"))
        val kp = words.phrase("keypad", name, "7", Random(1))
        assertTrue(kp, kp.startsWith("Сирна помпа:") && kp.endsWith("7"))
        val seg = words.phrase("seg", name, "Фета", Random(1))
        assertTrue(seg, seg.contains("Сирну помпу") && seg.contains("«Фета»"))
        Words.VERB_KEYS.forEach { k ->
            val s = words.phrase(k, name, "3", Random(2))
            assertFalse(s, s.contains('{'))
        }
    }

    @Test fun namesAreUniqueAndNounsFreshEarly() {
        val bag = NameBag(words, Random(4), sameNoun = false)
        val names = (1..45).map { bag.next() }
        assertEquals(45, names.map { it.nom }.toSet().size)
        assertEquals(45, names.map { it.noun }.toSet().size)
        val bag2 = NameBag(words, Random(4), sameNoun = true)
        val more = (1..120).map { bag2.next() }
        assertEquals(120, more.map { it.nom }.toSet().size)
    }
}
