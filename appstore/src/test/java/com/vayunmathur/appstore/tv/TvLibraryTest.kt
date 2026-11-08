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
import com.vayunmathur.appstore.util.SourceFilter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV library attribution: the installed test app is attributed to the
 * catalogue source that lists it (when that source is enabled), left
 * out when it is disabled, and counted per filter.
 */
@RunWith(RobolectricTestRunner::class)
class TvLibraryTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel
    private lateinit var ownPackage: String

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ownPackage = app.packageName
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = ownPackage,
                    source = AppSource.MODERN_APPS,
                    name = "This Store",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = TvStoreViewModel(app, db)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "timed out waiting for: $description; library=${vm.library.value}",
        )
    }

    @Test
    fun `the installed app is attributed to its catalogue source`() {
        await("library attribution") {
            vm.library.value.apps.any { it.packageName == ownPackage }
        }
        val listing = vm.library.value.apps.first { it.packageName == ownPackage }
        assertEquals(AppSource.MODERN_APPS, listing.source)
        assertEquals(1, vm.library.value.counts[SourceFilter.ALL])
        assertEquals(1, vm.library.value.counts[SourceFilter.MODERN_APPS])
        assertEquals(0, vm.library.value.counts[SourceFilter.FDROID])
    }

    @Test
    fun `disabling the source removes the attribution`() {
        await("library attribution") {
            vm.library.value.apps.any { it.packageName == ownPackage }
        }
        vm.enabledSourcesFlow.value = emptySet()
        await("attribution removed") { vm.library.value.apps.isEmpty() }
        assertEquals(0, vm.library.value.counts[SourceFilter.ALL])
    }

    @Test
    fun `the library filter narrows to one source`() {
        await("library attribution") {
            vm.library.value.apps.any { it.packageName == ownPackage }
        }
        vm.setLibraryFilter(SourceFilter.FDROID)
        await("filtered out") { vm.library.value.apps.isEmpty() }
        assertEquals(SourceFilter.FDROID, vm.library.value.filter)
        // Counts still describe the whole library, not the filtered view.
        assertEquals(1, vm.library.value.counts[SourceFilter.ALL])

        vm.setLibraryFilter(SourceFilter.MODERN_APPS)
        await("filtered in") {
            vm.library.value.apps.map { it.packageName } == listOf(ownPackage)
        }
        assertTrue(vm.library.value.apps.single().source == AppSource.MODERN_APPS)
    }
}
