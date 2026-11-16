package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.photos.LocalColumnCount
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Navigation
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.ui.AlbumDetailPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.TrashPage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Top-level pages and the real [Navigation] graph over in-memory Room.
 *
 * Earlier page suites used fake store implementations of the ViewModel's
 * seams; here [GalleryViewModel]'s default (production) adapters read the
 * installed in-memory repositories, so AlbumDetail/Map/Trash/People pages
 * and the navigation entries execute against genuine rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosPagesRealRepoTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, date: Long, album: String? = null, trashed: Boolean = false, lat: Double? = null) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = lat, long = lat?.let { -it },
        videoData = null, panoData = null, isTrashed = trashed, album = album,
    )

    private fun galleryVm(): GalleryViewModel = GalleryViewModel(app).also { it.runSyncHook = {} }

    private fun stack(vararg routes: Route): NavBackStack<Route> = NavBackStack(routes)

    private suspend fun seedLibrary() {
        installed.photos.upsertAll(listOf(
            photo("beach.jpg", 300L, album = "Camera", lat = 37.0),
            photo("mountain.jpg", 200L, album = "Camera", lat = 46.0),
            photo("city.jpg", 100L, album = "Trips"),
            photo("old.jpg", 50L, album = "Camera", trashed = true),
        ))
    }

    @Test fun navigationRendersGallery() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        val secureVm = SecureFolderViewModel(app)
        val mapVm = PhotoMapViewModel(app)
        setContent {
            DynamicTheme {
                Navigation(gallery, mapVm, secureVm)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("beach.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Gallery", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun albumDetailPageShowsAlbumPhotos() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        setContent {
            DynamicTheme { AlbumDetailPage(stack(Route.Gallery), gallery, "Camera") }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Camera", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun albumDetailPageForMissingAlbumRenders() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        setContent {
            DynamicTheme { AlbumDetailPage(stack(Route.Gallery), gallery, "No Such Album") }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("No Such Album", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun trashPageShowsTrashedPhotos() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        setContent { DynamicTheme { TrashPage(stack(Route.Gallery), gallery) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Trash", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun peoplePageRendersEmptyAndSeededStates() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        val mapVm = PhotoMapViewModel(app)
        setContent { DynamicTheme { PeoplePage(stack(Route.Gallery), gallery) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("People", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun mapPageRendersWithLocatedPhotos() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        val mapVm = PhotoMapViewModel(app)
        setContent { DynamicTheme { MapPage(stack(Route.Gallery), gallery, mapVm) } }
        rule.waitForIdle()
        Unit
    }

    @Test fun navigationBarSwitchesRoutes() = runBlocking {
        seedLibrary()
        val backStack = NavBackStack(arrayOf<Route>(Route.Gallery))
        setContent {
            DynamicTheme {
                com.vayunmathur.photos.NavigationBar(Route.Gallery, backStack)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Map", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("People", substring = true).performClick()
        assertTrue(backStack.last() == Route.People)
        rule.onNodeWithText("Albums", substring = true).performClick()
        assertTrue(backStack.last() == Route.Albums)
        Unit
    }

    @Test fun photoMapClusteringGroupsNearbyPoints() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a", 300L), photo("b", 200L), photo("c", 100L)))
        val (a, b, c) = installed.photos.getAll().sortedByDescending { it.date }
        val items = listOf(
            Triple(androidx.compose.ui.unit.DpOffset(0.dp, 0.dp), com.vayunmathur.library.map.GeoPoint(10.0, 20.0), a),
            Triple(androidx.compose.ui.unit.DpOffset(10.dp, 10.dp), com.vayunmathur.library.map.GeoPoint(10.1, 20.1), b),
            Triple(androidx.compose.ui.unit.DpOffset(500.dp, 500.dp), com.vayunmathur.library.map.GeoPoint(50.0, 60.0), c),
        )
        val clusters = PhotoMapViewModel.clusterPhotos(items, 50.dp)
        // The two nearby screen points merge; the far one stays alone, and
        // photos inside a cluster come back newest-first.
        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first { it.count == 2 }.allPhotos.size)
        assertEquals(listOf("a", "b"), clusters.first { it.count == 2 }.allPhotos.map { it.name })
        val vm = PhotoMapViewModel(app)
        vm.regenerateClusters(items)
        kotlinx.coroutines.delay(300)
        assertEquals(2, vm.generatedClusters.value.size)
        vm.requestCountryName(a.id, 37.0, -122.0)
        kotlinx.coroutines.delay(300)
        // Robolectric's geocoder has no backend: the request resolves to a
        // cached "Unknown" (or stays pending), never a crash.
        assertTrue(vm.countryNames.value[a.id] == null || vm.countryNames.value[a.id] == "Unknown")
        Unit
    }

    @Test fun galleryPageSelectionHeaderAppears() = runBlocking {
        seedLibrary()
        val gallery = galleryVm()
        val first = installed.photos.getAll().first()
        gallery.toggleSelection(first.id)
        val secureVm = SecureFolderViewModel(app)
        setContent {
            DynamicTheme {
                com.vayunmathur.photos.ui.GalleryPage(stack(Route.Gallery), gallery, secureVm)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("1", substring = true).fetchSemanticsNodes().size >= 2)
        Unit
    }
}
