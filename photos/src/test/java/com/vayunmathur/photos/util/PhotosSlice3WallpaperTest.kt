package com.vayunmathur.photos.util

import android.app.WallpaperManager
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.R
import com.vayunmathur.photos.ui.WallpaperControls
import com.vayunmathur.photos.ui.WallpaperPreview
import com.vayunmathur.photos.ui.groupPhotosByMonth
import com.vayunmathur.photos.data.Photo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 tests for the wallpaper stack: [WallpaperUtil]'s pure crop/scale
 * maths, the [WallpaperControls] sheet, [WallpaperPreview] load states, and the
 * gallery month-grouping helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3WallpaperTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- WallpaperUtil.computeCropRect ----

    @Test
    fun cropRectCenteredNoZoomCoversSource() {
        // 1000x500 source fitted into a 1000x500 display box at zoom 1 with a
        // full-size viewport in an equal container: the crop is the whole source.
        val rect = WallpaperUtil.computeCropRect(
            srcW = 1000, srcH = 500,
            baseDisplayW = 1000f, baseDisplayH = 500f,
            zoom = 1f, offsetX = 0f, offsetY = 0f,
            viewportW = 1000f, viewportH = 500f,
            containerW = 1000f, containerH = 500f,
        )
        assertEquals(Rect(0, 0, 1000, 500), rect)
    }

    @Test
    fun cropRectZoomedInShrinksToViewport() {
        // At 2x zoom the effective image is twice the container, so a centred
        // full-container viewport maps to the middle half of the source.
        val rect = WallpaperUtil.computeCropRect(
            srcW = 1000, srcH = 1000,
            baseDisplayW = 500f, baseDisplayH = 500f,
            zoom = 2f, offsetX = 0f, offsetY = 0f,
            viewportW = 500f, viewportH = 500f,
            containerW = 500f, containerH = 500f,
        )
        assertEquals(500, rect.width())
        assertEquals(500, rect.height())
        assertEquals(250, rect.left)
        assertEquals(250, rect.top)
    }

    @Test
    fun cropRectDegenerateInputsFallBackToFullSource() {
        assertEquals(Rect(0, 0, 800, 600), WallpaperUtil.computeCropRect(
            srcW = 800, srcH = 600, baseDisplayW = 0f, baseDisplayH = 0f,
            zoom = 1f, offsetX = 0f, offsetY = 0f,
            viewportW = 100f, viewportH = 100f, containerW = 100f, containerH = 100f,
        ))
        assertEquals(Rect(0, 0, 1, 1), WallpaperUtil.computeCropRect(
            srcW = 0, srcH = 0, baseDisplayW = 100f, baseDisplayH = 100f,
            zoom = 1f, offsetX = 0f, offsetY = 0f,
            viewportW = 100f, viewportH = 100f, containerW = 100f, containerH = 100f,
        ))
    }

    @Test
    fun cropRectPanShiftsWindow() {
        // Panning the image right by 100px moves the viewport window left in
        // source space.
        val base = WallpaperUtil.computeCropRect(
            srcW = 1000, srcH = 500,
            baseDisplayW = 1000f, baseDisplayH = 500f,
            zoom = 1f, offsetX = 0f, offsetY = 0f,
            viewportW = 500f, viewportH = 500f,
            containerW = 1000f, containerH = 500f,
        )
        val panned = WallpaperUtil.computeCropRect(
            srcW = 1000, srcH = 500,
            baseDisplayW = 1000f, baseDisplayH = 500f,
            zoom = 1f, offsetX = 100f, offsetY = 0f,
            viewportW = 500f, viewportH = 500f,
            containerW = 1000f, containerH = 500f,
        )
        assertEquals(base.width(), panned.width())
        assertTrue(panned.left < base.left)
        assertEquals(base.left - 100, panned.left)
    }

    // ---- WallpaperUtil.coverMinScale ----

    @Test
    fun coverMinScaleFitsViewport() {
        // Display 500x250 must cover a 500x500 viewport: height dominates -> 2x.
        assertEquals(2f, WallpaperUtil.coverMinScale(500f, 250f, 500f, 500f))
        // Already covering: floor of 1.
        assertEquals(1f, WallpaperUtil.coverMinScale(1000f, 1000f, 500f, 500f))
        // Degenerate display: safe default.
        assertEquals(1f, WallpaperUtil.coverMinScale(0f, 100f, 500f, 500f))
    }

    // ---- WallpaperControls ----

    private fun setControls(
        which: Int = WallpaperManager.FLAG_SYSTEM,
        isScrollable: Boolean = false,
        canSet: Boolean = true,
        isSetting: Boolean = false,
        onWhichChange: (Int) -> Unit = {},
        onScrollableChange: (Boolean) -> Unit = {},
        onSetWallpaper: () -> Unit = {},
    ) {
        composeRule.setContent {
            WallpaperControls(
                which = which,
                onWhichChange = onWhichChange,
                isScrollable = isScrollable,
                onScrollableChange = onScrollableChange,
                canSet = canSet,
                isSetting = isSetting,
                onSetWallpaper = onSetWallpaper,
            )
        }
    }

    @Test
    fun controlsShowTargetsAndSetButton() {
        setControls()
        composeRule.onNodeWithText(str(R.string.wallpaper_home)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.wallpaper_lock)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.wallpaper_both)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.set_wallpaper)).assertIsDisplayed()
    }

    @Test
    fun controlsTargetSwitchForwards() {
        var which: Int? = null
        setControls(onWhichChange = { which = it })
        composeRule.onNodeWithText(str(R.string.wallpaper_lock)).performClick()
        assertEquals(WallpaperManager.FLAG_LOCK, which)
    }

    @Test
    fun controlsScrollableShowsDescriptionOnlyWhenOn() {
        setControls(isScrollable = true)
        composeRule.onNodeWithText(str(R.string.wallpaper_scrollable_desc)).assertIsDisplayed()
        // Off: the description row is gone but the toggle row stays.
        setControls(isScrollable = false)
        composeRule.onAllNodesWithText(str(R.string.wallpaper_scrollable_desc)).assertCountEquals(0)
        composeRule.onNodeWithText(str(R.string.wallpaper_scrollable)).assertIsDisplayed()
    }

    @Test
    fun controlsScrollableSwitchForwards() {
        var scrolled: Boolean? = null
        setControls(isScrollable = false, onScrollableChange = { scrolled = it })
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(true, scrolled)
    }

    @Test
    fun controlsSettingShowsProgressAndDisables() {
        var sets = 0
        setControls(isSetting = true, onSetWallpaper = { sets++ })
        composeRule.onNodeWithText(str(R.string.setting_wallpaper)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.setting_wallpaper)).assertIsNotEnabled()
        assertEquals(0, sets)
    }

    @Test
    fun controlsSetDisabledWhenCannotSet() {
        setControls(canSet = false)
        composeRule.onNodeWithText(str(R.string.set_wallpaper)).assertIsNotEnabled()
    }

    @Test
    fun controlsSetButtonFires() {
        var sets = 0
        setControls(onSetWallpaper = { sets++ })
        composeRule.onNodeWithText(str(R.string.set_wallpaper)).assertIsEnabled()
        composeRule.onNodeWithText(str(R.string.set_wallpaper)).performClick()
        assertEquals(1, sets)
    }

    // ---- WallpaperPreview load states ----

    @Test
    fun previewFailedShowsError() {
        composeRule.setContent {
            WallpaperPreview(
                bitmap = null,
                loadState = WallpaperLoadState.Failed,
                targetAspect = 0.5f,
                zoomScale = 1f,
                zoomOffset = androidx.compose.ui.geometry.Offset.Zero,
                currentScale = androidx.compose.runtime.mutableStateOf(1f),
                currentOffset = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero),
                onZoomChange = { _, _ -> },
                onCoverMin = {},
                onSnapshot = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.wallpaper_load_failed)).assertIsDisplayed()
    }

    // ---- groupPhotosByMonth ----

    private var nextId = 100L

    private fun photo(name: String, date: Long) = Photo(
        id = nextId++,
        name = name,
        uri = "content://media/$name",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = true,
        lat = null,
        long = null,
        videoData = null,
        panoData = null,
    )

    @Test
    fun groupingBucketsByMonthNewestFirst() {
        // Jan 15 and Feb 10 2024 (UTC-ish; grouping uses the device zone but the
        // months differ regardless).
        val jan = photo("jan", 1_705_320_000_000L)
        val jan2 = photo("jan2", 1_705_400_000_000L)
        val feb = photo("feb", 1_707_744_000_000L)
        val grouped = groupPhotosByMonth(listOf(jan, jan2, feb), context.resources)
        assertEquals(2, grouped.size)
        val keys = grouped.keys.toList()
        // Feb bucket sorts before January.
        assertTrue(grouped.values.first().contains(feb))
        assertEquals(2, grouped.values.last().size)
        assertNotNull(grouped)
        assertTrue(keys[0] != keys[1])
    }

    @Test
    fun groupingEmptyIsEmpty() {
        assertTrue(groupPhotosByMonth(emptyList(), context.resources).isEmpty())
    }

    @Test
    fun albumCountIsPhotoCount() {
        val a = photo("a", 100L)
        val b = photo("b", 200L)
        val album = Album(name = "Trip", coverPhoto = b, photos = listOf(a, b))
        assertEquals(2, album.count)
        // Default cover is the newest photo; unknown bucket uses its sentinel.
        assertEquals(b.id, album.coverPhoto.id)
        assertEquals("__unknown__", Album.UNKNOWN_NAME)
    }
}
