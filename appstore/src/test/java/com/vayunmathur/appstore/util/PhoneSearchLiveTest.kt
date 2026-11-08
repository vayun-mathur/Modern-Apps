package com.vayunmathur.appstore.util

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The phone live search: with the network sources switched off in
 * persisted settings (the search job re-reads them, so the gRPC/Play
 * halves are skipped), the debounced search answers from the on-disk
 * catalogue and settles.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSearchLiveTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = "com.example.alpha",
                    source = AppSource.MODERN_APPS,
                    name = "Alpha",
                    summary = "the alpha app",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
        runBlocking {
            vm.settings.setSourceEnabled(AppSource.ACCRESCENT, false)
            vm.settings.setSourceEnabled(AppSource.PLAYSTORE, false)
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "timed out waiting for: $description; search=${vm.search.value}",
        )
    }

    @Test
    fun `a query finds the catalogue hit after the debounce`() {
        vm.setSearch("alpha")
        await("results") {
            vm.search.value.results.map { it.packageName } == listOf("com.example.alpha")
        }
        assertTrue(vm.search.value.hasSearched)
        assertEquals("alpha", vm.search.value.query)
        assertTrue(vm.search.value.playError.isEmpty())
    }

    @Test
    fun `a query with no hits settles as searched and empty`() {
        vm.setSearch("zzzznothing")
        await("settled empty") {
            vm.search.value.hasSearched && vm.search.value.results.isEmpty()
        }
    }
}
