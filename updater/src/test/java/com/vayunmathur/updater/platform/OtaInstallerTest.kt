package com.vayunmathur.updater.platform

import com.vayunmathur.updater.domain.OtaPackageValidation
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class)
class OtaInstallerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val expected = OtaPackageValidation.Expected(
        buildDateUtcSeconds = 100L,
        targetBuild = "2026090700",
        device = "shiba",
        currentBuild = "2026090500",
        currentFingerprint = "Modern/shiba/shiba:16/2026090500/user/release-keys",
    )

    private class FakeEngine(
        var bindResult: Boolean = true,
        var bindThrows: Boolean = false,
        var completionCode: Int = 0,
        var applyThrows: Boolean = false,
        var unbindResult: Boolean = true,
        var neverCompletes: Boolean = false,
    ) : OtaInstaller.PayloadEngine {
        var onComplete: ((Int) -> Unit)? = null
        var onStatus: ((Int, Float) -> Unit)? = null
        var appliedUrl: String? = null
        var appliedOffset: Long = -1L
        var appliedHeaders: List<String> = emptyList()
        var cancelled: Boolean = false
        var unbound: Boolean = false

        override fun bind(
            onStatusUpdate: (Int, Float) -> Unit,
            onPayloadApplicationComplete: (Int) -> Unit,
        ): Boolean {
            if (bindThrows) throw IllegalStateException("binder died")
            onStatus = onStatusUpdate
            onComplete = onPayloadApplicationComplete
            return bindResult
        }

        override fun applyPayload(url: String, offset: Long, headers: Array<String>) {
            if (applyThrows) throw IllegalStateException("engine refused")
            appliedUrl = url
            appliedOffset = offset
            appliedHeaders = headers.toList()
            if (neverCompletes) return
            onStatus?.invoke(3, 0.5f)
            onComplete?.invoke(completionCode)
        }

        override fun cancel() {
            cancelled = true
        }

        override fun unbind(): Boolean {
            unbound = true
            return unbindResult
        }
    }

    private lateinit var careMap: File

    @BeforeTest
    fun setUp() {
        careMap = File(ctx.cacheDir, "care_map-${System.nanoTime()}.pb")
        OtaInstaller.careMapPathOverride = careMap
        OtaInstaller.signatureCheckOverride = { _, onProgress -> onProgress(42); true }
    }

    @AfterTest
    fun tearDown() {
        OtaInstaller.signatureCheckOverride = null
        OtaInstaller.payloadEngineOverride = null
        OtaInstaller.careMapPathOverride = null
        careMap.delete()
    }

    private fun zipFile(
        metadata: String = """
            post-timestamp=100
            post-build-incremental=2026090700
            pre-device=shiba
            ota-type=AB
            ota-streaming-property-files=payload.bin:1234:5678
        """.trimIndent(),
        properties: String? = "FILE_HASH=a\nFILE_SIZE=1\nMETADATA_HASH=b\nMETADATA_SIZE=2\n",
        includeMetadata: Boolean = true,
        includeProperties: Boolean = true,
        includeCareMap: Boolean = true,
    ): File {
        val file = File.createTempFile("ota", ".zip", ctx.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            if (includeMetadata) {
                zip.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
                zip.write(metadata.toByteArray()); zip.closeEntry()
            }
            if (includeProperties && properties != null) {
                zip.putNextEntry(ZipEntry("payload_properties.txt"))
                zip.write(properties.toByteArray()); zip.closeEntry()
            }
            if (includeCareMap) {
                zip.putNextEntry(ZipEntry("care_map.pb"))
                zip.write(byteArrayOf(9, 8, 7)); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("payload.bin"))
            zip.write(ByteArray(32)); zip.closeEntry()
        }
        return file
    }

    private fun install(file: File, verify: MutableList<Int>? = null, apply: MutableList<Int>? = null) = runBlocking {
        OtaInstaller.install(file, expected, { verify?.add(it) }, { apply?.add(it) })
    }

    @Test
    fun `a verified valid package is applied and the staged file is removed`() {
        val engine = FakeEngine(completionCode = 0)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile()
        val verifyProgress = mutableListOf<Int>()
        val applyProgress = mutableListOf<Int>()
        val result = install(file, verifyProgress, applyProgress)
        assertEquals(OtaInstaller.Result.Applied, result)
        assertEquals(listOf(42), verifyProgress)
        assertEquals(listOf(50), applyProgress)
        assertFalse(file.exists(), "applied package must be deleted")
        assertTrue(engine.appliedUrl!!.endsWith(file.name), engine.appliedUrl)
        assertEquals(1234L, engine.appliedOffset)
        assertEquals(listOf("FILE_HASH=a", "FILE_SIZE=1", "METADATA_HASH=b", "METADATA_SIZE=2"), engine.appliedHeaders)
        assertTrue(engine.unbound)
        assertEquals(listOf<Byte>(9, 8, 7), careMap.readBytes().toList())
    }

    @Test
    fun `a failed signature check deletes the package and applies nothing`() {
        OtaInstaller.signatureCheckOverride = { _, _ -> false }
        var engineUsed = false
        OtaInstaller.payloadEngineOverride = { engineUsed = true; FakeEngine() }
        val file = zipFile()
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.reason.contains("signature verification"), failed.reason)
        assertFalse(file.exists())
        assertFalse(engineUsed)
    }

    @Test
    fun `the real signature check rejects an unsigned package under Robolectric`() {
        OtaInstaller.signatureCheckOverride = null
        val file = zipFile()
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.reason.contains("signature verification"), failed.reason)
        assertFalse(file.exists())
    }

    @Test
    fun `a package for another device is rejected after verification`() {
        OtaInstaller.payloadEngineOverride = { FakeEngine() }
        val file = zipFile(
            metadata = """
                post-timestamp=100
                post-build-incremental=2026090700
                pre-device=husky
                ota-type=AB
                ota-streaming-property-files=payload.bin:1234:5678
            """.trimIndent(),
        )
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.reason.contains("husky"), failed.reason)
        assertFalse(file.exists())
    }

    @Test
    fun `a package missing its payload properties is rejected`() {
        OtaInstaller.payloadEngineOverride = { FakeEngine() }
        val file = zipFile(properties = "FILE_HASH=a\nFILE_SIZE=1\n")
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.reason.contains("payload properties"), failed.reason)
        assertFalse(file.exists())
    }

    @Test
    fun `a package missing required entries cannot be read and is deleted`() {
        OtaInstaller.payloadEngineOverride = { FakeEngine() }
        val file = zipFile(includeProperties = false)
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.reason.contains("could not be read"), failed.reason)
        assertFalse(file.exists())
    }

    @Test
    fun `a file that is not a zip cannot be read and is deleted`() {
        OtaInstaller.payloadEngineOverride = { FakeEngine() }
        val file = File(ctx.cacheDir, "not-a-zip-${System.nanoTime()}.zip").apply { writeText("plain text") }
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.reason.contains("could not be read"), failed.reason)
        assertFalse(file.exists())
    }

    @Test
    fun `a missing care map does not stop the install`() {
        val engine = FakeEngine(completionCode = 0)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile(includeCareMap = false)
        assertEquals(OtaInstaller.Result.Applied, install(file))
        assertFalse(careMap.exists())
    }

    @Test
    fun `an engine completion error is classified and deletes the package`() {
        val engine = FakeEngine(completionCode = 9)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile()
        val result = install(file)
        val failed = assertIs<OtaInstaller.Result.Failed>(result)
        assertTrue(failed.retryable, "transfer errors are retryable")
        assertFalse(failed.initializationFailure)
        assertTrue(failed.reason.contains("transfer"), failed.reason)
        assertFalse(file.exists())
        assertTrue(engine.unbound)
    }

    @Test
    fun `an initialisation error on an incremental is flagged for the caller`() {
        val engine = FakeEngine(completionCode = 20)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile(
            metadata = """
                post-timestamp=100
                post-build-incremental=2026090700
                pre-device=shiba
                ota-type=AB
                pre-build-incremental=2026090500
                pre-build=Modern/shiba/shiba:16/2026090500/user/release-keys
                ota-streaming-property-files=payload.bin:1234:5678
            """.trimIndent(),
        )
        val failed = assertIs<OtaInstaller.Result.Failed>(install(file))
        assertTrue(failed.initializationFailure)
        assertFalse(failed.retryable)
    }

    @Test
    fun `when the engine cannot be constructed the install fails cleanly`() {
        OtaInstaller.payloadEngineOverride = { throw IllegalStateException("no service") }
        val file = zipFile()
        val failed = assertIs<OtaInstaller.Result.Failed>(install(file))
        assertTrue(failed.reason.contains("unavailable"), failed.reason)
    }

    @Test
    fun `when binding fails nothing is applied`() {
        val engine = FakeEngine(bindResult = false)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile()
        val failed = assertIs<OtaInstaller.Result.Failed>(install(file))
        assertTrue(failed.reason.contains("could not attach"), failed.reason)
        assertTrue(engine.unbound)
    }

    @Test
    fun `when binding throws the install fails cleanly instead of crashing`() {
        // bind() crosses the binder, which throws undocumented RuntimeExceptions when the
        // service is unavailable — the same condition a `false` return reports. The caller
        // (UpdateInstallService) treats install() as returning a value, so a throw here
        // would crash the run with no failure notification and no cleanup.
        val engine = FakeEngine(bindThrows = true)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile()
        val failed = assertIs<OtaInstaller.Result.Failed>(install(file))
        assertTrue(failed.reason.contains("could not attach"), failed.reason)
        assertTrue(engine.unbound)
    }

    @Test
    fun `cancelling an in-flight apply cancels the engine`() {
        val engine = FakeEngine(neverCompletes = true)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile()
        runBlocking {
            val deferred = async {
                OtaInstaller.install(file, expected, {}, {})
            }
            // Let the install reach applyPayload, then cancel the caller.
            repeat(100) {
                if (engine.appliedUrl != null) return@repeat
                kotlinx.coroutines.delay(10)
            }
            deferred.cancel()
            runCatching { deferred.await() }
            assertTrue(engine.cancelled, "cancellation must reach update_engine")
            assertTrue(engine.unbound)
        }
    }

    @Test
    fun `without a test engine the real engine is unavailable off-device`() {
        OtaInstaller.payloadEngineOverride = null
        val file = zipFile()
        val failed = assertIs<OtaInstaller.Result.Failed>(install(file))
        assertTrue(failed.reason.contains("unavailable"), failed.reason)
    }

    @Test
    fun `when the engine refuses the payload the refusal is reported`() {
        val engine = FakeEngine(applyThrows = true)
        OtaInstaller.payloadEngineOverride = { engine }
        val file = zipFile()
        val failed = assertIs<OtaInstaller.Result.Failed>(install(file))
        assertTrue(failed.reason.contains("refused"), failed.reason)
        assertTrue(engine.unbound)
    }
}
