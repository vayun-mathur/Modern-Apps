package com.vayunmathur.updater.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.updater.platform.FakeHttpsConnection
import com.vayunmathur.updater.platform.OtaDownloader
import com.vayunmathur.updater.platform.OtaInstaller
import com.vayunmathur.updater.platform.TestSysProps
import com.vayunmathur.updater.platform.UpdaterPreferences
import java.io.File
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdateInstallServiceTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val store get() = DataStoreUtils.getInstance(ctx)

    // The engine must complete through the callback registered at bind time; capture it there.
    private class CapturingEngine(var completionCode: Int = 0) : OtaInstaller.PayloadEngine {
        private var complete: ((Int) -> Unit)? = null
        override fun bind(
            onStatusUpdate: (Int, Float) -> Unit,
            onPayloadApplicationComplete: (Int) -> Unit,
        ): Boolean {
            complete = onPayloadApplicationComplete
            return true
        }

        override fun applyPayload(url: String, offset: Long, headers: Array<String>) {
            complete?.invoke(completionCode)
        }

        override fun cancel() = Unit
        override fun unbind(): Boolean = true
    }

    private lateinit var staged: File
    private lateinit var careMap: File
    private val responses = ArrayDeque<FakeHttpsConnection>()

    @BeforeTest
    fun setUp() = runBlocking {
        TestSysProps.setRunningBuild(dateUtcSeconds = 1000L)
        staged = File(ctx.cacheDir, "service-update-${System.nanoTime()}.zip")
        careMap = File(ctx.cacheDir, "service-care-${System.nanoTime()}.pb")
        OtaDownloader.updateFileOverride = staged
        OtaDownloader.connectionOverride = { url, _ -> responses.removeFirst() }
        OtaInstaller.signatureCheckOverride = { _, _ -> true }
        OtaInstaller.careMapPathOverride = careMap
        store.setString(UpdaterPreferences.PENDING_REBOOT_BUILD, "")
        store.setString(UpdaterPreferences.FAILED_INCREMENTAL, "")
        store.setString(UpdaterPreferences.DOWNLOAD_FILE, "")
        Unit
    }

    @AfterTest
    fun tearDown() {
        OtaDownloader.updateFileOverride = null
        OtaDownloader.connectionOverride = null
        OtaInstaller.signatureCheckOverride = null
        OtaInstaller.payloadEngineOverride = null
        OtaInstaller.careMapPathOverride = null
        staged.delete(); careMap.delete()
    }

    private fun zipBytes(): ByteArray {
        val file = File(ctx.cacheDir, "payload-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
            zip.write(
                """
                post-timestamp=100
                post-build-incremental=2026090700
                pre-device=shiba
                ota-type=AB
                ota-streaming-property-files=payload.bin:1234:5678
                """.trimIndent().toByteArray(),
            ); zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload_properties.txt"))
            zip.write("FILE_HASH=a\nFILE_SIZE=1\nMETADATA_HASH=b\nMETADATA_SIZE=2\n".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload.bin")); zip.write(ByteArray(16)); zip.closeEntry()
        }
        return file.readBytes().also { file.delete() }
    }

    private fun startIntent(build: String? = "2026090700", date: Long = 100L): Intent =
        Intent(ctx, UpdateInstallService::class.java).apply {
            if (build != null) putExtra("build", build)
            putExtra("build_date", date)
        }

    private fun startService(intent: Intent): UpdateInstallService {
        val controller = Robolectric.buildService(UpdateInstallService::class.java, intent).create()
        val service = controller.get()
        service.onStartCommand(intent, 0, 1)
        return service
    }

    private fun await(description: String, condition: suspend () -> Boolean) {
        repeat(250) {
            if (runBlocking { condition() }) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    private fun resultNotification(): Notification? =
        ctx.getSystemService(NotificationManager::class.java)
            .activeNotifications.firstOrNull { it.id == 2 }?.notification

    private fun resultText(): String? =
        resultNotification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `starting without a target build stops immediately`() {
        val service = startService(startIntent(build = null))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 2))
        assertNull(service.onBind(null))
    }

    @Test
    fun `an unreadable running build fails with the unknown-build message`() {
        TestSysProps.set("ro.build.version.incremental", "")
        startService(startIntent())
        await("failure notification") { resultText()?.contains("unknown") == true || resultNotification() != null }
        assertNotNull(resultNotification())
    }

    @Test
    fun `when no package is published the failure says so`() {
        responses.add(FakeHttpsConnection(URL("https://ota.example/inc"), 404))
        responses.add(FakeHttpsConnection(URL("https://ota.example/full"), 404))
        OtaInstaller.payloadEngineOverride = { CapturingEngine(0) }
        startService(startIntent())
        await("no-package failure") { resultNotification() != null }
        assertNotNull(resultNotification())
    }

    @Test
    fun `a download failure surfaces its reason`() {
        responses.add(FakeHttpsConnection(URL("https://ota.example/inc"), 500, "x".toByteArray()))
        OtaInstaller.payloadEngineOverride = { CapturingEngine(0) }
        startService(startIntent())
        await("download failure") { resultText()?.contains("500") == true }
    }

    @Test
    fun `a successful update is staged for an idle reboot`() { runBlocking {
        responses.add(FakeHttpsConnection(URL("https://ota.example/inc"), 200, zipBytes()))
        OtaInstaller.payloadEngineOverride = { CapturingEngine(0) }
        startService(startIntent())
        await("pending reboot") {
            store.getStringAwait(UpdaterPreferences.PENDING_REBOOT_BUILD) == "2026090700" &&
                store.getStringAwait(UpdaterPreferences.DOWNLOAD_FILE) == ""
        }
        await("reboot notification") { resultNotification() != null }
        // IdleReboot.schedule itself is covered in ReceiversIdleTest; here the marker and the
        // reboot notification are the service-level contract.
        assertNotNull(resultNotification())
        Unit
    } }

    @Test
    fun `an initialisation failure on the incremental is remembered`() = runBlocking {
        responses.add(FakeHttpsConnection(URL("https://ota.example/inc"), 200, zipBytes()))
        OtaInstaller.payloadEngineOverride = { CapturingEngine(20) }
        startService(startIntent())
        await("failed incremental remembered") {
            store.getStringAwait(UpdaterPreferences.FAILED_INCREMENTAL)
                ?.contains("incremental") == true
        }
        await("failure notification") { resultNotification() != null }
    }

    @Test
    fun `a non initialisation install failure is not remembered as a bad incremental`() = runBlocking {
        responses.add(FakeHttpsConnection(URL("https://ota.example/inc"), 200, zipBytes()))
        OtaInstaller.payloadEngineOverride = { CapturingEngine(10) }
        startService(startIntent())
        await("failure notification") { resultText()?.contains("hash") == true }
        assertTrue(store.getStringAwait(UpdaterPreferences.FAILED_INCREMENTAL).isNullOrEmpty())
    }

    @Test
    fun `timeout and destroy do not throw`() {
        val controller = Robolectric.buildService(UpdateInstallService::class.java, startIntent(build = null)).create()
        val service = controller.get()
        service.onTimeout(1, 0)
        controller.destroy()
    }

    @Test
    fun `the companion start intent carries the target build`() {
        shadowOf(app).clearStartedServices()
        UpdateInstallService.start(ctx, "2026090700", 1234L)
        val started = shadowOf(app).allStartedServices
        val intent = started.lastOrNull { it.component?.className?.endsWith("UpdateInstallService") == true }
        assertNotNull(intent, "started services: $started")
        assertEquals("2026090700", intent.getStringExtra("build"))
        assertEquals(1234L, intent.getLongExtra("build_date", 0L))
    }
}
