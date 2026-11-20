package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActsTest {
    private val answer = """
        The reply to Ines is written. Here it is, not sent yet:
        ![The draft](/tmp/shot-16.png)
        I also saved [the folio](~/folio.pdf) and checked https://example.com.

        [Send it](reply:Send it) [Not yet](reply: Hold off, don't send it) [Sign](screen:)
        See [the site](https://casadolimoeiro.pt) too.
    """.trimIndent()

    @Test fun findsWhatAnAnswerOffers() {
        assertEquals(listOf(
            Act.Show("/tmp/shot-16.png", "The draft"),
            Act.Show("~/folio.pdf", "the folio"),
            Act.Reply("Send it", "Send it"),
            Act.Reply("Not yet", "Hold off, don't send it"),
            Act.Screen("Sign"),
        ), acts(answer))
    }

    @Test fun aReplyWithoutTextSendsItsLabel() {
        assertEquals(listOf(Act.Reply("Yes", "Yes")), acts("[Yes](reply:)"))
    }

    @Test fun aRelativePictureIsShownButAWebLinkIsNot() {
        assertEquals(listOf(Act.Show("report.png", "")), acts("![](report.png) and ![logo](https://x.org/a.png)"))
    }

    @Test fun answerTextPutsPicturesOnTheirOwnLine() {
        val t = answerText("Look: ![x](/tmp/a.png) ok")
        assertEquals("Look:\n![x](/tmp/a.png)\nok", t)
        val blocks = parseBlocks(t)
        assertEquals(listOf(Block.Para("Look:"), Block.Show("/tmp/a.png", "x"), Block.Para("ok")), blocks)
    }

    @Test fun anOldReplyOrScreenLinkReadsAsItsLabel() {
        assertEquals("Or edit it yourself on my screen.", answerText("Or [edit it yourself](screen:) on my screen."))
        assertEquals("Want a 7am alarm?\nYes No", answerText("Want a 7am alarm?\n[Yes](reply:yes) [No](reply:no)"))
    }

    @Test fun aFileLinkedInASentenceStaysInIt() {
        assertEquals("Saved [it](~/a.pdf) for you.", answerText("Saved [it](~/a.pdf) for you."))
    }

    @Test fun localizePointsShownFilesAtTheirCopies() {
        val copies = mapOf("/tmp/shot-16.png" to "/data/shown/c/1-shot-16.png", "~/folio.pdf" to "/data/shown/c/2-folio.pdf")
        val t = localize(answer, copies)
        assertTrue("![The draft](/data/shown/c/1-shot-16.png)" in t)
        assertTrue("[the folio](/data/shown/c/2-folio.pdf)" in t)
        assertTrue("[Send it](reply:Send it)" in t)
    }

    @Test fun plainTextKeepsLinkLabelsAndCaptions() {
        val plain = stripMarkdown(answer)
        assertFalse("reply:" in plain)
        assertFalse("screen:" in plain)
        assertTrue("Sign" in plain)
        assertTrue("The draft" in plain)
        assertTrue("I also saved the folio and" in plain)
        assertTrue("the site (https://casadolimoeiro.pt)" in plain)
    }

    @Test fun memoryNotesComeInTheIndexOrderThenTheRest() {
        val index = "- [Phone mail](phone-mail.md) — Outlook accounts\n* [Gone](gone.md) — deleted since\n- [Tasks](tasks-app.md)\n"
        val n = notes(index, listOf("MEMORY.md", "tasks-app.md", "phone-mail.md", "bank-access.md", ""))
        assertEquals(listOf("Phone mail", "Tasks", "Bank access"), n.map { it.title })
        assertEquals(listOf("Outlook accounts", "", ""), n.map { it.about })
        assertEquals("$MEMORY_DIR/phone-mail.md", n[0].doc.path)
    }

    @Test fun aNotesFrontmatterIsKeptApart() {
        val note = "---\nname: x\ndescription: y\n---\n\nThe fact.\n"
        val (head, body) = frontmatter(note)
        assertEquals("The fact.\n", body)
        assertEquals(note.replace("\nThe fact.\n", "").trimEnd('\n') + "\n", head)
        assertEquals("" to "No header.", frontmatter("No header."))
    }

    @Test fun systemInstructionsSayHowDroidRunsOnThisPhone() {
        val template = "From Alpine Linux, {where}.\n`android` runs a command in Android {as}."
        assertEquals("From Alpine Linux, in a chroot with root.\n`android` runs a command in Android as root.",
            systemInstructions(template, root = true))
        val shell = systemInstructions(template, root = false)
        assertTrue(shell.startsWith("From Alpine Linux, under proot."))
        assertTrue("as adb's shell user" in shell)
    }

    @Test fun recognizesTheOldDefaultInstructions() {
        val old = javaClass.classLoader!!.getResource("old-default-CLAUDE.md")!!.readText()
        assertTrue(isOldDefaultInstructions(old))
        assertTrue(isOldDefaultInstructions("\n" + old.trim() + "\n\n"))
        assertFalse(isOldDefaultInstructions(old + "\n- I'm vegetarian."))
    }

    @Test fun pathsAreAsTheAgentSeesThem() {
        assertEquals("/root/a.png", linuxPath("~/a.png"))
        assertEquals("/root/out/a.png", linuxPath("out/a.png"))
        assertEquals("/sdcard/Download/a.pdf", linuxPath("/sdcard/Download/a.pdf"))
        assertEquals("'it'\\''s.png'", shellQuote("it's.png"))
    }
}
