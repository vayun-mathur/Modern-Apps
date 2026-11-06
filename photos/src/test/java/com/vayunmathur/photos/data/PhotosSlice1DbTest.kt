package com.vayunmathur.photos.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.util.ClipEmbedder
import com.vayunmathur.photos.util.FaceRecognizer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO tests for slice 1/3 (`data/workers`): [PhotoDao], [PhotoScanDao] and [FaceDao]
 * against an in-memory [PhotoDatabase].
 *
 * These pin the worker-facing contracts: the unscanned-photo projections that drive
 * OCR/CLIP/face indexing, the column-targeted scan writes (which must not clobber
 * sibling columns), and the cluster merge/name-preservation semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1DbTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: PhotoDatabase = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val photoDao = db.photoDao()
    private val scanDao = db.photoScanDao()
    private val faceDao = db.faceDao()

    @AfterTest
    fun closeDb() {
        db.close()
    }

    private fun photo(
        name: String,
        date: Long = 1_000L,
        trashed: Boolean = false,
        videoDuration: Long? = null,
    ) = Photo(
        name = name,
        uri = "content://media/$name",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = true,
        lat = null,
        long = null,
        videoData = videoDuration?.let { VideoData(it) },
        panoData = null,
        isTrashed = trashed,
    )

    // ---- PhotoDao ----

    @Test
    fun upsertAndGetAllOrdersNewestFirst() = runTest {
        photoDao.upsertAll(listOf(photo("old", date = 100L), photo("new", date = 900L)))
        val all = photoDao.getAllFlow().first()
        assertEquals(listOf("new", "old"), all.map { it.name })
    }

    @Test
    fun getByUriFindsRow() = runTest {
        photoDao.upsertAll(listOf(photo("a"), photo("b")))
        val found = photoDao.getByUri("content://media/b")
        assertEquals(1, found.size)
        assertEquals("b", found.single().name)
    }

    @Test
    fun deleteRemovesRow() = runTest {
        photoDao.upsertAll(listOf(photo("gone")))
        val row = photoDao.getAll().single()
        assertEquals(1, photoDao.delete(row))
        assertTrue(photoDao.getAll().isEmpty())
    }

    @Test
    fun setTrashedHidesFromSearchButKeepsRow() = runTest {
        photoDao.upsertAll(listOf(photo("pic", date = 5L)))
        val id = photoDao.getAll().single().id
        photoDao.setTrashed(id)
        assertEquals(1, photoDao.getAll().size)
        assertTrue(photoDao.searchPhotos("pic").isEmpty())
    }

    @Test
    fun searchMatchesNameSubstring() = runTest {
        photoDao.upsertAll(
            listOf(
                photo("beach sunset", date = 3L),
                photo("mountain", date = 2L),
            ),
        )
        assertEquals(listOf("beach sunset"), photoDao.searchPhotos("beach").map { it.name })
    }

    @Test
    fun searchMatchesOcrText() = runTest {
        photoDao.upsertAll(listOf(photo("sign", date = 3L)))
        val id = photoDao.getAll().single().id
        scanDao.setOcrResult(id, "hello world", null)
        assertEquals(listOf("sign"), photoDao.searchPhotos("hello").map { it.name })
    }

    @Test
    fun setAlbumRetargetsBucket() = runTest {
        photoDao.upsertAll(listOf(photo("p", date = 1L)))
        val id = photoDao.getAll().single().id
        photoDao.setAlbum(listOf(id), "Camera")
        assertEquals("Camera", photoDao.getAll().single().album)
    }

    @Test
    fun deleteByIdsRemovesBatch() = runTest {
        photoDao.upsertAll(listOf(photo("a", date = 1L), photo("b", date = 2L), photo("c", date = 3L)))
        val ids = photoDao.getAll().map { it.id }
        photoDao.deleteByIds(ids.take(2))
        assertEquals(listOf("c"), photoDao.getAll().map { it.name })
    }

    // ---- PhotoScanDao scan queues ----

    @Test
    fun unscannedQueuesSkipVideosAndTrashed() = runTest {
        photoDao.upsertAll(
            listOf(
                photo("still", date = 1L),
                photo("clip", date = 2L, videoDuration = 5_000L),
                photo("trashed", date = 3L, trashed = true),
            ),
        )
        assertEquals(listOf("still"), scanDao.getUnscannedForOCR().map { it.uri.substringAfterLast("/") })
        assertEquals(1, scanDao.getUnscannedForFaces().size)
        assertEquals(1, scanDao.getUnscannedForClip().size)
    }

    @Test
    fun setOcrScannedDrainsQueue() = runTest {
        photoDao.upsertAll(listOf(photo("a", date = 1L), photo("b", date = 2L)))
        val ids = photoDao.getAll().map { it.id }
        scanDao.setOcrScanned(listOf(ids.first()))
        assertEquals(1, scanDao.getUnscannedForOCR().size)
    }

    @Test
    fun setClipResultStoresEmbeddingAndMarksScanned() = runTest {
        photoDao.upsertAll(listOf(photo("a", date = 1L)))
        val id = photoDao.getAll().single().id
        val embedding = ClipEmbedder.floatsToBytes(FloatArray(8) { it.toFloat() })
        scanDao.setClipResult(id, embedding)
        val rows = scanDao.getClipEmbeddings()
        assertEquals(1, rows.size)
        assertContentEquals(embedding, rows.single().clipEmbedding)
        assertTrue(scanDao.getUnscannedForClip().isEmpty())
    }

    @Test
    fun resetClipScannedWipesEmbeddings() = runTest {
        photoDao.upsertAll(listOf(photo("a", date = 1L)))
        val id = photoDao.getAll().single().id
        scanDao.setClipResult(id, ByteArray(32))
        scanDao.resetClipScanned()
        assertTrue(scanDao.getClipEmbeddings().isEmpty())
        assertEquals(1, scanDao.getUnscannedForClip().size)
    }

    @Test
    fun countFlowsTrackIndexingProgress() = runTest {
        photoDao.upsertAll(
            listOf(
                photo("a", date = 1L),
                photo("b", date = 2L),
                photo("vid", date = 3L, videoDuration = 1_000L),
            ),
        )
        // Two stills count toward indexing; the video does not.
        assertEquals(2, photoDao.getIndexTargetCountFlow().first())
        val ids = photoDao.getAll().filter { it.videoData == null }.map { it.id }
        scanDao.setOcrScanned(ids)
        assertEquals(2, photoDao.getOCRCountFlow().first())
        scanDao.setFaceScanned(ids)
        assertEquals(2, photoDao.getFaceScannedCountFlow().first())
    }

    @Test
    fun exifWritePersistsColumns() = runTest {
        photoDao.upsertAll(listOf(photo("a", date = 1L)))
        val id = photoDao.getAll().single().id
        scanDao.setExif(
            id = id,
            lat = 37.4,
            long = -122.1,
            fullWidth = 8000,
            fullHeight = 4000,
            croppedWidth = 8000,
            croppedHeight = 4000,
            croppedLeft = 0,
            croppedTop = 0,
            projectionType = "equirectangular",
        )
        val row = photoDao.getAll().single()
        assertEquals(37.4, row.lat)
        assertEquals("equirectangular", row.panoData?.projectionType)
        assertTrue(scanDao.getUnscannedForExif().isEmpty())
    }

    @Test
    fun ocrBoxesRoundTrip() = runTest {
        photoDao.upsertAll(listOf(photo("a", date = 1L)))
        val id = photoDao.getAll().single().id
        assertNull(scanDao.getOcrBoxes(id))
        scanDao.setOcrBoxes(id, """{"lines":[]}""")
        assertEquals("""{"lines":[]}""", scanDao.getOcrBoxes(id))
    }

    @Test
    fun stillPhotoUrisExcludeVideosAndTrashed() = runTest {
        photoDao.upsertAll(
            listOf(
                photo("still", date = 1L),
                photo("vid", date = 2L, videoDuration = 1_000L),
                photo("trashed", date = 3L, trashed = true),
            ),
        )
        assertEquals(listOf("content://media/still"), scanDao.getStillPhotoUris())
    }

    // ---- FaceDao clustering ----

    private fun centroid(vararg values: Float) =
        FaceRecognizer.floatsToBytes(floatArrayOf(*values))

    @Test
    fun insertAndListPersons() = runTest {
        val id = faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 1, name = null))
        assertTrue(id > 0)
        val persons = faceDao.getPersons()
        assertEquals(1, persons.size)
        assertEquals(1, persons.single().faceCount)
        assertNull(persons.single().name)
    }

    @Test
    fun setPersonNamePersists() = runTest {
        val id = faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 1, name = null))
        faceDao.setPersonName(id, "Ada")
        assertEquals("Ada", faceDao.getPersons().single().name)
        faceDao.setPersonName(id, null)
        assertNull(faceDao.getPersons().single().name)
    }

    @Test
    fun updateClusterCentroidPreservesName() = runTest {
        val id = faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 1, name = "Ada"))
        faceDao.updateClusterCentroid(id, centroid(0.9f, 0.1f), 2)
        val row = faceDao.getPersons().single()
        assertEquals(2, row.faceCount)
        assertEquals("Ada", row.name)
        assertContentEquals(centroid(0.9f, 0.1f), row.centroid)
    }

    @Test
    fun mergeClusterKeepsSurvivorNameAndFallsBack() = runTest {
        val survivor = faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 2, name = "Ada"))
        val merged = Person(centroid = centroid(0.9f, 0.1f), faceCount = 3, name = null)
        faceDao.mergeClusterInto(survivor, merged.centroid, merged.faceCount, fallbackName = "Bo")
        // Survivor already named: COALESCE keeps "Ada", not the fallback.
        assertEquals("Ada", faceDao.getPersons().single().name)

        val unnamed = faceDao.insertPerson(Person(centroid = centroid(0f, 1f), faceCount = 1, name = null))
        faceDao.mergeClusterInto(unnamed, centroid(0f, 1f), 2, fallbackName = "Bo")
        assertEquals("Bo", faceDao.getPersons().first { it.id == unnamed }.name)
    }

    @Test
    fun photoFacesReassignAndClear() = runTest {
        val a = faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 1, name = null))
        val b = faceDao.insertPerson(Person(centroid = centroid(0f, 1f), faceCount = 1, name = null))
        fun face(cluster: Long, pid: Long) = PhotoFace(
            photoId = pid, clusterId = cluster, embedding = ByteArray(8),
            left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 100,
        )
        faceDao.insertPhotoFaces(listOf(face(a, 10L), face(b, 11L)))
        faceDao.reassignCluster(b, a)
        assertEquals(setOf(10L, 11L), faceDao.photoIdsForCluster(a).toSet())
        assertTrue(faceDao.photoIdsForCluster(b).isEmpty())
        faceDao.deletePhotoFacesByPhotoIds(listOf(10L))
        assertEquals(listOf(11L), faceDao.photoIdsForCluster(a))
        faceDao.clearPhotoFaces()
        assertTrue(faceDao.photoIdsForCluster(a).isEmpty())
    }

    @Test
    fun deletePersonRemovesCluster() = runTest {
        val id = faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 1, name = null))
        faceDao.deletePerson(id)
        assertTrue(faceDao.getPersons().isEmpty())
        faceDao.clearPersons() // no-op safe on empty table
    }

    @Test
    fun personsFlowEmitsInserts() = runTest {
        faceDao.insertPerson(Person(centroid = centroid(1f, 0f), faceCount = 1, name = "Ada"))
        val persons = faceDao.personsFlow().first()
        assertEquals(listOf("Ada"), persons.map { it.name })
        assertNotNull(faceDao.faceGeometryFlow().first())
    }
}
