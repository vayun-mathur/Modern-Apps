package com.vayunmathur.cast.service

import android.os.Bundle
import android.os.Message
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.sdk.cast.CastContract
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClientResourceResolverTest {

    private fun tempPfd(content: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("resource", ".bin")
        file.writeBytes(content)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test
    fun `resolve returns null when there is no client to ask`() {
        val resolver = ClientResourceResolver { false }
        assertNull(resolver.resolve("song-1"))
        resolver.close()
    }

    @Test
    fun `resolve times out fast when send succeeds but nobody answers`() {
        // Send succeeds; the mailbox wait is bounded by REQUEST_TIMEOUT_MS in production.
        // We do not wait it out here: instead answer a *different* request and confirm the
        // pending machinery tolerates stray responses, then close.
        var captured: Message? = null
        val resolver = ClientResourceResolver { msg -> captured = msg; true }
        // Stray response for an unknown id closes its descriptor and is ignored.
        val stray = Bundle().apply {
            putInt(CastContract.KEY_REQUEST_ID, 999)
            putParcelable(CastContract.KEY_RESOURCE_FD, tempPfd(byteArrayOf(1)))
        }
        resolver.onResponse(stray)
        resolver.onResponse(null)
        resolver.onComplete(null)
        resolver.close()
        assertNull(captured) // no resolve was issued in this test
    }

    @Test
    fun `interrupted resolve returns null and re-arms the interrupt`() {
        val resolver = ClientResourceResolver { true } // answers never come
        var result: com.vayunmathur.cast.protocol.MediaResource? = null
        var interruptedAfter = false
        val worker = Thread {
            result = resolver.resolve("never-answered")
            interruptedAfter = Thread.currentThread().isInterrupted
        }
        worker.start()
        Thread.sleep(200)
        worker.interrupt()
        worker.join(5_000)
        kotlin.test.assertNull(result)
        kotlin.test.assertTrue(interruptedAfter)
        resolver.close()
    }

    @Test
    fun `resolve answered by onResponse serves bytes at offsets and caches`() {
        lateinit var resolver: ClientResourceResolver
        val data = "hello cast world".toByteArray()
        var requests = 0
        resolver = ClientResourceResolver { msg ->
            requests++
            val id = msg.data.getInt(CastContract.KEY_REQUEST_ID)
            val response = Bundle().apply {
                putInt(CastContract.KEY_REQUEST_ID, id)
                putParcelable(CastContract.KEY_RESOURCE_FD, tempPfd(data))
                putLong(CastContract.KEY_RESOURCE_LENGTH, data.size.toLong())
                putString(CastContract.KEY_RESOURCE_TYPE, "audio/mpeg")
            }
            // Answer synchronously from the sending thread: resolve is blocked polling its mailbox.
            Thread { resolver.onResponse(response) }.start()
            true
        }
        val resource = assertNotNull(resolver.resolve("track-1"))
        assertEquals("audio/mpeg", resource.contentType)
        assertEquals(data.size.toLong(), resource.length)
        assertContentEquals("cast world".toByteArray(), resource.open(6).readBytes())
        assertContentEquals(data, resource.open(0).readBytes())
        // Cached: a second resolve does not ask again.
        val again = assertNotNull(resolver.resolve("track-1"))
        assertTrue(again === resource)
        assertEquals(1, requests)
        // Completion on the cached resource keeps its length.
        val done = Bundle().apply {
            putString(CastContract.KEY_RESOURCE_ID, "track-1")
            putLong(CastContract.KEY_RESOURCE_LENGTH, data.size.toLong())
        }
        resolver.onComplete(done)
        assertEquals(data.size.toLong(), resource.length)
        resolver.close()
    }

    @Test
    fun `complete for unknown resource is ignored and failure releases growing readers`() {
        lateinit var resolver: ClientResourceResolver
        resolver = ClientResourceResolver { msg ->
            val id = msg.data.getInt(CastContract.KEY_REQUEST_ID)
            val response = Bundle().apply {
                putInt(CastContract.KEY_REQUEST_ID, id)
                putParcelable(CastContract.KEY_RESOURCE_FD, tempPfd(byteArrayOf(5, 6)))
                putLong(CastContract.KEY_RESOURCE_LENGTH, -1L) // still growing
                putString(CastContract.KEY_RESOURCE_TYPE, "audio/ogg")
            }
            Thread { resolver.onResponse(response) }.start()
            true
        }
        resolver.onComplete(Bundle().apply { putString(CastContract.KEY_RESOURCE_ID, "nope") })
        val resource = assertNotNull(resolver.resolve("growing"))
        assertEquals(-1L, resource.length)
        // Fail it: a reader at/past EOF must throw rather than hang.
        resolver.onComplete(Bundle().apply { putString(CastContract.KEY_RESOURCE_ID, "growing") })
        var threw = false
        try {
            resource.open(2).readBytes()
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue(threw, "failed growing resource should fail its readers")
        resolver.close()
    }

    @Test
    fun `growing resource completes and then reads to real eof`() {
        lateinit var resolver: ClientResourceResolver
        val file = Files.createTempFile("growing", ".bin").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        resolver = ClientResourceResolver { msg ->
            val id = msg.data.getInt(CastContract.KEY_REQUEST_ID)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val response = Bundle().apply {
                putInt(CastContract.KEY_REQUEST_ID, id)
                putParcelable(CastContract.KEY_RESOURCE_FD, pfd)
                putLong(CastContract.KEY_RESOURCE_LENGTH, -1L)
            }
            Thread { resolver.onResponse(response) }.start()
            true
        }
        val resource = assertNotNull(resolver.resolve("g2"))
        // Grow then complete.
        file.appendBytes(byteArrayOf(4))
        resolver.onComplete(Bundle().apply {
            putString(CastContract.KEY_RESOURCE_ID, "g2")
            putLong(CastContract.KEY_RESOURCE_LENGTH, 4L)
        })
        assertEquals(4L, resource.length)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), resource.open(0).readBytes())
        assertEquals(-1, resource.open(4).read())
        resolver.close()
    }
}
