package com.vayunmathur.cast.service

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.sdk.cast.CastContract
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Reads on a still-growing resource: wait, then complete, then read the real end. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrowingResourceTest {

    private fun growingResolver(file: File): Pair<ClientResourceResolver, () -> Unit> {
        lateinit var resolver: ClientResourceResolver
        resolver = ClientResourceResolver { msg ->
            val id = msg.data.getInt(CastContract.KEY_REQUEST_ID)
            val response = Bundle().apply {
                putInt(CastContract.KEY_REQUEST_ID, id)
                putParcelable(
                    CastContract.KEY_RESOURCE_FD,
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                )
                putLong(CastContract.KEY_RESOURCE_LENGTH, -1L)
                putString(CastContract.KEY_RESOURCE_TYPE, "audio/ogg")
            }
            thread { resolver.onResponse(response) }
            true
        }
        return resolver to { resolver.close() }
    }

    @Test
    fun `reader waits for growth then sees completion`() {
        val file = File.createTempFile("growing-wait", ".bin")
        file.writeBytes(byteArrayOf(1, 2))
        val (resolver, close) = growingResolver(file)
        try {
            val resource = assertNotNull(resolver.resolve("g"))
            val stream = resource.open(2) // at the current end: must wait, not EOF
            val completer = thread {
                Thread.sleep(250)
                file.appendBytes(byteArrayOf(3, 4))
                resolver.onComplete(Bundle().apply {
                    putString(CastContract.KEY_RESOURCE_ID, "g")
                    putLong(CastContract.KEY_RESOURCE_LENGTH, 4L)
                })
            }
            val first = stream.read()
            assertEquals(3, first)
            assertContentEquals(byteArrayOf(4), stream.readBytes())
            completer.join(5_000)
        } finally {
            close()
        }
    }

    private fun assertEquals(expected: Int, actual: Int) =
        kotlin.test.assertEquals(expected, actual)
}
