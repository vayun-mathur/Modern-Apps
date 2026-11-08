package com.vayunmathur.appstore.tv

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The TV manual update check with only Modern Apps enabled (the
 * catalogue sync for it fails offline and keeps the cached rows): the catalogue
 * half still answers from Room (this test app, offered a much newer
 * build — its installed versionCode is date-based), and the check
 * always settles instead of wedging in "checking".
 */
@RunWith(RobolectricTestRunner::class)
class TvUpdateCheckTest {

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
                    versionCode = 99_999_999L,
                    // Purges key on the repo URL, not the source: the row
                    // must carry the Modern Apps repo it claims to be from.
                    repoUrl = com.vayunmathur.appstore.data.DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        vm = TvStoreViewModel(app, db)
        vm.enabledSourcesFlow.value = setOf(AppSource.MODERN_APPS)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "timed out waiting for: $description; updates=${vm.updatesUi.value}",
        )
    }

    @Test
    fun `checkForUpdates finds the catalogue update and settles`() {
        vm.checkForUpdates()
        await("check settled") {
            !vm.updatesUi.value.isChecking && vm.updatesUi.value.lastCheckedAt > 0L
        }
        assertTrue(
            vm.catalogUpdatesFlow.value.any { it.packageName == ownPackage },
            "catalogue updates were ${vm.catalogUpdatesFlow.value.map { it.packageName }}; dbrow=${runBlocking { db.cachedAppDao().byPackage(ownPackage)?.versionCode }}; rows=${runBlocking { db.cachedAppDao().all().size }}",
        )
        assertEquals(
            99_999_999L,
            vm.catalogUpdatesFlow.value.first { it.packageName == ownPackage }.versionCode,
        )
        assertFalse(vm.updatesUi.value.isChecking)
    }

    @Test
    fun `updateAll with nothing pending is a no-op`() {
        vm.updateAll()
        await("settled") { vm.updateAllJob?.isActive != true }
    }
}
