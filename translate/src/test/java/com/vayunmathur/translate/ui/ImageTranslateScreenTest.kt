package com.vayunmathur.translate.ui

import android.app.Application
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.platform.FakeAndroidKeyStoreProvider
import com.vayunmathur.translate.platform.TranslateViewModel
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An [Application] whose assets are the framework's only: the PP-OCR model
 * files that `:library:ocr` bundles are absent, so [com.vayunmathur.library.ml.TextRecognizer]
 * degrades to unavailable (as it does on a device without Vulkan) instead of
 * reaching the Android-only JNI bridge, which a host JVM cannot call.
 */
class MissingOcrAssetsApplication : Application() {
    override fun getAssets(): AssetManager = Resources.getSystem().assets
}

@RunWith(RobolectricTestRunner::class)
@Config(application = MissingOcrAssetsApplication::class)
class ImageTranslateScreenTest {

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

    private fun imageFile(): Uri {
        val file = File.createTempFile("translate-image", ".png")
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun `image screen decodes png and shows language bar without ocr results`() {
        var pickerFor: Boolean? = null; var back = 0
        rule.setContent {
            ImageTranslateScreen(
                viewModel = vm(),
                imageUri = imageFile().toString(),
                onBack = { back++ },
                onPickAnother = {},
                onOpenLanguagePicker = { pickerFor = it },
            )
        }
        // The overlay language bar is the marker that decode + first frame landed.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Español").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Español").performClick()
        assertEquals(false, pickerFor)
        rule.onNodeWithText("Auto").performClick()
        assertEquals(true, pickerFor)
        assertEquals(0, back)
    }

    @Test
    fun `image screen with unreadable uri shows failure state`() {
        var picked = 0
        rule.setContent {
            ImageTranslateScreen(
                viewModel = vm(),
                imageUri = Uri.fromFile(File("/nonexistent-dir/missing.png")).toString(),
                onBack = {},
                onPickAnother = { picked++ },
                onOpenLanguagePicker = {},
            )
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Couldn’t read that image").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Pick another image").performClick()
        assertEquals(1, picked)
    }

    @Test
    fun `image page binds screen and its retry picker`() {
        var replaced: String? = null
        rule.setContent {
            ImageTranslatePage(
                viewModel = vm(),
                imageUri = imageFile().toString(),
                onReplaceImage = { replaced = it },
                onBack = {},
                onOpenLanguagePicker = {},
            )
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Español").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Español").assertIsDisplayed()
        assertEquals(null, replaced)
    }
}
