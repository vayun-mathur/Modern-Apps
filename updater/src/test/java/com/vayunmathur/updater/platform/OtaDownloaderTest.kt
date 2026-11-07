package com.vayunmathur.updater.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OtaDownloaderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = DataStoreUtils.getInstance(ctx)

    private lateinit var file: File
    private val requested = mutableListOf<String>()
    private val responses = ArrayDeque<(String) -> FakeHttpsConnection>()

    @BeforeTest
    fun setUp() = runBlocking {
        file = File(ctx.cacheDir, "update-${System.nanoTime()}.zip")
        OtaDownloader.updateFileOverride = file
        OtaDownloader.connectionOverride = { url, _ ->
            val name = url.substringAfterLast('/')
            requested += name
            responses.removeFirst()(name)
        }
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "")
        store.setString(UpdaterPreferences.FAILED_INCREMENTAL, "")
        Unit
    }

    @AfterTest
    fun tearDown() {
        OtaDownloader.updateFileOverride = null
        OtaDownloader.connectionOverride = null
        file.delete()
    }

    private fun respond(code: Int, body: String = "", declaredLength: Long = body.toByteArray().size.toLong()) {
        responses.add { FakeHttpsConnection(URL("https://ota.example/$it"), code, body.toByteArray(), declaredLength) }
    }

    private fun download(current: String = "2026090500", target: String = "2026090700", progress: MutableList<Pair<Long, Long>>? = null) =
        runBlocking {
            OtaDownloader.download(ctx, "shiba", current, target) { bytes, total -> progress?.add(bytes to total) }
        }

    @Test
    fun `downloads the incremental first and records what the file is`() {
        respond(200, "incremental-bytes")
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = download(progress = progress)
        assertEquals(OtaDownloader.Result.Downloaded(incremental = true), result)
        assertEquals("incremental-bytes", file.readText())
        assertEquals(listOf("shiba-incremental-2026090500-2026090700.zip"), requested)
        assertEquals(17L to 17L, progress.last())
        runBlocking {
            assertEquals(
                "shiba-incremental-2026090500-2026090700.zip",
                store.getStringAwait(UpdaterPreferences.DOWNLOAD_FILE),
            )
        }
    }

    @Test
    fun `falls back to the full package when no incremental is published`() {
        respond(404)
        respond(200, "full-bytes")
        val result = download()
        assertEquals(OtaDownloader.Result.Downloaded(incremental = false), result)
        assertEquals("full-bytes", file.readText())
        assertEquals(
            listOf("shiba-incremental-2026090500-2026090700.zip", "shiba-ota_update-2026090700.zip"),
            requested,
        )
    }

    @Test
    fun `reports not found when neither artifact is published`() {
        respond(404)
        respond(404)
        assertEquals(OtaDownloader.Result.NotFound, download())
        // And when the current build is unknown there is only the full package to miss.
        requested.clear()
        respond(404)
        assertEquals(OtaDownloader.Result.NotFound, download(current = ""))
        assertEquals(listOf("shiba-ota_update-2026090700.zip"), requested)
    }

    @Test
    fun `skips an incremental update_engine already refused`() = runBlocking {
        store.setString(
            UpdaterPreferences.FAILED_INCREMENTAL,
            "shiba-incremental-2026090500-2026090700.zip",
        )
        respond(200, "full-bytes")
        assertEquals(OtaDownloader.Result.Downloaded(incremental = false), download())
        assertEquals(listOf("shiba-ota_update-2026090700.zip"), requested)
    }

    @Test
    fun `a resumed download the server calls complete is not streamed again`() = runBlocking {
        file.writeText("already-here")
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "shiba-ota_update-2026090700.zip")
        respond(416)
        val result = download()
        assertEquals(OtaDownloader.Result.Downloaded(incremental = false), result)
        assertEquals("already-here", file.readText())
    }

    @Test
    fun `resumes a partial full package with a ranged partial response`() = runBlocking {
        file.writeText("alp")
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "shiba-ota_update-2026090700.zip")
        respond(206, "ha", declaredLength = 2L)
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = download(progress = progress)
        assertEquals(OtaDownloader.Result.Downloaded(incremental = false), result)
        assertEquals("alpha", file.readText())
        assertEquals(5L to 5L, progress.last())
    }

    @Test
    fun `restarts from scratch when the server ignores the range`() = runBlocking {
        file.writeText("stale-partial")
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "shiba-ota_update-2026090700.zip")
        respond(200, "fresh-full-body")
        assertEquals(OtaDownloader.Result.Downloaded(incremental = false), download())
        assertEquals("fresh-full-body", file.readText())
    }

    @Test
    fun `a partial file from a different artifact is discarded, not resumed`() = runBlocking {
        file.writeText("other-artifact-bytes")
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "shiba-incremental-2026010100-2026090700.zip")
        respond(200, "inc")
        assertEquals(OtaDownloader.Result.Downloaded(incremental = true), download())
        assertEquals("inc", file.readText())
    }

    @Test
    fun `an unexpected HTTP status fails with the status in the reason`() {
        respond(500, "boom")
        val result = download()
        val failed = assertIs<OtaDownloader.Result.Failed>(result)
        assertTrue(failed.reason.contains("500"), failed.reason)
    }

    @Test
    fun `a body that ends before its declared length fails`() {
        respond(200, "short", declaredLength = 100L)
        val result = download()
        val failed = assertIs<OtaDownloader.Result.Failed>(result)
        assertTrue(failed.reason.contains("ended early"), failed.reason)
    }

    @Test
    fun `a body with no declared length downloads to its end`() {
        respond(200, "unbounded", declaredLength = -1L)
        assertEquals(OtaDownloader.Result.Downloaded(incremental = true), download())
        assertEquals("unbounded", file.readText())
    }

    @Test
    fun `a connection failure is a failed result, not a throw`() {
        responses.add {
            FakeHttpsConnection(URL("https://ota.example/$it"), 200, failOnInput = true)
        }
        val result = download()
        val failed = assertIs<OtaDownloader.Result.Failed>(result)
        assertTrue(failed.reason.contains("could not be downloaded"), failed.reason)
    }

    @Test
    fun `discard deletes the staged file and forgets its name`() = runBlocking {
        file.writeText("staged")
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "shiba-ota_update-2026090700.zip")
        OtaDownloader.discard(ctx)
        assertFalse(file.exists())
        assertEquals("", store.getStringAwait(UpdaterPreferences.DOWNLOAD_FILE))
    }
}
