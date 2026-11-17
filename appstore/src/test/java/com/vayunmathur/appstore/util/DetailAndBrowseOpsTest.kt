package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.loadBrowse
import com.vayunmathur.appstore.platform.loadDetail

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
 * Route-argument loading for Detail and Browse: `loadDetail` resolves a
 * package name against the catalogue (the catalogue row wins), Accrescent
 * and Play, shelling an unknown package; `loadBrowse` expands the section
 * a `Route.Browse` argument names to its full A–Z catalogue. This is what
 * the Detail/Browse entries call with their route arguments, so a restored
 * entry shows the app/section it names.
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
    fun `loadDetail by package resolves the catalogue row`() {
        // Even a package Play also lists: the catalogue row an install
        // would use wins over any shell listing.
        vm.loadDetail("com.example.cached")
        await("cached detail") { vm.detail.value.app?.name == "Cached Name" }
        assertEquals(9L, vm.detail.value.app?.versionCode)
        assertEquals(AppSource.FDROID, vm.detail.value.app?.source)
    }

    @Test
    fun `loadDetail shells an unknown package by its last segment`() {
        vm.loadDetail("com.example.unknown")
        await("shell by package") { vm.detail.value.app?.packageName == "com.example.unknown" }
        assertEquals("unknown", vm.detail.value.app?.name)
        assertEquals(AppSource.PLAYSTORE, vm.detail.value.app?.source)
    }

    @Test
    fun `loading a second package replaces the first detail`() {
        // The route-argument contract in one test: the entry for package B
        // must never render package A's listing, not even briefly from a
        // stale flow — loadDetail drops the old app synchronously.
        vm.loadDetail("com.example.cached")
        await("first detail") { vm.detail.value.app?.packageName == "com.example.cached" }
        vm.loadDetail("com.example.zmodern")
        assertTrue(
            vm.detail.value.app?.packageName != "com.example.cached",
            "stale detail survived a new route argument: ${vm.detail.value}",
        )
        await("second detail") { vm.detail.value.app?.packageName == "com.example.zmodern" }
        assertEquals("Zulu Modern", vm.detail.value.app?.name)
    }

    @Test
    fun `browse expands an offline section to its full sorted catalogue`() {
        vm.loadBrowse("modern-recent")
        await("browse loaded") { vm.browse.value.apps.size == 2 && !vm.browse.value.isLoading }
        assertEquals("modern-recent", vm.browse.value.sectionId)
        assertEquals(
            listOf("Alpha Modern", "Zulu Modern"),
            vm.browse.value.apps.map { it.name },
        )
        assertTrue(vm.browse.value.title.isNotBlank())
    }

    @Test
    fun `browse by category and by play cluster fallback`() {
        // A category's name lives only in the home row's title: browse the
        // category home is currently showing.
        vm.selectCategory("Games")
        await("category section on home") {
            vm.home.value.sections.any { it.id == "category" && it.title == "Games" }
        }
        vm.loadBrowse("category")
        await("category browse") {
            vm.browse.value.apps.map { it.packageName } == listOf("com.example.cached")
        }

        val clusterApps = listOf(
            UnifiedApp("com.example.b", AppSource.PLAYSTORE, "Beta"),
            UnifiedApp("com.example.a", AppSource.PLAYSTORE, "Alpha"),
        )
        vm.playFullClustersFlow.value = mapOf("play-cluster" to clusterApps)
        vm.loadBrowse("play-cluster")
        await("play browse") {
            vm.browse.value.apps.map { it.name } == listOf("Alpha", "Beta")
        }
        assertEquals("play-cluster", vm.browse.value.sectionId)
        assertTrue(vm.browse.value.apps.all { it.source == AppSource.PLAYSTORE })
    }

    @Test
    fun `updateAll with nothing eligible is a no-op`() {
        vm.updateAll()
        await("settled") { vm.updateAllJob?.isActive != true }
    }
}
