package com.vayunmathur.camera.platform.camera2

import android.graphics.SurfaceTexture
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bridge lifecycle: a stream size pinned onto one SurfaceTexture must be
 * re-pinned onto the *next* texture after a destroy (resume re-attach),
 * even when the selected stream size itself is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewSurfaceBridgeTest {

    @Test fun newTextureAfterDestroyIsPinnedAgain() {
        val bridge = PreviewSurfaceBridge { }
        val first = mock(SurfaceTexture::class.java)
        val second = mock(SurfaceTexture::class.java)
        val stream = FrameSize(1920, 1080)

        bridge.onSurfaceTextureAvailable(first, 100, 100)
        bridge.setPreviewStream(stream, 90, null)
        verify(first).setDefaultBufferSize(1920, 1080)

        // Background: the texture dies. Resume: a new texture arrives and
        // the same stream size is selected again.
        assertTrue(bridge.onSurfaceTextureDestroyed(first))
        bridge.onSurfaceTextureAvailable(second, 100, 100)
        bridge.setPreviewStream(stream, 90, null)

        // Before the fix the unchanged-size check skipped this call, so the
        // resume configure ran against the new texture's default buffer
        // size and failed once (a fresh bridge, as on Retry, worked).
        verify(second).setDefaultBufferSize(1920, 1080)
    }
}
