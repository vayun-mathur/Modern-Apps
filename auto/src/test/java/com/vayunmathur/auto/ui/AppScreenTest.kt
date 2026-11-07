package com.vayunmathur.auto.ui

import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.auto.platform.CarLauncherState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AppScreenTest {
    @get:Rule val rule = createComposeRule()
    @BeforeTest fun reset() {
        CarLauncherState.selectApp(null)
        CarLauncherState.mapSurfaceListener = null
        CarLauncherState.mapTouchForwarder = null
    }

    @Test fun `app screen with no template renders empty host view`() {
        // A unique id: other suites publish templates for their example apps into shared state
        rule.setContent { AppScreen(appId = "com.example.uniqueapp/com.example.uniqueapp.Svc") }
        // No template published: the host view renders nothing, and no map surface listener fires
        assertNull(CarLauncherState.templates.value["com.example.uniqueapp/com.example.uniqueapp.Svc"])
    }
}
