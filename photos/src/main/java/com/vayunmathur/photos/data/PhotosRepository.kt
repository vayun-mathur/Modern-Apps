package com.vayunmathur.photos.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for PhotoDatabase (core photo CRUD, search and
 * progress counts). Indexing reads/writes live in [PhotoScanRepository] and
 * face clustering in [FaceRepository], so no class exceeds the function-count
 * cap. All three share the one [PhotoDatabase] instance (the builder caches by
 * class). Consumers obtain data through here.
 */
open class PhotosRepository internal constructor(context: Context) :
    RoomRepository<PhotoDatabase>(context, PhotoDatabase::class) {

    private val photoDao: PhotoDao get() = db.photoDao()

    // ------------------------------------------------------------------
    // PhotoDao read Flows
    // ------------------------------------------------------------------
    fun getAllFlow(): Flow<List<Photo>> = photoDao.getAllFlow()
    fun getByIdFlow(id: Long): Flow<Photo?> = photoDao.getByIdFlow(id)
    fun getOCRCountFlow(): Flow<Int> = photoDao.getOCRCountFlow()
    fun getClipCountFlow(): Flow<Int> = photoDao.getClipCountFlow()
    fun getFaceScannedCountFlow(): Flow<Int> = photoDao.getFaceScannedCountFlow()

    /** Shared denominator for the OCR, CLIP and face progress bars. */
    fun getIndexTargetCountFlow(): Flow<Int> = photoDao.getIndexTargetCountFlow()

    // ------------------------------------------------------------------
    // PhotoDao suspend wrappers
    // ------------------------------------------------------------------
    open suspend fun getAll(): List<Photo> = photoDao.getAll()
    suspend fun getByUri(uri: String): List<Photo> = photoDao.getByUri(uri)
    open suspend fun upsertAll(photos: List<Photo>) = photoDao.upsertAll(photos)
    open suspend fun delete(value: Photo): Int = photoDao.delete(value)
    open suspend fun deleteByIds(ids: List<Long>) = photoDao.deleteByIds(ids)
    suspend fun setTrashed(id: Long) = photoDao.setTrashed(id)
    suspend fun setAlbum(ids: List<Long>, album: String?) = photoDao.setAlbum(ids, album)
    open suspend fun setFavorite(ids: List<Long>, value: Boolean) = photoDao.setFavorite(ids, value)
    suspend fun searchPhotos(query: String): List<Photo> = photoDao.searchPhotos(query)

    companion object {
        @Volatile
        private var instance: PhotosRepository? = null

        fun get(context: Context): PhotosRepository =
            instance ?: synchronized(this) {
                instance ?: PhotosRepository(context).also { instance = it }
            }
    }
}
