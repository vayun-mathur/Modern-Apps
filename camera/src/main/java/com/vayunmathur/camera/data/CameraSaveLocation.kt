package com.vayunmathur.camera.data

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.vayunmathur.camera.platform.CameraViewModel

/** Where new captures are saved. */
sealed interface SaveTarget {
    /** Default: MediaStore `DCIM/Camera` via [MediaStoreSaver]. */
    data object MediaStoreDefault : SaveTarget

    /** User-chosen SAF tree; stills/video are created as documents inside it. */
    data class SafTree(val treeUri: Uri) : SaveTarget
}

/** DataStore keys for the save-location setting. */
private const val KEY_SAVE_MODE = "camera_save_mode"
private const val KEY_SAVE_TREE_URI = "camera_save_tree_uri"
private const val MODE_MEDIA_STORE = "media_store"
private const val MODE_SAF = "saf"

/**
 * Loads the persisted save target, revalidating the SAF grant: if the stored
 * tree URI lost its persisted read+write permission (revoked externally), falls
 * back to [SaveTarget.MediaStoreDefault] and clears the stale keys.
 */
internal fun CameraViewModel.loadSaveTarget() {
    if (ds.getString(KEY_SAVE_MODE) != MODE_SAF) {
        saveTargetMutable.value = SaveTarget.MediaStoreDefault
        return
    }
    val uriString = ds.getString(KEY_SAVE_TREE_URI)
    val uri = uriString?.takeIf { it.isNotBlank() }?.let { runCatching { it.toUri() }.getOrNull() }
    if (uri != null && SafDocuments.hasPersistedPermission(app.contentResolver, uri)) {
        saveTargetMutable.value = SaveTarget.SafTree(uri)
    } else {
        Log.status("CameraViewModel", "SAF save folder grant missing; falling back to DCIM/Camera")
        saveTargetMutable.value = SaveTarget.MediaStoreDefault
        viewModelScope.launch {
            ds.setString(KEY_SAVE_MODE, MODE_MEDIA_STORE)
            ds.setString(KEY_SAVE_TREE_URI, "")
        }
    }
}

/** Persists a newly picked SAF tree (permission must already be taken by the caller). */
fun CameraViewModel.setSaveTreeUri(uri: Uri) {
    saveTargetMutable.value = SaveTarget.SafTree(uri)
    viewModelScope.launch {
        ds.setString(KEY_SAVE_TREE_URI, uri.toString())
        ds.setString(KEY_SAVE_MODE, MODE_SAF)
    }
}

/** Clears the SAF folder and returns to the MediaStore default. */
fun CameraViewModel.clearSaveTreeUri() {
    saveTargetMutable.value = SaveTarget.MediaStoreDefault
    viewModelScope.launch {
        ds.setString(KEY_SAVE_MODE, MODE_MEDIA_STORE)
        ds.setString(KEY_SAVE_TREE_URI, "")
    }
}

/** DocumentFile-free SAF helpers built on DocumentsContract + ContentResolver. */
object SafDocuments {
    fun hasPersistedPermission(resolver: android.content.ContentResolver, treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    fun takePersistablePermission(resolver: android.content.ContentResolver, treeUri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.status("SafDocuments", "takePersistableUriPermission failed for $treeUri", e)
        }
    }

    fun createImageDoc(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        displayName: String,
    ): Uri? = createDoc(resolver, treeUri, "image/jpeg", displayName)

    fun createVideoDoc(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        displayName: String,
    ): Uri? = createDoc(resolver, treeUri, "video/mp4", displayName)

    private fun createDoc(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        mimeType: String,
        displayName: String,
    ): Uri? = try {
        DocumentsContract.createDocument(resolver, treeUri, mimeType, displayName)
    } catch (e: IllegalArgumentException) {
        Log.status("SafDocuments", "createDocument failed for $displayName", e)
        null
    } catch (e: java.io.FileNotFoundException) {
        Log.status("SafDocuments", "createDocument failed for $displayName", e)
        null
    }

    /** Human-readable folder name for the settings row, e.g. "TestCam". */
    fun displayName(resolver: android.content.ContentResolver, treeUri: Uri): String? = try {
        resolver.query(treeUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: SecurityException) {
        Log.status("SafDocuments", "displayName query failed for $treeUri", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.status("SafDocuments", "displayName query failed for $treeUri", e)
        null
    }

    /**
     * Best-effort absolute path when the tree lives on primary external storage
     * (e.g. `/storage/emulated/0/Pictures/TestCam`), for media-scan indexing.
     * Null for exotic providers (SD card via SAF, cloud) — callers skip the scan.
     */
    fun primaryPath(treeUri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        // ExternalStorageProvider ids look like "primary:Pictures/TestCam".
        val (volume, path) = docId.split(":", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else return null
        }
        if (volume != "primary") return null
        val clean = path.trim('/').replace(Regex("[^A-Za-z0-9 _./-]"), "_")
        if (clean.isEmpty() || clean.contains("..")) return null
        return Environment.getExternalStorageDirectory().absolutePath + "/" + clean
    }
}
