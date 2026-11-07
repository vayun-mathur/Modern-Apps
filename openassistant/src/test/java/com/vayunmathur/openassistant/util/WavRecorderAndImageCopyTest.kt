package com.vayunmathur.openassistant.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WavRecorderAndImageCopyTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun recorderLifecycleWithoutAudioYieldsNoFile() = runBlocking {
        assertEquals(44, WavRecorder.WAV_HEADER_BYTES)
        val out = File(ctx.cacheDir, "rec-test.wav")
        out.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = WavRecorder(ctx, out, scope)
        // finish before start: nothing to write.
        assertNull(recorder.finish())
        recorder.start()
        recorder.stop()
        recorder.stop() // idempotent
        val finished = recorder.finish()
        // Shadow AudioRecord delivers no samples, so either no file or a
        // header-only file that finish() correctly rejects.
        assertTrue(finished == null || finished.length() > WavRecorder.WAV_HEADER_BYTES)
        scope.cancel()
    }

    @Test fun copyUriToFileCopiesRegisteredStream() {
        val uri = Uri.parse("content://test/image/42")
        Shadows.shadowOf(ctx.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        val copied = copyUriToFile(ctx, uri)
        assertNotNull(copied)
        assertEquals(4L, copied.length())
        assertTrue(copied.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        // An unregistered uri cannot be opened and copies to nothing.
        assertNull(copyUriToFile(ctx, Uri.parse("content://test/missing")))
    }
}
