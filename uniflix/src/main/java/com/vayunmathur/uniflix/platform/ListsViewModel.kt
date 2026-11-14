package com.vayunmathur.uniflix.platform

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.UserList
import com.vayunmathur.uniflix.data.UserListItem
import kotlinx.coroutines.launch

/** Local lists hub: create lists, add/remove items. */
class ListsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = UniflixRepository.get(app)

    val lists get() = repository.userLists
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
