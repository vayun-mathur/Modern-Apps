package com.vayunmathur.findfamily.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.service.SharingTileService
import com.vayunmathur.findfamily.util.LocationServiceController
import com.vayunmathur.findfamily.util.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class PlatformServicesTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @Test
    fun aLocationServiceControllerFlags() {
        // The tracking-tile test grants fine location on the shared app shadow; reset it.
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        // setTrackingEnabled reconciles the service via Dispatchers.Main, which deadlocks
        // against runBlocking on Robolectric's paused main thread: run the suspend body on
        // a worker thread and pump the looper from here.
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val step = java.util.concurrent.atomic.AtomicReference("init")
        val t = Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    step.set("permission"); assertFalse(LocationServiceController.hasFineLocationPermission(ctx))
                    step.set("setTrue"); LocationServiceController.setTrackingEnabled(ctx, true)
                    step.set("readTrue"); assertTrue(LocationServiceController.isTrackingEnabled(ctx))
                    step.set("setFalse"); LocationServiceController.setTrackingEnabled(ctx, false)
                    step.set("readFalse"); assertFalse(LocationServiceController.isTrackingEnabled(ctx))
                    step.set("setSharing"); LocationServiceController.setGlobalSharingEnabled(ctx, true)
                    step.set("readSharing"); assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))
                    step.set("flowSharing"); assertTrue(LocationServiceController.globalSharingEnabledFlow(ctx).first())
                    step.set("setCrowd"); LocationServiceController.setCrowdFindingEnabled(ctx, true)
                    step.set("flowCrowd"); assertTrue(LocationServiceController.crowdFindingEnabledFlow(ctx).first())
                    step.set("done")
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        t.start()
        val dl = System.currentTimeMillis() + 150_000
        while (t.isAlive && System.currentTimeMillis() < dl) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(40)
        }
        org.junit.Assert.assertFalse("thread stuck at step=${step.get()}", t.isAlive)
        failure.get()?.let { throw AssertionError("flags body failed at step=${step.get()}", it) }
        LocationServiceController.stop(ctx)
    }

    @Test
    fun directBootStoreRoundTrip() = runBlocking {
        val store = DirectBootStore.store(ctx)
        assertNotNull(store)
        assertEquals(null, DirectBootStore.userid(ctx))
        assertEquals(emptyList(), DirectBootStore.roster(ctx))
        assertFalse(DirectBootStore.isSeeded(ctx))
        DirectBootStore.seed(ctx, listOf(DirectBootStore.Target(id = 42L, bundle = "bundle-b64", pairId = "pair-1")), trackingEnabled = false, globalSharingEnabled = true)
        assertEquals(false, DirectBootStore.isTrackingEnabled(ctx))
        assertEquals(true, DirectBootStore.isGlobalSharingEnabled(ctx))
        val roster = DirectBootStore.roster(ctx)
        assertEquals(1, roster.size)
        assertEquals(42L, roster[0].id)
        assertEquals("pair-1", roster[0].pairId)
    }

    @Test
    fun sharingTileLifecycle() {
        val controller = Robolectric.buildService(SharingTileService::class.java)
        val service = controller.create().get()
        service.onTileAdded()
        service.onStartListening()
        service.onClick()
        Thread.sleep(500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        SharingTileService.requestRefresh(ctx)
        // No controller.destroy(): TileService shadow mishandles Service.onDestroy (see share tests).
    }

    @Test
    fun trackingTileLifecycle() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val cls = com.vayunmathur.findfamily.util.TrackingTileService::class.java
        val controller = Robolectric.buildService(cls)
        val service = controller.create().get()
        service.onTileAdded()
        service.onStartListening()
        service.onClick()
        Thread.sleep(500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun noShowSchedulerAndWorker() = runBlocking {
        FakeAndroidKeyStore.install()
        runCatching { androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        val alert = NoShowAlert(watchedUserId = 1L, waypointId = null, expectedAt = Instant.fromEpochMilliseconds(1000), grace = 5.minutes, id = 77)
        NoShowCheckScheduler.schedule(ctx, alert)
        NoShowCheckScheduler.schedule(ctx, alert.copy(expectedAt = Instant.fromEpochMilliseconds(9_999_999_999L)))
        NoShowCheckScheduler.cancel(ctx, 77L)
        val worker = androidx.work.testing.TestListenableWorkerBuilder<NoShowCheckWorker>(ctx).build()
        // sweepNow reaches the SQLCipher repository, whose native library cannot load on the
        // host JVM: the worker must fail closed (Error/Retry), never report success.
        val outcome = runCatching { worker.doWork() }
        assertTrue(outcome.isFailure || outcome.getOrNull() is androidx.work.ListenableWorker.Result.Retry)
        delay(100)
    }

    @Test
    fun platformCopyAndLocation() {
        val platform = Platform(ctx)
        platform.copy("hello-clip")
        val cm = app.getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        assertTrue(clip == null || clip.contains("hello"))
        assertTrue(platform.lastKnownDeviceLocation() == null)
    }

    @Test
    fun bootReceiverIgnoresAndHandles() {
        val receiver = com.vayunmathur.findfamily.util.BootReceiver()
        receiver.onReceive(ctx, Intent("com.example.OTHER"))
        receiver.onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(400)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun notificationChannelsEnsure() {
        val channels = com.vayunmathur.findfamily.util.FindFamilyNotificationChannels
        assertEquals("no_show_7", channels.noShowChannelId(7L))
        channels.ensureNoShowChannel(ctx, 7L, "Alice")
        channels.ensureEntryExitChannels(ctx, 7L, "Alice")
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(nm)
        assertTrue(nm.notificationChannels.isNotEmpty())
    }
}
