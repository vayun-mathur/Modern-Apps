package com.vayunmathur.appstore.data.installer

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.VerificationResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Install dispatch refusals that happen before any download: a source
 * that published no URL, and an Accrescent app the signed allowlist does
 * not vouch for. Both must record a Failed stage and the verification
 * verdict, and a dismissed failure must clear the stage.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InstallCoordinator

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined)
        coordinator = InstallCoordinator(
            context = ctx,
            db = db,
            play = PlayRepository(ctx),
            accrescent = AccrescentRepository(ctx, db),
            scope = scope,
            ownSigningCertificates = { setOf("ab".repeat(32)) },
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `a listing with no download url is refused and recorded`() = runTest {
        val app = UnifiedApp(
            packageName = "com.example.nourl",
            source = AppSource.FDROID,
            name = "No URL",
            apkUrl = null,
        )
        val outcome = coordinator.install(app)
        assertFalse(outcome.started)
        val rejected = outcome.verification as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("no download"), rejected.reason)
        assertEquals(rejected, coordinator.verification.value["com.example.nourl"])
        assertIs<InstallStage.Failed>(coordinator.stages.value["com.example.nourl"])
        assertFalse(coordinator.isBusy("com.example.nourl"))
    }

    @Test
    fun `dismissing a failure clears the stage but not for unknown packages`() = runTest {
        val app = UnifiedApp(
            packageName = "com.example.dismiss",
            source = AppSource.MODERN_APPS,
            name = "Dismiss Me",
            apkUrl = null,
        )
        coordinator.install(app)
        assertIs<InstallStage.Failed>(coordinator.stages.value["com.example.dismiss"])
        coordinator.dismissFailure("com.example.dismiss")
        assertEquals(null, coordinator.stages.value["com.example.dismiss"])
        // Dismissing a package with no stage is a no-op, not a crash.
        coordinator.dismissFailure("com.example.never-seen")
        assertFalse(coordinator.isBusy("com.example.never-seen"))
    }

    @Test
    fun `an accrescent app with no trust anchor is refused before downloading`() = runTest {
        val app = UnifiedApp(
            packageName = "com.example.unvouched",
            source = AppSource.ACCRESCENT,
            name = "Unvouched",
        )
        val outcome = coordinator.install(app)
        assertFalse(outcome.started)
        val rejected = outcome.verification as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("does not vouch"), rejected.reason)
        assertIs<InstallStage.Failed>(coordinator.stages.value["com.example.unvouched"])
    }
}
