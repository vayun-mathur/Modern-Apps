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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadCoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ds: DataStoreUtils = DataStoreUtils.getInstance(context)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDir(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix).toFile()

    /** Tiny loopback HTTP fixture: [handler] writes the response for each request. */
    private class Server(val handler: (exchange: com.sun.net.httpserver.HttpExchange) -> Unit) {
        val hits = AtomicInteger(0)
        val lastRange = AtomicReference<String?>(null)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange ->
                hits.incrementAndGet()
                lastRange.set(exchange.requestHeaders.getFirst("Range"))
                handler(exchange)
            }
            server.start()
        }

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}/$path"
        fun stop() = server.stop(0)
    }

    private fun serveBytes(bytes: ByteArray): Server = Server { exchange ->
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null) {
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            val rest = bytes.copyOfRange(start, bytes.size)
            exchange.responseHeaders.set("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
            exchange.sendResponseHeaders(206, rest.size.toLong())
            exchange.responseBody.use { it.write(rest) }
        } else {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun freshDownloadVerifiesAndFinalizes() = runBlocking<Unit> {
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val server = serveBytes(payload)
        val dir = tempDir("dl_fresh")
        try {
            val spec = DownloadSpec(
                fileName = "model.bin", description = "Model",
                url = server.url("model.bin"), sha256 = sha256(payload),
                expectedBytes = payload.size.toLong(),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            val file = File(dir, "model.bin")
            assertTrue(file.isFile)
            assertTrue(file.readBytes().contentEquals(payload))
            assertFalse(File(dir, "model.bin.part").exists())
            assertEquals(1.0, ds.getDoubleForTest("progress_model.bin"))
            assertEquals(sha256(payload), ds.getString("verified_model.bin"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun downloadIntoSubdirectoryCreatesParents() = runBlocking<Unit> {
        val payload = "nested-payload".toByteArray()
        val server = serveBytes(payload)
        val dir = tempDir("dl_nested")
        try {
            val spec = DownloadSpec(
                fileName = "small100/encoder.bin", description = "Encoder",
                url = server.url("encoder.bin"), sha256 = sha256(payload),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertTrue(File(dir, "small100/encoder.bin").readBytes().contentEquals(payload))
        } finally {
            server.stop()
        }
    }

    @Test
    fun alreadyVerifiedFileIsSkippedWithoutNetwork() = runBlocking<Unit> {
        val payload = "already-here".toByteArray()
        val dir = tempDir("dl_skip")
        File(dir, "cached.bin").writeBytes(payload)
        val spec = DownloadSpec(
            fileName = "cached.bin", description = "Cached",
            url = "http://127.0.0.1:1/cached.bin", sha256 = sha256(payload),
            expectedBytes = payload.size.toLong(),
        )
        markVerified(ds, spec)
        assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
        assertEquals(1.0, ds.getDoubleForTest("progress_cached.bin"))
    }

    @Test
    fun unmarkedExistingFileIsHashedThenMarked() = runBlocking<Unit> {
        val payload = "hash-me".toByteArray()
        val dir = tempDir("dl_hash")
        File(dir, "unmarked.bin").writeBytes(payload)
        val spec = DownloadSpec(
            fileName = "unmarked.bin", description = "Unmarked",
            url = "http://127.0.0.1:1/unmarked.bin", sha256 = sha256(payload),
        )
        assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
        // The launch gate marker now exists, written from the real hash.
        assertEquals(sha256(payload), ds.getString("verified_unmarked.bin"))
    }

    @Test
    fun staleOrTruncatedFileIsRedownloaded() = runBlocking<Unit> {
        val good = "the-good-bytes".toByteArray()
        val server = serveBytes(good)
        val dir = tempDir("dl_stale")
        try {
            File(dir, "stale.bin").writeBytes("old".toByteArray())
            val spec = DownloadSpec(
                fileName = "stale.bin", description = "Stale",
                url = server.url("stale.bin"), sha256 = sha256(good),
                expectedBytes = good.size.toLong(),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertTrue(File(dir, "stale.bin").readBytes().contentEquals(good))
            assertEquals(1, server.hits.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun resumeAppendsWithRangeHeader() = runBlocking<Unit> {
        val payload = ByteArray(4_000) { (it % 253).toByte() }
        val server = serveBytes(payload)
        val dir = tempDir("dl_resume")
        try {
            File(dir, "big.bin.part").writeBytes(payload.copyOfRange(0, 1_500))
            val spec = DownloadSpec(
                fileName = "big.bin", description = "Big",
                url = server.url("big.bin"), sha256 = sha256(payload),
                expectedBytes = payload.size.toLong(),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertEquals("bytes=1500-", server.lastRange.get())
            assertTrue(File(dir, "big.bin").readBytes().contentEquals(payload))
        } finally {
            server.stop()
        }
    }

    @Test
    fun serverIgnoringRangeRestartsFromScratch() = runBlocking<Unit> {
        val payload = "full-body".toByteArray()
        // Always 200 with the whole body, whatever Range was asked for.
        val server = Server { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val dir = tempDir("dl_restart")
        try {
            File(dir, "restart.bin.part").writeBytes("junkjunk".toByteArray())
            val spec = DownloadSpec(
                fileName = "restart.bin", description = "Restart",
                url = server.url("restart.bin"), sha256 = sha256(payload),
            )
            assertTrue(runDownloadsCore(context, ds, listOf(spec), dir))
            assertTrue(File(dir, "restart.bin").readBytes().contentEquals(payload))
        } finally {
            server.stop()
        }
    }

    @Test
    fun checksumMismatchExhaustsAttemptsAndReportsFailure() = runBlocking<Unit> {
        val served = "tampered".toByteArray()
        val server = serveBytes(served)
        val dir = tempDir("dl_tamper")
        try {
            val spec = DownloadSpec(
                fileName = "tampered.bin", description = "Tampered",
                url = server.url("tampered.bin"), sha256 = sha256("honest".toByteArray()),
            )
            assertFalse(runDownloadsCore(context, ds, listOf(spec), dir))
            assertFalse(File(dir, "tampered.bin").exists())
            assertFalse(File(dir, "tampered.bin.part").exists())
            assertEquals(4, server.hits.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun httpErrorReportsFailureAndKeepsGoingToNextFile() = runBlocking<Unit> {
        val payload = "second-file".toByteArray()
        val good = serveBytes(payload)
        val dir = tempDir("dl_mixed")
        try {
            val missing = DownloadSpec(
                fileName = "missing.bin", description = "Missing",
                url = "http://127.0.0.1:1/missing.bin",
            )
            val present = DownloadSpec(
                fileName = "second.bin", description = "Second",
                url = good.url("second.bin"), sha256 = sha256(payload),
            )
            assertFalse(runDownloadsCore(context, ds, listOf(missing, present), dir))
            assertTrue(File(dir, "second.bin").isFile)
        } finally {
            good.stop()
        }
    }

    @Test
    fun emptySpecListSucceeds() = runBlocking<Unit> {
        assertTrue(runDownloadsCore(context, ds, emptyList(), tempDir("dl_empty")))
    }

    @Test
    fun checksumMatchesCoversUnpinnedMismatchAndMarkerFastPath() = runBlocking<Unit> {
        val payload = "check".toByteArray()
        val dir = tempDir("dl_checksum")
        val file = File(dir, "c.bin").also { it.writeBytes(payload) }
        val unpinned = DownloadSpec("c.bin", "C", "http://x/c.bin", sha256 = null)
        assertTrue(checksumMatches(file, ds, unpinned))
        val matching = DownloadSpec("c.bin", "C", "http://x/c.bin", sha256 = sha256(payload))
        assertTrue(checksumMatches(file, ds, matching))
        assertEquals(sha256(payload), ds.getString("verified_c.bin"))
        val wrong = DownloadSpec("c.bin", "C", "http://x/c.bin", sha256 = sha256("nope".toByteArray()))
        assertFalse(checksumMatches(file, ds, wrong))
    }

    @Test
    fun markVerifiedIgnoresUnpinnedSpecs() = runBlocking<Unit> {
        markVerified(ds, DownloadSpec("plain.bin", "P", "http://x/plain.bin", sha256 = null))
        assertEquals(null, ds.getString("verified_plain.bin"))
        val sha = sha256("x".toByteArray())
        markVerified(ds, DownloadSpec("pinned.bin", "P", "http://x/pinned.bin", sha256 = sha))
        assertEquals(sha, ds.getString("verified_pinned.bin"))
    }

    @Test
    fun downloadModelsMapsItemsToSpecs() = runBlocking<Unit> {
        val payload = "via-models".toByteArray()
        val server = serveBytes(payload)
        try {
            val item = ModelDownloadItem(
                url = server.url("m.bin"), fileName = "dlmodels/m.bin",
                description = "M", sha256 = sha256(payload),
                expectedBytes = payload.size.toLong(),
            )
            // No targetDir override: writes under the app external files dir.
            assertTrue(downloadModels(context, ds, listOf(item)))
            val written = File(context.getExternalFilesDir(null), "dlmodels/m.bin")
            assertTrue(written.isFile)
            assertTrue(written.readBytes().contentEquals(payload))
            written.delete()
        } finally {
            server.stop()
        }
    }

    @Test
    fun manifestGateReadsMarkersThroughDataStore() = runBlocking<Unit> {
        val dir = tempDir("dl_gate")
        File(dir, "gate.bin").writeBytes(ByteArray(8))
        val sha = "ab".repeat(32)
        ds.setString("verified_gate.bin", sha)
        val manifest = specsToManifest(
            listOf(DownloadSpec("gate.bin", "G", "http://x/gate.bin", sha256 = sha, expectedBytes = 8L)),
        )
        // Wait for the synchronous mirror to observe the write.
        val deadline = System.currentTimeMillis() + 5_000
        var satisfied = false
        while (System.currentTimeMillis() < deadline && !satisfied) {
            satisfied = manifestSatisfied(dir, ds, manifest)
            if (!satisfied) Thread.sleep(20)
        }
        assertTrue(satisfied)
    }
}

// No synchronous double getter exists; the flow's first value is the stored one.
private suspend fun DataStoreUtils.getDoubleForTest(key: String): Double =
    kotlinx.coroutines.withTimeout(10_000) { doubleFlow(key).first() }
