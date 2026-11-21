package com.vayunmathur.files.platform

import android.app.Application
import android.net.Uri
import android.os.StatFs
import android.text.format.Formatter
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Migrated from okio FileSystem/Path/openZip to java.io.File + java.util.zip.ZipFile.
 * Real FS uses File; zip browsing is virtual backed by ZipFile entries.
 */
data class FileBrowserItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val realFile: File?,
    val zipInnerPath: String?,
    val key: String,
    val lastModified: Long = 0L,
    /**
     * MediaStore content URI, set only for items listed from the system trash. Lets the
     * trash screen restore / delete them without re-resolving the path.
     */
    val mediaUri: Uri? = null,
)

/**
 * The file-browser view model: one inheritance chain (core → browse → edit → share →
 * home → trash → this class) so every layer sees one `this`. This class itself only
 * wires the chain together — loads the first listing and observer — and implements the
 * top-level [FilesActions]: everything else is delegated to a layer by construction.
 */
class FilesViewModel(application: Application) : FilesTrashModel(application), FilesActions {

    init {
        loadDirectory()
        restartObserver()
        loadHome()
        startSearchPipeline()
    }

    override fun unzip(zipItem: FileBrowserItem, destPath: File) {
        val zipFile = zipItem.realFile ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            unzipTo(zipFile, destPath, ctx)
        }
    }

    private suspend fun unzipTo(zipFile: File, destPath: File, ctx: Application) {
        val budget = ArchiveLimits.extractionBudget(freeBytesOf(destPath))

        // What the archive says it expands to. Turning an oversized one away here means the user
        // finds out before anything is written; UnzipWorker still enforces the budget itself,
        // because these figures come from the archive and a crafted one can under-report.
        val declared = declaredSizeOf(zipFile)
        if (declared != null && declared > budget) {
            emitOversizedArchive(ctx, declared, budget)
            return
        }

        val unzipWork = OneTimeWorkRequestBuilder<UnzipWorker>().setInputData(
            workDataOf(
                "zip_path" to zipFile.absolutePath,
                "dest_path" to destPath.absolutePath,
                "size_budget" to budget,
            )
        ).build()
        WorkManager.getInstance(ctx).enqueue(unzipWork)
        clearSelection()
        emit(ctx.getString(R.string.unzipping_started_to, destPath.name))
    }

    private fun freeBytesOf(destPath: File): Long = runCatching {
        StatFs(destPath.absolutePath).availableBytes
    }.getOrDefault(0L)

    private fun declaredSizeOf(zipFile: File): Long? = runCatching {
        ZipFile(zipFile).use { zf ->
            val zipEntries = zf.entries().asSequence().toList()
            if (zipEntries.size > ArchiveLimits.MAX_ENTRIES) {
                return@runCatching Long.MAX_VALUE
            }
            ArchiveLimits.declaredUncompressedSize(zipEntries.asSequence().map { it.size })
        }
    }.getOrNull()

    private fun emitOversizedArchive(ctx: Application, declared: Long, budget: Long) {
        emit(
            ctx.getString(
                R.string.zip_too_large_to_extract,
                Formatter.formatShortFileSize(ctx, declared),
                Formatter.formatShortFileSize(ctx, budget),
            )
        )
    }

    /** Called by the UI after returning from the "install unknown apps" settings screen. */
    fun onInstallPermissionResult() = onApkInstallPermissionResult()

    /**
     * Checks the archive can be read and, if so, reports it via [zipOpened] for the navigation
     * layer to act on. Deliberately changes no state: a corrupt archive should leave the user
     * where they are, with a message, rather than navigate into a listing that cannot load.
     */
    internal fun openZipChecked(zipFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipFile(zipFile).use { _ -> }
                zipOpenedState.emit(zipFile)
            } catch (e: IOException) {
                emit(
                    getApplication<Application>().getString(
                        R.string.could_not_open_zip,
                        e.localizedMessage
                    )
                )
            }
        }
    }
}
