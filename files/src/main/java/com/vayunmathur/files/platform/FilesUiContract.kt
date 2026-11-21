package com.vayunmathur.files.platform

import java.io.File

/**
 * The UI contract between [FilesViewModel] and the directory browser.
 *
 * The screen takes a state value plus an actions interface rather than the ViewModel
 * itself, so it can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. [FilesViewModel] implements [FilesActions] through small layers
 * (see BrowseActionsLayer): each layer overrides one slice of the contract and delegates
 * the rest, so no single type exceeds the function cap.
 */

/** How the current directory's entries are ordered. */
enum class SortBy { NAME, DATE, SIZE, TYPE }

/** List vs. grid presentation of the current directory. */
enum class ViewMode { LIST, GRID }

/** A home-screen shortcut that collects files of one kind from across storage. */
enum class FileCategory { IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS }

/** Storage volume usage, for the home screen meter. */
data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

/** Everything the directory browser draws. */
data class FilesUiState(
    val rootDirectory: File,
    /**
     * Label for the root breadcrumb — the device model. Passed in because a preview has no
     * device to read it from.
     */
    val rootDisplayName: String,
    val currentDirectory: File,
    /** Non-null while browsing inside a zip; [currentDirectory] is then meaningless. */
    val zipPath: File? = null,
    val zipInternalPath: String = "",
    val directories: List<FileBrowserItem> = emptyList(),
    val files: List<FileBrowserItem> = emptyList(),
    val selectedPaths: Set<FileBrowserItem> = emptySet(),
    /** True while files shared into the app are waiting to be saved here. */
    val hasIncomingUris: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    /** Non-empty while the search field filters the current listing by name. */
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    /**
     * Recursive-search hits under the browsed folder, flattened with parent
     * subtitles. Null means no recursive search supplies this state (previews,
     * zip/category scopes), so the listing keeps its current-folder filter.
     */
    val searchResults: List<FilesSearchHit>? = null,
    /** True while the recursive search walk for [searchQuery] is running. */
    val isSearchLoading: Boolean = false,
    /** Number of files on the copy/cut clipboard (0 = nothing to paste). */
    val clipboardCount: Int = 0,
    /** True when the clipboard holds a cut (move on paste) rather than a copy. */
    val clipboardIsCut: Boolean = false,
    val showHidden: Boolean = false,
    /** Non-null while showing a category result list (e.g. "Images"); [directories] is empty. */
    val categoryTitle: String? = null,
)

/** The state the home screen draws (storage meter, shortcuts, recents, bookmarks). */
data class HomeUiState(
    val rootDisplayName: String,
    val storage: StorageInfo? = null,
    val recents: List<FileBrowserItem> = emptyList(),
    val bookmarks: List<FileBrowserItem> = emptyList(),
)

/** Multi-select state. Only valid in real-filesystem mode, never inside a zip. */
interface SelectionActions {
    /** Dismisses any selection. Always allowed; a no-op when nothing is selected. */
    fun clearSelection() {}
    fun addToSelection(item: FileBrowserItem) {}
    fun toggleSelection(item: FileBrowserItem) {}
    fun selectAll() {}
}

/** Where the user is. Location changes are back-stack entries (see FilesNavActions). */
interface NavigationActions {
    fun navigateTo(path: File) {}
    fun navigateIntoZipDir(dirName: String) {}
    fun navigateToZipInternalPath(fullInternalPath: String) {}
    fun navigateToZipParentRealFolder(target: File) {}
    /** Loads a location inside an archive. Called by the navigation layer for a `Route.Zip`. */
    fun showZip(zip: File, internalPath: String) {}
    /** Reloads the system-trash listing. Called by the navigation layer for a `Route.Trash`. */
    fun showTrash() {}

    /** Returns true when the back press was consumed (a selection was dismissed). */
    fun handleBack(): Boolean = false
}

/** Structural edits to the current folder: rename, delete, drag-and-drop moves. */
interface EditActions {
    fun rename(item: FileBrowserItem, newName: String) {}
    fun deleteSelection() {}
    /** Confirms permanent deletion of items the system trash cannot take (folders, unindexed). */
    fun confirmPermanentDelete() {}
    /** Dismisses the permanent-delete confirmation without deleting. */
    fun dismissPermanentDelete() {}
    fun moveInto(sources: List<File>, target: File) {}
    fun moveToBreadcrumb(sources: List<File>, target: File) {}
}

/** Opening and handing files to other apps. */
interface ShareOpenActions {
    fun openFile(item: FileBrowserItem) {}
    fun openWith(item: FileBrowserItem) {}
    /** Hands an .apk to the system package installer, prompting for the install permission first. */
    fun installApk(item: FileBrowserItem) {}
    fun shareSelection() {}
    /** Called by the UI after returning from the "install unknown apps" settings screen. */
    fun onApkInstallPermissionResult() {}
}

/** Archives: entering zips, creating them, extracting them, saving shared content. */
interface ArchiveActions {
    fun openZipFile(item: FileBrowserItem) {}
    fun archive(archiveName: String) {}
    fun saveIncomingUris() {}
    fun unzip(zipItem: FileBrowserItem, destPath: File) {}
}

/** Sort order, list/grid, hidden files, and search. */
interface BrowseViewActions {
    fun setSortBy(sortBy: SortBy) {}
    fun toggleSortDirection() {}
    fun setSortAscending(ascending: Boolean) {}
    fun toggleViewMode() {}
    fun toggleHidden() {}
    fun setSearchActive(active: Boolean) {}
    fun setSearchQuery(query: String) {}
}

/** Clipboard (copy/cut/paste). */
interface ClipboardActions {
    fun copySelection() {}
    fun cutSelection() {}
    fun pasteHere() {}
    fun clearClipboard() {}
}

/** Creating folders and files in the current directory. */
interface CreateActions {
    fun createFolder(name: String) {}
    fun createFile(name: String) {}
}

/** Home screen: storage meter, categories, bookmarks. */
interface HomeActions {
    fun goHome() {}
    fun openInternalStorage() {}
    fun openCategory(category: FileCategory) {}
    fun openBookmark(path: File) {}
    fun addBookmark(item: FileBrowserItem) {}
    fun removeBookmark(path: File) {}
}

/** System-trash listing: selecting, restoring, deleting forever. */
interface TrashActions {
    fun openTrash() {}
    fun toggleTrashSelection(item: FileBrowserItem) {}
    fun clearTrashSelection() {}
    fun restoreTrashSelection() {}
    fun deleteForeverTrashSelection() {}
    fun emptyTrash() {}
    /** Refreshes both listings after a trash/restore/delete consent dialog resolves. */
    fun onTrashConsentResult(granted: Boolean) {}
}

/** One-shot user feedback (snackbars) without going through a dialog flow. */
interface FeedbackActions {
    fun showMessage(message: String) {}
}

/**
 * Browser callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * Segregated into one sub-interface per concern (selection, navigation, edits, …) so no
 * single type exceeds the function cap.
 */
interface FilesActions :
    SelectionActions,
    NavigationActions,
    EditActions,
    ShareOpenActions,
    ArchiveActions,
    BrowseViewActions,
    ClipboardActions,
    CreateActions,
    HomeActions,
    TrashActions,
    FeedbackActions {
    /** Shared no-op actions, so previews can render the browser without behaviour. */
    companion object {
        val Noop: FilesActions = object : FilesActions {}
    }
}
