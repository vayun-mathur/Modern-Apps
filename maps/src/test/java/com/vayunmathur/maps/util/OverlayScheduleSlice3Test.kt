package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 (root package files M-Z): [MapOverlayWorker]'s scheduling
 * surface — the periodic auto-check chain, its cancellation, and the manual
 * / auto one-shot enqueues.
 *
 * Scheduling is asserted through WorkManager's unique-work listings (live
 * entries only — cancelled ones linger). The worker's `doWork` itself hits
 * the network for the manifest and is out of reach on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayScheduleSlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun initWork() {
        runCatching {
            WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    private fun liveUnique(name: String) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .filter { !it.state.isFinished }

    @Test
    fun `scheduleAuto enqueues the periodic chain`() {
        MapOverlayWorker.scheduleAuto(context)
        assertTrue(liveUnique("map_overlay_periodic").isNotEmpty())
    }

    @Test
    fun `cancelAuto drops the periodic chain`() {
        MapOverlayWorker.scheduleAuto(context)
        assertTrue(liveUnique("map_overlay_periodic").isNotEmpty())
        MapOverlayWorker.cancelAuto(context)
        assertTrue(liveUnique("map_overlay_periodic").isEmpty())
    }

    @Test
    fun `manual and auto checks enqueue one-shot work`() {
        MapOverlayWorker.checkManual(context)
        assertTrue(liveUnique("map_overlay_manual").isNotEmpty())
        MapOverlayWorker.checkAuto(context)
        assertTrue(liveUnique("map_overlay_auto").isNotEmpty())
    }

    @Test
    fun `max attempts is the documented retry bound`() {
        assertEquals(6, MapOverlayWorker.maxAttempts())
    }
}
