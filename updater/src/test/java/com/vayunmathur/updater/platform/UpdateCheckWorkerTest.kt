package com.vayunmathur.updater.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.util.DataStoreUtils
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
class UpdateCheckWorkerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = DataStoreUtils.getInstance(ctx)
    private val fetchCount = AtomicInteger(0)

    private lateinit var sysProps: Map<String, String>

    @BeforeTest
    fun setUp() = runBlocking {
        sysProps = TestSysProps.snapshot()
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        TestSysProps.setRunningBuild(dateUtcSeconds = 1000L)
        store.setString(UpdaterPreferences.PENDING_REBOOT_BUILD, "")
        store.setBoolean(UpdaterPreferences.AUTO_INSTALL, true)
        store.setBoolean(UpdaterPreferences.METERED_ALLOWED, false)
        store.setLong(UpdaterPreferences.LAST_CHECKED, 0L)
        Unit
    }

    @AfterTest
    fun tearDown() {
        UpdateChecker.connectionOverride = null
        TestSysProps.restore(sysProps)
    }

    private fun fakeBody(body: String, code: Int = 200) {
        UpdateChecker.connectionOverride = { url ->
            fetchCount.incrementAndGet()
            FakeHttpsConnection(URL(url), code, body.toByteArray())
        }
    }

    private fun worker() = TestListenableWorkerBuilder<UpdateCheckWorker>(ctx).build()

    private fun doWork() = runBlocking { worker().doWork() }

    @Test
    fun `a pending reboot skips the check entirely`() = runBlocking {
        store.setString(UpdaterPreferences.PENDING_REBOOT_BUILD, "2026090700")
        fakeBody("2026090800 3000 shiba stable")
        assertEquals(ListenableWorker.Result.success(), doWork())
        assertEquals(0, fetchCount.get())
    }

    @Test
    fun `up to date is a success and stamps the last check`() = runBlocking {
        fakeBody("2026090500 1000 shiba stable")
        assertEquals(ListenableWorker.Result.success(), doWork())
        assertTrue((store.getLongAwait(UpdaterPreferences.LAST_CHECKED) ?: 0L) > 0L)
    }

    @Test
    fun `a failed check asks WorkManager to retry`() {
        fakeBody("", code = 404)
        assertEquals(ListenableWorker.Result.retry(), doWork())
    }

    @Test
    fun `an update on an unconnected device is held and announced`() {
        fakeBody("2026090700 2000 shiba stable")
        assertEquals(ListenableWorker.Result.success(), doWork())
        val posted = ctx.getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications
        assertTrue(posted.any { it.id == 2 }, "held update should post the available notification")
    }

    @Test
    fun `an update on unmetered wifi proceeds to the install service`() {
        val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        shadow.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, android.net.NetworkInfo.State.CONNECTED,
            ),
        )
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        val capsShadow = shadowOf(capabilities)
        capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        capsShadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        shadowOf(ctx as android.app.Application).clearStartedServices()

        fakeBody("2026090700 2000 shiba stable")
        assertEquals(ListenableWorker.Result.success(), doWork())
        val started = shadowOf(ctx as android.app.Application).allStartedServices
        assertTrue(
            started.any { it.component?.className?.endsWith("UpdateInstallService") == true },
            "expected the install service to start, got $started",
        )
    }

    @Test
    fun `schedule enqueues the immediate and periodic checks`() {
        UpdateCheckWorker.schedule(ctx)
        val manager = androidx.work.WorkManager.getInstance(ctx)
        assertTrue(manager.getWorkInfosForUniqueWork("updater_check_immediate").get().isNotEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork("updater_check_periodic").get().isNotEmpty())
    }
}
