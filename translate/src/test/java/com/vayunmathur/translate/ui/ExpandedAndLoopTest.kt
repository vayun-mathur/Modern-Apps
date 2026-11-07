package com.vayunmathur.translate.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.platform.FakeAndroidKeyStoreProvider
import com.vayunmathur.translate.platform.TextTranslateActions
import com.vayunmathur.translate.platform.TextTranslateUiState
import com.vayunmathur.translate.platform.TranslateViewModel
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** The text screen at expanded width: source and output panels side by side. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class ExpandedAndLoopTest {

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
    fun `expanded text screen shows both panels side by side`() {
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(
                    sourceLang = "en", targetLang = "es",
                    translationAvailable = true,
                    inputText = "wide input", outputText = "entrada ancha",
                ),
                TextTranslateActions.Noop,
            )
        }
        rule.onNodeWithText("wide input").assertIsDisplayed()
        rule.onNodeWithText("entrada ancha").assertIsDisplayed()
    }

    @Test
    fun `translation loop without availability reports empty and stops`() {
        var reported: Map<String, String>? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TranslationLoop(
                viewModel = vm(),
                sourceLang = "en", targetLang = "es",
                translationAvailable = false,
                overlays = listOf(OverlayBox(listOf(Offset(0f, 0f)), "hello")),
                onTranslations = { reported = it },
            )
        }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(emptyMap(), reported)
    }

    @Test
    fun `translation loop with all lines memoized idles`() {
        var reported: Map<String, String>? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TranslationLoop(
                viewModel = vm(),
                sourceLang = "en", targetLang = "es",
                translationAvailable = true,
                overlays = emptyList(),
                onTranslations = { reported = it },
            )
        }
        rule.mainClock.advanceTimeBy(1000)
        // First callback is always the reset to an empty map.
        assertEquals(emptyMap(), reported)
    }
}
