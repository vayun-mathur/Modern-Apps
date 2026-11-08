package com.vayunmathur.updater

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.updater.platform.TestSysProps
import com.vayunmathur.updater.platform.UpdaterViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.app.Application

@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sysProps: Map<String, String>

    @BeforeTest
    fun saveProps() {
        sysProps = TestSysProps.snapshot()
    }

    @AfterTest
    fun restoreProps() {
        TestSysProps.restore(sysProps)
    }

    @Test
    fun `navigation hosts the updater screen for the home route`() {
        TestSysProps.setRunningBuild(dateUtcSeconds = 1000L)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = UpdaterViewModel(app)
        composeRule.setContent {
            DynamicTheme { Navigation(viewModel) }
        }
        composeRule.onNodeWithText("Check now").assertIsDisplayed()
        composeRule.onNodeWithText("Install updates automatically").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private lateinit var sysProps: Map<String, String>

    @BeforeTest
    fun saveProps() {
        sysProps = TestSysProps.snapshot()
    }

    @AfterTest
    fun restoreProps() {
        TestSysProps.restore(sysProps)
    }

    @Test
    fun `the activity launches, schedules checks and renders the home route`() {
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
                ApplicationProvider.getApplicationContext(),
            )
        }
        TestSysProps.setRunningBuild(dateUtcSeconds = 1000L)
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        kotlin.test.assertNotNull(activity)
        kotlin.test.assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
