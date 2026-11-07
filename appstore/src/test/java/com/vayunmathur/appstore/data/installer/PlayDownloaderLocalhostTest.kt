package com.vayunmathur.appstore.data.installer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.PlayFile
import com.sun.net.httpserver.HttpServer
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
 * PlayDownloader against a loopback server: files land in the cache with
 * the served bytes, a second run reuses the completed cache entry, and a
 * blank URL fails the batch instead of creating an empty file.
 */
@RunWith(RobolectricTestRunner::class)
class PlayDownloaderLocalhostTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val bytes = ByteArray(5000) { (it % 253).toByte() }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/gone") { exchange ->
            exchange.sendResponseHeaders(410, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `downloads files with progress and reuses the completed cache`() = runTest {
        val downloader = PlayDownloader(context())
        val files = listOf(PlayFile(name = "base.apk", url = "$base/apk", size = 5000L))
        val progress = mutableListOf<Float>()

        val first = downloader.downloadFiles("com.example.dl", 7L, files) { progress += it }
        assertTrue(first.isSuccess, "first download failed: ${first.exceptionOrNull()}")
        val downloaded = first.getOrThrow().single()
        assertEquals(5000L, downloaded.length())
        assertEquals((4999 % 253).toByte(), downloaded.readBytes()[4999])
        assertTrue(progress.isNotEmpty(), "no progress reported")
        assertTrue(progress.all { it in 0f..1f })

        // Second run: the completed cache entry is reused, not re-downloaded.
        val second = downloader.downloadFiles("com.example.dl", 7L, files)
        assertTrue(second.isSuccess)
        assertEquals(downloaded.absolutePath, second.getOrThrow().single().absolutePath)
    }

    @Test
    fun `a blank url fails the batch`() = runTest {
        val downloader = PlayDownloader(context())
        val result = downloader.downloadFiles(
            "com.example.blank",
            1L,
            listOf(PlayFile(name = "base.apk", url = "", size = 10L)),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `an expired url fails with the expired exception`() = runTest {
        val downloader = PlayDownloader(context())
        val result = downloader.downloadFiles(
            "com.example.gone",
            1L,
            listOf(PlayFile(name = "base.apk", url = "$base/gone", size = 10L)),
        )
        assertTrue(result.isFailure, "expected failure, got ${result.getOrNull()}")
    }

    @Test
    fun `an empty file list succeeds with no files`() = runTest {
        val downloader = PlayDownloader(context())
        val result = downloader.downloadFiles("com.example.empty", 1L, emptyList())
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }
}
