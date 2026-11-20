package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {
    @Test fun splitsSentencesAndLines() = assertEquals(
        listOf("The flight leaves at 6:45 a.m. on Thursday for $212.", "Should I book it, or wait for a cheaper one?", "First item. Second item."),
        sentences("The flight leaves at 6:45 a.m. on Thursday for $212. Should I book it, or wait for a cheaper one?\nFirst item\nSecond item"),
    )

    @Test fun mergesShortSentencesAfterTheFirst() = assertEquals(
        listOf("Done.", "I set it. It rings at seven tomorrow morning."),
        sentences("Done. I set it. It rings at seven tomorrow morning."),
    )

    @Test fun cutsALongFirstSentenceAtAComma() = assertEquals(
        listOf("The Moon is Earth's only natural satellite,", "and it orbits our planet at an average distance of about 384,400 kilometers.", "It formed long ago."),
        sentences("The Moon is Earth's only natural satellite, and it orbits our planet at an average distance of about 384,400 kilometers. It formed long ago."),
    )

    @Test fun emptyText() = assertEquals(emptyList<String>(), sentences("  \n "))
}
