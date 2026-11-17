package com.vayunmathur.photos.data
import com.vayunmathur.photos.platform.FaceRecognizer

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real [PhotosRepository], [PhotoScanRepository], [FaceRepository] and
 * [VaultRepository] over in-memory Room (see [TestPhotosRepository]).
 *
 * Earlier suites tested the DAOs directly or subclassed the repositories
 * with fakes, so the repository layer itself — including the methods that
 * are not `open` and the final [FaceRepository]'s clustering transactions —
 * never executed. Here every call runs the production wrapper and its DAO
 * SQL, and results are asserted in the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosRepositoriesRealDbTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var installed: TestPhotosRepository.Installed
    private val photos get() = installed.photos
    private val scan get() = installed.scan
    private val faces get() = installed.faces

    @BeforeTest fun open() { installed = TestPhotosRepository.install(context) }

    private fun photo(
        name: String,
        date: Long = 1_000L,
        trashed: Boolean = false,
        video: Boolean = false,
        album: String? = null,
        favorite: Boolean = false,
        mime: String? = "image/jpeg",
    ) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 80,
        dateModified = date, exifSet = false, lat = null, long = null,
        videoData = if (video) VideoData(5_000L) else null, panoData = null,
        isTrashed = trashed, album = album, isFavorite = favorite, mimeType = mime,
    )

    private fun face(photoId: Long, embedding: FloatArray, clusterable: Boolean = true) = PhotoFace(
        photoId = photoId, clusterId = null,
        embedding = com.vayunmathur.photos.platform.FaceRecognizer.floatsToBytes(embedding),
        clusterable = clusterable,
        left = 0.1f, top = 0.1f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 80,
    )

    private fun floats(vararg values: Float) = FloatArray(4) { values.getOrElse(it) { 0f } }

    // ---- PhotosRepository ----

    @Test fun photoCrudThroughRepository() = runTest {
        photos.upsertAll(listOf(photo("old", date = 100L), photo("new", date = 900L), photo("mid", date = 500L)))
        assertEquals(listOf("new", "mid", "old"), photos.getAllFlow().first().map { it.name })
        assertEquals(setOf("new", "mid", "old"), photos.getAll().map { it.name }.toSet())
        val newId = photos.getAll().first { it.name == "new" }.id
        assertEquals("new", photos.getByIdFlow(newId).first()?.name)
        assertEquals(1, photos.getByUri("content://media/mid").size)
        photos.setTrashed(newId)
        assertTrue(photos.getAll().first { it.id == newId }.isTrashed)
        assertEquals(1, photos.delete(photos.getAll().first { it.name == "old" }))
        photos.deleteByIds(listOf(photos.getAll().first { it.name == "mid" }.id))
        assertEquals(listOf("new"), photos.getAll().map { it.name })
    }

    @Test fun albumFavoriteAndSearchThroughRepository() = runTest {
        photos.upsertAll(listOf(photo("beach", date = 3L), photo("mountain", date = 2L)))
        val ids = photos.getAll().map { it.id }
        photos.setAlbum(ids, "Camera")
        assertEquals(setOf("Camera"), photos.getAll().map { it.album }.toSet())
        photos.setAlbum(ids, null)
        assertEquals(setOf<String?>(null), photos.getAll().map { it.album }.toSet())
        photos.setFavorite(listOf(ids.first()), true)
        assertEquals(1, photos.getAll().count { it.isFavorite })
        assertEquals(listOf("beach"), photos.searchPhotos("beach").map { it.name })
        scan.setOcrResults(listOf(OcrResult(ids.last(), "hello sign", null)))
        assertEquals(listOf("mountain"), photos.searchPhotos("hello").map { it.name })
        // Trashed rows leave search.
        photos.setTrashed(ids.last())
        assertTrue(photos.searchPhotos("hello").isEmpty())
    }

    @Test fun progressCountsThroughRepository() = runTest {
        photos.upsertAll(listOf(
            photo("a", date = 1L), photo("b", date = 2L),
            photo("clip", date = 3L, video = true), photo("trashed", date = 4L, trashed = true),
        ))
        val ids = photos.getAll().filter { !it.isTrashed && it.videoData == null }.map { it.id }
        assertEquals(2, photos.getIndexTargetCountFlow().first())
        assertEquals(0, photos.getOCRCountFlow().first())
        assertEquals(0, photos.getClipCountFlow().first())
        assertEquals(0, photos.getFaceScannedCountFlow().first())
        scan.setOcrResults(listOf(OcrResult(ids[0], "x", null)))
        scan.setClipResults(listOf(ClipResult(ids[0], byteArrayOf(1, 2, 3))))
        scan.setFaceScanned(listOf(ids[1]))
        assertEquals(1, photos.getOCRCountFlow().first())
        assertEquals(1, photos.getClipCountFlow().first())
        assertEquals(1, photos.getFaceScannedCountFlow().first())
    }

    // ---- PhotoScanRepository ----

    @Test fun scanQueuesAndWritesThroughRepository() = runTest {
        photos.upsertAll(listOf(
            photo("still", date = 1L), photo("movie", date = 2L, video = true), photo("gone", date = 3L, trashed = true),
        ))
        val still = photos.getAll().first { it.name == "still" }
        assertEquals(listOf(still.id), scan.getUnscannedForOCR().map { it.id })
        assertEquals(listOf(still.id), scan.getUnscannedForFaces().map { it.id })
        assertEquals(listOf(still.id), scan.getUnscannedForClip().map { it.id })
        // The EXIF queue has no trashed/video filter: every unprocessed row is queued.
        assertEquals(photos.getAll().map { it.id }.toSet(),
            scan.getUnscannedForExif().map { it.id }.toSet())
        scan.setOcrScanned(listOf(still.id))
        assertTrue(scan.getUnscannedForOCR().isEmpty())
        scan.setExifResults(listOf(ExifResult(still.id, 10.0, 20.0,
            PanoData(4000, 2000, 3800, 1900, 100, 50, "cylindrical"))))
        val updated = photos.getAll().first { it.id == still.id }
        assertEquals(10.0, updated.lat)
        assertEquals(20.0, updated.long)
        assertEquals(4000, updated.panoData?.fullWidth)
        assertEquals("cylindrical", updated.panoData?.projectionType)
        assertTrue(scan.getUnscannedForExif().none { it.id == still.id })
    }

    @Test fun clipEmbeddingsOcrBoxesAndResetsThroughRepository() = runTest {
        photos.upsertAll(listOf(photo("a", date = 1L), photo("b", date = 2L), photo("nomime", date = 3L, mime = null)))
        val ids = photos.getAll().map { it.id }
        assertEquals(1, scan.countMissingMimeType())
        assertEquals(listOf("content://media/a", "content://media/b", "content://media/nomime"),
            scan.getStillPhotoUris().sorted())
        scan.setClipResults(listOf(ClipResult(ids[0], byteArrayOf(1, 2, 3)), ClipResult(ids[1], null)))
        val embeddings = scan.getClipEmbeddings()
        assertEquals(1, embeddings.size)
        assertContentEquals(byteArrayOf(1, 2, 3), embeddings.single().clipEmbedding)
        scan.setOcrBoxes(ids[0], "{\"lines\":[]}")
        assertEquals("{\"lines\":[]}", scan.getOcrBoxes(ids[0]))
        scan.setOcrBoxes(ids[0], null)
        assertNull(scan.getOcrBoxes(ids[0]))
        scan.resetClipScanned()
        assertTrue(scan.getClipEmbeddings().isEmpty())
        assertEquals(3, scan.getUnscannedForClip().size)
        scan.setFaceScanned(ids)
        assertTrue(scan.getUnscannedForFaces().isEmpty())
        scan.resetFaceScanned()
        assertEquals(3, scan.getUnscannedForFaces().size)
    }

    // ---- FaceRepository ----

    @Test fun faceScanCommitIsAtomicAndIdempotent() = runTest {
        photos.upsertAll(listOf(photo("a", date = 1L), photo("b", date = 2L)))
        val (a, b) = photos.getAll().map { it.id }.sorted()
        faces.commitFaceScan(listOf(a), listOf(face(a, floats(1f)), face(a, floats(0.9f))))
        assertEquals(1, scan.getUnscannedForFaces().size)
        assertEquals(2, faces.getClusterRows().size)
        // Re-scanning the same photo replaces rows instead of duplicating them.
        faces.commitFaceScan(listOf(a), listOf(face(a, floats(0.5f))))
        assertEquals(1, faces.getClusterRows().count { it.clusterable })
        assertEquals(listOf(a), faces.faceGeometryFlow().first().map { it.photoId }.distinct())
        faces.commitFaceScan(listOf(b), emptyList())
        assertTrue(scan.getUnscannedForFaces().isEmpty())
    }

    @Test fun applyClusteringKeepsNamesAndDeletesStalePersons() = runTest {
        photos.upsertAll(listOf(photo("a", date = 1L)))
        val photoId = photos.getAll().single().id
        faces.commitFaceScan(listOf(photoId), listOf(
            face(photoId, floats(1f)), face(photoId, floats(0.8f)), face(photoId, floats(0.1f, 1f)),
        ))
        val rows = faces.getClusterRows()
        val centroid = rows.first().embedding
        faces.applyClustering(
            persons = listOf(
                ResolvedPersonRow(null, centroid, listOf(rows[0].id, rows[1].id)),
                ResolvedPersonRow(null, centroid, listOf(rows[2].id)),
            ),
            deletedPersonIds = emptyList(),
        )
        val persons = faces.getPersons()
        assertEquals(2, persons.size)
        assertEquals(listOf(2, 1), persons.map { it.faceCount }.sortedDescending())
        faces.setPersonName(persons[0].id, "Alice")
        assertEquals(listOf(photoId), faces.photoIdsForCluster(persons[1].id).ifEmpty { faces.photoIdsForCluster(persons[0].id) })
        // Second pass keeping only the named person: their name survives, the other is deleted.
        val named = faces.getPersons().first { it.name == "Alice" }
        val rowsAfter = faces.getClusterRows()
        val namedFaceIds = rowsAfter.filter { it.clusterId == named.id }.map { it.id }
        val stale = faces.getPersons().first { it.name == null }
        faces.applyClustering(
            persons = listOf(ResolvedPersonRow(named.id, centroid, namedFaceIds)),
            deletedPersonIds = listOf(stale.id),
        )
        val after = faces.getPersons()
        assertEquals(1, after.size)
        assertEquals("Alice", after.single().name)
        assertEquals(namedFaceIds.sorted(), faces.getClusterRows().filter { it.clusterId == named.id }.map { it.id }.sorted())
        assertEquals(listOf("Alice"), faces.personsFlow().first().map { it.name })
        faces.clearPhotoFaces()
        assertTrue(faces.getClusterRows().isEmpty())
        faces.clearPersons()
        assertTrue(faces.getPersons().isEmpty())
        faces.setPersonName(12345L, "Nobody")
    }

    @Test fun applyClusteringChunksLargeAssignments() = runTest {
        photos.upsertAll(listOf(photo("crowd", date = 1L)))
        val photoId = photos.getAll().single().id
        // 950 faces in one commit: assignment must chunk under SQLite's bound limit.
        val many = (1..950).map { face(photoId, floats(it / 1000f)) }
        faces.commitFaceScan(listOf(photoId), many)
        val rows = faces.getClusterRows()
        assertEquals(950, rows.size)
        faces.applyClustering(
            persons = listOf(ResolvedPersonRow(null, rows.first().embedding, rows.map { it.id })),
            deletedPersonIds = emptyList(),
        )
        val person = faces.getPersons().single()
        assertEquals(950, person.faceCount)
        assertEquals(950, faces.getClusterRows().count { it.clusterId == person.id })
    }

    // ---- VaultRepository ----

    @Test fun vaultCrudThroughRepository() = runTest {
        val vault = TestPhotosRepository.installVault(context)
        val first = VaultPhoto(name = "a.jpg", path = "/vault/a", thumbnailPath = "/vault/a.thumb",
            date = 100L, width = 10, height = 10, dateModified = 100L)
        val id = vault.upsert(first)
        vault.upsert(VaultPhoto(name = "b.mp4", path = "/vault/b", thumbnailPath = "/vault/b.thumb",
            date = 200L, width = 10, height = 10, dateModified = 200L, videoDuration = 3_000L))
        assertEquals(listOf("a.jpg", "b.mp4"), vault.getAllFlow().first().map { it.name })
        assertEquals("a.jpg", vault.getByIdFlow(id).first()?.name)
        assertEquals(1, vault.dao().delete(vault.getAllFlow().first().first { it.name == "a.jpg" }))
        assertEquals(listOf("b.mp4"), vault.getAllFlow().first().map { it.name })
        // Same password returns the cached instance; a different one is distinct.
        assertEquals(vault, VaultRepository.get(context, "test-vault-pass"))
        assertTrue(VaultRepository.getIfExists("test-vault-pass") != null)
        assertNull(VaultRepository.getIfExists("never-used-pass"))
    }

    @Test fun singletonRepositoriesShareOneDatabase() = runTest {
        assertEquals(photos, PhotosRepository.get(context))
        assertEquals(scan, PhotoScanRepository.get(context))
        assertEquals(faces, FaceRepository.get(context))
        photos.upsertAll(listOf(photo("shared", date = 1L)))
        // Writes through PhotosRepository are visible to the scan/face repositories.
        assertEquals(1, scan.getUnscannedForOCR().size)
        assertEquals(1, photos.getAll().size)
    }
}
