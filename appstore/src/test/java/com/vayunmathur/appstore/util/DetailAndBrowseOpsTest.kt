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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Detail selection and Browse resolution: the catalogue row wins over
 * a tapped shell listing, an unknown package becomes a Play shell,
 * and Browse expands a home section to its full A–Z catalogue.
 */
@RunWith(RobolectricTestRunner::class)
class DetailAndBrowseOpsTest {

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
            db.cachedAppDao().upsertAll(
                listOf(
                    UnifiedApp(
                        packageName = "com.example.cached",
                        source = AppSource.FDROID,
                        name = "Cached Name",
                        versionCode = 9L,
                        categories = listOf("Games"),
                        repoUrl = DefaultRepos.FDROID.url,
                    ).toEntity(),
                    UnifiedApp(
                        packageName = "com.example.zmodern",
                        source = AppSource.MODERN_APPS,
                        name = "Zulu Modern",
                        repoUrl = DefaultRepos.MODERN_APPS.url,
                    ).toEntity(),
                    UnifiedApp(
                        packageName = "com.example.amodern",
                        source = AppSource.MODERN_APPS,
                        name = "Alpha Modern",
                        repoUrl = DefaultRepos.MODERN_APPS.url,
                    ).toEntity(),
                ),
            )
        }
        vm = AppStoreViewModel(app, db)
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
            "timed out waiting for: $description; detail=${vm.detail.value}; browse=${vm.browse.value}",
        )
    }

    @Test
    fun `the catalogue row wins over a tapped play shell`() {
        vm.selectApp(
            UnifiedApp(
                packageName = "com.example.cached",
                source = AppSource.PLAYSTORE,
                name = "Shell Name",
            ),
        )
        await("cached detail") { vm.detail.value.app?.name == "Cached Name" }
        assertEquals(9L, vm.detail.value.app?.versionCode)
        assertEquals(AppSource.FDROID, vm.detail.value.app?.source)
    }

    @Test
    fun `an uncached offline listing stays as tapped`() {
        val tapped = UnifiedApp(
            packageName = "com.example.uncached",
            source = AppSource.FDROID,
            name = "Uncached",
        )
        vm.selectApp(tapped)
        await("tapped detail") { vm.detail.value.app?.packageName == "com.example.uncached" }
        assertEquals("Uncached", vm.detail.value.app?.name)
    }

    @Test
    fun `selectPackage resolves cached apps and shells unknown ones`() {
        vm.selectPackage("com.example.cached")
        await("cached by package") { vm.detail.value.app?.name == "Cached Name" }

        vm.selectPackage("com.example.unknown")
        await("shell by package") { vm.detail.value.app?.packageName == "com.example.unknown" }
        assertEquals("unknown", vm.detail.value.app?.name)
        assertEquals(AppSource.PLAYSTORE, vm.detail.value.app?.source)
    }

    @Test
    fun `clearSelection empties the detail`() {
        vm.selectApp(
            UnifiedApp(
                packageName = "com.example.uncached",
                source = AppSource.FDROID,
                name = "Uncached",
            ),
        )
        await("selected") { vm.detail.value.app != null }
        vm.clearSelection()
        await("cleared") { vm.detail.value.app == null }
        assertNull(vm.detail.value.app)
    }

    @Test
    fun `browse expands an offline section to its full sorted catalogue`() {
        vm.openBrowse(AppSection(id = "modern-recent", title = "Modern", apps = emptyList()))
        await("browse loaded") { vm.browse.value.apps.size == 2 && !vm.browse.value.isLoading }
        assertEquals(
            listOf("Alpha Modern", "Zulu Modern"),
            vm.browse.value.apps.map { it.name },
        )
        assertEquals("Modern", vm.browse.value.title)
    }

    @Test
    fun `browse by category and by play cluster fallback`() {
        vm.openBrowse(AppSection(id = "category", title = "Games", apps = emptyList()))
        await("category browse") {
            vm.browse.value.apps.map { it.packageName } == listOf("com.example.cached")
        }

        val clusterApps = listOf(
            UnifiedApp("com.example.b", AppSource.PLAYSTORE, "Beta"),
            UnifiedApp("com.example.a", AppSource.PLAYSTORE, "Alpha"),
        )
        vm.openBrowse(AppSection(id = "play-cluster", title = "Play", apps = clusterApps))
        await("play browse") {
            vm.browse.value.apps.map { it.name } == listOf("Alpha", "Beta")
        }
        assertTrue(vm.browse.value.apps.all { it.source == AppSource.PLAYSTORE })
    }

    @Test
    fun `updateAll with nothing eligible is a no-op`() {
        vm.updateAll()
        await("settled") { vm.updateAllJob?.isActive != true }
    }
}
