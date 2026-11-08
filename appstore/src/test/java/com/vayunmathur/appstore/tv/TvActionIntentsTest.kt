package com.vayunmathur.appstore.tv

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV ViewModel actions that leave the app: opening an installed app,
 * uninstalling, opening the browser / Play listing, and installing an
 * app with no download all do their work through intents or the install
 * pipeline, assertable through Robolectric's started-activity shadow.
 */
@RunWith(RobolectricTestRunner::class)
class TvActionIntentsTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = TvStoreViewModel(app, db)
    }

    private fun nextStarted(): Intent? = Shadows.shadowOf(app).nextStartedActivity

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    @Test
    fun `openApp launches the installed app and ignores unknown packages`() {
        vm.openApp(app.packageName)
        val started = nextStarted()
        assertNotNull(started, "opening the installed test app must start an activity")
        assertEquals(app.packageName, started.`package` ?: started.component?.packageName)

        vm.openApp("com.example.not.installed")
        // At most the same intent again is not expected: nothing new starts.
        assertNull(nextStarted())
    }

    @Test
    fun `uninstallApp fires the system delete intent`() {
        vm.uninstallApp("com.example.remove.me")
        val started = nextStarted()
        assertNotNull(started)
        assertEquals(Intent.ACTION_DELETE, started.action)
        assertEquals("package:com.example.remove.me", started.dataString)
        assertTrue(started.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }

    @Test
    fun `openInPlayStore falls back to the web listing when no market app resolves`() {
        vm.openInPlayStore("com.example.listed")
        val started = nextStarted()
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertTrue(
            started.dataString.orEmpty().contains("com.example.listed"),
            "started ${started.dataString}",
        )
    }

    @Test
    fun `openInBrowser ignores blank urls and opens real ones`() {
        vm.openInBrowser("   ")
        assertNull(nextStarted())
        vm.openInBrowser("https://example.org/project")
        val started = nextStarted()
        assertNotNull(started)
        assertEquals("https://example.org/project", started.dataString)
    }

    @Test
    fun `installing an app with no download fails its stage`() {
        val target = UnifiedApp(
            packageName = "com.example.nodownload",
            source = AppSource.FDROID,
            name = "No Download",
        )
        vm.install(target)
        await("install stage failed") {
            vm.installer.stages.value["com.example.nodownload"] != null
        }
        assertTrue(
            vm.installer.stages.value["com.example.nodownload"]
                is com.vayunmathur.appstore.data.installer.InstallStage.Failed,
            "stage was ${vm.installer.stages.value["com.example.nodownload"]}",
        )
    }

    @Test
    fun `toggling a source off drops its in-memory contributions`() {
        vm.playUpdatesFlow.value = listOf(
            UnifiedApp("com.example.play", AppSource.PLAYSTORE, "Play App"),
        )
        vm.accrescentAppsFlow.value = listOf(
            UnifiedApp("com.example.acc", AppSource.ACCRESCENT, "Acc App"),
        )
        vm.setSourceEnabled(AppSource.PLAYSTORE, false)
        await("play contributions forgotten") { vm.playUpdatesFlow.value.isEmpty() }
        vm.setSourceEnabled(AppSource.ACCRESCENT, false)
        await("accrescent contributions forgotten") { vm.accrescentAppsFlow.value.isEmpty() }
        vm.setSourceEnabled(AppSource.FDROID, true)
        await("fdroid enabled") { AppSource.FDROID in vm.enabledSourcesFlow.value }
        vm.setSourceEnabled(AppSource.FDROID, false)
        await("fdroid toggle settles") {
            AppSource.FDROID !in vm.enabledSourcesFlow.value
        }
    }
}
