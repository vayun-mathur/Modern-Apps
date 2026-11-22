package com.vayunmathur.photos.platform

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotosRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Narrow store interfaces seams for tests: the production implementations
 * delegate one-to-one to the real repositories (see below); tests supply
 * in-memory fakes. Behaviour-preserving: same members, same dispatch.
 */
interface PhotoStore {
    fun getAllFlow(): Flow<List<Photo>>
    /** DAO-filtered favourites; default keeps test fakes source-compatible. */
    fun getFavoritesFlow(): Flow<List<Photo>> =
        getAllFlow().map { all -> all.filter { it.isFavorite && !it.isTrashed } }
    fun getOCRCountFlow(): Flow<Int>
    fun getIndexTargetCountFlow(): Flow<Int>
    fun getClipCountFlow(): Flow<Int>
    fun getFaceScannedCountFlow(): Flow<Int>

    /**
     * The four counters as one flow. The default combines the individual
     * flows so existing test fakes stay source-compatible; production
     * overrides it with the single DAO query.
     */
    fun getIndexCountsFlow(): Flow<com.vayunmathur.photos.data.IndexCounts> =
        combine(
            getOCRCountFlow(), getClipCountFlow(),
            getFaceScannedCountFlow(), getIndexTargetCountFlow(),
        ) { ocr, clip, face, target ->
            com.vayunmathur.photos.data.IndexCounts(ocr, clip, face, target)
        }
    suspend fun searchPhotos(query: String): List<Photo>
    suspend fun delete(photo: Photo): Int
    suspend fun setTrashed(id: Long)
    suspend fun setAlbum(ids: List<Long>, album: String?)
    suspend fun setFavorite(ids: List<Long>, value: Boolean)
    suspend fun getByUri(uri: String): List<Photo>
    suspend fun getAll(): List<Photo>
}

/** Contract for photo scan store. */
interface PhotoScanStore {
    suspend fun getClipEmbeddings(): List<com.vayunmathur.photos.data.PhotoEmbedding>
}

/** Contract for face store. */
interface FaceStore {
    fun personsFlow(): Flow<List<com.vayunmathur.photos.data.Person>>
    fun faceGeometryFlow(): Flow<List<com.vayunmathur.photos.data.FaceGeometry>>
    suspend fun photoIdsForCluster(clusterId: Long): List<Long>
    suspend fun setPersonName(id: Long, name: String?)
    /** Default no-ops keep existing test fakes source-compatible. */
    suspend fun setPersonHidden(id: Long, hidden: Boolean) {}
    suspend fun deletePersonCluster(id: Long) {}
}

internal class PhotoStoreRepository(
    application: Application,
    val delegate: PhotosRepository = PhotosRepository.get(application),
) : PhotoStore {
    private val repository = delegate
    override fun getAllFlow(): Flow<List<Photo>> = repository.getAllFlow()
    override fun getFavoritesFlow(): Flow<List<Photo>> = repository.getFavoritesFlow()
    override fun getOCRCountFlow(): Flow<Int> = repository.getOCRCountFlow()
    override fun getIndexTargetCountFlow(): Flow<Int> = repository.getIndexTargetCountFlow()
    override fun getClipCountFlow(): Flow<Int> = repository.getClipCountFlow()
    override fun getFaceScannedCountFlow(): Flow<Int> = repository.getFaceScannedCountFlow()
    override fun getIndexCountsFlow(): Flow<com.vayunmathur.photos.data.IndexCounts> =
        repository.getIndexCountsFlow()
    override suspend fun searchPhotos(query: String): List<Photo> = repository.searchPhotos(query)
    override suspend fun delete(photo: Photo): Int = repository.delete(photo)
    override suspend fun setTrashed(id: Long) = repository.setTrashed(id)
    override suspend fun setAlbum(ids: List<Long>, album: String?) = repository.setAlbum(ids, album)
    override suspend fun setFavorite(ids: List<Long>, value: Boolean) = repository.setFavorite(ids, value)
    override suspend fun getByUri(uri: String): List<Photo> = repository.getByUri(uri)
    override suspend fun getAll(): List<Photo> = repository.getAll()
}

internal class PhotoScanStoreRepository(
    application: Application,
    delegate: PhotoScanRepository = PhotoScanRepository.get(application),
) : PhotoScanStore {
    private val scanRepository = delegate
    override suspend fun getClipEmbeddings() = scanRepository.getClipEmbeddings()
}

internal class FaceStoreRepository(
    application: Application,
    delegate: FaceRepository = FaceRepository.get(application),
) : FaceStore {
    private val faceRepository = delegate
    override fun personsFlow(): Flow<List<com.vayunmathur.photos.data.Person>> = faceRepository.personsFlow()
    override fun faceGeometryFlow(): Flow<List<com.vayunmathur.photos.data.FaceGeometry>> =
        faceRepository.faceGeometryFlow()
    override suspend fun photoIdsForCluster(clusterId: Long): List<Long> = faceRepository.photoIdsForCluster(clusterId)
    override suspend fun setPersonName(id: Long, name: String?) = faceRepository.setPersonName(id, name)
    override suspend fun setPersonHidden(id: Long, hidden: Boolean) = faceRepository.setPersonHidden(id, hidden)
    override suspend fun deletePersonCluster(id: Long) = faceRepository.deletePersonCluster(id)
}

/**
 * [WorkManager] is unavailable in unit tests (no initializer); tests set a fake
 * flow here, production leaves it null to read the real thing. Internal, not
 * part of the UI contract.
 */
internal var workInfosOverride: ((Application, String) -> Flow<List<WorkInfo>>)? = null

internal fun workInfosFlow(application: Application, name: String): Flow<List<WorkInfo>> =
    workInfosOverride?.invoke(application, name)
        ?: WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow(name)

/**
 * Sync-enqueue seams for the page binders ([TrashPage][com.vayunmathur.photos.ui.TrashPage],
 * [GalleryPage][com.vayunmathur.photos.ui.GalleryPage]), mirroring [workInfosOverride]:
 * unit tests have no WorkManager initializer, so they set these to no-ops while
 * production leaves them null to run the real sync. Behavior-preserving: the
 * default path is unchanged. Defined here (slice 3/3) rather than in SyncWorker
 * (slice 1/3) so no cross-slice edits are needed.
 */
internal var syncOnceHook: ((android.content.Context) -> Unit)? = null
internal var syncEnqueueHook: ((android.content.Context) -> Unit)? = null

internal fun runSyncOnce(context: android.content.Context) {
    syncOnceHook?.invoke(context) ?: SyncWorker.runOnce(context)
}

internal fun enqueueSyncWork(context: android.content.Context) {
    syncEnqueueHook?.invoke(context) ?: SyncWorker.enqueue(context)
}
