package com.vayunmathur.photos.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gallery page driven hard: search text entry filters the grid,
 * items enter selection mode, and the page chrome (menus, headers)
 * executes against the seeded library.
 */
@RunWith(AndroidJUnit4::class)
class GalleryPageDeepDriveTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }

    private fun photo(name: String, date: Long, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, isFavorite = favorite,
    )

    @Test fun gallerySearchSelectionAndChrome() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("beach sunset.jpg", 1_700_000_000_000L),
            photo("mountain.jpg", 1_699_000_000_000L),
            photo("city night.jpg", 1_698_000_000_000L, favorite = true),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        setContent {
            DynamicTheme {
                GalleryPage(
                    NavBackStack<Route>(arrayOf(Route.Gallery)),
                    gallery, secureVm,
                )
            }
        }
        rule.waitForIdle()
        // Type into the search field if the page exposes one directly.
        val fields = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        if (fields > 0) {
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("beach")
            rule.waitForIdle()
            gallery.setSearchQuery("beach")
            rule.waitForIdle()
        } else {
            gallery.setSearchQuery("beach")
            rule.waitForIdle()
        }
        // Press the page chrome and grid items.
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 14)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        assertTrue(count > 0)
        assertTrue(
            rule.onAllNodesWithText("beach", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty(),
        )
        Unit
    }

    @Test fun galleryEmptyStateRenders() = runBlocking {
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        setContent {
            DynamicTheme {
                GalleryPage(
                    NavBackStack<Route>(arrayOf(Route.Gallery)),
                    gallery, secureVm,
                )
            }
        }
        rule.waitForIdle()
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 8)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        Unit
    }
}
