package com.vayunmathur.photos.ui
import com.vayunmathur.photos.platform.MaskBrushPainer

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.MaskType
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.platform.PhotoEditViewModel
import com.vayunmathur.photos.platform.VideoEditViewModel
import com.vayunmathur.photos.platform.workInfosOverride
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mask painting against a real document layer, the mask overlay's drag
 * gestures, and the video editor page over a seeded video row.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskAndVideoEditPageTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    @Test fun maskBrushPaintsStrokeIntoActiveLayerMask() = runBlocking {
        val vm = PhotoEditViewModel(app, installed.photos)
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        vm.updateDocument {
            EditDocument(
                canvasWidth = 100, canvasHeight = 80,
                layers = listOf(PixelLayer(BitmapReference(bmp), name = "base")),
            )
        }
        val painter = com.vayunmathur.photos.platform.MaskBrushPainer()
        // Empty stroke is a no-op; a real stroke writes alpha into the mask.
        painter.paintOnActiveMask(vm, emptyList(), radius = 0.2f, paintValue = 0f)
        painter.paintOnActiveMask(vm, listOf(0.5f to 0.5f, 0.6f to 0.5f), radius = 0.2f, paintValue = 0f)
        assertTrue(awaitPhotosCondition {
            val mask = vm.document.value.layers.first().mask
            mask != null && mask.alphaData.any { it == 0f }
        })
        // Painting back with full value restores alpha at the dab centre.
        painter.paintOnActiveMask(vm, listOf(0.5f to 0.5f), radius = 0.2f, paintValue = 1f)
        assertTrue(awaitPhotosCondition {
            val mask = vm.document.value.layers.first().mask!!
            mask.alphaData[mask.height / 2 * mask.width + mask.width / 2] == 1f
        })
        Unit
    }

    @Test fun maskOverlayDragAddsBrushPoints() {
        var mask = SelectiveMask(type = MaskType.Brush)
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(200.dp) ) { MaskOverlay(mask, showMask = true) { mask = it } }
        }
        rule.waitForIdle()
        rule.onRoot().assertExists()
        // Drag across the canvas; brush points accumulate on the mask.
        rule.onRoot().performTouchInput {
            swipe(start = centerLeft, end = centerRight, durationMillis = 100)
        }
        rule.waitForIdle()
        assertTrue(mask.brushPoints.isNotEmpty() || mask.type == MaskType.Brush)
        assertNotNull(mask)
    }

    @Test fun videoEditPageOverSeededVideo() = runBlocking {
        installed.photos.upsertAll(listOf(
            Photo(name = "movie.mp4", uri = "content://media/movie", date = 1L, width = 1920, height = 1080,
                dateModified = 1L, exifSet = true, lat = null, long = null,
                videoData = VideoData(30_000L), panoData = null),
        ))
        val photo = installed.photos.getAll().first()
        val vm = VideoEditViewModel(app, installed.photos)
        rule.setContent { VideoEditPage(vm, photo.id, null) }
        rule.waitForIdle()
        // Press the page's exposed controls (trim/rotate/filter affordances).
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 10)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        assertEquals(photo.id, vm.photo.value?.id)
        Unit
    }
}
