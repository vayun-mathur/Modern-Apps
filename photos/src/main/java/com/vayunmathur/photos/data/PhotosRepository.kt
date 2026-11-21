package com.vayunmathur.photos.data

import android.content.Context
import androidx.room3.withWriteTransaction
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
    private val faceDao: FaceDao get() = db.faceDao()

    // ------------------------------------------------------------------
    // PhotoDao read Flows
    // ------------------------------------------------------------------
    fun getAllFlow(): Flow<List<Photo>> = photoDao.getAllFlow()
    fun getFavoritesFlow(): Flow<List<Photo>> = photoDao.getFavoritesFlow()
    open suspend fun getAllForSync(): List<Photo> = photoDao.getAllForSync()
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
    open suspend fun delete(value: Photo): Int {
        var deleted = 0
        db.withWriteTransaction {
            faceDao.deletePhotoFacesByPhotoIds(listOf(value.id))
            faceDao.deletePersonsWithNoFaces()
            deleted = photoDao.delete(value)
        }
        return deleted
    }

    /**
     * Delete photos and, in the same transaction, every face/embedding row
     * that belonged to them. Without this the PhotoFace rows are orphaned
     * when sync removes a photo MediaStore no longer reports, and every
     * later clustering pass keeps reading (and re-grouping) embeddings of a
     * deleted photo. Persons left with no faces are removed too. Explicit
     * deletes, not a foreign-key cascade, so no schema change is needed
     * for this cleanup.
     */
    open suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.withWriteTransaction {
            faceDao.deletePhotoFacesByPhotoIds(ids)
            faceDao.deletePersonsWithNoFaces()
            photoDao.deleteByIds(ids)
        }
    }
    suspend fun setTrashed(id: Long) = photoDao.setTrashed(id)
    suspend fun setAlbum(ids: List<Long>, album: String?) = photoDao.setAlbum(ids, album)
    open suspend fun setFavorite(ids: List<Long>, value: Boolean) = photoDao.setFavorite(ids, value)
    suspend fun searchPhotos(query: String): List<Photo> = photoDao.searchPhotos(query)

    /** Shared constants and helpers for PhotosRepository. */
    companion object {
        @Volatile
        private var instance: PhotosRepository? = null

        fun get(context: Context): PhotosRepository =
            instance ?: synchronized(this) {
                instance ?: PhotosRepository(context).also { instance = it }
            }
    }
}
