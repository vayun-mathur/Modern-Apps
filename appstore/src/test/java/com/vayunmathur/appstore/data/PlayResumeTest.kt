package com.vayunmathur.appstore.data

import com.vayunmathur.appstore.data.installer.PlayDownloader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: a server that ignores the Range header answers 200 with the
 * whole body. Appending that to the partial .tmp corrupted the APK, so a
 * resumed Play download failed verification instead of restarting clean.
 */
class PlayResumeTest {

    @Test
    fun `206 with a partial file resumes`() {
        assertTrue(PlayDownloader.resumesPartial(206, existingBytes = 512))
    }

    @Test
    fun `200 restarts instead of appending`() {
        assertFalse(PlayDownloader.resumesPartial(200, existingBytes = 512))
    }

    @Test
    fun `nothing to resume from`() {
        assertFalse(PlayDownloader.resumesPartial(206, existingBytes = 0))
        assertFalse(PlayDownloader.resumesPartial(200, existingBytes = 0))
    }
}
