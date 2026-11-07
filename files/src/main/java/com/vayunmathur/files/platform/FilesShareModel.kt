package com.vayunmathur.files.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The clipboard (copy/cut/paste), share, archive, APK-install and open-with actions behind
 * [FilesViewModel], as a layer of the chain (see FilesCoreModel) so the ViewModel stays
 * under the function cap. The `FilesActions` overrides live here next to the state they
 * touch ([FilesCoreModel.clipboardState] and friends).
 */
abstract class FilesShareModel(application: Application) :
    FilesEditModel(application),
    ShareOpenActions,
    ArchiveActions,
    ClipboardActions,
    FeedbackActions {

    // ---- Clipboard (copy/cut/paste) ----
    override fun copySelection() {
        if (isZipMode()) return
        val files = selectedPathsState.value.mapNotNull { it.realFile }
        if (files.isEmpty()) return
        clipboardState.value = files
        clipboardIsCutState.value = false
        selectedPathsState.value = emptySet()
        emit(getApplication<Application>().getString(R.string.copied_n, files.size))
    }

    override fun cutSelection() {
        if (isZipMode()) return
        val files = selectedPathsState.value.mapNotNull { it.realFile }
        if (files.isEmpty()) return
        clipboardState.value = files
        clipboardIsCutState.value = true
        selectedPathsState.value = emptySet()
        emit(getApplication<Application>().getString(R.string.cut_n, files.size))
    }

    override fun clearClipboard() {
        clipboardState.value = emptyList()
        clipboardIsCutState.value = false
    }

    override fun pasteHere() {
        if (isZipMode()) return
        val sources = clipboardState.value
        if (sources.isEmpty()) return
        pasteSources(sources, currentDirectoryState.value, clipboardIsCutState.value)
    }

    private fun pasteSources(sources: List<File>, target: File, isCut: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var lastError: IOException? = null
            var lastSecurityError: SecurityException? = null
            sources.forEach { source ->
                try {
                    pasteOne(source, target, isCut)
                } catch (e: IOException) {
                    lastError = e
                } catch (e: SecurityException) {
                    lastSecurityError = e
                }
            }
            if (isCut) clearClipboard()
            loadDirectory()
            lastError?.let { emitMoveFailed(it) }
            lastSecurityError?.let { emitMoveFailedMessage(it.localizedMessage) }
        }
    }

    private fun pasteOne(source: File, target: File, isCut: Boolean) {
        if (!source.exists()) return
        // Don't paste a folder into itself or a descendant.
        val targetPath = target.absolutePath
        if (targetPath == source.absolutePath ||
            targetPath.startsWith(source.absolutePath + "/")
        ) return
        val dest = uniqueDestination(target, source.name)
        if (isCut) {
            source.atomicMoveTo(dest)
        } else if (source.isDirectory) {
            source.copyRecursively(dest, overwrite = false)
        } else {
            source.copyTo(dest, overwrite = false)
        }
    }

    // ---- Share ----
    override fun shareSelection() {
        val ctx = getApplication<Application>()
        val files = selectedPathsState.value.mapNotNull { it.realFile }.filter { it.isFile }
        if (files.isEmpty()) return
        val uris = try {
            ArrayList(files.map {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
            })
        } catch (e: IllegalArgumentException) {
            emitMoveFailed(e)
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        selectedPathsState.value = emptySet()
        viewModelScope.launch {
            intentsFlow.emit(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    internal fun mimeFor(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"

    // ---- Archive ----
    override fun archive(archiveName: String) {
        if (isZipMode()) return
        // The dialog asks for a file name, not a path, and the archive must
        // not clobber an existing file (ZipWorker refuses that too): pick a
        // fresh "name (n).zip" on collision, like create and paste do.
        val baseName = File(archiveName.trim()).name
        if (baseName.isBlank()) return
        val destFileName = if (baseName.endsWith(".zip")) baseName else "$baseName.zip"
        val destFile = uniqueDestination(currentDirectoryState.value, destFileName)
        val sources = selectedPathsState.value.mapNotNull { it.realFile?.absolutePath }
            .toTypedArray()
        if (sources.isEmpty()) return
        enqueueZip(sources, destFile)
        selectedPathsState.value = emptySet()
        emit(getApplication<Application>().getString(R.string.archiving_started))
    }

    private fun enqueueZip(sources: Array<String>, destFile: File) {
        val zipWork = OneTimeWorkRequestBuilder<ZipWorker>().setInputData(
            workDataOf("source_paths" to sources, "dest_path" to destFile.absolutePath)
        ).build()
        WorkManager.getInstance(getApplication<Application>()).enqueue(zipWork)
    }

    override fun openZipFile(item: FileBrowserItem) {
        val file = item.realFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            checkZipReadable(file)
        }
    }

    private suspend fun checkZipReadable(file: File) {
        try {
            java.util.zip.ZipFile(file).use { _ -> }
            zipOpenedState.emit(file)
        } catch (e: IOException) {
            emitZipOpenFailed(e.localizedMessage)
        }
    }

    private fun emitZipOpenFailed(detail: String?) {
        emit(getApplication<Application>().getString(R.string.could_not_open_zip, detail))
    }

    // ---- Share URIs ----
    fun setIncomingUris(uris: List<Uri>) {
        incomingUrisState.value = uris
    }

    fun clearIncomingUris() {
        incomingUrisState.value = null
    }

    override fun saveIncomingUris() {
        if (isZipMode()) return
        val uris = incomingUrisState.value ?: return
        saveUris(uris, currentDirectoryState.value)
    }

    private fun saveUris(uris: List<Uri>, target: File) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            var lastError: IOException? = null
            uris.forEach { uri ->
                try {
                    saveUriToPath(ctx, uri, target)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            incomingUrisState.value = null
            loadDirectory()
            lastError?.let { emitMoveFailed(it) }
                ?: viewModelScope.launch {
                    snackbarMessagesFlow.emit(ctx.getString(R.string.files_saved))
                }
        }
    }

    // ---- APK install ----
    override fun installApk(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        if (isZipMode()) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val file = item.realFile ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) {
            launchApkInstallFile(file)
        } else {
            pendingApkInstall = file
            emit(ctx.getString(R.string.install_permission_needed))
            installPermissionRequestsFlow.tryEmit(Unit)
        }
    }

    /** Called by the UI after returning from the "install unknown apps" settings screen. */
    override fun onApkInstallPermissionResult() {
        val ctx = getApplication<Application>()
        val file = pendingApkInstall ?: return
        pendingApkInstall = null
        if (ctx.packageManager.canRequestPackageInstalls()) {
            launchApkInstallFile(file)
        } else {
            emit(ctx.getString(R.string.install_permission_denied))
        }
    }

    private fun launchApkInstallFile(file: File) {
        val ctx = getApplication<Application>()
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            emitMoveFailed(e)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        viewModelScope.launch { intentsFlow.emit(intent) }
    }

    // ---- Open with ----
    override fun openWith(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        val file = item.realFile ?: return
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            emitMoveFailed(e)
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        viewModelScope.launch { intentsFlow.emit(chooser) }
    }

    override fun openFile(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        if (isZipMode()) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val file = item.realFile ?: return
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            emitMoveFailed(e)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        viewModelScope.launch { intentsFlow.emit(intent) }
    }

    override fun showMessage(message: String) {
        emit(message)
    }
}

/** Copies a shared content [Uri] into [targetDir] under its display name. */
private fun saveUriToPath(context: Context, uri: Uri, targetDir: File) {
    val name = getSharedFileName(context, uri) ?: "shared_file_${System.currentTimeMillis()}"
    val targetFile = File(targetDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        targetFile.outputStream().use { out -> input.copyTo(out) }
    }
}

/** Display name for a shared [Uri]: the provider's name when set, else the last path segment. */
private fun getSharedFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) return cursor.getString(nameIndex)
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}
