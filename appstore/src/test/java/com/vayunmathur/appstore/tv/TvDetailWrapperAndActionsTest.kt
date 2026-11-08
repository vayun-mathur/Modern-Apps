package com.vayunmathur.appstore.tv

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The TV detail wrapper against the real ViewModel, plus the TV
 * row actions: install refusal records a dismissible failure, and
 * open/uninstall/browser fire the same intents as the phone's.
 */
@RunWith(RobolectricTestRunner::class)
class TvDetailWrapperAndActionsTest {

    @get:Rule
    val rule = createComposeRule()

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
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = TvStoreViewModel(app, db)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; detail=${vm.detail.value}")
    }

    private fun nextStarted(): Intent? = Shadows.shadowOf(app).nextStartedActivity

    @Test
    fun `detail wrapper renders the selected app`() {
        vm.selectApp(
            UnifiedApp(
                packageName = "com.example.wrapper",
                source = AppSource.FDROID,
                name = "Wrapper App",
            ),
        )
        await("selected") { vm.detail.value.app?.packageName == "com.example.wrapper" }
        rule.setContent { TvStoreDetail(viewModel = vm, onBack = {}) }
        rule.onNodeWithText("Wrapper App").assertIsDisplayed()
    }

    @Test
    fun `install refusal records a failure that dismiss clears`() {
        val target = UnifiedApp(
            packageName = "com.example.refused",
            source = AppSource.FDROID,
            name = "Refused",
            apkUrl = null,
        )
        vm.install(target)
        await("failed stage") {
            vm.installer.stages.value["com.example.refused"] is InstallStage.Failed
        }
        vm.dismissInstallFailure("com.example.refused")
        assertNull(vm.installer.stages.value["com.example.refused"])
    }

    @Test
    fun `uninstall and browser actions fire their intents`() {
        vm.uninstallApp("com.example.target")
        val delete = nextStarted()
        assertNotNull(delete)
        assertEquals(Intent.ACTION_DELETE, delete.action)
        assertEquals("package:com.example.target", delete.dataString)

        vm.openInBrowser("https://example.org/app")
        val view = nextStarted()
        assertNotNull(view)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("https://example.org/app", view.dataString)

        vm.openInBrowser("  ")
        assertNull(nextStarted())
    }

    @Test
    fun `openInPlayStore falls back to the web listing when no market app resolves`() {
        vm.openInPlayStore("com.example.target")
        val intent = nextStarted()
        assertNotNull(intent)
        assertTrue(
            intent.dataString!!.contains("com.example.target"),
            "was ${intent.dataString}",
        )
    }
}
