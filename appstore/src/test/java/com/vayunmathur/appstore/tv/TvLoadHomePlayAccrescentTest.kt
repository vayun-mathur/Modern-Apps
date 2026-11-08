package com.vayunmathur.appstore.tv

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV loadHome with Play and Accrescent enabled: the Accrescent row
 * comes from the cached listings flow, and Play (unanswerable offline)
 * contributes no cluster or top-chart row, without blocking the rest.
 */
@RunWith(RobolectricTestRunner::class)
class TvLoadHomePlayAccrescentTest {

    private lateinit var vm: TvStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = TvStoreViewModel(app, db)
        vm.enabledSourcesFlow.value = setOf(
            AppSource.PLAYSTORE, AppSource.ACCRESCENT, AppSource.MODERN_APPS,
        )
        vm.accrescentAppsFlow.value = listOf(
            UnifiedApp("com.example.acc", AppSource.ACCRESCENT, "Acc App"),
        )
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; home=${vm.home.value}")
    }

    @Test
    fun `loadHome adds the accrescent row and no play row offline`() {
        vm.loadHome()
        await("accrescent section") {
            vm.home.value.sections.any { it.id == "accrescent" }
        }
        val sections = vm.home.value.sections
        assertTrue(sections.none { it.source == AppSource.PLAYSTORE }, sections.toString())
        assertTrue(
            sections.first { it.id == "accrescent" }.apps.map { it.packageName } ==
                listOf("com.example.acc"),
        )
    }
}
