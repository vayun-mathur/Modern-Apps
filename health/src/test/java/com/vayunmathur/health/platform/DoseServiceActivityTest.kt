package com.vayunmathur.health.platform

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.service.DoseSoundService
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoseServiceActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    @Test
    fun `dose sound service starts sticky and binds null`() {
        val intent = Intent(context, DoseSoundService::class.java).apply {
            putExtra(DoseSoundService.EXTRA_MEDICATION_NAME, "Aspirin")
        }
        val controller = Robolectric.buildService(DoseSoundService::class.java, intent).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 1))
        // Second start is a no-op but still sticky.
        assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 2))
        assertNull(service.onBind(intent))
        controller.destroy()
    }

    @Test
    fun `dose sound service handles null intent`() {
        val controller = Robolectric.buildService(DoseSoundService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        controller.destroy()
    }

}
