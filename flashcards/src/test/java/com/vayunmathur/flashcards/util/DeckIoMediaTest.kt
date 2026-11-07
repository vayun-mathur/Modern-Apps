package com.vayunmathur.flashcards.util

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeckIoMediaTest {

    @Test
    fun csvRoundTripWithQuoting() {
        val rows = listOf("a,b" to "say \"hi\"", "plain" to "simple", "" to "only back")
        val text = DeckIo.writeCsv(rows)
        assertTrue(text.startsWith("front,back\n"))
        assertEquals(rows, DeckIo.parseCsv(text))
    }

    @Test
    fun parseCsvSkipsHeaderBlankAndShortRows() {
        val parsed = DeckIo.parseCsv("front,back\n\nonlyone\na,b\n,c\n")
        assertEquals(listOf("a" to "b", "" to "c"), parsed)
    }

    @Test
    fun parseCsvHandlesQuotedCommasAndEscapedQuotes() {
        assertEquals(
            listOf("x,y" to "he said \"z\""),
            DeckIo.parseCsv("\"x,y\",\"he said \"\"z\"\"\""),
        )
        assertEquals(emptyList(), DeckIo.parseCsv(""))
        assertEquals(emptyList(), DeckIo.parseCsv("front,back\n"))
    }

    @Test
    fun mediaStoreWriteResolveAndGc() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = MediaStore(ctx)
        val name = store.writeBytes("pic.png", byteArrayOf(1, 2, 3))
        assertEquals("pic.png", name)
        assertTrue(store.resolve(name).exists())
        assertEquals(3, store.resolve(name).readBytes().size)
        // Collision renames.
        val second = store.writeBytes("pic.png", byteArrayOf(9))
        assertEquals("pic(1).png", second)
        val third = store.writeBytes("noext", byteArrayOf(1))
        assertEquals("noext", third)
        val fourth = store.writeBytes("noext", byteArrayOf(1))
        assertEquals("noext(1)", fourth)
        store.garbageCollect(setOf(name))
        assertTrue(store.resolve(name).exists())
        assertFalse(store.resolve(second).exists())
        assertFalse(store.resolve(fourth).exists())
    }

    @Test
    fun mediaReferencedExtraction() {
        assertEquals(
            setOf("a.png", "b.jpg"),
            MediaStore.referenced("x ![alt](a.png) y ![]( b.jpg ) ![z](a.png)"),
        )
        assertEquals(emptySet(), MediaStore.referenced("no images [link](x.png)"))
    }
}
