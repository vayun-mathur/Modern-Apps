package com.vayunmathur.photos.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.awaitPhotosCondition
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PhotoEditViewModel.decode] of a real JPEG builds the background-layer
 * document, and the undo/redo + adjustment controllers mutate it; the
 * video export start failure path completes cleanly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditDecodeAndExportTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun jpegFile(name: String, w: Int = 96, h: Int = 72): Uri {
        val file = File(app.cacheDir, name)
        FileOutputStream(file).use { out ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(140, 60, 20))
            }.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return Uri.fromFile(file)
    }

    @Test fun decodeBuildsDocumentAndHistoryWorks() = runBlocking {
        val vm = PhotoEditViewModel(app, installed.photos)
        val uri = jpegFile("decode-me.jpg")
        vm.decode(uri)
        assertTrue(awaitPhotosCondition {
            vm.document.value.layers.isNotEmpty() && vm.baseBitmap.value != null
        })
        assertEquals(96, vm.document.value.canvasWidth)
        // Re-decoding the same uri is a no-op (cached base).
        val layersBefore = vm.document.value.layers.size
        vm.decode(uri)
        assertEquals(layersBefore, vm.document.value.layers.size)
        // Document mutations push undo; undo/redo walk the stacks.
        vm.updateDocument { it.copy(canvasWidth = 50) }
        assertEquals(50, vm.document.value.canvasWidth)
        assertTrue(vm.canUndo.value)
        vm.undo()
        assertEquals(96, vm.document.value.canvasWidth)
        assertTrue(vm.canRedo.value)
        vm.redo()
        assertEquals(50, vm.document.value.canvasWidth)
        // A fresh decode of another image clears history.
        vm.decode(jpegFile("decode-two.jpg", 64, 64))
        assertTrue(awaitPhotosCondition { vm.document.value.canvasWidth == 64 })
        assertFalse(vm.canUndo.value)
        Unit
    }

    @Test fun videoExportStartFailureCompletes() = runBlocking {
        val vm = VideoEditViewModel(app, installed.photos)
        val photo = Photo(
            name = "gone.mp4", uri = "content://media/vanished", date = 1L,
            width = 16, height = 16, dateModified = 1L, exifSet = true,
            lat = null, long = null,
            videoData = com.vayunmathur.photos.data.VideoData(1_000L), panoData = null,
        )
        var completed = 0
        vm.export(app, photo, asCopy = true) { completed++ }
        // Either the transformer errors asynchronously or start throws:
        // the completion callback must land and exporting must clear.
        assertTrue(awaitPhotosCondition(timeoutMs = 30_000) { completed == 1 })
        assertFalse(vm.exporting.value)
        vm.cancelExport()
        Unit
    }
}
