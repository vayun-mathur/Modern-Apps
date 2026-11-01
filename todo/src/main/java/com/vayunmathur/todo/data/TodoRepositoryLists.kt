package com.vayunmathur.todo.data

import com.vayunmathur.todo.domain.Positions

suspend fun TodoRepository.getLists(): List<TodoList> = listDao.getAll()
suspend fun TodoRepository.getListBySyncId(syncId: String): TodoList? = listDao.getBySyncId(syncId)
suspend fun TodoRepository.nextListPosition(): Double = Positions.after(listDao.maxPosition())
suspend fun TodoRepository.upsertList(value: TodoList): Long = listDao.upsert(value)
suspend fun TodoRepository.upsertLists(values: List<TodoList>) = listDao.upsertAll(values)

/** Deletes a list; its tasks move to the Inbox. */
suspend fun TodoRepository.tombstoneList(list: TodoList) = inTransaction {
    listDao.upsert(list.copy(deleted = true))
    dao.upsertAll(dao.getInList(list.syncId).map { it.copy(listSyncId = null, section = null) })
}
