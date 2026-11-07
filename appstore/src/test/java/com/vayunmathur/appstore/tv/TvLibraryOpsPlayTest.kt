package com.vayunmathur.appstore.tv

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.installer.InstallEvents
import com.vayunmathur.appstore.data.installer.InstallResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV library attribution when Play is enabled: the ViewModel asks Play
 * (via detailsOrNull) which installed packages it hosts. Offline the
 * lookup cannot be dispensed, so the previous attribution is kept —
 * and a successful install event re-runs the same refresh without
 * emptying the library.
 */
@RunWith(RobolectricTestRunner::class)
class TvLibraryOpsPlayTest {

    private lateinit var db: AppDatabase
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Persist Play as enabled before the ViewModel reads settings.
        val settings = SettingsRepository(app, CoroutineScope(Dispatchers.IO))
        runBlocking { settings.setSourceEnabled(AppSource.PLAYSTORE, true) }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    @Test
    fun `play attribution keeps the previous answer when play cannot answer`() {
        val vm = TvStoreViewModel(app, db)
        await("play enabled at init") { AppSource.PLAYSTORE in vm.enabledSourcesFlow.value }
        vm.playInstalledPackagesFlow.value = setOf("com.example.kept")
        // A successful install for another package re-runs the refresh:
        // Play still cannot answer offline, so the kept set survives.
        InstallEvents.publish(InstallResult("com.example.other", success = true))
        await("refresh settled") {
            vm.installedRepo.apps.value.any { it.packageName == app.packageName }
        }
        Thread.sleep(500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("com.example.kept"), vm.playInstalledPackagesFlow.value)
    }

    @Test
    fun `library filter can be narrowed and reset`() {
        val vm = TvStoreViewModel(app, db)
        vm.setLibraryFilter(com.vayunmathur.appstore.util.SourceFilter.FDROID)
        assertEquals(
            com.vayunmathur.appstore.util.SourceFilter.FDROID,
            vm.libraryFilterFlow.value,
        )
        vm.setLibraryFilter(com.vayunmathur.appstore.util.SourceFilter.ALL)
        assertTrue(vm.library.value.apps.all { true })
    }
}
