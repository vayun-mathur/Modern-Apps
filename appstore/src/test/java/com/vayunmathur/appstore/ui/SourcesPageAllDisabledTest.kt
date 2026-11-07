package com.vayunmathur.appstore.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.util.AppStoreViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * With every toggleable source switched off the sources screen says
 * so plainly, instead of presenting six off switches with no hint
 * that the store now has nothing to browse from.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesPageAllDisabledTest {

    @get:Rule
    val rule = createComposeRule()

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
    fun `switching every source off shows the nothing to browse warning`() {
        for (source in AppSource.TOGGLEABLE) vm.setSourceEnabled(source, false)
        await("all sources disabled") {
            AppSource.TOGGLEABLE.none { it in vm.enabledSources.value }
        }

        rule.setContent { SourcesPage(viewModel = vm) }

        rule.onNodeWithText("Every source is switched off", substring = true)
            .assertIsDisplayed()
    }
}
