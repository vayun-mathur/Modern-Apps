package com.vayunmathur.openassistant.util

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.openassistant.data.AuditRecord
import com.vayunmathur.openassistant.data.AuditRecordDao
import com.vayunmathur.openassistant.data.PendingToolCallDao
import com.vayunmathur.openassistant.data.PendingToolCallEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host markers ([HostSendMsg] / [HostConfirm] / [HostCancel]) through the
 * real InferenceService with a fake engine: the composer-send gate and
 * the confirm/cancel turns the just-landed host-gated write foundation
 * added. Earlier service tests only drove model turns, so these paths
 * (and their pending/audit persistence) never ran.
 */
@RunWith(RobolectricTestRunner::class)
class InferenceServiceHostMarkerTest {

    class FakePendingDao : PendingToolCallDao {
        val rows = linkedMapOf<String, PendingToolCallEntity>()
        override suspend fun getById(id: String): PendingToolCallEntity? = rows[id]
        override suspend fun getByConversation(conversationId: Long): List<PendingToolCallEntity> =
            rows.values.filter { it.conversationId == conversationId }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun upsert(value: PendingToolCallEntity): Long {
            rows[value.id] = value; return 1L
        }
    }

    class FakeAuditDao : AuditRecordDao {
        val records = mutableListOf<AuditRecord>()
        override fun getAllFlow(): Flow<List<AuditRecord>> = MutableStateFlow(records.toList())
        override suspend fun getRecent(limit: Int): List<AuditRecord> = records.take(limit)
        override suspend fun upsert(value: AuditRecord): Long {
            records += value; return records.size.toLong()
        }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val messageDao = InferenceServiceFakeEngineTest.FakeMessageDao()
    private val conversationDao = InferenceServiceFakeEngineTest.FakeConversationDao()
    private val pendingDao = FakePendingDao()
    private val auditDao = FakeAuditDao()
    private val clock = AtomicLong(System.currentTimeMillis())
    private var engines = mutableListOf<InferenceServiceFakeEngineTest.FakeEngine>()
    private var controller: org.robolectric.android.controller.ServiceController<InferenceService>? = null

    @AfterTest fun tearDown() {
        InferenceService.halt = false
        InferenceService.newTitle = null
        runCatching { controller?.destroy() }
    }

    private fun startService(vararg fakes: InferenceServiceFakeEngineTest.FakeEngine): InferenceService {
        engines = fakes.toMutableList()
        val queue = fakes.toMutableList()
        val built = Robolectric.buildService(InferenceService::class.java)
        controller = built
        val service = built.get()
        service.messageDaoOverride = messageDao
        service.conversationDaoOverride = conversationDao
        service.memoryDaoOverride = InferenceServiceFakeEngineTest.FakeMemoryDao()
        service.pendingDaoOverride = pendingDao
        service.auditDaoOverride = auditDao
        service.nowMillis = { clock.get() }
        service.engineFactory = {
            queue.removeFirstOrNull() ?: InferenceServiceFakeEngineTest.FakeEngine().also { engines += it }
        }
        built.create()
        return service
    }

    private fun intent(conv: Long, text: String): Intent = Intent(ctx, InferenceService::class.java).apply {
        putExtra("conversation_id", conv)
        putExtra("user_text", text)
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(25)
        assertTrue(cond(), "timed out: $desc")
    }

    private fun sendMarker(body: String = "running late") =
        """[HostSendMsg {"address":"+15551234","line":"Sim","body":"$body"}]"""

    @Test fun hostSendMarkerCreatesPendingConfirmationCard() {
        val service = startService(InferenceServiceFakeEngineTest.FakeEngine())
        service.onStartCommand(intent(11L, sendMarker()), 0, 1)
        await("confirm card stored") {
            messageDao.assistantTexts(11L).any { it.contains("Please confirm sending this message.") }
        }
        // The pending call is persisted for the gate, naming the send tool.
        await("pending persisted") { pendingDao.rows.values.any { it.toolName == "send_message_v2" } }
        val pending = pendingDao.rows.values.single()
        assertTrue(pending.argsJson.contains("+15551234"), pending.argsJson)
        assertTrue(pending.argsJson.contains("running late"), pending.argsJson)
    }

    @Test fun hostSendMarkerWithMissingFieldStoresRefusalAndNoPending() {
        val service = startService(InferenceServiceFakeEngineTest.FakeEngine())
        service.onStartCommand(
            intent(12L, """[HostSendMsg {"address":"+15551234","line":"Sim"}]"""), 0, 1,
        )
        await("refusal stored") {
            messageDao.assistantTexts(12L).any { it.contains("could not be sent") }
        }
        assertTrue(pendingDao.rows.isEmpty(), "a malformed send must not create a pending call")
    }

    @Test fun hostSendMarkerWithBrokenJsonIsConsumedSilently() {
        val fake = InferenceServiceFakeEngineTest.FakeEngine()
        val service = startService(fake)
        service.onStartCommand(intent(13L, "[HostSendMsg {not json}]"), 0, 1)
        // The marker is consumed (never sent to the model as a user turn)
        // and nothing is stored or pended.
        Thread.sleep(750)
        assertTrue(messageDao.assistantTexts(13L).isEmpty())
        assertTrue(pendingDao.rows.isEmpty())
        assertEquals(null, fake.lastRequest, "a host marker must not start a model turn")
    }

    @Test fun hostConfirmExecutesPendingAndWritesAudit() {
        val engine = InferenceServiceFakeEngineTest.FakeEngine()
        val service = startService(engine)
        service.onStartCommand(intent(14L, sendMarker("confirm me")), 0, 1)
        await("pending persisted") { pendingDao.rows.isNotEmpty() }
        val id = pendingDao.rows.values.single().id

        service.onStartCommand(intent(14L, "[HostConfirm $id]"), 0, 2)
        await("audit written") { auditDao.records.any { it.toolName == "send_message_v2" } }
        await("pending consumed") { pendingDao.rows[id]?.consumed == true }
        // Re-confirming the same (now consumed) call cannot execute it again:
        // the model turn carries the refusal instead of a fresh execution.
        service.onStartCommand(intent(14L, "[HostConfirm $id]"), 0, 3)
        await("second confirm refused") {
            engine.lastRequest?.userText?.contains("could not be completed") == true &&
                engine.lastRequest!!.userText.contains("send_message_v2")
        }
        assertEquals(1, auditDao.records.size, "only the first confirmation may be audited")
        assertNotNull(id)
    }

    @Test fun hostCancelAndUnknownConfirmReachTheModelAsTurns() {
        val fake = InferenceServiceFakeEngineTest.FakeEngine()
        val service = startService(fake)
        service.onStartCommand(intent(15L, "[HostCancel never-existed]"), 0, 1)
        await("cancel turn") { fake.lastRequest?.userText?.startsWith("[Cancelled") == true }
        assertTrue(fake.lastRequest!!.userText.contains("tool"), fake.lastRequest!!.userText)

        service.onStartCommand(intent(15L, "[HostConfirm also-missing]"), 0, 2)
        await("unknown confirm turn") {
            fake.lastRequest?.userText?.contains("could not be completed") == true
        }
        assertTrue(runBlocking { pendingDao.getByConversation(15L) }.isEmpty())
    }
}
