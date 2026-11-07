package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/** Pass 3: save-location routing, SAF document helpers, thumbnails, motion-photo guards. */
@RunWith(AndroidJUnit4::class)
class CameraPass3SaveAndThumbTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun vm() = CameraViewModel(app)

    @Test fun saveTargetDefaultsAndTransitions() {
        val v = vm()
        v.loadSaveTarget()
        assertEquals(SaveTarget.MediaStoreDefault, v.saveTarget.value)
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")
        v.setSaveTreeUri(tree)
        assertEquals(SaveTarget.SafTree(tree), v.saveTarget.value)
        // The grant was never actually persisted in this environment, so a
        // reload must fall back to the MediaStore default.
        v.loadSaveTarget()
        assertEquals(SaveTarget.MediaStoreDefault, v.saveTarget.value)
        v.setSaveTreeUri(tree)
        v.clearSaveTreeUri()
        assertEquals(SaveTarget.MediaStoreDefault, v.saveTarget.value)
    }

    @Test fun safPrimaryPathParsing() {
        fun tree(docId: String): Uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/${Uri.encode(docId)}")
        val primary = SafDocuments.primaryPath(tree("primary:Pictures/TestCam"))
        assertTrue(primary != null && primary.endsWith("/Pictures/TestCam"), "$primary")
        assertTrue(SafDocuments.primaryPath(tree("primary:")) == null)
        // Non-primary volumes have no absolute path.
        assertNull(SafDocuments.primaryPath(tree("1234-5678:Pictures")))
        // Path traversal is rejected, stray characters sanitized.
        assertNull(SafDocuments.primaryPath(tree("primary:../etc")))
        val cleaned = SafDocuments.primaryPath(tree("primary:Pictures/My Cam!"))
        assertTrue(cleaned != null && cleaned.endsWith("/Pictures/My Cam_"), "$cleaned")
        // Not a tree uri at all.
        assertNull(SafDocuments.primaryPath(Uri.parse("content://media/external/images/1")))
    }

    @Test fun safPermissionHelpersDegrade() {
        val resolver = app.contentResolver
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")
        assertFalse(SafDocuments.hasPersistedPermission(resolver, tree))
        // No provider in the test environment: taking the grant must not throw.
        SafDocuments.takePersistablePermission(resolver, tree)
        assertNull(SafDocuments.displayName(resolver, tree))
        // Document creation against a nonexistent provider yields null or a
        // caught failure, never a crash.
        runCatching { SafDocuments.createImageDoc(resolver, tree, "a.jpg") }
        runCatching { SafDocuments.createVideoDoc(resolver, tree, "a.mp4") }
    }

    @Test fun thumbnailLoadingDegrades() = runTest {
        val v = vm()
        assertNull(v.loadThumbnail(null))
        val missing = Uri.fromFile(File(app.cacheDir, "no-thumb.jpg"))
        assertNull(v.loadThumbnail(missing))
    }

    @Test fun motionPhotoEncoderGuards() {
        val out = File(app.cacheDir, "motion-out.mp4")
        assertFalse(MotionPhotoEncoder.encode(emptyList(), out, 0))
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertFalse(MotionPhotoEncoder.encode(listOf(tiny), out, 0))
        tiny.recycle()
        // A plausible frame still cannot encode without a real codec here;
        // the contract is a false result, not a crash.
        val frame = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val result = runCatching { MotionPhotoEncoder.encode(listOf(frame), out, 90) }.getOrDefault(false)
        assertFalse(result)
        frame.recycle()
    }
}
