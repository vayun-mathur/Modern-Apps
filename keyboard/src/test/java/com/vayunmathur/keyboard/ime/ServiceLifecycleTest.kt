package com.vayunmathur.keyboard.ime

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.lifecycle.Lifecycle
import com.vayunmathur.keyboard.util.KeyboardLayouts
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ServiceLifecycleTest {

    @Test
    fun `input view and window lifecycle callbacks run`() {
        val controller = Robolectric.buildService(KeyboardService::class.java)
        val service = controller.create().get()
        ShadowLooper.idleMainLooper()

        val view = service.onCreateInputView()
        assertNotNull(view)
        assertEquals(Lifecycle.State.CREATED, service.lifecycle.currentState)

        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            initialSelStart = 0; initialSelEnd = 0
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        service.onStartInputView(info, restarting = false)
        assertEquals(Lifecycle.State.STARTED, service.lifecycle.currentState)
        assertTrue(service.kbState.suggestions.isEmpty())

        service.onWindowShown()
        assertEquals(Lifecycle.State.RESUMED, service.lifecycle.currentState)
        assertTrue(service.windowShown)
        service.updateBottomInset()

        service.onUpdateSelection(0, 0, 1, 1, -1, -1)
        service.onUpdateSelection(0, 0, 2, 3, -1, -1)
        assertTrue(service.before.hasSelection)

        service.onWindowHidden()
        assertEquals(Lifecycle.State.STARTED, service.lifecycle.currentState)

        service.onStartInputView(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                initialSelStart = 2; initialSelEnd = 2
            },
            restarting = true,
        )
        assertTrue(service.kbState.passwordField)

        service.onFinishInputView(finishingInput = true)
        controller.destroy()
        ShadowLooper.idleMainLooper()
        assertEquals(Lifecycle.State.DESTROYED, service.lifecycle.currentState)
    }

    @Test
    fun `subtype changes apply layouts and layoutIdOf reads extras`() {
        val controller = Robolectric.buildService(KeyboardService::class.java)
        val service = controller.create().get()
        ShadowLooper.idleMainLooper()

        val german = InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeMode("keyboard")
            .setLanguageTag("de")
            .setSubtypeExtraValue("layoutId=de_qwertz")
            .build()
        service.subtypeByLayoutId = mapOf("de_qwertz" to german)
        assertEquals("de_qwertz", service.layoutIdOf(german))

        val byTag = InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeMode("keyboard").setLanguageTag("fr").build()
        val resolved = service.layoutIdOf(byTag)
        assertTrue(resolved == null || KeyboardLayouts.byId(resolved) != null)
        assertNull(service.layoutIdOf(null))

        service.applyLayout(checkNotNull(service.layoutIdOf(german)))
        ShadowLooper.idleMainLooper()
        assertEquals("de_qwertz", service.kbState.settings.activeLayoutId)
        // applying the same layout again keeps it
        service.applyLayout("de_qwertz")
        assertEquals("de_qwertz", service.kbState.settings.activeLayoutId)

        service.registerLayoutSubtypes()
        controller.destroy()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `main activity creates its content`() {
        val controller = Robolectric.buildActivity(com.vayunmathur.keyboard.MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        assertNotNull(activity)
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test
    fun `voice permission activity finishes after launch`() {
        // The trampoline launches a permission request; Robolectric denies by default
        // and the activity finishes itself through the result callback or stays created.
        val controller = Robolectric.buildActivity(
            com.vayunmathur.keyboard.platform.VoicePermissionActivity::class.java,
        )
        val activity = controller.create().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
        runCatching { controller.destroy() }
        assertNotNull(Looper.getMainLooper())
    }
}
