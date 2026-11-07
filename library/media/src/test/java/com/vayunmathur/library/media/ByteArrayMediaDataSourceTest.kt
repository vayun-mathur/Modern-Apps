package com.vayunmathur.library.media

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ByteArrayMediaDataSourceTest {

    private val bytes = ByteArray(64) { (it * 3).toByte() }

    @Test
    fun `reads through the media data source contract`() {
        val source = ByteArrayMediaDataSource(bytes)
        assertEquals(bytes.size.toLong(), source.size)
        val destination = ByteArray(16)
        assertEquals(8, source.readAt(4, destination, 3, 8))
        assertTrue(bytes.copyOfRange(4, 12).contentEquals(destination.copyOfRange(3, 11)))
    }

    @Test
    fun `truncates at the end and reports eos as minus one`() {
        val source = ByteArrayMediaDataSource(bytes)
        val destination = ByteArray(16)
        assertEquals(4, source.readAt(60, destination, 0, 16))
        assertEquals(-1, source.readAt(64, destination, 0, 4))
        assertEquals(-1, source.readAt(-5, destination, 0, 4))
        assertEquals(0, source.readAt(10, destination, 0, 0))
    }

    @Test
    fun `close is a no op`() {
        val source = ByteArrayMediaDataSource(bytes)
        source.close()
        assertEquals(bytes.size.toLong(), source.size)
        assertEquals(2, source.readAt(0, ByteArray(4), 0, 2))
    }
}
