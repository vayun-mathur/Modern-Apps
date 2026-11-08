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
 * Update-all batch eligibility: a package this store did not install
 * (no installer of record under Robolectric) is foreign — listed but
 * never bulk-attempted — and the run settles without touching it.
 * (Marking a package store-installed needs PackageManager installer
 * state Robolectric's shadow does not expose, so the eligible-attempt
 * path itself is not exercised here.)
 */
@RunWith(RobolectricTestRunner::class)
class UpdateAllTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel
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
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
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
            "timed out waiting for: $description; partitioned=${vm.partitionedUpdates.value}",
        )
    }

    @Test
    fun `updateAll leaves foreign updates alone`() {
        // The test app was not installed by this store (no installer of
        // record under Robolectric), so its update is foreign: listed,
        // but the batch must not attempt it.
        await("foreign update") {
            vm.partitionedUpdates.value.foreign.any { it.packageName == ownPackage }
        }
        assertTrue(vm.partitionedUpdates.value.eligible.isEmpty())

        vm.updateAll()
        await("run settled") { vm.updateAllJob?.isActive != true }

        // No install was attempted for the foreign package.
        assertEquals(null, vm.installer.stages.value[ownPackage])
    }

    @Test
    fun `reason maps each verification verdict to its message`() {
        assertEquals(
            "blocked reason",
            vm.reason(com.vayunmathur.appstore.data.security.VerificationResult.Rejected("blocked reason")),
        )
        assertEquals(
            "soft reason",
            vm.reason(com.vayunmathur.appstore.data.security.VerificationResult.Unverified("soft reason")),
        )
        assertTrue(
            vm.reason(com.vayunmathur.appstore.data.security.VerificationResult.Verified("ok"))
                .isNotBlank(),
        )
    }
}
