package com.vayunmathur.photos.util

import android.app.Application
import android.os.Looper
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.SharePhotoCopy
import com.vayunmathur.photos.platform.workInfosOverride
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Privacy fixes: face rows die with their photo, people clusters can be
 * hidden/deleted by the user, and shared stills lose their GPS EXIF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosPrivacyFixesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, date: Long) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null,
    )

    private fun face(photoId: Long, clusterId: Long?) = PhotoFace(
        photoId = photoId, clusterId = clusterId, embedding = byteArrayOf(1, 2, 3),
        clusterable = true, left = 0f, top = 0f, right = 0.5f, bottom = 0.5f,
        srcWidth = 100, srcHeight = 100,
    )

    @Test fun deleteByIdsRemovesFaceRowsAndFacelessPersons() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a", 200L), photo("b", 100L)))
        val (a, b) = installed.photos.getAll().sortedBy { it.name }
        val dao = installed.database.faceDao()
        val ada = dao.insertPerson(Person(centroid = byteArrayOf(1), faceCount = 1, name = "Ada"))
        val shared = dao.insertPerson(Person(centroid = byteArrayOf(2), faceCount = 2, name = "Shared"))
        dao.insertPhotoFaces(listOf(face(a.id, ada), face(a.id, shared), face(b.id, shared)))

        installed.photos.deleteByIds(listOf(a.id))

        // a's faces (embeddings included) are gone; b's face and person survive.
        val geometry = dao.faceGeometryFlow().first()
        assertEquals(listOf(b.id), geometry.map { it.photoId })
        assertEquals(listOf("Shared"), installed.faces.getPersons().map { it.name })
        assertEquals(listOf("b"), installed.photos.getAll().map { it.name })
        Unit
    }

    @Test fun hideExcludesPersonFromPeopleAndDeleteRemovesOnlyTheCluster() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a", 200L)))
        val a = installed.photos.getAll().single()
        val dao = installed.database.faceDao()
        val ada = dao.insertPerson(Person(centroid = byteArrayOf(1), faceCount = 1, name = "Ada"))
        dao.insertPhotoFaces(listOf(face(a.id, ada)))

        val vm = GalleryViewModel(app).also { it.runSyncHook = {} }
        val peopleJob: kotlinx.coroutines.Job =
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { vm.people.collect {} }
        val photosJob: kotlinx.coroutines.Job =
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { vm.photos.collect {} }
        val jobs = listOf(peopleJob, photosJob)
        assertTrue(
            awaitPhotosCondition { Shadows.shadowOf(Looper.getMainLooper()).idle(); vm.people.value.size == 1 },
            "person projected before hide",
        )

        vm.hidePerson(ada)
        assertTrue(
            awaitPhotosCondition { Shadows.shadowOf(Looper.getMainLooper()).idle(); vm.people.value.isEmpty() },
            "hidden person excluded from people",
        )
        // Hidden, not deleted: row, name and face assignment are retained.
        val hidden = installed.faces.getPersons().single()
        assertTrue(hidden.hidden)
        assertEquals("Ada", hidden.name)

        vm.deletePerson(ada)
        assertTrue(
            awaitPhotosCondition { installed.faces.getPersons().isEmpty() },
            "cluster deleted",
        )
        // Assignments cleared, but the face row and the photo itself survive.
        assertEquals(null, dao.faceGeometryFlow().first().single().clusterId)
        assertEquals(listOf("a"), installed.photos.getAll().map { it.name })
        jobs.forEach { it.cancel() }
        Unit
    }

    @Test fun stripGpsExifRemovesLocationButKeepsOtherTags() {
        // A 4x3 white JPEG (222 bytes) generated with ffmpeg.
        val bytes = Base64.decode(TINY_JPEG_BASE64, Base64.DEFAULT)
        val file = File(app.cacheDir, "gps-test.jpg").apply { writeBytes(bytes) }
        val exif = ExifInterface(file)
        exif.setLatLong(37.4215, -122.0840)
        exif.setAttribute(ExifInterface.TAG_MAKE, "TestCamera")
        exif.saveAttributes()
        assertTrue(SharePhotoCopy.hasGpsExif(file), "seeded GPS is readable")

        SharePhotoCopy.stripGpsExif(file)

        assertFalse(SharePhotoCopy.hasGpsExif(file), "GPS removed from the shared copy")
        assertEquals(null, ExifInterface(file).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals("TestCamera", ExifInterface(file).getAttribute(ExifInterface.TAG_MAKE))
        Unit
    }

    private companion object {
        const val TINY_JPEG_BASE64 =
            "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjExLjEwMwD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABLAAEBAAAAAAAAAAAAAAAAAAAABwEBAAAAAAAAAAAAAAAAAAAAABABAAAAAAAAAAAAAAAAAAAAABEBAAAAAAAAAAAAAAAAAAAAAP/AABEIAAMABAMBIgACEQADEQD/2gAMAwEAAhEDEQA/AL+AD//Z"
    }
}
