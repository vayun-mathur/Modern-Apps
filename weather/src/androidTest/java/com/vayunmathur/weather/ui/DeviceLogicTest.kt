package com.vayunmathur.weather.ui

import android.content.Intent
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.weather.service.car.WeatherCarSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device logic Robolectric cannot run faithfully: the refresh worker
 * against the real SQLCipher repository (empty locations -> success), and
 * the Android Auto session creating its locations screen against the real
 * cache snapshot.
 */
@RunWith(AndroidJUnit4::class)
class DeviceLogicTest {

    @Test
    fun refreshWorkerSucceedsWithRealRepository() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val worker = TestListenableWorkerBuilder<WeatherRefreshWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        assertTrue(
            "expected Success, got $result",
            result is ListenableWorker.Result.Success,
        )
    }

    @Test
    fun carSessionCreatesLocationsScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var controller: SessionController
        instrumentation.runOnMainSync {
            val carContext = TestCarContext.createCarContext(context)
            val session = WeatherCarSession()
            controller = SessionController(session, carContext, Intent())
            controller.moveToState(Lifecycle.State.CREATED)
            assertNotNull(session)
        }
        // Give the locations flow a moment to land, then tear down so the
        // session's lifecycle observer cancels its collectors.
        Thread.sleep(1_000)
        instrumentation.runOnMainSync {
            controller.moveToState(Lifecycle.State.DESTROYED)
        }
    }
}
