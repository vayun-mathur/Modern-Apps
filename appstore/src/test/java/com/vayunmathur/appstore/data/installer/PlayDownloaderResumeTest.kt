package com.vayunmathur.appstore.data.installer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.PlayFile
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.appstore.data.DownloadCache
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayDownloader resume and progress edges: a partial .tmp is resumed
 * with a Range request instead of restarted, unknown-size batches
 * report per-file progress, and a 403 is the expired-URL failure.
 */
@RunWith(RobolectricTestRunner::class)
class PlayDownloaderResumeTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val payload = ByteArray(5000) { (it % 251).toByte() }
    @Volatile private var lastRange: String? = null

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            lastRange = range
            if (range != null) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                val rest = payload.copyOfRange(from, payload.size)
                exchange.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, rest.size.toLong())
                exchange.responseBody.use { it.write(rest) }
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            exchange.close()
        }
        server.createContext("/forbidden") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun `a partial tmp file is resumed with a range request`() = runTest {
        val pkg = "com.example.resume"
        val dir = DownloadCache.playDir(context.cacheDir, pkg, 3L).apply { mkdirs() }
        File(dir, "base.apk.tmp").writeBytes(payload.copyOf(2000))

        val result = PlayDownloader(context).downloadFiles(
            pkg, 3L,
            listOf(PlayFile(name = "base.apk", url = "$base/apk", size = payload.size.toLong())),
        )
        assertTrue(result.isSuccess, "failed: ${result.exceptionOrNull()}")
        assertEquals("bytes=2000-", lastRange)
        assertTrue(result.getOrThrow().single().readBytes().contentEquals(payload))
    }

    @Test
    fun `unknown size batches report per file progress`() = runTest {
        val progress = mutableListOf<Float>()
        val result = PlayDownloader(context).downloadFiles(
            "com.example.unknownsize", 1L,
            listOf(
                PlayFile(name = "a.apk", url = "$base/apk", size = 0L),
                PlayFile(name = "b.apk", url = "$base/apk", size = 0L),
            ),
        ) { progress += it }
        assertTrue(result.isSuccess, "failed: ${result.exceptionOrNull()}")
        assertEquals(2, result.getOrThrow().size)
        assertTrue(progress.isNotEmpty() && progress.all { it in 0f..1f }, progress.toString())
    }

    @Test
    fun `a 403 is reported as an expired url`() = runTest {
        val result = PlayDownloader(context).downloadFiles(
            "com.example.forbidden", 1L,
            listOf(PlayFile(name = "base.apk", url = "$base/forbidden", size = 10L)),
        )
        assertTrue(result.exceptionOrNull() is PlayDownloader.ExpiredUrlException)
    }
}
