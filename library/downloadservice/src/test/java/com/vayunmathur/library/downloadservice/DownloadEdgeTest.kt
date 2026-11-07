package com.vayunmathur.library.downloadservice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadEdgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ds: DataStoreUtils = DataStoreUtils.getInstance(context)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun transientHttpFailureIsRetriedAndThenSucceeds() = runBlocking<Unit> {
        val payload = "second-attempt".toByteArray()
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (hits.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
        try {
            val dir = kotlin.io.path.createTempDirectory("dl_flaky").toFile()
            val spec = DownloadSpec(
                fileName = "flaky.bin", description = "Flaky",
                url = "http://127.0.0.1:${server.address.port}/flaky.bin",
                sha256 = sha256(payload),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertEquals(2, hits.get())
            assertTrue(File(dir, "flaky.bin").readBytes().contentEquals(payload))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun chunkedResponseWithoutContentLengthDownloads() = runBlocking<Unit> {
        val payload = "no-length".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // 0 means chunked: HttpURLConnection reports contentLengthLong == -1.
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val dir = kotlin.io.path.createTempDirectory("dl_chunked").toFile()
            val spec = DownloadSpec(
                fileName = "chunked.bin", description = "Chunked",
                url = "http://127.0.0.1:${server.address.port}/chunked.bin",
                sha256 = sha256(payload),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertTrue(File(dir, "chunked.bin").readBytes().contentEquals(payload))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun slowDripPublishesProgressAndSpeed() = runBlocking<Unit> {
        val payload = ByteArray(64 * 1024) { (it % 241).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < payload.size) {
                    val chunk = minOf(4096, payload.size - offset)
                    out.write(payload, offset, chunk)
                    out.flush()
                    offset += chunk
                    Thread.sleep(80)
                }
            }
        }
        server.start()
        try {
            val dir = kotlin.io.path.createTempDirectory("dl_drip").toFile()
            val spec = DownloadSpec(
                fileName = "drip.bin", description = "Drip",
                url = "http://127.0.0.1:${server.address.port}/drip.bin",
                sha256 = sha256(payload), expectedBytes = payload.size.toLong(),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertTrue(File(dir, "drip.bin").readBytes().contentEquals(payload))
            // The throttled publisher ran during the ~1.3 s stream.
            val progress = withTimeout(10_000) { ds.doubleFlow("progress_drip.bin").first() }
            assertEquals(1.0, progress)
            val speed = withTimeout(10_000) { ds.doubleFlow("speed_drip.bin").first() }
            assertEquals(0.0, speed)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyDownloadManagerClaimIsSeveredOnce() = runBlocking<Unit> {
        val payload = "legacy-file".toByteArray()
        val dir = kotlin.io.path.createTempDirectory("dl_legacy_claim").toFile()
        File(dir, "legacy.bin").writeBytes(payload)
        ds.setLong("dlid_legacy.bin", 12345L)
        val spec = DownloadSpec(
            fileName = "legacy.bin", description = "Legacy",
            url = "http://127.0.0.1:1/legacy.bin", sha256 = sha256(payload),
        )
        assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
        // File survived the migration and the stale id was cleared.
        assertTrue(File(dir, "legacy.bin").readBytes().contentEquals(payload))
        val deadline = System.currentTimeMillis() + 5_000
        var cleared: Long? = 12345L
        while (System.currentTimeMillis() < deadline && cleared != 0L) {
            cleared = ds.getLong("dlid_legacy.bin")
            if (cleared != 0L) Thread.sleep(20)
        }
        assertEquals(0L, cleared)
    }

    @Test
    fun strayMigratingFileIsRestored() = runBlocking<Unit> {
        val payload = "restore-me".toByteArray()
        val dir = kotlin.io.path.createTempDirectory("dl_restore").toFile()
        // Crash leftover: only the .migrating copy exists.
        File(dir, "restored.bin.migrating").writeBytes(payload)
        val spec = DownloadSpec(
            fileName = "restored.bin", description = "Restored",
            url = "http://127.0.0.1:1/restored.bin", sha256 = sha256(payload),
        )
        assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
        assertTrue(File(dir, "restored.bin").readBytes().contentEquals(payload))
    }

    @Test
    fun staleDlidWithoutFileIsCleared() = runBlocking<Unit> {
        val payload = "fresh".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val dir = kotlin.io.path.createTempDirectory("dl_dlid").toFile()
            ds.setLong("dlid_fresh.bin", 999L)
            val spec = DownloadSpec(
                fileName = "fresh.bin", description = "Fresh",
                url = "http://127.0.0.1:${server.address.port}/fresh.bin",
                sha256 = sha256(payload),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertTrue(File(dir, "fresh.bin").isFile)
        } finally {
            server.stop(0)
        }
    }
}
