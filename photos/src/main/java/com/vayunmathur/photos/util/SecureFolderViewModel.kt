package com.vayunmathur.photos.util

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.migration.Migration
import androidx.core.net.toUri
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.VaultDatabase
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Secure Folder (encrypted vault) feature.
 *
 * Owns:
 *  - vault biometric unlock + lazy [VaultPhotoDao] creation
 *  - the observable list of [VaultPhoto]s (DAO Flow, switched on unlock)
 *  - decrypted-thumbnail bitmap cache (LRU, bounded)
 *  - encrypt/move and decrypt/restore operations off the main thread
 *
 * Bitmaps are recycled in [onCleared] to release native memory promptly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureFolderViewModel(application: Application) : AndroidViewModel(application) {

    private val _vaultPhotoDao = MutableStateFlow<VaultPhotoDao?>(null)
    val vaultPhotoDao: StateFlow<VaultPhotoDao?> = _vaultPhotoDao.asStateFlow()

    private val _vaultPassword = MutableStateFlow<String?>(null)
    val vaultPassword: StateFlow<String?> = _vaultPassword.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val photos: StateFlow<List<VaultPhoto>> = _vaultPhotoDao
        .flatMapLatest { dao -> dao?.getAllFlow() ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val sfm: SecureFolderManager by lazy { SecureFolderManager(application) }

    // Bounded LRU cache for decrypted thumbnails. Cap 32 to prevent unbounded
    // bitmap retention while scrolling large vaults. Eldest entries are recycled
    // synchronously on eviction.
    private val thumbCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size > 32) {
                try { eldest.value.recycle() } catch (e: Exception) { Log.w(TAG, "Failed to recycle evicted thumbnail", e) }
                return true
            }
            return false
        }
    }

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    fun setVault(dao: VaultPhotoDao, password: String) {
        _vaultPassword.value = password
        _vaultPhotoDao.value = dao
    }

    fun unlock(
        activity: FragmentActivity,
        onSuccess: (VaultPhotoDao, String) -> Unit = { _, _ -> },
        onFailure: () -> Unit = {},
    ) {
        val existingDao = _vaultPhotoDao.value
        if (existingDao != null) {
            onSuccess(existingDao, _vaultPassword.value!!)
            return
        }
        unlockDatabaseWithBiometrics(
            activity,
            onSuccess = { password ->
                val db = activity.buildDatabase<VaultDatabase>(emptyList<Migration>(), password, "vault-db")
                val dao = db.vaultPhotoDao()
                setVault(dao, password)
                onSuccess(dao, password)
            },
            onFailure = onFailure,
        )
    }

    /**
     * Decrypt a single thumbnail and publish into [thumbnails]. Composables read
     * `thumbnails.collectAsState()` and look up by path. Cached results return
     * immediately without re-decrypting.
     */
    fun requestThumbnail(thumbnailPath: String, password: String) {
        synchronized(thumbCache) {
            thumbCache[thumbnailPath]?.let { cached ->
                if (_thumbnails.value[thumbnailPath] !== cached) {
                    _thumbnails.update { it + (thumbnailPath to cached) }
                }
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = sfm.decryptThumbnail(thumbnailPath, password) ?: return@launch
                synchronized(thumbCache) {
                    val existing = thumbCache[thumbnailPath]
                    if (existing != null) {
                        try { bmp.recycle() } catch (e: Exception) { Log.w(TAG, "Failed to recycle duplicate thumbnail", e) }
                    } else {
                        thumbCache[thumbnailPath] = bmp
                    }
                }
                _thumbnails.update { current ->
                    current + (thumbnailPath to synchronized(thumbCache) { thumbCache[thumbnailPath]!! })
                }
            } catch (e: Exception) {
                Log.e(TAG, "decryptThumbnail failed for $thumbnailPath", e)
            }
        }
    }

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun addSelection(id: Long) {
        _selectedIds.update { it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * Restore a list of vault photos back to the MediaStore and delete the
     * matching VaultPhoto rows. Errors per photo are swallowed (mirrors
     * the existing UI behaviour).
     */
    fun restorePhotos(photos: List<VaultPhoto>) {
        if (photos.isEmpty()) return
        val vaultDao = _vaultPhotoDao.value ?: return
        val password = _vaultPassword.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                photos.forEach { photo ->
                    val restored = sfm.decryptAndRestore(photo, password)
                    if (restored != null) {
                        vaultDao.delete(photo)
                    }
                }
                clearSelection()
            } catch (e: Exception) {
                Log.e(TAG, "restorePhotos failed", e)
            }
        }
    }

    /**
     * Encrypt and move [photos] into the vault. Returns the original MediaStore
     * URIs through [onSuccess] so the caller can issue the MediaStore delete
     * request (the only step that must run on the activity).
     */
    fun moveToSecure(
        photos: List<Photo>,
        sourcePhotoDao: PhotoDao,
        onSuccess: (List<android.net.Uri>) -> Unit,
    ) {
        if (photos.isEmpty()) return
        val vaultDao = _vaultPhotoDao.value ?: return
        val password = _vaultPassword.value ?: return
        viewModelScope.launch {
            val urisToDelete = withContext(Dispatchers.IO) {
                val collected = mutableListOf<android.net.Uri>()
                photos.forEach { photo ->
                    try {
                        val (path, thumbPath) = sfm.encryptAndMove(
                            photo.uri.toUri(),
                            photo.name,
                            password,
                        )
                        vaultDao.upsert(
                            VaultPhoto(
                                name = photo.name,
                                path = path,
                                thumbnailPath = thumbPath,
                                date = photo.date,
                                width = photo.width,
                                height = photo.height,
                                dateModified = photo.dateModified,
                                videoDuration = photo.videoData?.duration,
                            )
                        )
                        collected.add(photo.uri.toUri())
                        sourcePhotoDao.delete(photo)
                    } catch (e: Exception) {
                        Log.e(TAG, "encryptAndMove failed for ${photo.uri}", e)
                    }
                }
                collected
            }
            if (urisToDelete.isNotEmpty()) {
                onSuccess(urisToDelete)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(thumbCache) {
            thumbCache.values.forEach { bmp ->
                try { if (!bmp.isRecycled) bmp.recycle() } catch (e: Exception) { Log.w(TAG, "Failed to recycle thumbnail on clear", e) }
            }
            thumbCache.clear()
        }
        _thumbnails.value = emptyMap()
    }

    companion object {
        private const val TAG = "SecureFolderViewModel"
    }
}

@Suppress("FunctionName")
fun SecureFolderViewModelFactory(application: Application): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { SecureFolderViewModel(application) }
    }
