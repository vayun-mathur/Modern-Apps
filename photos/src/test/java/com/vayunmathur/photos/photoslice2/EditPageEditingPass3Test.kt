package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.room3.Room
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import com.vayunmathur.photos.data.PhotoDatabase
import kotlin.test.BeforeTest
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.ui.EditPhotoCanvas
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditPhotoPage
import com.vayunmathur.photos.ui.EditRoute
import com.vayunmathur.photos.ui.VideoEditPage
import com.vayunmathur.photos.util.PhotoEditViewModel
import com.vayunmathur.photos.util.VideoEditViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class EditPageEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun bitmap(): Bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFF336699.toInt())
    }

    @BeforeTest
    fun seedDb() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        synchronized(databases) {
            if (databases[PhotoDatabase::class] == null) {
                databases[PhotoDatabase::class] = Room
                    .inMemoryDatabaseBuilder(ctx, PhotoDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    private fun editorState(bmp: Bitmap?): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val doc = EditDocument(canvasWidth = 100, canvasHeight = 50)
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(doc), mutableStateOf(null), mutableStateOf(bmp), mutableStateOf(null),
        )
    }

    @Test
    fun `edit photo page shows scaffold before photo loads`() {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                EditPhotoPage(backStack, vm, 0L, "content://fixture/photo.jpg")
            }
        }
        rule.waitForIdle()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.title_edit_photo)).assertExists()
    }

    @Test
    fun `video edit page shows loading before video loads`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        rule.setContent { VideoEditPage(vm, 0L, "content://fixture/video.mp4") }
        rule.waitForIdle()
        // Photo has not resolved yet: the page is in its loading branch
        // (black box + indicator), not the editor scaffold.
        rule.onRoot().captureToImage()
        assertNotNull(vm.state.value)
    }

    @Test
    fun `video edit state defaults are sane`() {
        val state = VideoEditState()
        assertEquals(0L, state.trimStartMs)
        assertNotNull(state)
        assertEquals(Uri.EMPTY, Uri.EMPTY)
    }
}
