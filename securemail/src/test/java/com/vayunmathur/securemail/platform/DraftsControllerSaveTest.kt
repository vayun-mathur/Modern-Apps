package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.MailFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [DraftsController] save/delete against a fake store (the async IO paths). */
@RunWith(RobolectricTestRunner::class)
class DraftsControllerSaveTest {

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        kotlin.test.assertTrue(condition(), "timed out: $description")
    }

    @Test fun `save persists a draft row that decodes back and delete removes it`() {
        val store = FakeMailStore()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val drafts = DraftsController(store, scope) { "me@maemail.org" }
        val draft = Draft(
            id = "draft-1", to = listOf("ada@example.org"), cc = listOf("c@x.org"),
            subject = "Later", body = "Not yet", inReplyTo = "m9",
        )
        drafts.save(draft)
        await("draft saved") { store.messages.snapshot().any { it.id == "draft-1" } }
        val row = store.messages.snapshot().single()
        assertEquals(MailFolder.DRAFTS, row.folder)
        assertEquals("me@maemail.org", row.from)
        assertTrue(row.isRead)
        assertEquals(draft.copy(from = "me@maemail.org"), DraftsController.decode(row).copy(from = "me@maemail.org"))
        runBlocking { assertEquals(listOf("draft-1"), drafts.drafts.first().map { it.id }) }
        // Re-saving the same id updates in place.
        drafts.save(draft.copy(subject = "Later v2"))
        await("draft updated") { store.messages.snapshot().singleOrNull()?.decryptedSubject == "Later v2" }
        assertEquals(1, store.messages.snapshot().size)
        drafts.delete("draft-1")
        await("draft deleted") { store.messages.snapshot().isEmpty() }
        scope.cancel()
    }

    @Test fun `save with an explicit from keeps it and sent flow stays empty`() {
        val store = FakeMailStore()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val drafts = DraftsController(store, scope) { "me@maemail.org" }
        drafts.save(Draft(id = "draft-2", subject = "A", from = "alias@maemail.org"))
        await("alias draft saved") { store.messages.snapshot().isNotEmpty() }
        assertEquals("alias@maemail.org", store.messages.snapshot().single().from)
        runBlocking {
            assertTrue(drafts.sent.first().isEmpty())
            assertEquals(1, drafts.drafts.first().size)
        }
        scope.cancel()
    }
}
