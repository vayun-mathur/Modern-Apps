package com.vayunmathur.photos.util

import androidx.compose.ui.unit.IntSize
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.PanoData
import com.vayunmathur.photos.data.parseOcrLayout
import com.vayunmathur.photos.data.toJson
import com.vayunmathur.photos.domain.MotionPhotoXmp
import com.vayunmathur.photos.ui.imageFitTransform
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for slice 3/3 (ViewModels and remaining screens): the portable
 * logic those screens stand on — undo history, XMP parsing, the shared Fit
 * letterbox, OCR layout JSON, and the gallery UI contract defaults.
 *
 * Robolectric (not plain JVM): [parseOcrLayout]'s failure path logs through
 * `android.util.Log`, which needs the framework shadows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3JvmTest {

    // ---- UndoRedoManager ----

    @Test
    fun undoRedoBasicFlow() {
        val history = UndoRedoManager<String>()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo("current"))
        assertNull(history.redo("current"))

        history.push("a")
        assertTrue(history.canUndo)
        history.push("b")
        assertEquals("b", history.undo("c"))
        assertTrue(history.canRedo)
        assertEquals("c", history.redo("b2"))
        assertFalse(history.canRedo)
    }

    @Test
    fun pushClearsRedo() {
        val history = UndoRedoManager<Int>()
        history.push(1)
        history.push(2)
        assertEquals(2, history.undo(3))
        history.push(3)
        assertFalse(history.canRedo)
        assertNull(history.redo(3))
    }

    @Test
    fun capacityEvictsOldest() {
        val history = UndoRedoManager<Int>(capacity = 2)
        history.push(1)
        history.push(2)
        history.push(3)
        // Only 2 and 3 survive; undoing twice reaches 2, third undo is empty.
        assertEquals(3, history.undo(99))
        assertEquals(2, history.undo(98))
        assertNull(history.undo(97))
    }

    @Test
    fun clearResetsBothStacks() {
        val history = UndoRedoManager<Int>()
        history.push(1)
        history.undo(2)
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    // ---- GalleryUiState / GalleryActions defaults ----

    @Test
    fun galleryUiStateDefaultsAreEmpty() {
        val state = GalleryUiState()
        assertTrue(state.photos.isEmpty())
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isRefreshing)
        assertEquals("", state.searchQuery)
        assertTrue(state.searchResults.isEmpty())
        assertEquals(SearchAiState.READY, state.searchAiState)
    }

    @Test
    fun peopleAndAlbumsStatesDefaultEmpty() {
        assertTrue(PeopleUiState().people.isEmpty())
        assertTrue(PeopleUiState().modelsAvailable)
        assertTrue(AlbumsUiState().albums.isEmpty())
        assertTrue(AlbumsUiState().defaultAlbums.isEmpty())
    }

    @Test
    fun galleryActionsNoopDoesNothing() {
        // Must not throw: previews render screens with GalleryActions.Noop.
        GalleryActions.Noop.setSearchQuery("x")
        GalleryActions.Noop.toggleSelection(1L)
        GalleryActions.Noop.clearSelection()
        GalleryActions.Noop.runSync()
        GalleryActions.Noop.moveSelectionToSecureFolder()
        GalleryActions.Noop.trashSelection()
        GalleryActions.Noop.addSelectionToAlbum("new")
    }

    // ---- MotionPhotoXmp ----

    @Test
    fun motionPhotoParsesAttributeOffset() {
        val xmp = """<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="12345"/>"""
        assertEquals(12345L, MotionPhotoXmp.parseMicroVideoOffset(xmp))
    }

    @Test
    fun motionPhotoParsesElementOffset() {
        val xmp = """<GCamera:MicroVideo>1</GCamera:MicroVideo><GCamera:MicroVideoOffset>999</GCamera:MicroVideoOffset>"""
        assertEquals(999L, MotionPhotoXmp.parseMicroVideoOffset(xmp))
    }

    @Test
    fun motionPhotoRejectsNonMotion() {
        assertNull(MotionPhotoXmp.parseMicroVideoOffset(null))
        assertNull(MotionPhotoXmp.parseMicroVideoOffset(""))
        assertNull(MotionPhotoXmp.parseMicroVideoOffset("""<rdf:Description GCamera:MicroVideo="0" GCamera:MicroVideoOffset="5"/>"""))
        assertNull(MotionPhotoXmp.parseMicroVideoOffset("""<rdf:Description GCamera:MicroVideo="1"/>"""))
        assertNull(MotionPhotoXmp.parseMicroVideoOffset("""<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="0"/>"""))
        assertNull(MotionPhotoXmp.parseMicroVideoOffset("""<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="-7"/>"""))
        assertNull(MotionPhotoXmp.parseMicroVideoOffset("""<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="abc"/>"""))
    }

    // ---- PanoXmpParser ----

    private fun panoXmp(projection: String = "equirectangular") = """
        <rdf:Description GPano:UsePanoramaViewer="True"
          GPano:ProjectionType="$projection"
          GPano:FullPanoWidthPixels="8000" GPano:FullPanoHeightPixels="4000"
          GPano:CroppedAreaImageWidthPixels="8000" GPano:CroppedAreaImageHeightPixels="2000"
          GPano:CroppedAreaLeftPixels="0" GPano:CroppedAreaTopPixels="1000"/>
    """.trimIndent()

    @Test
    fun panoParserReadsSphere() {
        val pano = PanoXmpParser.parse(panoXmp())
        assertNotNull(pano)
        assertEquals(8000, pano.fullWidth)
        assertEquals(4000, pano.fullHeight)
        assertEquals(2000, pano.croppedHeight)
        assertEquals(1000, pano.croppedTop)
        assertTrue(pano.isSphere)
    }

    @Test
    fun panoParserReadsCylindrical() {
        val pano = PanoXmpParser.parse(panoXmp("cylindrical"))
        assertNotNull(pano)
        assertFalse(pano.isSphere)
        assertEquals("cylindrical", pano.projectionType)
    }

    @Test
    fun panoParserRejectsNonPanoramas() {
        assertNull(PanoXmpParser.parse(null))
        assertNull(PanoXmpParser.parse(""))
        assertNull(PanoXmpParser.parse("""<rdf:Description GPano:ProjectionType="equirectangular"/>"""))
        assertNull(PanoXmpParser.parse(panoXmp("perspective")))
        // Missing required dimensions.
        assertNull(PanoXmpParser.parse("""<rdf:Description GPano:UsePanoramaViewer="true" GPano:ProjectionType="equirectangular"/>"""))
    }

    @Test
    fun panoDataLegacyNullProjectionIsSphere() {
        assertTrue(PanoData(1, 1, 1, 1, 0, 0, null).isSphere)
        assertTrue(PanoData(1, 1, 1, 1, 0, 0, "Equirectangular").isSphere)
    }

    // ---- imageFitTransform ----

    @Test
    fun fitExactMatchHasUnitScaleAndZeroOrigin() {
        val t = imageFitTransform(200, 100, IntSize(200, 100))
        assertNotNull(t)
        assertEquals(1f, t.scale, 1e-6f)
        assertEquals(0f, t.originX, 1e-6f)
        assertEquals(0f, t.originY, 1e-6f)
    }

    @Test
    fun fitLetterboxesWideImage() {
        // 200x100 into 100x100: scale 0.5, 25px bars top and bottom.
        val t = imageFitTransform(200, 100, IntSize(100, 100))
        assertNotNull(t)
        assertEquals(0.5f, t.scale, 1e-6f)
        assertEquals(0f, t.originX, 1e-6f)
        assertEquals(25f, t.originY, 1e-6f)
    }

    @Test
    fun fitPillarsTallImage() {
        val t = imageFitTransform(100, 200, IntSize(100, 100))
        assertNotNull(t)
        assertEquals(0.5f, t.scale, 1e-6f)
        assertEquals(25f, t.originX, 1e-6f)
        assertEquals(0f, t.originY, 1e-6f)
    }

    @Test
    fun fitRejectsDegenerateInputs() {
        assertNull(imageFitTransform(0, 100, IntSize(100, 100)))
        assertNull(imageFitTransform(100, -1, IntSize(100, 100)))
        assertNull(imageFitTransform(100, 100, IntSize(0, 100)))
        assertNull(imageFitTransform(100, 100, IntSize(100, 0)))
    }

    // ---- OcrLayout JSON ----

    @Test
    fun ocrLayoutTextJoinsLines() {
        val layout = OcrLayout(
            w = 640,
            h = 480,
            boxes = listOf(
                OcrBox("hello", 0, 0, 10, 10),
                OcrBox("world", 0, 12, 10, 22),
            ),
        )
        assertEquals("hello\nworld", layout.text)
    }

    @Test
    fun ocrLayoutRoundTripsThroughJson() {
        val layout = OcrLayout(
            w = 640,
            h = 480,
            boxes = listOf(
                OcrBox(
                    text = "tilted",
                    left = 1,
                    top = 2,
                    right = 30,
                    bottom = 12,
                    quad = listOf(1f, 2f, 30f, 3f, 29f, 12f, 0f, 11f),
                    vertical = true,
                ),
            ),
        )
        assertEquals(layout, parseOcrLayout(layout.toJson()))
    }

    @Test
    fun ocrLayoutParseRejectsBadInputs() {
        assertNull(parseOcrLayout(null))
        assertNull(parseOcrLayout(""))
        assertNull(parseOcrLayout("   "))
        assertNull(parseOcrLayout("not json"))
        assertNull(parseOcrLayout("""{"w":0,"h":0,"boxes":[]}"""))
    }

    // ---- AlbumMediaStore pure helpers ----

    @Test
    fun sanitizeAlbumNameTrimsAndFlattensSeparators() {
        assertEquals("Trip", AlbumMediaStore.sanitizeAlbumName("  Trip  "))
        assertEquals("a_b", AlbumMediaStore.sanitizeAlbumName("a/b"))
        assertEquals("a_b", AlbumMediaStore.sanitizeAlbumName("a\\\\b"))
        assertEquals("a_b", AlbumMediaStore.sanitizeAlbumName("a//b"))
        assertEquals("", AlbumMediaStore.sanitizeAlbumName("   "))
    }

    @Test
    fun urisOfMapsPhotoUris() {
        val a = com.vayunmathur.photos.data.Photo(
            id = 1, name = "a", uri = "content://media/a", date = 1,
            width = 1, height = 1, dateModified = 1, exifSet = true,
            lat = null, long = null, videoData = null, panoData = null,
        )
        assertEquals(listOf(android.net.Uri.parse("content://media/a")), AlbumMediaStore.urisOf(listOf(a)))
        assertEquals(emptyList(), AlbumMediaStore.urisOf(emptyList()))
    }
}
