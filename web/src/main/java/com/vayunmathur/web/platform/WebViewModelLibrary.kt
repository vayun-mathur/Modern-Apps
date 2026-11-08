package com.vayunmathur.web.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkFolder
import com.vayunmathur.web.data.HistoryEntry
import kotlinx.coroutines.launch

private const val TAG = "WebViewModel"

// ---- History and bookmarks ----

fun WebViewModel.recordHistoryVisit(url: String, title: String, isPrivateTab: Boolean) {
    if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
    // The owning tab's privacy flag, not the active tab's: background tabs finish
    // loading while another tab is active, and the active tab may differ.
    if (isPrivateTab) return
    scope.launch {
        runCatching { repository.history.upsert(HistoryEntry(url = url, title = title)) }
            .onFailure { Log.error(TAG, "recordHistory", it) }
    }
}

fun WebViewModel.addBookmark(url: String, title: String, folderId: Long? = null) {
    scope.launch {
        val entry = Bookmark(url = url, title = title, folderId = folderId)
        runCatching { repository.bookmarks.upsert(entry) }
    }
}

fun WebViewModel.removeBookmark(bookmark: Bookmark) {
    scope.launch { repository.bookmarks.delete(bookmark) }
}

fun WebViewModel.createFolder(name: String) {
    scope.launch { runCatching { repository.bookmarks.upsertFolder(BookmarkFolder(name = name)) } }
}

fun WebViewModel.deleteFolder(folder: BookmarkFolder) {
    scope.launch {
        runCatching {
            repository.bookmarks.deleteByFolder(folder.id)
            repository.bookmarks.deleteFolder(folder)
        }
    }
}

fun WebViewModel.clearHistory() {
    scope.launch { repository.history.clearAll() }
}
