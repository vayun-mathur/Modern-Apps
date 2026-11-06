package com.vayunmathur.photos.photoslice2

import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.PhotoFilters
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.data.VideoTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice-2 tests for [VideoEditState] predicates, [VideoFilterPreset] /
 * [VideoTool] enums, and the [PhotoFilters] preset catalog. Pure data —
 * no framework needed.
 */
class PhotoSlice2VideoStateTest {

    @Test
    fun defaultStateHasNoEdits() {
        val state = VideoEditState(durationMs = 10_000L, trimEndMs = 10_000L)
        assertFalse(state.isTrimmed)
        assertFalse(state.isCropped)
        assertFalse(state.hasEdits)
        assertFalse(state.hasVideoEffects)
    }

    @Test
    fun trimDetection() {
        val full = VideoEditState(durationMs = 10_000L, trimStartMs = 0L, trimEndMs = 10_000L)
        assertFalse(full.isTrimmed)
        val cutHead = VideoEditState(durationMs = 10_000L, trimStartMs = 1_000L, trimEndMs = 10_000L)
        assertTrue(cutHead.isTrimmed)
        assertTrue(cutHead.hasEdits)
        val cutTail = VideoEditState(durationMs = 10_000L, trimStartMs = 0L, trimEndMs = 9_000L)
        assertTrue(cutTail.isTrimmed)
        // Zero duration never counts as trimmed.
        assertFalse(VideoEditState().isTrimmed)
    }

    @Test
    fun cropDetection() {
        assertFalse(VideoEditState().isCropped)
        // Partial bounds (nulls) are not a crop.
        assertFalse(VideoEditState(cropLeft = 0.1f).isCropped)
        val full = VideoEditState(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f)
        assertFalse(full.isCropped)
        val cropped = VideoEditState(cropLeft = 0.1f, cropTop = 0f, cropRight = 1f, cropBottom = 1f)
        assertTrue(cropped.isCropped)
        assertTrue(cropped.hasEdits)
        assertTrue(cropped.hasVideoEffects)
    }

    @Test
    fun editFlags() {
        assertTrue(VideoEditState(rotationDegrees = 90).hasEdits)
        assertTrue(VideoEditState(rotationDegrees = 90).hasVideoEffects)
        assertTrue(VideoEditState(flipHorizontal = true).hasEdits)
        assertTrue(VideoEditState(muted = true).hasEdits)
        // Mute is not a video effect.
        assertFalse(VideoEditState(muted = true).hasVideoEffects)
        assertTrue(VideoEditState(brightness = 0.5f).hasEdits)
        assertTrue(VideoEditState(filterPreset = VideoFilterPreset.Mono).hasEdits)
        assertTrue(VideoEditState(filterPreset = VideoFilterPreset.Vivid).hasVideoEffects)
    }

    @Test
    fun videoToolsAndPresetsEnumerated() {
        assertEquals(4, VideoTool.entries.size)
        assertTrue(VideoTool.entries.contains(VideoTool.Trim))
        assertEquals(5, VideoFilterPreset.entries.size)
        assertEquals(VideoFilterPreset.None, VideoFilterPreset.entries.first())
    }

    @Test
    fun photoFiltersCatalog() {
        assertTrue(PhotoFilters.all.size >= 15, "catalog size=${PhotoFilters.all.size}")
        assertEquals("None", PhotoFilters.all.first().name)
        // Names are unique.
        assertEquals(PhotoFilters.all.size, PhotoFilters.all.map { it.name }.distinct().size)
        // Mono is full desaturation.
        val mono = PhotoFilters.all.first { it.name == "Mono" }
        assertEquals(-100f, mono.adjustments.saturation)
        // None is the identity.
        assertEquals(com.vayunmathur.photos.data.ImageAdjustments(), PhotoFilters.all.first().adjustments)
    }

    @Test
    fun photoFilterDataClass() {
        val f = PhotoFilter("Custom", com.vayunmathur.photos.data.ImageAdjustments(contrast = 10f))
        assertEquals("Custom", f.name)
        assertEquals(10f, f.adjustments.contrast)
        assertEquals(f, f.copy())
    }
}
