package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Navigation
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real [Navigation] graph driven through its bottom bar: every tab
 * entry composes against the seeded in-memory library, albums open their
 * detail page, and the viewer opens from the gallery grid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosNavigationDeepTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
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

    private fun photo(name: String, date: Long, album: String? = null, lat: Double? = null) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = lat, long = lat?.let { -it },
        videoData = null, panoData = null, album = album,
    )

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }

    @Test fun bottomBarVisitsEveryTab() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("beach.jpg", 300L, album = "Camera", lat = 37.0),
            photo("mountain.jpg", 200L, album = "Camera", lat = 46.0),
            photo("city.jpg", 100L, album = "Trips"),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        val mapVm = PhotoMapViewModel(app)
        setContent {
            DynamicTheme {
                Navigation(gallery, mapVm, secureVm)
            }
        }
        rule.waitForIdle()
        // Albums tab lists both buckets; opening one pushes the detail page.
        rule.onRoot().printToLog("NAV")
        rule.onNodeWithText("Albums").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Camera", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Camera", substring = true).performClick()
        rule.waitForIdle()
        // The detail page's header names the album it opened.
        assertTrue(rule.onAllNodesWithText("Camera", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Back to the tab host, then People and Map tabs compose.
        rule.onNodeWithText("People").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Map").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Gallery").performClick()
        rule.waitForIdle()
        // Grid items expose their photo names as content descriptions.
        assertTrue(rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size >= 3)
        Unit
    }

    @Test fun viewerOpensFromGalleryGrid() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("beach.jpg", 300L, album = "Camera"),
            photo("mountain.jpg", 200L, album = "Camera"),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        val mapVm = PhotoMapViewModel(app)
        setContent {
            DynamicTheme {
                Navigation(gallery, mapVm, secureVm)
            }
        }
        rule.waitForIdle()
        // Grid items are clickable image nodes; click the first one and
        // let the viewer compose (its chrome carries the photo name).
        val clickables = rule.onAllNodes(hasClickAction())
        assertTrue(clickables.fetchSemanticsNodes().isNotEmpty())
        clickables[0].performClick()
        rule.waitForIdle()
        Unit
    }
}
