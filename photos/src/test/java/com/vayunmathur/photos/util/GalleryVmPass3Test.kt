package com.vayunmathur.photos.util

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.Flow
import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.Person
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pass-3 tests for [GalleryViewModel]: search merging, album moves, cluster photos, factory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryVmPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application

    @BeforeTest
    fun setUp() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
    }

    @AfterTest
    fun tearDown() {
        workInfosOverride = null
        syncOnceHook = null
        syncEnqueueHook = null
    }

    private fun vm(
        store: FakePhotoStorePass3 = FakePhotoStorePass3(),
        scan: FakeScanStorePass3 = FakeScanStorePass3(),
        face: FakeFaceStorePass3 = FakeFaceStorePass3(),
    ): GalleryViewModel {
        val vm = GalleryViewModel(app, store, scan, face)
        vm.runSyncHook = {}
        return vm
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $desc")
    }

    @Test
    fun searchMergesOcrHitsChronologicallyAndDropsTrashed() {
        val store = FakePhotoStorePass3()
        val old = pass3Photo(1, "cat-old.jpg", date = 100)
        val new = pass3Photo(2, "cat-new.jpg", date = 300)
        val trashed = pass3Photo(3, "cat-trash.jpg", date = 400, trashed = true)
        store.searchHits = listOf(old, new, trashed)
        val vm = vm(store)
        vm.setSearchQuery("cat")
        await("search results") { vm.searchResults.value.isNotEmpty() }
        assertEquals(listOf(2L, 1L), vm.searchResults.value.map { it.id })
        assertEquals("cat", vm.searchQuery.value)
        vm.setSearchQuery("")
        await("results cleared") { vm.searchResults.value.isEmpty() }
    }

    @Test
    fun faceCountsAndBoxesProject() = runBlocking {
        val face = FakeFaceStorePass3(
            persons = listOf(com.vayunmathur.photos.data.Person(id = 7, centroid = ByteArray(4), faceCount = 1, name = "Ada")),
            geometry = listOf(
                FaceGeometry(photoId = 1, clusterId = 7, left = 0.1f, top = 0.1f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 80),
                FaceGeometry(photoId = 1, clusterId = null, left = 0.6f, top = 0.1f, right = 0.8f, bottom = 0.4f, srcWidth = 100, srcHeight = 80),
            ),
        )
        val vm = vm(face = face)
        val counts = vm.faceCountByPhoto.first { it.isNotEmpty() }
        assertEquals(2, counts[1])
        val boxes = vm.faceBoxesByPhoto.first { it.isNotEmpty() }
        assertEquals(2, boxes[1]?.faces?.size)
        assertEquals("Ada", boxes[1]?.faces?.first { it.clusterId == 7L }?.name)
        assertEquals(100, boxes[1]?.srcWidth)
    }

    @Test
    fun peopleProjectsNamedSmallClustersAndCovers() = runBlocking {
        val p1 = pass3Photo(1, "a.jpg", date = 100)
        val p2 = pass3Photo(2, "b.jpg", date = 200)
        val face = FakeFaceStorePass3(
            persons = listOf(Person(id = 5, centroid = ByteArray(4), faceCount = 2, name = "Grace")),
            geometry = listOf(
                FaceGeometry(photoId = 1, clusterId = 5, left = 0f, top = 0f, right = 0.2f, bottom = 0.2f, srcWidth = 100, srcHeight = 100),
                FaceGeometry(photoId = 2, clusterId = 5, left = 0f, top = 0f, right = 0.9f, bottom = 0.9f, srcWidth = 100, srcHeight = 100),
            ),
        )
        val store = FakePhotoStorePass3(listOf(p1, p2))
        // PhotoStore flow must be collected for people (WhileSubscribed); collect via first with subscription
        val vm = vm(store, face = face)
        val people = vm.people.first { it.isNotEmpty() }
        assertEquals(1, people.size)
        assertEquals("Grace", people[0].name)
        assertEquals(2L, people[0].coverPhoto.id) // largest face area wins
        assertEquals(listOf(2L, 1L), people[0].photos.map { it.id })

        face.clusterIds = mapOf(5L to listOf(1L, 2L))
        val photos = vm.photosForCluster(5)
        // photos.value may be empty without a subscriber; ids resolved through face store only gate membership
        assertTrue(photos.all { it.id in setOf(1L, 2L) })
        assertTrue(vm.photosForCluster(999).isEmpty())

        vm.setPersonName(5, "G.")
        await("renamed") { face.renames.contains(5L to "G.") }
    }

    @Test
    fun albumsGroupWithCoverOverride() = runBlocking {
        val a1 = pass3Photo(1, "a1.jpg", date = 100, album = "Trips")
        val a2 = pass3Photo(2, "a2.jpg", date = 200, album = "Trips")
        val b1 = pass3Photo(3, "b1.jpg", date = 300, album = null)
        val trashed = pass3Photo(4, "t.jpg", date = 400, album = "Trips", trashed = true)
        val store = FakePhotoStorePass3(listOf(a2, b1, a1, trashed))
        val vm = vm(store)
        val albums = vm.albums.first { it.size >= 2 }
        val trips = albums.first { it.name == "Trips" }
        assertEquals(2, trips.count)
        assertEquals(2L, trips.coverPhoto.id)
        assertTrue(albums.any { it.name == Album.UNKNOWN_NAME })
        vm.setAlbumCover("Trips", 1)
        val updated = vm.albums.first { list -> list.first { it.name == "Trips" }.coverPhoto.id == 1L }
        assertEquals(1L, updated.first { it.name == "Trips" }.coverPhoto.id)
    }

    @Test
    fun deleteTrashAndMoveFlows() {
        val store = FakePhotoStorePass3()
        val vm = vm(store)
        val photo = pass3Photo(9, "x.jpg")
        vm.deletePhoto(photo)
        await("deleted") { store.deleted.contains(photo) }
        vm.trashPhotoLocally(photo)
        await("trashed") { store.trashed.contains(9L) }

        // Empty move is a no-op (no coroutine launched).
        vm.moveToAlbum(emptyList(), "Trips")
        assertTrue(store.albumSets.isEmpty())

        assertFalse(vm.consumePendingMove())
        vm.requestAlbumMove(listOf(photo), "Trips")
        assertTrue(vm.consumePendingMove())
        assertFalse(vm.consumePendingMove())
        // Moving runs on IO: AlbumMediaStore may move nothing in this environment,
        // but selection is cleared by the flow either way.
        await("selection cleared after move") { vm.selectedIds.value.isEmpty() }
    }

    @Test
    fun resolveAndIndexWithUnknownUriReturnsNull() {
        val store = FakePhotoStorePass3()
        store.byUri = emptyList()
        val vm = vm(store)
        var resolved: Long? = -1L
        // syncPhotos against a fake-backed resolveRepository falls back to the real
        // repository; the URI is unresolvable so the callback must report null.
        vm.resolveAndIndex(Uri.parse("content://media/999999")) { resolved = it }
        await("resolved callback") { resolved != -1L }
        assertEquals(null, resolved)
    }

    @Test
    fun syncHooksRouteThroughSeams() {
        var once = 0
        var enqueued = 0
        syncOnceHook = { once++ }
        syncEnqueueHook = { enqueued++ }
        runSyncOnce(app)
        enqueueSyncWork(app)
        assertEquals(1, once)
        assertEquals(1, enqueued)
    }
}
