package com.vayunmathur.openassistant.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** AppDatabase DAOs over an in-memory Room database under Robolectric. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class AppDatabaseDaoTest {

    private lateinit var db: AppDatabase

    @BeforeTest fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @AfterTest fun tearDown() { db.close() }

    @Test fun conversationCrudAndOrdering() = runTest {
        val dao = db.conversationDao()
        assertTrue(dao.getAllFlow().first().isEmpty())
        val id1 = dao.upsert(Conversation(title = "First", timestamp = 100L))
        val id2 = dao.upsert(Conversation(title = "Second", timestamp = 200L))
        assertTrue(id1 > 0 && id2 > id1)
        // Newest first.
        assertEquals(listOf("Second", "First"), dao.getAllFlow().first().map { it.title })
        assertEquals("First", dao.getById(id1)?.title)
        assertEquals("First", dao.getByIdFlow(id1).first()?.title)
        assertNull(dao.getById(999_999L))
        // Upsert with an existing id updates in place.
        dao.upsert(Conversation(title = "First v2", timestamp = 300L, id = id1))
        assertEquals("First v2", dao.getById(id1)?.title)
        assertEquals(1, dao.delete(Conversation(title = "First v2", timestamp = 300L, id = id1)))
        assertNull(dao.getById(id1))
    }

    @Test fun messageCrudPerConversation() = runTest {
        val dao = db.messageDao()
        val m1 = dao.upsert(Message(conversationId = 7L, text = "hi", role = "user", timestamp = 2L))
        dao.upsert(Message(conversationId = 7L, text = "hello", role = "assistant", timestamp = 1L))
        dao.upsert(Message(conversationId = 8L, text = "other", role = "user", timestamp = 3L))
        // Chronological within a conversation.
        assertEquals(listOf("hello", "hi"), dao.getByConversation(7L).map { it.text })
        assertEquals(listOf("hello", "hi"), dao.getByConversationFlow(7L).first().map { it.text })
        val stored = dao.getById(m1)
        assertNotNull(stored)
        assertEquals(emptyList(), stored.imagePaths)
        assertNull(stored.a2ui)
        dao.upsert(stored.copy(text = "hi again", a2ui = "{}", imagePaths = listOf("/tmp/a.png"), hasAudio = true))
        val updated = dao.getById(m1)!!
        assertEquals("hi again", updated.text)
        assertEquals("{}", updated.a2ui)
        assertEquals(listOf("/tmp/a.png"), updated.imagePaths)
        assertTrue(updated.hasAudio)
        dao.deleteById(m1)
        assertNull(dao.getById(m1))
        assertEquals(1, dao.delete(dao.getByConversation(8L).single()))
        assertTrue(dao.getByConversation(8L).isEmpty())
    }

    @Test fun memoryCrud() = runTest {
        val dao = db.memoryDao()
        assertTrue(dao.getAll().isEmpty())
        val id = dao.upsert(Memory(content = "likes tea"))
        dao.upsert(Memory(content = "lives in Lisbon"))
        assertEquals(listOf("likes tea", "lives in Lisbon"), dao.getAll().map { it.content })
        assertEquals(2, dao.getAllFlow().first().size)
        dao.deleteById(id)
        assertEquals(listOf("lives in Lisbon"), dao.getAll().map { it.content })
        assertEquals(1, dao.delete(dao.getAll().single()))
        assertTrue(dao.getAll().isEmpty())
    }

    @Test fun entityDefaults() {
        val c = Conversation(title = "T")
        assertEquals(0L, c.id)
        assertTrue(c.timestamp > 0)
        val m = Message(conversationId = 1L, text = "x", role = "user")
        assertEquals(emptyList(), m.imagePaths)
        assertEquals(false, m.hasAudio)
        assertNull(m.missingAppPackage)
        assertNull(m.a2ui)
    }
}
