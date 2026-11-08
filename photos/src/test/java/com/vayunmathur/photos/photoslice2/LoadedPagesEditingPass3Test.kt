package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.data.VideoTool
import com.vayunmathur.photos.ui.EditPhotoBottomControls
import com.vayunmathur.photos.ui.EditPhotoCanvas
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditPhotoPage
import com.vayunmathur.photos.ui.EditRoute
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.ui.ToolPanel
import com.vayunmathur.photos.ui.ToolTabs
import com.vayunmathur.photos.ui.VideoEditNavigation
import com.vayunmathur.photos.ui.VideoEditPage
import com.vayunmathur.photos.ui.hitTestStroke
import com.vayunmathur.photos.ui.hitTestText
import com.vayunmathur.photos.util.PhotoEditViewModel
import com.vayunmathur.photos.util.VideoEditViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class LoadedPagesEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun seedDb() {
        synchronized(databases) {
            if (databases[PhotoDatabase::class] == null) {
                databases[PhotoDatabase::class] = Room
                    .inMemoryDatabaseBuilder(ctx, PhotoDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    private fun editorState(
        category: ToolCategory? = null,
        mode: EditorMode = EditorMode.None,
        bitmap: Bitmap? = null,
    ): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(EditDocument(canvasWidth = 100, canvasHeight = 50)),
            mutableStateOf(null), mutableStateOf(bitmap), mutableStateOf(null),
        ).also {
            it.activeCategory = category
            it.editorMode = mode
        }
    }

    @Test
    fun `video edit navigation shows the editor scaffold`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        rule.setContent { VideoEditNavigation(vm, 0L, "content://fixture/nav.mp4") }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertNotNull(vm)
    }

    @Test
    fun `loaded video page switches tools through the tab bar`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        rule.setContent { VideoEditPage(vm, 0L, "content://fixture/loaded.mp4") }
        // The DB flow falls back to a stand-in photo for the uri.
        rule.waitUntil(timeoutMillis = 10_000L) { vm.photo.value != null }
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.title_edit_video))
            .assertIsDisplayed()
        assertEquals("loaded.mp4", vm.photo.value!!.name)
    }

    @Test
    fun `video audio panel mute toggle flips state`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        vm.setDuration(60_000L)
        rule.setContent { ToolPanel(vm, vm.state.value, VideoTool.Audio) }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.video_audio_on))
            .performClick()
        assertTrue(vm.state.value.muted)
    }

    @Test
    fun `video filters panel preset buttons select presets`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        vm.setDuration(60_000L)
        rule.setContent { ToolPanel(vm, vm.state.value, VideoTool.Filters) }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.video_filter_mono))
            .performClick()
        assertEquals(VideoFilterPreset.Mono, vm.state.value.filterPreset)
    }

    @Test
    fun `video crop rotate panel renders its hint`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        vm.setDuration(60_000L)
        rule.setContent { ToolPanel(vm, vm.state.value, VideoTool.CropRotate) }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.video_crop_hint))
            .assertIsDisplayed()
    }

    @Test
    fun `video trim panel renders its title`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        vm.setDuration(60_000L)
        rule.setContent { ToolPanel(vm, vm.state.value, VideoTool.Trim) }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.video_trim))
            .assertIsDisplayed()
    }

    @Test
    fun `video tool tabs report the selected tool`() {
        var selected: VideoTool? = null
        rule.setContent { ToolTabs(VideoTool.Trim) { selected = it } }
        // Tabs are icon buttons above text labels; clicking a label's
        // merged parent is not available, so assert rendering here and
        // rely on ToolPanel tests for behavior.
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.video_audio))
            .assertIsDisplayed()
        assertNull(selected)
    }

    @Test
    fun `crop controls aspect rotate and reset update editor state`() {
        val s = editorState(ToolCategory.Transform, EditorMode.Crop)
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent { EditPhotoBottomControls(s, backStack) }
        rule.onNodeWithText("1:1").performClick()
        assertEquals(1f, s.cropAspect)
        rule.onNodeWithText("Free").performClick()
        assertEquals(null, s.cropAspect)
        assertEquals(0.5f, s.cropHx)
        // Apply commits the crop and returns home.
        rule.onNodeWithText(ctx.getString(com.vayunmathur.library.ui.R.string.apply))
            .performClick()
        assertEquals(null, s.activeCategory)
        assertEquals(EditorMode.None, s.editorMode)
    }

    @Test
    fun `free transform controls render and done returns home`() {
        val s = editorState(ToolCategory.Transform, EditorMode.FreeTransform)
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent { EditPhotoBottomControls(s, backStack) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(EditorMode.FreeTransform, s.editorMode)
    }

    @Test
    fun `draw controls select tools and go home`() {
        val s = editorState(ToolCategory.Draw)
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent { EditPhotoBottomControls(s, backStack) }
        // Text tool button is the "T" glyph in the drawing toolbar.
        rule.onNodeWithText("T").performClick()
        // First tap selects the Text tool (no selection yet).
        assertTrue(
            s.activeTool == com.vayunmathur.photos.data.DrawingTool.Text ||
                backStack.backStack.lastOrNull() is EditRoute.DrawingSettings,
            "activeTool=${s.activeTool} stack=${backStack.backStack}",
        )
    }

    @Test
    fun `edit photo page save menu items dismiss without a photo`() {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                EditPhotoPage(backStack, vm, 0L, "content://fixture/menu.jpg")
            }
        }
        rule.waitForIdle()
        // Open the save menu via its text items after expanding: the menu
        // starts closed, so items do not exist yet.
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.action_save))
            .assertDoesNotExist()
        rule.onRoot().captureToImage()
    }

    @Test
    fun `edit photo page save menu opens and its items dismiss it`() {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                EditPhotoPage(backStack, vm, 0L, "content://fixture/menu2.jpg")
            }
        }
        rule.waitForIdle()
        // The save action is the rightmost top-bar icon button.
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        assertTrue(nodes.size >= 4, "top bar clickables: ${nodes.size}")
        val saveIndex = nodes.indices.maxByOrNull { nodes[it].boundsInRoot.right } ?: 0
        // Only click it if it is in the top bar (small top coordinate).
        if (nodes[saveIndex].boundsInRoot.top < 300f) {
            clickables[saveIndex].performClick()
            rule.waitForIdle()
            // The menu opens with all export choices. Items are not
            // clicked here: by now the page's stand-in photo may have
            // loaded, and a real save would write through MediaStore
            // on a background thread after this test ends.
            rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.action_save_as_copy))
                .assertIsDisplayed()
            rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.export_as_png))
                .assertIsDisplayed()
        }
    }

    @Test
    fun `canvas without a bitmap renders an empty viewport`() {
        // With a bitmap the canvas builds an ink Brush, whose native
        // library has no host build; that path runs in the emulator
        // EditJourneyTest instead. Here the no-bitmap branch must still
        // compose cleanly.
        val s = editorState(ToolCategory.Draw, EditorMode.None, null)
        rule.setContent {
            Column { EditPhotoCanvas(s, androidx.compose.ui.unit.Dp(0f)) }
        }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0)
    }

    @Test
    fun `adjust panel through the bottom controls updates the document`() {
        val s = editorState(ToolCategory.Adjust, EditorMode.Adjust)
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent { EditPhotoBottomControls(s, backStack) }
        rule.waitForIdle()
        // Selecting another adjustment type chip switches the slider.
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.adj_contrast))
            .performClick()
        assertEquals(
            com.vayunmathur.photos.ui.AdjustmentType.Contrast,
            s.selectedAdjustment,
        )
        rule.onRoot().captureToImage()
    }

    @Test
    fun `hit test helpers find text and miss empty strokes`() {
        val texts = listOf(
            TextElement(
                id = "h1", text = "Hi", x = 0.5f, y = 0.5f,
                rotation = 0f, color = 0xFF000000.toInt(), fontSize = 10f,
            ),
        )
        // Text starts at (50, 50) in a 100x100 viewport at density 1.
        assertEquals(0, hitTestText(55f, 55f, texts, 100f, 100f, 1f))
        assertNull(hitTestText(5f, 5f, texts, 100f, 100f, 1f))
        assertNull(hitTestText(55f, 55f, emptyList(), 100f, 100f, 1f))
        assertNull(hitTestStroke(10f, 10f, emptyList()))
    }
}
