package com.vayunmathur.todo.data

// The relay provider's surface: raw writes that keep pulled timestamps, tombstones by sync id,
// purges, and the snapshot baseline. Nothing else should call these.

suspend fun TodoRepository.getUpdatedSince(since: Long): List<Todo> = dao.getUpdatedSince(since)

/** Writes [value] verbatim, preserving a pulled timestamp. */
suspend fun TodoRepository.upsertRaw(value: Todo): Long = dao.upsertRaw(value)

suspend fun TodoRepository.tombstoneBySyncId(syncId: String): Int {
    val existing = dao.getBySyncId(syncId) ?: return 0
    if (!existing.deleted) dao.upsert(existing.copy(deleted = true))
    return 1
}

/** Removes every todo (including tombstones). Used when a relay vault is purged. */
suspend fun TodoRepository.purge() = dao.deleteAll()

suspend fun TodoRepository.getListsUpdatedSince(since: Long): List<TodoList> = listDao.getUpdatedSince(since)
suspend fun TodoRepository.upsertListRaw(value: TodoList): Long = listDao.upsertRaw(value)

suspend fun TodoRepository.tombstoneListBySyncId(syncId: String): Int {
    val existing = listDao.getBySyncId(syncId) ?: return 0
    if (!existing.deleted) listDao.upsert(existing.copy(deleted = true))
    return 1
}

suspend fun TodoRepository.purgeLists() = listDao.deleteAll()

suspend fun TodoRepository.getTodoSnapshots(): List<SyncSnapshot> = snapshotDao.getAllExcept(SyncSnapshot.KIND_LIST)
suspend fun TodoRepository.getListSnapshots(): List<SyncSnapshot> = snapshotDao.getByKind(SyncSnapshot.KIND_LIST)
suspend fun TodoRepository.upsertSnapshot(snapshot: SyncSnapshot) = snapshotDao.upsert(snapshot)
suspend fun TodoRepository.deleteSnapshotBySyncId(syncId: String) = snapshotDao.deleteBySyncId(syncId)
suspend fun TodoRepository.deleteTodoSnapshots() = snapshotDao.deleteAllExcept(SyncSnapshot.KIND_LIST)
suspend fun TodoRepository.deleteListSnapshots() = snapshotDao.deleteByKind(SyncSnapshot.KIND_LIST)
