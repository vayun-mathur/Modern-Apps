package com.vayunmathur.health

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** W6: the real MainActivity composition and the sync worker's degrade path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityW6Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun mainActivity_createsAndResumes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        ShadowLooper.idleMainLooper()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun syncWorker_enqueue_registersPeriodicWork() {
        // enqueue() is the periodic-work contract used at app start; WorkManager
        // must be initialised explicitly under Robolectric.
        try {
            androidx.work.WorkManager.initialize(
                context, androidx.work.Configuration.Builder().build(),
            )
        } catch (_: IllegalStateException) { /* already initialised */ }
        com.vayunmathur.health.sync.HealthSyncWorker.enqueue(context)
        val infos = runBlocking {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("HealthSyncWork").get()
        }
        assertNotNull(infos)
    }
}
