package com.vayunmathur.openassistant.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.platform.FakeAndroidKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AssistantViewModelFakeDataTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val conv = Conversation("Fake", timestamp = 5L, id = 3L)
    private val mem = Memory("fake memory", id = 4L)
    private val deletedConvs = mutableListOf<Conversation>()
    private val deletedMems = mutableListOf<Memory>()
    private lateinit var vm: AssistantViewModel

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        deletedConvs.clear(); deletedMems.clear()
        vm = AssistantViewModel(
            app, OpenAssistantRepository.get(app),
            conversationsSource = flowOf(listOf(conv)),
            memoriesSource = flowOf(listOf(mem)),
            messagesSource = { id -> flowOf(listOf(Message(id, "m$id", "user", timestamp = 1L, id = 9L))) },
            conversationSource = { id -> flowOf(conv.takeIf { it.id == id }) },
            conversationUpserter = { 77L },
            messageUpserter = { 88L },
            conversationDeleter = { deletedConvs += it },
            memoryDeleter = { deletedMems += it },
        )
        Shadows.shadowOf(app).clearStartedServices()
    }

    @Test fun fakeFlowsAndMutationsRouteToSeams() = runTest {
        val convCollector = launch { vm.conversations.collect {} }
        val memCollector = launch { vm.memories.collect {} }
        kotlinx.coroutines.delay(50)
        assertEquals(listOf(conv), vm.conversations.value)
        assertEquals(listOf(mem), vm.memories.value)
        convCollector.cancel(); memCollector.cancel()
        assertEquals(listOf("m3"), vm.messagesFor(3L).first().map { it.text })
        assertEquals(77L, vm.upsertConversation(Conversation("new")))
        assertEquals(88L, vm.upsertMessage(Message(3L, "hi", "user")))
        vm.deleteConversation(conv); vm.deleteMemory(mem)
        assertEquals(listOf(conv), deletedConvs); assertEquals(listOf(mem), deletedMems)
    }

    @Test fun recordingStateHelpersWithoutRecorder() = runTest {
        assertNull(vm.awaitRecordedAudio())
        vm.stopRecording()
        vm.cancelRecording()
        assertNull(vm.recordedAudioPath.value)
        assertTrue(!vm.isRecording.value)
        vm.consumeRecordedAudio()
        vm.setScreenContext("screen text")
        vm.requestInference(1L, "q", emptyList(), null)
        val intent = Shadows.shadowOf(app).nextStartedService
        assertTrue(intent.getStringExtra("user_text")!!.contains("screen text"))
    }

    @Test fun legacyModelCleanupDeletesOnlyStaleFiles() {
        val ctx = app
        val external = ctx.getExternalFilesDir(null)!!
        val dir = java.io.File(external, com.vayunmathur.library.downloadservice.ModelUrls.GemmaMaml.DIR)
        dir.mkdirs()
        for (item in com.vayunmathur.library.downloadservice.ModelUrls.GemmaMaml.FILES) {
            java.io.File(external, item.fileName).apply { parentFile?.mkdirs(); createNewFile() }
        }
        val legacy = java.io.File(external, "gemma4_legacy.maml").apply { createNewFile() }
        val siglip = java.io.File(external, "siglip2_vision_model_fp16.onnx").apply { createNewFile() }
        val keep = java.io.File(external, "unrelated.txt").apply { createNewFile() }
        AssistantViewModel(app, OpenAssistantRepository.get(app))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && (legacy.exists() || siglip.exists())) Thread.sleep(25)
        assertTrue(!legacy.exists(), "legacy gemma file should be deleted")
        assertTrue(!siglip.exists(), "siglip file should be deleted")
        assertTrue(keep.exists(), "unrelated files must be kept")
        assertTrue(java.io.File(external, com.vayunmathur.library.downloadservice.ModelUrls.GemmaMaml.FILES.first().fileName).exists())
    }
}
