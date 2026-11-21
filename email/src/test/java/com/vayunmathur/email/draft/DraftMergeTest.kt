package com.vayunmathur.email.draft

import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.EmailOutboxDao
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.platform.EmailDraftActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins draft insert-vs-update merging through a fake DAO.
 *
 * The composer calls save on every keystroke pause with either no id (a fresh
 * draft) or the id it was given back (an edit). Reporting the wrong id would
 * fork one draft into many rows; overwriting the wrong row would lose text.
 * The scope is unconfined so each save completes before the assertion runs.
 */
class DraftMergeTest {

    private class FakeOutboxDao : EmailOutboxDao {
        val drafts = mutableMapOf<Long, DraftEntry>()
        var nextId = 1L

        override fun getOutboxFlow(): Flow<List<OutboxEntry>> = flowOf(emptyList())
        override suspend fun getOutbox(): List<OutboxEntry> = emptyList()
        override suspend fun getOutboxCount(): Int = 0
        override suspend fun insertOutboxEntry(entry: OutboxEntry): Long = 0
        override suspend fun deleteOutboxEntry(entry: OutboxEntry) {}
        override suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long) {}
        override suspend fun updateOutboxMessageId(id: Long, messageId: String?) {}

        override fun getDraftsFlow(): Flow<List<DraftEntry>> = flowOf(drafts.values.toList())
        override suspend fun getDraft(id: Long): DraftEntry? = drafts[id]

        override suspend fun insertDraft(draft: DraftEntry): Long {
            val rowId = if (draft.id == 0L) nextId++ else draft.id
            drafts[rowId] = draft.copy(id = rowId)
            return rowId
        }

        override suspend fun deleteDraftById(id: Long) {
            drafts.remove(id)
        }
    }

    private val dao = FakeOutboxDao()
    private val actions = EmailDraftActions(CoroutineScope(Dispatchers.Unconfined), dao)

    private fun save(
        id: Long?,
        to: String = "a@b",
        subject: String = "s",
        body: String = "b",
    ): Long {
        var reported = -1L
        actions.save(id, "me@x", to, "", "", subject, body) { reported = it }
        return reported
    }

    @Test
    fun `a draft without an id reports the inserted row id`() {
        assertEquals(1L, save(null))
        assertEquals(1, dao.drafts.size)
    }

    @Test
    fun `resaving with the same id keeps that id and overwrites content`() {
        val id = save(null, subject = "first")
        assertEquals(id, save(id, subject = "second"))
        assertEquals(1, dao.drafts.size)
        assertEquals("second", dao.drafts[id]?.subject)
    }

    @Test
    fun `id zero is treated as a new draft`() {
        assertEquals(1L, save(0L))
        assertEquals(2L, save(0L))
        assertEquals(2, dao.drafts.size)
    }

    @Test
    fun `saved drafts preserve every composer field`() {
        val id = save(null, to = "to@x", subject = "hi", body = "hello")
        val stored = dao.drafts[id]
        assertEquals("me@x", stored?.accountEmail)
        assertEquals("to@x", stored?.to)
        assertEquals("hi", stored?.subject)
        assertEquals("hello", stored?.body)
    }

    @Test
    fun `delete removes the draft so loading returns null`() {
        val id = save(null)
        actions.delete(id)
        assertNull(dao.drafts[id])
    }

    @Test
    fun `loading an unknown id returns null`() {
        assertNull(dao.drafts[999L])
    }
}
