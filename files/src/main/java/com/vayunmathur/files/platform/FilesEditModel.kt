package com.vayunmathur.files.platform

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The structural edits (create, rename, delete, drag-and-drop moves) behind [FilesViewModel],
 * as a layer of the chain (see FilesCoreModel) so the ViewModel stays under the function cap.
 *
 * Each operation guards against zip mode up front — the browser is read-only inside archives —
 * then runs on IO and refreshes the listing.
 */
abstract class FilesEditModel(application: Application) :
    FilesBrowseModel(application),
    EditActions,
    CreateActions {

    internal fun createDestination(trimmedName: String, mkdir: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = uniqueDestination(currentDirectoryState.value, trimmedName)
                val created = if (mkdir) dest.mkdirs() else dest.createNewFile()
                if (created) {
                    loadDirectory()
                } else {
                    emit(getApplication<Application>().getString(R.string.create_failed))
                }
            } catch (e: IOException) {
                emitMoveFailed(e)
            } catch (e: SecurityException) {
                emitMoveFailedMessage(e.localizedMessage)
            }
        }
    }

    override fun createFolder(name: String) {
        if (isZipMode() || name.isBlank()) return
        createDestination(name.trim(), mkdir = true)
    }

    override fun createFile(name: String) {
        if (isZipMode() || name.isBlank()) return
        createDestination(name.trim(), mkdir = false)
    }

    override fun rename(item: FileBrowserItem, newName: String) {
        if (isZipMode()) return
        // The dialog passes raw text, which may contain separators: keep the
        // rename inside the item's own folder, the same rule the create and
        // share paths enforce via sanitizedFileName. A blank name or a bare
        // "." / ".." is a no-op, like a blank create name.
        val safeName = sanitizedFileName(newName) ?: return
        val path = item.realFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(path.parentFile, safeName)
                path.atomicMoveTo(target)
                selectedPathsState.value = emptySet()
                loadDirectory()
            } catch (e: IOException) {
                emitMoveFailed(e)
            } catch (e: SecurityException) {
                emitMoveFailedMessage(e.localizedMessage)
            }
        }
    }

    override fun deleteSelection() {
        if (isZipMode()) return
        trashSelection()
    }

    override fun confirmPermanentDelete() {
        if (isZipMode()) return
        confirmPendingPermanentDelete()
    }

    override fun dismissPermanentDelete() {
        pendingPermanentDeleteState.value = null
    }

    override fun moveInto(sources: List<File>, target: File) {
        if (isZipMode()) return
        if (!target.isDirectory) return
        val targetPath = target.absolutePath
        // Don't paste a folder into itself or a descendant. The separator
        // matters: a raw prefix check also rejects siblings that merely share
        // a name prefix (moving "foo" into "foobar"), as pasteOne already
        // handles correctly.
        moveFiles(sources, target) { source ->
            source != target && !targetPath.startsWith(source.absolutePath + "/")
        }
    }

    override fun moveToBreadcrumb(sources: List<File>, target: File) {
        if (isZipMode()) return
        moveFiles(sources, target) { source -> source.parentFile != target && source != target }
    }

    private fun moveFiles(sources: List<File>, target: File, canMove: (File) -> Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var movedAny = false
            var lastError: IOException? = null
            var lastSecurityError: SecurityException? = null
            sources.forEach { source ->
                if (canMove(source)) {
                    try {
                        val dest = File(target, source.name)
                        source.atomicMoveTo(dest)
                        movedAny = true
                    } catch (e: IOException) {
                        lastError = e
                    } catch (e: SecurityException) {
                        lastSecurityError = e
                    }
                }
            }
            if (movedAny) {
                selectedPathsState.value = emptySet()
                loadDirectory()
            }
            lastError?.let { emitMoveFailed(it) }
            lastSecurityError?.let { emitMoveFailedMessage(it.localizedMessage) }
        }
    }
}
