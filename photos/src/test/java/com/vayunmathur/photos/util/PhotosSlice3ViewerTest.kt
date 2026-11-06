package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.ui.FaceBoxLayer
import com.vayunmathur.photos.ui.PhotoDetailSection
import com.vayunmathur.photos.ui.PhotoMetadataCard
import com.vayunmathur.photos.ui.PhotoViewerActions
import com.vayunmathur.photos.ui.PhotoViewerWideLayout
import com.vayunmathur.photos.ui.ZoomState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 Compose tests for the viewer pieces: [PhotoMetadataCard],
 * [PhotoViewerActions], [PhotoViewerWideLayout], [FaceBoxLayer] and
 * [PhotoDetailSection].
 *
 * Image decoding stays stubbed out (plain boxes / no-op media); what is
 * asserted is branching, labels, callbacks and face-overlay geometry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3ViewerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var nextId = 1L

    private fun photo(name: String, date: Long, video: Boolean = false) = Photo(
        id = nextId++,
        name = name,
        uri = "content://media/$name",
        date = date,
        width = 4000,
        height = 3000,
        dateModified = date,
        exifSet = true,
        lat = null,
        long = null,
        videoData = if (video) com.vayunmathur.photos.data.VideoData(duration = 1000L) else null,
        panoData = null,
    )

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- PhotoMetadataCard ----

    private fun setMetadata(
        photo: Photo,
        countryName: String? = "Paris, France",
        peopleCount: Int = 0,
        isSphere: Boolean = false,
        isMotionPhoto: Boolean = false,
        onEditPhoto: () -> Unit = {},
        onDelete: (Photo) -> Unit = {},
        onSetWallpaper: (Photo) -> Unit = {},
    ) {
        val app = context.applicationContext
        composeRule.setContent {
            PhotoMetadataCard(
                photo = photo,
                context = app as android.content.Context,
                countryName = countryName,
                fileSize = 2_500_000L,
                pageOffset = 0f,
                peopleCount = peopleCount,
                isSphere = isSphere,
                isMotionPhoto = isMotionPhoto,
                onSetWallpaper = onSetWallpaper,
                onEditPhoto = onEditPhoto,
                onDelete = onDelete,
            )
        }
    }

    @Test
    fun metadataShowsNameDateLocationAndResolution() {
        val p = photo("card.jpg", 1_700_000_000_000L)
        setMetadata(p)
        composeRule.onNodeWithText("card.jpg").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.resolution, 4000, 3000)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.location, "Paris, France")).assertIsDisplayed()
    }

    @Test
    fun metadataDetectingWhileCountryResolves() {
        setMetadata(photo("loc.jpg", 100L), countryName = null)
        composeRule.onNodeWithText(str(R.string.location, str(R.string.detecting)))
            .assertIsDisplayed()
    }

    @Test
    fun metadataShowsPeopleCountAndMotionBadge() {
        setMetadata(photo("m.jpg", 100L), peopleCount = 3, isMotionPhoto = true)
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.people_in_photo, 3, 3),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.motion_badge)).assertIsDisplayed()
    }

    @Test
    fun metadataButtonsForwardActions() {
        var edited = false
        var deleted: Photo? = null
        var wallpaper: Photo? = null
        val p = photo("act.jpg", 100L)
        setMetadata(p, onEditPhoto = { edited = true }, onDelete = { deleted = it }, onSetWallpaper = { wallpaper = it })
        composeRule.onNodeWithContentDescription("Edit").performClick()
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithContentDescription("Wallpaper").performClick()
        assertTrue(edited)
        assertEquals(p.id, deleted?.id)
        assertEquals(p.id, wallpaper?.id)
    }

    // ---- PhotoViewerActions ----

    private fun setViewerActions(
        isPanorama: Boolean,
        isSphere: Boolean = false,
        isMotionPhoto: Boolean = false,
        motionPlaying: Boolean = false,
        onOpenImmersive: () -> Unit = {},
        onMotionClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            androidx.compose.foundation.layout.Box {
                PhotoViewerActions(
                    isPanorama = isPanorama,
                    isSphere = isSphere,
                    isMotionPhoto = isMotionPhoto,
                    isMetadataVisible = true,
                    motionPlaying = motionPlaying,
                    motionExtracting = false,
                    onOpenImmersive = onOpenImmersive,
                    onMotionClick = onMotionClick,
                )
            }
        }
    }

    @Test
    fun viewerActionsHiddenForPlainPhoto() {
        setViewerActions(isPanorama = false, isMotionPhoto = false)
        composeRule.onAllNodesWithText(str(R.string.view_panorama)).assertCountEquals(0)
        composeRule.onAllNodesWithText(str(R.string.play_motion_photo)).assertCountEquals(0)
    }

    @Test
    fun viewerActionsPanoramaOpensImmersive() {
        var opened = false
        setViewerActions(isPanorama = true, isSphere = true, onOpenImmersive = { opened = true })
        composeRule.onNodeWithText(str(R.string.view_360)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.view_360)).performClick()
        assertTrue(opened)
    }

    @Test
    fun viewerActionsMotionPlaysAndStops() {
        var clicks = 0
        // Both states in one composition: playing and stopped rows side by side.
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box {
                    PhotoViewerActions(
                        isPanorama = false,
                        isSphere = false,
                        isMotionPhoto = true,
                        isMetadataVisible = true,
                        motionPlaying = false,
                        motionExtracting = false,
                        onOpenImmersive = {},
                        onMotionClick = { clicks++ },
                    )
                }
                androidx.compose.foundation.layout.Box {
                    PhotoViewerActions(
                        isPanorama = false,
                        isSphere = false,
                        isMotionPhoto = true,
                        isMetadataVisible = true,
                        motionPlaying = true,
                        motionExtracting = false,
                        onOpenImmersive = {},
                        onMotionClick = { clicks++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText(str(R.string.play_motion_photo)).performClick()
        composeRule.onNodeWithText(str(R.string.stop_motion_photo)).performClick()
        assertEquals(2, clicks)
    }

    // ---- PhotoViewerWideLayout ----

    @Test
    fun wideLayoutHostsBothSlots() {
        composeRule.setContent {
            PhotoViewerWideLayout(
                viewer = { modifier -> Box(modifier.testTag("slot-viewer")) },
                filmstrip = { modifier -> Box(modifier.testTag("slot-filmstrip")) },
            )
        }
        composeRule.onNodeWithTag("slot-viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("slot-filmstrip").assertIsDisplayed()
    }

    // ---- FaceBoxLayer ----

    private fun boxes(vararg faces: FaceBox, w: Int = 4000, h: Int = 3000) = PhotoFaceBoxes(
        srcWidth = w,
        srcHeight = h,
        faces = faces.toList(),
    )

    private fun face(id: Long, name: String? = "Ada") = FaceBox(
        clusterId = id,
        name = name,
        left = 0.25f,
        top = 0.25f,
        right = 0.5f,
        bottom = 0.5f,
    )

    private fun setFaceBoxes(
        boxes: PhotoFaceBoxes,
        onFaceClick: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            FaceBoxLayer(
                boxes = boxes,
                containerSize = IntSize(400, 300),
                zoom = { 1f },
                onFaceClick = onFaceClick,
                modifier = Modifier.testTag("facelayer"),
            )
        }
    }

    @Test
    fun faceLayerLabelsNamedFacesAndTapsOpenPerson() {
        var opened: Long? = null
        setFaceBoxes(boxes(face(7L, "Ada"), face(9L, null)), onFaceClick = { opened = it })
        // Named face gets a label; unnamed one renders outline only.
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        // Tap targets carry no text: click inside the face box's screen rect.
        // 4000x3000 source fits exactly into the 400x300 container (scale 0.1),
        // so the 0.25-0.5 box maps to pixels x 100..200, y 75..150.
        composeRule.onNodeWithTag("facelayer", useUnmergedTree = true)
            .performTouchInput { click(Offset(150f, 100f)) }
        composeRule.waitForIdle()
        assertEquals(7L, opened)
    }

    @Test
    fun faceLayerDropsDegenerateBoxes() {
        // Zero-area box: right == left, so placement filters it and nothing shows.
        val flat = face(3L, "Flat").copy(right = 0.25f)
        setFaceBoxes(boxes(flat))
        composeRule.onAllNodesWithText("Flat").assertCountEquals(0)
    }

    // ---- PhotoDetailSection ----

    @Test
    fun detailSectionShowsMetadataAndPanoramaAction() {
        var immersive = false
        val p = photo("sect.jpg", 100L)
        val zoom = mutableStateOf(ZoomState())
        composeRule.setContent {
            androidx.compose.foundation.layout.Box {
                PhotoDetailSection(
                    photo = p,
                    context = context.applicationContext as android.content.Context,
                    countryName = null,
                    fileSize = null,
                    pageOffset = 0f,
                    peopleCount = 0,
                    isSphere = false,
                    isMotionPhoto = false,
                    isPanorama = true,
                    isMetadataVisible = true,
                    showImmersive = false,
                    motionPlaying = false,
                    motionExtracting = false,
                    faceBoxes = null,
                    ocrLayout = null,
                    ocrClearToken = 0,
                    isSettled = true,
                    size = IntSize(400, 300),
                    currentZoom = zoom,
                    zoomModifier = Modifier,
                    onOpenPerson = {},
                    onSetWallpaper = {},
                    onEditPhoto = {},
                    onDelete = {},
                    onShowImmersive = { immersive = it },
                    onMotionClick = {},
                )
            }
        }
        // Metadata card renders the name; panorama chip is visible.
        composeRule.onNodeWithText("sect.jpg").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.view_panorama)).assertIsDisplayed()
    }
}
