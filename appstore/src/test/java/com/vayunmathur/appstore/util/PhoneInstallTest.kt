package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.reason

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The phone install action: an app whose source published no download
 * is rejected by the pipeline and the ViewModel surfaces the failed
 * stage (the message itself goes through AppMessages).
 */
@RunWith(RobolectricTestRunner::class)
class PhoneInstallTest {

    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = AppStoreViewModel(app, db)
    }

    @Test
    fun `installing an app with no download fails its stage`() {
        val target = UnifiedApp(
            packageName = "com.example.phone.nodownload",
            source = AppSource.FDROID,
            name = "No Download",
        )
        vm.install(target)
        // Step-bounded pump instead of a wall-clock deadline (see
        // PagesWrapperTest): looper steps, not seconds CI contention
        // can burn before the install settles.
        var steps = 0
        while (steps++ < 6_000) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (vm.installer.stages.value[target.packageName] is InstallStage.Failed) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10),
            )
        }
        val stage = vm.installer.stages.value[target.packageName]
        assertTrue(stage is InstallStage.Failed, "stage was $stage")
        assertTrue(stage.reason.isNotBlank())
    }
}
