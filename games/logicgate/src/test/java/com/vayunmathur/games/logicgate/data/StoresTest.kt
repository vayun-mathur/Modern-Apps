package com.vayunmathur.games.logicgate.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.logicgate.domain.ReferenceCircuits
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.CustomChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StoresTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun `circuit store round trips a solved circuit and rewrites`() = runBlocking {
        val store = CircuitStore(ctx, Dispatchers.IO)
        assertNull(store.load("not"))
        val circuit = ReferenceCircuits.build("not")
        store.save("not", circuit)
        await("circuit saved") { runBlocking { store.load("not") } != null }
        assertEquals(circuit.nodes.size, store.load("not")!!.nodes.size)
        assertEquals(circuit.wires.size, store.load("not")!!.wires.size)
        store.rewriteAll { it }
        assertEquals(circuit.nodes.size, store.load("not")!!.nodes.size)
        assertNull(store.load("and"))
    }

    @Test fun `back to back saves keep both levels drafts`() = runBlocking {
        val store = CircuitStore(ctx, Dispatchers.IO)
        val a = ReferenceCircuits.build("not")
        val b = ReferenceCircuits.build("and")
        store.save("race-a", a)
        store.save("race-b", b)
        // Before the fix the second save cancelled the first level's debounced write, so the
        // "race-a" draft never reached the DataStore and this timed out. Read through a fresh
        // store so the in-memory overlay cannot mask a lost flush.
        await("both drafts saved") {
            val fresh = CircuitStore(ctx, Dispatchers.IO)
            runBlocking { fresh.load("race-a") } != null &&
                runBlocking { fresh.load("race-b") } != null
        }
        val fresh = CircuitStore(ctx, Dispatchers.IO)
        assertEquals(a.nodes.size, fresh.load("race-a")!!.nodes.size)
        assertEquals(b.nodes.size, fresh.load("race-b")!!.nodes.size)
    }

    @Test fun `rewrite during debounce keeps the rewrite`() = runBlocking {
        val store = CircuitStore(ctx, Dispatchers.IO)
        val circuit = ReferenceCircuits.build("not")
        store.save("race-rw", circuit)
        // The save is still inside its debounce here, so the rewrite must absorb the pending
        // draft instead of throwing the cancelled flush's CancellationException into the caller.
        store.rewriteAll { it.copy(nextId = it.nextId + 100) }
        val fresh = CircuitStore(ctx, Dispatchers.IO)
        await("rewrite landed") {
            runBlocking { fresh.load("race-rw") }?.nextId == circuit.nextId + 100
        }
        assertEquals(circuit.nextId + 100, fresh.load("race-rw")!!.nextId)
    }

    @Test fun `component store round trips custom chips`() = runBlocking {
        val store = ComponentStore(ctx, Dispatchers.IO)
        store.save(emptyList())
        await("components cleared") { runBlocking { store.load() }.isEmpty() }
        val chip = CustomChip("custom:test-1", "My Gate", "Group", Board.Empty)
        store.save(listOf(chip))
        await("component saved") { runBlocking { store.load() }.isNotEmpty() }
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("My Gate", loaded.first().name)
        assertEquals("custom:test-1", loaded.first().id)
        store.save(emptyList())
    }

    @Test fun `progress store records best only when better and brief seen once`() = runBlocking {
        val store = ProgressStore(ctx)
        store.record("not", 5)
        assertEquals(5, store.bestCosts()["not"])
        store.record("not", 9) // worse: best stays at the lower cost (lower wins)
        assertEquals(5, store.bestCosts()["not"])
        assertEquals(true, store.markBriefSeen("brief-test-level"))
        assertEquals(false, store.markBriefSeen("brief-test-level"))
    }
}
