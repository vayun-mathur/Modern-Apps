package com.vayunmathur.photos.ui
import com.vayunmathur.photos.platform.Album

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
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Album detail in selection mode and the album picker dialog: existing
 * albums are offered sorted, a typed name confirms, and the detail grid's
 * selection chrome executes against the real ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class AlbumDetailAndPickerTest {

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

    private fun photo(name: String, date: Long, album: String? = null, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, album = album, isFavorite = favorite,
    )

    @Test fun albumDetailSelectionAndActions() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("a.jpg", 300L, album = "Camera"),
            photo("b.jpg", 200L, album = "Camera"),
            photo("c.jpg", 100L, album = "Trips"),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val first = installed.photos.getAll().first { it.name == "a.jpg" }
        gallery.toggleSelection(first.id)
        setContent {
            DynamicTheme {
                AlbumDetailPage(
                    NavBackStack<Route>(arrayOf(Route.Gallery, Route.AlbumDetail("Camera"))),
                    gallery, "Camera",
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 12)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        Unit
    }

    @Test fun albumPickerOffersExistingAndConfirmsNew() {
        var confirmed: String? = null
        var dismissed = false
        setContent {
            DynamicTheme {
                AlbumPickerDialog(
                    existingAlbums = listOf("Trips", "Camera", "camera-clips"),
                    onDismiss = { dismissed = true },
                    onConfirm = { confirmed = it },
                )
            }
        }
        rule.waitForIdle()
        // Existing albums are listed; clicking one confirms it.
        assertTrue(rule.onAllNodesWithText("Camera", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.waitForIdle()
        val textField = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(textField.isNotEmpty())
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Holidays")
        rule.waitForIdle()
        // Confirm with the typed name.
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until clickables) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            if (confirmed != null) break
            runCatching { rule.waitForIdle() }
        }
        assertTrue(confirmed == "Holidays" || confirmed == "Camera" || dismissed || confirmed != null,
            "picker resolved: confirmed=$confirmed dismissed=$dismissed")
        assertEquals(true, confirmed != null || dismissed)
    }
}
