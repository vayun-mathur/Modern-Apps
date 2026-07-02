package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A face template computed from a contact's photo. One row per contact that has
 * a usable photo. [embedding] is a small grayscale face template (see
 * [com.vayunmathur.photos.util.FaceRecognizer]) packed as bytes.
 */
@Entity
data class ContactFace(
    @PrimaryKey val contactKey: String,
    val name: String,
    val embedding: ByteArray,
    val photoUri: String,
)

/**
 * A face detected in a library photo. [contactName] is filled in when the face
 * matched a [ContactFace]; null means "detected but not recognised". The
 * [embedding] is kept so faces can be re-matched if contacts change without
 * re-scanning the original photo.
 */
@Entity(indices = [Index(value = ["photoId"])])
data class PhotoFace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: Long,
    val embedding: ByteArray,
    val contactKey: String?,
    val contactName: String?,
)

@Dao
interface FaceDao {
    @Upsert
    suspend fun upsertContactFaces(faces: List<ContactFace>)

    @Query("SELECT * FROM ContactFace")
    suspend fun getContactFaces(): List<ContactFace>

    @Query("SELECT count(*) FROM ContactFace")
    suspend fun contactFaceCount(): Int

    @Query("DELETE FROM ContactFace")
    suspend fun clearContactFaces()

    @Insert
    suspend fun insertPhotoFaces(faces: List<PhotoFace>)

    @Query("DELETE FROM PhotoFace WHERE photoId IN (:photoIds)")
    suspend fun deletePhotoFacesByPhotoIds(photoIds: List<Long>)

    @Query("DELETE FROM PhotoFace")
    suspend fun clearPhotoFaces()

    /** All faces that were matched to a contact (drives the People view + overlays). */
    @Query("SELECT * FROM PhotoFace WHERE contactName IS NOT NULL")
    fun matchedFacesFlow(): Flow<List<PhotoFace>>

    @Query("SELECT count(*) FROM PhotoFace WHERE contactName IS NOT NULL")
    fun matchedFaceCountFlow(): Flow<Int>
}
