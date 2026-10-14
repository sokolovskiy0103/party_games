package com.sokolovskiy.party.games.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerRulesTest {

    @Test fun cleanKeepsApostrophes() {
        assertEquals("п'ять секунд", AnswerRules.clean("  П’ЯТЬ   секунд!! "))
        assertEquals("елка", AnswerRules.clean("Ёлка"))
    }

    @Test fun numbers() {
        assertEquals(5L, AnswerRules.number("п'ять"))
        assertEquals(5L, AnswerRules.number("5 секунд"))
        assertEquals(30L, AnswerRules.number("Тридцять"))
        assertNull(AnswerRules.number("привіт"))
    }

    @Test fun containment() {
        assertTrue(AnswerRules.contains("перець", "чорний перець"))
        assertTrue(AnswerRules.contains("пульт від телевізора", "пульт"))
        assertFalse(AnswerRules.contains("вино", "біле вино")) // stem too short to trust
        assertFalse(AnswerRules.contains("кава", "чай"))
    }

    @Test fun rulesOverrideModelScores() {
        val answers = listOf("3", "тридцять", "п'ять", "5", "перець", "чорний перець")
        val n = answers.size
        val high = Array(n) { DoubleArray(n) { 0.9 } }       // model says everything is alike
        val adjusted = AnswerRules.adjust(answers, high)
        assertEquals(0.0, adjusted[0][1], 0.0)   // 3 vs 30
        assertEquals(1.0, adjusted[2][3], 0.0)   // п'ять vs 5
        assertEquals(1.0, adjusted[4][5], 0.0)   // перець vs чорний перець
        assertEquals(0.0, adjusted[3][4], 0.0)   // number vs word
    }

    @Test fun clusteringAveragesLinkage() {
        val sim = arrayOf(
            doubleArrayOf(1.0, 0.9, 0.8, 0.1),
            doubleArrayOf(0.9, 1.0, 0.2, 0.1),
            doubleArrayOf(0.8, 0.2, 1.0, 0.1),
            doubleArrayOf(0.1, 0.1, 0.1, 1.0),
        )
        // 0-1 merge (0.9), then the pair vs 2 averages (0.8 + 0.2) / 2 = 0.5 < 0.55 → stays apart
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), AnswerRules.cluster(sim, 0.55))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), AnswerRules.cluster(sim, 0.45))
    }
}
