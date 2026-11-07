package com.vayunmathur.openassistant.util

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.platform.FakeAndroidKeyStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.robolectric.Shadows

/**
 * AssistantViewModel service dispatch + recording-state lifecycle under
 * Robolectric (the repository is the real singleton; its SQLCipher database
 * is never opened on these paths).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class AssistantViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var vm: AssistantViewModel

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        vm = AssistantViewModel(app, OpenAssistantRepository.get(app))
        // Drain the init pre-warm startService call.
        Shadows.shadowOf(app).clearStartedServices()
    }

    private fun nextService(): Intent = Shadows.shadowOf(app).nextStartedService

    @Test fun requestInferenceDispatchesExtras() {
        vm.requestInference(42L, "hello there", listOf("/a.png", "/b.png"), "/audio.wav")
        val intent = nextService()
        assertEquals(InferenceService::class.java.name, intent.component?.className)
        assertEquals(42L, intent.getLongExtra("conversation_id", -1L))
        assertEquals("hello there", intent.getStringExtra("user_text"))
        assertEquals(
            listOf("/a.png", "/b.png"),
            intent.getStringArrayExtra("image_paths")?.toList())
        assertEquals("/audio.wav", intent.getStringExtra("audio_path"))
    }

    @Test fun screenContextSeedsOnlyOneTurn() {
        vm.setScreenContext("  Notes app showing Groceries  ")
        vm.requestInference(1L, "summarize", emptyList(), null)
        val first = nextService()
        assertTrue(first.getStringExtra("user_text")!!.startsWith("Current screen contents:"))
        assertTrue(first.getStringExtra("user_text")!!.endsWith("User request: summarize"))
        // Context is consumed: the next turn sends the raw text.
        vm.requestInference(1L, "again", emptyList(), null)
        assertEquals("again", nextService().getStringExtra("user_text"))
        // Blank context is ignored entirely.
        vm.setScreenContext("   ")
        vm.requestInference(1L, "third", emptyList(), null)
        assertEquals("third", nextService().getStringExtra("user_text"))
        vm.setScreenContext(null)
        vm.requestInference(1L, "fourth", emptyList(), null)
        assertEquals("fourth", nextService().getStringExtra("user_text"))
    }

    @Test fun recordingLifecycle() = runTest {
        assertFalse(vm.isRecording.value)
        assertNull(vm.recordedAudioPath.value)
        vm.startRecording()
        // Robolectric's AudioRecord shadow lets the recorder start; a second
        // start while recording is refused.
        assertTrue(vm.isRecording.value)
        val path = vm.recordedAudioPath.value
        assertTrue(path!!.endsWith(".wav"), path)
        vm.startRecording()
        assertEquals(path, vm.recordedAudioPath.value)
        vm.stopRecording()
        assertFalse(vm.isRecording.value)
        // The pending path survives stop (it is the send attachment).
        assertEquals(path, vm.recordedAudioPath.value)
        vm.consumeRecordedAudio()
        assertNull(vm.recordedAudioPath.value)
        vm.cancelRecording()
        assertNull(vm.awaitRecordedAudio())
    }

    @Test fun factoryCreatesViewModelAndRejectsOthers() {
        val factory = AssistantViewModelFactory(app, OpenAssistantRepository.get(app))
        val created = factory.create(AssistantViewModel::class.java)
        assertEquals(vm.javaClass, created.javaClass)
        assertFailsWith<IllegalArgumentException> {
            factory.create(OtherViewModel::class.java)
        }
    }

    class OtherViewModel : androidx.lifecycle.ViewModel()
}
