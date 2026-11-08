package com.vayunmathur.appstore.tv

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
 * The TV search with Play and Accrescent enabled: the local catalogue
 * hit still leads, the network halves (unanswerable offline) merge in
 * as empty without erasing it, and no Play error is recorded.
 */
@RunWith(RobolectricTestRunner::class)
class TvSearchLiveSourcesTest {

    private lateinit var vm: TvStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
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
        vm = TvStoreViewModel(app, db)
        vm.enabledSourcesFlow.value = setOf(
            AppSource.MODERN_APPS, AppSource.PLAYSTORE, AppSource.ACCRESCENT,
        )
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; search=${vm.search.value}")
    }

    @Test
    fun `live sources enabled still surface the catalogue hit`() {
        vm.setSearch("alpha")
        await("searched") { vm.search.value.hasSearched }
        assertEquals(
            listOf("com.example.alpha"),
            vm.search.value.results.map { it.packageName },
        )
        assertTrue(vm.search.value.playError.isEmpty(), vm.search.value.playError)
    }
}
