package com.sokolovskiy.party.games.herd

import org.junit.Assert.assertEquals
import org.junit.Test

class UnigramTest {

    // ids: 0 pad, 1 cls, 2 sep, 3 unk, then pieces (line number = id)
    private val vocab = Unigram(
        listOf(
            "#unk=3 cls=1 sep=2 pad=0 pair=0",
            "<pad>\t0.0", "<s>\t0.0", "</s>\t0.0", "<unk>\t0.0",
            "▁\t-5.0",
            "▁ко\t-4.0", "т\t-3.0", "▁кіт\t-3.5", "▁к\t-6.0", "о\t-6.0",
            "ки\t-4.0", "▁кот\t-9.0",
        )
    )

    @Test fun picksBestScoringSegmentation() {
        // «кіт» as one piece (-3.5) beats «▁к»+…; «кот» = ▁ко + т (-7.0) beats ▁кот (-9.0)
        assertEquals(listOf(7), vocab.encode("кіт"))
        assertEquals(listOf(5, 6), vocab.encode("кот"))
    }

    @Test fun unknownCharactersFuseIntoOneUnk() {
        // "▁" is known, both «щ» are not: they collapse into a single <unk>
        assertEquals(listOf(4, 3), vocab.encode("щщ"))
    }

    @Test fun truncatesAndSkipsBlank() {
        assertEquals(emptyList<Int>(), vocab.encode("   "))
        assertEquals(2, vocab.encode("кіт кіт кіт", maxTokens = 2).size)
    }
}
