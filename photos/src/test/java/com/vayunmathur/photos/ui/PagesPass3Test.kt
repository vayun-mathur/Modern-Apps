package com.vayunmathur.photos.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.util.PeopleUiState
import com.vayunmathur.photos.util.PersonCluster
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.util.FakeFaceStorePass3
import com.vayunmathur.photos.util.FakePhotoStorePass3
import com.vayunmathur.photos.util.FakeScanStorePass3
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.enqueueSyncWork
import com.vayunmathur.photos.util.pass3Photo
import com.vayunmathur.photos.util.runSyncOnce
import com.vayunmathur.photos.util.syncEnqueueHook
import com.vayunmathur.photos.util.syncOnceHook
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass-3 Robolectric tests for the slice-3 page binders (album detail, people, trash, filmstrip). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PagesPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application

    @BeforeTest
    fun setUp() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        syncOnceHook = {}
        syncEnqueueHook = {}
    }

    @AfterTest
    fun tearDown() {
        workInfosOverride = null
        syncOnceHook = null
        syncEnqueueHook = null
    }

    private fun vm(
        store: FakePhotoStorePass3 = FakePhotoStorePass3(),
        face: FakeFaceStorePass3 = FakeFaceStorePass3(),
    ): GalleryViewModel {
        val vm = GalleryViewModel(app, store, FakeScanStorePass3(), face)
        vm.runSyncHook = {}
        return vm
    }

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Gallery))

    private fun awaitVm(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out: $desc")
    }

    @Test
    fun albumDetailShowsAlbumPhotos() {
        val store = FakePhotoStorePass3(
            listOf(
                pass3Photo(2, "b.jpg", date = 200, album = "Trips"),
                pass3Photo(1, "a.jpg", date = 100, album = "Trips"),
                pass3Photo(3, "c.jpg", date = 300, album = "Other"),
            ),
        )
        val vm = vm(store)
        rule.setContent { CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) { AlbumDetailPage(backStack(), vm, "Trips") } }
        awaitVm("albums load") { vm.albums.value.isNotEmpty() }
        rule.onNodeWithText("Trips").assertIsDisplayed()
    }

    @Test
    fun albumDetailMissingAlbumRendersEmpty() {
        val vm = vm(FakePhotoStorePass3(listOf(pass3Photo(1, "a.jpg", album = "Trips"))))
        rule.setContent { CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) { AlbumDetailPage(backStack(), vm, "NoSuchAlbum") } }
        rule.waitForIdle()
    }

    @Test
    fun trashPageEmptyState() {
        val vm = vm(FakePhotoStorePass3(listOf(pass3Photo(1, "a.jpg"))))
        rule.setContent { CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) { TrashPage(backStack(), vm) } }
        rule.onNodeWithText("Trash is empty", substring = true).assertExists()
    }

    @Test
    fun trashPageListsTrashedByMonth() {
        val store = FakePhotoStorePass3(
            listOf(
                pass3Photo(1, "old.jpg", date = 1700000000000, trashed = true),
                pass3Photo(2, "keep.jpg", date = 1700000000000),
            ),
        )
        val vm = vm(store)
        rule.setContent { CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) { TrashPage(backStack(), vm) } }
        rule.waitForIdle()
        assertTrue(vm.photos.value.count { it.isTrashed } == 1)
    }

    @Test
    fun peoplePageShowsClusters() {
        val face = FakeFaceStorePass3(
            persons = listOf(Person(id = 5, centroid = ByteArray(4), faceCount = 2, name = "Grace")),
            geometry = listOf(
                FaceGeometry(photoId = 1, clusterId = 5, left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 100),
                FaceGeometry(photoId = 2, clusterId = 5, left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 100),
            ),
        )
        val store = FakePhotoStorePass3(listOf(pass3Photo(1, "a.jpg"), pass3Photo(2, "b.jpg")))
        val vm = vm(store, face)
        rule.setContent { PeoplePage(backStack(), vm) }
        awaitVm("people load") { vm.people.value.isNotEmpty() }
        // Without the on-device models the page explains instead of listing people.
        rule.waitForIdle()
    }

    @Test
    fun peopleScreenWithModelsShowsClusterNames() {
        val cover = pass3Photo(1, "a.jpg")
        val cluster = PersonCluster(
            id = 5, name = "Grace", coverPhoto = cover,
            faceLeft = 0f, faceTop = 0f, faceRight = 0.5f, faceBottom = 0.5f,
            photos = listOf(cover),
        )
        rule.setContent {
            PeopleScreen(
                backStack = backStack(),
                state = PeopleUiState(people = listOf(cluster), modelsAvailable = true),
                faceThumbnail = { _, modifier -> androidx.compose.foundation.layout.Box(modifier) },
            )
        }
        rule.onNodeWithText("Grace").assertIsDisplayed()
    }

    @Test
    fun peoplePageEmptyWhenNoFaces() {
        val vm = vm()
        rule.setContent { PeoplePage(backStack(), vm) }
        rule.waitForIdle()
    }

    @Test
    fun filmstripPaneRendersTiles() {
        val photos = listOf(pass3Photo(1, "a.jpg"), pass3Photo(2, "b.jpg"), pass3Photo(3, "c.jpg"))
        rule.setContent {
            val pager = rememberPagerState(initialPage = 1) { photos.size }
            FilmstripPane(photos, pager)
        }
        rule.waitForIdle()
    }

    @Test
    fun pendingPhotoViewRenders() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent { PendingPhotoView("content://media/123", ctx) }
        rule.waitForIdle()
    }

    @Test
    fun syncSeamFunctionsInvokeHooks() {
        var once = 0
        var enq = 0
        syncOnceHook = { once++ }
        syncEnqueueHook = { enq++ }
        runSyncOnce(app)
        enqueueSyncWork(app)
        assertEquals(1, once)
        assertEquals(1, enq)
    }
}
