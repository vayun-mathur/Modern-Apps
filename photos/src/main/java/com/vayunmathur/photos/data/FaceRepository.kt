package com.vayunmathur.photos.data

import android.content.Context
import androidx.room3.withWriteTransaction
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Face clustering reads/writes (persons, photo faces), split out of
 * [PhotosRepository] so neither class exceeds the function-count cap.
 * Owns no database of its own: shares the one [PhotoDatabase] instance via
 * [RoomRepository], touching [FaceDao] (and [PhotoScanDao] for the scanned flag).
 */
class FaceRepository private constructor(context: Context) :
    RoomRepository<PhotoDatabase>(context, PhotoDatabase::class) {

    private val faceDao: FaceDao get() = db.faceDao()
    private val scanDao: PhotoScanDao get() = db.photoScanDao()

    fun personsFlow(): Flow<List<Person>> = faceDao.personsFlow()
    fun faceGeometryFlow(): Flow<List<FaceGeometry>> = faceDao.faceGeometryFlow()
    suspend fun getPersons(): List<Person> = faceDao.getPersons()
    suspend fun clearPersons() = faceDao.clearPersons()
    suspend fun setPersonName(id: Long, name: String?) = faceDao.setPersonName(id, name)
    suspend fun clearPhotoFaces() = faceDao.clearPhotoFaces()
    suspend fun getClusterRows(): List<FaceClusterRow> = faceDao.getClusterRows()
    suspend fun photoIdsForCluster(clusterId: Long): List<Long> = faceDao.photoIdsForCluster(clusterId)

    /**
     * Replace every face's person assignment with one clustering pass's result, in one
     * transaction so the People view never sees a half-applied regrouping.
     *
     * Kept persons only get their centroid and count rewritten, so a name entered during
     * the pass survives; new persons start unnamed.
     */
    suspend fun applyClustering(persons: List<ResolvedPersonRow>, deletedPersonIds: List<Long>) =
        db.withWriteTransaction {
            faceDao.clearClusterAssignments()
            deletedPersonIds.forEach { faceDao.deletePerson(it) }
            for (person in persons) {
                val id = person.personId?.also {
                    faceDao.updateClusterCentroid(it, person.centroid, person.faceIds.size)
                } ?: faceDao.insertPerson(
                    Person(centroid = person.centroid, faceCount = person.faceIds.size, name = null),
                )
                person.faceIds.chunked(ASSIGN_CHUNK).forEach { faceDao.assignFaces(it, id) }
            }
        }

    /**
     * Commit one batch of face scanning atomically: replace the face rows for
     * [photoIds] and mark those photos scanned in the same transaction.
     *
     * All three statements have to land together. If the rows were written but the
     * flag was not (a worker killed mid-batch, which WorkManager does routinely),
     * the next run would re-scan those photos and insert a second set of rows for
     * them. Delete-then-insert also makes a re-scan idempotent on its own.
     */
    suspend fun commitFaceScan(photoIds: List<Long>, faces: List<PhotoFace>) =
        db.withWriteTransaction {
            faceDao.deletePhotoFacesByPhotoIds(photoIds)
            if (faces.isNotEmpty()) faceDao.insertPhotoFaces(faces)
            scanDao.setFaceScanned(photoIds)
        }

    companion object {
        /** Below SQLite's 999 bound-parameter limit, leaving room for the cluster id. */
        private const val ASSIGN_CHUNK = 900

        @Volatile
        private var instance: FaceRepository? = null

        fun get(context: Context): FaceRepository =
            instance ?: synchronized(this) {
                instance ?: FaceRepository(context).also { instance = it }
            }
    }
}

/** One person to write: [personId] null means insert a new row. */
class ResolvedPersonRow(val personId: Long?, val centroid: ByteArray, val faceIds: List<Long>)
