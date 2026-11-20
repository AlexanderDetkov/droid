package com.adetkov.droid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AttachmentsTest {
    @Test fun namesKeepTheirExtension() {
        assertEquals("file.jpg", safeName("写真.jpg"))
        assertEquals("Fotka_z_dovolen.JPG", safeName("Fotka z dovolené.JPG"))
        assertEquals("archive.tar.gz", safeName("archive.tar.gz"))
        assertEquals("bashrc", safeName(".bashrc"))
        assertEquals("Report_v2.final_draft", safeName("Report v2.final draft"))
        assertEquals("file", safeName("…"))
        val long = safeName("a".repeat(200) + ".pdf")
        assertEquals(80, long.length)
        assertTrue(long.endsWith("a.pdf"))
        assertEquals(80, safeName("b".repeat(200)).length)
    }

    @Test fun copiesUpToTheCapAndNoFurther() {
        val data = ByteArray(300_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        assertTrue(copyAtMost(ByteArrayInputStream(data), out, data.size.toLong()))
        assertArrayEquals(data, out.toByteArray())
        assertFalse(copyAtMost(ByteArrayInputStream(data), ByteArrayOutputStream(), data.size - 1L))
    }
}
