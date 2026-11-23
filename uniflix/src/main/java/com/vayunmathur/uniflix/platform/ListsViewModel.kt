package com.vayunmathur.uniflix.platform

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.UserList
import com.vayunmathur.uniflix.data.UserListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Local lists hub: create lists, add/remove items. */
class ListsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = UniflixRepository.get(app)

    private val _hasLoadedLists = MutableStateFlow(false)

    /** Flips true once the user-lists DAO emits at least once; the TV lists start empty. */
    val hasLoadedLists: StateFlow<Boolean> = _hasLoadedLists.asStateFlow()

    val lists get() = repository.userLists.onEach { _hasLoadedLists.value = true }
    fun items(listId: Long) = repository.listItems(listId)

    fun createList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.upsertList(UserList(name = name.trim(), createdAt = System.currentTimeMillis()))
        }
    }

    fun addItem(listId: Long, tmdbId: Int, mediaType: String, title: String, posterPath: String?) {
        viewModelScope.launch {
            repository.upsertListItem(
                UserListItem(
                    listId = listId,
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    title = title,
                    posterPath = posterPath,
                ),
            )
        }
    }

    fun deleteList(list: UserList) {
        viewModelScope.launch { repository.deleteList(list) }
    }

    fun deleteItem(item: UserListItem) {
        viewModelScope.launch { repository.deleteListItem(item) }
    }

    /** Shared constants and helpers for ListsViewModel. */
    companion object {
        fun listsFor(context: Context) = UniflixRepository.get(context).userLists
    }
}
