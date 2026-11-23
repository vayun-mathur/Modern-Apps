package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PreviewEnginePolicyTest {

    @Test fun supportedModesIsAllModes() {
        assertEquals(CameraMode.entries.toSet(), PreviewEnginePolicy.supportedModes)
    }

    @Test fun everyModeUsesCamera2WhenUnlatched() {
        // No off switch exists: every mode previews through Camera2.
        for (mode in CameraMode.entries) {
            assertTrue(PreviewEnginePolicy.usesCamera2(mode), "$mode must use Camera2")
            assertNull(PreviewEnginePolicy.exclusionReason(mode), "$mode")
        }
    }

    @Test fun latchedBindFailureIsTheOnlyExclusion() {
        for (mode in CameraMode.entries) {
            assertFalse(PreviewEnginePolicy.usesCamera2(mode, fallbackLatched = true), "$mode")
            assertTrue(
                PreviewEnginePolicy.exclusionReason(mode, fallbackLatched = true)!!
                    .contains("latched"),
                "$mode",
            )
        }
    }

    @Test fun ringGatesDefaultOn() {
        assertTrue(ReprocessRingGate.ENABLED)
        assertTrue(RawRingGate.ENABLED)
    }

    @Test fun fallbackLatchIsOneShotPerBindKey() {
        val latch = Camera2FallbackLatch()
        val key = Camera2FallbackLatch.bindKey("0", CameraMode.PHOTO)
        assertFalse(latch.isLatched(key))
        assertTrue(latch.recordFailure(key), "first failure must latch the bind error")
        assertTrue(latch.isLatched(key))
        assertFalse(latch.recordFailure(key), "second failure for same key must not re-trigger")
        val other = Camera2FallbackLatch.bindKey("1", CameraMode.PHOTO)
        assertFalse(latch.isLatched(other))
        assertTrue(latch.recordFailure(other))
        latch.reset()
        assertFalse(latch.isLatched(key))
        assertTrue(latch.recordFailure(key))
        latch.clear(key)
        assertFalse(latch.isLatched(key))
    }

    @Test fun latchedBindUsesNoPreviewUntilRetryClearsLatch() {
        val latch = Camera2FallbackLatch()
        val key = Camera2FallbackLatch.bindKey("0", CameraMode.PHOTO)
        latch.recordFailure(key)
        assertFalse(
            PreviewEnginePolicy.usesCamera2(
                CameraMode.PHOTO, fallbackLatched = latch.isLatched(key),
            ),
        )
        latch.clear(key)
        assertTrue(
            PreviewEnginePolicy.usesCamera2(
                CameraMode.PHOTO, fallbackLatched = latch.isLatched(key),
            ),
        )
    }
}
