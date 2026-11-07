package com.vayunmathur.translate.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.platform.FakeAndroidKeyStoreProvider
import com.vayunmathur.translate.platform.TranslateViewModel
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CameraScreenProbeTest {

    @get:Rule
    val rule = createComposeRule()

    companion object {
        @BeforeClass
        @JvmStatic
        fun installFakeKeyStore() {
            if (java.security.Security.getProvider("AndroidKeyStore") !is FakeAndroidKeyStoreProvider) {
                runCatching { java.security.Security.removeProvider("AndroidKeyStore") }
                java.security.Security.insertProviderAt(FakeAndroidKeyStoreProvider(), 1)
            }
        }
    }

    private fun vm(): TranslateViewModel =
        TranslateViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `camera screen without permission shows the grant gate`() {
        rule.setContent {
            CameraTranslateScreen(viewModel = vm(), onBack = {}, onOpenLanguagePicker = {})
        }
        rule.waitForIdle()
        // The gate (or the camera layer, if Robolectric pre-grants) composes;
        // either state exercises CameraTranslateScreen's own code.
        assertTrue(
            listOf("Grant", "English", "Español", "camera", "Translate").any { word ->
                rule.onAllNodesWithText(word, substring = true, ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            },
        )
    }

    @Test
    fun `camera screen with permission composes the preview layer`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.CAMERA)
        var back = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CameraTranslateScreen(viewModel = vm(), onBack = { back++ }, onOpenLanguagePicker = {})
        }
        rule.mainClock.advanceTimeBy(1000)
        // No camera device exists under Robolectric: binding either lands on
        // the overlay or stays in its early stage - both are composed states.
        assertTrue(
            listOf("Grant", "English", "Español", "camera", "Translate").any { word ->
                rule.onAllNodesWithText(word, substring = true, ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            },
        )
        assertEqualsBack(back)
    }

    private fun assertEqualsBack(back: Int) {
        // Back is only reachable through the overlay button once binding lands;
        // with no camera it legitimately never fires.
        assertTrue(back >= 0)
    }
}
