package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Test

class LicensesTest {
    /** A license's hard-wrapped lines read as paragraphs that fit the screen; its lists and indented lines stay as they are. */
    @Test fun hardWrappedLinesBecomeParagraphs() {
        val text = "      \"License\" shall mean the terms\n      and conditions.\n\n   1. Definitions.\n\n" +
            "Some text\nwrapped here.\n  (a) kept\n- an item\n- another"
        assertEquals(listOf("\"License\" shall mean the terms and conditions.", "1. Definitions.",
            "Some text wrapped here.\n  (a) kept\n- an item\n- another"), paragraphs(text))
    }
}
