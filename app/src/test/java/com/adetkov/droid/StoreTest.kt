package com.adetkov.droid

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoreTest {
    private fun file() = File.createTempFile("convs", ".json").also { it.deleteOnExit() }
    private fun bad(f: File) = File(f.path + ".bad").also { it.deleteOnExit() }
    private fun conv(id: String) = """{"id":"$id","title":"T","sessionId":null,"createdAt":1,"updatedAt":1,"running":false,"messages":[]}"""

    @Test fun aChatThatCantBeReadDoesntTakeTheOthersWithIt() {
        val f = file()
        val text = """[${conv("a")},{"id":"b"},${conv("c")}]"""
        f.writeText(text)
        assertEquals(listOf("a", "c"), Store(f).convs.value.map { it.id })
        assertEquals(text, bad(f).readText())  // kept aside before a save leaves "b" out
    }

    @Test fun aFileThatCantBeReadIsKeptAside() {
        val f = file()
        f.writeText("""[${conv("a")},{"id":""")  // cut short
        assertTrue(Store(f).convs.value.isEmpty())
        assertEquals("""[${conv("a")},{"id":""", bad(f).readText())
    }

    @Test fun aSaveReplacesTheFileWhole() {
        val f = file().also { it.delete() }
        val store = Store(f)
        store.create("hi")
        val until = System.currentTimeMillis() + 5_000
        while (!f.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals(1, JSONArray(f.readText()).length())
        assertFalse(File(f.path + ".tmp").exists())
        assertFalse(bad(f).exists())
    }

    /** A wake-up's message exists nowhere else once it is out of later.json. */
    @Test fun saveNowDoesntWait() {
        val f = file().also { it.delete() }
        val store = Store(f, wait = 60_000)
        store.create("hi")
        store.saveNow()
        val until = System.currentTimeMillis() + 5_000
        while (!f.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals(1, JSONArray(f.readText()).length())
    }
}
