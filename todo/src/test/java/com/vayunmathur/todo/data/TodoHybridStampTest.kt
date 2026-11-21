package com.vayunmathur.todo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.HybridStampGenerator
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hybrid sync stamps for the todo store: local stamps stay monotonic when
 * the wall clock jumps backwards, and a received future remote stamp
 * advances subsequent local stamps past it. The DAO persists the
 * high-water mark as `MAX(updatedAt)` over its rows (pulled stamps land
 * via `upsertRaw`), so no extra column is needed.
 */
@RunWith(RobolectricTestRunner::class)
class TodoHybridStampTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val harness = lazy { TestTodoRepository.create(context) }
    private val repo: TodoRepository get() = harness.value.repository

    @AfterTest fun tearDown() {
        if (harness.isInitialized()) harness.value.close()
    }

    @Test
    fun generatorStampsIncreaseWhenWallClockJumpsBackwards() {
        var wall = 1_000_000L
        var persisted = 0L
        val gen = HybridStampGenerator(initialLast = persisted, wallNow = { wall })
        val first = gen.next().also { persisted = gen.last }
        wall = 1L // wall clock jumps far backwards
        val second = gen.next().also { persisted = gen.last }
        assertTrue(second > first, "stamp regressed after backwards clock: $first -> $second")
        // A fresh generator seeded from the persisted mark stays ahead too.
        val restarted = HybridStampGenerator(initialLast = persisted, wallNow = { wall })
        assertTrue(restarted.next() > second)
    }

    @Test
    fun localUpsertAfterFutureRemoteStampExceedsRemote() = runTest {
        val future = System.currentTimeMillis() + 86_400_000L
        repo.upsertRaw(Todo(syncId = "remote", title = "From fast device", updatedAt = future))
        repo.upsert(Todo(syncId = "local", title = "Local edit"))
        val local = repo.getBySyncId("local")!!
        assertTrue(
            local.updatedAt > future,
            "local stamp ${local.updatedAt} did not advance past remote $future",
        )
        // Sequential local writes keep increasing even within one millisecond.
        repo.upsert(local.copy(title = "Local edit 2"))
        assertTrue(repo.getBySyncId("local")!!.updatedAt > local.updatedAt)
    }

    @Test
    fun listUpsertAfterFutureRemoteStampExceedsRemote() = runTest {
        val future = System.currentTimeMillis() + 86_400_000L
        repo.upsertListRaw(TodoList(syncId = "rlist", name = "Remote", updatedAt = future))
        repo.upsertList(TodoList(syncId = "llist", name = "Local"))
        val local = repo.getListBySyncId("llist")!!
        assertTrue(local.updatedAt > future, "list stamp ${local.updatedAt} not past remote $future")
    }
}
