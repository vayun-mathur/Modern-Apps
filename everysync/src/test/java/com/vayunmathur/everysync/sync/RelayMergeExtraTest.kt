package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.sync.adapters.TodoKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayMergeExtraTest {
    private fun todo(uid: String, title: String, upd: Long, deleted: Boolean = false) =
        RemoteTodo(uid = uid, title = title, createdAt = 7L, deleted = deleted, updatedAt = upd)
    private fun base(e: RemoteTodo, deleted: Boolean = false) =
        RelaySnapshot(e.uid, "todo", TodoKind.contentHash(e), e.updatedAt, e.updatedAt, 900L, deleted)

    private class FakeStore : RelayStore<RemoteTodo> {
        val local = mutableListOf<RemoteTodo>(); val upserted = mutableListOf<RemoteTodo>()
        val deletedItems = mutableListOf<RemoteTodo>(); val saved = mutableListOf<RelaySnapshot>(); val removed = mutableListOf<String>()
        override suspend fun listLocal() = local.toList()
        override suspend fun upsertLocal(items: List<RemoteTodo>) { upserted += items }
        override suspend fun deleteLocal(items: List<RemoteTodo>) { deletedItems += items }
        override suspend fun loadBaseline() = emptyMap<String, RelaySnapshot>()
        override suspend fun saveSnapshots(s: List<RelaySnapshot>) { saved += s }
        override suspend fun removeSnapshots(ids: List<String>) { removed += ids }
        override fun parseRemote(json: String) = TodoKind.fromJson(json)
        override fun serializeForRemote(e: RemoteTodo) = TodoKind.toJson(e)
    }

    @Test fun applyLocalPersistsInOrder() = runBlocking {
        val store = FakeStore()
        val plan = RelayPlan(listOf(todo("a","A",1)), listOf(todo("b","B",1)), listOf(todo("c","C",1)),
            listOf(RelaySnapshot("a","todo","h",1,1,1)), listOf("x"), true, 1, 1, 0, 0)
        RelayMerge.applyLocal(store, plan)
        assertEquals(1, store.deletedItems.size); assertEquals(1, store.upserted.size)
        assertEquals(listOf("x"), store.removed); assertEquals(1, store.saved.size)
        // empty plan touches nothing
        val s2 = FakeStore(); RelayMerge.applyLocal(s2, RelayPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, 0,0,0,0))
        assertTrue(s2.upserted.isEmpty() && s2.saved.isEmpty())
    }
    @Test fun localTombstoneWithoutBaselineIsDroppedLocally() {
        val plan = RelayMerge.merge(TodoKind, listOf(todo("a","A",100, deleted=true)), emptyList(), emptyMap(), 1000)
        assertEquals(1, plan.localDeletes.size); assertTrue(plan.remoteOut.isEmpty())
    }
    @Test fun acknowledgedTombstoneIsGarbageCollected() {
        val dead = todo("a","A",100, deleted=true)
        val plan = RelayMerge.merge(TodoKind, listOf(dead), emptyList(), mapOf("a" to base(dead, deleted=true)), 1000)
        assertEquals(1, plan.localDeletes.size); assertEquals(listOf("a"), plan.removedSyncIds)
    }
    @Test fun recreatedAfterAcknowledgedDeleteIsPushedLive() {
        val deadBase = todo("a","A",100, deleted=true)
        val live = todo("a","A2",200)
        val plan = RelayMerge.merge(TodoKind, listOf(live), emptyList(), mapOf("a" to base(deadBase, deleted=true)), 1000)
        assertEquals(1, plan.remoteOut.size); assertEquals(false, plan.remoteOut[0].deleted); assertEquals(1, plan.pushed)
    }
    @Test fun localTombstoneWithBaselineNoRemoteIsCollected() {
        val dead = todo("a","A",100, deleted=true)
        val plan = RelayMerge.merge(TodoKind, listOf(dead), emptyList(), mapOf("a" to base(todo("a","A",100))), 1000)
        assertEquals(1, plan.localDeletes.size); assertEquals(listOf("a"), plan.removedSyncIds)
    }
    @Test fun remoteTombstoneWithoutLocalIsRecordedNotPulled() {
        val dead = todo("a","A",100, deleted=true)
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(dead), emptyMap(), 1000)
        assertTrue(plan.localUpserts.isEmpty()); assertEquals(1, plan.snapshots.size); assertEquals(true, plan.snapshots[0].deleted); assertEquals(1, plan.deletedRemote)
    }
    @Test fun acknowledgedRemoteTombstoneIsSteadyState() {
        val dead = todo("a","A",100, deleted=true)
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(dead), mapOf("a" to base(dead, deleted=true)), 1000)
        assertTrue(plan.localUpserts.isEmpty() && plan.remoteOut.isEmpty() && plan.snapshots.isEmpty())
    }
    @Test fun unidentifiedRemoteIsAdoptedByContentKey() {
        val local = todo("local1","Shop",100)
        val remote = todo("","Shop",100) // same contentKey (title+createdAt)
        val plan = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000)
        // adopted onto local id: should not duplicate as a second pull
        assertTrue(plan.localUpserts.all { it.uid == "local1" })
        assertEquals(1, plan.snapshots.size)
    }
    @Test fun unidentifiedRemoteWithoutMatchGetsFreshId() {
        val remote = todo("","Unique",100)
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(remote), emptyMap(), 1000)
        assertEquals(1, plan.localUpserts.size); assertTrue(plan.localUpserts[0].uid.isNotBlank())
        assertEquals(1, plan.remoteOut.size) // re-appended identified
    }
    @Test fun duplicateRemoteIdsSecondIsAdopted() {
        val a = todo("x","One",100); val dup = todo("x","One",100)
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(a, dup), emptyMap(), 1000)
        assertTrue(plan.localUpserts.size >= 1)
    }
    @Test fun onlyLocalDirtyWinsWithoutRemoteDirtyCheck() {
        val agreed = todo("a","v1",100); val localNew = todo("a","v2",100)
        val plan = RelayMerge.merge(TodoKind, listOf(localNew), listOf(agreed), mapOf("a" to base(agreed)), 1000)
        assertEquals(1, plan.pushed); assertEquals(0, plan.pulled)
    }
    @Test fun onlyRemoteDirtyWins() {
        val agreed = todo("a","v1",100); val remoteNew = todo("a","v2",100)
        val plan = RelayMerge.merge(TodoKind, listOf(agreed), listOf(remoteNew), mapOf("a" to base(agreed)), 1000)
        assertEquals(1, plan.pulled); assertEquals("v2", plan.localUpserts[0].title)
    }
    @Test fun bothTombstonesDeleteWins() {
        val plan = RelayMerge.merge(TodoKind, listOf(todo("a","A",100,true)), listOf(todo("a","A",100,true)), emptyMap(), 1000)
        assertEquals(true, plan.snapshots[0].deleted)
    }
    @Test fun snapshotHelperBuildsKindSnapshot() {
        val s = TodoKind.snapshot("id","hash",1,2,3,true)
        assertEquals("todo", s.kind); assertEquals(true, s.deleted); assertEquals(3L, s.lastSyncedAt)
    }
    @Test fun localReadExceptionCarriesCause() {
        val e = LocalReadException("boom", RuntimeException("x"))
        assertEquals("boom", e.message); assertEquals("x", e.cause?.message)
    }
}
