package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val decoded = PreviewFrame.decode(frame)
        assertNotNull(decoded)
        assertEquals(0x01020304L, decoded.sequence)
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
        assertEquals(90, decoded.rotationDegrees)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(decoded.jpeg))
    }

    @Test
    fun stateAndOpRoundTripAndMalformedIgnored() {
        val state = WearCameraState(mode = "PORTRAIT", zoomRatio = 1.5f)
        assertEquals(state, decodeWearCameraState(encodeWearCameraState(state)))
        val op = WearCameraOp(WearOpKind.SET_TIMER, timerSeconds = 5)
        assertEquals(op, decodeWearCameraOp(encodeWearCameraOp(op)))
        assertNull(decodeWearCameraState("not json".encodeToByteArray()))
        assertNull(decodeWearCameraOp(byteArrayOf(0)))
        assertNull(PreviewFrame.decode(byteArrayOf(1, 2, 3)))
        assertNull(PreviewFrame.decode(PreviewFrame.encode(1L, 1L, 0, 240, 0, byteArrayOf(1))))
    }

    @Test
    fun planRejectsNonRemoteModesAndRecordingSwitches() {
        val idle = WearCameraState(mode = "PHOTO")
        assertEquals(
            WearOpDecision.Apply,
            planWearCameraOp(WearCameraOp(WearOpKind.SET_MODE, mode = "VIDEO"), idle),
        )
        assertTrue(
            planWearCameraOp(WearCameraOp(WearOpKind.SET_MODE, mode = "PANORAMA"), idle)
                is WearOpDecision.Reject,
        )
        val recording = idle.copy(recording = true)
        assertTrue(
            planWearCameraOp(WearCameraOp(WearOpKind.SET_MODE, mode = "VIDEO"), recording)
                is WearOpDecision.Reject,
        )
        assertTrue(
            planWearCameraOp(WearCameraOp(WearOpKind.FLIP_LENS), recording) is WearOpDecision.Reject,
        )
        // Shutter/record and torch stay live while recording.
        assertEquals(WearOpDecision.Apply, planWearCameraOp(WearCameraOp(WearOpKind.RECORD_TOGGLE), recording))
        assertEquals(
            WearOpDecision.Apply,
            planWearCameraOp(WearCameraOp(WearOpKind.SET_TORCH, torch = true), recording),
        )
    }

    @Test
    fun planValidatesFlashTimerAndZoomLock() {
        val state = WearCameraState(mode = "PHOTO")
        assertEquals(
            WearOpDecision.Apply,
            planWearCameraOp(WearCameraOp(WearOpKind.SET_FLASH, flash = "AUTO"), state),
        )
        assertTrue(
            planWearCameraOp(WearCameraOp(WearOpKind.SET_FLASH, flash = "STROBE"), state)
                is WearOpDecision.Reject,
        )
        assertTrue(
            planWearCameraOp(WearCameraOp(WearOpKind.SET_TIMER, timerSeconds = 7), state)
                is WearOpDecision.Reject,
        )
        val pano = state.copy(mode = "PANORAMA")
        assertTrue(
            planWearCameraOp(WearCameraOp(WearOpKind.SET_ZOOM, zoomRatio = 2f), pano)
                is WearOpDecision.Reject,
        )
    }

    @Test
    fun zoomClampAndPresetLookup() {
        val state = WearCameraState(zoomRatio = 1f, zoomPresets = listOf(0.5f, 1f, 2f), zoomMin = 0.5f, zoomMax = 10f)
        assertEquals(10f, clampWearZoom(99f, state))
        assertEquals(0.5f, clampWearZoom(0.1f, state))
        assertEquals(1f, clampWearZoom(Float.NaN, state))
        assertEquals(2f, wearZoomPreset(state, 2))
        assertNull(wearZoomPreset(state, 5))
    }
}
