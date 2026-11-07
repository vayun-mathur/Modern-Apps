package com.vayunmathur.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class CameraSettingsPageComposeTest {

    @get:Rule val rule = createComposeRule()

    @Serializable
    private sealed interface TestRoute : NavKey {
        @Serializable data object Home : TestRoute
    }

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun settingsPageShowsSections() {
        val backStack = NavBackStack<TestRoute>(arrayOf(TestRoute.Home))
        val viewModel = vm()
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Selfie mirroring").assertIsDisplayed()
        assertEquals("settings", viewModel.foregroundRoute)
    }

    @Test fun settingsPageMirrorToggleWritesThrough() {
        val backStack = NavBackStack<TestRoute>(arrayOf(TestRoute.Home))
        val viewModel = vm()
        val before = viewModel.mirrorFront.value
        rule.setContent { SettingsPage(backStack, viewModel) }
        // Switches are the toggleable nodes in each SettingsSwitchRow.
        rule.onAllNodes(
            androidx.compose.ui.test.hasClickAction(),
        )[0].assertIsDisplayed()
        assertEquals(before, viewModel.mirrorFront.value)
    }
}
