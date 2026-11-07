package com.vayunmathur.appstore.tv

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.SourceFilter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV ViewModel state derivation, mirroring the phone ViewModel test:
 * raw flows in, per-screen TV state out, no network required.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel

    private fun app(pkg: String, source: AppSource = AppSource.FDROID, name: String = pkg) =
        UnifiedApp(packageName = pkg, source = source, name = name, versionCode = 2L)

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = TvStoreViewModel(app, db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; home=${vm.home.value}")
    }

    @Test
    fun `factory creates the tv view model`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val created = TvStoreViewModelFactory(app, db).create(TvStoreViewModel::class.java)
        assertEquals(app.packageName, created.context.packageName)
    }

    @Test
    fun `home follows sections and status flows`() {
        val section = TvSection(id = "recent", title = "Recently updated", apps = listOf(app("com.example.a", name = "Alpha")))
        vm.sectionsFlow.value = listOf(section)
        vm.statusMessageFlow.value = "Checking sources"
        await("home section") { vm.home.value.sections == listOf(section) }
        assertEquals("Checking sources", vm.home.value.statusMessage)
    }

    @Test
    fun `select and clear an app drives the detail state`() {
        val selected = app("com.example.detail", name = "Detail")
        vm.selectApp(selected)
        await("detail selected") { vm.detail.value.app?.packageName == "com.example.detail" }
        vm.clearSelection()
        await("detail cleared") { vm.detail.value.app == null }
        assertNull(vm.detail.value.app)
    }

    @Test
    fun `browse opens a section and clears`() {
        val section = TvSection(
            id = "play-cluster",
            title = "Top apps",
            apps = listOf(app("com.example.b", name = "Beta"), app("com.example.a", name = "Alpha")),
        )
        vm.openBrowse(section)
        await("browse apps sorted") {
            vm.browse.value.apps.map { it.name } == listOf("Alpha", "Beta")
        }
        assertEquals("Top apps", vm.browse.value.title)
        vm.clearBrowse()
        await("browse cleared") { vm.browse.value.apps.isEmpty() && vm.browse.value.title.isEmpty() }
    }

    @Test
    fun `search filter narrows the tv search state`() {
        vm.queryFlow.value = "alpha"
        vm.searchResultsFlow.value = listOf(
            app("com.example.f", AppSource.FDROID, "Alpha F"),
            app("com.example.m", AppSource.MODERN_APPS, "Alpha M"),
        )
        await("all results") { vm.search.value.results.size == 2 }
        vm.setSearchFilter(SourceFilter.MODERN_APPS)
        await("filtered") { vm.search.value.results.map { it.packageName } == listOf("com.example.m") }
    }

    @Test
    fun `category selection with no rows yields an empty category section`() {
        vm.selectCategory("Games")
        await("category section") {
            vm.home.value.sections.any { it.id == "category" && it.title == "Games" }
        }
        assertTrue(vm.home.value.sections.first { it.id == "category" }.apps.isEmpty())
        vm.selectCategory(null)
        await("category cleared") { vm.selectedCategoryFlow.value == null }
    }
}
