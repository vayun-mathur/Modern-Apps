package com.vayunmathur.appstore.util

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Phone ViewModel source toggles and installed refresh: turning a source
 * off drops what it contributed to the screens (Play sections, Accrescent
 * rows and updates) and turning it back on restores the enabled set,
 * while refreshInstalled re-reads the device into the library state.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSourceToggleTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // The library only lists installed apps a source vouches for:
        // seed the catalogue row that attributes this test app.
        kotlinx.coroutines.runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = app.packageName,
                    source = AppSource.MODERN_APPS,
                    name = "This Store",
                ).toEntity()
                    .copy(repoUrl = com.vayunmathur.appstore.data.DefaultRepos.MODERN_APPS.url),
            )
        }
        vm = AppStoreViewModel(app, db)
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
    fun `turning play off forgets its sections updates and installed set`() {
        vm.playSectionsFlow.value = listOf(
            AppSection("play-top", "Top", emptyList(), subtitle = "sub"),
        )
        vm.playUpdatesFlow.value = listOf(
            UnifiedApp("com.example.p", AppSource.PLAYSTORE, "P"),
        )
        vm.playInstalledPackagesFlow.value = setOf("com.example.p")
        vm.setSourceEnabled(AppSource.PLAYSTORE, false)
        await("play forgotten") {
            vm.playSectionsFlow.value.isEmpty() &&
                vm.playUpdatesFlow.value.isEmpty() &&
                vm.playInstalledPackagesFlow.value.isEmpty()
        }
        assertTrue(AppSource.PLAYSTORE !in vm.enabledSources.value)
    }

    @Test
    fun `turning accrescent off forgets its rows and updates`() {
        vm.accrescentAppsFlow.value = listOf(
            UnifiedApp("com.example.a", AppSource.ACCRESCENT, "A"),
        )
        vm.accrescentUpdatesFlow.value = listOf(
            UnifiedApp("com.example.a", AppSource.ACCRESCENT, "A"),
        )
        vm.accrescentPackagesFlow.value = setOf("com.example.a")
        vm.setSourceEnabled(AppSource.ACCRESCENT, false)
        await("accrescent forgotten") {
            vm.accrescentAppsFlow.value.isEmpty() &&
                vm.accrescentUpdatesFlow.value.isEmpty() &&
                vm.accrescentPackagesFlow.value.isEmpty()
        }
    }

    @Test
    fun `turning an offline source off and on settles in the enabled set`() {
        vm.setSourceEnabled(AppSource.FDROID, true)
        await("fdroid enabled") { AppSource.FDROID in vm.enabledSources.value }
        vm.setSourceEnabled(AppSource.FDROID, false)
        await("fdroid disabled") { AppSource.FDROID !in vm.enabledSources.value }
    }

    @Test
    fun `refreshInstalled republishes the device apps`() {
        vm.refreshInstalled()
        await("library contains this app") {
            vm.library.value.apps.any { it.packageName == app.packageName }
        }
        val mine = vm.library.value.apps.first { it.packageName == app.packageName }
        assertEquals(app.packageName, mine.packageName)
    }

    @Test
    fun `auto install toggle round trips through settings`() {
        vm.setAutoInstallUpdates(true)
        await("auto install on") { vm.autoInstallUpdates.value }
        vm.setAutoInstallUpdates(false)
        await("auto install off") { !vm.autoInstallUpdates.value }
    }
}
