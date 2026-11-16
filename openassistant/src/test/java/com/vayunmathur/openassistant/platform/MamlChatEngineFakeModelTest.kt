package com.vayunmathur.openassistant.platform

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.library.ml.Sampling
import com.vayunmathur.openassistant.util.InferenceServiceFakeEngineTest
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MamlChatEngineFakeModelTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    class FakeTextModel(
        var positions: Int = 10,
        var script: (continuation: String, onToken: (String) -> Boolean) -> String? = { _, onToken ->
            onToken("plain answer"); "plain answer"
        },
    ) : MamlChatEngine.TextModel {
        override val available: Boolean = true
        var prefixLoads = 0
        var generateCalls = 0
        var lastTools: List<Gemma4Handle.ToolDeclaration> = emptyList()
        var lastConversation: List<Gemma4Handle.Turn> = emptyList()
        override fun loadPrefix(prefix: String): Boolean { prefixLoads++; return true }
        override fun positionsFor(
            conversation: List<Gemma4Handle.Turn>, system: String?,
            tools: List<Gemma4Handle.ToolDeclaration>, limit: Int,
        ): Int = positions
        override fun generate(
            conversation: List<Gemma4Handle.Turn>, system: String?,
            tools: List<Gemma4Handle.ToolDeclaration>, limit: Int,
            continuation: String, sampling: Sampling, onToken: (String) -> Boolean,
        ): String? {
            generateCalls++; lastTools = tools; lastConversation = conversation
            return script(continuation, onToken)
        }
        override fun close() {}
    }

    private fun engine(model: FakeTextModel): MamlChatEngine {
        val e = MamlChatEngine(File(ctx.cacheDir, "fake-model"))
        e.textModelOpener = { model to null }
        return e
    }
    private fun tools(): AssistantTools =
        AssistantTools(ctx, InferenceServiceFakeEngineTest.FakeMemoryDao())

    @Test fun loadAndPlainChatReply() = runBlocking {
        val fake = FakeTextModel()
        val e = engine(fake)
        assertTrue(e.load()); assertNull(e.loadError())
        val streamed = mutableListOf<String>()
        val result = e.chat(ChatRequest("sys", listOf(HistoryTurn(true, "old q")), "hello"), tools()) { text ->
            streamed += text; true
        }
        assertEquals(ChatResult.Reply("plain answer", halted = false), result)
        assertTrue(streamed.isNotEmpty())
        assertEquals("hello", fake.lastConversation.last().text)
        assertTrue(fake.lastTools.isNotEmpty(), "tool declarations should reach the model")
        e.close()
    }

    @Test fun toolCallRoundTripsThroughLoop() = runBlocking {
        var calls = 0
        val fake = FakeTextModel(script = { continuation, onToken ->
            calls++
            if (calls == 1) {
                "<|tool_call>call:get_memories{}<tool_call|>"
            } else {
                assertTrue(continuation.contains("get_memories") || continuation.isNotEmpty())
                onToken("you have no memories"); "you have no memories"
            }
        })
        val e = engine(fake)
        val result = e.chat(ChatRequest("sys", emptyList(), "what do you know?"), tools()) { true }
        assertEquals(ChatResult.Reply("you have no memories", halted = false), result)
        assertEquals(2, calls)
        e.close()
    }

    @Test fun renderUiCallAttachesA2uiFenceToReply() = runBlocking {
        var calls = 0
        val uiJson = """{"components":[{"id":"root","component":"Text","text":"Pick one"}]}"""
        val quoted = "<|\"|>" + uiJson + "<|\"|>"
        val fake = FakeTextModel(script = { _, onToken ->
            calls++
            if (calls == 1) {
                "<|tool_call>call:render_ui{components_json:$quoted}<tool_call|>"
            } else {
                onToken("here you go"); "here you go"
            }
        })
        val e = engine(fake)
        val toolSet = tools()
        val result = e.chat(ChatRequest("sys", emptyList(), "show a card"), toolSet) { true }
        assertTrue(result is ChatResult.Reply, "$result")
        val text = (result as ChatResult.Reply).text
        assertTrue(text.contains("here you go"), text)
        assertTrue(text.contains("```a2ui"), text)
        assertTrue(text.contains("Pick one"), text)
        assertEquals(2, calls)
        // The stash was consumed by the engine, exactly once.
        assertEquals(null, toolSet.pendingRenderUi)
        e.close()
    }

    @Test fun sendMessageScriptGatesAndCancelNeverExecutes() = runBlocking {
        var calls = 0
        val quoted = { s: String -> "<|\"|>" + s + "<|\"|>" }
        val fake = FakeTextModel(script = { _, onToken ->
            calls++
            when (calls) {
                1 -> "<|tool_call>call:draft_message{address:${quoted("+15551234")},line:${quoted("Sim")},body:${quoted("Running late")}}<tool_call|>"
                2 -> "<|tool_call>call:send_message_v2{address:${quoted("+15551234")},line:${quoted("Sim")},body:${quoted("Running late")}}<tool_call|>"
                else -> { onToken("ok"); "ok" }
            }
        })
        val e = engine(fake)
        val toolSet = tools()
        val result = e.chat(ChatRequest("sys", emptyList(), "text Ann I'm late"), toolSet) { true }
        // The write gated: the loop stopped at the confirmation, nothing sent.
        assertTrue(result is ChatResult.Pending, "$result")
        val pendingId = (result as ChatResult.Pending).pendingId
        assertEquals(2, calls)
        // Cancel path (host removes the pending call): a later confirm attempt
        // finds nothing and the handler never runs.
        toolSet.removePending(pendingId)
        assertEquals("Error: pending tool call not found", toolSet.invokeConfirmed(pendingId))
        assertEquals(
            "Error: confirmation required for send_message_v2",
            toolSet.invoke(
                com.vayunmathur.openassistant.domain.ParsedToolCall(
                    "send_message_v2",
                    mapOf("address" to "+15551234", "line" to "Sim", "body" to "Running late"),
                ),
            ),
        )
        e.close()
    }

    @Test fun haltedStreamAndTooLongAndUnavailable() = runBlocking {
        val fake = FakeTextModel()
        val e = engine(fake)
        val halted = e.chat(ChatRequest("sys", emptyList(), "hi"), tools()) { false }
        assertTrue(halted is ChatResult.Reply && halted.halted)

        fake.positions = Int.MAX_VALUE
        val tooLong = e.chat(ChatRequest("sys", listOf(HistoryTurn(true, "q")), "hi"), tools()) { true }
        assertEquals(ChatResult.TooLong, tooLong)
        e.close()

        val unavailable = MamlChatEngine(File(ctx.cacheDir, "nope"))
        unavailable.textModelOpener = { null to "fake load error" }
        assertTrue(!unavailable.load())
        assertEquals("fake load error", unavailable.loadError())
        assertEquals(ChatResult.Unavailable, unavailable.chat(ChatRequest("s", emptyList(), "hi"), tools()) { true })
        assertNull(unavailable.extract("s", "u", emptyList()) { true })
        unavailable.close()
    }

    @Test fun extractReturnsGeneratedText() = runBlocking {
        val fake = FakeTextModel(script = { _, onToken -> onToken("{\"a\":1}"); "{\"a\":1}" })
        val e = engine(fake)
        assertEquals("{\"a\":1}", e.extract("sys", "extract this", emptyList()) { true })
        e.close()
    }

    @Test fun prefixIsSeededOncePerSystemPrompt() = runBlocking {
        val fake = FakeTextModel()
        val e = engine(fake)
        e.chat(ChatRequest("same system", emptyList(), "one"), tools()) { true }
        e.chat(ChatRequest("same system", emptyList(), "two"), tools()) { true }
        assertEquals(1, fake.prefixLoads)
        e.chat(ChatRequest("other system", emptyList(), "three"), tools()) { true }
        assertEquals(2, fake.prefixLoads)
        assertNotNull(fake.lastConversation)
        e.close()
    }

    @Test fun mediaTurnsDegradeWithoutTowers() = runBlocking {
        // Real PNG on disk; the vision tower cannot load on the JVM, so the
        // turn must reach the model text-only rather than failing.
        val image = java.awt.image.BufferedImage(6, 6, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val png = File(ctx.cacheDir, "chat.png"); javax.imageio.ImageIO.write(image, "png", png)
        val fake = FakeTextModel()
        val e = engine(fake)
        val result = e.chat(
            ChatRequest("sys", emptyList(), "look at this", imagePaths = listOf(png.absolutePath)), tools()) { true }
        assertEquals(ChatResult.Reply("plain answer", halted = false), result)
        assertTrue(fake.lastConversation.last().images.isEmpty())

        // Valid WAV: decoded, then dropped because the audio tower is absent.
        val wav = File(ctx.cacheDir, "clip.wav")
        val samples = ShortArray(1600)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(36 + samples.size * 2)
        header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray()); header.putInt(16)
        header.putShort(1.toShort()); header.putShort(1.toShort()); header.putInt(16000)
        header.putInt(32000); header.putShort(2.toShort()); header.putShort(16.toShort())
        header.put("data".toByteArray()); header.putInt(samples.size * 2)
        val pcm = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort(s)
        wav.writeBytes(header.array() + pcm.array())
        val result2 = e.chat(
            ChatRequest("sys", emptyList(), "listen", audioPath = wav.absolutePath), tools()) { true }
        assertEquals(ChatResult.Reply("plain answer", halted = false), result2)
        assertTrue(fake.lastConversation.last().audio.isEmpty())

        // Non-WAV audio and a missing file are dropped the same way.
        val notWav = File(ctx.cacheDir, "note.txt").apply { writeText("hello") }
        assertEquals(ChatResult.Reply("plain answer", halted = false),
            e.chat(ChatRequest("sys", emptyList(), "listen", audioPath = notWav.absolutePath), tools()) { true })
        assertEquals(ChatResult.Reply("plain answer", halted = false),
            e.chat(ChatRequest("sys", emptyList(), "listen", audioPath = File(ctx.cacheDir, "gone.wav").absolutePath), tools()) { true })
        e.close()
    }

    @Test fun historyIsTrimmedWhenItCannotFit() = runBlocking {
        val fake = FakeTextModel()
        fake.script = { _, onToken -> onToken("ok"); "ok" }
        // Positions grow with conversation length, so only a prefix fits.
        val e = MamlChatEngine(File(ctx.cacheDir, "fake-model"))
        val sizing = object : MamlChatEngine.TextModel by fake {
            override fun positionsFor(
                conversation: List<Gemma4Handle.Turn>, system: String?,
                tools: List<Gemma4Handle.ToolDeclaration>, limit: Int,
            ): Int = conversation.size * 2000
        }
        e.textModelOpener = { sizing to null }
        val history = (1..20).map { HistoryTurn(it % 2 == 0, "turn $it") }
        val result = e.chat(ChatRequest("sys", history, "now"), tools()) { true }
        assertEquals(ChatResult.Reply("ok", halted = false), result)
        assertTrue(fake.lastConversation.size < history.size + 1,
            "expected trimming, got ${fake.lastConversation.size} of ${history.size + 1}")
        e.close()
    }
}
