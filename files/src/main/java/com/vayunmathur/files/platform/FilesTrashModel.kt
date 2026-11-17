package com.vayunmathur.files.platform

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.viewModelScope
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * System trash (MediaStore) actions behind [FilesViewModel], as a layer of the chain
 * (see FilesCoreModel) so the ViewModel stays under the function cap.
 *
 * This is the platform trash, not an app-owned `.trash` folder: trashed items live in the
 * shared MediaStore trash with 30-day auto-delete, restore, and per-batch user-consent dialogs.
 */
abstract class FilesTrashModel(application: Application) :
    FilesHomeModel(application),
    TrashActions {

    override fun trashSelection() {
        val selection = selectedPathsState.value.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val trashable = resolveTrashableUris(ctx, selection.mapNotNull { it.realFile })
            val untrashable = selection.filter { it.realFile !in trashable }
            if (trashable.isNotEmpty()) {
                selectedPathsState.value = emptySet()
                loadDirectory()
                pendingTrashMessage = ctx.resources
                    .getQuantityString(R.plurals.moved_to_trash, trashable.size, trashable.size)
                trashConsentRequestsFlow.emit(
                    trashPendingIntent(ctx, trashable.values.toList(), true)
                )
            }
            if (untrashable.isNotEmpty()) {
                pendingPermanentDeleteState.value = untrashable
            }
        }
    }

    override fun confirmPendingPermanentDelete() {
        val pending = pendingPermanentDeleteState.value ?: return
        pendingPermanentDeleteState.value = null
        viewModelScope.launch(Dispatchers.IO) {
            pending.mapNotNull { it.realFile }.forEach { it.deleteRecursively() }
            selectedPathsState.value = emptySet()
            loadDirectory()
        }
    }

    override fun loadTrash() {
        trashSelectionState.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            trashedItemsState.value = queryTrashedItems(getApplication())
        }
    }

    override fun openTrash() {
        categoryTitleState.value = null
        zipPathState.value = null
        zipInternalPathState.value = ""
        clearSelection()
        cancelDirectoryObserver()
        loadTrash()
    }

    override fun toggleTrashSelection(item: FileBrowserItem) {
        val current = trashSelectionState.value
        trashSelectionState.value = if (current.any { it.key == item.key }) {
            current.filterNot { it.key == item.key }.toSet()
        } else {
            current + item
        }
    }

    override fun clearTrashSelection() {
        if (trashSelectionState.value.isNotEmpty()) trashSelectionState.value = emptySet()
    }

    override fun restoreTrashSelection() {
        restoreTrashed()
    }

    override fun deleteForeverTrashSelection() {
        deleteTrashedForever()
    }

    override fun emptyTrash() {
        emptySystemTrash()
    }

    private fun restoreTrashed() {
        val selection = trashSelectionState.value.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val uris = urisForSelection(ctx, selection)
            if (uris.isEmpty()) return@launch
            trashSelectionState.value = emptySet()
            pendingTrashMessage = ctx.resources.getQuantityString(R.plurals.restored_n, uris.size, uris.size)
            trashConsentRequestsFlow.emit(trashPendingIntent(ctx, uris, false))
        }
    }

    private fun deleteTrashedForever() {
        val selection = trashSelectionState.value.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val uris = urisForSelection(ctx, selection)
            if (uris.isEmpty()) return@launch
            trashSelectionState.value = emptySet()
            pendingTrashMessage = ctx.resources.getQuantityString(R.plurals.deleted_forever_n, uris.size, uris.size)
            trashConsentRequestsFlow.emit(deleteForeverPendingIntent(ctx, uris))
        }
    }

    private fun emptySystemTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val items = queryTrashedItems(ctx)
            if (items.isEmpty()) return@launch
            val uris = items.mapNotNull { it.mediaUri }
            if (uris.isEmpty()) return@launch
            trashSelectionState.value = emptySet()
            pendingTrashMessage = ctx.resources.getQuantityString(R.plurals.deleted_forever_n, uris.size, uris.size)
            trashConsentRequestsFlow.emit(deleteForeverPendingIntent(ctx, uris))
        }
    }

    /** Called after a trash/restore/delete consent dialog resolves; refreshes both listings. */
    override fun onTrashConsentResult(granted: Boolean) {
        loadDirectory()
        viewModelScope.launch(Dispatchers.IO) {
            trashedItemsState.value = queryTrashedItems(getApplication())
        }
        if (granted) {
            pendingTrashMessage?.let { emit(it) }
        }
        pendingTrashMessage = null
    }
}

/** Consent-gated request to move [uris] to (or restore from) the system trash. */
internal fun trashPendingIntent(context: Context, uris: List<Uri>, trash: Boolean): PendingIntent =
    MediaStore.createTrashRequest(context.contentResolver, uris, trash)

/** Consent-gated request to permanently delete [uris] from the system trash. */
internal fun deleteForeverPendingIntent(context: Context, uris: List<Uri>): PendingIntent =
    MediaStore.createDeleteRequest(context.contentResolver, uris)

/** MediaStore Uris for [selection], preferring the cached [FileBrowserItem.mediaUri]. */
internal fun urisForSelection(
    context: Context,
    selection: List<FileBrowserItem>
): List<Uri> = selection.mapNotNull { item ->
    item.mediaUri ?: item.realFile?.let { file ->
        resolveTrashableUris(context, listOf(file))[file]
    }
}
