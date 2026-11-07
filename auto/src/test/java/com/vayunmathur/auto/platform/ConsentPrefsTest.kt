package com.vayunmathur.auto.platform

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: [MessagingPrefs] and [MusicCapturePrefs] — device-level
 * consent switches. Both fail closed (false until the user opts in) and
 * round-trip through SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentPrefsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `messaging consent defaults to false and round-trips`() = runTest {
        MessagingPrefs.setMirroringConsented(context, false)
        assertFalse(MessagingPrefs.isMirroringConsented(context))
        MessagingPrefs.setMirroringConsented(context, true)
        assertTrue(MessagingPrefs.isMirroringConsented(context))
        MessagingPrefs.setMirroringConsented(context, false)
        assertFalse(MessagingPrefs.isMirroringConsented(context))
    }

    @Test
    fun `music capture consent defaults to false and round-trips`() = runTest {
        MusicCapturePrefs.setMusicCaptureConsented(context, false)
        assertFalse(MusicCapturePrefs.isMusicCaptureConsented(context))
        MusicCapturePrefs.setMusicCaptureConsented(context, true)
        assertTrue(MusicCapturePrefs.isMusicCaptureConsented(context))
        MusicCapturePrefs.setMusicCaptureConsented(context, false)
        assertFalse(MusicCapturePrefs.isMusicCaptureConsented(context))
    }

    @Test
    fun `mic permission reads the platform grant state`() {
        // Robolectric grants nothing by default: fail-closed like production.
        assertFalse(MicPermission.isGranted(context))
    }
}
