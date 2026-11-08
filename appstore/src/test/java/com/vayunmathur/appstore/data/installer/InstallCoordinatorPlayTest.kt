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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Play install path with no reachable Play session: the purchase
 * cannot be dispensed, so the install must fail the row (never crash,
 * never start a session), with the failure recorded as the stage.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorPlayTest {

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
            ownSigningCertificates = { emptySet() },
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `a play install with no session fails the row instead of crashing`() = runBlocking {
        val app = UnifiedApp(
            packageName = "com.example.playapp",
            source = AppSource.PLAYSTORE,
            name = "Play App",
            versionCode = 5L,
        )
        val outcome = coordinator.install(app)
        assertFalse(outcome.started)
        assertIs<VerificationResult.Rejected>(outcome.verification)
        assertIs<InstallStage.Failed>(coordinator.stages.value["com.example.playapp"])
        assertFalse(coordinator.isBusy("com.example.playapp"))
    }
}
