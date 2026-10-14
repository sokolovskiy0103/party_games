package com.sokolovskiy.party.games.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    @Test fun sameMeaningForms() {
        assertTrue(FuzzyMatcher.same("в машині", "Машина!"))
        assertTrue(FuzzyMatcher.same("книжка", "книжку"))
        assertTrue(FuzzyMatcher.same("оргазм", "Оргазм."))
        assertTrue(FuzzyMatcher.same("холодильник", "холодильнік")) // typo
    }

    @Test fun differentThingsStayApart() {
        assertFalse(FuzzyMatcher.same("пиво", "вино"))
        assertFalse(FuzzyMatcher.same("кіт", "кит"))
        assertFalse(FuzzyMatcher.same("3", "4"))
        assertFalse(FuzzyMatcher.same("кухня", "ванна"))
    }

    @Test fun grouping() {
        val g = FuzzyMatcher.group(listOf("книжка", "аптечка", "Книжку", "праска", "книжка"))
        assertEquals(listOf(listOf(0, 2, 4), listOf(1), listOf(3)), g)
    }
}
