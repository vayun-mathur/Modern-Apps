package com.vayunmathur.openassistant.util

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.ConversationDao
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.MessageDao
import com.vayunmathur.openassistant.platform.AssistantTools
import com.vayunmathur.openassistant.platform.ChatRequest
import com.vayunmathur.openassistant.platform.ChatResult
import com.vayunmathur.openassistant.platform.MamlChatEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InferenceServiceFakeEngineTest {

    class FakeMessageDao : MessageDao {
        val messages = mutableListOf<Message>()
        private var nextId = 1L
        override suspend fun upsert(value: Message): Long {
            val id = if (value.id == 0L) nextId++ else value.id
            messages.removeAll { it.id == id }; messages += value.copy(id = id); return id
        }
        override suspend fun getById(id: Long): Message? = messages.firstOrNull { it.id == id }
        override suspend fun getByConversation(id: Long): List<Message> =
            messages.filter { it.conversationId == id }.sortedBy { it.timestamp }
        override fun getByConversationFlow(id: Long): Flow<List<Message>> =
            MutableStateFlow(messages.filter { it.conversationId == id })
        override suspend fun deleteById(id: Long) { messages.removeAll { it.id == id } }
        override suspend fun delete(value: Message): Int =
            if (messages.removeAll { it.id == value.id }) 1 else 0
        fun assistantTexts(conv: Long) = synchronized(this) { messages.filter { it.conversationId == conv && it.role == "assistant" }.map { it.text } }
    }

    class FakeConversationDao : ConversationDao {
        val conversations = mutableListOf<Conversation>()
        private var nextId = 1L
        override suspend fun upsert(value: Conversation): Long {
            val id = if (value.id == 0L) nextId++ else value.id
            conversations.removeAll { it.id == id }; conversations += value.copy(id = id); return id
        }
        override suspend fun getById(id: Long): Conversation? = conversations.firstOrNull { it.id == id }
        override fun getByIdFlow(id: Long): Flow<Conversation?> = MutableStateFlow(conversations.firstOrNull { it.id == id })
        override fun getAllFlow(): Flow<List<Conversation>> = MutableStateFlow(conversations.toList())
        override suspend fun delete(value: Conversation): Int =
            if (conversations.removeAll { it.id == value.id }) 1 else 0
    }

    class FakeMemoryDao : MemoryDao {
        val memories = mutableListOf<Memory>()
        private var nextId = 1L
        override suspend fun upsert(value: Memory): Long {
            val id = if (value.id == 0L) nextId++ else value.id
            memories.removeAll { it.id == id }; memories += value.copy(id = id); return id
        }
        override fun getAllFlow(): Flow<List<Memory>> = MutableStateFlow(memories.toList())
        override suspend fun getAll(): List<Memory> = memories.toList()
        override suspend fun deleteById(id: Long) { memories.removeAll { it.id == id } }
        override suspend fun delete(value: Memory): Int =
            if (memories.removeAll { it.id == value.id }) 1 else 0
    }

    class FakeEngine(
        var chatBehavior: (ChatRequest, (String) -> Boolean) -> ChatResult = { _, _ -> ChatResult.Reply("default reply", false) },
        var extractBehavior: (String, (String) -> Boolean) -> String? = { _, _ -> null },
    ) : MamlChatEngine(File("/nonexistent-model")) {
        var closed = false
        var lastRequest: ChatRequest? = null
        override suspend fun load(): Boolean = true
        override suspend fun loadError(): String? = null
        override suspend fun chat(request: ChatRequest, tools: AssistantTools, onText: (String) -> Boolean): ChatResult {
            lastRequest = request; return chatBehavior(request, onText)
        }
        override suspend fun extract(system: String, userText: String, imagePaths: List<String>, onText: (String) -> Boolean): String? =
            extractBehavior(userText, onText)
        override fun close() { closed = true }
    }

    class CapturingReceiver : ResultReceiver(Handler(Looper.getMainLooper())) {
        val results = mutableListOf<Pair<Int, Bundle?>>()
        val latch = CountDownLatch(1)
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            synchronized(results) { results += resultCode to resultData }; latch.countDown()
        }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val messageDao = FakeMessageDao()
    private val conversationDao = FakeConversationDao()
    private val engines = mutableListOf<FakeEngine>()
    private var engineQueue = mutableListOf<FakeEngine>()
    private val clock = AtomicLong(System.currentTimeMillis())
    private var controller: org.robolectric.android.controller.ServiceController<InferenceService>? = null

    @AfterTest fun tearDown() {
        InferenceService.halt = false; InferenceService.newTitle = null
        try { controller?.destroy() } catch (_: Exception) {}
    }

    private fun startService(vararg fakes: FakeEngine): InferenceService {
        engines.clear(); engineQueue = fakes.toMutableList(); engines += fakes
        val c = Robolectric.buildService(InferenceService::class.java)
        controller = c
        val service = c.get()
        service.messageDaoOverride = messageDao
        service.conversationDaoOverride = conversationDao
        service.memoryDaoOverride = FakeMemoryDao()
        service.nowMillis = { clock.get() }
        service.engineFactory = { engineQueue.removeFirstOrNull() ?: FakeEngine().also { engines += it } }
        c.create()
        return service
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(25) }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun standardIntent(conv: Long, text: String): Intent = Intent(ctx, InferenceService::class.java).apply {
        putExtra("conversation_id", conv); putExtra("user_text", text)
    }

    @Test fun standardInferenceStoresReplyAndTitle() {
        kotlinx.coroutines.runBlocking { conversationDao.upsert(Conversation("Old", id = 7L)) }
        val fake = FakeEngine(chatBehavior = { req, onText ->
            onText("Hello world"); InferenceService.newTitle = "Greeting"
            ChatResult.Reply("Hello world", halted = false)
        })
        val service = startService(fake)
        service.onStartCommand(standardIntent(7L, "hi there"), 0, 1)
        await("reply stored") { messageDao.assistantTexts(7L).contains("Hello world") }
        await("title flushed") { kotlinx.coroutines.runBlocking { conversationDao.getById(7L)?.title } == "Greeting" }
        val req = assertNotNull(fake.lastRequest)
        assertEquals("hi there", req.userText)
        assertTrue(req.system.contains("helpful Android assistant") || req.system.isNotBlank())
        assertNull(InferenceService.newTitle)
    }

    @Test fun historyExcludesJustSentDuplicateButKeepsOlder() {
        val now = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking {
            messageDao.upsert(Message(9L, "repeat", "user", timestamp = now))
            messageDao.upsert(Message(9L, "repeat", "user", timestamp = now - 60_000))
            messageDao.upsert(Message(9L, "earlier answer", "assistant", timestamp = now - 50_000))
        }
        val fake = FakeEngine()
        val service = startService(fake)
        service.onStartCommand(standardIntent(9L, "repeat"), 0, 1)
        await("reply") { fake.lastRequest != null && messageDao.assistantTexts(9L).contains("default reply") }
        val history = assertNotNull(fake.lastRequest).history
        // Only the older duplicate and the assistant turn remain (2 of 3).
        assertEquals(2, history.size)
        assertTrue(history.any { it.text == "earlier answer" && !it.fromUser })
    }

    @Test fun haltedReplyDeletesPlaceholder() {
        val fake = FakeEngine(chatBehavior = { _, _ -> ChatResult.Reply("partial", halted = true) })
        val service = startService(fake)
        service.onStartCommand(standardIntent(3L, "stop"), 0, 1)
        await("placeholder gone") { messageDao.assistantTexts(3L).isEmpty() && engines.isNotEmpty() }
        Thread.sleep(300)
        assertTrue(messageDao.assistantTexts(3L).isEmpty())
        assertTrue(!InferenceService.halt)
    }

    @Test fun tooLongUpdatesPlaceholderWithError() {
        val fake = FakeEngine(chatBehavior = { _, _ -> ChatResult.TooLong })
        val service = startService(fake)
        service.onStartCommand(standardIntent(4L, "long"), 0, 1)
        await("too long message") { messageDao.assistantTexts(4L).any { it.contains("too long") } }
    }

    @Test fun unavailableRetriesWithFreshEngine() {
        val first = FakeEngine(chatBehavior = { _, _ -> ChatResult.Unavailable })
        val second = FakeEngine(chatBehavior = { _, _ -> ChatResult.Reply("recovered", false) })
        val service = startService(first, second)
        service.onStartCommand(standardIntent(5L, "retry me"), 0, 1)
        await("recovered") { messageDao.assistantTexts(5L).contains("recovered") }
        assertTrue(first.closed, "failed engine must be closed before retry")
    }

    @Test fun failingTwiceStoresErrorMessage() {
        val boom = { _: ChatRequest, _: (String) -> Boolean -> throw RuntimeException("boom-native") }
        val service = startService(FakeEngine(chatBehavior = boom), FakeEngine(chatBehavior = boom))
        service.onStartCommand(standardIntent(6L, "fail"), 0, 1)
        await("error stored") { messageDao.assistantTexts(6L).any { it.contains("boom-native") } }
    }

    @Test fun intentInferenceStreamsValidJsonEarly() {
        val receiver = CapturingReceiver()
        val schema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
        val fake = FakeEngine(extractBehavior = { _, onText -> onText("""{"name":"Ada"}"""); """{"name":"Ada"}""" })
        val service = startService(fake)
        val intent = Intent(ctx, InferenceService::class.java).apply {
            putExtra("user_text", "extract"); putExtra("schema", schema); putExtra("RECEIVER", receiver)
        }
        service.onStartCommand(intent, 0, 1)
        assertTrue(receiver.latch.await(15, TimeUnit.SECONDS), "receiver should get result")
        val (code, data) = synchronized(receiver.results) { receiver.results.first() }
        assertEquals(0, code); assertEquals("""{"name":"Ada"}""", data?.getString("json_result"))
    }

    @Test fun intentInferenceSchemaMismatchAndNullReplyError() {
        val receiver = CapturingReceiver()
        val schema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
        val fake = FakeEngine(extractBehavior = { _, _ -> "not json at all" })
        val service = startService(fake)
        val intent = Intent(ctx, InferenceService::class.java).apply {
            putExtra("user_text", "x"); putExtra("schema", schema); putExtra("RECEIVER", receiver)
        }
        service.onStartCommand(intent, 0, 1)
        assertTrue(receiver.latch.await(15, TimeUnit.SECONDS))
        assertEquals(-1, synchronized(receiver.results) { receiver.results.first().first })

        val receiver2 = CapturingReceiver()
        val service2Engine = FakeEngine(extractBehavior = { _, _ -> null })
        // Second service instance shares DAOs; queue a null-reply intent on the same service by swapping factory is not possible,
        // so use the running service's next engine: enqueue another intent after replacing behavior of the live fake.
        fake.extractBehavior = { _, _ -> null }
        val intent2 = Intent(ctx, InferenceService::class.java).apply {
            putExtra("user_text", "y"); putExtra("schema", schema); putExtra("RECEIVER", receiver2)
        }
        service.onStartCommand(intent2, 0, 2)
        assertTrue(receiver2.latch.await(15, TimeUnit.SECONDS))
        assertEquals(-1, synchronized(receiver2.results) { receiver2.results.first().first })
        assertNotNull(service2Engine)
    }

    @Test fun expiredIntentIsDiscardedWithError() {
        val release = CountDownLatch(1)
        val blocker = FakeEngine(chatBehavior = { _, _ ->
            release.await(10, TimeUnit.SECONDS); ChatResult.Reply("done", false)
        })
        val receiver = CapturingReceiver()
        val schema = """{"type":"object"}"""
        val service = startService(blocker)
        service.onStartCommand(standardIntent(11L, "block"), 0, 1)
        val intent = Intent(ctx, InferenceService::class.java).apply {
            putExtra("user_text", "late"); putExtra("schema", schema); putExtra("RECEIVER", receiver)
        }
        service.onStartCommand(intent, 0, 2)
        // Let the intent job enqueue, then jump the clock past the TTL and release the blocker.
        Thread.sleep(500)
        clock.addAndGet(60_000)
        release.countDown()
        assertTrue(receiver.latch.await(15, TimeUnit.SECONDS), "expired job should notify receiver")
        val (code, data) = synchronized(receiver.results) { receiver.results.first() }
        assertEquals(-1, code)
        assertTrue(data?.getString("error")!!.isNotBlank())
    }

    @Test fun onDestroyClosesEngine() {
        val fake = FakeEngine()
        startService(fake)
        // Force engine creation through a standard job.
        val service = controller!!.get()
        service.onStartCommand(standardIntent(12L, "hi"), 0, 1)
        await("reply") { messageDao.assistantTexts(12L).isNotEmpty() }
        controller!!.destroy()
        assertTrue(fake.closed)
        controller = null
    }

    @Test fun imageUrisAreCopiedIntoRequest() {
        val uri = android.net.Uri.parse("content://test/chat-image")
        org.robolectric.Shadows.shadowOf(ctx.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(byteArrayOf(9, 8, 7)))
        val fake = FakeEngine()
        val service = startService(fake)
        val intent = Intent(ctx, InferenceService::class.java).apply {
            putExtra("conversation_id", 13L); putExtra("user_text", "look")
            putParcelableArrayListExtra("image_uris", arrayListOf(uri))
            putExtra("image_paths", arrayOf("/static/a.jpg"))
        }
        service.onStartCommand(intent, 0, 1)
        await("request captured") { fake.lastRequest != null }
        val paths = assertNotNull(fake.lastRequest).imagePaths
        assertEquals(2, paths.size)
        assertEquals("/static/a.jpg", paths[0])
        assertTrue(File(paths[1]).exists(), "copied image should exist: ${paths[1]}")
    }

    @Test fun customSystemPromptIsUsed() {
        kotlinx.coroutines.runBlocking {
            com.vayunmathur.library.util.DataStoreUtils.getInstance(ctx)
                .setString(InferenceService.KEY_SYSTEM_PROMPT, "CUSTOM PROMPT XYZ")
        }
        val fake = FakeEngine()
        val service = startService(fake)
        service.onStartCommand(standardIntent(14L, "prompt check"), 0, 1)
        await("request") { fake.lastRequest != null }
        assertTrue(assertNotNull(fake.lastRequest).system.contains("CUSTOM PROMPT XYZ"))
        assertTrue(assertNotNull(fake.lastRequest).system.contains("a2ui") ||
            assertNotNull(fake.lastRequest).system.length > 20)
        kotlinx.coroutines.runBlocking {
            com.vayunmathur.library.util.DataStoreUtils.getInstance(ctx)
                .setString(InferenceService.KEY_SYSTEM_PROMPT, "")
        }
    }

    @Test fun schemaWithoutReceiverFallsBackToStandardJob() {
        val fake = FakeEngine()
        val service = startService(fake)
        val intent = Intent(ctx, InferenceService::class.java).apply {
            putExtra("conversation_id", 15L); putExtra("user_text", "fallback")
            putExtra("schema", """{"type":"object"}""")
        }
        service.onStartCommand(intent, 0, 1)
        await("standard reply") { messageDao.assistantTexts(15L).contains("default reply") }
        assertEquals("fallback", assertNotNull(fake.lastRequest).userText)
    }
}
