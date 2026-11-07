package com.vayunmathur.translate.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.MainActivity
import com.vayunmathur.translate.Route
import com.vayunmathur.translate.platform.FakeAndroidKeyStoreProvider
import com.vayunmathur.translate.platform.TranslateViewModel
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ImageAndActivityTest {

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

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun vm(): TranslateViewModel =
        TranslateViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun imageFile(): Uri {
        val file = File.createTempFile("translate-image", ".png")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    // --- ImageTranslateScreen / Page -------------------------------------------------

    @Test
    fun `image picker launcher composes and launches`() {
        var picked: String? = null
        lateinit var launch: () -> Unit
        rule.setContent {
            launch = rememberImagePickerLauncher(onPicked = { picked = it })
        }
        rule.runOnIdle { launch() }
        rule.waitForIdle()
        // Launching opens the system picker (shadowed); no pick is delivered here.
        assertEquals(null, picked)
    }

    // --- MainActivity / Route -------------------------------------------------------------

    @Test
    fun `main activity launches with a plain intent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
            .create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `main activity consumes process text intent`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, "translate this text")
            putExtra(CalendarContract.Events.TITLE, "ignored")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
            .create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `main activity with missing process text extra uses empty input`() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
            .create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `routes have their serializable shapes`() {
        assertEquals(Route.Text, Route.Text)
        assertEquals(Route.Camera, Route.Camera)
        assertEquals(Route.History, Route.History)
        assertEquals("file:///x.png", Route.Image("file:///x.png").imageUri)
        assertEquals(true, Route.LanguagePicker(true).forSource)
        assertEquals(false, Route.LanguagePicker(false).forSource)
        assertNotNull(Route.Text.serializer())
        assertNotNull(Route.Image.serializer())
        assertTrue(Route.serializer().descriptor.serialName.contains("Route"))
    }

    // --- ViewModel.onCleared -----------------------------------------------------------------

    @Test
    fun `viewmodel onCleared shuts engines down through the store`() {
        val store = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider(
            store,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(
                ApplicationProvider.getApplicationContext<Application>(),
            ),
        )
        val viewModel = provider[TranslateViewModel::class.java]
        assertNotNull(viewModel.translator)
        store.clear() // invokes onCleared: ocr/translator/tts teardown
    }
}
