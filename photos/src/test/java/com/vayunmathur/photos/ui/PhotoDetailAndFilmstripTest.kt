package com.vayunmathur.photos.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.PhotoFaceBoxes
import com.vayunmathur.photos.util.PhotoMapViewModel
import com.vayunmathur.photos.util.workInfosOverride
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
 * Viewer detail surfaces: the metadata/detail view for a located photo,
 * OCR text geometry, face-box placement, and the filmstrip selector.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDetailAndFilmstripTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, date: Long, lat: Double? = null) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 800, height = 600,
        dateModified = date, exifSet = true, lat = lat, long = lat?.let { -it },
        videoData = null, panoData = null,
    )

    @Test fun detailViewShowsMetadataForLocatedPhoto() = runBlocking {
        installed.photos.upsertAll(listOf(photo("located.jpg", 300L, lat = 37.5)))
        val seeded = installed.photos.getAll().first()
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val mapVm = PhotoMapViewModel(app)
        rule.setContent {
            DynamicTheme {
                val pager = rememberPagerState { 1 }
                PhotoDetailView(
                    photo = seeded, context = app,
                    photoMapViewModel = mapVm,
                    pagerState = pager, pageIndex = 0,
                    isSettled = true, isMetadataVisible = true,
                    currentZoom = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.vayunmathur.library.ui.ZoomState()) },
                    onZoomUpdate = {},
                    onToggleMetadata = {},
                    onEditPhoto = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("located.jpg", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun ocrAndFaceLayersPlaceGeometry() = runBlocking {
        var faceClicks = 0L
        rule.setContent {
            Box(Modifier.size(200.dp)) {
                OcrTextLayer(
                    layout = OcrLayout(800, 600, listOf(
                        OcrBox("hello sign", 80, 120, 400, 240),
                        OcrBox("second line", 80, 300, 400, 360),
                    )),
                    containerSize = IntSize(200, 150),
                    showOutlines = true,
                    zoom = { 1f },
                )
                FaceBoxLayer(
                    boxes = PhotoFaceBoxes(
                        srcWidth = 800, srcHeight = 600,
                        faces = listOf(
                            com.vayunmathur.photos.util.FaceBox(
                                clusterId = 5L, name = "Alice",
                                left = 0.1f, top = 0.1f, right = 0.5f, bottom = 0.5f,
                            ),
                        ),
                    ),
                    containerSize = IntSize(200, 150),
                    zoom = { 1f },
                    onFaceClick = { faceClicks = it },
                )
            }
        }
        rule.waitForIdle()
        // The OCR text is selectable content in the semantics tree.
        assertTrue(rule.onAllNodesWithText("hello sign", substring = true).fetchSemanticsNodes().isNotEmpty())
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 4)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
        }
        rule.waitForIdle()
        assertEquals(5L, faceClicks)
        Unit
    }

    @Test fun filmstripSelectsPhotos() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("a.jpg", 300L), photo("b.jpg", 200L), photo("c.jpg", 100L),
        ))
        val photos = installed.photos.getAll()
        rule.setContent {
            DynamicTheme {
                val pager = androidx.compose.foundation.pager.rememberPagerState { photos.size }
                Box(Modifier.size(120.dp, 300.dp)) { FilmstripPane(photos, pager) }
            }
        }
        rule.waitForIdle()
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue(count >= 3)
        rule.onAllNodes(hasClickAction())[count - 1].performClick()
        rule.waitForIdle()
        Unit
    }
}
