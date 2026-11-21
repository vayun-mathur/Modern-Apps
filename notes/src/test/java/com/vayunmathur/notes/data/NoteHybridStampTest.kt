package com.vayunmathur.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.HybridStampGenerator
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hybrid sync stamps for the notes store: local stamps stay monotonic when
 * the wall clock jumps backwards, and a received future remote stamp
 * advances subsequent local stamps past it. The DAO persists the
 * high-water mark as `MAX(updatedAt)` over its rows (pulled stamps land
 * via `upsertRaw`), so no extra column is needed.
 */
@RunWith(RobolectricTestRunner::class)
class NoteHybridStampTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(context)

    @BeforeTest fun purge() = runTest {
        repo.purge(); repo.deleteAllSnapshots()
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
        repo.upsertRaw(Note(title = "From fast device", content = "", syncId = "remote", updatedAt = future))
        repo.upsert(Note(title = "Local edit", content = "", syncId = "local"))
        val local = repo.getBySyncId("local")!!
        assertTrue(
            local.updatedAt > future,
            "local stamp ${local.updatedAt} did not advance past remote $future",
        )
        // Sequential local writes keep increasing even within one millisecond.
        repo.upsert(local.copy(title = "Local edit 2"))
        assertTrue(repo.getBySyncId("local")!!.updatedAt > local.updatedAt)
    }
}
