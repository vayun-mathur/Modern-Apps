package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.WearCameraOp
import com.vayunmathur.camera.domain.WearCameraState
import com.vayunmathur.camera.domain.WearOpDecision
import com.vayunmathur.camera.domain.WearOpKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Op application against the camera's mode/zoom seams, via a recording fake control. */
class WearCameraOpApplicationTest {

    private class FakeControls : WearCameraControls {
        val calls = mutableListOf<String>()

        override fun shutter() { calls += "shutter" }
        override fun toggleRecording() { calls += "record" }
        override fun setMode(modeName: String) { calls += "mode:$modeName" }
        override fun setZoom(ratio: Float) { calls += "zoom:$ratio" }
        override fun setFlash(flashName: String) { calls += "flash:$flashName" }
        override fun setTorch(enabled: Boolean) { calls += "torch:$enabled" }
        override fun flipLens() { calls += "flip" }
        override fun setTimer(seconds: Int) { calls += "timer:$seconds" }
    }

    private val idle = WearCameraState(
        mode = "PHOTO",
        zoomPresets = listOf(0.5f, 1f, 2f, 5f),
        zoomMin = 0.5f,
        zoomMax = 10f,
    )

    @Test
    fun shutterRecordModeZoomAndSettingsApply() {
        val controls = FakeControls()
        assertEquals(WearOpDecision.Apply, executeWearOp(WearCameraOp(WearOpKind.SHUTTER), idle, controls))
        assertEquals(
            WearOpDecision.Apply,
            executeWearOp(WearCameraOp(WearOpKind.SET_MODE, mode = "VIDEO"), idle, controls),
        )
        assertEquals(
            WearOpDecision.Apply,
            executeWearOp(WearCameraOp(WearOpKind.SET_ZOOM, zoomRatio = 99f), idle, controls),
        )
        assertEquals(
            WearOpDecision.Apply,
            executeWearOp(WearCameraOp(WearOpKind.ZOOM_PRESET, zoomPresetIndex = 3), idle, controls),
        )
        assertEquals(
            WearOpDecision.Apply,
            executeWearOp(WearCameraOp(WearOpKind.SET_FLASH, flash = "ON"), idle, controls),
        )
        assertEquals(
            WearOpDecision.Apply,
            executeWearOp(WearCameraOp(WearOpKind.SET_TORCH, torch = true), idle, controls),
        )
        assertEquals(
            WearOpDecision.Apply,
            executeWearOp(WearCameraOp(WearOpKind.SET_TIMER, timerSeconds = 10), idle, controls),
        )
        assertEquals(WearOpDecision.Apply, executeWearOp(WearCameraOp(WearOpKind.FLIP_LENS), idle, controls))
        // Zoom clamps to the mirrored device range before it reaches the seam.
        assertEquals(
            listOf("shutter", "mode:VIDEO", "zoom:10.0", "zoom:5.0", "flash:ON", "torch:true", "timer:10", "flip"),
            controls.calls,
        )
    }

    @Test
    fun rejectedOpsNeverReachTheCamera() {
        val controls = FakeControls()
        val recording = idle.copy(recording = true)
        assertTrue(
            executeWearOp(WearCameraOp(WearOpKind.SET_MODE, mode = "VIDEO"), recording, controls)
                is WearOpDecision.Reject,
        )
        assertTrue(
            executeWearOp(WearCameraOp(WearOpKind.SET_MODE, mode = "PANORAMA"), idle, controls)
                is WearOpDecision.Reject,
        )
        assertTrue(
            executeWearOp(WearCameraOp(WearOpKind.SET_TIMER, timerSeconds = 7), idle, controls)
                is WearOpDecision.Reject,
        )
        assertEquals(emptyList(), controls.calls)
    }

    @Test
    fun stalePresetIndexIsANoOpNotACrash() {
        val controls = FakeControls()
        val decision = executeWearOp(WearCameraOp(WearOpKind.ZOOM_PRESET, zoomPresetIndex = 99), idle, controls)
        assertEquals(WearOpDecision.Apply, decision)
        assertEquals(emptyList(), controls.calls)
    }

    @Test
    fun previewOpsDoNotTouchCameraControls() {
        val controls = FakeControls()
        executeWearOp(WearCameraOp(WearOpKind.PREVIEW_START), idle, controls)
        executeWearOp(WearCameraOp(WearOpKind.PREVIEW_STOP), idle, controls)
        assertEquals(emptyList(), controls.calls)
    }
}
