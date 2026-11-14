package com.vayunmathur.openassistant.util

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.library.ml.GEMMA4_EXPECTED_BYTES
import com.vayunmathur.openassistant.platform.AssistantTools
import com.vayunmathur.openassistant.platform.ChatRequest
import com.vayunmathur.openassistant.platform.ChatResult
import com.vayunmathur.openassistant.platform.MamlChatEngine
import com.vayunmathur.openassistant.util.InferenceServiceFakeEngineTest.FakeConversationDao
import com.vayunmathur.openassistant.util.InferenceServiceFakeEngineTest.FakeMemoryDao
import com.vayunmathur.openassistant.util.InferenceServiceFakeEngineTest.FakeMessageDao
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The load error must say whether the model is missing or present-but-refused (#917).
 *
 * The reporter's files were all in `gemma4/`, downloaded and SHA-verified, but the message
 * read "Model missing or failed to load", so a GPU refusal looked like a missing download.
 * Files here are sparse, so full-size ones cost no disk.
 */
@RunWith(RobolectricTestRunner::class)
class InferenceServiceModelLoadTest {

    /** An engine that never comes up, reporting [error] the way MamlChatEngine does. */
    private class RefusingEngine(private val error: String?) : MamlChatEngine(File("/nonexistent-model")) {
        override suspend fun load(): Boolean = false
        override suspend fun loadError(): String? = error
        override suspend fun chat(
            request: ChatRequest,
            tools: AssistantTools,
            onText: (String) -> Boolean,
        ): ChatResult = ChatResult.Unavailable
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val modelDir: File get() = ModelUrls.GemmaMaml.modelDir(ctx)
    private val messageDao = FakeMessageDao()
    private var controller: ServiceController<InferenceService>? = null

    @BeforeTest fun cleanDir() {
        modelDir.deleteRecursively()
        modelDir.mkdirs()
    }

    @AfterTest fun tearDown() {
        runCatching { controller?.destroy() }
        modelDir.deleteRecursively()
    }

    private fun writeModel(sizes: Map<String, Long> = GEMMA4_EXPECTED_BYTES) {
        for ((name, bytes) in sizes) {
            RandomAccessFile(File(modelDir, name), "rw").use { it.setLength(bytes) }
        }
    }

    /** Runs one chat turn against engines that refuse with [reason]; returns the stored error, path masked. */
    private fun errorFor(reason: String?): String {
        val c = Robolectric.buildService(InferenceService::class.java)
        controller = c
        val service = c.get()
        service.messageDaoOverride = messageDao
        service.conversationDaoOverride = FakeConversationDao()
        service.memoryDaoOverride = FakeMemoryDao()
        service.engineFactory = { RefusingEngine(reason) }
        c.create()
        service.onStartCommand(
            Intent(ctx, InferenceService::class.java).apply {
                putExtra("conversation_id", 1L)
                putExtra("user_text", "hello")
            },
            0,
            1,
        )
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            // The path is masked: Robolectric names its temp dir after the test method, so a
            // test called "...Missing" would otherwise find "missing" in every message.
            messageDao.assistantTexts(1L).firstOrNull()?.let { return it.replace(modelDir.absolutePath, "<dir>") }
            Thread.sleep(25)
        }
        error("no error message was stored")
    }

    @Test fun presentFilesRefusedByTheDeviceDoNotReadAsMissing() {
        writeModel()
        val reason = "1101558320 bytes of weights against a maxMemoryAllocationSize of 1073741824"
        val text = errorFor(reason)
        assertFalse(text.contains("missing", ignoreCase = true), text)
        assertTrue(text.contains("present") && text.contains("failed to load"), text)
        assertTrue(text.contains(reason) && text.contains("<dir>"), text)
    }

    @Test fun presentFilesWithNoReasonStillSayFailedToLoad() {
        writeModel()
        val text = errorFor(null)
        assertFalse(text.contains("missing", ignoreCase = true), text)
        assertTrue(text.contains("failed to load") && text.contains("no reason"), text)
    }

    @Test fun anAbsentRequiredFileIsNamedAsMissing() {
        writeModel(GEMMA4_EXPECTED_BYTES - "gemma4_text.maml")
        val text = errorFor(null)
        assertTrue(text.contains("missing") && text.contains("gemma4_text.maml"), text)
        assertFalse(text.contains("gemma4_embed.maml"), text)
    }

    @Test fun aShortFileIsNamedWithItsSizes() {
        writeModel(GEMMA4_EXPECTED_BYTES + ("gemma4_embed.maml" to 10L))
        val text = errorFor("short files: gemma4_embed.maml 10/1566576256")
        assertTrue(text.contains("incomplete") && text.contains("gemma4_embed.maml 10/1566576256"), text)
        assertFalse(text.contains("missing", ignoreCase = true), text)
    }
}
