package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun blocks() {
        val b = parseBlocks("Intro **bold**\n\n- one\n* two\n1. first\n2) second\n\n```kotlin\nval x = 1\n```\n# Title\nend")
        assertEquals(
            listOf(
                Block.Para("Intro **bold**"), Block.Item("•", "one"), Block.Item("•", "two"),
                Block.Item("1.", "first"), Block.Item("2.", "second"),
                Block.Code("val x = 1", "kotlin"), Block.Heading("Title"), Block.Para("end"),
            ),
            b,
        )
    }

    @Test fun headingKeepsATrailingHash() {
        assertEquals(listOf(Block.Heading("Using C#", 2), Block.Heading("Closed", 2)), parseBlocks("## Using C#\n## Closed ##"))
    }

    @Test fun underscoresInAPathStayPlain() {
        assertEquals("open /a/_tmp_/b and stay", stripMarkdown("open /a/_tmp_/b and _stay_"))
    }

    @Test fun stripForSpeech() {
        val s = stripMarkdown("Hello **there** and *you*, see [docs](https://a.b/c) or https://x.y.\n- item `code`\n```\nskip me\n```", spoken = true)
        assertEquals("Hello there and you, see docs or link.\nitem code", s)
    }

    @Test fun stripForReading() {
        val s = stripMarkdown("Hello **there**, see [docs](https://a.b/c) or https://x.y.\n\n- one\n  - two `code`\n\n```\nkeep me\n```")
        assertEquals("Hello there, see docs (https://a.b/c) or https://x.y.\n\n• one\n  • two code\n\nkeep me", s)
    }

    @Test fun table() {
        val md = "Apps:\n\n| # | App | Category |\n|---|:---|---:|\n| 1 | **Maps** | Travel |\n| 2 | Mail | Work |\n\nDone"
        assertEquals(
            listOf(
                Block.Para("Apps:"),
                Block.Table(listOf("#", "App", "Category"), listOf(listOf("1", "**Maps**", "Travel"), listOf("2", "Mail", "Work"))),
                Block.Para("Done"),
            ),
            parseBlocks(md),
        )
        assertEquals("Apps:\n#, App, Category\n1, Maps, Travel\n2, Mail, Work\nDone", stripMarkdown(md, spoken = true))
        assertEquals("Apps:\n\n# | App | Category\n1 | Maps | Travel\n2 | Mail | Work\n\nDone", stripMarkdown(md))
    }

    @Test fun quotesNestingRulesAndHeadingLevels() {
        val b = parseBlocks("## Plan\n> first line\n> second\n\n- top\n  - nested\n    1. deep\n---\nafter")
        assertEquals(
            listOf(
                Block.Heading("Plan", 2), Block.Quote("first line\nsecond"),
                Block.Item("•", "top"), Block.Item("•", "nested", 1), Block.Item("1.", "deep", 2),
                Block.Rule, Block.Para("after"),
            ),
            b,
        )
        assertEquals("Plan\nfirst line\nsecond\ntop\nnested\ndeep\nafter", stripMarkdown("## Plan\n> first line\n> second\n\n- top\n  - nested\n    1. deep\n---\nafter", spoken = true))
    }

    @Test fun pipeWithoutSeparatorIsNotATable() {
        assertEquals(listOf<Block>(Block.Para("a | b\nc | d")), parseBlocks("a | b\nc | d"))
    }
}
