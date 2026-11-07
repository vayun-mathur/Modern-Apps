package com.vayunmathur.files.platform

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A directory listing: folders first, then files. */
internal typealias DirListing = Pair<List<FileBrowserItem>, List<FileBrowserItem>>

/**
 * Shared browser state and cross-cutting helpers behind [FilesViewModel].
 *
 * The ViewModel is one inheritance chain (core → browse → edit → share → home → trash →
 * [FilesViewModel]) so every layer sees one `this`: a single view model for the UI, with
 * each class declaring few enough functions to stay under the function cap. State lives
 * here so layers never duplicate flows; layers only add behaviour.
 *
 * Layers never define `init` blocks — only [FilesViewModel] does, once everything exists.
 */
abstract class FilesCoreModel(application: Application) : AndroidViewModel(application) {

    internal val prefs: SharedPreferences =
        application.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)

    val rootDirectory: File = Environment.getExternalStorageDirectory()
    internal val downloadsDir: File get() = File(rootDirectory, "Download")

    private val isFilesGrantedState = MutableStateFlow(Environment.isExternalStorageManager())
    val isFilesGranted: StateFlow<Boolean> = isFilesGrantedState.asStateFlow()

    private val hasPromptedNotificationsState =
        MutableStateFlow(prefs.getBoolean("has_prompted_notifications", false))
    val hasPromptedNotifications: StateFlow<Boolean> =
        hasPromptedNotificationsState.asStateFlow()

    internal fun setFilesGranted(granted: Boolean) {
        isFilesGrantedState.value = granted
    }

    internal fun setNotificationsPromptedState() {
        hasPromptedNotificationsState.value = true
    }

    internal val currentDirectoryState = MutableStateFlow(rootDirectory)
    val currentDirectory: StateFlow<File> = currentDirectoryState.asStateFlow()

    internal val zipPathState = MutableStateFlow<File?>(null)
    val zipPath: StateFlow<File?> = zipPathState.asStateFlow()

    internal val zipInternalPathState = MutableStateFlow("")
    val zipInternalPath: StateFlow<String> = zipInternalPathState.asStateFlow()

    internal val zipOpenedState = MutableSharedFlow<File>(extraBufferCapacity = 4)

    /**
     * Emitted once an archive has been confirmed readable, so that navigation happens only for a zip
     * that actually opens. Validation is IO, so it cannot be done by the caller before navigating.
     */
    val zipOpened: SharedFlow<File> = zipOpenedState.asSharedFlow()

    internal val categoryTitleState = MutableStateFlow<String?>(null)
    val categoryTitle: StateFlow<String?> = categoryTitleState.asStateFlow()

    internal val entriesState = MutableStateFlow<DirListing>(
        emptyList<FileBrowserItem>() to emptyList()
    )
    val entries: StateFlow<DirListing> = entriesState.asStateFlow()

    internal val selectedPathsState = MutableStateFlow<Set<FileBrowserItem>>(emptySet())
    val selectedPaths: StateFlow<Set<FileBrowserItem>> = selectedPathsState.asStateFlow()

    internal val snackbarMessagesFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = snackbarMessagesFlow.asSharedFlow()

    internal val intentsFlow = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val intents: SharedFlow<Intent> = intentsFlow.asSharedFlow()

    // ---- System trash ----
    internal val trashedItemsState = MutableStateFlow<List<FileBrowserItem>>(emptyList())
    val trashedItems: StateFlow<List<FileBrowserItem>> = trashedItemsState.asStateFlow()

    internal val trashSelectionState = MutableStateFlow<Set<FileBrowserItem>>(emptySet())
    val trashSelection: StateFlow<Set<FileBrowserItem>> = trashSelectionState.asStateFlow()

    internal val pendingPermanentDeleteState = MutableStateFlow<List<FileBrowserItem>?>(null)
    val pendingPermanentDelete: StateFlow<List<FileBrowserItem>?> =
        pendingPermanentDeleteState.asStateFlow()

    internal val trashConsentRequestsFlow = MutableSharedFlow<PendingIntent>(
        extraBufferCapacity = 4
    )

    /**
     * System trash/restore/delete consent requests. Every MediaStore trash mutation needs a
     * user-confirmed PendingIntent, so the UI fires these via StartIntentSenderForResult —
     * same shape as [installPermissionRequests].
     */
    val trashConsentRequests: SharedFlow<PendingIntent> =
        trashConsentRequestsFlow.asSharedFlow()

    /** Snackbar for the operation whose consent dialog just resolved. Set before emitting. */
    internal var pendingTrashMessage: String? = null

    // ---- APK install ----
    /** The APK waiting to be installed once the user grants the "install unknown apps" permission. */
    internal var pendingApkInstall: File? = null

    internal val installPermissionRequestsFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when an APK was tapped but Files lacks permission to install; UI opens settings. */
    val installPermissionRequests: SharedFlow<Unit> = installPermissionRequestsFlow.asSharedFlow()

    private var directoryObserverJob: Job? = null
    private var directoryLoadJob: Job? = null

    // Guards against a stale reload (e.g. a FileObserver event that snapshots the
    // directory mid-deletion) overwriting a newer one. Only the latest load wins.
    private val loadGeneration = AtomicInteger(0)

    internal fun cancelDirectoryObserver() {
        directoryObserverJob?.cancel()
        directoryObserverJob = null
    }

    internal fun isZipMode(): Boolean = zipPathState.value != null

    internal fun emit(message: String) {
        viewModelScope.launch { snackbarMessagesFlow.emit(message) }
    }

    internal fun emitMoveFailed(error: IOException) {
        emitMoveFailedMessage(error.localizedMessage)
    }

    internal fun emitMoveFailed(error: IllegalArgumentException) {
        emitMoveFailedMessage(error.localizedMessage)
    }

    internal fun emitMoveFailedMessage(detail: String?) {
        emit(getApplication<Application>().getString(R.string.move_failed, detail))
    }

    internal fun loadDirectory() {
        val gen = loadGeneration.incrementAndGet()
        directoryLoadJob?.cancel()
        val zipFile = zipPathState.value
        val dir = currentDirectoryState.value
        val inner = zipInternalPathState.value
        val showHidden = showHiddenState.value
        val sort = sortByState.value
        val ascending = sortAscendingState.value
        directoryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val raw = if (zipFile == null) {
                listRealDirItems(dir, showHidden)
            } else {
                listZipDirItems(zipFile, inner)
            }
            val sorted = sortListing(raw, sort, ascending)
            if (gen == loadGeneration.get()) {
                entriesState.value = sorted
            }
        }
    }

    internal fun restartObserver() {
        directoryObserverJob?.cancel()
        if (isZipMode()) {
            directoryObserverJob = null
            return
        }
        val dir = currentDirectoryState.value
        directoryObserverJob = viewModelScope.launch {
            val observer = object : FileObserver(dir, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    loadDirectory()
                }
            }
            observer.startWatching()
            try {
                awaitCancellation()
            } finally {
                observer.stopWatching()
            }
        }
    }

    // ---- Sort state lives here so loadDirectory can apply it; behaviour in FilesBrowseModel. ----
    internal val sortByState = MutableStateFlow(
        runCatching { SortBy.valueOf(prefs.getString("sort_by", null) ?: "NAME") }
            .getOrDefault(SortBy.NAME)
    )
    val sortBy: StateFlow<SortBy> = sortByState.asStateFlow()

    internal val sortAscendingState = MutableStateFlow(prefs.getBoolean("sort_ascending", true))
    val sortAscending: StateFlow<Boolean> = sortAscendingState.asStateFlow()

    internal val viewModeState = MutableStateFlow(
        runCatching { ViewMode.valueOf(prefs.getString("view_mode", null) ?: "LIST") }
            .getOrDefault(ViewMode.LIST)
    )
    val viewMode: StateFlow<ViewMode> = viewModeState.asStateFlow()

    internal val searchQueryState = MutableStateFlow("")
    val searchQuery: StateFlow<String> = searchQueryState.asStateFlow()

    internal val isSearchActiveState = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = isSearchActiveState.asStateFlow()

    internal val showHiddenState = MutableStateFlow(prefs.getBoolean("show_hidden", false))
    val showHidden: StateFlow<Boolean> = showHiddenState.asStateFlow()

    // ---- Clipboard state lives here for the same reason; behaviour in FilesShareModel. ----
    internal val clipboardState = MutableStateFlow<List<File>>(emptyList())
    val clipboard: StateFlow<List<File>> = clipboardState.asStateFlow()

    internal val clipboardIsCutState = MutableStateFlow(false)
    val clipboardIsCut: StateFlow<Boolean> = clipboardIsCutState.asStateFlow()

    internal val incomingUrisState = MutableStateFlow<List<Uri>?>(null)
    val incomingUris: StateFlow<List<Uri>?> = incomingUrisState.asStateFlow()

    // ---- Trash operations are implemented by FilesTrashModel; stubs here so earlier layers ----
    // ---- (browse, edit) can call them polymorphically without knowing about that layer. ----
    internal abstract fun trashSelection()

    internal abstract fun confirmPendingPermanentDelete()

    internal abstract fun loadTrash()
}

internal fun sortListing(
    entries: DirListing,
    sortBy: SortBy,
    ascending: Boolean
): DirListing = sortItemsList(entries.first, sortBy, ascending) to
    sortItemsList(entries.second, sortBy, ascending)

internal fun sortItemsList(
    items: List<FileBrowserItem>,
    sortBy: SortBy,
    ascending: Boolean
): List<FileBrowserItem> {
    val cmp: Comparator<FileBrowserItem> = when (sortBy) {
        SortBy.NAME -> compareBy { it.name.lowercase() }
        SortBy.DATE -> compareBy { it.lastModified }
        SortBy.SIZE -> compareBy { it.size ?: -1L }
        SortBy.TYPE -> compareBy<FileBrowserItem> {
            it.name.substringAfterLast('.', "").lowercase()
        }.thenBy { it.name.lowercase() }
    }
    val sorted = items.sortedWith(cmp)
    return if (ascending) sorted else sorted.reversed()
}

/**
 * Best-effort atomic move with a copy fallback, so a rename never half-completes.
 *
 * A move must never overwrite: [target] already existing is an error the caller
 * reports, leaving both files untouched. Callers that want a fresh name use
 * `uniqueDestination` first (paste, create), so refusing here does not change them.
 */
internal fun File.atomicMoveTo(target: File) {
    if (canonicalFile == target.canonicalFile) return
    if (target.exists()) {
        throw java.nio.file.FileAlreadyExistsException(
            absolutePath, target.absolutePath, "destination already exists"
        )
    }
    try {
        java.nio.file.Files.move(
            this.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    } catch (e: java.nio.file.FileAlreadyExistsException) {
        throw e
    } catch (_: IOException) {
        // Cross-volume (or non-atomic) move: rename, else copy then delete the source.
        // The copy never overwrites, and the source is only deleted after it succeeds.
        if (target.exists()) {
            throw java.nio.file.FileAlreadyExistsException(
                absolutePath, target.absolutePath, "destination already exists"
            )
        }
        if (!this.renameTo(target)) {
            if (this.isDirectory) {
                if (!this.copyRecursively(target, overwrite = false)) {
                    target.deleteRecursively()
                    throw IOException("Could not move $absolutePath to ${target.absolutePath}")
                }
                this.deleteRecursively()
            } else {
                this.copyTo(target, overwrite = false)
                this.delete()
            }
        }
    }
}
