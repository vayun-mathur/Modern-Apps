package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: DataSettings export/restore/import rows fire their launchers. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DataSettingsRowsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var vm: YouPipeViewModel

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        val db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
    }

    @Test
    fun backupRestoreRows_launchPickersAndPageSurvives() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
        rule.setContent { DataSettingsPage(stack, vm) }
        // Each row launches a document picker; under Robolectric the launch is
        // shadowed and no result comes back, so the page must stay put.
        rule.onNodeWithText(app.getString(R.string.label_export_youpipe)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.label_restore_youpipe)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.label_import_newpipe)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.label_import_youtube)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.clear_history)).assertIsDisplayed()
    }
}
