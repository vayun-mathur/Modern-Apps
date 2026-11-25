package com.vayunmathur.camera.wear.domain

import com.vayunmathur.camera.wear.data.WearCameraStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearCameraProtocolTest {

    @Test
    fun goldenStateJson() {
        val state = WearCameraState(
            mode = "VIDEO",
            frontLens = true,
            zoomRatio = 2f,
            zoomPresets = listOf(1f, 2f),
            zoomMin = 0.5f,
            zoomMax = 10f,
            flash = "AUTO",
            torch = true,
            timerSeconds = 3,
            recording = true,
            recordingSeconds = 65L,
            previewStreaming = true,
            lastCaptureAtMillis = 7L,
            lastCaptureKind = "PHOTO",
        )
        assertEquals(
            """{"mode":"VIDEO","frontLens":true,"zoomRatio":2.0,"zoomPresets":[1.0,2.0],"zoomMin":0.5,""" +
                """"zoomMax":10.0,"flash":"AUTO","torch":true,"timerSeconds":3,"recording":true,""" +
                """"recordingSeconds":65,"previewStreaming":true,"lastCaptureAtMillis":7,"lastCaptureKind":"PHOTO"}""",
            encodeWearCameraState(state).decodeToString(),
        )
    }

    @Test
    fun goldenOpJson() {
        val op = WearCameraOp(WearOpKind.SET_ZOOM, zoomRatio = 2.5f)
        assertEquals(
            """{"kind":"SET_ZOOM","zoomRatio":2.5}""",
            encodeWearCameraOp(op).decodeToString(),
        )
    }

    @Test
    fun goldenPreviewFrameHeader() {
        val frame = PreviewFrame.encode(
            sequence = 0x01020304L,
            timestampMillis = 0x05060708L,
            width = 320,
            height = 240,
            rotationDegrees = 90,
            jpeg = byteArrayOf(9, 8, 7),
        )
        assertEquals(
            listOf(
                'W'.code, 'C'.code, 'F'.code, '1'.code,
                1, 2, 3, 4, 5, 6, 7, 8, 1, 64, 0, 240, 0, 90, 0, 0, 9, 8, 7,
            ),
            frame.map { it.toInt() and 0xFF },
        )
        val decoded = PreviewFrame.decode(frame)!!
        assertEquals(0x01020304L, decoded.sequence)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(decoded.jpeg))
    }

    @Test
    fun stateAndOpRoundTrip() {
        val state = WearCameraState(mode = "PORTRAIT", zoomRatio = 1.5f)
        assertEquals(state, decodeWearCameraState(encodeWearCameraState(state)))
        val op = WearCameraOp(WearOpKind.SET_TIMER, timerSeconds = 5)
        assertEquals(op, decodeWearCameraOp(encodeWearCameraOp(op)))
        assertNull(decodeWearCameraState("not json".encodeToByteArray()))
    }

    @Test
    fun storeMirrorsStateAndDropsStaleFrames() {
        val store = WearCameraStore()
        val first = WearCameraState(mode = "VIDEO", zoomRatio = 2f, recording = true, recordingSeconds = 12L)
        store.applyState(first)
        assertEquals(first, store.state.value)
        // Mirroring replaces wholesale — nothing merges or lingers.
        val second = WearCameraState(mode = "PHOTO")
        store.applyState(second)
        assertEquals(second, store.state.value)

        val frame1 = PreviewFrame.encode(1L, 1L, 320, 240, 0, byteArrayOf(1))
        val frame2 = PreviewFrame.encode(2L, 2L, 320, 240, 90, byteArrayOf(2))
        assertTrue(store.applyPreviewFrame(frame1))
        assertTrue(store.applyPreviewFrame(frame2))
        assertTrue(byteArrayOf(2).contentEquals(store.previewJpeg.value!!))
        assertEquals(90, store.previewRotation.value)
        // Stale and replayed sequences never replace the newest frame.
        assertFalse(store.applyPreviewFrame(frame1))
        assertFalse(store.applyPreviewFrame(frame2))
        assertEquals(2L, store.droppedStaleFrames)
        assertFalse(store.applyPreviewFrame(byteArrayOf(0, 1, 2)))

        store.applyThumbnail(byteArrayOf(5, 6))
        assertTrue(byteArrayOf(5, 6).contentEquals(store.thumbnailJpeg.value!!))
        store.applyThumbnail(ByteArray(0))
        assertTrue(byteArrayOf(5, 6).contentEquals(store.thumbnailJpeg.value!!))
    }

    @Test
    fun linkDownClearsLiveFrameButKeepsMirroredState() {
        val store = WearCameraStore()
        store.applyState(WearCameraState(mode = "VIDEO"))
        store.applyPreviewFrame(PreviewFrame.encode(3L, 1L, 320, 240, 0, byteArrayOf(1)))
        store.setLinkConnected(true)
        assertEquals(true, store.linkConnected.value)
        store.setLinkConnected(false)
        assertNull(store.previewJpeg.value)
        assertEquals("VIDEO", store.state.value.mode)
    }
}
