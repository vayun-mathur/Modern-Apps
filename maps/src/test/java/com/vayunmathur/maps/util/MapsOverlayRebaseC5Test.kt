package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.MapOverlay
import com.vayunmathur.maps.data.MapTileCache
import com.vayunmathur.maps.platform.MapOverlayWorker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 overlay rebase: a manifest whose empty segment list pins a new base
 * downloads that base from the given URL, swaps it in, drops the old
 * overlay stack, and pins the new manifest; a rebased file whose build
 * id disagrees is refused without touching the old base.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsOverlayRebaseC5Test {

    companion object {
        // The host JVM routes HTTP through the corp forward proxy, which
        // cannot reach this test's loopback server.
        @JvmStatic
        @org.junit.BeforeClass
        fun bypassProxyForLoopback() {
            System.setProperty("http.nonProxyHosts", "127.0.0.1|localhost")
            System.setProperty("https.nonProxyHosts", "127.0.0.1|localhost")
        }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val root: File get() = ctx.getExternalFilesDir(null)!!
    private val origFetcher = MapOverlayWorker.manifestFetcher
    private var server: ServerSocket? = null

    @AfterTest
    fun tearDown() {
        MapOverlayWorker.manifestFetcher = origFetcher
        server?.close()
        server = null
        cleanRoot()
    }

    private fun cleanRoot() {
        File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).delete()
        File(root, "${MapTileCache.BASEMAP_ARCHIVE_FILE}.next").delete()
        File(root, "${MapTileCache.BASEMAP_ARCHIVE_FILE}.prev").delete()
        File(root, MapOverlay.OVERLAY_ROOT).deleteRecursively()
    }

    private fun baseBytes(buildId: Long, size: Int = 64): ByteArray {
        val bytes = ByteArray(size)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, buildId)
        return bytes
    }

    private fun plantBase(buildId: Long = 0x10) {
        val f = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)
        f.parentFile?.mkdirs()
        f.writeBytes(baseBytes(buildId))
    }

    /** Serves [body] once per connection on a loopback port. */
    private fun serve(body: ByteArray): Int {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                runCatching {
                    val client = socket.accept()
                    // Consume the request line + headers.
                    val input = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine()
                        if (line.isNullOrEmpty()) break
                    }
                    val out = client.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(),
                    )
                    out.write(body)
                    out.flush()
                    client.close()
                }
            }
        }
        return socket.localPort
    }

    private fun manifest(seq: Long, baseBuild: String, baseLen: Long, baseUrl: String) = """
        {"version": 1, "min_client": 1, "overlay_seq": $seq, "overlay_build_id": "0x2a",
         "base": {"url": "$baseUrl", "build_id": "$baseBuild", "len": $baseLen},
         "segments": []}
    """.trimIndent()

    private fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<MapOverlayWorker>(ctx)
            .setInputData(workDataOf("manifest_url" to "https://example.invalid/manifest.json"))
            .build()
            .startWork()
            .get(180, TimeUnit.SECONDS)

    @Test
    fun `rebase downloads the new base swaps it in and pins`() = runBlocking {
        cleanRoot(); plantBase(0x10)
        val port = serve(baseBytes(0x20))
        val text = manifest(9, "0x20", 64, "http://127.0.0.1:$port/base.mamaps")
        MapOverlayWorker.manifestFetcher = { text }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        // The live base is now the new build, and the manifest is pinned.
        val live = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)
        val id = ByteBuffer.wrap(live.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getLong(16)
        assertEquals(0x20L, id)
        assertEquals(9L, MapOverlay.pinned(root)?.overlaySeq)
        assertTrue(!File(root, "${MapTileCache.BASEMAP_ARCHIVE_FILE}.next").exists())
        assertTrue(!File(root, "${MapTileCache.BASEMAP_ARCHIVE_FILE}.prev").exists())
    }

    @Test
    fun `missing base skips and unparseable manifest succeeds without pinning`() = runBlocking {
        cleanRoot()
        MapOverlayWorker.manifestFetcher = { "not json at all" }
        // No base archive: the worker skips before fetching.
        assertEquals(ListenableWorker.Result.success(), runWorker())
        plantBase(0x10)
        MapOverlayWorker.manifestFetcher = { "not json at all" }
        // A base exists but the manifest does not parse: success, no pin.
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(null, MapOverlay.pinned(root))
        MapOverlayWorker.manifestFetcher = { null }
        // An unfetchable manifest retries.
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun `rebase with a mismatched downloaded build is refused`() = runBlocking {
        cleanRoot(); plantBase(0x10)
        // Server says the new base is 0x20 but sends 0x99 bytes.
        val port = serve(baseBytes(0x99))
        val text = manifest(9, "0x20", 64, "http://127.0.0.1:$port/base.mamaps")
        MapOverlayWorker.manifestFetcher = { text }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val live = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)
        val id = ByteBuffer.wrap(live.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getLong(16)
        assertEquals(0x10L, id, "old base untouched after a refused rebase")
    }

    @Test
    fun `rebase download failure retries`() = runBlocking {
        cleanRoot(); plantBase(0x10)
        val text = manifest(9, "0x20", 64, "http://127.0.0.1:1/base.mamaps")
        MapOverlayWorker.manifestFetcher = { text }
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }
}
