package com.vayunmathur.photos.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 1/3 second pass: [FaceDao] clustering persistence semantics against an
 * in-memory [PhotoDatabase] (the repository layer needs SQLCipher's keystore,
 * so the DAO contract it delegates to is pinned here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1Dao2Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: PhotoDatabase = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val faceDao = db.faceDao()

    @AfterTest
    fun closeDb() {
        db.close()
    }

    private fun face(
        photoId: Long,
        clusterId: Long? = null,
        clusterable: Boolean = true,
        embedding: ByteArray = byteArrayOf(1, 2, 3),
    ) = PhotoFace(
        photoId = photoId, clusterId = clusterId, embedding = embedding,
        clusterable = clusterable,
        left = 0.1f, top = 0.2f, right = 0.5f, bottom = 0.6f,
        srcWidth = 1000, srcHeight = 800,
    )

    @Test
    fun personInsertRenameAndCentroidUpdatePreservesName() = runTest {
        val id = faceDao.insertPerson(Person(centroid = byteArrayOf(9), faceCount = 3, name = null))
        assertTrue(id > 0)
        faceDao.setPersonName(id, "Ada")
        // The clustering-owned update must not clobber the user's name.
        faceDao.updateClusterCentroid(id, byteArrayOf(7, 7), faceCount = 5)
        val person = faceDao.getPersons().single()
        assertEquals("Ada", person.name)
        assertEquals(5, person.faceCount)
        assertTrue(byteArrayOf(7, 7).contentEquals(person.centroid))
        faceDao.setPersonName(id, null)
        assertEquals(null, faceDao.getPersons().single().name)
    }

    @Test
    fun clusterAssignmentLifecycle() = runTest {
        val ada = faceDao.insertPerson(Person(centroid = byteArrayOf(1), faceCount = 0, name = "Ada"))
        val grace = faceDao.insertPerson(Person(centroid = byteArrayOf(2), faceCount = 0, name = "Grace"))
        faceDao.insertPhotoFaces(
            listOf(face(photoId = 10), face(photoId = 10), face(photoId = 11), face(photoId = 12, clusterable = false)),
        )
        val rows = faceDao.getClusterRows()
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.clusterId == null })
        assertEquals(1, rows.count { !it.clusterable })

        faceDao.assignFaces(rows.map { it.id }.take(2), ada)
        faceDao.assignFaces(listOf(rows[2].id), grace)
        assertEquals(listOf(10L), faceDao.photoIdsForCluster(ada))
        assertEquals(listOf(11L), faceDao.photoIdsForCluster(grace))
        assertEquals(emptyList(), faceDao.photoIdsForCluster(999L))

        // A fresh pass clears assignments without deleting faces or persons.
        faceDao.clearClusterAssignments()
        assertTrue(faceDao.getClusterRows().all { it.clusterId == null })
        assertEquals(2, faceDao.getPersons().size)

        // Geometry flow excludes embeddings but keeps boxes and cluster ids.
        faceDao.assignFaces(listOf(rows[0].id), ada)
        val geometry = faceDao.faceGeometryFlow().first()
        assertEquals(4, geometry.size)
        assertEquals(ada, geometry.first { it.photoId == 10L }.clusterId)
        assertEquals(1000, geometry.first().srcWidth)

        assertEquals(2, faceDao.personsFlow().first().size)
    }

    @Test
    fun deleteFacesByPhotoAndClearAll() = runTest {
        faceDao.insertPhotoFaces(listOf(face(photoId = 1), face(photoId = 1), face(photoId = 2)))
        faceDao.deletePhotoFacesByPhotoIds(listOf(1L))
        assertEquals(1, faceDao.getClusterRows().size)
        faceDao.clearPhotoFaces()
        assertTrue(faceDao.getClusterRows().isEmpty())

        val id = faceDao.insertPerson(Person(centroid = byteArrayOf(3), faceCount = 1, name = null))
        faceDao.deletePerson(id)
        assertTrue(faceDao.getPersons().isEmpty())
        faceDao.insertPerson(Person(centroid = byteArrayOf(4), faceCount = 1, name = null))
        faceDao.clearPersons()
        assertTrue(faceDao.getPersons().isEmpty())
    }

    @Test
    fun scanDaoStillPhotoUrisAndOcrBoxesRoundTrip() = runTest {
        val photoDao = db.photoDao()
        val scanDao = db.photoScanDao()
        photoDao.upsertAll(
            listOf(
                Photo(
                    name = "still", uri = "content://media/still", date = 5L, width = 10, height = 10,
                    dateModified = 5L, exifSet = false, lat = null, long = null, videoData = null,
                    panoData = null, isTrashed = false,
                ),
                Photo(
                    name = "clip", uri = "content://media/clip", date = 6L, width = 10, height = 10,
                    dateModified = 6L, exifSet = false, lat = null, long = null,
                    videoData = VideoData(1200L), panoData = null, isTrashed = false,
                ),
            )
        )
        // Still-photo URIs exclude videos (duration IS NULL predicate).
        assertEquals(listOf("content://media/still"), scanDao.getStillPhotoUris())
        val stillId = photoDao.getByUri("content://media/still").single().id
        assertEquals(null, scanDao.getOcrBoxes(stillId))
        scanDao.setOcrBoxes(stillId, """{"lines":[]}""")
        assertEquals("""{"lines":[]}""", scanDao.getOcrBoxes(stillId))
        scanDao.setOcrBoxes(stillId, null)
        assertEquals(null, scanDao.getOcrBoxes(stillId))
    }
}
